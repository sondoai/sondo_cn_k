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
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.pager.IPagerEventObserver
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.timer.setTimeout
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
class SDRollingDigitView : ComposeView<SDRollingDigitAttr, ComposeEvent>(), IPagerEventObserver {
    // 两个槽位轮流充当「当前」与「上一个」
    private var text0 by observable("")
    private var text1 by observable("")
    /**
     * 槽位位置（声明式）：y 为自身高度的倍数（0 在位，1 在下方待命，-1 在上方离场），animated 决定这次变化是否带动画。
     * 位置和动画开关放在同一个字段里，一次赋值 attr 只重跑一次，动画和位移落在同一轮。
     * 以前对视图命令式调 animateToAttr，页面在后台时（比如扣积分时首页被创作页盖住）被打断的动画
     * 会让视图卡住，之后旧字符留在原位和新字符叠在一起，每次都错。
     */
    private var pos0 by observable(SlotPos(0f, 1f, false, 0))
    private var pos1 by observable(SlotPos(1f, 0f, false, 0))
    private var posSeq = 0

    /**
     * [alpha]：不在位的槽始终透明。离场时和上移一起淡出到 0；离场的槽之后要从上方挪回下方待命，
     * 这次挪动在 Kuikly 里仍会沿用上一次的动画、穿过可见区，透明着挪就看不见。
     * 进场时和上移在同一次动画里淡入 —— 不能单独先恢复不透明：那一步会沿用动画，被紧接着的滑入打断后停在半透明。
     */
    private fun place(slot: Int, y: Float, animated: Boolean, alpha: Float = 1f) {
        val p = SlotPos(y, alpha, animated, ++posSeq)
        if (slot == 0) pos0 = p else pos1 = p
    }
    private var active = 0
    private var bound = false
    private var alive = true

    /** 正在滚动；滚动期间来的新值先记下，滚完再滚（不叠加动画）。 */
    private var rolling = false
    private var pending: String? = null
    private var rollGen = 0

    override fun createAttr(): SDRollingDigitAttr = SDRollingDigitAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    /**
     * 所在页面是否可见。页面在后台（比如扣积分时导航栏所在的首页被创作页盖住、App 切到后台）时，
     * 带动画的属性变化在原生侧不会落地，数字会叠在一起或整位消失；不可见时直接换字，回到前台再把位置摆正。
     */
    private var pageVisible = true

    override fun created() {
        super.created()
        text0 = attr.value
        val pager = getPager() as Pager
        pageVisible = pager.isAppeared
        pager.addPagerEventObserver(this)
    }

    override fun onPagerEvent(pagerEvent: String, eventData: JSONObject) {
        if (!alive) return
        when (pagerEvent) {
            Pager.PAGER_EVENT_DID_APPEAR -> {
                pageVisible = true
                repair()
            }
            Pager.PAGER_EVENT_DID_DISAPPEAR -> pageVisible = false
        }
    }

    /** 回到前台后重建两个字符视图的次数（新视图没有残留的动画状态）。 */
    private var viewKey by observable(0)

    /**
     * 回到前台：当前字符在原位、另一个藏到下方，并重建两个字符视图。
     * 后台被打断的动画会让原生视图停在中间状态，属性记录值却已是终值，之后在原视图上怎么写都纠正不回来。
     */
    private fun repair() {
        rollGen++ // 作废还在路上的动画步骤
        rolling = false
        val next = pending
        pending = null
        if (next != null) {
            if (active == 0) text0 = next else text1 = next
        }
        place(active, 0f, false, alpha = 1f)
        place(1 - active, 1f, false, alpha = 0f)
        viewKey++
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
        alive = false
        rollGen++
        (getPager() as Pager).removePagerEventObserver(this)
        unbindAllValueChange()
        super.viewDestroyed()
    }

    private fun roll(newValue: String) {
        if (rolling) {
            pending = newValue
            return
        }
        val current = if (active == 0) text0 else text1
        if (current == newValue) return
        if (!pageVisible) {
            // 看不见就不做动画，直接换字（回到前台时 repair 会再摆正位置）
            if (active == 0) text0 = newValue else text1 = newValue
            return
        }
        rolling = true
        val gen = ++rollGen
        // 第一步：新字符写进待命槽（它本来就在下方、透明，只换字）
        if (active == 0) text1 = newValue else text0 = newValue
        active = 1 - active
        val d = attr.durationS
        // 第二步（带动画）：新字符上移到位、旧字符上移离场
        setTimeout(pagerId, 16) {
            if (!alive || gen != rollGen) return@setTimeout
            place(active, 0f, true)
            place(1 - active, -1f, true, alpha = 0f)
        }
        // 第三步：时长到了把离场的槽放回下方待命（透明着挪，看不见）
        val stepMs = (d * 1000f).toInt() + 60
        setTimeout(pagerId, 16 + stepMs) {
            if (!alive || gen != rollGen) return@setTimeout
            place(1 - active, 1f, false, alpha = 0f)
            // 第四步：这次挪动在 Kuikly 里也会沿用动画、持续一个时长；在它跑完之前不能让这个槽开始下一次动画 ——
            // 原生动画被打断会停在中间状态（字符整位消失或新旧叠在一起）。冷却完再处理滚动期间到达的新值（只取最新的）
            setTimeout(pagerId, stepMs) {
                if (!alive || gen != rollGen) return@setTimeout
                rolling = false
                val next = pending
                pending = null
                if (next != null) roll(next)
            }
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                size(ctx.attr.boxWidth, ctx.attr.boxHeight)
                overflow(true)
            }
            vbind({ ctx.viewKey }) {
                View {
                    attr { absolutePositionAllZero() }
                    for (slot in 0..1) {
                        View {
                            attr {
                                // 左右放宽：位宽只决定排版间距，不能把字形约束成省略号
                                absolutePosition(top = 0f, left = -4f, bottom = 0f, right = -4f)
                                allCenter()
                            }
                            attr {
                                val p = if (slot == 0) ctx.pos0 else ctx.pos1
                                transform(Translate(0f, p.y))
                                opacity(p.alpha)
                                if (p.animated) animate(Animation.easeOut(ctx.attr.durationS), p.seq)
                            }
                            Text {
                                attr {
                                    text(if (slot == 0) ctx.text0 else ctx.text1)
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
        }
    }
}

/** 滚动字符槽位的位置（见 [SDRollingDigitView]）。 */
private data class SlotPos(val y: Float, val alpha: Float, val animated: Boolean, val seq: Int)

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
