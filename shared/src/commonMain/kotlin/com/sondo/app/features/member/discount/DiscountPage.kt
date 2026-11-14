package com.sondo.app.features.member.discount

import com.sondo.app.base.AppEnv
import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.http.ApiResult
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.i18n.trParams
import com.sondo.app.core.utils.DevLog
import com.sondo.app.data.models.GoodsModel
import com.sondo.app.features.member.data.MemberApi
import com.sondo.app.features.member.memberPayResult
import com.sondo.app.features.member.widgets.OfferBuyButton
import com.sondo.app.features.member.widgets.OfferDiscountBackground
import com.sondo.app.features.member.widgets.OfferDiscountCloseButton
import com.sondo.app.features.member.widgets.OfferDiscountHeadline
import com.sondo.app.features.member.widgets.OfferFooterSkeleton
import com.sondo.app.features.member.widgets.OfferLegalLinks
import com.sondo.app.features.member.widgets.OfferPriceBlock
import com.sondo.app.features.member.widgets.OfferPriceInfo
import com.sondo.app.features.member.widgets.OfferRenewalNotice
import com.sondo.app.features.member.widgets.OfferSkeleton
import com.sondo.app.service.ConfigService
import com.sondo.app.service.UserStore
import com.sondo.app.service.pay.PayController
import com.sondo.app.service.pay.PayService
import com.sondo.app.ui.atoms.SDOfferCountdownDigits
import com.sondo.app.ui.atoms.VGap
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.View

/**
 * 限时折扣订阅页（Dart: features/member/presentation/pages/discount/discount_page.dart + DiscountController）。
 * 展示幸运月卡（Android / 鸿蒙：`cn_luck_monthly`）或幸运周卡（iOS）的首期价与 ConfigService 的实时倒计时。
 *
 * 参数：无。打开方：订阅页返回分流（`nav.replace(Routes.discount)`）、首页倒计时入口。
 * 结果：购买成功由 PayService 关页并带回 `{"result": true}`。
 */
@Page(Routes.discount)
internal class DiscountPage : BasePager() {

    // --- 商品数据 ---

    /** 当前平台的折扣订阅商品。 */
    private var discountGoods by observable<GoodsModel?>(null)

    /** 商品加载中 */
    private var isLoading by observable(true)

    /** 本页发起的支付还没有结果。 */
    private var inRequest by observable(false)

    private var remainingSeconds by observable(ConfigService.countdown.remainingSeconds)

    private val discountPrice: String
        get() {
            val goods = discountGoods ?: return ""
            if (goods.introductoryPrice.isNotEmpty() && goods.numberOfPeriods > 0) return goods.introductoryPrice
            return goods.shopPrice
        }

    private val originalPrice: String
        get() {
            val goods = discountGoods ?: return ""
            return goods.originalPrice.ifEmpty { goods.shopPrice }
        }

    /** 幸运月卡按月，iOS 折扣页仍按周。 */
    private val billingUnit: String
        get() {
            if (AppEnv.isIOS) return "week".tr
            return if (discountGoods?.productSubscriptionPeriod == 3) "year".tr else "month".tr
        }

    /** 价格行和标题用的首期，例如「首月」「首周」。 */
    private val firstPeriodLabel: String
        get() = if (AppEnv.isIOS) "discount_first_week".tr else "discount_first_period".trParams("unit" to billingUnit)

    /** 下期价格和每期积分。缺原价或积分时不拼空金额。 */
    private val renewalNotice: String
        get() {
            val points = credits
            if (originalPrice.isNotEmpty() && points != null) {
                return "offer_renewal_notice".trParams(
                    "unit" to billingUnit,
                    "price" to originalPrice,
                    "points" to "$points",
                )
            }
            return "cancel_anytime_auto_renewable".tr
        }

    /** Android 按钮带首期价；iOS 仍走商店订阅文案。 */
    private val subscribeLabel: String
        get() {
            val price = discountPrice
            if (!AppEnv.isIOS && price.isNotEmpty()) return "offer_auto_renew_cta".trParams("price" to price)
            return "subscribe".tr
        }

    /** 每个订阅周期发放的积分，商品未加载时不显示额度。 */
    private val credits: Int? get() = discountGoods?.points

    private val hasDiscount: Boolean
        get() {
            val goods = discountGoods ?: return false
            return goods.introductoryPrice.isNotEmpty() && goods.numberOfPeriods > 0
        }

    override fun created() {
        super.created()
        on(Events.OFFER_COUNTDOWN) { remainingSeconds = it.optInt("remainingSeconds", 0) }
        on(Events.PAY_RESULT) { json ->
            memberPayResult(json, Routes.discount) { inRequest = false }
        }
        if (AppEnv.isIOS) PayService.resumePurchase(showRestoreTips = false)
        initData()
    }

