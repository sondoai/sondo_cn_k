package com.sondo.app.service.audio

import com.sondo.app.service.AsyncMutex

/**
 * 应用内出声仲裁：音乐播放器与每个视频播放器之间互斥（系统音频会话只负责和其它 App 仲裁）。
 *
 * 用法（视频播放器同样遵守）：
 * - 创建时 [attach]，销毁时调用返回的 detach；
 * - 播放 / 取消静音前 `val token = claim(owner)`，随后 `if (isCurrent(owner, token)) 真正播放`；
 * - 暂停 / 静音 / 销毁时 [release]。静音播放器不 claim，也不会被暂停。
 */
object AppAudioOutputCoordinator {

    private class Registration(
        val isPlaying: () -> Boolean,
        val isAudible: () -> Boolean,
        val pauseForHandoff: suspend () -> Unit,
    ) {
        var claimed = false
    }

    private val registrations = LinkedHashMap<Any, Registration>()
    private val claims = HashMap<Any, Int>()
    private var tail = AsyncMutex()
    private var nextClaim = 0
    private var pendingClaims = 0

    /** @return detach 函数 */
    fun attach(
        owner: Any,
        isPlaying: () -> Boolean,
        isAudible: () -> Boolean,
        pauseForHandoff: suspend () -> Unit,
    ): () -> Unit {
        val registration = Registration(isPlaying, isAudible, pauseForHandoff)
        registrations[owner] = registration
        return {
            release(owner)
            if (registrations[owner] === registration) registrations.remove(owner)
        }
    }

    /**
     * 申请出声。返回递增令牌；期间 owner 已 release 则返回 -1。
     * 新请求立即让其它播放器的旧令牌失效；实际暂停按到达顺序串行执行。
     */
    suspend fun claim(owner: Any): Int {
        val claim = ++nextClaim
        val registration = registrations[owner]
        if (registration != null) registration.claimed = true

        for (other in registrations.keys) {
            if (other !== owner) claims.remove(other)
        }

        val hasActiveOther = registrations.entries.any { (key, other) ->
            key !== owner && (other.claimed || (safeBool(other.isPlaying) && safeBool(other.isAudible)))
        }
        if (pendingClaims == 0 && !hasActiveOther) {
            claims[owner] = claim
            return claim
        }

        pendingClaims++
        tail.lock()
        try {
            if (registration != null && !registration.claimed) return -1
            for ((key, other) in registrations.entries.toList()) {
                if (key === owner) continue
                val shouldPause = other.claimed || (safeBool(other.isPlaying) && safeBool(other.isAudible))
                if (!shouldPause) continue
                other.claimed = false
                try {
                    other.pauseForHandoff()
                } catch (e: Throwable) {
                    // 单个播放器暂停失败不阻断交接
                }
                claims.remove(key)
            }
            if (registration != null && !registration.claimed) return -1
            claims[owner] = claim
            return claim
        } finally {
            pendingClaims--
            tail.unlock()
        }
    }

    fun isCurrent(owner: Any, claim: Int): Boolean = claims[owner] == claim

    /** 宿主页面重建后调用：排队中的交接已随旧协程失效，清掉占位。 */
    fun resetAfterHostChange() {
        tail = AsyncMutex()
        pendingClaims = 0
        claims.clear()
        registrations.values.forEach { it.claimed = false }
    }

    fun release(owner: Any) {
        claims.remove(owner)
        registrations[owner]?.claimed = false
    }

    private fun safeBool(callback: () -> Boolean): Boolean = try {
        callback()
    } catch (e: Throwable) {
        false
    }
}
