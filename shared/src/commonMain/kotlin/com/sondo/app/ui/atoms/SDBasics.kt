package com.sondo.app.ui.atoms

import com.sondo.app.ui.SDColors
import com.sondo.app.ui.SDWeight
import com.sondo.app.ui.weight
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Canvas
import com.tencent.kuikly.core.views.DivView
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

// ============================================================================
// 间距 / 分割线 / 安全区 —— 都是一行的小东西，直接做成扩展函数
// ============================================================================

/** 竖向间距（Dart `SizedBox(height: h)` / `h.verticalSpace`）。 */
fun ViewContainer<*, *>.VGap(h: Float) {
    View { attr { height(h) } }
}

/** 横向间距（Dart `SizedBox(width: w)` / `w.horizontalSpace`）。 */
fun ViewContainer<*, *>.HGap(w: Float) {
    View { attr { width(w) } }
}

/** 占满剩余空间（Dart `Spacer()` / `Expanded(child: SizedBox())`）。 */
fun ViewContainer<*, *>.Spacer(flex: Float = 1f) {
    View { attr { flex(flex) } }
}

/**
 * 分割线。默认横向 0.5 高、白 10%。
 * @param vertical true 为竖线（此时 [length] 是高度；不传则拉伸）
 * @param indent 两端缩进
 */
fun ViewContainer<*, *>.SDDivider(
    color: Color = SDColors.white10,
    thickness: Float = 0.5f,
    vertical: Boolean = false,
    length: Float? = null,
    indent: Float = 0f,
) {
    View {
        attr {
            backgroundColor(color)
            if (vertical) {
                width(thickness)
                if (length != null) height(length) else alignSelfStretch()
                if (indent > 0f) margin(indent, 0f, indent, 0f)
            } else {
                height(thickness)
                if (length != null) width(length)
                if (indent > 0f) margin(0f, indent, 0f, indent)
            }
        }
    }
}

/** 状态栏占位（Dart `SafeArea(bottom: false)` 的顶部留白）。 */
fun ViewContainer<*, *>.SafeTop(extra: Float = 0f) {
    val h = getPager().pageData.statusBarHeight + extra
    View { attr { height(h) } }
}

/** 底部安全区占位（手势条 / Home Indicator）。[min] 为没有安全区时的最小留白。 */
fun ViewContainer<*, *>.SafeBottom(extra: Float = 0f, min: Float = 0f) {
    val inset = getPager().pageData.safeAreaInsets.bottom
    val h = (if (inset > min) inset else min) + extra
    View { attr { height(h) } }
}

/**
 * 带安全区内边距的容器（Dart `SafeArea(top:, bottom:, child:)`）。
 * 里面照常写子节点；容器默认 `flex(1f)`，可在 [init] 的 attr 里改。
 */
fun ViewContainer<*, *>.SDSafeArea(top: Boolean = true, bottom: Boolean = true, init: DivView.() -> Unit) {
    val pd = getPager().pageData
    View {
        attr {
            flex(1f)
            if (top) paddingTop(pd.statusBarHeight)
            if (bottom) paddingBottom(pd.safeAreaInsets.bottom)
        }
        init()
    }
}

/**
 * 虚线（Dart `DashedLine`）。
 * @param horizontal 方向；[totalLength] 不传则沿主轴拉伸（父容器需给出尺寸）
 */
fun ViewContainer<*, *>.SDDashedLine(
    horizontal: Boolean = true,
    dashLength: Float = 5f,
    dashGap: Float = 3f,
    color: Color = Color.BLACK,
    strokeWidth: Float = 1f,
    totalLength: Float? = null,
) {
    Canvas({
        attr {
            if (horizontal) {
                height(strokeWidth)
                if (totalLength != null) width(totalLength) else alignSelfStretch()
            } else {
                width(strokeWidth)
                if (totalLength != null) height(totalLength) else alignSelfStretch()
            }
        }
    }) { context, width, height ->
        context.beginPath()
        context.strokeStyle(color)
        context.lineWidth(strokeWidth)
        context.setLineDash(listOf(dashLength, dashGap))
        if (horizontal) {
            context.moveTo(0f, height / 2f)
            context.lineTo(width, height / 2f)
        } else {
            context.moveTo(width / 2f, 0f)
            context.lineTo(width / 2f, height)
        }
        context.stroke()
    }
}

// ============================================================================
// 标签 / 角标
// ============================================================================

