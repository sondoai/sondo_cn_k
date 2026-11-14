package com.sondo.app.ui.video

import com.sondo.app.core.utils.nowMs
import com.sondo.app.service.VideoCacheService
import com.tencent.kuikly.core.base.PagerScope

/**
 * Dart `AppVideoControllerPool`：按 key 复用控制器（首页 Banner：maxControllers 2）。
 *
 * - [acquire]：当前页租约 +1，同 key 同源复用；有租约的 key 换源抛异常；
 * - [prefetch]：预热磁盘缓存，并在有空槽时预建控制器（0 租约）；
 * - [release]：租约 -1，到 0 暂停，可被淘汰；
 * - 槽位不够时先按最近最少使用淘汰 0 租约的控制器，仍不够：prefetch 静默跳过、acquire 抛 "no available slot"。
 *
 * 和 Flutter 不同：控制器不持有解码器（解码器属于挂载的 SDVideo 视图），所以「预建」只省掉状态重建；
 * 真正的解码器数量由页面「只给当前页挂播放器」控制。控制器默认音量 1，Banner 拿到后自己 setMuted(true)。
 */
class AppVideoControllerPool(
    private val scope: PagerScope,
    val maxControllers: Int = 2,
    private val mixWithOthers: Boolean = AppVideoPlaybackProfile.default.mixWithOthers,
    private val cacheNetworkSources: Boolean = true,
) {
    private class Entry(val controller: AppVideoController, var url: String) {
        var leases = 0
        var lastUsed = nowMs()
    }

    private val entries = LinkedHashMap<Any, Entry>()
    private var disposed = false

    init {
        require(maxControllers > 0) { "maxControllers must be > 0" }
    }

    /** 获取（或创建）key 对应的控制器并持有租约。 */
    fun acquire(key: Any, url: String): AppVideoController {
        check(!disposed) { "pool disposed" }
        val src = AppVideoSource.parse(url)
        val existing = entries[key]
        if (existing != null) {
            if (existing.url != src) {
                check(existing.leases == 0) { "source changed while leased: $key" }
                existing.controller.setSource(src, autoPlay = false)
                existing.url = src
            }
            existing.leases++
            existing.lastUsed = nowMs()
            return existing.controller
        }
        if (!ensureSlot()) throw IllegalStateException("no available slot")
        val entry = Entry(AppVideoController(scope, src, mixWithOthers = mixWithOthers), src)
        entry.leases = 1
        entries[key] = entry
        return entry.controller
    }

    /** 预热：磁盘缓存 + 有空槽时预建控制器（不持有租约）。错误吞掉。 */
    fun prefetch(key: Any, url: String) {
        if (disposed) return
        val src = AppVideoSource.parse(url)
        if (src.isEmpty()) return
        if (cacheNetworkSources) VideoCacheService.prefetchInBackground(src)
        val existing = entries[key]
        if (existing != null) {
            if (existing.leases == 0 && existing.url != src) {
                existing.controller.setSource(src, autoPlay = false)
                existing.url = src
            }
            return
        }
        if (!ensureSlot()) return
        entries[key] = Entry(AppVideoController(scope, src, mixWithOthers = mixWithOthers), src)
    }

    /** 释放租约；到 0 时暂停，等待淘汰。 */
    fun release(key: Any, controller: AppVideoController? = null) {
        val entry = entries[key] ?: return
        if (controller != null && entry.controller !== controller) return
        if (entry.leases > 0) entry.leases--
        entry.lastUsed = nowMs()
        if (entry.leases == 0) entry.controller.pause()
    }

    /** 释放所有 0 租约的控制器。 */
    fun evictIdle() {
        val idle = entries.filterValues { it.leases == 0 }.keys.toList()
        idle.forEach { k -> entries.remove(k)?.controller?.dispose() }
    }

    fun dispose() {
        disposed = true
        entries.values.forEach { it.controller.dispose() }
        entries.clear()
    }

    fun controllerOf(key: Any): AppVideoController? = entries[key]?.controller

    private fun ensureSlot(): Boolean {
        while (entries.size >= maxControllers) {
            val victim = entries.entries.filter { it.value.leases == 0 }.minByOrNull { it.value.lastUsed } ?: return false
            entries.remove(victim.key)
            victim.value.controller.dispose()
        }
        return true
    }
}

/** 列表邻页预热（Dart：未接 pool 时邻页只预热磁盘，不建解码器）。 */
object AppVideoPrefetch {
    fun prefetch(url: String?) {
        val src = AppVideoSource.parse(url)
        if (AppVideoSource.isNetwork(src)) VideoCacheService.prefetchInBackground(src)
    }

    /** 预热 index 前后 [distance] 条。 */
    fun neighbours(urls: List<String>, index: Int, distance: Int = 1) {
        if (distance <= 0) return
        for (d in 1..distance) {
            urls.getOrNull(index + d)?.let { prefetch(it) }
            urls.getOrNull(index - d)?.let { prefetch(it) }
        }
    }
}
