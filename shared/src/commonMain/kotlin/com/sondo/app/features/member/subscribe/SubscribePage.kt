package com.sondo.app.features.member.subscribe

import com.sondo.app.base.AppEnv
import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.constants.asset
import com.sondo.app.core.http.ApiResult
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.i18n.trParams
import com.sondo.app.core.storage.Sp
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.nowMs
import com.sondo.app.data.models.GoodsModel
import com.sondo.app.features.member.data.MemberApi
import com.sondo.app.features.member.memberPayResult
import com.sondo.app.features.member.subscribe.widgets.BenefitMarquee
import com.sondo.app.service.ConfigService
import com.sondo.app.service.UserStore
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.service.pay.PayController
import com.sondo.app.service.pay.PayService
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.atoms.SDGradientButton
import com.sondo.app.ui.atoms.SDShimmer
import com.sondo.app.ui.atoms.VGap
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.event.layoutFrameDidChange
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vforIndex
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/** 约对齐原 14s 一轮的观感（逻辑像素 / 秒） */
private const val MARQUEE_VELOCITY = 40f

/**
 * 订阅页（Dart: features/member/presentation/pages/subscribe/subscribe_page.dart + SubscribeV2View + SubscribeController）。
 * 参考工程按 `personalCenterVersion` 分发新旧版，但入口固定渲染新版（`SubscribeV2View`），旧版 `SubscribeClassicView` 无引用，未移植。
 *
 * 参数：`{skipDiscountOnBack?: Boolean}` —— true 时返回不分流到折扣页（播放页等入口）。
 * 结果：订阅成功由 PayService 关页并带回 `{"result": true}`；返回分流到折扣页时本页被替换，打开方收到 null。
 */
@Page(Routes.subscribe)
internal class SubscribePage : BasePager() {

    // --- 套餐数据 ---

    /** 订阅商品列表 */
    private val goodsList by observableList<GoodsModel>()

    /** 当前选中的订阅套餐索引 */
    private var selectedPlanIndex by observable(0)

    /** 选中套餐赠送积分数 */
    private var points by observable(0)

    /** 选中套餐订阅周期（1 周 / 2 月 / 3 年） */
    private var productSubscriptionPeriod by observable(0)

    /** 本页发起的支付还没有结果（PayController.isInRequest）。 */
    private var isLoading by observable(false)

    /** 滚动区实际高度（布局回调）：内容最小高度取它，英雄区 + 套餐贴底，与参考工程 LayoutBuilder 一致。 */
    private var scrollViewport by observable(0f)

    fun viewport(): Float = scrollViewport

    fun onViewport(height: Float) {
        if (height > 0f && kotlin.math.abs(height - scrollViewport) >= 0.5f) scrollViewport = height
    }

    /** 从播放页等入口进入时，返回不触发折扣页。 */
    private var skipDiscountOnBack = false

    override fun created() {
        super.created()
        skipDiscountOnBack = pagerData.params.optBoolean("skipDiscountOnBack", false)
        on(Events.PAY_RESULT) { json -> memberPayResult(json, Routes.subscribe) { isLoading = false } }
        if (AppEnv.isIOS) PayService.resumePurchase(showRestoreTips = false)
        // 标记用户曾进入订阅页，供试用视频末段订阅卡片分流
        Sp.setBool(StorageKey.hasVisitedSubscribePage, true)
        launch { initData() }
    }

    override fun interceptBack(): Boolean = true

    override fun onBackPressed(): Boolean {
        onBack()
        return true
    }

    private suspend fun initData() {
        // Android 拉国内支付宝会员商品；iOS 仍用 Apple 商品。
        val platform = if (AppEnv.isIOS) 2 else 12
        val list = when (val res = MemberApi.getProductList(productType = 2, platform = platform)) {
            is ApiResult.Success -> PayController.getShopList(2, localShopList = res.data)
            is ApiResult.Failure -> {
                DevLog.logger("getProductList error: ${res.error}")
                emptyList()
            }
        }
        goodsList.clear()
        goodsList.addAll(list ?: emptyList())
        DevLog.logger("goodsList====$list")
        // 异步刷新用户信息（积分 / 会员等），不 await
        launch { UserStore.getUserInfo() }
        if (goodsList.isNotEmpty()) selectPlan(0)
    }

