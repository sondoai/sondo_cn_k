package com.sondo.music.video.ai.kuikly.module

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 视频磁盘缓存（SDVideoView 与 SDVideoCacheModule 共用同一份）。
 *
 * 策略与参考工程的 `sondo_video_player_cache` 一致：
 * - 只有体积 ≤ 20MB 的资源才落盘：先发 `Range: bytes=0-0` 探测总长度（5s 超时），探测失败按不可缓存处理；
 * - 最多 300 个对象，超过按最近最少使用淘汰；69 天未使用的对象清除；
 * - HLS（m3u8）不缓存。
 *
 * 实现：Media3 [SimpleCache] + [CacheDataSource]。探测与播放共用一个 OkHttpClient（连接复用，
 * 探测几乎不增加首帧耗时）。SimpleCache 的构造会读磁盘索引，只能在非主线程触发。
 */
@OptIn(markerClass = [UnstableApi::class])
internal object SDVideoCache {

    private const val TAG = "SDVideoCache"
    private const val CACHE_DIR = "sondo_video_player_cache"
    private const val EXPORT_DIR = "sondo_video_player_files"

    const val MAX_CACHEABLE_BYTES = 20L * 1024 * 1024
    private const val MAX_OBJECTS = 300
    private val MAX_AGE_MS = TimeUnit.DAYS.toMillis(69)
    private const val PROBE_TIMEOUT_SECONDS = 5L
    private const val PROBE_RETRY_AFTER_MS = 60_000L

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val probeClient: OkHttpClient by lazy {
        httpClient.newBuilder().callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    }

    private val lock = Any()
    @Volatile
    private var simpleCache: SimpleCache? = null
    private val evictor = LruCountEvictor()

    /** url → 资源总字节数（探测成功的结果，进程内长期有效）。 */
    private val knownSizes = ConcurrentHashMap<String, Long>()
    private val failedProbes = ConcurrentHashMap<String, Long>()
    private val runningProbes = ConcurrentHashMap<String, FutureTask<Long>>()

    private class PrefetchTask(val url: String) {
        val callbacks = ArrayList<(Map<String, Any>) -> Unit>()
        @Volatile
        var writer: CacheWriter? = null
        val cancelled = AtomicBoolean(false)
    }

    private val prefetchTasks = HashMap<String, PrefetchTask>()

    // ---------------------------------------------------------------------------------
    // 缓存实例
    // ---------------------------------------------------------------------------------

    /** 取得缓存实例。会做磁盘 IO，禁止在主线程调用。 */
    fun cache(context: Context): SimpleCache {
        simpleCache?.let { return it }
        synchronized(lock) {
            simpleCache?.let { return it }
            val app = context.applicationContext
            appContextForTrim = app
            val dir = File(app.cacheDir, CACHE_DIR)
            val created = SimpleCache(dir, evictor, StandaloneDatabaseProvider(app))
            simpleCache = created
            evictor.ready = true
            SDMediaKit.io.execute { trim(app) }
            return created
        }
    }

    private fun exportDir(context: Context): File = SDMediaKit.tempDir(context.applicationContext, EXPORT_DIR)

    // ---------------------------------------------------------------------------------
    // 播放用 DataSource
    // ---------------------------------------------------------------------------------

    /** 不带缓存的数据源（本地文件 / asset / content / http）。 */
    fun upstreamFactory(context: Context): DataSource.Factory {
        val app = context.applicationContext
        return DefaultDataSource.Factory(app, OkHttpDataSource.Factory(httpClient))
    }

    /** 播放用数据源：[useCache] 为 true 时按体积门限决定是否走磁盘缓存。 */
    fun playbackFactory(context: Context, useCache: Boolean): DataSource.Factory {
        val upstream = upstreamFactory(context)
        if (!useCache) return upstream
        val app = context.applicationContext
        return DataSource.Factory { SizeGatedDataSource(app, upstream) }
    }

    private fun cacheDataSourceFactory(context: Context, upstream: DataSource.Factory?): CacheDataSource.Factory {
        val cache = cache(context)
        val factory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        if (upstream != null) {
            // 不分片：顺序下载完的资源在磁盘上就是一个完整文件，resolve 时可以直接给出路径
            factory.setCacheWriteDataSinkFactory(
                CacheDataSink.Factory().setCache(cache).setFragmentSize(C.LENGTH_UNSET.toLong())
            )
        } else {
            factory.setCacheWriteDataSinkFactory(null)
        }
        return factory
    }

