package com.sondo.app.features.four_view

import com.sondo.app.base.BasePager
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.currentPagerId
import com.sondo.app.core.utils.isPagerAlive
import com.tencent.kuikly.core.timer.setTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 在页面自己的上下文里执行 [block]，执行完再回到调用方。
 *
 * PollingService 的任务跑在宿主（根）页面上；请求和响应式字段写入必须回到本页面，
 * UI 才会按本页面的节奏刷新。页面已销毁时直接返回（不执行）。
 */
internal suspend fun BasePager.runOnPage(block: suspend () -> Unit) {
    val pager = this
    if (!isPagerAlive(pager.pagerId)) return
    val callerId = currentPagerId()
    suspendCoroutine<Unit> { cont ->
        var done = false
        fun finish() {
            if (done) return
            done = true
            cont.resume(Unit)
        }
        // 页面在定时器触发前 / 执行中被销毁时，它的定时器和协程随之丢弃：由调用方上下文兜底恢复，避免协程永久挂起
        fun watch() {
            if (callerId.isEmpty() || callerId == pager.pagerId || !isPagerAlive(callerId)) return
            setTimeout(callerId, 1000) {
                if (done) return@setTimeout
                if (!isPagerAlive(pager.pagerId)) finish() else watch()
            }
        }
        watch()
        setTimeout(pager.pagerId, 0) {
            pager.launch {
                try {
                    block()
                } catch (e: Throwable) {
                    DevLog.logger("runOnPage error: ${e.message}")
                } finally {
                    finish()
                }
            }
        }
    }
}