    /** 切换本页选中的套餐并更新积分和埋点。 */
    fun selectPlan(index: Int) {
        if (index !in 0 until goodsList.size) return
        selectedPlanIndex = index
        val goods = goodsList[index]
        points = goods.points ?: 0
        productSubscriptionPeriod = goods.productSubscriptionPeriod ?: 0
        DevLog.logger("goods====${goods.toJson()}")
        UmengUtils.logEvent(
            "switch_products",
            mapOf("productId" to (goods.productId ?: ""), "productType" to (goods.productType ?: "")),
        )
    }

    /** 发起订阅；已订阅用户提示并返回 */
    fun subscribe() {
        val index = selectedPlanIndex
        if (index < 0 || index >= goodsList.size) {
            toast("please_select_a_plan".tr)
            return
        }
        if (isLoading) return
        // 保存用户本次点击的套餐，登录或其他订阅页不能替换本次商品。
        val goods = goodsList[index]
        launch {
            var isLogin = UserStore.isLogin
            if (!isLogin) isLogin = UserStore.showLoginDialog(this@SubscribePage)
            if (!isLogin) return@launch
            if (UserStore.isSubscribed) {
                toast("already_subscribed_member".tr)
                nav.pop()
                return@launch
            }
            isLoading = true
            PayController.subscript(goods, Routes.subscribe) { isLoading = false }
        }
    }

    /** 返回时：新用户且折扣有效则跳转折扣页，否则直接返回 */
    fun onBack() {
        if (skipDiscountOnBack) {
            nav.pop()
            return
        }
        val discount = ConfigService.mvDiscountModel
        val isTimeOut = discount.isTimeOut
        val discountOpen = ConfigService.discountOpen
        val userType = UserStore.userInfo?.memberType ?: 1
        val isNewUser = discount.isNewUser
        DevLog.logger(
            "isNewUser====$isNewUser isTimeOut====$isTimeOut discountOpen====$discountOpen userType====$userType",
        )
        if (userType == 1 && isNewUser && !isTimeOut && discountOpen) {
            val expireTime = nowMs() + 10 * 60 * 1000L
            val model = discount.copy(
                isEnteredSubscriptionPage = true,
                isEnteredDiscountPage = true,
                enteredDiscountPageExpireTime = expireTime,
            )
            ConfigService.updateMvDiscountModel(model)
            model.saveToStorage()
            ConfigService.countdown.startDiscount(model)
            nav.replace(Routes.discount)
        } else {
            nav.pop()
        }
    }

    fun creditsLabel(): String {
        val p = points
        if (p <= 0) return "credits".tr
        return "${groupThousands(p)} ${"credits".tr} ${periodWord(productSubscriptionPeriod)}"
    }

    fun isSelected(index: Int): Boolean = selectedPlanIndex == index

    fun goodsListEmpty(): Boolean = goodsList.isEmpty()

    fun goods() = goodsList

