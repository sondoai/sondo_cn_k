package com.sondo.app.service.pay

import com.sondo.app.base.AppEnv
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.recallIntroUsd
import com.sondo.app.data.models.GoodsModel
import com.sondo.app.data.models.ProductDetails
import com.sondo.app.data.models.ProductInfo
import com.sondo.app.data.models.ReturningOffer

/*
 * 回归周卡要展示 / 购买哪个商品（Dart: lib/features/member/presentation/recall_goods.dart）。
 * iOS 核验商店促销，Android / 鸿蒙 使用支付宝后端商品；购买资格不影响登录前展示。
 * 这些函数会查商店，在页面自己的协程里调用。
 */

private val weeklyWord = Regex("\\bWeekly\\b", RegexOption.IGNORE_CASE)
private val weekWord = Regex("\\bWeek\\b", RegexOption.IGNORE_CASE)

/** 国内没有周订阅：商品名里的「周」统一改成「月」。 */
internal fun weekNameToMonth(name: String): String = name
    .replace("周卡", "月卡")
    .replace("周订阅", "月订阅")
    .replace("每周", "每月")
    .replace(weeklyWord, "Monthly")
    .replace(weekWord, "Month")

/** 可购买的回归商品：iOS 必须在商店里核到对应的促销优惠；核不到返回 null。 */
suspend fun loadRecallGoods(offer: ReturningOffer): GoodsModel? {
    if (!offer.visible || !offer.isComplete(ios = AppEnv.isIOS)) {
        DevLog.logger(
            "recall offer skipped visible=${offer.visible} canPurchase=${offer.canPurchase} " +
                "reason=${offer.blockReason} cycle=${offer.returnCycleId}",
        )
        return null
    }
    if (!AppEnv.isIOS) return recallDisplayGoods(offer)
    val productId = offer.productId
    try {
        if (!PayStore.isAvailable()) return null
        val response = PayStore.queryProductDetails(setOf(productId))
        val details = response.productDetails.firstOrNull { it.id == productId } ?: return null
        val local = ProductInfo.productInfoMap[productId] ?: GoodsModel(
            productId = productId,
            productName = "Welcome Back Weekly VIP",
            productPrice = recallIntroUsd,
            productType = 2,
            productSubscriptionPeriod = 1,
            points = 888,
        )
        val goods = local.copy(
            productId = productId,
            productName = if (offer.displayName.isEmpty()) local.productName else offer.displayName,
            productDetails = details,
            points = offer.points,
            productPrice = recallIntroUsd,
        )
        val promo = applePromo(details, offer.offerId)
        if (promo == null) {
            DevLog.logger("recall goods missing promotional offer: $productId")
            return null
        }
        fillApple(goods, details, promo)
        goods.appleOfferId = promo.id.ifEmpty { offer.offerId }
        return goods
    } catch (e: Throwable) {
        DevLog.logger("loadRecallGoods: $e")
        return null
    }
}

/** 商店优惠没对上时仍用接口价格展示。没有 product 或不允许展示时返回 null。 */
fun recallDisplayGoods(offer: ReturningOffer): GoodsModel? {
    if (!offer.visible || !offer.hasProduct || offer.productId.isEmpty()) return null
    val alipay = offer.paymentProvider.uppercase() == "ALIPAY"
    if (alipay && !offer.isComplete(ios = false)) return null
    val intro = money(offer.firstPrice, offer.currency)
    val renewal = money(offer.renewalPrice, offer.currency)
    val discounted = if (alipay) {
        (offer.firstPrice ?: 0.0) < (offer.renewalPrice ?: 0.0)
    } else {
        intro.isNotEmpty() && renewal.isNotEmpty() && intro != renewal
    }
    val name = offer.displayName.ifEmpty { if (alipay) "pro_member".tr else "Welcome Back Weekly VIP" }
    return GoodsModel(
        productId = offer.productId,
        productName = if (alipay) weekNameToMonth(name) else name,
        productPrice = offer.firstPrice ?: recallIntroUsd,
        productType = 2,
        productSubscriptionPeriod = if (alipay) offer.subscriptionPeriod else 1,
        points = if (alipay) offer.points else (if (offer.points > 0) offer.points else 888),
        introductoryPrice = intro,
        originalPrice = renewal,
        numberOfPeriods = if (discounted) 1 else 0,
    )
}

/** 回归商品按归一化后的周期展示，国内缺省按月，Apple 缺省按周。 */
fun recallPeriodLabel(goods: GoodsModel?): String = when (goods?.productSubscriptionPeriod) {
    1 -> "week".tr
    2 -> "month".tr
    3 -> "year".tr
    else -> if (AppEnv.isIOS) "week".tr else "month".tr
}

/** 商品页沿用平台校验；iOS 商店核验失败时仍可展示接口价格，但不能下单（没有 productDetails）。 */
suspend fun resolveRecallGoods(offer: ReturningOffer): GoodsModel? {
    if (!AppEnv.isIOS) return loadRecallGoods(offer)
    val verified = if (offer.canLoadStoreProduct) loadRecallGoods(offer) else null
    return verified ?: recallDisplayGoods(offer)
}

/** 只认促销优惠，并且付款方式仍是按周期付，不用首购试用。 */
private fun applePromo(details: ProductDetails, offerId: String): StoreOffer? {
    if (offerId.isEmpty()) return null
    for (item in details.subscriptionOffers()) {
        if (!item.isPromotional) continue
        if (item.paymentMode != StoreOffer.MODE_PAY_AS_YOU_GO) continue
        if (item.id != offerId || item.periodCount <= 0) continue
        return item
    }
    return null
}

private fun fillApple(goods: GoodsModel, details: ProductDetails, offer: StoreOffer) {
    goods.originalPrice = details.price
    goods.introductoryPrice = offer.displayPrice(details)
    goods.numberOfPeriods = offer.periodCount
}

private fun money(amount: Double?, currency: String): String {
    if (amount == null) return ""
    return simpleCurrency(amount, currency)
}
