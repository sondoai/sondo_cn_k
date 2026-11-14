package com.sondo.app.service.pay

import com.sondo.app.base.AppHost
import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.currentPager
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.currentPagerId
import com.sondo.app.core.utils.isPagerAlive
import com.sondo.app.core.utils.nowMs
import com.sondo.app.nativeapi.SDNavModule
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.timer.setTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

// ---------------------------------------------------------------------------------------------
// 结果回执
// ---------------------------------------------------------------------------------------------

/** [PayOutcome.status] 取值。 */
object PayStatus {
    /** 后端已确认到账（主动购买）或本轮补单有成功项（restore）。 */
    const val SUCCESS = "success"
    /** 用户在商店 / 支付宝收银台取消（restore：取消了 Apple ID 验证）。 */
    const val CANCELED = "canceled"
    /**
     * 商店返回待批准（家长审批 / SCA），或支付宝已拉起、等待后台确认；
     * 之后到账（或关单）会再广播一次终态（late = true）。
     */
    const val PENDING = "pending"
    /** 没有成交：前置校验、建单、拉起、验单失败，或订单已关闭 / 退款 / 不存在。 */
    const val FAILED = "failed"
    /** 等不到本单的结果：商店回的不是本单的交易（旧交易 / 已验过），或支付宝查单轮询到上限（订单保留，回前台 / 重启后继续查）。 */
    const val TIMEOUT = "timeout"
    /** 会话被登出、换号或新的购买替换，结果作废。 */
    const val SUPERSEDED = "superseded"
}

/**
 * 一次支付操作的结果。既是 [com.sondo.app.service.pay.PayService] 回调 / 挂起返回值，
 * 也是 [Events.PAY_RESULT] 广播的数据（[toJson]）。
 *
 * JSON 形状：
 * ```
 * {
 *   "type": "purchase" | "restore",     // 购买（含支付宝静默补查到账）/ Apple 补单恢复
 *   "channel": "apple" | "alipay",
 *   "status": "success" | "canceled" | "pending" | "failed" | "timeout" | "superseded",
 *   "reason": String,                    // 细分原因，见 PayReason；成功为空串
 *   "pageName": String,                  // submit 传入的发起页面（Routes.xxx）；静默流程为空串
 *   "productId": String,
 *   "orderId": String,                   // 后端订单号（未建单为空串）
 *   "subscription": Boolean,             // true 会员订阅，false 积分包
 *   "isNewOrder": Boolean,               // 本次点击产生的新订单（成功时弹了「购买成功」）
 *   "userInitiated": Boolean,            // false = 冷启动 / 回前台的静默补查
 *   "late": Boolean,                     // true = 回执（pending / timeout）之后才到账的补发广播
 *   "pageClosed": Boolean,               // PayService 已替页面执行了 nav.pop({"result": true})
 *   "message": String,                   // 已经 toast 给用户的文案（没有为空串）
 *   "code": Int,                         // 后端业务码（没有则不带该字段）
 *   "successCount": Int, "failureCount": Int, "hasSubscribe": Boolean   // 仅 type = restore
 * }
 * ```
 */
