package com.sondo.app.ui.atoms

import com.sondo.app.ui.SDColors
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.BoxShadow
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Canvas
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

// ============================================================================
// Switch
// ============================================================================

class SDSwitchAttr : ComposeAttr() {
    /** 当前值（受控：由父级绑定，点击只回调 onChanged，不自己改值） */
    var value: Boolean by observable(false)
    /** 视觉缩放（Dart SwitchWidget.scale，基准 51×31 的 CupertinoSwitch） */
    var scale: Float = 0.55f
    /**
     * 缩放后的开关在 51×31 布局盒里的对齐：-1 左 / 0 居中 / 1 右（Dart `alignment`）。
     * Dart 的 Transform.scale 只缩放绘制、不缩小布局盒，页面间距是按这个盒子调的，所以默认保留。
     */
    var alignment: Int = 0
    /** true：布局盒收缩到实际视觉大小（新页面推荐） */
    var compact: Boolean = false
    var activeTrackColor: Color by observable(SDColors.brandPurple)
    var inactiveTrackColor: Color by observable(SDColors.white20)
    var thumbColor: Color = Color.WHITE
    /** 关闭态滑块颜色（Dart inactiveThumbColor）；null 同 [thumbColor] */
    var inactiveThumbColor: Color? = null
    var disabled: Boolean by observable(false)
}

class SDSwitchEvent : ComposeEvent() {
    internal var changedHandler: ((Boolean) -> Unit)? = null

    /** 点击后回调目标值（!value）。 */
    fun onChanged(handler: (Boolean) -> Unit) {
        changedHandler = handler
    }
}

/**
 * Dart `SwitchWidget`（缩小版 CupertinoSwitch）→ `SDSwitch`。
 *
 * ```
 * SDSwitch {
 *     attr { value = ctx.watermarkOn }
 *     event { onChanged { ctx.toggleWatermark(it) } }
 * }
 * ```
 */
