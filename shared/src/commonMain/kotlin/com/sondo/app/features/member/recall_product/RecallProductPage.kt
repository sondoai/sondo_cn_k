package com.sondo.app.features.member.recall_product

import com.sondo.app.base.AppEnv
import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.i18n.trParams
import com.sondo.app.core.utils.shouldEndRecallCountdownOnProductLeave
import com.sondo.app.data.api.AppConfigApi
import com.sondo.app.data.models.GoodsModel
import com.sondo.app.data.models.ReturningOffer
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
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.service.pay.PayController
import com.sondo.app.service.pay.PayService
import com.sondo.app.service.pay.recallPeriodLabel
import com.sondo.app.service.pay.resolveRecallGoods
import com.sondo.app.ui.atoms.SDOfferCountdownDigits
import com.sondo.app.ui.atoms.VGap
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.directives.velseif
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.View

/**
 * 回归周卡商品页（Dart: features/member/presentation/pages/recall_product/recall_product_page.dart
 * + RecallProductController）。交互对齐折扣页，使用回归商品与回归 10 分钟倒计时（`OfferCountdown.recall*`）。
 *
 * 参数：无（首页订阅入口经 `RecallFlow.openProductFromHome()` 打开）。
 * 结果：购买成功由 PayService 关页并带回 `{"result": true}`。
 */
@Page(Routes.recallProduct)
internal class RecallProductPage : BasePager() {

    // --- 商品数据 ---

    /** 当前平台校验通过的回归商品。为空时不展示可购买价格。 */
    private var goods by observable<GoodsModel?>(null)

    /** 商品加载中。 */
    private var isLoading by observable(true)

    private var inRequest by observable(false)

    private var recallRemainingSeconds by observable(ConfigService.countdown.recallRemainingSeconds)

    private var pageViewLogged = false

    /** 最近一次回归优惠。离开页面时用它判断登录后还有没有资格。 */
    private var offer: ReturningOffer? = null

    private val discountPrice: String
        get() {
            val item = goods ?: return ""
            if (item.introductoryPrice.isNotEmpty() && (!AppEnv.isIOS || item.numberOfPeriods > 0)) {
                return item.introductoryPrice
            }
            return item.shopPrice
        }

    private val originalPrice: String
        get() {
            val item = goods ?: return ""
            return item.originalPrice.ifEmpty { item.shopPrice }
        }

    private val credits: Int? get() = goods?.points

    private val periodUnit: String get() = if (AppEnv.isIOS) "week".tr else recallPeriodLabel(goods)

    private val firstPeriodLabel: String
        get() = if (AppEnv.isIOS) "discount_first_week".tr else "discount_first_period".trParams("unit" to periodUnit)

    /** 下期价格和每期积分。缺原价或积分时不拼空金额。 */
    private val renewalNotice: String
        get() {
            val points = credits
            if (originalPrice.isNotEmpty() && points != null) {
                return "offer_renewal_notice".trParams(
                    "unit" to periodUnit,
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

    private val hasDiscount: Boolean
        get() {
            val item = goods ?: return false
            return item.introductoryPrice.isNotEmpty() && item.numberOfPeriods > 0
        }

    /** 倒计时结束或已是会员不能下单。Android 不要求商店详情，仍看接口是否允许买。 */
    private val canBuy: Boolean
        get() {
            if (goods == null || !ConfigService.countdown.recallOfferActive || UserStore.isSubscribed) return false
            if (!AppEnv.isIOS) return offer?.purchasable ?: false
            return goods?.productDetails != null
        }

    override fun created() {
        super.created()
        on(Events.OFFER_COUNTDOWN) { recallRemainingSeconds = it.optInt("recallRemainingSeconds", 0) }
        on(Events.PAY_RESULT) { json -> memberPayResult(json, Routes.recallProduct) { inRequest = false } }
        if (!pageViewLogged) {
            pageViewLogged = true
            // 首页订阅入口进来，不计为回归落地页访问。
            UmengUtils.logEventPageView(Routes.recallProduct, "home_subscription", null)
        }
        if (AppEnv.isIOS) PayService.resumePurchase(showRestoreTips = false)
        launch { initData() }
    }

    private suspend fun initData() {
        isLoading = true
        val result = AppConfigApi.fetchReturningOffer().getOrNull()
        // 先记下资格，等用户返回再停表。商店没对上但接口允许买时，离开也不停。
        offer = result
        // iOS 商店对不上时仍可展示接口价格，Android 必须有完整的支付宝商品。
        val loaded = if (result != null && !UserStore.isSubscribed) resolveRecallGoods(result) else null
        goods = loaded
        isLoading = false
    }

    /** 未登录先登录，回来后重查。超时或不可买则关闭，不发起支付。 */
    private fun subscribe() {
        if (isLoading || inRequest) return
        if (!ConfigService.countdown.recallOfferActive) {
            nav.pop()
            return
        }
        launch {
            var loggedIn = UserStore.isLogin
            if (!loggedIn) loggedIn = UserStore.showLoginDialog(this@RecallProductPage)
            if (!loggedIn) return@launch
            initData()
            if (!canBuy) {
                if (UserStore.isSubscribed) toast("already_subscribed_member".tr)
                nav.pop()
                return@launch
            }
            val item = goods ?: return@launch
            inRequest = true
            PayController.subscript(item, Routes.recallProduct) { inRequest = false }
        }
    }

    private fun closePage() = nav.pop()

    override fun pageWillDestroy() {
        // 登录后没资格，或恢复购买后已经是会员，返回时停掉回归倒计时。
        if (shouldEndRecallCountdownOnProductLeave(
                loggedIn = UserStore.isLogin,
                subscribed = UserStore.isSubscribed,
                purchasable = offer?.purchasable,
            )
        ) {
            ConfigService.countdown.endRecallOffer()
        }
        super.pageWillDestroy()
    }

    private fun priceInfo(): OfferPriceInfo = OfferPriceInfo(
        firstPeriodLabel = firstPeriodLabel,
        price = discountPrice,
        originalPrice = originalPrice,
        showDiscount = hasDiscount && !isLoading,
        credits = credits,
        unit = periodUnit,
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
                    View {
                        attr {
                            minHeight(viewport)
                            justifyContentFlexEnd()
                        }
                        vbind({ Pair(ctx.goods, ctx.isLoading) }) {
                            val item = ctx.goods
                            if (item == null) {
                                if (ctx.isLoading) OfferSkeleton() else View { attr { height(200f) } }
                            } else {
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
                                    SDOfferCountdownDigits {
                                        attr { remainingSeconds = ctx.recallRemainingSeconds }
                                    }
                                }
                            }
                        }
                        View {
                            attr { padding(20f, 20f, 30f, 20f) }
                            vif({ ctx.goods == null && ctx.isLoading }) {
                                OfferFooterSkeleton()
                            }
                            velseif({ ctx.goods != null }) {
                                View {
                                    attr { alignItemsCenter() }
                                    vbind({ ctx.renewalNotice }) {
                                        OfferRenewalNotice(ctx.renewalNotice)
                                    }
                                    VGap(12f)
                                    // 只在倒计时结束时收起按钮。点击后重查商品期间继续留紫色按钮。
                                    vif({ ctx.recallRemainingSeconds > 0 }) {
                                        OfferBuyButton({ ctx.subscribeLabel }) { ctx.subscribe() }
                                    }
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
