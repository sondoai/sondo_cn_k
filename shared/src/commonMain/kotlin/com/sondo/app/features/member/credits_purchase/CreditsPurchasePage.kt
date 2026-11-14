package com.sondo.app.features.member.credits_purchase

import com.sondo.app.base.AppEnv
import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.constants.asset
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.i18n.trParams
import com.sondo.app.core.utils.DevLog
import com.sondo.app.data.models.GoodsModel
import com.sondo.app.features.member.memberPayResult
import com.sondo.app.service.UserStore
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.service.pay.PayController
import com.sondo.app.service.pay.PayService
import com.sondo.app.ui.atoms.SDGradientButton
import com.sondo.app.ui.atoms.SDLinkSpan
import com.sondo.app.ui.atoms.SDLinkText
import com.sondo.app.ui.atoms.SDShimmer
import com.sondo.app.ui.atoms.SDText
import com.sondo.app.ui.atoms.Spacer
import com.sondo.app.ui.atoms.VGap
import com.sondo.app.ui.nav.SDNavBack
import com.sondo.app.ui.SDColors
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderRectRadius
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vforIndex
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.List
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 积分购买页（Dart: features/member/presentation/pages/credits_purchase/credits_purchase_page.dart
 * + CreditsPurchaseController）。iOS 读 Apple 积分包，Android / 鸿蒙读支付宝积分包；本页不展示订阅升级。
 *
 * 参数：无。
 * 结果：购买成功由 PayService 关页并带回 `{"result": true}`（积分记录页据此刷新）；直接返回时为 null。
 */
@Page(Routes.creditsPurchase)
internal class CreditsPurchasePage : BasePager() {

    /** 积分包商品（productType == 1） */
    private val goodsList by observableList<GoodsModel>()

    /** 当前选中的积分包索引，-1 表示未选中 */
    private var selectedPlanIndex by observable(-1)

    private var inRequest by observable(false)

    override fun created() {
        super.created()
        on(Events.PAY_RESULT) { json -> memberPayResult(json, Routes.creditsPurchase) { inRequest = false } }
        if (AppEnv.isIOS) PayService.resumePurchase(showRestoreTips = false)
        launch { initData() }
    }

    private suspend fun initData() {
        val list = PayController.getShopList(1) ?: emptyList()
        goodsList.clear()
        goodsList.addAll(list.filter { it.productType == 1 })
        if (goodsList.isNotEmpty()) selectPlan(0)
        DevLog.logger("goodsList====$goodsList")
    }

    /** 切换本页选中的积分包并记录埋点。 */
    private fun selectPlan(index: Int) {
        selectedPlanIndex = index
        val goods = goodsList[index]
        DevLog.logger("goods====$goods")
        UmengUtils.logEvent(
            "switch_products",
            mapOf("productId" to (goods.productId ?: ""), "productType" to (goods.productType ?: 0)),
        )
    }

    /** 购买本页选中的积分包。未登录先登录，否则支付服务会直接忽略这次点击。 */
    private fun buy(index: Int) {
        if (index < 0 || index >= goodsList.size) {
            toast("please_select_a_plan".tr)
            return
        }
        if (inRequest) return
        // 先记下本次点击的积分包，登录期间列表变化也不换商品。
        val goods = goodsList[index]
        launch {
            if (!UserStore.isLogin && !UserStore.showLoginDialog(this@CreditsPurchasePage)) return@launch
            inRequest = true
            PayController.purchase(goods, Routes.creditsPurchase) { inRequest = false }
        }
    }

    fun onItemTap(index: Int) {
        if (index in 0 until goodsList.size) selectPlan(index)
    }

    fun isSelected(index: Int): Boolean = selectedPlanIndex == index

    fun priceText(item: GoodsModel): String = planPrice(item)