    /**
     * 在 open 时才决定走缓存还是直连：open 运行在 ExoPlayer 的加载线程，可以阻塞等待体积探测。
     */
    private class SizeGatedDataSource(
        private val context: Context,
        private val upstreamFactory: DataSource.Factory,
    ) : DataSource {

        private val transferListeners = ArrayList<TransferListener>()
        private var active: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            transferListeners.add(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val scheme = dataSpec.uri.scheme?.lowercase()
            val url = dataSpec.uri.toString()
            val useCache = (scheme == "http" || scheme == "https") && try {
                isCacheable(context, url)
            } catch (e: Throwable) {
                false
            }
            val source: DataSource = if (useCache) {
                try {
                    cacheDataSourceFactory(context, upstreamFactory).createDataSource()
                } catch (e: Throwable) {
                    Log.w(TAG, "cache unavailable, fallback to network", e)
                    upstreamFactory.createDataSource()
                }
            } else {
                upstreamFactory.createDataSource()
            }
            for (l in transferListeners) source.addTransferListener(l)
            active = source
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val source = active ?: throw IllegalStateException("data source not opened")
            return source.read(buffer, offset, length)
        }

        override fun getUri(): Uri? = active?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

        override fun close() {
            val source = active
            active = null
            source?.close()
        }
    }

    // ---------------------------------------------------------------------------------
    // 体积探测
    // ---------------------------------------------------------------------------------

