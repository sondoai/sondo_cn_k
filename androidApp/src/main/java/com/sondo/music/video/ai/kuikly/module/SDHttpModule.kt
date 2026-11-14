package com.sondo.music.video.ai.kuikly.module

import android.os.SystemClock
import android.webkit.MimeTypeMap
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 通用 HTTP（契约：shared/nativeapi/SDHttpModule.kt）。基于 OkHttp，全程不占用主线程 / Kuikly 线程。
 *
 * - request：任意方法、任意字符串 body；拿到 HTTP 响应（任何状态码）即 ok=true，
 *   只有传输层失败才 ok=false，并带 errorType = timeout | network | cancel | other。
 * - upload / download：常驻回调。先发若干 `{event:"progress"}`（约 10 次/秒），最后恰好一个 `{event:"done"}`。
 *   **done.ok 表示传输成功且状态码为 2xx**（与 request 不同：上传 / 下载「成功」必须是服务端真的收下 / 给出了文件）；
 *   非 2xx 时 ok=false，但 status / headers / body 照常带回，调用方可据此区分 HTTP 错误与传输错误（status=0）。
 * - 响应头 headers：键统一提供小写形式（HTTP/2 本来就是小写），同时保留服务端原始大小写的键；
 *   ETag 额外保证有 `ETag` 这个键，上传的 done 事件还带顶层 `etag`。多值头用 ", " 连接。
 * - timeoutMs：读 / 写的空闲超时（两次数据之间的最长间隔，语义同 Dio 的 receiveTimeout / sendTimeout），
 *   不是整个请求的总时长；连接超时取 min(timeoutMs, 30s)。
 * - cancel(id)：任何页面的模块实例都能取消任意进行中的请求（注册表是进程级的）。
 * - 页面销毁时：它发起的上传 / 下载会被取消（没有人接收进度了）；普通 request 让它跑完，只是不再回调——
 *   已经发出的写操作不应因为用户离开页面而中断。
 */
class SDHttpModule : SDCoreBaseModule() {