class SDSwitchView : ComposeView<SDSwitchAttr, SDSwitchEvent>() {
    override fun createAttr(): SDSwitchAttr = SDSwitchAttr()
    override fun createEvent(): SDSwitchEvent = SDSwitchEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        val trackW = 51f * ctx.attr.scale
        val trackH = 31f * ctx.attr.scale
        // CupertinoSwitch：滑块直径 28（51×31 轨道内边距 1.5）
        val thumbMargin = 1.5f * ctx.attr.scale
        val thumbSize = trackH - thumbMargin * 2f
        return {
            attr {
                if (ctx.attr.compact) {
                    size(trackW, trackH)
                } else {
                    size(51f, 31f)
                    justifyContentCenter()
                    when {
                        ctx.attr.alignment < 0 -> alignItemsFlexStart()
                        ctx.attr.alignment > 0 -> alignItemsFlexEnd()
                        else -> alignItemsCenter()
                    }
                }
                opacity(if (ctx.attr.disabled) 0.5f else 1f)
            }
            event {
                click {
                    if (!ctx.attr.disabled) ctx.event.changedHandler?.invoke(!ctx.attr.value)
                }
            }
            View {
                attr {
                    size(trackW, trackH)
                    borderRadius(trackH / 2f)
                    backgroundColor(if (ctx.attr.value) ctx.attr.activeTrackColor else ctx.attr.inactiveTrackColor)
                    animate(Animation.easeInOut(0.2f), ctx.attr.value)
                }
                View {
                    attr {
                        absolutePosition(top = thumbMargin)
                        left(if (ctx.attr.value) trackW - thumbMargin - thumbSize else thumbMargin)
                        size(thumbSize, thumbSize)
                        borderRadius(thumbSize / 2f)
                        // CupertinoSwitch 滑块阴影 (0, 3) blur 8 黑 15%
                        boxShadow(BoxShadow(0f, 3f * ctx.attr.scale, 8f * ctx.attr.scale, Color(0, 0, 0, 0.15f)))
                        backgroundColor(
                            if (ctx.attr.value) ctx.attr.thumbColor
                            else ctx.attr.inactiveThumbColor ?: ctx.attr.thumbColor
                        )
                        animate(Animation.easeInOut(0.2f), ctx.attr.value)
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDSwitch(init: SDSwitchView.() -> Unit) {
    addChild(SDSwitchView(), init)
}

// ============================================================================
// Checkbox / Radio
// ============================================================================

class SDCheckAttr : ComposeAttr() {
    /** 是否选中（受控） */
    var checked: Boolean by observable(false)
    /** 方框 / 圆圈边长 */
    var boxSize: Float = 18f
    /** 选中填充色 */
    var activeColor: Color by observable(SDColors.brandPurple)
    /** 未选中描边色 */
    var borderColor: Color by observable(SDColors.white40)
    /** 对勾颜色 */
    var checkColor: Color = Color.WHITE
    /** 右侧文字（可选）；需要富文本时用 [label] 自定义 */
    var text: String by observable("")
    var textColor: Color = SDColors.white80
    var fontSize: Float = 14f
    var gap: Float = 8f
    /** 自定义右侧内容（如带协议链接的富文本） */
    var label: ViewBuilder? = null
    var disabled: Boolean by observable(false)
    /** 扩大点击热区的内边距 */
    var hitPadding: Float = 4f
    internal var round: Boolean = true
    internal var radioStyle: Boolean = false
}

class SDCheckEvent : ComposeEvent() {
    internal var changedHandler: ((Boolean) -> Unit)? = null

    /** 点击后回调目标值。Radio 只会回调 true。 */
    fun onChanged(handler: (Boolean) -> Unit) {
        changedHandler = handler
    }
}

class SDCheckView : ComposeView<SDCheckAttr, SDCheckEvent>() {
    override fun createAttr(): SDCheckAttr = SDCheckAttr()
    override fun createEvent(): SDCheckEvent = SDCheckEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        val s = ctx.attr.boxSize
        return {
            attr {
                flexDirectionRow()
                alignItemsCenter()
                padding(ctx.attr.hitPadding)
                opacity(if (ctx.attr.disabled) 0.5f else 1f)
            }
            event {
                click {
                    if (ctx.attr.disabled) return@click
                    if (ctx.attr.radioStyle) {
                        if (!ctx.attr.checked) ctx.event.changedHandler?.invoke(true)
                    } else {
                        ctx.event.changedHandler?.invoke(!ctx.attr.checked)
                    }
                }
            }
            View {
                attr {
                    size(s, s)
                    borderRadius(if (ctx.attr.round) s / 2f else 4f)
                    allCenter()
                    if (ctx.attr.radioStyle) {
                        border(Border(1.5f, BorderStyle.SOLID, if (ctx.attr.checked) ctx.attr.activeColor else ctx.attr.borderColor))
                    } else if (ctx.attr.checked) {
                        backgroundColor(ctx.attr.activeColor)
                        border(Border(1.5f, BorderStyle.SOLID, ctx.attr.activeColor))
                    } else {
                        backgroundColor(Color.TRANSPARENT)
                        border(Border(1.5f, BorderStyle.SOLID, ctx.attr.borderColor))
                    }
                }
                if (ctx.attr.radioStyle) {
                    View {
                        attr {
                            size(s * 0.5f, s * 0.5f)
                            borderRadius(s * 0.25f)
                            backgroundColor(ctx.attr.activeColor)
                            opacity(if (ctx.attr.checked) 1f else 0f)
                            animate(Animation.easeOut(0.15f), ctx.attr.checked)
                        }
                    }
                } else {
                    Canvas({
                        attr {
                            size(s, s)
                            opacity(if (ctx.attr.checked) 1f else 0f)
                        }
                    }) { context, w, h ->
                        // 对勾：左下折线
                        context.beginPath()
                        context.strokeStyle(ctx.attr.checkColor)
                        context.lineWidth(w * 0.11f)
                        context.lineCapRound()
                        context.moveTo(w * 0.27f, h * 0.52f)
                        context.lineTo(w * 0.44f, h * 0.68f)
                        context.lineTo(w * 0.74f, h * 0.35f)
                        context.stroke()
                    }
                }
            }
            if (ctx.attr.label != null) {
                View {
                    attr {
                        flex(1f)
                        marginLeft(ctx.attr.gap)
                    }
                    ctx.attr.label?.invoke(this)
                }
            } else {
                vif({ ctx.attr.text.isNotEmpty() }) {
                    Text {
                        attr {
                            marginLeft(ctx.attr.gap)
                            text(ctx.attr.text)
                            fontSize(ctx.attr.fontSize)
                            color(ctx.attr.textColor)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 复选框（默认圆形，协议勾选那种；`attr { square() }` 改方形）。
 *
 * ```
 * SDCheckbox {
 *     attr { checked = ctx.agreed; text = "usage_statement_read_and_agree".tr }
 *     event { onChanged { ctx.agreed = it } }
 * }
 * ```
 */
fun ViewContainer<*, *>.SDCheckbox(init: SDCheckView.() -> Unit) {
    addChild(SDCheckView(), init)
}

/** 单选（外圈 + 内点）。同组互斥由父级处理：`checked = ctx.selected == id`，`onChanged { ctx.selected = id }`。 */
fun ViewContainer<*, *>.SDRadio(init: SDCheckView.() -> Unit) {
    addChild(SDCheckView()) {
        attr { radioStyle = true }
        init()
    }
}

/** 复选框改方形圆角。 */
fun SDCheckAttr.square() {
    round = false
}
