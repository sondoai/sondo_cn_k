package com.sondo.app.ui.anim

import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.ui.SDColors
import com.tencent.kuikly.core.base.Anchor
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.Rotate
import com.tencent.kuikly.core.base.Scale
import com.tencent.kuikly.core.base.Skew
import com.tencent.kuikly.core.base.Translate
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.DivView
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.ImageView
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import kotlin.random.Random

/*
 * 动画组件。写法约定（Kuikly 声明式动画的两个坑）：
 * 1. `animate(anim, ctx.flag)` 绑定的是「flag 变化时，本 attr 块里它前面的属性变化用 anim 过渡」，
 *    正向、反向都会带动画；需要「瞬间复位再重播」的用命令式 `view.animateToAttr(...)`。
 * 2. `repeatForever(true)` 是「从起点到终点」反复重播，不是往返；往返（呼吸）用定时器翻转状态。
 */

// ============================================================================
// 入场动画：淡入 / 缩放 / 滑入
// ============================================================================

/** 缓动曲线。 */
enum class SDCurve { LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, SPRING }

internal fun sdAnimation(curve: SDCurve, durationS: Float): Animation = when (curve) {
    SDCurve.LINEAR -> Animation.linear(durationS)
    SDCurve.EASE_IN -> Animation.easeIn(durationS)
    SDCurve.EASE_OUT -> Animation.easeOut(durationS)
    SDCurve.EASE_IN_OUT -> Animation.easeInOut(durationS)
    SDCurve.SPRING -> Animation.springEaseOut(durationS, 0.7f, 1f)
}

class SDEnterAnimAttr : ComposeAttr() {
    /** 是否做透明度 0 → 1 */
    var fade: Boolean = true
    /** 起始缩放（1 = 不缩放；弹窗弹出用 0.92） */
    var scaleFrom: Float = 1f
    /** 起始位移，单位是「自身宽 / 高的倍数」（如 slideY = 1f 表示从下方一个自身高度处滑入） */
    var slideX: Float = 0f
    var slideY: Float = 0f
    var durationS: Float = 0.25f
    /** 延迟（秒） */
    var delayS: Float = 0f
    var curve: SDCurve = SDCurve.EASE_OUT
    /**
     * 是否显示（受控）。默认 true：挂载后自动播放入场；父级改成 false 会反向播放退场。
     */
    var show: Boolean by observable(true)
}

/**
 * 入场动画容器（Dart `flutter_animate` 的 `.fadeIn() / .scale() / .slide()`，以及隐私弹窗的 pop 动画）。
 *
 * ```
 * SDFadeIn { content { … } }
 * SDScaleIn { attr { scaleFrom = 0.92f; durationS = 0.2f }; content { … } }
 * SDSlideIn { attr { slideY = 1f }; content { … } }
 * ```
 */
class SDEnterAnimView : ComposeView<SDEnterAnimAttr, ComposeEvent>() {
    private var mounted by observable(false)
    private var contentBuilder: ViewBuilder? = null

    override fun createAttr(): SDEnterAnimAttr = SDEnterAnimAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    fun content(builder: ViewBuilder) { contentBuilder = builder }

    override fun viewDidLoad() {
        super.viewDidLoad()
        setTimeout(pagerId, 16) { mounted = true }
    }

    private val shown: Boolean get() = mounted && attr.show

    override fun body(): ViewBuilder {
        val ctx = this
        val a = ctx.attr
        return {
            attr {
                val on = ctx.shown
                if (a.fade) opacity(if (on) 1f else 0f)
                transform(
                    Rotate.DEFAULT,
                    Scale(if (on) 1f else a.scaleFrom, if (on) 1f else a.scaleFrom),
                    Translate(if (on) 0f else a.slideX, if (on) 0f else a.slideY),
                    Anchor.DEFAULT,
                    Skew.DEFAULT,
                )
                val anim = sdAnimation(a.curve, a.durationS)
                if (a.delayS > 0f) anim.delay(a.delayS)
                // mounted 与 show 任何一个变化都要过渡：分别绑定
                animate(anim, ctx.mounted)
                animate(sdAnimation(a.curve, a.durationS), a.show)
            }
            ctx.contentBuilder?.invoke(this)
        }
    }
}

fun ViewContainer<*, *>.SDEnterAnim(init: SDEnterAnimView.() -> Unit) {
    addChild(SDEnterAnimView(), init)
}

/** 淡入。 */
fun ViewContainer<*, *>.SDFadeIn(init: SDEnterAnimView.() -> Unit) = SDEnterAnim(init)

