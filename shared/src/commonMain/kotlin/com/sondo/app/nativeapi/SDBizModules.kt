package com.sondo.app.nativeapi

import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 支付。
 * - Android / 鸿蒙：支付宝（积分包走 SDK `payV2(orderStr)`；自动续费签约走 SDAppModule.openUrl 拉起 alipays://）。
 * - iOS：StoreKit 2 内购。
 * 建单、轮询、验单信封、会话与补单状态机都在 Kuikly 侧 PayService；原生只做 SDK 调用。
 *
 * 事件（[addEventListener]）：
 * - {event:"iapUpdate", transactions:[{transactionId, productId, status:"pending"|"purchased"|"restored"|"canceled"|"error",
 *     errorMessage?, appAccountToken?, jws, pendingFinish: Boolean}]}        仅 iOS
 */
class SDPayModule : SDModule() {
    override fun moduleName(): String = MODULE_NAME

    /** sync → 是否安装支付宝（iOS 恒 false） */
    fun alipayIsInstalled(): Boolean = invokeSync("alipayIsInstalled") == "1"

    /** 支付宝 App 支付。→ {ok, resultStatus:"9000"|"8000"|"6001"|…, result?, memo?} */
    suspend fun alipayPay(orderStr: String): JSONObject = await("alipayPay", JSONObject().put("orderStr", orderStr))

    /** → 是否可用内购（非 iOS 恒 false） */
    suspend fun iapIsAvailable(): Boolean = await("iapIsAvailable").optBoolean("available", false)

    /**
     * → {ok, products:[{id, title, description, price(本地化价格串), rawPrice(Number), currencyCode, currencySymbol,
     *      subscription?:{periodUnit:"day"|"week"|"month"|"year", periodCount,
     *        offers:[{id?, type:"introductory"|"promotional", paymentMode:"freeTrial"|"payAsYouGo"|"payUpFront", price, rawPrice, periodUnit, periodCount, cycles}]}}],
     *    notFound:[id]}
     */
    suspend fun iapQueryProducts(ids: List<String>): JSONObject =
        await("iapQueryProducts", JSONObject().put("ids", JSONArray().also { a -> ids.forEach { a.put(it) } }))

    suspend fun iapIsIntroEligible(productId: String): Boolean =
        await("iapIsIntroEligible", JSONObject().put("productId", productId)).optBoolean("eligible", false)

    /**
     * 发起购买，结果经 iapUpdate 事件返回。
     * params: {productId, appAccountToken(UUID), consumable: Boolean,
     *          promotionalOffer?:{offerId, keyId, nonce, timestamp, signature}}
     * → {ok, started, error?}
     */
    suspend fun iapPurchase(params: JSONObject): JSONObject = await("iapPurchase", params)

    suspend fun iapFinish(transactionId: String): Boolean =
        await("iapFinish", JSONObject().put("transactionId", transactionId)).ok()

    suspend fun iapRestore(): Boolean = await("iapRestore").ok()

    /** → {ok, transactions:[{transactionId, productId, appAccountToken?, purchaseDateMs, jws}]} */
    suspend fun iapUnfinishedTransactions(): JSONObject = await("iapUnfinishedTransactions")

    /** → {ok, transactions:[{productId, expirationDateMs?}]}（当前有效权益） */
    suspend fun iapTransactionHistory(): JSONObject = await("iapTransactionHistory")

    /** 打开系统订阅管理页 */
    fun openSubscriptionManagement() = invoke("openSubscriptionManagement")

    fun addEventListener(listener: (JSONObject) -> Unit): CallbackRef? = listen("addEventListener", null, listener)

    companion object {
        const val MODULE_NAME = "SDPayModule"
    }
}

/**
 * 登录相关三方 SDK：微信授权（仅 auth，无微信支付）与极光一键登录。
 * 隐私同意前不得初始化。
 *
 * 事件（[addEventListener]）：
 * - {event:"wechatAuthResp", errCode, code?, state?}
 */
class SDAuthModule : SDModule() {
    override fun moduleName(): String = MODULE_NAME

    /** → {ok} */
    suspend fun wechatRegister(appId: String, universalLink: String): Boolean =
        await("wechatRegister", JSONObject().put("appId", appId).put("universalLink", universalLink)).ok()

    fun wechatIsInstalled(): Boolean = invokeSync("wechatIsInstalled") == "1"

