package com.sondo.app.service

import com.sondo.app.core.utils.onHost
import com.sondo.app.core.utils.HostBinding
import com.sondo.app.core.utils.HostNative
import com.sondo.app.nativeapi.ok

enum class VideoSourceType { asset, file, network, cachedFile }

/** 视频来源解析结果。 */
class VideoSource(
    val type: VideoSourceType,
    /** asset 路径 / 本地绝对路径 / 网络地址 */
    val source: String,
    val originalUrl: String? = null,
)

/**
 * 视频磁盘缓存（对应 Flutter 侧 VideoCacheService）。
 *
 * 缓存本体在原生侧（SDVideoCacheModule）：单文件 ≤ 20MB 才落盘，最多 300 个对象、保留 69 天；
 * SDVideo 视图的 `cache` 属性默认已走同一份缓存，这里提供列表预热与显式解析。
 */
object VideoCacheService {
    private val inflight = HashSet<String>()
    private val hostBinding = HostBinding()

    // 宿主（根页面）重建后，旧宿主上没回来的预热不再占位
    private fun checkHost() {
        if (hostBinding.needsBind()) inflight.clear()
    }

    private fun isHttpUrl(value: String) = value.startsWith("http://") || value.startsWith("https://")

    private fun isAssetPath(value: String) = value.startsWith("assets/") || value.startsWith("videos/")

    /**
     * 解析播放来源：asset / 本地文件原样返回；网络地址命中缓存返回本地路径，
     * 否则后台预热并返回网络地址（缓存读取失败降级为网络播放，不阻塞主流程）。
     * 空串返回 null。
     */
    suspend fun resolve(rawSource: String): VideoSource? = onHost { resolveImpl(rawSource) }

    private suspend fun resolveImpl(rawSource: String): VideoSource? {
        val source = rawSource.trim()
        if (source.isEmpty()) return null
        if (isAssetPath(source)) return VideoSource(VideoSourceType.asset, source)
        if (!isHttpUrl(source)) {
            return VideoSource(VideoSourceType.file, source.removePrefix("file://"))
        }
        val cached = try {
            HostNative.videoCache.resolve(source)
        } catch (e: Throwable) {
            null
        }
        if (cached != null) return VideoSource(VideoSourceType.cachedFile, cached, originalUrl = source)
        prefetchInBackground(source)
        return VideoSource(VideoSourceType.network, source)
    }

    /** 预热到磁盘缓存；返回本地路径，非网络地址或失败返回 null。同一地址并发只下载一次。 */
    suspend fun prefetch(source: String): String? = onHost { prefetchImpl(source) }

    private suspend fun prefetchImpl(source: String): String? {
        if (!isHttpUrl(source)) return null
        checkHost()
        if (!inflight.add(source)) return null
        return try {
            val res = HostNative.videoCache.prefetch(source)
            if (res.ok()) res.optString("localPath", "").ifEmpty { null } else null
        } catch (e: Throwable) {
            // 缓存预热失败不影响当前播放链路。
            null
        } finally {
            inflight.remove(source)
        }
    }

    /** 不等待结果的预热（列表项曝光时调用）。 */
    fun prefetchInBackground(source: String) {
        checkHost()
        if (!isHttpUrl(source) || inflight.contains(source)) return
        Svc.launch { prefetch(source) }
    }

    fun cancelPrefetch(source: String) {
        if (!isHttpUrl(source)) return
        HostNative.videoCache.cancelPrefetch(source)
        inflight.remove(source)
    }

    suspend fun clearAll(): Boolean = onHost { HostNative.videoCache.clear() }
}