/** 缩放 + 淡入（默认从 0.92）。 */
fun ViewContainer<*, *>.SDScaleIn(init: SDEnterAnimView.() -> Unit) {
    addChild(SDEnterAnimView()) {
        attr { scaleFrom = 0.92f }
        init()
    }
}

/** 滑入 + 淡入（默认从下方 30% 自身高度）。 */
fun ViewContainer<*, *>.SDSlideIn(init: SDEnterAnimView.() -> Unit) {
    addChild(SDEnterAnimView()) {
        attr { slideY = 0.3f }
        init()
    }
}

// ============================================================================
// 呼吸 / 脉冲
// ============================================================================

class SDBreathingAttr : ComposeAttr() {
    /** 缩放范围 */
    var minScale: Float = 1f
    var maxScale: Float = 1.08f
    /** 透明度范围 */
    var minOpacity: Float = 1f
    var maxOpacity: Float = 1f
    /** 一次往返时长（秒） */
    var periodS: Float = 1.6f
    /** 是否播放 */
    var active: Boolean by observable(true)
}

/** 呼吸 / 脉冲：在两组缩放、透明度之间来回过渡（按钮提示、录音指示等）。 */
class SDBreathingView : ComposeView<SDBreathingAttr, ComposeEvent>() {
    private var high by observable(false)
    private var contentBuilder: ViewBuilder? = null
    private var alive = true
    private var looping = false

    override fun createAttr(): SDBreathingAttr = SDBreathingAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    fun content(builder: ViewBuilder) { contentBuilder = builder }

    override fun viewDidLoad() {
        super.viewDidLoad()
        bindValueChange({ attr.active }) { loop() }
    }

    override fun viewDestroyed() {
        alive = false
        unbindAllValueChange()
        super.viewDestroyed()
    }

    private fun loop() {
        if (!alive || looping || !attr.active) return
        looping = true
        high = !high
        setTimeout(pagerId, (attr.periodS * 500f).toInt()) {
            looping = false
            loop()
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val a = ctx.attr
        return {
            attr {
                val s = if (ctx.high) a.maxScale else a.minScale
                transform(Scale(s, s))
                opacity(if (ctx.high) a.maxOpacity else a.minOpacity)
                animate(Animation.easeInOut(a.periodS / 2f), ctx.high)
            }
            ctx.contentBuilder?.invoke(this)
        }
    }
}

fun ViewContainer<*, *>.SDBreathing(init: SDBreathingView.() -> Unit) {
    addChild(SDBreathingView(), init)
}

// ============================================================================
// 旋转
// ============================================================================

class SDRotatingAttr : ComposeAttr() {
    /** 转一圈的时长（秒） */
    var periodS: Float = 1f
    var active: Boolean by observable(true)
}

/** 匀速无限旋转的容器（自定义 loading 图标、唱片封面）。 */
class SDRotatingView : ComposeView<SDRotatingAttr, ComposeEvent>() {
    private var spinning by observable(false)
    private var contentBuilder: ViewBuilder? = null

    override fun createAttr(): SDRotatingAttr = SDRotatingAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    fun content(builder: ViewBuilder) { contentBuilder = builder }

    override fun viewDidLoad() {
        super.viewDidLoad()
        setTimeout(pagerId, 16) { spinning = true }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                val on = ctx.spinning && ctx.attr.active
                transform(Rotate(if (on) 360f else 0f))
                animate(Animation.linear(ctx.attr.periodS).repeatForever(true), ctx.spinning && ctx.attr.active)
            }
            ctx.contentBuilder?.invoke(this)
        }
    }
}

fun ViewContainer<*, *>.SDRotating(init: SDRotatingView.() -> Unit) {
    addChild(SDRotatingView(), init)
}

// ============================================================================
// 输入中三个点
// ============================================================================

class SDTypingDotsAttr : ComposeAttr() {
    var dotSize: Float = 6f
    var gap: Float = 4f
    var color: Color = Color.WHITE
    var count: Int = 3
    /** 每个点高亮的时长（毫秒） */
    var stepMs: Int = 300
}

/** 「正在输入 / 生成中」的跳动圆点。 */
class SDTypingDotsView : ComposeView<SDTypingDotsAttr, ComposeEvent>() {
    private var step by observable(0)
    private var alive = true

    override fun createAttr(): SDTypingDotsAttr = SDTypingDotsAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun viewDidLoad() {
        super.viewDidLoad()
        tick()
    }