class SDTagAttr : ComposeAttr() {
    var text: String by observable("")
    /** 选中态（风格 / 分类 chip） */
    var selected: Boolean by observable(false)
    /** 高度；chip 默认 30（Dart 歌曲风格 chip），分类 chip 用 32 */
    var tagHeight: Float = 30f
    var radius: Float = 10f
    var paddingH: Float = 12f
    var fontSize: Float = 12f
    var weight: SDWeight? = SDWeight.W600
    /** 未选中：底色 / 文字色 */
    var bgColor: Color by observable(SDColors.white10)
    var textColor: Color by observable(SDColors.white80)
    /** 选中：底色 / 文字色（Dart 风格 chip：白底深色字） */
    var selectedBgColor: Color by observable(Color.WHITE)
    var selectedTextColor: Color by observable(SDColors.primaryBtnText)
    /** 左侧本地图标 */
    var iconAsset: String = ""
    var iconSize: Float = 14f
    /** 描边 */
    var borderWidth: Float = 0f
    var borderColor: Color = SDColors.white20
}

class SDTagEvent : ComposeEvent() {
    internal var tapHandler: (() -> Unit)? = null
    fun onTap(handler: () -> Unit) { tapHandler = handler }
}

/**
 * 标签 / chip（歌曲风格、分类筛选、歌词结构标签等）。
 * 选中态由父级用 `selected = ctx.current == item.id` 绑定。
 */
class SDTagView : ComposeView<SDTagAttr, SDTagEvent>() {
    override fun createAttr(): SDTagAttr = SDTagAttr()
    override fun createEvent(): SDTagEvent = SDTagEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                height(ctx.attr.tagHeight)
                borderRadius(ctx.attr.radius)
                padding(0f, ctx.attr.paddingH, 0f, ctx.attr.paddingH)
                flexDirectionRow()
                allCenter()
                backgroundColor(if (ctx.attr.selected) ctx.attr.selectedBgColor else ctx.attr.bgColor)
                if (ctx.attr.borderWidth > 0f) {
                    border(Border(ctx.attr.borderWidth, BorderStyle.SOLID, ctx.attr.borderColor))
                }
            }
            event {
                click { ctx.event.tapHandler?.invoke() }
            }
            if (ctx.attr.iconAsset.isNotEmpty()) {
                Image {
                    attr {
                        size(ctx.attr.iconSize, ctx.attr.iconSize)
                        marginRight(4f)
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
                    color(if (ctx.attr.selected) ctx.attr.selectedTextColor else ctx.attr.textColor)
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDTag(init: SDTagView.() -> Unit) {
    addChild(SDTagView(), init)
}

// ---------------------------------------------------------------------------

class SDBadgeAttr : ComposeAttr() {
    /** 数字角标；0 且 [text] 为空时，[dot] 为 true 显示红点，否则不显示 */
    var count: Int by observable(0)
    /** 文字角标（优先于 count），如 "PRO" / "NEW" */
    var text: String by observable("")
    /** 只显示小圆点 */
    var dot: Boolean by observable(false)
    /** 超过后显示 `max+` */
    var max: Int = 99
    var bgColor: Color by observable(SDColors.errorRed)
    var textColor: Color = Color.WHITE
    var fontSize: Float = 10f
    var badgeHeight: Float = 16f
    var dotSize: Float = 8f
}

/** 角标（未读数 / 红点 / PRO 标）。通常绝对定位在图标右上角：`attr { absolutePosition(top = -4f, right = -4f) }`。 */
class SDBadgeView : ComposeView<SDBadgeAttr, ComposeEvent>() {
    override fun createAttr(): SDBadgeAttr = SDBadgeAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    private val label: String
        get() = when {
            attr.text.isNotEmpty() -> attr.text
            attr.count > attr.max -> "${attr.max}+"
            attr.count > 0 -> attr.count.toString()
            else -> ""
        }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            vif({ ctx.attr.dot && ctx.label.isEmpty() }) {
                View {
                    attr {
                        size(ctx.attr.dotSize, ctx.attr.dotSize)
                        borderRadius(ctx.attr.dotSize / 2f)
                        backgroundColor(ctx.attr.bgColor)
                    }
                }
            }
            vif({ ctx.label.isNotEmpty() }) {
                View {
                    attr {
                        height(ctx.attr.badgeHeight)
                        minWidth(ctx.attr.badgeHeight)
                        borderRadius(ctx.attr.badgeHeight / 2f)
                        padding(0f, 4f, 0f, 4f)
                        allCenter()
                        backgroundColor(ctx.attr.bgColor)
                    }
                    Text {
                        attr {
                            text(ctx.label)
                            fontSize(ctx.attr.fontSize)
                            fontWeight600()
                            color(ctx.attr.textColor)
                            lines(1)
                        }
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDBadge(init: SDBadgeView.() -> Unit) {
    addChild(SDBadgeView(), init)
}
