package com.sondo.app.service.analytics

import com.sondo.app.base.AppEnv
import com.sondo.app.base.AppHost
import com.sondo.app.base.BasePager
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.http.Api
import com.sondo.app.core.utils.DevLog
import com.sondo.app.nativeapi.SDAnalyticsModule
import com.tencent.kuikly.core.manager.PagerManager
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager

/** 事件名称 */
object EventName {
    /** 页面浏览 */
    const val pageView = "page_view"
    /** 按钮点击 */
    const val buttonClick = "button_click"
    /** 弹窗展示 */
    const val popupView = "popup_view"
    /** 取消支付订单 */
    const val cancelPayment = "cancel_payment"
    /** 消耗订单 */
    const val consumptionOrder = "consumption_order"
    /** 完成注册 */
    const val register = "complete_registration"
    /** 分享 */
    const val share = "share"
    /** 邀请好友 */
    const val invite = "invite"
    /** 打开应用 */
    const val openApp = "app_launch"
    /** 登录 */
    const val login = "af_login"
    /** 购买积分 */
    const val purchase = "af_purchase"
    /** 创建订单 */
    const val addToCart = "af_add_to_cart"
    /** 订阅 */
    const val subscribe = "af_subscribe"
}

/**
 * 友盟统计（对应 Flutter 侧 UmengUtils）。隐私同意后才 [initUmeng]；在此之前所有上报都是空操作。
 *
 * 参考工程的 installErrorLogging（Flutter 未捕获异常过滤 + logcat 按行输出）是 Flutter 运行时专属，
 * Kuikly 没有对应的全局异常入口，不移植。
 */
object UmengUtils {
    private var analyticsReady = false
    private var currentPage: String? = null
    private var previousPage: String = ""
    private val seenPagers = HashSet<String>()

    /** 友盟自定义属性值上限。超长会被整条事件丢弃，这里先截断。 */
    private const val umengValueMaxLength = 256

    private fun module(): SDAnalyticsModule? = try {
        (PagerManager.getCurrentPager() as Pager).acquireModule(SDAnalyticsModule.MODULE_NAME)
    } catch (e: Throwable) {
        if (AppHost.isReady) AppHost.module(SDAnalyticsModule.MODULE_NAME) else null
    }

    /** 冷启动（同意后）初始化；当前平台 AppKey 为空时不初始化，事件不会上报。 */
    fun initUmeng() {
        if (analyticsReady) return
        val appKey = when {
            AppEnv.isIOS -> AppConstants.umengIosAppKey
            AppEnv.isOhos -> AppConstants.umengOhosAppKey
            else -> AppConstants.umengAndroidAppKey
        }
        if (appKey.isEmpty()) {
            DevLog.logger("Umeng skip init: app key empty")
            return
        }
        // 参考工程用编译期 UMENG_CHANNEL；这里渠道包直接取 Walle 渠道号，非渠道包回落默认值。
        val channel = Api.appChannel.trim().ifEmpty { if (AppEnv.isIOS) "App Store" else "official" }
        try {
            // 原生侧 init 内部切到手动页面统计模式，页面时长由 onRouteChanged 打点。
            module()?.init(appKey, channel)
            analyticsReady = true
        } catch (e: Throwable) {
            DevLog.logger("Umeng init error: $e")
        }
        logEvent(EventName.openApp)
    }

    /** 登录后把业务用户 id 绑到友盟账号。 */
    fun setAnalyticsUser(userId: String) {
        if (!analyticsReady || userId.isEmpty()) return
        try {
            module()?.signIn(userId)
        } catch (e: Throwable) {
            DevLog.logger("Umeng onProfileSignIn error: $e")
        }
    }

    /** 登出、注销后解除账号，后续事件不再算到该用户。 */
    fun clearAnalyticsUser() {
        if (!analyticsReady) return
        try {
            module()?.signOff()
        } catch (e: Throwable) {
            DevLog.logger("Umeng onProfileSignOff error: $e")
        }
    }

    // ------------------------------------------------------------------ 页面

    /**
     * Kuikly 页面名 → 参考工程的路由串（统计口径沿用 Flutter 的 `/music/detail` 这类路径）。
     */
    fun routePath(page: String): String = when (page) {
        "", Routes.root -> "/"
        Routes.musicDetail -> "/music/detail"
        Routes.musicGenre -> "/music/genre"
        Routes.squarePlay -> "/square/play"
        Routes.squarePlayDetail -> "/square/play-detail"
        Routes.fourView -> "/four-view"
        Routes.fourViewForm -> "/four-view/form"
        else -> "/$page"
    }

    /** 当前页面的路由串（不在页面上下文时为空串）。 */
    fun currentRoute(): String = try {
        routePath((PagerManager.getCurrentPager() as Pager).pageName)
    } catch (e: Throwable) {
        ""
    }

    fun logCurrentScreen(screenName: String) = onRouteChanged(screenName)

    /** 路由切换时结掉上一页再打开当前页，返回也要结，否则时长只统计前进。 */
    fun onRouteChanged(pageName: String) {
        if (!analyticsReady || pageName.isEmpty() || pageName == currentPage) return
        val previous = currentPage
        currentPage = pageName
        try {
            val m = module() ?: return
            if (previous != null) m.onPageEnd(previous)
            m.onPageStart(pageName)
        } catch (e: Throwable) {
            DevLog.logger("Umeng onRouteChanged error: $e")
        }
    }

