package com.sondo.app.core.utils

import com.tencent.kuikly.core.timer.setTimeout
import kotlin.random.Random

/**
 * 假进度：从 1 涨到 98，越接近末尾越慢；[complete] 时跳到 99。
 * 定时器挂在 [start] 时的当前页面上，页面销毁后自然停止。
 */
class FakeProgressController {
    var progress = 1
        private set
    val maxFakeProgress = 98

    private var generation = 0

    fun start(onProgress: (Int) -> Unit) {
        progress = 1
        onProgress(progress)
        run(onProgress)
    }

    private fun run(onProgress: (Int) -> Unit) {
        val gen = ++generation
        val pagerId = currentPagerId()
        if (pagerId.isEmpty()) return
        val delay = 100 + Random.nextInt(200)
        setTimeout(pagerId, delay) {
            if (gen != generation) return@setTimeout
            if (progress >= maxFakeProgress) return@setTimeout
            progress = minOf(maxFakeProgress, progress + randomIncrement(progress))
            onProgress(progress)
            run(onProgress)
        }
    }

    private fun randomIncrement(current: Int): Int = when {
        // 越接近 99，涨得越慢
        current < 30 -> 1 + Random.nextInt(8)
        current < 70 -> 1 + Random.nextInt(5)
        current < 90 -> 1 + Random.nextInt(3)
        else -> 1
    }

    fun complete(onProgress: (Int) -> Unit) {
        generation++
        progress = 99
        onProgress(progress)
    }

    fun dispose() {
        generation++
    }
}
