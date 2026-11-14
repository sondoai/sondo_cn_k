package com.sondo.app.ui.atoms

import com.sondo.app.ui.SDColors
import com.sondo.app.ui.SDFont
import com.sondo.app.ui.SDWeight
import com.sondo.app.ui.weight
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.base.Translate
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.DivView
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

// ============================================================================
// 单个滚动字符：旧字符向上滑出，新字符从下方滑入
// ============================================================================

class SDRollingDigitAttr : ComposeAttr() {
    /** 当前字符（通常是一位数字）；变化时触发滚动 */
    var value: String by observable("0")
    var boxWidth: Float = 10f
    var boxHeight: Float = 20f
    var fontSize: Float = 14f
    var weight: SDWeight? = null
    var fontFamily: String? = null
    var color: Color by observable(Color.WHITE)
    /** 滚动时长（秒） */
    var durationS: Float = 0.35f
}

/**
 * 滚动数字位（Dart `AnimatedSwitcher` + `SlideTransition`：旧数向上离场、新数由下进入）。
 * 被 [SDFlipCounter] 和 [SDOfferCountdownDigits] 复用，一般不直接用。
 */
class SDRollingDigitView : ComposeView<SDRollingDigitAttr, ComposeEvent>() {
    // 两个槽位轮流充当「当前」与「上一个」
    private var text0 by observable("")
    private var text1 by observable("")
    private var active = 0
    private var ref0: ViewRef<DivView>? = null
    private var ref1: ViewRef<DivView>? = null
    private var bound = false

    override fun createAttr(): SDRollingDigitAttr = SDRollingDigitAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun created() {
        super.created()
        text0 = attr.value
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        // 监听 value 变化（绑定时会立即回调一次，跳过）
        bindValueChange({ attr.value }) { v ->
            if (!bound) {
                bound = true
            } else {
                roll(v as String)
            }
        }
    }

    override fun viewDestroyed() {
        unbindAllValueChange()
        super.viewDestroyed()
    }

