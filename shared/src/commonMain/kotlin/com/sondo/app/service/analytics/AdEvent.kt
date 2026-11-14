package com.sondo.app.service.analytics

import com.sondo.app.base.AppEnv
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.storage.Sp
import com.sondo.app.core.utils.nowMs
import kotlin.math.roundToInt

/**
 * 广告 / 转化事件（上报友盟）：注册、购买、订阅、取消支付、加入购物车。
 */
object AdEvent {
    /** 友盟营销归因只收这几种注册方式，对不上的登录渠道记成 account。 */
    fun umengRegisterMethod(loginType: String): String = when (loginType) {
        "email" -> "email"
        "apple" -> "third_party_apple"
        else -> "account"
    }

    /** 现有价格是元，预置事件要求单位为分。 */
    fun umengPriceFen(yuan: Double): Int = (yuan * 100).roundToInt()

    /** 注册事件 */
    fun logRegisterEvent(afLoginType: String) {
        UmengUtils.logEvent(EventName.register, mapOf("af_login_type" to afLoginType))
        val properties = LinkedHashMap<String, Any?>()
        properties["register_method"] = umengRegisterMethod(afLoginType)
        properties["register_time"] = nowMs()
        val inviteCode = Sp.getString(StorageKey.referralCode)
        if (inviteCode.isNotEmpty()) properties["invite_code"] = inviteCode
        UmengUtils.logPresetEvent("um_onUMengUserRegister", properties)
    }

    /** 加入购物车 */
    fun logAddToCartEvent(productId: String, productType: String, price: Double, orderId: String) {
        UmengUtils.logEventAddToCart(productId = productId, productType = productType, price = price, orderId = orderId)
    }

    /** 购买 */
    fun logPurchaseEvent(price: Double, productId: String, productName: String, orderId: String, purchaseId: String) {
        UmengUtils.logEventPurchase(productId = productId, price = price, orderId = orderId, purchaseId = purchaseId)
        val props = LinkedHashMap<String, Any?>()
        props["order_id"] = orderId
        props["transaction_type"] = "purchase"
        props["product_id"] = productId
        props["product_type"] = "tool"
        if (productName.isNotEmpty()) props["product_name"] = productName
        props["product_price"] = umengPriceFen(price)
        props["time"] = nowMs()
        props["currency"] = "CNY"
        if (AppEnv.isIOS) props["payment_method"] = "apple_pay"
        UmengUtils.logPresetEvent("um_onUMengInAppConvert", props)
    }

    /** 订阅 */
    fun logSubscribeEvent(price: Double, productId: String, orderId: String) {
        UmengUtils.logEventSubscribe(productId = productId, price = price, orderId = orderId)
        val props = LinkedHashMap<String, Any?>()
        props["order_id"] = orderId
        props["transaction_type"] = "purchase"
        props["product_id"] = productId
        props["product_type"] = "vip"
        props["product_price"] = umengPriceFen(price)
        props["time"] = nowMs()
        props["currency"] = "CNY"
        if (AppEnv.isIOS) props["payment_method"] = "apple_pay"
        UmengUtils.logPresetEvent("um_onUMengInAppConvert", props)
    }

    /** 取消支付订单 */
    fun logCancelPaymentEvent(productId: String, productType: Int) {
        UmengUtils.logEventCancelPayment(productId = productId, productType = productType)
    }
}