    fun loading(): Boolean = isLoading

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            attr { backgroundColor(Color.BLACK) }
            Image {
                attr {
                    absolutePosition(top = 0f, left = 0f)
                    size(ctx.screenWidth, ctx.screenWidth * 489f / 870f)
                    src(AssetsImage.subscribeV2Bg.asset())
                    resizeCover()
                }
            }
            View {
                attr {
                    absolutePosition(top = 119f, left = 0f, right = 0f)
                    height(280f)
                    backgroundLinearGradient(
                        Direction.TO_BOTTOM,
                        ColorStop(Color.TRANSPARENT, 0f),
                        ColorStop(Color(0, 0, 0, 0.85f), 0.45f),
                        ColorStop(Color.BLACK, 1f),
                    )
                }
            }
            View {
                attr {
                    flex(1f)
                    paddingTop(ctx.statusBarHeight)
                    paddingBottom(ctx.safeBottom)
                }
                // 关闭栏
                View {
                    attr {
                        padding(4f, 12f, 4f, 12f)
                        flexDirectionRow()
                        justifyContentFlexEnd()
                    }
                    View {
                        attr {
                            size(28f, 28f)
                            borderRadius(14f)
                            backgroundColor(SDColors.white20)
                            allCenter()
                        }
                        event { click { ctx.onBack() } }
                        // 参考工程是白 20% 圆底 + Material Icons.close(16)。close_3 自带半透明圆底，
                        // 再叠这层底就成了双层圆；这里用纯 × 的 close.png（× 占画布约 50%，18 ≈ Icons.close 16 的笔画大小）
                        Image {
                            attr {
                                size(18f, 18f)
                                src(AssetsImage.close.asset())
                                resizeContain()
                            }
                        }
                    }
                }
                Scroller {
                    attr {
                        flex(1f)
                        showScrollerIndicator(false)
                    }
                    event { layoutFrameDidChange { ctx.onViewport(it.height) } }
                    // 内容最小高度 = 滚动区实际高度：英雄区 + 套餐贴底；短屏上内容更高时改为滚动
                    View {
                        attr {
                            // Scroller 内容不会自动撑满横向：显式给屏宽，标题才能真正居中（之前按内容宽度、标题靠左）
                            width(ctx.screenWidth)
                            minHeight(ctx.viewport())
                            justifyContentFlexEnd()
                        }
                        heroSection(ctx)
                        plansSection(ctx)
                    }
                }
            }
        }
    }
}

private fun groupThousands(value: Int): String {
    val s = value.toString()
    val sb = StringBuilder()
    for (i in s.indices) {
        if (i > 0 && (s.length - i) % 3 == 0 && s[i - 1] != '-') sb.append(',')
        sb.append(s[i])
    }
    return sb.toString()
}

private fun periodWord(period: Int): String = when (period) {
    1 -> "weekly".tr.lowercase()
    2 -> "monthly".tr.lowercase()
    3 -> "yearly".tr.lowercase()
    else -> ""
}

private fun planTitle(period: Int?, productId: String): String {
    if (AppConstants.superWeeklyPlan.contains(productId)) return "super_weekly".tr
    return when (period) {
        1 -> "weekly".tr
        2 -> "monthly".tr
        3 -> "yearly".tr
        else -> ""
    }
}

private fun planCreditsSub(item: GoodsModel): String {
    val p = item.points ?: 0
    if (p <= 0) return ""
    return "${groupThousands(p)} ${"credits".tr}".lowercase()
}

private fun ViewContainer<*, *>.heroSection(ctx: SubscribePage) {
    View {
        attr {
            alignSelfStretch()
            padding(0f, 20f, 0f, 20f)
        }
        Text {
            attr {
                alignSelfStretch()
                text("subscribe_creative_journey".tr)
                fontSize(24f)
                lineHeight(38f)
                fontFamily("Poppins-SemiBold")
                color(Color.WHITE)
                textAlignCenter()
                lines(3)
            }
        }
    }
    VGap(30f)
    // 权益跑马灯全宽；credits 文案随套餐变，只刷新那一段文字，不重启动画
    BenefitMarquee {
        attr {
            alignSelfStretch()
            height(38f)
            velocity = MARQUEE_VELOCITY
            reverse = false
            builder = {
                benefitChip(AssetsImage.subscribeV2ChipPriority, { "priority_generation".tr })
                View { attr { width(10f) } }
                benefitChip(AssetsImage.subscribeV2ChipCredits, { ctx.creditsLabel() })
                View { attr { width(10f) } }
                benefitChip(AssetsImage.subscribeV2ChipWatermark, { "mv_without_watermark".tr })
            }
        }
    }
    VGap(20f)
    BenefitMarquee {
        attr {
            alignSelfStretch()
            height(38f)
            velocity = MARQUEE_VELOCITY
            reverse = true
            builder = {
                benefitChip(AssetsImage.subscribeV2ChipPremium, { "premium_tools".tr })
                View { attr { width(10f) } }
                benefitChip(AssetsImage.subscribeV2ChipLicense, { "commercial_license".tr })
                View { attr { width(10f) } }
                benefitChip(AssetsImage.subscribeV2ChipDownload, { "enable_download_mv_song".tr })
            }
        }
    }
    VGap(24f)
}