    /** 本实例发起的上传 / 下载，页面销毁时取消。 */
    private val ownedTransfers: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun handle(method: String, p: JSONObject, callback: KuiklyRenderCallback?): Any? {
        when (method) {
            "request" -> request(p, callback)
            "cancel" -> cancel(p.optString("id"))
            "upload" -> upload(p, callback)
            "download" -> download(p, callback)
            "probeContentLength" -> probeContentLength(p, callback)
            else -> return unknown(method, callback)
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        for (id in ownedTransfers) calls[id]?.cancel()
        ownedTransfers.clear()
    }

    // ------------------------------------------------------------------ request

    private fun request(p: JSONObject, callback: KuiklyRenderCallback?) {
        val id = idOf(p)
        val call = try {
            val method = p.optString("method", "GET").trim().uppercase().ifEmpty { "GET" }
            val body: String? = if (p.has("body") && !p.isNull("body")) p.optString("body") else null
            val request = Request.Builder()
                .url(p.optString("url").trim())
                .headers(buildHeaders(p.optJSONObject("headers")))
                .method(method, stringBody(method, body))
                .build()
            val timeout = timeoutOf(p, DEFAULT_REQUEST_TIMEOUT_MS)
            client(timeout, timeout).newCall(request)
        } catch (e: Throwable) {
            // URL / 方法非法等：还没发出去
            reply(callback, failure(null, e))
            return
        }
        calls[id] = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                calls.remove(id, call)
                reply(callback, failure(call, e))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { r ->
                        val text = r.body?.string().orEmpty()
                        SDCoreKit.ok()
                            .put("status", r.code)
                            .put("headers", headersJson(r.headers))
                            .put("body", text)
                    }
                } catch (e: Throwable) {
                    // 读 body 途中断开 / 超时 / 被取消
                    failure(call, e)
                } finally {
                    calls.remove(id, call)
                }
                reply(callback, result)
            }
        })
    }

    private fun cancel(id: String) {
        if (id.isEmpty()) return
        calls[id]?.cancel()
    }

    // ------------------------------------------------------------------ upload

    private fun upload(p: JSONObject, callback: KuiklyRenderCallback?) {
        val id = idOf(p)
        val done = DoneOnce(id, callback)
        val progress = Progress(id, "sent", callback)
        val call = try {
            val file = File(SDCoreKit.localPath(p.optString("filePath")))
            if (!file.isFile) throw IllegalArgumentException("file not found: ${file.path}")
            val fileLength = file.length()
            val offset = p.optLong("offset", 0L)
            val length = if (p.has("length") && !p.isNull("length")) p.optLong("length", -1L) else fileLength - offset
            // 分片上传必须字节数精确：区间越界直接报错，不做静默截断
            if (offset < 0 || length < 0 || offset + length > fileLength) {
                throw IllegalArgumentException("range out of file: offset=$offset length=$length size=$fileLength")
            }

            var method = p.optString("method", "PUT").trim().uppercase().ifEmpty { "PUT" }
            if (!permitsBody(method)) method = "PUT"
            val headersJson = p.optJSONObject("headers")
            val headerContentType = headerValue(headersJson, "Content-Type")
            val paramContentType = p.optString("contentType").takeIf { it.isNotEmpty() }

            val headers: Headers
            val payload: RequestBody
            if (p.optBoolean("multipart", false)) {
                val fileName = sanitizeFileName(p.optString("fileName")).ifEmpty { file.name }
                val partType = (paramContentType ?: mimeFromName(fileName) ?: OCTET_STREAM).toMediaTypeOrNull()
                val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
                p.optJSONObject("formFields")?.let { fields ->
                    for (key in fields.keys()) {
                        if (!fields.isNull(key)) builder.addFormDataPart(key, fields.optString(key))
                    }
                }
                builder.addFormDataPart(
                    p.optString("fieldName").ifEmpty { "file" },
                    fileName,
                    FileRangeBody(file, offset, length, partType)
                )
                // multipart 的 Content-Type（含 boundary）由 OkHttp 生成，调用方传的要丢掉
                headers = buildHeaders(headersJson, dropContentType = true)
                payload = builder.build()
            } else {
                // 裸文件体：Content-Type 以 headers 里的为准，其次 contentType 参数，都没有则 application/octet-stream。
                // 作为普通请求头原样发送（body 自身不带类型），预签名 URL 的签名才对得上。
                val contentType = headerContentType ?: paramContentType ?: OCTET_STREAM
                headers = buildHeaders(headersJson, dropContentType = true).newBuilder()
                    .also { addHeader(it, "Content-Type", contentType) }
                    .build()
                payload = FileRangeBody(file, offset, length, null)
            }
            val total = payload.contentLength()
            val request = Request.Builder()
                .url(p.optString("url").trim())
                .headers(headers)
                .method(method, CountingBody(payload) { sent -> progress.update(sent, total) })
                .build()
            val timeout = timeoutOf(p, DEFAULT_UPLOAD_TIMEOUT_MS)
            client(timeout, timeout).newCall(request)
        } catch (e: Throwable) {
            done.emit(failure(null, e).put("status", 0))
            return
        }
        calls[id] = call
        ownedTransfers.add(id)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                release(id, call)
                done.emit(failure(call, e).put("status", 0))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { r ->
                        val text = r.body?.string().orEmpty()
                        val ok = r.isSuccessful
                        if (ok) progress.finish(call.request().body?.contentLength() ?: -1L)
                        val json = JSONObject()
                            .put("ok", ok)
                            .put("status", r.code)
                            .put("headers", headersJson(r.headers))
                            .put("body", text)
                        r.header("ETag")?.let { json.put("etag", it) }
                        if (!ok) json.put("error", "HTTP ${r.code}")
                        json
                    }
                } catch (e: Throwable) {
                    failure(call, e).put("status", 0)
                } finally {
                    release(id, call)
                }
                done.emit(result)
            }
        })
    }

    // ------------------------------------------------------------------ download

    private fun download(p: JSONObject, callback: KuiklyRenderCallback?) {
        val id = idOf(p)
        val done = DoneOnce(id, callback)
        val progress = Progress(id, "received", callback)
        val url = p.optString("url").trim()
        val call = try {
            var headers = buildHeaders(p.optJSONObject("headers"))
            // 不要透明 gzip：否则拿不到 Content-Length，进度没有总量
            if (headers["Accept-Encoding"] == null) {
                headers = headers.newBuilder().add("Accept-Encoding", "identity").build()
            }
            val request = Request.Builder().url(url).headers(headers).get().build()
            val timeout = timeoutOf(p, DEFAULT_DOWNLOAD_TIMEOUT_MS)
            client(timeout, timeout).newCall(request)
        } catch (e: Throwable) {
            done.emit(failure(null, e).put("status", 0))
            return
        }
        calls[id] = call
        ownedTransfers.add(id)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                release(id, call)
                done.emit(failure(call, e).put("status", 0))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { r -> saveResponse(p, url, call, r, progress) }
                } catch (e: Throwable) {
                    failure(call, e).put("status", response.code)
                } finally {
                    release(id, call)
                }
                done.emit(result)
            }
        })
    }

    /** 写入 `<目标>.<随机>.tmp`，完整收完后原子改名；任何失败都清掉临时文件，目标文件不会出现半截内容。 */
    private fun saveResponse(p: JSONObject, url: String, call: Call, r: Response, progress: Progress): JSONObject {
        if (!r.isSuccessful) {
            return JSONObject().put("ok", false).put("status", r.code).put("error", "HTTP ${r.code}")
        }
        val body = r.body ?: throw IOException("empty response body")
        val dest = resolveDestination(p, url, r)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, "${dest.name}.${System.nanoTime()}.tmp")
        val total = body.contentLength()
        var received = 0L
        try {
            body.byteStream().use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        received += n
                        progress.update(received, total)
                    }
                    output.fd.sync()
                }
            }
            if (call.isCanceled()) throw IOException("Canceled")
            if (total >= 0 && received != total) throw IOException("incomplete download: $received / $total")
            if (dest.exists() && !dest.delete()) throw IOException("cannot replace ${dest.path}")
            if (!tmp.renameTo(dest)) throw IOException("cannot move download into place: ${dest.path}")
        } finally {
            tmp.delete()
        }
        progress.finish(received, if (total >= 0) total else received)
        return SDCoreKit.ok()
            .put("status", r.code)
            .put("path", dest.absolutePath)
            .put("bytes", received)
    }

    /**
     * 保存位置：
     * - savePath 是文件路径 → 就存到这里；
     * - savePath 是已存在的目录或以 `/` 结尾 → 目录 + 文件名；
     * - savePath 为空 → `<cacheDir>/sd_download/` + 文件名。
     * 文件名取 fileName；为空时用 `md5(url).<扩展名>`（与参考工程的下载缓存命名一致）。
     */
    private fun resolveDestination(p: JSONObject, url: String, r: Response): File {
        val savePath = SDCoreKit.localPath(p.optString("savePath"))
        val named = sanitizeFileName(p.optString("fileName"))
        fun defaultName(): String = named.ifEmpty { md5Hex(url) + extensionOf(url, r) }
        if (savePath.isEmpty()) return File(File(appContext.cacheDir, "sd_download"), defaultName())
        val target = File(savePath)
        return if (savePath.endsWith("/") || target.isDirectory) File(target, defaultName()) else target
    }

    private fun extensionOf(url: String, r: Response): String {
        val path = url.substringBefore('#').substringBefore('?')
        val ext = path.substringAfterLast('/', "").substringAfterLast('.', "")
        if (ext.isNotEmpty() && ext.length <= 5 && ext.all { it.isLetterOrDigit() }) return ".${ext.lowercase()}"
        val mime = r.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
        val fromMime = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return if (fromMime.isNullOrEmpty()) "" else ".$fromMime"
    }

    // ------------------------------------------------------------------ probeContentLength

    /** GET `Range: bytes=0-0` 探测资源大小（整体 5s 超时），不下载内容。 */
    private fun probeContentLength(p: JSONObject, callback: KuiklyRenderCallback?) {
        val call = try {
            val headers = buildHeaders(p.optJSONObject("headers")).newBuilder().set("Range", "bytes=0-0").build()
            val request = Request.Builder().url(p.optString("url").trim()).headers(headers).get().build()
            baseClient.newBuilder()
                .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request)
        } catch (e: Throwable) {
            reply(callback, failure(null, e).put("length", -1L))
            return
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                reply(callback, failure(call, e).put("length", -1L))
            }

            override fun onResponse(call: Call, response: Response) {
                val length = try {
                    response.use { r ->
                        when {
                            // 206：Content-Range: bytes 0-0/12345
                            r.code == 206 -> r.header("Content-Range")
                                ?.substringAfterLast('/', "")?.trim()?.toLongOrNull() ?: -1L
                            // 服务端不支持 Range，直接回了整个资源：Content-Length 就是总大小（不读 body，关闭即丢弃连接）
                            r.isSuccessful -> r.header("Content-Length")?.trim()?.toLongOrNull() ?: -1L
                            else -> -1L
                        }
                    }
                } catch (e: Throwable) {
                    -1L
                }
                val result = JSONObject().put("ok", length >= 0).put("length", length)
                if (length < 0) result.put("error", "content length unknown")
                reply(callback, result)
            }
        })
    }

    // ------------------------------------------------------------------ 事件发射

    /** 进度节流：两次进度至少间隔 [PROGRESS_INTERVAL_MS]。 */
    private inner class Progress(val id: String, val key: String, val callback: KuiklyRenderCallback?) {
        private var lastAt = 0L
        private var lastValue = -1L

        fun update(value: Long, total: Long) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAt < PROGRESS_INTERVAL_MS || value == lastValue) return
            emit(value, total, now)
        }

        /** 结束前补发一次 100%，保证调用方最后看到的进度是满的。 */
        fun finish(value: Long, total: Long = value) {
            if (value < 0 || value == lastValue) return
            emit(value, total, SystemClock.elapsedRealtime())
        }

        private fun emit(value: Long, total: Long, now: Long) {
            lastAt = now
            lastValue = value
            reply(callback, JSONObject().put("event", "progress").put("id", id).put(key, value).put("total", total))
        }
    }

    /** 保证 done 事件恰好发一次。 */
    private inner class DoneOnce(val id: String, val callback: KuiklyRenderCallback?) {
        private val emitted = AtomicBoolean(false)

        fun emit(result: JSONObject) {
            if (!emitted.compareAndSet(false, true)) return
            reply(callback, result.put("event", "done").put("id", id))
        }
    }

    private fun release(id: String, call: Call) {
        calls.remove(id, call)
        ownedTransfers.remove(id)
    }

    // ------------------------------------------------------------------ request bodies

    /** 文件的 [offset, offset + length) 区间；Content-Length 精确等于 length，不使用 chunked 编码。 */
    private class FileRangeBody(
        private val file: File,
        private val offset: Long,
        private val length: Long,
        private val type: MediaType?,
    ) : RequestBody() {
        override fun contentType(): MediaType? = type
        override fun contentLength(): Long = length

        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buf = ByteArray(BUFFER)
                var remaining = length
                while (remaining > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) throw IOException("file truncated while uploading: ${file.path}")
                    sink.write(buf, 0, n)
                    remaining -= n
                }
            }
        }
    }

    /** 统计已写出的字节数（裸文件体与 multipart 共用）。OkHttp 重试时 writeTo 会再走一遍，计数随之从头开始。 */
    private class CountingBody(
        private val delegate: RequestBody,
        private val onProgress: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()

        override fun writeTo(sink: BufferedSink) {
            var written = 0L
            val counting = object : ForwardingSink(sink) {
                override fun write(source: Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    written += byteCount
                    onProgress(written)
                }
            }
            val buffered = counting.buffer()
            delegate.writeTo(buffered)
            buffered.flush()
        }
    }

    companion object {
        const val MODULE_NAME = "SDHttpModule"

        private const val BUFFER = 64 * 1024
        private const val OCTET_STREAM = "application/octet-stream"
        private const val PROGRESS_INTERVAL_MS = 100L
        private const val MAX_CONNECT_TIMEOUT_MS = 30_000L
        private const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L
        private const val DEFAULT_UPLOAD_TIMEOUT_MS = 120_000L
        private const val DEFAULT_DOWNLOAD_TIMEOUT_MS = 300_000L
        private const val PROBE_TIMEOUT_MS = 5_000L

        /** 进行中的请求（进程级）：id → Call。 */
        private val calls = ConcurrentHashMap<String, Call>()

        private val baseClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                // 默认每个域名 5 个并发：分片上传（3 路 × 2 个任务）会把同域名的其它请求堵住
                .dispatcher(Dispatcher().apply {
                    maxRequests = 64
                    maxRequestsPerHost = 16
                })
                .connectTimeout(MAX_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(DEFAULT_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(DEFAULT_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
        }

        /** 共享连接池 / 线程池，只覆盖超时。 */
        private fun client(readMs: Long, writeMs: Long): OkHttpClient =
            baseClient.newBuilder()
                .connectTimeout(minOf(readMs, MAX_CONNECT_TIMEOUT_MS), TimeUnit.MILLISECONDS)
                .readTimeout(readMs, TimeUnit.MILLISECONDS)
                .writeTimeout(writeMs, TimeUnit.MILLISECONDS)
                .build()

        private fun idOf(p: JSONObject): String = p.optString("id").ifEmpty { "auto-" + UUID.randomUUID() }

        private fun timeoutOf(p: JSONObject, fallback: Long): Long {
            val t = p.optLong("timeoutMs", 0L)
            return if (t > 0) t else fallback
        }

        private fun permitsBody(method: String): Boolean = method != "GET" && method != "HEAD"

        private fun requiresBody(method: String): Boolean =
            method == "POST" || method == "PUT" || method == "PATCH" || method == "PROPPATCH" || method == "REPORT"

        /**
         * 字符串 body 按 UTF-8 原样发送，不让 OkHttp 改写 Content-Type（它会给文本类型追加 charset），
         * 请求头里写什么就发什么。
         */
        private fun stringBody(method: String, body: String?): RequestBody? {
            if (!permitsBody(method)) return null
            if (body != null) return body.toByteArray(Charsets.UTF_8).toRequestBody(null)
            return if (requiresBody(method)) ByteArray(0).toRequestBody(null) else null
        }

        /**
         * 构造请求头。非法的头名 / 值跳过而不是让整个请求失败；值里带非 ASCII 字符时按原样发送。
         * Content-Length / Transfer-Encoding 由 OkHttp 根据 body 生成，调用方传的忽略。
         */
        private fun buildHeaders(json: JSONObject?, dropContentType: Boolean = false): Headers {
            val builder = Headers.Builder()
            if (json == null) return builder.build()
            for (name in json.keys()) {
                if (json.isNull(name)) continue
                val lower = name.lowercase()
                if (lower == "content-length" || lower == "transfer-encoding") continue
                if (dropContentType && lower == "content-type") continue
                addHeader(builder, name, json.optString(name))
            }
            return builder.build()
        }

        private fun addHeader(builder: Headers.Builder, name: String, value: String) {
            try {
                builder.add(name, value)
            } catch (e: IllegalArgumentException) {
                try {
                    builder.addUnsafeNonAscii(name, value)
                } catch (e2: IllegalArgumentException) {
                    SDCoreKit.logE("skip invalid header: $name", null)
                }
            }
        }

        private fun headerValue(json: JSONObject?, name: String): String? {
            if (json == null) return null
            for (key in json.keys()) {
                if (key.equals(name, ignoreCase = true) && !json.isNull(key)) {
                    return json.optString(key).takeIf { it.isNotEmpty() }
                }
            }
            return null
        }

        private fun headersJson(headers: Headers): JSONObject {
            val json = JSONObject()
            for (name in headers.names()) {
                val value = headers.values(name).joinToString(", ")
                val lower = name.lowercase()
                json.put(lower, value)
                // 保留服务端原始大小写（names() 按不区分大小写去重，取到的是首次出现的写法）
                for (i in 0 until headers.size) {
                    val original = headers.name(i)
                    if (original != lower && original.equals(name, ignoreCase = true)) json.put(original, value)
                }
                if (lower == "etag") json.put("ETag", value)
            }
            return json
        }

        /** 传输层失败 → {ok:false, error, errorType}。 */
        private fun failure(call: Call?, e: Throwable): JSONObject {
            val type = when {
                // 整体超时（callTimeout）在 OkHttp 内部也是通过 cancel 实现的，要先于 isCanceled 判断
                e is SocketTimeoutException -> "timeout"
                e is InterruptedIOException && e.message == "timeout" -> "timeout"
                call?.isCanceled() == true -> "cancel"
                e is IOException && e.message == "Canceled" -> "cancel"
                e is InterruptedIOException -> "timeout"
                e is IOException -> "network"
                else -> "other"
            }
            return SDCoreKit.fail(if (type == "cancel") "cancelled" else SDCoreKit.errorText(e)).put("errorType", type)
        }

        private fun sanitizeFileName(name: String): String =
            name.trim().substringAfterLast('/').substringAfterLast('\\').takeIf { it != "." && it != ".." }.orEmpty()

        private fun mimeFromName(name: String): String? {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) return null
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        }

        private fun md5Hex(text: String): String =
            SDCoreKit.hex(MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)))
    }
}
