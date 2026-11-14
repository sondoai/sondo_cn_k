package com.sondo.app.ui.atoms

import com.sondo.app.ui.SDWeight
import com.sondo.app.ui.weight
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.Translate
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.event.layoutFrameDidChange
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.DivView
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.TextAttr
import com.tencent.kuikly.core.views.View

class SDMarqueeTextAttr : ComposeAttr() {
    var text: String by observable("")
    var color: Color by observable(Color.WHITE)
    /** 字号（Dart `size`） */
    var size: Float = 14f
    var fontWeight: SDWeight? = null
    var fontFamily: String? = null
    /** 滚动速度（dp/s，Dart `velocity`，默认 50） */
    var velocity: Float = 50f
    /** 首次开始滚动前的停顿（毫秒，Dart `startAfter` 2s） */
    var startAfterMs: Int = 2000
    /** 每轮结束后的停顿（毫秒，Dart `pauseAfterRound` 2s） */
    var pauseAfterRoundMs: Int = 2000
    /** 首尾之间的留白（Dart marquee blankSpace 40） */
    var blankSpace: Float = 40f
}

/**
 * Dart `MarqueeTextWidget` → `SDMarqueeText`：单行文字，放得下就是普通文字（超出省略），
 * 放不下才横向循环滚动（停 2s → 匀速滚一轮 → 停 2s …）。
 *
 * 宽度由调用方约束（`flex(1f)` 或 `width()`）。未移植：两端渐隐遮罩（纯视觉）。
 */
class SDMarqueeTextView : ComposeView<SDMarqueeTextAttr, ComposeEvent>() {
    private var containerWidth by observable(0f)
    private var textWidth by observable(0f)
    private var trackRef: ViewRef<DivView>? = null
    private var generation = 0
    private var alive = true

    /** 测量 / 滚动用的轨道足够宽，文字在里面不会被父宽度截断。 */
    private val trackWidth = 10000f

    private val overflowing: Boolean
        get() = containerWidth > 0f && textWidth > containerWidth + 0.5f

    override fun createAttr(): SDMarqueeTextAttr = SDMarqueeTextAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun viewDestroyed() {
        alive = false
        generation++
        super.viewDestroyed()
    }

    private fun applyStyle(a: TextAttr) {
        a.text(attr.text.ifEmpty { " " })
        a.fontSize(attr.size)
        a.color(attr.color)
        a.weight(attr.fontWeight)
        attr.fontFamily?.let { a.fontFamily(it) }
        a.lines(1)
    }

    private fun resetOffset() {
        trackRef?.view?.getViewAttr()?.transform(Translate(0f, 0f))
    }

    /** 尺寸 / 文案变化后重新开始。 */
    private fun restart() {
        val g = ++generation
        resetOffset()
        if (!overflowing) return
        setTimeout(pagerId, attr.startAfterMs) {
            if (alive && g == generation) startRound(g)
        }
    }

    private fun startRound(g: Int) {
        val track = trackRef?.view ?: return
        val distance = textWidth + attr.blankSpace
        val duration = distance / attr.velocity.coerceAtLeast(1f)
        val percent = -distance / trackWidth
        track.animateToAttr(Animation.linear(duration), { _ ->
            if (alive && g == generation) {
                // 第二份文字此刻正好落在起点，瞬间归零不会跳
                resetOffset()
                setTimeout(pagerId, attr.pauseAfterRoundMs) {
                    if (alive && g == generation) startRound(g)
                }
            }
        }) {
            transform(Translate(percent, 0f))
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { overflow(true) }
            event {
                layoutFrameDidChange {
                    if (it.width != ctx.containerWidth) {
                        ctx.containerWidth = it.width
                        ctx.restart()
                    }
                }
            }
            // 静态文字：撑出高度；放得下时就显示它
            Text {
                attr {
                    ctx.applyStyle(this)
                    textOverFlowTail()
                    opacity(if (ctx.overflowing) 0f else 1f)
                }
            }
            // 滚动轨道：文字 + 留白 + 文字
            View {
                ref { ctx.trackRef = it }
                attr {
                    absolutePosition(top = 0f, left = 0f)
                    width(ctx.trackWidth)
                    flexDirectionRow()
                    alignItemsFlexStart()
                    opacity(if (ctx.overflowing) 1f else 0f)
                    touchEnable(false)
                }
                Text {
                    attr { ctx.applyStyle(this) }
                    event {
                        layoutFrameDidChange {
                            if (it.width != ctx.textWidth) {
                                ctx.textWidth = it.width
                                ctx.restart()
                            }
                        }
                    }
                }
                View { attr { width(ctx.attr.blankSpace) } }
                Text {
                    attr { ctx.applyStyle(this) }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDMarqueeText(init: SDMarqueeTextView.() -> Unit) {
    addChild(SDMarqueeTextView(), init)
}
