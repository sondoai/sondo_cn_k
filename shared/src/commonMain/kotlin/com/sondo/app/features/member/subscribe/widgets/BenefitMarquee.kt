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
import com.tencent.kuikly.core.reactive.handler.observable
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

    override fun createAttr(): BenefitMarqueeAttr = BenefitMarqueeAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun viewDestroyed() {
        alive = false
        generation++
        super.viewDestroyed()
    }

    /**
     * 轨道宽度 = 两段内容之和（量出单段宽度后设置）。以前固定 10000 宽，Android 上约 3 万像素宽的视图
     * 做位移动画超出纹理上限，真机上跑马灯不动；现在轨道刚好两段宽，平移 -50% 正好走完一段。
     */
    private var trackWidth by observable(0f)

    // 百分比位移（相对轨道自身宽度）：offsetX 形式要等布局帧回调换算，绝对定位的轨道上不可靠
    private fun startOffset(): Float = if (attr.reverse) -0.5f else 0f

    private fun endOffset(): Float = if (attr.reverse) 0f else -0.5f



    private fun onSegmentWidth(width: Float) {
        if (width <= 0f || kotlin.math.abs(width - segmentWidth) < 0.5f) return
        segmentWidth = width
        trackWidth = width * 2f
        val g = ++generation
        trackRef?.view?.getViewAttr()?.transform(Translate(startOffset(), 0f))
        // 等新的轨道宽度布局生效后再开跑
        setTimeout(pagerId, 50) { if (alive && g == generation) round(g) }
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
                    // 量出单段宽度前先给足宽度让内容按自然宽度排开（不被父宽度压缩），之后为两段之和
                    width(if (ctx.trackWidth > 0f) ctx.trackWidth else 2000f)
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