    /** 拉起微信授权（scope=snsapi_userinfo），结果经 wechatAuthResp 事件返回。→ {ok, launched} */
    suspend fun wechatAuth(state: String, scope: String = "snsapi_userinfo"): Boolean =
        await("wechatAuth", JSONObject().put("scope", scope).put("state", state)).optBoolean("launched", false)

    /** 极光认证初始化。→ {ok, code}（8000 = 成功） */
    suspend fun jverifySetup(appKey: String, channel: String, debug: Boolean): JSONObject =
        await("jverifySetup", JSONObject().put("appKey", appKey).put("channel", channel).put("debug", debug).put("timeoutMs", 10000))

    /** 当前网络环境是否支持一键登录。 */
    suspend fun jverifyCheckEnable(): Boolean = await("jverifyCheckEnable").optBoolean("enable", false)

    /** 预取号（提前拉运营商信息，缩短授权页拉起时间）。→ {ok, code} */
    suspend fun jverifyPreLogin(timeoutMs: Int = 5000): JSONObject =
        await("jverifyPreLogin", JSONObject().put("timeoutMs", timeoutMs))

    /**
     * 拉起一键登录授权页（各端用原生实现的底部弹层样式，文案 / 协议链接由这里传入）。
     * params: {
     *   title, loginButtonText, otherLoginText?(短信登录入口文案), wechatLoginText?(微信登录入口文案),
     *   privacyPrefix, privacySuffix, privacyItems:[{name, url}], privacyUncheckedToast,
     *   timeoutMs
     * }
     * → {ok, action:"token"|"wechat"|"sms"|"cancel"|"unsupported"|"error", token?, operator?, code?, error?}
     */
    suspend fun jverifyLoginAuth(params: JSONObject): JSONObject = await("jverifyLoginAuth", params)

    fun jverifyDismiss() = invoke("jverifyDismiss")

    fun addEventListener(listener: (JSONObject) -> Unit): CallbackRef? = listen("addEventListener", null, listener)

    companion object {
        const val MODULE_NAME = "SDAuthModule"
    }
}

/**
 * 极光推送。隐私同意且通知权限已授予后才 [setup]（内部先 setAuth(true)，前台不弹横幅）。
 *
 * 事件（[addEventListener]）：
 * - {event:"connected"}
 * - {event:"notificationReceived", title, alert, extras:{}}
 * - {event:"notificationOpened", title, alert, extras:{}}     extras 已从 cn.jpush.android.EXTRA 拍平
 */
class SDPushModule : SDModule() {
    override fun moduleName(): String = MODULE_NAME

    suspend fun setup(appKey: String, channel: String, production: Boolean, debug: Boolean): Boolean =
        await("setup", JSONObject().put("appKey", appKey).put("channel", channel).put("production", production).put("debug", debug)).ok()

    /** → 极光 Registration ID（未连上时为空串） */
    suspend fun getRegistrationId(): String = await("getRegistrationId").optString("rid", "")

    /** 冷启动时点击的通知。→ {ok, title?, alert?, extras?}；读取后清空 */
    suspend fun getLaunchNotification(): JSONObject = await("getLaunchNotification")

    fun setBadge(count: Int) = invoke("setBadge", JSONObject().put("count", count))

    fun addEventListener(listener: (JSONObject) -> Unit): CallbackRef? = listen("addEventListener", null, listener)

    companion object {
        const val MODULE_NAME = "SDPushModule"
    }
}

/**
 * 友盟统计。隐私同意后才 [init]；手动页面统计模式。
 */
class SDAnalyticsModule : SDModule() {
    override fun moduleName(): String = MODULE_NAME

    fun init(appKey: String, channel: String) =
        invoke("init", JSONObject().put("appKey", appKey).put("channel", channel))

    /** props 的值只允许 String / Number */
    fun onEvent(name: String, props: JSONObject? = null) {
        val p = JSONObject().put("name", name)
        if (props != null) p.put("props", props)
        invoke("onEvent", p)
    }

    fun onPageStart(name: String) = invoke("onPageStart", JSONObject().put("name", name))
    fun onPageEnd(name: String) = invoke("onPageEnd", JSONObject().put("name", name))
    fun signIn(userId: String) = invoke("signIn", JSONObject().put("userId", userId))
    fun signOff() = invoke("signOff")

    companion object {
        const val MODULE_NAME = "SDAnalyticsModule"
    }
}
