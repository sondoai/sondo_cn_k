package com.sondo.app.service.pay

import com.sondo.app.base.AppEnv
import com.sondo.app.base.currentPager
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.i18n.trParams
import com.sondo.app.core.utils.DevLog
import com.sondo.app.data.models.GoodsModel

/**
 * 加载平台商品，将页面明确选择的商品交给全局支付服务
 * （Dart: lib/shared/controller/pay_controller.dart `PayController`）。
 *
 * 参考工程里它是每个页面一份的 GetxController，`isInRequest` 是页面级的加载状态；
 * 这里是无状态的 object：页面在自己的协程里调用 [getShopList]，加载状态由页面的 observable 自己记。
 */
object PayController {

    /**
     * 获取商品列表：2-会员商品，1-积分。在页面自己的协程里调用。
     *
     * - Android / 鸿蒙：国内商品接口（platform=12），价格用接口返回值，不查商店；空列表是最终结果。
     * - iOS：`iosPaymentOptions` + StoreKit 详情；带上商店价、推介优惠（有资格时）；商店查不到的商品剔除。
     *
     * @param applePlatform 仅 iOS，订阅/积分为 2。
     * @return 空列表或出错时返回 null（[showToast] 时提示 `product_not_found`）。
     */
    suspend fun getShopList(
        productType: Int,
        showToast: Boolean = true,
        localShopList: List<GoodsModel>? = null,
        isRate: Boolean = false,
        applePlatform: Int = 2,
    ): List<GoodsModel>? {
        PayUi.registerCurrent()
        try {
            val isAndroid = !AppEnv.isIOS
            var shopList: MutableList<GoodsModel> = (localShopList ?: emptyList()).toMutableList()
            if (localShopList == null || (!isAndroid && shopList.isEmpty())) {
                // 国内空列表是最终结果；iOS 保留原有的空列表重取逻辑。积分包同样走国内商品。
                shopList = fetchRemoteShopList(productType, isRate, applePlatform).toMutableList()
            }
            val ids = shopList.mapNotNull { it.productId }.toSet()
            DevLog.logger("shopList====$ids")
            var pdRes: ProductDetailsResponse? = null
            if (!isAndroid && ids.isNotEmpty()) {
                pdRes = PayService.getProduct(ids)
                // 商店不可用：参考工程此处抛错，整个列表按失败处理。
                if (pdRes.error != null) return null
            }
            if (pdRes != null && pdRes.productDetails.isNotEmpty()) {
                // iOS 获取优惠信息
                for (shop in shopList) {
                    val pd = pdRes.productDetails.firstOrNull { it.id == shop.productId }
                    shop.productDetails = pd
                    shop.introductoryPrice = ""
                    shop.numberOfPeriods = 0
                    if (pd == null) continue
                    shop.originalPrice = pd.price
                    val offer = pd.introductoryOffer()
                    if (offer == null || offer.periodCount <= 0) continue
                    try {
                        if (PayStore.isIntroductoryOfferEligible(pd.id)) {
                            shop.introductoryPrice = offer.displayPrice(pd)
                            shop.numberOfPeriods = offer.periodCount
                        }
                    } catch (e: Throwable) {
                        // 资格查询失败时沿用上面已设置的正常价格。
                    }
                }
            }
            // iOS 对不上商店的商品无法拉起支付，直接剔除。Android 用接口价格，不查商店。
            if (AppEnv.isIOS) {
                shopList.removeAll { it.productDetails == null }
            }
            if (shopList.isEmpty()) {
                if (showToast) currentPager()?.toast("product_not_found".tr)
                return null
            }
            return shopList
        } catch (e: Throwable) {
            DevLog.logger("getShopList error: $e")
            return null
        }
    }

    /** 商店页远程商品。 */
    private suspend fun fetchRemoteShopList(productType: Int, isRate: Boolean, applePlatform: Int): List<GoodsModel> {
        if (!AppEnv.isIOS) {
            return PayBackend.getCnSubscriptionProducts(productType = productType, isRate = isRate) ?: emptyList()
        }
        val options = PayBackend.getIosPaymentOptions(
            productType = productType,
            applePlatform = applePlatform,
            isRate = isRate,
        )
        return options?.products ?: emptyList()
    }

    /** 购买当前页面选中的积分包，避免其他页面覆盖本次商品。 */
    fun purchase(goods: GoodsModel, pageName: String, onResult: ((PayOutcome) -> Unit)? = null) {
        PayService.submit(goods, false, pageName, onResult)
    }

    /** 订阅或升级当前页面明确选择的套餐。 */
    fun subscript(goods: GoodsModel, pageName: String, onResult: ((PayOutcome) -> Unit)? = null) {
        PayService.submit(goods, true, pageName, onResult)
    }
}

/** 支付宝付款说明：首期、续费、周期、积分，以及去设置里取消。 */
fun alipayRenewNoticeFor(goods: GoodsModel): String {
    val period = when (goods.productSubscriptionPeriod) {
        3 -> "year".tr
        else -> "month".tr
    }
    val renewal = goods.originalPrice.ifEmpty { goods.shopPrice }
    val first = goods.introductoryPrice.ifEmpty { goods.shopPrice }
    if (first.isEmpty() && renewal.isEmpty()) return ""
    return "alipay_auto_renew_notice".trParams(
        "first" to first,
        "period" to period,
        "renewal" to renewal.ifEmpty { first },
        "points" to "${goods.points ?: 0}",
    )
}