class PayOutcome(
    val type: String = TYPE_PURCHASE,
    val channel: String,
    val status: String,
    val reason: String = "",
    val pageName: String = "",
    val productId: String = "",
    val orderId: String = "",
    val subscription: Boolean = false,
    val isNewOrder: Boolean = false,
    val userInitiated: Boolean = true,
    val late: Boolean = false,
    val pageClosed: Boolean = false,
    val message: String = "",
    val code: Int? = null,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val hasSubscribe: Boolean = false,
) {
    val isSuccess: Boolean get() = status == PayStatus.SUCCESS

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("type", type)
            .put("channel", channel)
            .put("status", status)
            .put("reason", reason)
            .put("pageName", pageName)
            .put("productId", productId)
            .put("orderId", orderId)
            .put("subscription", subscription)
            .put("isNewOrder", isNewOrder)
            .put("userInitiated", userInitiated)
            .put("late", late)
            .put("pageClosed", pageClosed)
            .put("message", message)
        if (code != null) o.put("code", code)
        if (type == TYPE_RESTORE) {
            o.put("successCount", successCount).put("failureCount", failureCount).put("hasSubscribe", hasSubscribe)
        }
        return o
    }

    companion object {
        const val TYPE_PURCHASE = "purchase"
        const val TYPE_RESTORE = "restore"
        const val CHANNEL_APPLE = "apple"
        const val CHANNEL_ALIPAY = "alipay"

        /** 页面在 `on(Events.PAY_RESULT)` 里还原。 */
        fun fromJson(json: JSONObject): PayOutcome = PayOutcome(
            type = json.optString("type", TYPE_PURCHASE),
            channel = json.optString("channel", ""),
            status = json.optString("status", PayStatus.FAILED),
            reason = json.optString("reason", ""),
            pageName = json.optString("pageName", ""),
            productId = json.optString("productId", ""),
            orderId = json.optString("orderId", ""),
            subscription = json.optBoolean("subscription", false),
            isNewOrder = json.optBoolean("isNewOrder", false),
            userInitiated = json.optBoolean("userInitiated", true),
            late = json.optBoolean("late", false),
            pageClosed = json.optBoolean("pageClosed", false),
            message = json.optString("message", ""),
            code = if (json.has("code")) json.optInt("code", 0) else null,
            successCount = json.optInt("successCount", 0),
            failureCount = json.optInt("failureCount", 0),
            hasSubscribe = json.optBoolean("hasSubscribe", false),
        )
    }
}

/** [PayOutcome.reason] 取值。 */
object PayReason {
    const val NOT_LOGGED_IN = "not_logged_in"
    const val NO_NETWORK = "no_network"
    const val PRODUCT_MISSING = "product_missing"
    const val IN_PROGRESS = "in_progress"
    const val STORE_UNAVAILABLE = "store_unavailable"
    const val CREATE_ORDER = "create_order"
    const val OFFER_SIGNATURE = "offer_signature"
    const val LAUNCH = "launch"
    const val STORE_ERROR = "store_error"
    const val USER_CANCELED = "user_canceled"
    const val STORE_PENDING = "store_pending"
    const val VERIFY = "verify"
    /** 商店的购买结果里没有属于本单的交易（旧交易 / 已验过的交易）。 */
    const val NO_TRANSACTION = "no_transaction"
    const val LOGOUT = "logout"
    const val ACCOUNT_SWITCHED = "account_switched"
    const val REPLACED = "replaced"
    const val INTERNAL = "internal"
    const val NOT_SUPPORTED = "not_supported"
    // 支付宝
    const val CHANNEL_CLOSED = "channel_closed"
    const val BIND_MOBILE = "bind_mobile"
    const val NOT_INSTALLED = "alipay_not_installed"
    /** 有未完成的旧订单挡住了本次下单（已提示，旧单继续查）。 */
    const val ORDER_PENDING = "order_pending"
    const val ORDER_CLOSED = "order_closed"
    const val ORDER_REFUNDED = "order_refunded"
    const val ORDER_NOT_FOUND = "order_not_found"
    /** 查单轮询到上限仍未确认。 */
    const val CONFIRMING = "confirming"
    /** 支付宝签约页 / 收银台已拉起，后台查单中（页面不再保持「请求中」）。 */
    const val AWAITING_CONFIRM = "awaiting_confirm"
    const val BUSY = "busy"
    // 恢复购买
    const val NOTHING_TO_RESTORE = "nothing_to_restore"
}

/** 一次用户操作的回执：回调 + [Events.PAY_RESULT] 广播，只发生一次。 */
internal class PayAttempt(private val onResult: ((PayOutcome) -> Unit)?) {
    private val callerPagerId: String = currentPagerId()

    var resolved = false
        private set

    fun resolve(outcome: PayOutcome): Boolean {
        if (resolved) return false
        resolved = true
        val cb = onResult
        if (cb != null && isPagerAlive(callerPagerId)) {
            // 回到发起页面自己的上下文再回调
            setTimeout(callerPagerId, 0) { cb(outcome) }
        }
        PayHost.emit(Events.PAY_RESULT, outcome.toJson())
        return true
    }
}

