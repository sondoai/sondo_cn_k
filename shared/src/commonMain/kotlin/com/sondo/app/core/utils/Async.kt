package com.sondo.app.core.utils

import com.sondo.app.base.AppEnv
import com.tencent.kuikly.core.datetime.DateTime
import com.tencent.kuikly.core.manager.PagerManager
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.timer.setTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** 当前毫秒时间戳（commonMain 没有 System.currentTimeMillis）。 */
fun nowMs(): Long = DateTime.currentTimestamp()

/** 当前页面的 pagerId；不在页面上下文时为空串。 */
fun currentPagerId(): String = try {
    (PagerManager.getCurrentPager() as Pager).pagerId
} catch (e: Throwable) {
    ""
}

/** 页面是否还存活。 */
fun isPagerAlive(pagerId: String): Boolean = pagerId.isNotEmpty() && try {
    PagerManager.getPager(pagerId)
    true
} catch (e: Throwable) {
    false
}

/**
 * 挂起 [ms] 毫秒（Future.delayed）。定时器绑定在发起时的当前页面上：
 * 页面销毁后不再恢复，后续代码自然不会执行。
 */
suspend fun delayMs(ms: Int) {
    val pagerId = currentPagerId()
    if (pagerId.isEmpty()) return
    suspendCoroutine<Unit> { cont ->
        setTimeout(pagerId, ms) { cont.resume(Unit) }
    }
}

/**
 * 给挂起操作加超时：先到者生效，另一方的结果被丢弃。
 * 超时返回 [onTimeout] 的值（原操作不会被取消，只是不再等它）。
 */
suspend fun <T> withTimeoutOr(ms: Int, onTimeout: () -> T, start: ((T) -> Unit) -> Unit): T {
    val pagerId = currentPagerId()
    return suspendCoroutine { cont ->
        var done = false
        val finish: (T) -> Unit = { v ->
            if (!done) {
                done = true
                cont.resume(v)
            }
        }
        if (pagerId.isNotEmpty()) setTimeout(pagerId, ms) { finish(onTimeout()) }
        start(finish)
    }
}

/** 仅调试包执行。 */
inline fun debugOnly(block: () -> Unit) {
    if (AppEnv.isDebug) block()
}

/**
 * 在宿主（根）页面上下文里执行 [block]，完成后回到调用方页面继续。
 *
 * Kuikly 的网络 / 定时回调绑定在发起它的页面上：业务页发起请求后立刻关闭，回调就丢了。
 * 账号、配置这类「必须做完」的工作用它包一层：即使调用方页面中途销毁，工作仍会完成，只是调用方不再被恢复。
 */
suspend fun <T> onHost(block: suspend () -> T): T {
    val callerId = currentPagerId()
    val hostId = try {
        com.sondo.app.base.AppHost.pager.pagerId
    } catch (e: Throwable) {
        ""
    }
    if (callerId.isEmpty() || hostId.isEmpty() || callerId == hostId) return block()
    return suspendCoroutine { cont ->
        com.sondo.app.base.AppHost.launch {
            val result = try {
                Result.success(block())
            } catch (e: Throwable) {
                Result.failure(e)
            }
            if (isPagerAlive(callerId)) {
                setTimeout(callerId, 0) { cont.resumeWith(result) }
            }
        }
    }
}

/**
 * 记录「挂在宿主页面上的监听 / 定时器」绑定在哪个宿主上。
 *
 * 应用级服务是进程级单例，而监听、定时器随宿主（根）页面销毁而失效：
 * Android 上按返回键退出后再进入，进程还在但根页面是新的。每次 init 用 [needsBind] 判断是否要重新绑定。
 */
class HostBinding {
    private var hostId = ""

    /** 宿主没变且还活着返回 false；否则记下新宿主并返回 true（调用方随后重新注册监听）。 */
    fun needsBind(): Boolean {
        val id = try {
            com.sondo.app.base.AppHost.pager.pagerId
        } catch (e: Throwable) {
            return false
        }
        if (id == hostId && isPagerAlive(id)) return false
        hostId = id
        return true
    }
}
