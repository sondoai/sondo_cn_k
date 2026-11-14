package com.sondo.app.features.member.widgets

import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.constants.asset
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.i18n.trParams
import com.sondo.app.ui.SDTextAlign
import com.sondo.app.ui.atoms.SDShimmer
import com.sondo.app.ui.atoms.SDText
import com.sondo.app.ui.atoms.VGap
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.Rotate
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.views.Blur
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.RichText
import com.tencent.kuikly.core.views.Span
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/*
 * 限时折扣页 / 回归商品页共用的视觉块
 * （Dart: features/member/presentation/widgets/offer_discount_frame.dart，
 *  以及 discount_page.dart / recall_product_page.dart 里两份相同的价格、骨架、协议区）。
 */

/** 折扣页与回归商品页的强调紫。 */
internal val offerAccent = Color(0xFF7F58F7L)

/**
 * 限时折扣稿的演唱会底图（Dart `OfferDiscountBackground`）：正方形，圆形头像和手绘箭头按 360 宽的比例贴在中部。
 * 放在页面最底层，`absolutePosition(top = 0, left = 0, right = 0)`。
 */
internal fun ViewContainer<*, *>.OfferDiscountBackground(width: Float) {
    val scale = width / 360f
    View {
        attr {
            absolutePosition(top = 0f, left = 0f)
            size(width, width)
        }
        Image {
            attr {
                absolutePositionAllZero()
                src(AssetsImage.discountOfferBg.asset())
                resizeStretch()
            }
        }
        // 上半段保持照片，从中线收到接近底边时变成页面底色。
        View {
            attr {
                absolutePositionAllZero()
                backgroundLinearGradient(
                    Direction.TO_BOTTOM,
                    ColorStop(Color.TRANSPARENT, 0f),
                    ColorStop(Color.TRANSPARENT, 0.5f),
                    ColorStop(UIColor.c0A0A0A, 0.972f),
                )
            }
        }
        Image {
            attr {
                absolutePosition(top = 177.8f * scale, left = 43.41f * scale)
                size(68f * scale, 68f * scale)
                src(AssetsImage.discountOfferPortrait.asset())
                resizeCover()
            }
        }
        View {
            attr {
                absolutePosition(top = 162.66f * scale, left = 119.87f * scale)
                size(43.062f * scale, 49.136f * scale)
                allCenter()
            }
            // 稿里箭头先垂直翻转，再逆时针转 56.39°：外层旋转，内层绕 x 轴转 180° 实现翻转。
            View {
                attr {
                    size(46.125f * scale, 24.379f * scale)
                    transform(Rotate(-56.39f))
                }
                Image {
                    attr {
                        size(46.125f * scale, 24.379f * scale)
                        src(AssetsImage.discountOfferArrow.asset())
                        resizeStretch()
                        transform(Rotate(0f, 180f, 0f))
                    }
                }
            }
        }
    }
}

/** 限时折扣标题，例如「首月优惠」（Dart `OfferDiscountHeadline`：48 / 行高 64 / w900 白，系统字体）。 */
internal fun ViewContainer<*, *>.OfferDiscountHeadline(title: String) {
    Text {
        attr {
            text(title)
            fontSize(48f)
            lineHeight(64f)
            fontWeight900()
            color(Color.WHITE)
            textAlignCenter()
        }
    }
}

/** 右上角毛玻璃关闭按钮（Dart `OfferDiscountCloseButton`，32 圆，模糊 5，白 10% 底与描边，叉 20）。 */
internal fun ViewContainer<*, *>.OfferDiscountCloseButton(onTap: () -> Unit) {
    View {
        attr {
            size(32f, 32f)
            borderRadius(16f)
            overflow(true)
            allCenter()
        }
        event { click { onTap() } }
        Blur {
            attr {
                absolutePositionAllZero()
                blurRadius(5f)
            }
        }
        View {
            attr {
                absolutePositionAllZero()
                borderRadius(16f)
                backgroundColor(UIColor.transparent10)
                border(Border(1f, BorderStyle.SOLID, UIColor.transparent10))
            }
        }
        Image {
            attr {
                // close_3 的 × 只占画布 37.5%，Material close 占 58%：31 显示 ≈ Icons.close size 20
                size(31f, 31f)
                src(AssetsImage.close3.asset())
                tintColor(Color.WHITE)
                resizeContain()
            }
        }
    }
}

/** 骨架色条。 */
internal fun ViewContainer<*, *>.offerSkeletonBar(width: Float?, height: Float, radius: Float = 4f) {
    SDShimmer {
        attr {
            if (width != null) width(width) else alignSelfStretch()
            height(height)
            this.radius = radius
            baseColor = UIColor.transparent10
            highlightColor = UIColor.transparent30
        }
    }
}

/** 商品未到达时的价格区骨架（标题 / 价格 / 积分 / 说明 + 倒计时）。 */
internal fun ViewContainer<*, *>.OfferSkeleton() {
    View {
        attr {
            padding(0f, 20f, 30f, 20f)
            alignItemsCenter()
        }
        View {
            attr {
                height(64f)
                allCenter()
            }
            offerSkeletonBar(192f, 48f, 8f)
        }
        VGap(36f)
        offerSkeletonBar(248f, 27f)
        VGap(8f)
        offerSkeletonBar(135f, 21f)
        VGap(8f)
        offerSkeletonBar(230f, 12f)
        VGap(36f)
        // 商品尚未确认时连同计时数字一起占位，避免误读为已到期。
        OfferCountdownSkeleton()
    }
}