// ---------------------------------------------------------------------------------------------
// 宿主上下文：定时器 / 协程 / 广播
// ---------------------------------------------------------------------------------------------

/** 可取消的宿主定时器句柄。 */
internal class PayTimer {
    var cancelled = false
        private set

    fun cancel() {
        cancelled = true
    }
}

/** 支付状态机跑在宿主页面（根页面）上，页面关闭不影响建单 / 验单 / 轮询。 */
internal object PayHost {
    private const val TAG = "PayHost"

    val pagerId: String get() = AppHost.pager.pagerId

    fun timeout(ms: Int, block: () -> Unit): PayTimer {
        val timer = PayTimer()
        setTimeout(pagerId, ms) {
            if (!timer.cancelled) block()
        }
        return timer
    }

    /** Timer.periodic：每 [ms] 触发一次，直到 cancel。 */
    fun periodic(ms: Int, block: () -> Unit): PayTimer {
        val timer = PayTimer()
        fun arm() {
            setTimeout(pagerId, ms) {
                if (timer.cancelled) return@setTimeout
                block()
                if (!timer.cancelled) arm()
            }
        }
        arm()
        return timer
    }

    suspend fun sleep(ms: Int) {
        suspendCoroutine<Unit> { cont -> setTimeout(pagerId, ms) { cont.resume(Unit) } }
    }

    /** 从任意页面上下文切到宿主并启动协程；协程体内的异常不外抛。 */
    fun launch(block: suspend () -> Unit) {
        AppHost.launch {
            try {
                block()
            } catch (e: Throwable) {
                DevLog.logger("$TAG coroutine error: $e")
            }
        }
    }

    /** 已在宿主上下文时直接起一个并行协程（Dart 的 unawaited）。 */
    fun spawn(block: suspend () -> Unit) {
        AppHost.scope.launch {
            try {
                block()
            } catch (e: Throwable) {
                DevLog.logger("$TAG coroutine error: $e")
            }
        }
    }

    /** 带超时等待；超时或抛异常都返回 null（对应 Dart 的 `.timeout()` 抛错）。原操作不会被取消。 */
    suspend fun <T : Any> withTimeout(ms: Int, block: suspend () -> T?): T? = suspendCoroutine { cont ->
        var done = false
        val timer = timeout(ms) {
            if (!done) {
                done = true
                cont.resume(null)
            }
        }
        AppHost.scope.launch {
            val value: T? = try {
                block()
            } catch (e: Throwable) {
                null
            }
            if (!done) {
                done = true
                timer.cancel()
                cont.resume(value)
            }
        }
    }

