package com.sondo.app.core.utils

import com.sondo.app.nativeapi.ok
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class DownloadException(message: String) : Exception(message)

/**
 * 公网 CDN / OSS 资源下载（不带业务鉴权头），含参考工程的本地缓存规则：
 * - 缓存文件 `<tmp>/<md5(url)>.<ext>`，下载中写 `<同名>.tmp`，完整且校验通过后才改名；
 * - 媒体缓存有效 = 存在、非空、能探测到时长 > 0（原 ffprobe，这里用 SDMediaProcessModule.probe）；
 *   无效的缓存删除后重新下载；
 * - 失败重试最多 5 次，每次间隔 2000ms；单次接收超时 5 分钟。
 * 所有下载在宿主页面上执行（[onHost]）：业务页关闭不影响下载本身，调用方在自己的页面上下文里恢复。
 */
object CdnDownload {
    private const val RECEIVE_TIMEOUT_MS = 5 * 60 * 1000
    private const val CDN_TIMEOUT_MS = 70 * 1000
    private const val RETRY_DELAY_MS = 2000

    fun isHttpUrl(path: String): Boolean = path.startsWith("http://") || path.startsWith("https://")

    /** URL 路径里的扩展名（小写，不含点）；没有则 [fallback]。 */
    fun mediaExt(path: String, fallback: String): String {
        val clean = path.substringBefore('?').substringBefore('#')
        val name = clean.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return fallback
        return name.substring(dot + 1).lowercase()
    }

    fun cachePathFor(url: String, ext: String): String = "${HostNative.tempDir()}/${HostNative.md5(url)}.$ext"

    /** 校验本地媒体文件是否可用（存在、非空、可读且时长 > 0）。 */
    suspend fun isValidCachedMediaFile(path: String): Boolean = onHost { isValidCachedMediaFileImpl(path) }

    private suspend fun isValidCachedMediaFileImpl(path: String): Boolean {
        val file = HostNative.file
        if (file.size(path) <= 0L) return false
        val info = HostNative.mediaProcess.probe(path)
        return info.ok() && info.optLong("durationMs", 0L) > 0L
    }

    /** [url] 对应的本地缓存路径（文件存在且有效时），否则 null。 */
    suspend fun cachedFilePathIfExists(url: String, ext: String): String? = onHost { cachedFilePathIfExistsImpl(url, ext) }

    private suspend fun cachedFilePathIfExistsImpl(url: String, ext: String): String? {
        val savePath = cachePathFor(url, ext)
        if (HostNative.file.exists(savePath) && isValidCachedMediaFileImpl(savePath)) return savePath
        return null
    }

    /**
     * 下载带缓存和重试（媒体文件）。失败抛 [DownloadException]，取消抛 [CancelledException]。
     * [url] 为本地路径 / file:// 时直接校验后返回。
     */
    suspend fun downloadWithCache(
        url: String,
        ext: String,
        maxRetry: Int = 5,
        progressCallback: ProgressCallback? = null,
        cancelToken: CancelToken? = null,
    ): String = onHost { downloadWithCacheImpl(url, ext, maxRetry, progressCallback, cancelToken) }

    private suspend fun downloadWithCacheImpl(
        url: String,
        ext: String,
        maxRetry: Int,
        progressCallback: ProgressCallback?,
        cancelToken: CancelToken?,
    ): String {
        checkCancelled(cancelToken)
        val localPath = resolveLocalMediaPath(url)
        if (localPath != null) {
            if (isValidCachedMediaFileImpl(localPath)) {
                progressCallback?.invoke("cache_hit", 1.0)
                return localPath
            }
            throw DownloadException("本地媒体文件无效: $localPath")
        }

        val file = HostNative.file
        val savePath = cachePathFor(url, ext)
        val tempPath = "$savePath.tmp"
        if (file.exists(savePath)) {
            if (isValidCachedMediaFileImpl(savePath)) {
                progressCallback?.invoke("cache_hit", 1.0)
                return savePath
            }
            DevLog.logger("downloadWithCache invalid cache, re-download: $url")
            file.delete(savePath)
        }

        var attempt = 0
        while (attempt < maxRetry) {
            checkCancelled(cancelToken)
            // 每次重试前清理临时文件
            if (file.exists(tempPath)) file.delete(tempPath)
            val ok = downloadTo(url, tempPath, RECEIVE_TIMEOUT_MS, cancelToken) { received, total ->
                if (cancelToken?.isCancelled != true && total > 0L) {
                    progressCallback?.invoke("download", received.toDouble() / total)
                }
            }
            checkCancelledAfter(cancelToken, tempPath)
            if (ok && file.size(tempPath) > 0L && isValidCachedMediaFileImpl(tempPath)) {
                // 下载完整后，再替换正式缓存文件
                if (file.exists(savePath)) file.delete(savePath)
                if (file.rename(tempPath, savePath)) return savePath
            }
            // 下载失败时必须删除临时文件，避免下次误用
            if (file.exists(tempPath)) file.delete(tempPath)
            checkCancelled(cancelToken)
            attempt++
            if (attempt < maxRetry) {
                HostNative.sleep(RETRY_DELAY_MS)
            } else {
                throw DownloadException("下载失败超过 $attempt 次: $url")
            }
        }
        throw DownloadException("下载失败: $url")
    }