internal fun ViewContainer<*, *>.OfferCountdownSkeleton() {
    View {
        attr {
            flexDirectionRow()
            justifyContentCenter()
            alignItemsCenter()
        }
        for (i in 0 until 4) {
            if (i == 2) {
                View {
                    attr {
                        size(30f, 60f)
                        allCenter()
                    }
                    offerSkeletonBar(8f, 24f)
                }
            }
            View {
                attr { if (i != 3) marginRight(4f) }
                offerSkeletonBar(48f, 60f, 10f)
            }
        }
    }
}

/** 页脚骨架：续费说明 + 购买按钮。 */
internal fun ViewContainer<*, *>.OfferFooterSkeleton() {
    View {
        attr { alignItemsCenter() }
        offerSkeletonBar(220f, 24f)
        VGap(12f)
        View {
            attr { alignSelfStretch() }
            offerSkeletonBar(null, 48f, 48f)
        }
    }
}

/** 价格块需要的展示数据（由页面从商品算出）。 */
internal data class OfferPriceInfo(
    val firstPeriodLabel: String,
    val price: String,
    val originalPrice: String,
    /** 有首期优惠且已加载完 */
    val showDiscount: Boolean,
    val credits: Int?,
    val unit: String,
)

/** 「首月 ¥x (¥y)」+「n 积分/月」+「首月优惠仅限…」。 */
internal fun ViewContainer<*, *>.OfferPriceBlock(info: OfferPriceInfo) {
    View {
        attr { alignItemsCenter() }
        RichText {
            attr {
                textAlignCenter()
                fontFamily("Poppins-SemiBold")
                fontSize(18f)
                color(UIColor.transparent80)
                letterSpacing(0.72f)
            }
            Span {
                text(info.firstPeriodLabel)
                fontFamily("Poppins-SemiBold")
                fontSize(18f)
                color(UIColor.transparent80)
                letterSpacing(0.72f)
            }
            if (info.price.isNotEmpty()) {
                Span {
                    text(" ${info.price}")
                    fontFamily("Poppins-SemiBold")
                    fontSize(18f)
                    color(offerAccent)
                    letterSpacing(0.72f)
                }
                if (info.showDiscount && info.originalPrice.isNotEmpty()) {
                    Span {
                        text(" (${info.originalPrice})")
                        fontFamily("Poppins")
                        fontSize(12f)
                        color(UIColor.transparent40)
                        textDecorationLineThrough()
                        letterSpacing(0.72f)
                    }
                }
            }
        }
        if (info.credits != null) {
            VGap(8f)
            SDText(
                "${info.credits} ${"credits".tr}/${info.unit}",
                size = 14f,
                color = UIColor.transparent80,
                fontFamily = "Poppins-SemiBold",
                letterSpacing = 0.56f,
            )
        }
        if (info.showDiscount) {
            VGap(8f)
            SDText(
                "offer_period_eligible".trParams("period" to info.firstPeriodLabel),
                size = 12f,
                maxLines = 2,
                textAlign = SDTextAlign.CENTER,
                height = 1f,
                color = UIColor.transparent40,
                fontFamily = "Poppins-Medium",
            )
        }
    }
}

/** 续费说明（10 / 行高 1.4 / 白 40%，最多 3 行居中）。 */
internal fun ViewContainer<*, *>.OfferRenewalNotice(text: String, lineHeight: Float? = 1.4f) {
    SDText(
        text,
        size = 10f,
        maxLines = 3,
        textAlign = SDTextAlign.CENTER,
        fontFamily = "Poppins-Medium",
        height = lineHeight,
        color = UIColor.transparent40,
    )
}

/** 紫色 48 胶囊购买按钮。 */
internal fun ViewContainer<*, *>.OfferBuyButton(label: () -> String, onTap: () -> Unit) {
    View {
        attr {
            alignSelfStretch()
            height(48f)
            borderRadius(48f)
            backgroundColor(offerAccent)
            allCenter()
        }
        event { click { onTap() } }
        Text {
            attr {
                text(label())
                fontSize(16f)
                lineHeight(16f * 1.19f)
                fontFamily("Poppins-SemiBold")
                color(Color.WHITE)
                lines(1)
            }
        }
    }
}

/** 「用户协议 | 隐私政策」（10 / 白 40% 下划线，中间 1×10 竖线，间距 16）。 */
internal fun ViewContainer<*, *>.OfferLegalLinks(onTerms: () -> Unit, onPrivacy: () -> Unit) {
    View {
        attr {
            flexDirectionRow()
            flexWrapWrap()
            justifyContentCenter()
            alignItemsCenter()
        }
        offerLegalLink("terms_of_use".tr, onTerms)
        View {
            attr {
                size(1f, 10f)
                margin(0f, 16f, 0f, 16f)
                backgroundColor(UIColor.transparent40)
            }
        }
        offerLegalLink("privacy_policy".tr, onPrivacy)
    }
}

private fun ViewContainer<*, *>.offerLegalLink(text: String, onTap: () -> Unit) {
    Text {
        attr {
            text(text)
            fontSize(10f)
            lineHeight(14f)
            fontFamily("Poppins-Medium")
            color(UIColor.transparent40)
            textDecorationUnderLine()
        }
        event { click { onTap() } }
    }
}