    /**
     * 页面出现时调用（BasePager.pageDidAppear）：对应参考工程 GetMaterialApp.routingCallback。
     * 每次出现都切换页面时长统计；只有首次出现（前进，而非返回）才记 page_view。
     */
    fun onPagerAppear(pager: BasePager) {
        val route = routePath(pager.pageName)
        val from = previousPage
        val isForward = seenPagers.add(pager.pagerId)
        onRouteChanged(route)
        if (isForward && from.isNotEmpty() && from != route) {
            logEventPageView(route, from, pager.pagerData.params.toString())
        }
        previousPage = route
    }

    /** 页面销毁时调用，释放「是否首次出现」的记录。 */
    fun onPagerDestroy(pager: BasePager) {
        seenPagers.remove(pager.pagerId)
    }

    // ------------------------------------------------------------------ 事件

    /** 通用事件记录 */
    fun logEvent(eventName: String, parameters: Map<String, Any?>? = null) = onEvent(eventName, parameters)

    /** 页面浏览 */
    fun logEventPageView(currentName: String, fromPageName: String, args: Any?) {
        onEvent(
            EventName.pageView,
            mapOf("current_page_name" to currentName, "from_page_name" to fromPageName, "args" to "${args ?: ""}"),
        )
    }

    /** 按钮点击；[pageName] 为空时取当前路由 */
    fun logEventButtonClick(
        buttonName: String,
        popupName: String = "",
        pageName: String = "",
        args: String = "",
        result: String = "",
    ) {
        try {
            val parameters = LinkedHashMap<String, Any?>()
            parameters["current_page_name"] = pageName.ifEmpty { currentRoute() }
            parameters["button_name"] = buttonName
            parameters["popup_name"] = popupName
            parameters["args"] = args
            if (result.isNotEmpty()) parameters["result"] = result
            onEvent(EventName.buttonClick, parameters)
        } catch (e: Throwable) {
            DevLog.logger("logEventButtonClick error $e")
        }
    }

    /** 弹窗展示 */
    fun logEventPopupView(popupName: String) = onEvent(EventName.popupView, mapOf("popup_name" to popupName))

    /** 添加到购物车 */
    fun logEventAddToCart(productId: String, productType: String, price: Double, orderId: String) {
        DevLog.logger("productId: $productId, productType: $productType $price")
        onEvent(
            EventName.addToCart,
            mapOf("productId" to productId, "productType" to productType, "orderId" to orderId, "s_price" to price),
        )
    }

    /** 购买 */
    fun logEventPurchase(productId: String, price: Double, orderId: String, purchaseId: String) {
        DevLog.logger("productId: $productId,  $price")
        onEvent(
            EventName.purchase,
            mapOf("productId" to productId, "productType" to "consumable", "orderId" to orderId, "price" to price),
        )
    }

    /** 订阅 */
    fun logEventSubscribe(productId: String, price: Double, orderId: String) {
        DevLog.logger("productId: $productId, $price")
        onEvent(
            EventName.subscribe,
            mapOf("productId" to productId, "productType" to "subscription", "orderId" to orderId, "price" to price),
        )
    }

    /** 取消支付订单 */
    fun logEventCancelPayment(productId: String, productType: Int) {
        onEvent(EventName.cancelPayment, mapOf("productId" to productId, "productType" to productType))
    }

    /** 营销归因预置事件。不追加 event_source，避免多余字段影响友盟对预置 key 的识别。 */
    fun logPresetEvent(eventName: String, parameters: Map<String, Any?>) {
        if (!analyticsReady || eventName.isEmpty()) return
        try {
            module()?.onEvent(eventName, umengEventProperties(parameters, withEventSource = false))
        } catch (e: Throwable) {
            DevLog.logger("Umeng preset event error: $e")
        }
    }

    private fun onEvent(eventName: String, parameters: Map<String, Any?>?) {
        if (!analyticsReady || eventName.isEmpty()) return
        try {
            module()?.onEvent(eventName, umengEventProperties(parameters))
        } catch (e: Throwable) {
            DevLog.logger("Umeng onEvent error: $e")
        }
    }

    /** 把参数收成友盟能收的标量：字符串、整数、浮点；null 丢弃。 */
    fun umengEventProperties(parameters: Map<String, Any?>?, withEventSource: Boolean = true): JSONObject {
        val result = JSONObject()
        parameters?.forEach { (key, raw) ->
            when (val value = umengPropertyValue(raw)) {
                null -> {}
                is String -> result.put(key, value)
                is Int -> result.put(key, value)
                is Long -> result.put(key, value)
                is Number -> result.put(key, value.toDouble())
                else -> result.put(key, value.toString())
            }
        }
        if (withEventSource) result.put("event_source", "App")
        return result
    }

    private fun umengPropertyValue(value: Any?): Any? = when (value) {
        null -> null
        is String -> if (value.length <= umengValueMaxLength) value else value.substring(0, umengValueMaxLength)
        is Number -> value
        // 原生 SDK 不收布尔、Map、数组，布尔压成 0/1，其余转成字符串。
        is Boolean -> if (value) 1 else 0
        else -> value.toString().let { if (it.length <= umengValueMaxLength) it else it.substring(0, umengValueMaxLength) }
    }
}