    private fun initData() {
        // 商品未到达时由页内骨架承接，避免全屏加载层挡住计时和关闭入口。
        isLoading = true
        launch {
            val platform = if (AppEnv.isIOS) 2 else 12
            val loadedList = when (val res = MemberApi.getProductList(productType = 2, platform = platform, isRate = true)) {
                is ApiResult.Success ->
                    PayController.getShopList(2, isRate = true, localShopList = res.data) ?: emptyList()
                is ApiResult.Failure -> {
                    DevLog.logger("getProductList error: ${res.error}")
                    emptyList()
                }
            }
            if (loadedList.isNotEmpty()) {
                DevLog.logger("list: ${loadedList[0].toJson()}")
                // Android 折扣页只卖幸运月卡，不再按周卡周期筛选。
                discountGoods = if (!AppEnv.isIOS) {
                    loadedList.firstOrNull { it.productId == "cn_luck_monthly" }
                } else {
                    loadedList.firstOrNull { it.productSubscriptionPeriod == 1 } ?: loadedList.first()
                }
            }
            // 空商品或请求失败仍保留骨架；到货时切换内容。
            isLoading = false
        }
    }

    /** 发起折扣订阅；超时或未登录则拦截 */
    private fun subscribe() {
        val model = ConfigService.mvDiscountModel
        if (model.enteredDiscountPageExpireTime == 0L || model.isTimeOut) {
            toast((if (AppEnv.isIOS) "lucky_weekly_time_out" else "lucky_monthly_time_out").tr)
            nav.pop()
            return
        }
        val goods = discountGoods
        if (goods == null) {
            toast("please_select_a_plan".tr)
            return
        }
        if (inRequest) return
        launch {
            var isLogin = UserStore.isLogin
            if (!isLogin) isLogin = UserStore.showLoginDialog(this@DiscountPage)
            if (!isLogin) return@launch
            if (UserStore.isSubscribed) {
                toast("already_subscribed_member".tr)
                nav.pop()
                return@launch
            }
            inRequest = true
            PayController.subscript(goods, Routes.discount) { inRequest = false }
        }
    }

    /** 关闭折扣页，不修改当前限时优惠的计时状态。 */
    private fun closePage() = nav.pop()

    private fun priceInfo(): OfferPriceInfo = OfferPriceInfo(
        firstPeriodLabel = firstPeriodLabel,
        price = discountPrice,
        originalPrice = originalPrice,
        showDiscount = hasDiscount && !isLoading,
        credits = credits,
        unit = billingUnit,
    )

    override fun content(): ViewBuilder {
        val ctx = this
        val viewport = ctx.screenHeight - ctx.statusBarHeight - 56f - ctx.safeBottom
        return {
            attr { backgroundColor(UIColor.c0A0A0A) }
            OfferDiscountBackground(ctx.screenWidth)
            View {
                attr {
                    flex(1f)
                    paddingTop(ctx.statusBarHeight)
                    paddingBottom(ctx.safeBottom)
                }
                View {
                    attr {
                        height(56f)
                        flexDirectionRow()
                        justifyContentFlexEnd()
                        alignItemsCenter()
                        paddingRight(16f)
                    }
                    OfferDiscountCloseButton { ctx.closePage() }
                }
                Scroller {
                    attr {
                        flex(1f)
                        showScrollerIndicator(false)
                    }
                    // 内容在常规屏幕贴底，短屏时允许整块滚动。
                    View {
                        attr {
                            minHeight(viewport)
                            justifyContentFlexEnd()
                        }
                        vbind({ Pair(ctx.discountGoods, ctx.isLoading) }) {
                            vif({ ctx.discountGoods == null }) {
                                OfferSkeleton()
                            }
                            velse {
                                View {
                                    attr {
                                        padding(0f, 20f, 30f, 20f)
                                        alignItemsCenter()
                                    }
                                    // 没有首期优惠时不占标题位，避免空出一块大标题高度。
                                    if (!ctx.isLoading && ctx.hasDiscount) {
                                        OfferDiscountHeadline("offer_period_benefit".trParams("period" to ctx.firstPeriodLabel))
                                        VGap(36f)
                                    }
                                    OfferPriceBlock(ctx.priceInfo())
                                    VGap(36f)
                                    // 直接使用现有秒数，计时边界与订阅超时规则保持一致。
                                    SDOfferCountdownDigits {
                                        attr { remainingSeconds = ctx.remainingSeconds }
                                    }
                                }
                            }
                        }
                        View {
                            attr { padding(20f, 20f, 30f, 20f) }
                            // 商品未就绪时，续费金额与购买按钮都不能出现空值或可点击状态。
                            vif({ ctx.discountGoods == null }) {
                                OfferFooterSkeleton()
                            }
                            velse {
                                View {
                                    attr { alignItemsCenter() }
                                    vbind({ ctx.renewalNotice }) {
                                        OfferRenewalNotice(ctx.renewalNotice)
                                    }
                                    VGap(12f)
                                    OfferBuyButton({ ctx.subscribeLabel }) { ctx.subscribe() }
                                }
                            }
                            VGap(12f)
                            OfferLegalLinks({ UserStore.openTerms(ctx) }, { UserStore.openPrivacyPolicy(ctx) })
                        }
                    }
                }
            }
        }
    }
}
