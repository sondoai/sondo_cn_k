package com.sondo.app.service

import com.sondo.app.base.AppHost
import com.sondo.app.base.Events
import com.sondo.app.core.http.AuthSession
import com.sondo.app.nativeapi.SDAppModule
import com.sondo.app.core.utils.HostBinding
import com.sondo.app.core.utils.HostNative
import com.sondo.app.nativeapi.SDFileModule
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuikly.core.datetime.DateTime
import com.tencent.kuikly.core.log.KLog
import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.timer.clearTimeout
import com.tencent.kuikly.core.timer.setTimeout
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 服务层公共支撑：事件广播、宿主定时器、路径与哈希。
 *
 * 服务是普通 object，不持有 Pager；凡是需要跨页面存活的回调（事件监听、定时器、原生模块）
 * 一律挂在宿主页面（[AppHost.pager]）上。
 */
object Svc {
    private const val TAG = "Svc"

    /** App 是否在后台（由 [Events.APP_LIFECYCLE] 维护）。 */
    var appInBackground: Boolean = false
        private set


    fun now(): Long = DateTime.currentTimestamp()

    fun log(tag: String, msg: String) = KLog.i(tag, msg)

    // ------------------------------------------------------------------ 事件

    private fun notify(): NotifyModule = AppHost.pager.acquireModule(NotifyModule.MODULE_NAME)

    /** 广播跨页面事件。 */
    fun emit(event: String, data: JSONObject = JSONObject()) {
        notify().postNotify(event, data)
    }

    /** 在宿主页面上订阅事件（与 App 同寿命）。 */
    fun on(event: String, handler: (JSONObject) -> Unit): CallbackRef =
        notify().addNotify(event) { handler(it ?: JSONObject()) }

    fun off(event: String, ref: CallbackRef) = notify().removeNotify(event, ref)

    private val busBinding = HostBinding()
    private var userTracked = false
    private var lastLogin = false
    private val logoutHandlers = ArrayList<() -> Unit>()
    private val loginHandlers = ArrayList<() -> Unit>()

    /**
     * 每个服务 init 时调用：把前后台、登录态的订阅挂到宿主页面上。
     * 宿主（根页面）重建后旧订阅随之失效，这里会重新订阅；宿主没变则什么都不做。
     */
    fun ensureBound() {
        if (!busBinding.needsBind()) return
        appInBackground = false
        if (!userTracked) {
            userTracked = true
            lastLogin = AuthSession.isLoggedIn
        }
        on(Events.APP_LIFECYCLE) { appInBackground = !it.optBoolean("foreground", true) }
        // USER_CHANGED 在资料刷新时也会发，这里只挑出登录态翻转
        on(Events.USER_CHANGED) { data ->
            val login = loginOf(data)
            if (login != lastLogin) {
                lastLogin = login
                val handlers = if (login) loginHandlers else logoutHandlers
                handlers.toList().forEach { h ->
                    try {
                        h()
                    } catch (e: Throwable) {
                        KLog.e(TAG, "user handler error: ${e.message}")
                    }
                }
            }
        }
    }

    /** USER_CHANGED 里的登录态；事件没带时退回本地凭证。 */
    fun loginOf(data: JSONObject): Boolean =
        if (data.has("login")) data.optBoolean("login", false) else AuthSession.isLoggedIn

    /** 登录 → 登出（含换号、会话失效）的那一刻回调一次。每个服务只注册一次。 */
    fun onLogout(handler: () -> Unit) {
        logoutHandlers.add(handler)
    }

    /** 登出 → 登录的那一刻回调一次。每个服务只注册一次。 */
    fun onLogin(handler: () -> Unit) {
        loginHandlers.add(handler)
    }

    // ------------------------------------------------------------------ 定时

    fun setTimeout(delayMs: Int, block: () -> Unit): String = setTimeout(AppHost.pager.pagerId, delayMs, block)

    fun clearTimeout(ref: String?) {
        if (ref == null) return
        AppHost.pager.clearTimeout(ref)
    }

    /** 挂起 [ms] 毫秒（宿主定时器，不依赖调用方协程作用域）。 */
    suspend fun sleep(ms: Int) {
        suspendCoroutine<Unit> { cont -> setTimeout(ms) { cont.resume(Unit) } }
    }

    // ------------------------------------------------------------------ 模块 / 路径

    fun <T : Module> module(name: String): T = AppHost.module(name)

    val file: SDFileModule get() = module(SDFileModule.MODULE_NAME)
    val app: SDAppModule get() = module(SDAppModule.MODULE_NAME)

    fun md5(text: String): String = HostNative.md5(text)

    /** 临时目录（不带结尾斜杠）。 */
    fun tempDir(): String = HostNative.tempDir()

    fun documentsDir(): String = HostNative.documentsDir()

    // ------------------------------------------------------------------ 协程

    /** 在宿主页面上启动协程（跨页面存活）；异常只记日志。 */
    fun launch(block: suspend () -> Unit) {
        AppHost.launch {
            try {
                block()
            } catch (e: Throwable) {
                KLog.e(TAG, "launch error: ${e.message}")
            }
        }
    }

    /**
     * 并行执行并等待全部结束（Future.wait）。每个任务的成功 / 失败各自收集，不会因一个失败提前返回。
     */
    suspend fun <T> awaitAll(blocks: List<suspend () -> T>): List<Result<T>> {
        if (blocks.isEmpty()) return emptyList()
        val results = ArrayList<Result<T>?>(blocks.size)
        repeat(blocks.size) { results.add(null) }
        var remaining = blocks.size
        var waiter: Continuation<Unit>? = null
        val scope = AppHost.pager.lifecycleScope
        blocks.forEachIndexed { i, block ->
            scope.launch {
                results[i] = try {
                    Result.success(block())
                } catch (e: Throwable) {
                    Result.failure(e)
                }
                remaining--
                if (remaining == 0) {
                    val w = waiter
                    waiter = null
                    w?.resume(Unit)
                }
            }
        }
        if (remaining > 0) suspendCoroutine<Unit> { waiter = it }
        return results.map { it!! }
    }

    /** 协程体内吞掉异常：Kuikly 协程里未捕获的异常会在下一帧抛到宿主导致崩溃。 */
    inline fun guard(tag: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            KLog.e(tag, "error: ${e.message}")
        }
    }
}

/** 协程互斥（单线程协作式）：按到达顺序串行执行。 */
class AsyncMutex {
    private var locked = false
    private val waiters = ArrayList<Continuation<Unit>>()

    suspend fun lock() {
        if (!locked) {
            locked = true
            return
        }
        suspendCoroutine<Unit> { waiters.add(it) }
    }

    fun unlock() {
        if (waiters.isNotEmpty()) {
            waiters.removeAt(0).resume(Unit)
        } else {
            locked = false
        }
    }

    suspend inline fun <T> withLock(block: () -> T): T {
        lock()
        try {
            return block()
        } finally {
            unlock()
        }
    }
}