    fun emit(event: String, data: JSONObject = JSONObject()) {
        try {
            AppHost.pager.acquireModule<NotifyModule>(NotifyModule.MODULE_NAME).postNotify(event, data)
        } catch (e: Throwable) {
            DevLog.logger("$TAG emit $event failed: $e")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// UI：toast / loading / 关页 / 跳转
// ---------------------------------------------------------------------------------------------

/**
 * 支付流程里的界面动作。
 *
 * 状态机跑在宿主页面上，而 Kuikly 的浮层和导航属于单个页面，所以这里维护一份
 * 「调用过支付入口的页面」登记表，按原生页面栈的栈顶找到真正可见的那个页面，再切到它的上下文执行。
 * 栈顶页面没登记时退回：最近登记的页面 → 宿主页面。
 */
internal object PayUi {
    private val pages = ArrayList<BasePager>()

    /** 刚关掉页面时原生栈还没更新，这段时间内按「关页后的栈顶」算。 */
    private var poppedRoute = ""
    private var belowRoute = ""
    private var poppedAt = 0L

    private var loadingPager: BasePager? = null

    private val host: BasePager?
        get() = try {
            AppHost.pager as? BasePager
        } catch (e: Throwable) {
            null
        }

    /** 登记调用支付入口的页面（宿主页面不用登记）。 */
    fun register(pager: BasePager?) {
        if (pager == null) return
        if (AppHost.isReady && pager === AppHost.pager) return
        pages.remove(pager)
        pages.add(pager)
        prune()
    }

    fun registerCurrent() = register(currentPager())

    private fun prune() {
        val it = pages.iterator()
        while (it.hasNext()) {
            if (!isPagerAlive(it.next().pagerId)) it.remove()
        }
    }

    private fun sameRoute(a: String, b: String): Boolean = a.isNotEmpty() && a.equals(b, ignoreCase = true)

    private fun stack(): List<String> = try {
        AppHost.module<SDNavModule>(SDNavModule.MODULE_NAME).stack()
    } catch (e: Throwable) {
        emptyList()
    }

    /** 当前栈顶页面名（Get.currentRoute）。拿不到原生栈时退回最近登记的页面。 */
    fun currentRoute(): String {
        val s = stack()
        val top = s.lastOrNull() ?: ""
        if (top.isEmpty()) {
            prune()
            return pages.lastOrNull()?.pageName ?: host?.pageName ?: ""
        }
        if (poppedRoute.isNotEmpty() && nowMs() - poppedAt < 800 && sameRoute(top, poppedRoute)) return belowRoute
        return top
    }

    /** 与 Routes 常量比较用：页面名统一小写。 */
    fun currentRouteName(): String = currentRoute().lowercase()

    private fun pagerOf(route: String): BasePager? {
        if (route.isEmpty()) return null
        prune()
        pages.lastOrNull { sameRoute(it.pageName, route) }?.let { return it }
        val h = host
        if (h != null && sameRoute(h.pageName, route)) return h
        return null
    }

    /** 可见页面；找不到时退回最近登记的页面或宿主页面。 */
    private fun target(): BasePager? = pagerOf(currentRoute()) ?: pages.lastOrNull() ?: host

    private fun runOn(pager: BasePager, block: (BasePager) -> Unit) {
        if (!isPagerAlive(pager.pagerId)) return
        setTimeout(pager.pagerId, 0) {
            try {
                block(pager)
            } catch (e: Throwable) {
                DevLog.logger("PayUi action failed: $e")
            }
        }
    }

    fun toast(msg: String, durationMs: Int = 2000) {
        if (msg.isEmpty()) return
        val pager = target() ?: return
        runOn(pager) { it.toast(msg, durationMs) }
    }

    fun showLoading() {
        hideLoading()
        val pager = target() ?: return
        loadingPager = pager
        runOn(pager) { it.showLoading() }
    }

    fun hideLoading() {
        val pager = loadingPager ?: return
        loadingPager = null
        runOn(pager) { it.hideLoading() }
    }

    /**
     * Get.back：关掉栈顶页面（必须是登记过的页面），可同时带一条 toast。
     * toast 显示在关页后露出的页面上；那个页面不认识时，先在要关的页面上提示再关。
     * @param result true 对应 `Get.back(closeOverlays: true, result: true)`：先收起页面上的弹窗，
     *   并把 `{"result": true}` 作为页面结果带回；false 对应不带结果的 `Get.back()`。
     * @return 是否关了页面
     */
    fun back(toastMsg: String? = null, result: Boolean = true, durationMs: Int = 2000): Boolean {
        val s = stack()
        val top = currentRoute()
        val pager = pagerOf(top)
        val h = host
        if (pager == null || pager === h) {
            if (toastMsg != null) toast(toastMsg, durationMs)
            return false
        }
        val index = s.indexOfLast { sameRoute(it, top) }
        val below = if (index > 0) s[index - 1] else ""
        pages.remove(pager)
        poppedRoute = top
        belowRoute = below
        poppedAt = nowMs()
        val next = pagerOf(below)
        if (toastMsg != null && next == null) runOn(pager) { it.toast(toastMsg, durationMs) }
        runOn(pager) {
            if (result) {
                it.overlay.dismissAllDialogs()
                it.nav.pop(JSONObject().put("result", true))
            } else {
                it.nav.pop()
            }
        }
        if (toastMsg != null && next != null) runOn(next) { it.toast(toastMsg, durationMs) }
        return true
    }

    /** Get.toNamed：从可见页面打开新页面。 */
    fun toNamed(route: String, args: JSONObject? = null) {
        val pager = target() ?: return
        runOn(pager) { it.nav.push(route, args) }
    }
}
