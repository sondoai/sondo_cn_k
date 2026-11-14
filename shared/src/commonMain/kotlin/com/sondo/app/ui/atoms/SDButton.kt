package com.sondo.app.ui.atoms

import com.sondo.app.ui.SDColors
import com.sondo.app.ui.SDWeight
import com.sondo.app.ui.weight
import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.base.event.ClickParams
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.ActivityIndicator
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 胶囊按钮的属性（Dart `ButtonWidget` / `GradientButton` 两者的并集，参数名保持一致）。
 *
 * 布局约定（与 Dart 相同）：组件自身 = 外层带 padding 的容器，里面是胶囊。
 * - `padding(top, left, bottom, right)` → Dart `padding`（默认 20/20/34/20，[withBottomPadding] = false 时底部 20）；
 *   弹窗里通常写 `padding(0f)`。
 * - `height(h)` / `width(w)` / `borderRadius(r)` 作用在**胶囊**上（Dart `height` / `width` / `borderRadius`），
 *   不传宽度则撑满父容器。
 * - `flex(1f)`、`margin…`、`alignSelf…` 作用在外层容器上。
 */
class SDButtonAttr : ComposeAttr() {
    var text: String by observable("")
    var fontSize: Float by observable(16f)
    var weight: SDWeight? by observable<SDWeight?>(null)
    /** 文字色；null 时用预设（SDButton 黑 / SDGradientButton #0A0A0A） */
    var textColor: Color? by observable<Color?>(null)
    /** 横向渐变色；两色相同即纯色 */
    var gradientColors: List<Color> by observable(listOf(SDColors.gradientStart, SDColors.gradientEnd))
    var disabled: Boolean by observable(false)
    var disabledBg: Color by observable(Color(0x33FFFFFFL))
    /** 禁用时的文字色 */
    var disabledTextColor: Color by observable(Color(0x66FFFFFFL))
    /** 加载中：左侧转圈，点击无效（Dart ButtonWidget.isLoading） */
    var isLoading: Boolean by observable(false)
    /** 加载圈是否用深色（浅色按钮上用深色圈） */
    var loadingDark: Boolean = false
    /** 默认外边距的底部是否用 34（底部安全区风格） */
    var withBottomPadding: Boolean = true
        set(value) {
            field = value
            if (!paddingCustomized) super.padding(20f, 20f, if (value) 34f else 20f, 20f)
        }
    /** 自定义内容，替换文字（Dart `child`） */
    var child: ViewBuilder? = null
    /** 文字左侧的本地图标（AssetsImage.xxx），0 尺寸表示不显示 */
    var iconAsset: String by observable("")
    var iconSize: Float = 20f
    var iconGap: Float = 6f
    /** 描边（宽度 0 不显示） */
    var borderWidth: Float by observable(0f)
    var borderColor: Color by observable(SDColors.white20)

    internal var pillHeight: Float by observable(44f)
    internal var pillWidth: Float by observable(-1f)
    internal var pillRadius: Float by observable(22f)
    internal var defaultTextColor: Color = Color.BLACK
    private var paddingCustomized = false

    override fun height(height: Float): Attr {
        pillHeight = height
        return this
    }

    override fun width(width: Float): Attr {
        pillWidth = width
        return this
    }

    override fun padding(top: Float, left: Float, bottom: Float, right: Float): ComposeAttr {
        paddingCustomized = true
        super.padding(top, left, bottom, right)
        return this
    }

    /** 胶囊圆角（Dart ButtonWidget.borderRadius，默认 22）。 */
    fun radius(r: Float) {
        pillRadius = r
    }

    internal fun applyDefaultPadding() {
        super.padding(20f, 20f, if (withBottomPadding) 34f else 20f, 20f)
    }
}

class SDButtonEvent : ComposeEvent() {
    internal var tapHandler: (() -> Unit)? = null
    internal var disabledTapHandler: (() -> Unit)? = null