    fun isHls(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".m3u8")
    }

    /** 提前在后台发起探测，播放器真正 open 时大概率已有结果。 */
    fun warmUp(url: String) {
        if (!SDMediaKit.isHttp(url) || isHls(url)) return
        if (knownSizes.containsKey(url) || recentlyFailed(url)) return
        probeFuture(url)
    }

    /** 是否允许落盘（阻塞，最长约 5s；非主线程调用）。 */
    fun isCacheable(context: Context, url: String): Boolean {
        if (!SDMediaKit.isHttp(url) || isHls(url)) return false
        val known = knownSizes[url] ?: cachedContentLength(context, url)?.also { knownSizes[url] = it }
        if (known != null) return known in 1..MAX_CACHEABLE_BYTES
        if (recentlyFailed(url)) return false
        val size = try {
            probeFuture(url).get(PROBE_TIMEOUT_SECONDS + 1, TimeUnit.SECONDS)
        } catch (e: Throwable) {
            -1L
        }
        return size in 1..MAX_CACHEABLE_BYTES
    }

    private fun recentlyFailed(url: String): Boolean {
        val at = failedProbes[url] ?: return false
        if (SystemClock.elapsedRealtime() - at < PROBE_RETRY_AFTER_MS) return true
        failedProbes.remove(url)
        return false
    }

    private fun probeFuture(url: String): FutureTask<Long> {
        runningProbes[url]?.let { return it }
        val task = FutureTask {
            val size = probeContentLength(url)
            if (size > 0) knownSizes[url] = size else failedProbes[url] = SystemClock.elapsedRealtime()
            runningProbes.remove(url)
            size
        }
        val existing = runningProbes.putIfAbsent(url, task)
        if (existing != null) return existing
        SDMediaKit.io.execute(task)
        return task
    }

    /** 只请求首字节，从 Content-Range 读总长度；拿到响应头立即断开。 */
    private fun probeContentLength(url: String): Long {
        return try {
            val request = Request.Builder().url(url).header("Range", "bytes=0-0").get().build()
            probeClient.newCall(request).execute().use { resp ->
                when (resp.code) {
                    206 -> resp.header("Content-Range")
                        ?.substringAfterLast('/')
                        ?.trim()
                        ?.toLongOrNull() ?: -1L
                    200 -> resp.body?.contentLength()?.takeIf { it >= 0 } ?: -1L
                    else -> -1L
                }
            }
        } catch (e: Throwable) {
            -1L
        }
    }

    private fun cachedContentLength(context: Context, url: String): Long? {
        return try {
            val len = ContentMetadata.getContentLength(cache(context).getContentMetadata(url))
            if (len > 0) len else null
        } catch (e: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------------------------
    // resolve / prefetch / clear（均在 IO 线程执行，结果回调在 IO 线程）
    // ---------------------------------------------------------------------------------

    /** 资源已完整缓存时返回本地文件路径，否则 null。 */
    fun resolve(context: Context, url: String): String? {
        if (!SDMediaKit.isHttp(url)) return SDMediaKit.localPath(url)?.takeIf { File(it).exists() }
        return try {
            val cache = cache(context)
            val length = fullyCachedLength(cache, url)
            if (length <= 0) null else materialize(context, cache, url, length)
        } catch (e: Throwable) {
            Log.w(TAG, "resolve failed", e)
            null
        }
    }

    fun prefetch(context: Context, url: String, callback: (Map<String, Any>) -> Unit) {
        if (!SDMediaKit.isHttp(url)) {
            callback(SDMediaKit.fail("invalid_url"))
            return
        }
        val task: PrefetchTask
        synchronized(prefetchTasks) {
            val existing = prefetchTasks[url]
            if (existing != null) {
                // 同一个地址只保留一个下载任务
                existing.callbacks.add(callback)
                return
            }
            task = PrefetchTask(url)
            task.callbacks.add(callback)
            prefetchTasks[url] = task
        }
        val app = context.applicationContext
        SDMediaKit.io.execute {
            val result = runPrefetch(app, task)
            val callbacks = synchronized(prefetchTasks) {
                prefetchTasks.remove(url)
                ArrayList(task.callbacks)
            }
            for (cb in callbacks) {
                try {
                    cb(result)
                } catch (e: Throwable) {
                    // ignore
                }
            }
        }
    }

    private fun runPrefetch(context: Context, task: PrefetchTask): Map<String, Any> {
        val url = task.url
        return try {
            resolve(context, url)?.let { return SDMediaKit.ok("localPath" to it) }
            if (task.cancelled.get()) return SDMediaKit.cancelled()
            if (!isCacheable(context, url)) {
                return SDMediaKit.fail("not_cacheable", "size" to knownSizes[url])
            }
            if (task.cancelled.get()) return SDMediaKit.cancelled()
            val dataSource = cacheDataSourceFactory(context, upstreamFactory(context)).createDataSourceForDownloading()
            val writer = CacheWriter(dataSource, DataSpec(Uri.parse(url)), null, null)
            task.writer = writer
            if (task.cancelled.get()) return SDMediaKit.cancelled()
            writer.cache()
            trim(context)
            val path = resolve(context, url)
            if (path != null) SDMediaKit.ok("localPath" to path) else SDMediaKit.fail("cache_incomplete")
        } catch (e: Throwable) {
            if (task.cancelled.get()) SDMediaKit.cancelled() else SDMediaKit.fail(SDMediaKit.errorText(e))
        }
    }

    fun cancelPrefetch(url: String) {
        val task = synchronized(prefetchTasks) { prefetchTasks[url] } ?: return
        task.cancelled.set(true)
        task.writer?.cancel()
    }

    /** 清空全部缓存（进行中的预取一并取消）。 */
    fun clear(context: Context): Boolean {
        val tasks = synchronized(prefetchTasks) { ArrayList(prefetchTasks.values) }
        for (t in tasks) {
            t.cancelled.set(true)
            t.writer?.cancel()
        }
        var ok = true
        try {
            val cache = cache(context)
            for (key in ArrayList(cache.keys)) {
                try {
                    cache.removeResource(key)
                } catch (e: Throwable) {
                    ok = false
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "clear failed", e)
            ok = false
        }
        try {
            exportDir(context).listFiles()?.forEach { it.delete() }
        } catch (e: Throwable) {
            ok = false
        }
        return ok
    }

    private fun fullyCachedLength(cache: Cache, key: String): Long {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (length <= 0) return -1L
        return if (cache.isCached(key, 0, length)) length else -1L
    }

    /**
     * 给出一份完整的本地文件：
     * - 资源只有一个覆盖全长的分片时，直接返回缓存文件本身（零拷贝；文件名是缓存内部命名，内容是原始字节）；
     * - 否则（边播边存产生了多个分片）合并导出一份到导出目录。
     */
    private fun materialize(context: Context, cache: SimpleCache, url: String, length: Long): String? {
        val spans = cache.getCachedSpans(url)
        if (spans.size == 1) {
            val span = spans.first()
            val file = span.file
            if (span.isCached && span.position == 0L && span.length == length && file != null && file.length() == length) {
                evictor.touchKey(url)
                return file.absolutePath
            }
        }
        val ext = SDMediaKit.extensionOf(url).ifEmpty { "mp4" }
        val out = File(exportDir(context), "${sha1(url)}.$ext")
        if (out.exists() && out.length() == length) {
            out.setLastModified(System.currentTimeMillis())
            return out.absolutePath
        }
        val tmp = File(out.parentFile, out.name + ".part")
        val source = cacheDataSourceFactory(context, null).createDataSource()
        try {
            source.open(DataSpec(Uri.parse(url)))
            FileOutputStream(tmp).use { os ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = source.read(buffer, 0, buffer.size)
                    if (n == C.RESULT_END_OF_INPUT) break
                    os.write(buffer, 0, n)
                    total += n
                }
                if (total != length) throw IllegalStateException("cache export length mismatch")
            }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) throw IllegalStateException("rename failed")
            return out.absolutePath
        } catch (e: Throwable) {
            Log.w(TAG, "export failed", e)
            tmp.delete()
            return null
        } finally {
            try {
                source.close()
            } catch (e: Throwable) {
                // ignore
            }
        }
    }

    private fun sha1(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    // ---------------------------------------------------------------------------------
    // 淘汰：最多 300 个对象 / 69 天
    // ---------------------------------------------------------------------------------

    private val trimming = AtomicBoolean(false)

    private fun trim(context: Context) {
        if (!trimming.compareAndSet(false, true)) return
        try {
            val cache = simpleCache ?: return
            val now = System.currentTimeMillis()
            val victims = evictor.victims(now, MAX_OBJECTS, MAX_AGE_MS)
            val exports = exportDir(context)
            for (key in victims) {
                try {
                    cache.removeResource(key)
                    evictor.forget(key)
                    val prefix = sha1(key) + "."
                    exports.listFiles()?.forEach { if (it.name.startsWith(prefix)) it.delete() }
                } catch (e: Throwable) {
                    Log.w(TAG, "evict failed: $key", e)
                }
            }
            // 导出目录里过期的合并文件
            exports.listFiles()?.forEach { if (now - it.lastModified() > MAX_AGE_MS) it.delete() }
        } catch (e: Throwable) {
            Log.w(TAG, "trim failed", e)
        } finally {
            trimming.set(false)
        }
    }

    /** 以「资源」为单位计数的 LRU（Media3 自带的淘汰器只按字节数）。 */
    private class LruCountEvictor : CacheEvictor {

        private val lastTouch = HashMap<String, Long>()
        @Volatile
        var ready = false
        private val trimScheduled = AtomicBoolean(false)

        override fun requiresCacheSpanTouches(): Boolean = true

        override fun onCacheInitialized() {}

        override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {}

        override fun onSpanAdded(cache: Cache, span: CacheSpan) {
            val count = touch(span.key, span.lastTouchTimestamp)
            if (ready && count > MAX_OBJECTS && trimScheduled.compareAndSet(false, true)) {
                // 不在 SimpleCache 的回调里直接删，放到 IO 线程
                SDMediaKit.io.execute {
                    trimScheduled.set(false)
                    val ctx = appContextForTrim ?: return@execute
                    trim(ctx)
                }
            }
        }

        override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
            val empty = try {
                cache.getCachedSpans(span.key).isEmpty()
            } catch (e: Throwable) {
                false
            }
            if (empty) forget(span.key)
        }

        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
            touch(newSpan.key, newSpan.lastTouchTimestamp)
        }

        fun touchKey(key: String) {
            touch(key, System.currentTimeMillis())
        }

        private fun touch(key: String, timestamp: Long): Int = synchronized(lastTouch) {
            val ts = if (timestamp > 0) timestamp else System.currentTimeMillis()
            val prev = lastTouch[key]
            if (prev == null || ts > prev) lastTouch[key] = ts
            lastTouch.size
        }

        fun forget(key: String) {
            synchronized(lastTouch) { lastTouch.remove(key) }
        }

        fun victims(now: Long, maxObjects: Int, maxAgeMs: Long): List<String> = synchronized(lastTouch) {
            val sorted = lastTouch.entries.sortedBy { it.value }
            val out = ArrayList<String>()
            var remaining = sorted.size
            for (entry in sorted) {
                val stale = now - entry.value > maxAgeMs
                if (stale || remaining > maxObjects) {
                    out.add(entry.key)
                    remaining--
                } else {
                    break
                }
            }
            out
        }
    }

    /** Application Context，供淘汰器在回调里触发清理。 */
    @Volatile
    private var appContextForTrim: Context? = null
}