    override fun viewDestroyed() {
        alive = false
        super.viewDestroyed()
    }

    private fun tick() {
        setTimeout(pagerId, attr.stepMs) {
            if (!alive) return@setTimeout
            step = (step + 1) % attr.count
            tick()
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val a = ctx.attr
        return {
            attr {
                flexDirectionRow()
                alignItemsCenter()
            }
            for (i in 0 until a.count) {
                View {
                    attr {
                        size(a.dotSize, a.dotSize)
                        borderRadius(a.dotSize / 2f)
                        backgroundColor(a.color)
                        if (i > 0) marginLeft(a.gap)
                        val on = ctx.step == i
                        opacity(if (on) 1f else 0.3f)
                        transform(Translate(0f, if (on) -0.4f else 0f))
                        animate(Animation.easeInOut(a.stepMs / 1000f), ctx.step)
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDTypingDots(init: SDTypingDotsView.() -> Unit) {
    addChild(SDTypingDotsView(), init)
}

// ============================================================================
// 均衡器跳动柱
// ============================================================================

class SDEqualizerBarsAttr : ComposeAttr() {
    var barCount: Int = 4
    var barWidth: Float = 3f
    var gap: Float = 2f
    /** 最大高度 */
    var maxHeight: Float = 14f
    var color: Color = Color.WHITE
    /** 是否跳动；false 时停在低位 */
    var playing: Boolean by observable(true)
    /** 刷新间隔（毫秒） */
    var intervalMs: Int = 180
}

/** 正在播放指示：几根高度随机起伏的柱子（平滑过渡版的 `AudioVisualizer` 柱）。 */
class SDEqualizerBarsView : ComposeView<SDEqualizerBarsAttr, ComposeEvent>() {
    private val heights by observableList<Float>()
    private var alive = true
    private var ticking = false

    override fun createAttr(): SDEqualizerBarsAttr = SDEqualizerBarsAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun created() {
        super.created()
        for (i in 0 until attr.barCount) heights.add(0.3f)
        bindValueChange({ attr.playing }) { tick() }
    }

    override fun viewDestroyed() {
        alive = false
        unbindAllValueChange()
        super.viewDestroyed()
    }

    private fun tick() {
        if (!alive || ticking) return
        if (!attr.playing) {
            for (i in 0 until heights.size) heights[i] = 0.3f
            return
        }
        ticking = true
        for (i in 0 until heights.size) heights[i] = Random.nextFloat() * 0.8f + 0.2f
        setTimeout(pagerId, attr.intervalMs) {
            ticking = false
            tick()
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val a = ctx.attr
        return {
            attr {
                flexDirectionRow()
                alignItemsFlexEnd()
                height(a.maxHeight)
            }
            for (i in 0 until a.barCount) {
                View {
                    attr {
                        width(a.barWidth)
                        if (i > 0) marginLeft(a.gap)
                        borderRadius(a.barWidth / 2f)
                        backgroundColor(a.color)
                        height(a.maxHeight * ctx.heights[i])
                        animate(Animation.linear(a.intervalMs / 1000f), ctx.heights[i])
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDEqualizerBars(init: SDEqualizerBarsView.() -> Unit) {
    addChild(SDEqualizerBarsView(), init)
}

// ============================================================================
// 下拉刷新指示（5 根依次点亮的柱子）
// ============================================================================

class SDRefreshLoadingAttr : ComposeAttr() {
    /** 尺寸（Dart RefreshLoading 默认 27×21） */
    var loadingWidth: Float = 27f
    var loadingHeight: Float = 21f
    var color: Color = Color.WHITE
    /** 是否播放；false 时停在全空（只剩 10% 的轨道） */
    var active: Boolean by observable(true)
}

/**
 * Dart `RefreshLoading` → `SDRefreshLoading`：5 根圆头竖条，1400ms 一轮，从左到右依次由中心向两端点亮，
 * 点亮后保持到本轮结束。用作下拉刷新头的指示。
 */
class SDRefreshLoadingView : ComposeView<SDRefreshLoadingAttr, ComposeEvent>() {
    private val fillRefs = arrayOfNulls<ViewRef<DivView>>(5)
    private var alive = true
    private var looping = false

    override fun createAttr(): SDRefreshLoadingAttr = SDRefreshLoadingAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun viewDidLoad() {
        super.viewDidLoad()
        bindValueChange({ attr.active }) { loop() }
    }

    override fun viewDestroyed() {
        alive = false
        unbindAllValueChange()
        super.viewDestroyed()
    }

    private fun loop() {
        if (!alive || looping) return
        if (!attr.active) {
            for (ref in fillRefs) ref?.view?.getViewAttr()?.transform(Scale(1f, 0f))
            return
        }
        looping = true
        for (i in 0 until 5) {
            val v = fillRefs[i]?.view ?: continue
            // 先瞬间清空，再按各自的时间窗点亮
            v.getViewAttr().transform(Scale(1f, 0f))
            val start = STARTS[i] * LOOP_S
            val duration = (ENDS[i] - STARTS[i]) * LOOP_S
            v.animateToAttr(Animation.easeOut(duration).delay(start)) { transform(Scale(1f, 1f)) }
        }
        setTimeout(pagerId, (LOOP_S * 1000f).toInt()) {
            looping = false
            loop()
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val a = ctx.attr
        val sx = a.loadingWidth / 27f
        val sy = a.loadingHeight / 21f
        val stroke = 3f * sx
        return {
            attr { size(a.loadingWidth, a.loadingHeight) }
            for (i in 0 until 5) {
                val left = (XS[i] - 1.5f) * sx
                val top = (TOPS[i] - 1.5f) * sy
                val h = (HEIGHTS[i] + 3f) * sy
                // 轨道
                View {
                    attr {
                        absolutePosition(top = top, left = left)
                        size(stroke, h)
                        borderRadius(stroke / 2f)
                        backgroundColor(Color(a.color.hexColor and 0xFFFFFFL, 0.1f))
                    }
                }
                // 填充（纵向缩放，从中心向两端）
                View {
                    ref { ctx.fillRefs[i] = it }
                    attr {
                        absolutePosition(top = top, left = left)
                        size(stroke, h)
                        borderRadius(stroke / 2f)
                        backgroundColor(a.color)
                        transform(Scale(1f, 0f))
                    }
                }
            }
        }
    }

    companion object {
        private const val LOOP_S = 1.4f
        private val XS = floatArrayOf(1.5f, 7.5f, 13.5f, 19.5f, 25.5f)
        private val TOPS = floatArrayOf(5.48438f, 3.48438f, 1.48438f, 3.48438f, 5.48438f)
        private val HEIGHTS = floatArrayOf(9f, 13f, 17.0312f, 13f, 9f)
        private val STARTS = floatArrayOf(0f, 0.17621f, 0.37389f, 0.60176f, 0.81384f)
        private val ENDS = floatArrayOf(0.17693f, 0.37401f, 0.60279f, 0.81384f, 1f)
    }
}

fun ViewContainer<*, *>.SDRefreshLoading(init: SDRefreshLoadingView.() -> Unit) {
    addChild(SDRefreshLoadingView(), init)
}

// ============================================================================
// 响铃图标
// ============================================================================

class SDAnimatedBellAttr : ComposeAttr() {
    /** 外圈直径（Dart 42） */
    var size: Float = 42f
    /** 铃铛绘制区边长（Dart 24；图层偏移按 24 标定） */
    var bellSize: Float = 24f
}

/**
 * Dart `AnimatedBellIcon`：白 10% 圆底上的铃铛，挂载后播放一次 1408ms 的衰减摆动
 * （铃体绕顶部 ±12° 摆 3 次，铃锤错相摆动）。原实现是逐帧的 `sin(6πt)·(1−t)`，这里用 6 段关键帧近似。
 */
class SDAnimatedBellView : ComposeView<SDAnimatedBellAttr, ComposeEvent>() {
    private var bodyRef: ViewRef<ImageView>? = null
    private var clapperRef: ViewRef<ImageView>? = null
    private var alive = true

    override fun createAttr(): SDAnimatedBellAttr = SDAnimatedBellAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun viewDidLoad() {
        super.viewDidLoad()
        setTimeout(pagerId, 32) { play() }
    }

    override fun viewDestroyed() {
        alive = false
        super.viewDestroyed()
    }

    /** 重新播放一次。 */
    fun play() {
        swing(0, BODY_ANGLES, bodyAnchor) { bodyRef?.view }
        swing(0, CLAPPER_ANGLES, Anchor.DEFAULT) { clapperRef?.view }
    }

    private val bodyAnchor = Anchor(0.5f, 0.225f) // Dart Alignment(0, -0.55)

    private fun swing(index: Int, angles: FloatArray, anchor: Anchor, target: () -> ImageView?) {
        if (!alive || index >= angles.size) return
        val v = target() ?: return
        val angle = angles[index]
        v.animateToAttr(Animation.easeInOut(SEGMENT_S), { _ ->
            swing(index + 1, angles, anchor, target)
        }) {
            transform(Rotate(angle), Scale.DEFAULT, Translate.DEFAULT, anchor, Skew.DEFAULT)
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val b = ctx.attr.bellSize
        return {
            attr {
                size(ctx.attr.size, ctx.attr.size)
                borderRadius(ctx.attr.size / 2f)
                backgroundColor(SDColors.white10)
                allCenter()
            }
            View {
                attr { size(b, b) }
                Image {
                    ref { ctx.bodyRef = it }
                    attr {
                        absolutePosition(top = b * 0.0863f, left = b * 0.1147f, bottom = b * 0.2306f, right = b * 0.1147f)
                        src(ImageUri.commonAssets(AssetsImage.bellBody))
                        resizeContain()
                    }
                }
                Image {
                    ref { ctx.clapperRef = it }
                    attr {
                        absolutePosition(top = b * 0.8244f, left = b * 0.3247f, bottom = b * 0.0804f, right = b * 0.3291f)
                        src(ImageUri.commonAssets(AssetsImage.bellClapper))
                        resizeContain()
                    }
                }
            }
        }
    }

    companion object {
        /** 1408ms / 6 段 */
        private const val SEGMENT_S = 0.2347f
        // sin(6πt)(1−t)·0.21rad 在各段峰值处的角度（度），最后回 0
        private val BODY_ANGLES = floatArrayOf(11f, -9f, 7f, -5f, 3f, 0f)
        // 铃锤：相位超前 π/3、幅度 0.35rad
        private val CLAPPER_ANGLES = floatArrayOf(18f, -15f, 12f, -8f, 5f, 0f)
    }
}

fun ViewContainer<*, *>.SDAnimatedBell(init: SDAnimatedBellView.() -> Unit) {
    addChild(SDAnimatedBellView(), init)
}

// ============================================================================
// 浮动的「+N / −N」
// ============================================================================

class SDFloatDeltaAttr : ComposeAttr() {
    /** 变化量；正数绿色 `+N`，负数红色 `-N` */
    var delta: Int = 0
    var fontSize: Float = 10f
}

class SDFloatDeltaEvent : ComposeEvent() {
    internal var finished: (() -> Unit)? = null

    /** 动画（1200ms）播完，调用方应把这一项从列表里移除。 */
    fun onFinished(handler: () -> Unit) { finished = handler }
}

/**
 * Dart `FloatItem`：积分变化时飘起的 `+N / −N`（10 号 w500；上飘约两个自身高度、放大到 1.4、后 70% 时间淡出）。
 * 由页面在积分变化时往一个 observableList 里加一项、`onFinished` 时移除。
 */
class SDFloatDeltaView : ComposeView<SDFloatDeltaAttr, SDFloatDeltaEvent>() {
    private var started by observable(false)
    private var alive = true

    override fun createAttr(): SDFloatDeltaAttr = SDFloatDeltaAttr()
    override fun createEvent(): SDFloatDeltaEvent = SDFloatDeltaEvent()

    override fun viewDidLoad() {
        super.viewDidLoad()
        setTimeout(pagerId, 16) { started = true }
        setTimeout(pagerId, 1250) { if (alive) event.finished?.invoke() }
    }

    override fun viewDestroyed() {
        alive = false
        super.viewDestroyed()
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val d = ctx.attr.delta
        return {
            attr {
                // 位移 + 放大：整段 1.2s
                transform(
                    Rotate.DEFAULT,
                    Scale(if (ctx.started) 1.4f else 1f, if (ctx.started) 1.4f else 1f),
                    Translate(-0.2f, if (ctx.started) -2f else 0.3f),
                    Anchor.DEFAULT,
                    Skew.DEFAULT,
                )
                animate(Animation.easeOut(1.2f), ctx.started)
                // 淡出：延后 30% 再开始
                opacity(if (ctx.started) 0f else 1f)
                animate(Animation.easeOut(0.84f).delay(0.36f), ctx.started)
                touchEnable(false)
            }
            Text {
                attr {
                    text(if (d > 0) "+$d" else d.toString())
                    fontSize(ctx.attr.fontSize)
                    fontWeight500()
                    // Flutter Colors.greenAccent / redAccent
                    color(if (d > 0) Color(0xFF69F0AEL) else Color(0xFFFF5252L))
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDFloatDelta(init: SDFloatDeltaView.() -> Unit) {
    addChild(SDFloatDeltaView(), init)
}