    private fun roll(newValue: String) {
        val current = if (active == 0) text0 else text1
        if (current == newValue) return
        val outRef = if (active == 0) ref0 else ref1
        val inRef = if (active == 0) ref1 else ref0
        if (active == 0) text1 = newValue else text0 = newValue
        active = 1 - active
        val d = attr.durationS
        // 新字符先瞬移到下方，再滑入
        inRef?.view?.also { v ->
            v.getViewAttr().transform(Translate(0f, 1f))
            v.animateToAttr(Animation.easeOut(d)) { transform(Translate(0f, 0f)) }
        }
        outRef?.view?.animateToAttr(Animation.easeIn(d)) { transform(Translate(0f, -1f)) }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                size(ctx.attr.boxWidth, ctx.attr.boxHeight)
                overflow(true)
            }
            View {
                ref { ctx.ref0 = it }
                attr {
                    // 左右放宽：位宽只决定排版间距，不能把字形约束成省略号
                    absolutePosition(top = 0f, left = -4f, bottom = 0f, right = -4f)
                    allCenter()
                }
                Text {
                    attr {
                        text(ctx.text0)
                        fontSize(ctx.attr.fontSize)
                        weight(ctx.attr.weight)
                        ctx.attr.fontFamily?.let { fontFamily(it) }
                        color(ctx.attr.color)
                        lines(1)
                    }
                }
            }
            View {
                ref { ctx.ref1 = it }
                // 初始位置单独一个静态 attr 块：下面的响应式块重跑时不会把位移冲掉
                attr {
                    absolutePosition(top = 0f, left = -4f, bottom = 0f, right = -4f)
                    allCenter()
                    transform(Translate(0f, 1f))
                }
                Text {
                    attr {
                        text(ctx.text1)
                        fontSize(ctx.attr.fontSize)
                        weight(ctx.attr.weight)
                        ctx.attr.fontFamily?.let { fontFamily(it) }
                        color(ctx.attr.color)
                        lines(1)
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDRollingDigit(init: SDRollingDigitView.() -> Unit) {
    addChild(SDRollingDigitView(), init)
}

// ============================================================================
// 翻牌计数（积分数字）
// ============================================================================

/** 一位数字的持有者：自己带响应式字段，换值时对应视图不重建，才能做滚动动画。 */
internal class SDFlipSlot(scope: PagerScope, ch: String) {
    var ch by scope.observable(ch)
}

class SDFlipCounterAttr : ComposeAttr() {
    /** 数值（非负整数） */
    var value: Int by observable(0)
    /** 字号 / 字重 / 颜色（Dart NavPoints：14 w600 白） */
    var fontSize: Float = 14f
    var weight: SDWeight? = SDWeight.W600
    var fontFamily: String? = null
    var color: Color by observable(Color.WHITE)
    /** 每位宽度；<= 0 时按 `fontSize * 0.72` 估算（Poppins 数字约 0.7em，偏窄时 Android 会把数字省略成「…」） */
    var digitWidth: Float = 0f
    /** 行高；<= 0 时按 `fontSize * 1.4` */
    var digitHeight: Float = 0f
    /** 动画时长（秒，Dart AnimatedFlipCounter 600ms） */
    var durationS: Float = 0.6f
}

/**
 * Dart `AnimatedFlipCounter` → `SDFlipCounter`：数字变化时逐位滚动（导航栏积分）。
 *
 * ```
 * SDFlipCounter { attr { value = ctx.points } }
 * ```
 */
class SDFlipCounterView : ComposeView<SDFlipCounterAttr, ComposeEvent>() {
    private val slots by observableList<SDFlipSlot>()

    override fun createAttr(): SDFlipCounterAttr = SDFlipCounterAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun created() {
        super.created()
        bindValueChange({ attr.value }) { v -> sync(v as Int) }
    }

    override fun viewDestroyed() {
        unbindAllValueChange()
        super.viewDestroyed()
    }

    private fun sync(value: Int) {
        val s = (if (value < 0) 0 else value).toString()
        if (s.length != slots.size) {
            // 位数变化：整体重建（这一次不做滚动）
            slots.clear()
            for (c in s) slots.add(SDFlipSlot(this, c.toString()))
        } else {
            for (i in s.indices) {
                val ch = s[i].toString()
                if (slots[i].ch != ch) slots[i].ch = ch
            }
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val w = if (ctx.attr.digitWidth > 0f) ctx.attr.digitWidth else ctx.attr.fontSize * 0.72f
        val h = if (ctx.attr.digitHeight > 0f) ctx.attr.digitHeight else ctx.attr.fontSize * 1.4f
        return {
            attr {
                flexDirectionRow()
                alignItemsCenter()
            }
            vfor({ ctx.slots }) { slot ->
                SDRollingDigit {
                    attr {
                        value = slot.ch
                        boxWidth = w
                        boxHeight = h
                        fontSize = ctx.attr.fontSize
                        weight = ctx.attr.weight
                        fontFamily = ctx.attr.fontFamily
                        color = ctx.attr.color
                        durationS = ctx.attr.durationS
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDFlipCounter(init: SDFlipCounterView.() -> Unit) {
    addChild(SDFlipCounterView(), init)
}

// ============================================================================
// 优惠倒计时 MM:SS
// ============================================================================

class SDOfferCountdownDigitsAttr : ComposeAttr() {
    /** 剩余秒数（负数按 0）；计时器由调用方维护 */
    var remainingSeconds: Int by observable(0)
}

/**
 * Dart `OfferCountdownDigits`：`MM:SS` 四个数字牌（48×60，底 0x0DFFFFFF，1px 白 10% 描边，圆角 10，
 * 数字 32 w600 Poppins），中间冒号格 30×60；变化的位向上滚动 350ms。
 */
class SDOfferCountdownDigitsView : ComposeView<SDOfferCountdownDigitsAttr, ComposeEvent>() {
    override fun createAttr(): SDOfferCountdownDigitsAttr = SDOfferCountdownDigitsAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    private fun digitAt(index: Int): String {
        val remaining = if (attr.remainingSeconds < 0) 0 else attr.remainingSeconds
        val m = (remaining / 60).toString().padStart(2, '0')
        val s = (remaining % 60).toString().padStart(2, '0')
        val digits = m + s
        // 分钟超过 99 时只取末四位
        val tail = if (digits.length > 4) digits.substring(digits.length - 4) else digits
        return tail[index].toString()
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
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
                        Text {
                            attr {
                                text(":")
                                fontSize(32f)
                                fontFamily(SDFont.poppins)
                                fontWeight600()
                                color(Color.WHITE)
                            }
                        }
                    }
                }
                View {
                    attr {
                        size(48f, 60f)
                        if (i != 3) marginRight(4f)
                        backgroundColor(SDColors.white5)
                        border(Border(1f, BorderStyle.SOLID, SDColors.white10))
                        borderRadius(10f)
                        overflow(true)
                    }
                    SDRollingDigit {
                        attr {
                            value = ctx.digitAt(i)
                            boxWidth = 48f
                            boxHeight = 60f
                            fontSize = 32f
                            weight = SDWeight.W600
                            fontFamily = SDFont.poppins
                            durationS = 0.35f
                        }
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDOfferCountdownDigits(init: SDOfferCountdownDigitsView.() -> Unit) {
    addChild(SDOfferCountdownDigitsView(), init)
}