    /** 下载静态图到临时目录（同样的缓存命名，不做时长校验）。失败抛 [DownloadException]。 */
    suspend fun downloadStillFile(url: String, ext: String): String = onHost { downloadStillFileImpl(url, ext) }

    private suspend fun downloadStillFileImpl(url: String, ext: String): String {
        val file = HostNative.file
        val savePath = cachePathFor(url, ext)
        if (file.size(savePath) > 0L) return savePath
        // 先写入临时文件，避免中断后把不完整内容当成缓存。
        val tempPath = "$savePath.tmp"
        if (file.exists(tempPath)) file.delete(tempPath)
        val ok = downloadTo(url, tempPath, RECEIVE_TIMEOUT_MS, null, null)
        if (!ok || file.size(tempPath) <= 0L) {
            if (file.exists(tempPath)) file.delete(tempPath)
            throw DownloadException("下载图片失败: $url")
        }
        if (file.exists(savePath)) file.delete(savePath)
        if (!file.rename(tempPath, savePath)) {
            file.delete(tempPath)
            throw DownloadException("下载图片失败: $url")
        }
        return savePath
    }

    /**
     * 按 URL 拉取到临时文件（对应 CdnDownloadClient.downloadBytes：70s 超时、不缓存、不重试）。
     * 返回本地路径；失败或空文件返回 null。调用方用完自行删除。
     */
    suspend fun downloadFile(url: String, ext: String = mediaExt(url, "bin")): String? = onHost { downloadFileImpl(url, ext) }

    private suspend fun downloadFileImpl(url: String, ext: String = mediaExt(url, "bin")): String? {
        val path = "${HostNative.tempDir()}/cdn_${HostNative.uniqueStamp()}.$ext"
        val ok = downloadTo(url, path, CDN_TIMEOUT_MS, null, null)
        if (!ok || HostNative.file.size(path) <= 0L) {
            HostNative.file.delete(path)
            return null
        }
        return path
    }

    /** 单次下载到 [savePath]。返回是否成功（2xx 且写盘完成）。 */
    suspend fun downloadTo(
        url: String,
        savePath: String,
        timeoutMs: Int,
        cancelToken: CancelToken?,
        onProgress: ((received: Long, total: Long) -> Unit)?,
    ): Boolean {
        if (cancelToken?.isCancelled == true) return false
        val id = Uuid.v4()
        val params = JSONObject().put("id", id).put("url", url).put("savePath", savePath).put("timeoutMs", timeoutMs)
        cancelToken?.track(id)
        val http = HostNative.http
        val done: JSONObject = suspendCoroutine { cont ->
            var finished = false
            var ref: String? = null
            ref = http.download(params) { e ->
                when (e.optString("event", "")) {
                    "progress" -> if (!finished) onProgress?.invoke(e.optLong("received", 0L), e.optLong("total", 0L))
                    "done" -> if (!finished) {
                        finished = true
                        // 常驻回调用完即注销
                        ref?.let { http.removeCallback(it) }
                        cont.resume(e)
                    }
                }
            }
        }
        cancelToken?.untrack(id)
        val status = done.optInt("status", 0)
        return done.ok() && (status == 0 || status in 200..299)
    }

    private fun resolveLocalMediaPath(url: String): String? {
        if (url.startsWith("file://")) return url.removePrefix("file://")
        if (!isHttpUrl(url)) return url
        return null
    }

    private fun checkCancelled(token: CancelToken?) {
        if (token?.isCancelled == true) throw CancelledException("下载已取消")
    }

    private suspend fun checkCancelledAfter(token: CancelToken?, tempPath: String) {
        if (token?.isCancelled != true) return
        if (HostNative.file.exists(tempPath)) HostNative.file.delete(tempPath)
        throw CancelledException("下载已取消")
    }
}