    /** 点击（禁用 / 加载中不触发）。 */
    fun onTap(handler: () -> Unit) {
        tapHandler = handler
    }

    /** 禁用状态下被点击（用于提示「请先填写…」）。 */
    fun onDisabledTap(handler: () -> Unit) {
        disabledTapHandler = handler
    }

    /** `click { }` 等价于 [onTap]，同样受禁用 / 加载态约束。 */
    override fun click(handler: (ClickParams) -> Unit) {
        tapHandler = { handler(ClickParams(0f, 0f, 0f, 0f)) }
    }
}

class SDButtonView : ComposeView<SDButtonAttr, SDButtonEvent>() {
    override fun createAttr(): SDButtonAttr = SDButtonAttr()
    override fun createEvent(): SDButtonEvent = SDButtonEvent()

    override fun willInit() {
        super.willInit()
        attr.applyDefaultPadding()
    }

    private fun handleTap() {
        if (attr.disabled) {
            event.disabledTapHandler?.invoke()
            return
        }
        if (attr.isLoading) return
        event.tapHandler?.invoke()
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    height(ctx.attr.pillHeight)
                    if (ctx.attr.pillWidth >= 0f) width(ctx.attr.pillWidth)
                    borderRadius(ctx.attr.pillRadius)
                    flexDirectionRow()
                    allCenter()
                    val colors = ctx.attr.gradientColors
                    if (ctx.attr.disabled) {
                        backgroundLinearGradient(
                            Direction.TO_RIGHT,
                            ColorStop(ctx.attr.disabledBg, 0f),
                            ColorStop(ctx.attr.disabledBg, 1f),
                        )
                    } else {
                        val first = colors.firstOrNull() ?: SDColors.primaryBtn
                        val last = colors.lastOrNull() ?: first
                        backgroundLinearGradient(Direction.TO_RIGHT, ColorStop(first, 0f), ColorStop(last, 1f))
                    }
                    if (ctx.attr.borderWidth > 0f) {
                        border(
                            com.tencent.kuikly.core.base.Border(
                                ctx.attr.borderWidth,
                                com.tencent.kuikly.core.base.BorderStyle.SOLID,
                                ctx.attr.borderColor,
                            )
                        )
                    }
                }
                event {
                    click { ctx.handleTap() }
                }

                vif({ ctx.attr.isLoading }) {
                    ActivityIndicator {
                        attr {
                            size(20f, 20f)
                            marginRight(10f)
                            isGrayStyle(ctx.attr.loadingDark)
                        }
                    }
                }