    private fun planPrice(item: GoodsModel): String {
        if (item.productType == 1) return item.shopPrice
        val period = when (item.productSubscriptionPeriod) {
            3 -> " / ${"year".tr}"
            2 -> " / ${"month".tr}"
            1 -> " / ${"week".tr}"
            else -> ""
        }
        return "${item.shopPrice}$period "
    }

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            attr { backgroundColor(UIColor.pageBg) }
            Image {
                attr {
                    absolutePositionAllZero()
                    src(AssetsImage.profileBg.asset())
                    resizeCover()
                }
            }
            Image {
                attr {
                    absolutePosition(top = 0f, left = 0f)
                    size(ctx.screenWidth, ctx.screenWidth)
                    src(AssetsImage.creditsBg.asset())
                    resizeContain()
                }
            }
            Image {
                attr {
                    absolutePosition(top = 0f, left = 0f)
                    size(ctx.screenWidth, ctx.screenWidth)
                    src(AssetsImage.creditsFont.asset())
                    resizeContain()
                }
            }
            View {
                attr {
                    flex(1f)
                    paddingTop(ctx.statusBarHeight)
                }
                // AppBar：返回 + iOS「恢复」
                View {
                    attr {
                        height(56f)
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    // AppBar leading 槽 56 宽，48 热区居中
                    View {
                        attr {
                            width(56f)
                            alignItemsCenter()
                        }
                        SDNavBack()
                    }
                    Spacer()
                    if (AppEnv.isIOS) {
                        View {
                            attr {
                                padding(7f, 12f, 7f, 12f)
                                marginRight(10f)
                                backgroundColor(Color(255, 255, 255, 96f / 255f))
                                borderRadius(43f)
                            }
                            event { click { PayService.resumePurchase() } }
                            SDText("recover".tr, size = 12f)
                        }
                    }
                }
                View {
                    attr {
                        flex(1f)
                        paddingBottom(ctx.safeBottom)
                    }
                    Spacer()
                    View {
                        attr {
                            backgroundColor(Color(0, 0, 0, 0.4f))
                            borderRadius(BorderRectRadius(28f, 28f, 0f, 0f))
                        }
                        View {
                            attr {
                                height(320f)
                                padding(16f, 16f, 0f, 16f)
                            }
                            Text {
                                attr {
                                    text("credits_package".tr)
                                    fontSize(14f)
                                    fontFamily("Poppins-Bold")
                                    color(Color.WHITE)
                                    lines(1)
                                }
                            }
                            VGap(6f)
                            List {
                                attr {
                                    flex(1f)
                                    showScrollerIndicator(false)
                                    bouncesEnable(false)
                                }
                                vif({ ctx.goodsList.isEmpty() }) {
                                    View {
                                        for (i in 0 until 3) {
                                            if (i > 0) VGap(6f)
                                            creditsItemSkeleton()
                                        }
                                    }
                                }
                                velse {
                                    View {
                                        vforIndex({ ctx.goodsList }) { item, index, _ ->
                                            View {
                                                attr { if (index > 0) marginTop(6f) }
                                                creditsItem(ctx, item, index)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // 底部按钮和链接区域
                        View {
                            attr {
                                padding(10f, 0f, if (AppEnv.isIOS) 6f else 10f, 0f)
                                alignItemsCenter()
                            }
                            VGap(6f)
                            View {
                                attr { alignSelfStretch() }
                                vif({ ctx.goodsList.isEmpty() }) {
                                    View {
                                        attr { padding(0f, 16f, 0f, 16f) }
                                        SDShimmer {
                                            attr {
                                                height(40f)
                                                radius = 40f
                                                baseColor = SDColors.white20
                                                highlightColor = SDColors.white30
                                            }
                                        }
                                    }
                                }
                                velse {
                                    SDGradientButton {
                                        attr {
                                            padding(0f, 16f, 0f, 16f)
                                            text = "buy".tr
                                            isLoading = ctx.inRequest
                                        }
                                        event {
                                            onTap {
                                                val size = ctx.goodsList.size
                                                val index = ctx.selectedPlanIndex.coerceIn(0, (size - 1).coerceAtLeast(0))
                                                ctx.buy(index)
                                            }
                                        }
                                    }
                                }
                            }
                            VGap(10f)
                            // 条款和隐私政策链接
                            SDLinkText(
                                listOf(
                                    SDLinkSpan("terms_of_use".tr) { UserStore.openTerms(ctx) },
                                    SDLinkSpan("  |  "),
                                    SDLinkSpan("privacy_policy".tr) { UserStore.openPrivacyPolicy(ctx) },
                                ),
                                size = 12f,
                                color = Color(0x8AFFFFFFL),
                                linkColor = Color(0x8AFFFFFFL),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.creditsItemSkeleton() {
    // Dart：整张卡片作为 Shimmer 遮罩、内部色块都是不透明白 → 视觉上一整块均匀扫光（白 20% ↔ 30%）
    View {
        attr { paddingTop(10f) }
        SDShimmer {
            attr {
                height(70f)
                radius = 12f
                baseColor = SDColors.white20
                highlightColor = SDColors.white10
            }
        }
    }
}

private fun ViewContainer<*, *>.creditsItem(ctx: CreditsPurchasePage, item: GoodsModel, index: Int) {
    val pointDollar = item.pointDollar
    val subtitle = if (pointDollar > 0) "credits_per_dollar".trParams("pointDollar" to "$pointDollar") else ""
    View {
        attr { paddingTop(10f) }
        event { click { ctx.onItemTap(index) } }
        View {
            attr {
                minHeight(70f)
                padding(14f, 16f, 14f, 16f)
                borderRadius(12f)
                flexDirectionRow()
                justifyContentSpaceBetween()
                alignItemsCenter()
                val selected = ctx.isSelected(index)
                if (selected) {
                    backgroundLinearGradient(
                        Direction.TO_BOTTOM,
                        ColorStop(Color(45, 28, 69, 187f / 255f), 0f),
                        ColorStop(Color(99, 31, 93, 179f / 255f), 1f),
                    )
                } else {
                    backgroundLinearGradient(
                        Direction.TO_BOTTOM,
                        ColorStop(SDColors.white5, 0f),
                        ColorStop(SDColors.white5, 1f),
                    )
                }
                border(Border(2f, BorderStyle.SOLID, if (selected) Color(0xFFB55AFFL) else Color(124, 123, 124, 32f / 255f)))
            }
            View {
                attr { flex(1f) }
                Text {
                    attr {
                        text(item.productName ?: "")
                        fontSize(14f)
                        fontWeight700()
                        lines(1)
                        color(if (ctx.isSelected(index)) Color.WHITE else Color(0xB3FFFFFFL))
                    }
                }
                if (pointDollar > 0) {
                    VGap(6f)
                    SDText(subtitle, size = 12f, color = Color(0x8AFFFFFFL))
                }
            }
            Text {
                attr {
                    text(ctx.priceText(item))
                    fontSize(16f)
                    fontWeight900()
                    lines(1)
                    marginLeft(8f)
                    color(if (ctx.isSelected(index)) Color.WHITE else Color(0xB3FFFFFFL))
                }
            }
        }
    }
}