private fun ViewContainer<*, *>.benefitChip(icon: String, label: () -> String) {
    View {
        attr {
            height(38f)
            padding(0f, 12f, 0f, 16f)
            flexDirectionRow()
            alignItemsCenter()
            backgroundColor(SDColors.white5)
            borderRadius(100f)
            border(Border(1f, BorderStyle.SOLID, SDColors.white10))
        }
        Image {
            attr {
                size(16f, 16f)
                src(icon.asset())
                // 单色图标：只用 alpha，避免导出底色渗入
                tintColor(SDColors.white80)
                resizeContain()
            }
        }
        View { attr { width(8f) } }
        Text {
            attr {
                text(label())
                fontSize(12f)
                fontFamily("Poppins-Medium")
                color(SDColors.white80)
                lines(1)
            }
        }
    }
}

private fun ViewContainer<*, *>.plansSection(ctx: SubscribePage) {
    View {
        attr { alignItemsCenter() }
        View {
            attr {
                alignSelfStretch()
                padding(0f, 20f, 0f, 20f)
            }
            vif({ ctx.goodsListEmpty() }) {
                View {
                    for (i in 0 until 3) {
                        if (i > 0) VGap(20f)
                        SDShimmer {
                            attr {
                                height(70f)
                                radius = 16f
                                baseColor = SDColors.white20
                                highlightColor = SDColors.white10
                            }
                        }
                    }
                }
            }
            velse {
                View {
                    vforIndex({ ctx.goods() }) { item, index, _ ->
                        View {
                            attr { if (index > 0) marginTop(20f) }
                            planCard(ctx, item, index)
                        }
                    }
                }
            }
        }
        VGap(30f)
        Text {
            attr {
                text("cancel_anytime_auto_renewable".tr)
                fontSize(10f)
                fontFamily("Poppins-Medium")
                color(SDColors.white50)
                lines(1)
            }
        }
        VGap(12f)
        View {
            attr { alignSelfStretch() }
            vif({ ctx.goodsListEmpty() }) {
                View {
                    attr { padding(0f, 20f, 0f, 20f) }
                    SDShimmer {
                        attr {
                            height(48f)
                            radius = 48f
                            baseColor = SDColors.white20
                            highlightColor = SDColors.white10
                        }
                    }
                }
            }
            velse {
                SDGradientButton {
                    attr {
                        padding(0f, 20f, 0f, 20f)
                        height(48f)
                        radius(24f)
                        isLoading = ctx.loading()
                        child = {
                            Text {
                                attr {
                                    text("subscribe".tr)
                                    fontSize(16f)
                                    fontFamily("Poppins-SemiBold")
                                    color(UIColor.c0A0A0A)
                                    lines(1)
                                }
                            }
                        }
                    }
                    event { onTap { ctx.subscribe() } }
                }
            }
        }
        VGap(12f)
        footerLinks(ctx)
        VGap(10f)
    }
}

