package com.sondo.app.features.member.subscribe.widgets

import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.Translate
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.event.layoutFrameDidChange
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.DivView
import com.tencent.kuikly.core.views.View

class BenefitMarqueeAttr : ComposeAttr() {
    /** 滚动速度（逻辑像素 / 秒） */
    var velocity: Float = 40f

    /** true 时反向滚动（第二行对向） */
    var reverse: Boolean = false

    /** 两段内容之间的间距，计入循环周期宽度 */
    var gap: Float = 10f

    /** 每段内容；组件内构建两份做无缝循环。内容里读的 observable 照常响应。 */
    var builder: ViewBuilder? = null
}

/**
 * 订阅页权益 chips 无缝跑马灯（Dart: subscribe/widgets/benefit_marquee.dart `BenefitMarquee`）。
 * 两份相同内容排成一行，整体平移一段宽度后瞬间归位，接点处间距一致。高度由调用方给。
 */
class BenefitMarqueeView : ComposeView<BenefitMarqueeAttr, ComposeEvent>() {
    private var trackRef: ViewRef<DivView>? = null
    private var segmentWidth = 0f
    private var generation = 0
    private var alive = true

    /** 轨道足够宽，内容不会被父宽度截断。 */
    private val trackWidth = 10000f

    override fun createAttr(): BenefitMarqueeAttr = BenefitMarqueeAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun viewDestroyed() {
        alive = false
        generation++
        super.viewDestroyed()
    }

    private fun startOffset(): Float = if (attr.reverse) -segmentWidth / trackWidth else 0f

    private fun endOffset(): Float = if (attr.reverse) 0f else -segmentWidth / trackWidth

    private fun onSegmentWidth(width: Float) {
        if (width <= 0f || kotlin.math.abs(width - segmentWidth) < 0.5f) return
        segmentWidth = width
        val g = ++generation
        trackRef?.view?.getViewAttr()?.transform(Translate(startOffset(), 0f))
        setTimeout(pagerId, 16) { if (alive && g == generation) round(g) }
    }

    private fun round(g: Int) {
        val track = trackRef?.view ?: return
        val seconds = segmentWidth / attr.velocity.coerceAtLeast(1f)
        if (seconds <= 0f) return
        val end = endOffset()
        track.animateToAttr(Animation.linear(seconds), { _ ->
            if (!alive || g != generation) return@animateToAttr
            // 平移一整段后与第二份内容重合，瞬间归位看不出跳变
            trackRef?.view?.getViewAttr()?.transform(Translate(startOffset(), 0f))
            setTimeout(pagerId, 16) { if (alive && g == generation) round(g) }
        }) {
            transform(Translate(end, 0f))
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { overflow(true) }
            View {
                ref { ctx.trackRef = it }
                attr {
                    absolutePosition(top = 0f, left = 0f, bottom = 0f)
                    width(ctx.trackWidth)
                    flexDirectionRow()
                    alignItemsCenter()
                }
                View {
                    attr {
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    event { layoutFrameDidChange { ctx.onSegmentWidth(it.width) } }
                    ctx.attr.builder?.invoke(this)
                    View { attr { width(ctx.attr.gap) } }
                }
                View {
                    attr {
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    ctx.attr.builder?.invoke(this)
                    View { attr { width(ctx.attr.gap) } }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.BenefitMarquee(init: BenefitMarqueeView.() -> Unit) {
    addChild(BenefitMarqueeView(), init)
}
