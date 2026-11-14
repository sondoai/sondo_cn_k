package com.sondo.app.service.pay

import com.sondo.app.core.utils.nowMs
import com.sondo.app.data.models.MvDiscountModel
import com.sondo.app.data.models.ProductDetails
import com.sondo.app.data.models.jArr
import com.sondo.app.data.models.jDbl
import com.sondo.app.data.models.jInt
import com.sondo.app.data.models.jMap
import com.sondo.app.data.models.jObj
import com.sondo.app.data.models.jStr
import com.sondo.app.service.ConfigService
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 商店订阅优惠（StoreKit 2 `Product.SubscriptionOffer`，取自 `iapQueryProducts` 的 `subscription.offers[]`）。
 */
class StoreOffer(
    val id: String,
    /** "introductory" | "promotional" */
    val type: String,
    /** "freeTrial" | "payAsYouGo" | "payUpFront" */
    val paymentMode: String,
    /** 本地化价格串（原生给出时优先用它展示）。 */
    val price: String,
    val rawPrice: Double,
    /** 优惠持续的计费周期数（Flutter 侧 `SK2SubscriptionOffer.periodCount`）。 */
    val periodCount: Int,
) {
    val isIntroductory: Boolean get() = type == TYPE_INTRODUCTORY
    val isPromotional: Boolean get() = type == TYPE_PROMOTIONAL

    /** 展示价：原生本地化串优先，否则按商品的货币符号格式化（`NumberFormat.currency(symbol).format(price)`）。 */
    fun displayPrice(details: ProductDetails): String {
        if (price.isNotEmpty()) return price
        return details.currencySymbol + formatAmount(rawPrice, 2)
    }

    companion object {
        const val TYPE_INTRODUCTORY = "introductory"
        const val TYPE_PROMOTIONAL = "promotional"
        const val MODE_PAY_AS_YOU_GO = "payAsYouGo"

        fun fromJson(json: JSONObject): StoreOffer {
            // 原生契约里 periodUnit/periodCount 描述单个周期，cycles 才是优惠持续的周期数；没给 cycles 时退回 periodCount。
            val cycles = if (json.has("cycles")) json.jInt("cycles", 0) else json.jInt("periodCount", 0)
            return StoreOffer(
                id = json.jStr("id"),
                type = json.jStr("type"),
                paymentMode = json.jStr("paymentMode"),
                price = json.jStr("price"),
                rawPrice = json.jDbl("rawPrice"),
                periodCount = cycles,
            )
        }
    }
}

/** 商品的订阅优惠列表（推介 + 促销）。非订阅商品为空。 */
fun ProductDetails.subscriptionOffers(): List<StoreOffer> =
    raw?.jObj("subscription")?.jArr("offers").jMap { StoreOffer.fromJson(it) }

/** 推介优惠（首购优惠）；没有则 null。 */
fun ProductDetails.introductoryOffer(): StoreOffer? = subscriptionOffers().firstOrNull { it.isIntroductory }

/** 幸运周卡折扣漏斗在支付层的收尾。 */
object DiscountOffer {
    /**
     * 折扣页购买成功：倒计时归零，清掉漏斗状态并落盘，停表
     * （Dart: PayService._showPurchaseSuccess 里 pageName == Routes.discount 的分支）。
     */
    fun endAfterPurchase() {
        val countdown = ConfigService.countdown
        // 剩余秒只有倒计时自己能写：先按「马上到期」同步一次把它写成 0，下面再按清空后的状态停表。
        countdown.startDiscount(
            MvDiscountModel(
                isNewUser = true,
                isEnteredDiscountPage = true,
                enteredDiscountPageExpireTime = nowMs() + 500,
                isEnteredSubscriptionPage = true,
            ),
        )
        val model = ConfigService.mvDiscountModel.copy(
            isNewUser = false,
            isEnteredDiscountPage = false,
            isEnteredSubscriptionPage = false,
            enteredDiscountPageExpireTime = 0L,
        )
        ConfigService.updateMvDiscountModel(model)
        model.saveToStorage()
        countdown.startDiscount(model)
    }
}

/** 定点格式化：千分位 + 固定小数位（intl 的 `#,##0.00`）。 */
internal fun formatAmount(value: Double, digits: Int): String {
    if (value.isNaN() || value.isInfinite()) return "0"
    var scale = 1L
    repeat(digits) { scale *= 10 }
    val scaled = (abs(value) * scale).roundToLong()
    val whole = (scaled / scale).toString()
    val grouped = StringBuilder()
    for (i in whole.indices) {
        if (i > 0 && (whole.length - i) % 3 == 0) grouped.append(',')
        grouped.append(whole[i])
    }
    val sb = StringBuilder()
    if (value < 0 && scaled != 0L) sb.append('-')
    sb.append(grouped)
    if (digits > 0) {
        sb.append('.').append((scaled % scale).toString().padStart(digits, '0'))
    }
    return sb.toString()
}

/** intl `NumberFormat.simpleCurrency(name: code).format(amount)`：货币符号 + 该货币的小数位。 */
internal fun simpleCurrency(amount: Double, currency: String): String {
    val code = currency.trim().uppercase().ifEmpty { "USD" }
    val symbol = when (code) {
        "USD", "CAD", "AUD", "HKD", "SGD", "NZD", "MXN" -> "$"
        "CNY", "JPY" -> "¥"
        "EUR" -> "€"
        "GBP" -> "£"
        "KRW" -> "₩"
        "TWD" -> "NT$"
        "INR" -> "₹"
        "RUB" -> "₽"
        "THB" -> "฿"
        "VND" -> "₫"
        "BRL" -> "R$"
        else -> code
    }
    val digits = when (code) {
        "JPY", "KRW", "VND" -> 0
        else -> 2
    }
    return symbol + formatAmount(amount, digits)
}