/** 条款 / 隐私 / 恢复购买：长文案自动换行。 */
private fun ViewContainer<*, *>.footerLinks(ctx: SubscribePage) {
    val items = ArrayList<Pair<String, () -> Unit>>()
    items.add("terms_of_use".tr to { UserStore.openTerms(ctx) })
    items.add("privacy_policy".tr to { UserStore.openPrivacyPolicy(ctx) })
    if (AppEnv.isIOS) items.add("restore".tr to { PayService.resumePurchase() })
    View {
        attr {
            alignSelfStretch()
            padding(0f, 20f, 0f, 20f)
            flexDirectionRow()
            flexWrapWrap()
            justifyContentCenter()
            alignItemsCenter()
        }
        items.forEachIndexed { i, (label, onTap) ->
            if (i > 0) {
                Text {
                    attr {
                        text("|")
                        fontSize(10f)
                        lineHeight(11f)
                        fontFamily("Poppins-Medium")
                        color(Color(0x80FFFFFFL))
                        margin(0f, 8f, 0f, 8f)
                    }
                }
            }
            Text {
                attr {
                    text(label)
                    fontSize(10f)
                    lineHeight(11f)
                    fontFamily("Poppins-Medium")
                    color(Color(0x80FFFFFFL))
                    textDecorationUnderLine()
                    textAlignCenter()
                    marginTop(3f)
                    marginBottom(3f)
                }
                event { click { onTap() } }
            }
        }
    }
}

private fun ViewContainer<*, *>.planCard(ctx: SubscribePage, item: GoodsModel, index: Int) {
    val isIntroductory = item.introductoryPrice.isNotEmpty() && item.numberOfPeriods > 0
    View {
        attr {
            minHeight(68f)
            padding(if (isIntroductory) 10f else 0f, 16f, if (isIntroductory) 10f else 0f, 16f)
            borderRadius(16f)
            justifyContentCenter()
            val selected = ctx.isSelected(index)
            backgroundColor(if (selected) SDColors.white5 else SDColors.white10)
            border(Border(1.5f, BorderStyle.SOLID, if (selected) Color(0xFFF3EFF7L) else Color.TRANSPARENT))
        }
        event { click { ctx.selectPlan(index) } }
        View {
            attr {
                flexDirectionRow()
                alignItemsCenter()
                if (!isIntroductory) height(68f)
            }
            View {
                attr {
                    flex(1f)
                    justifyContentCenter()
                }
                Text {
                    attr {
                        text(planTitle(item.productSubscriptionPeriod, item.productId ?: ""))
                        fontSize(14f)
                        fontFamily("Poppins-Bold")
                        color(Color.WHITE)
                        lines(1)
                    }
                }
                VGap(8f)
                Text {
                    attr {
                        text(planCreditsSub(item))
                        fontSize(12f)
                        fontFamily("Poppins-Medium")
                        color(SDColors.white40)
                        lines(1)
                    }
                }
            }
            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                }
                Text {
                    attr {
                        text(item.shopPrice)
                        fontSize(if (isIntroductory) 12f else 20f)
                        fontFamily("Carter")
                        color(if (isIntroductory) SDColors.white50 else Color.WHITE)
                        if (isIntroductory) textDecorationLineThrough()
                        lines(1)
                    }
                }
                if (isIntroductory) {
                    View { attr { width(8f) } }
                    Text {
                        attr {
                            text(item.introductoryPrice)
                            fontSize(20f)
                            fontFamily("Carter")
                            color(Color.WHITE)
                            lines(1)
                        }
                    }
                }
            }
        }
        if (isIntroductory) {
            VGap(4f)
            val unit = when (item.productSubscriptionPeriod) {
                1 -> "week".tr
                2 -> "month".tr
                else -> "year".tr
            }
            Text {
                attr {
                    text(
                        "introductory_price_tip".trParams(
                            "introPrice" to item.introductoryPrice,
                            "unit" to unit,
                            "price" to item.shopPrice,
                        ),
                    )
                    fontSize(11f)
                    fontFamily("Poppins-Medium")
                    color(SDColors.white50)
                    lines(2)
                }
            }
        }
    }
}