                if (ctx.attr.child != null) {
                    ctx.attr.child?.invoke(this)
                } else {
                    vif({ ctx.attr.iconAsset.isNotEmpty() }) {
                        Image {
                            attr {
                                size(ctx.attr.iconSize, ctx.attr.iconSize)
                                marginRight(ctx.attr.iconGap)
                                src(ImageUri.commonAssets(ctx.attr.iconAsset))
                                resizeContain()
                            }
                        }
                    }
                    Text {
                        attr {
                            text(ctx.attr.text)
                            fontSize(ctx.attr.fontSize)
                            weight(ctx.attr.weight)
                            lines(1)
                            color(
                                if (ctx.attr.disabled) ctx.attr.disabledTextColor
                                else ctx.attr.textColor ?: ctx.attr.defaultTextColor
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dart `ButtonWidget` → `SDButton`：品牌渐变胶囊 CTA（#E661F6 → #7F58F7，黑字 w500，支持 loading）。
 *
 * ```
 * SDButton {
 *     attr { text = "submit".tr; isLoading = ctx.submitting; disabled = !ctx.canSubmit }
 *     event { onTap { ctx.submit() } }
 * }
 * ```
 */
fun ViewContainer<*, *>.SDButton(init: SDButtonView.() -> Unit) {
    addChild(SDButtonView()) {
        attr {
            weight = SDWeight.W500
            defaultTextColor = Color.BLACK
        }
        init()
    }
}

/**
 * Dart `GradientButton` → `SDGradientButton`：默认纯色 #F3EFF7 + 深色字（名字叫 Gradient，默认其实是纯色）。
 * 禁用时底色 ARGB(219,188,194,212)、文字黑 54%。
 */
fun ViewContainer<*, *>.SDGradientButton(init: SDButtonView.() -> Unit) {
    addChild(SDButtonView()) {
        attr {
            gradientColors = listOf(SDColors.primaryBtn, SDColors.primaryBtn)
            defaultTextColor = SDColors.primaryBtnText
            // 文字在 TextButton 里，字重继承 M3 labelLarge（w500）
            weight = SDWeight.W500
            disabledBg = Color(188, 194, 212, 219f / 255f)
            disabledTextColor = Color(0, 0, 0, 0.54f)
            loadingDark = true
        }
        init()
    }
}

/** 主按钮：#F3EFF7 底 / #0A0A0A 字，无外边距（弹窗里的「确定」）。高度默认 44，可 `height(40f)`。 */
fun ViewContainer<*, *>.SDPrimaryButton(init: SDButtonView.() -> Unit) {
    SDGradientButton {
        attr { padding(0f, 0f, 0f, 0f) }
        init()
    }
}

/** 次按钮：白 20% 底 / 白字，无外边距（弹窗里的「取消」）。 */
fun ViewContainer<*, *>.SDSecondaryButton(init: SDButtonView.() -> Unit) {
    addChild(SDButtonView()) {
        attr {
            padding(0f, 0f, 0f, 0f)
            gradientColors = listOf(SDColors.secondaryBtn, SDColors.secondaryBtn)
            defaultTextColor = Color.WHITE
            weight = SDWeight.W500
        }
        init()
    }
}

// ---------------------------------------------------------------------------

class SDIconButtonAttr : ComposeAttr() {
    /** 本地图标（AssetsImage.xxx） */
    var icon: String by observable("")
    /** 图标边长 */
    var iconSize: Float by observable(24f)
    /** 着色；null 不着色 */
    var color: Color? by observable<Color?>(null)
    /** 点击热区边长（Material IconButton 默认 48） */
    var hitSize: Float = 48f
    /** 圆形底色（如预览页关闭按钮的黑 50%）；null 无底 */
    var bgColor: Color? by observable<Color?>(null)
    var disabled: Boolean by observable(false)
}

class SDIconButtonEvent : ComposeEvent() {
    internal var tapHandler: (() -> Unit)? = null
    fun onTap(handler: () -> Unit) { tapHandler = handler }
    override fun click(handler: (ClickParams) -> Unit) {
        tapHandler = { handler(ClickParams(0f, 0f, 0f, 0f)) }
    }
}

/** 图标按钮（Dart `IconButton(icon: Image.asset(...))` / `Image.asset(...).tap()`）。 */
class SDIconButtonView : ComposeView<SDIconButtonAttr, SDIconButtonEvent>() {
    override fun createAttr(): SDIconButtonAttr = SDIconButtonAttr()
    override fun createEvent(): SDIconButtonEvent = SDIconButtonEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                size(ctx.attr.hitSize, ctx.attr.hitSize)
                allCenter()
                val bg = ctx.attr.bgColor
                if (bg != null) {
                    backgroundColor(bg)
                    borderRadius(ctx.attr.hitSize / 2f)
                }
                opacity(if (ctx.attr.disabled) 0.4f else 1f)
            }
            View {
                // 整个热区可点
                attr { absolutePositionAllZero() }
                event {
                    click {
                        if (!ctx.attr.disabled) ctx.event.tapHandler?.invoke()
                    }
                }
            }
            Image {
                attr {
                    size(ctx.attr.iconSize, ctx.attr.iconSize)
                    src(ImageUri.commonAssets(ctx.attr.icon))
                    resizeContain()
                    tintColor(ctx.attr.color)
                    touchEnable(false)
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDIconButton(init: SDIconButtonView.() -> Unit) {
    addChild(SDIconButtonView(), init)
}
