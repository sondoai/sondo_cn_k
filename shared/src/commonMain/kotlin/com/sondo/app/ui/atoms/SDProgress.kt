package com.sondo.app.ui.atoms

import com.sondo.app.core.utils.nowMs
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.anim.SDRotating
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.Translate
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.event.layoutFrameDidChange
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.Canvas
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import kotlin.math.PI

/** 毫秒 → `m:ss` / `h:mm:ss`，秒截断（Dart `AppVideoProgressBar._formatDuration`）。 */
private fun formatScrubMs(ms: Float): String {
    val total = (ms / 1000f).toLong().coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    fun two(v: Long) = if (v < 10) "0$v" else v.toString()
    return if (h > 0) "$h:${two(m)}:${two(sec)}" else "$m:${two(sec)}"
}

/** 毫秒 → `mm:ss` / `hh:mm:ss`（Dart `_formatMs`）。 */
fun formatDurationMs(ms: Float): String {
    val totalSeconds = (ms / 1000f + 0.5f).toLong().coerceAtLeast(0L)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    fun two(v: Long) = if (v < 10) "0$v" else v.toString()
    return if (h > 0) "${two(h)}:${two(m)}:${two(s)}" else "${two(m)}:${two(s)}"
}

// ============================================================================
// 线性进度条
// ============================================================================

class SDProgressBarAttr : ComposeAttr() {
    /** 进度 0..1 */
    var progress: Float by observable(0f)
    /** 条高 */
    var barHeight: Float = 2f
    /** 轨道色（Dart 迷你播放器 0x4DFFFFFF） */
    var trackColor: Color by observable(SDColors.white30)
    /** 进度色；设了 [gradientColors] 时忽略 */
    var color: Color by observable(Color.WHITE)
    /** 横向渐变进度（如上传进度 #9B64FF → #7F58F7） */
    var gradientColors: List<Color>? = null
    /** 圆角；负数 = 半高 */
    var radius: Float = -1f
    /** 进度变化的过渡时长（秒，Dart 迷你播放器 200ms）；0 不过渡 */
    var animationS: Float = 0.2f
}

/**
 * 线性进度条（迷你播放器进度线、上传 / 生成进度）。宽度由调用方给（`flex(1f)` 或 `width()`）。
 */
class SDProgressBarView : ComposeView<SDProgressBarAttr, ComposeEvent>() {
    private var trackWidth by observable(0f)

    override fun createAttr(): SDProgressBarAttr = SDProgressBarAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        val r = if (ctx.attr.radius < 0f) ctx.attr.barHeight / 2f else ctx.attr.radius
        return {
            attr {
                height(ctx.attr.barHeight)
                borderRadius(r)
                backgroundColor(ctx.attr.trackColor)
                overflow(true)
            }
            event {
                layoutFrameDidChange { ctx.trackWidth = it.width }
            }
            View {
                attr {
                    absolutePosition(top = 0f, left = 0f, bottom = 0f)
                    width(ctx.trackWidth * ctx.attr.progress.coerceIn(0f, 1f))
                    borderRadius(r)
                    val g = ctx.attr.gradientColors
                    if (g != null && g.isNotEmpty()) {
                        backgroundLinearGradient(Direction.TO_RIGHT, ColorStop(g.first(), 0f), ColorStop(g.last(), 1f))
                    } else {
                        backgroundColor(ctx.attr.color)
                    }
                    if (ctx.attr.animationS > 0f) animate(Animation.linear(ctx.attr.animationS), ctx.attr.progress)
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDProgressBar(init: SDProgressBarView.() -> Unit) {
    addChild(SDProgressBarView(), init)
}

// ============================================================================
// 环形进度
// ============================================================================

class SDRingProgressAttr : ComposeAttr() {
    /** 进度 0..1 */
    var progress: Float by observable(0f)
    var strokeWidth: Float by observable(2f)
    var progressColor: Color by observable(Color.WHITE)
    /** 轨道色（Dart 默认 0x0DFFFFFF） */
    var trackColor: Color by observable(SDColors.white5)
}

/** Dart `RingProgressPainter` → `SDRingProgress`：12 点方向起、顺时针。尺寸用 `size(d, d)`。 */
class SDRingProgressView : ComposeView<SDRingProgressAttr, ComposeEvent>() {
    override fun createAttr(): SDRingProgressAttr = SDRingProgressAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            Canvas({
                attr { absolutePositionAllZero() }
            }) { context, width, height ->
                val stroke = ctx.attr.strokeWidth
                val dim = if (width < height) width else height
                val cx = width / 2f
                val cy = height / 2f
                val radius = (dim - stroke) / 2f
                if (radius <= 0f) return@Canvas
                val twoPi = (2.0 * PI).toFloat()
                // 轨道
                context.beginPath()
                context.strokeStyle(ctx.attr.trackColor)
                context.lineWidth(stroke)
                context.lineCapButt()
                context.arc(cx, cy, radius, 0f, twoPi, false)
                context.stroke()
                // 进度
                val p = ctx.attr.progress.coerceIn(0f, 1f)
                if (p <= 0f) return@Canvas
                val start = (-PI / 2.0).toFloat()
                context.beginPath()
                context.strokeStyle(ctx.attr.progressColor)
                context.lineWidth(stroke)
                if (p >= 1f) {
                    context.lineCapButt()
                    context.arc(cx, cy, radius, 0f, twoPi, false)
                } else {
                    context.lineCapRound()
                    context.arc(cx, cy, radius, start, start + twoPi * p, false)
                }
                context.stroke()
            }
        }
    }
}

fun ViewContainer<*, *>.SDRingProgress(init: SDRingProgressView.() -> Unit) {
    addChild(SDRingProgressView(), init)
}

/**
 * Material `CircularProgressIndicator`（不定进度）的近似：旋转的 1/4 圆弧 + 可选底环。
 * 默认 36 / 线宽 4，与 Flutter 默认尺寸一致；[trackColor] 为透明时不画底环（对应未设 backgroundColor）。
 */
fun ViewContainer<*, *>.SDCircularIndicator(
    color: Color,
    trackColor: Color = Color.TRANSPARENT,
    size: Float = 36f,
    strokeWidth: Float = 4f,
) {
    SDRotating {
        content {
            SDRingProgress {
                attr {
                    size(size, size)
                    this.strokeWidth = strokeWidth
                    progress = 0.25f
                    progressColor = color
                    this.trackColor = trackColor
                }
            }
        }
    }
}

// ============================================================================
// 可拖动进度条（播放器）
// ============================================================================

class SDSeekBarAttr : ComposeAttr() {
    /** 总时长（毫秒）；<= 0 时整条隐藏（Dart: opacity 0） */
    var duration: Float by observable(0f)
    /** 当前位置（毫秒） */
    var position: Float by observable(0f)
    /** 已缓冲位置（毫秒）；<= 0 不画缓冲条 */
    var buffered: Float by observable(0f)
    /** 触摸区高度（Dart 固定 30；Material Slider 撑满这 30，轨道在其中垂直居中） */
    var touchHeight: Float = 30f
    /** 左右内边距（Dart `padding` 的水平部分） */
    var paddingH: Float = 0f
    /** 轨道高度：常态 / 拖动中 */
    var trackHeight: Float = 3f
    var activeTrackHeight: Float = 4f
    /** 滑块半径：常态（0 = 不显示）/ 拖动中 */
    var thumbRadius: Float = 0f
    var activeThumbRadius: Float = 7f
    var activeColor: Color by observable(Color.WHITE)
    var inactiveColor: Color by observable(SDColors.white30)
    var bufferedColor: Color by observable(SDColors.white50)
    /** 拖动时是否在上方显示 `mm:ss / mm:ss` 提示 */
    var showTooltip: Boolean = true
    /**
     * 提示气泡样式：false = Dart `VideoProgressWidget`（黑 65%、圆角 6、8/4、16 w500、mm:ss，底边在滑块上方）；
     * true = Dart `AppVideoProgressBar._ScrubTimeBubble`（黑 60%、圆角 8、10/4、13 w600、m:ss，底边距触摸区顶部 8）。
     */
    var appVideoTooltip: Boolean = false
    /**
     * 轨道在触摸区内的位置：false 居中（默认，与 Dart `VideoProgressWidget` 一致：轨道中心距底 15），true 贴底。
     * 以前默认贴底，视频进度条比旧版低了约 13。
     */
    var alignBottom: Boolean = false
    /** 拖动过程中 onSeek 的节流间隔（毫秒） */
    var seekThrottleMs: Int = 80
    /**
     * 加载中：在**同一条轨道**里循环扫光（不定进度），时长为 0 时也显示轨道。
     * 视频加载条和播放进度条共用这条轨道：宽高、位置完全一致，加载结束直接在原地显示真实进度，不换条。
     */
    var loading: Boolean by observable(false)
    /** 加载扫光颜色 */
    var loadingColor: Color by observable(SDColors.white50)
    /**
     * 没有时长时是否隐藏整条（Dart VideoProgressWidget 的 opacity 0）。音乐详情切歌时时长会短暂为 0，
     * 来回隐藏 / 显示靠透明度动画恢复，真机上偶发停在不可见；那里设为 false，只显示空轨道。
     */
    var hideWithoutDuration: Boolean = true
}

class SDSeekBarEvent : ComposeEvent() {
    internal var seekStart: ((Float) -> Unit)? = null
    internal var seek: ((Float) -> Unit)? = null
    internal var seekEnd: ((Float) -> Unit)? = null

    /** 开始拖动，参数为目标毫秒。 */
    fun onSeekStart(handler: (ms: Float) -> Unit) { seekStart = handler }
    /** 拖动中（已节流）。 */
    fun onSeek(handler: (ms: Float) -> Unit) { seek = handler }
    /** 松手（或点击轨道），参数为最终目标毫秒 —— 在这里调播放器 seek。 */
    fun onSeekEnd(handler: (ms: Float) -> Unit) { seekEnd = handler }
}

/**
 * Dart `VideoProgressWidget` → `SDSeekBar`：可拖动 / 可点击的播放进度条，带缓冲条。
 *
 * ```
 * SDSeekBar {
 *     attr { duration = ctx.durationMs; position = ctx.positionMs; buffered = ctx.bufferedMs }
 *     event { onSeekEnd { ms -> ctx.player.seekTo(ms.toLong()) } }
 * }
 * ```
 * 拖动期间显示的是手指位置，不跟随 `position`；松手后回到受控值。
 */
class SDSeekBarView : ComposeView<SDSeekBarAttr, SDSeekBarEvent>() {
    private var trackWidth by observable(0f)
    private var isDragging by observable(false)
    private var dragValue by observable(0f)
    private var lastSeekAt = 0L

    /** 是否正在拖动（页面可据此暂停用播放器进度覆盖 position）。 */
    val dragging: Boolean get() = isDragging

    override fun createAttr(): SDSeekBarAttr = SDSeekBarAttr()
    override fun createEvent(): SDSeekBarEvent = SDSeekBarEvent()

    private fun valueFromX(x: Float): Float? {
        val d = attr.duration
        val w = trackWidth
        if (d <= 0f || w <= 0f) return null
        return d * ((x - attr.paddingH) / w).coerceIn(0f, 1f)
    }

    private fun throttledSeek(ms: Float) {
        val now = nowMs()
        if (lastSeekAt != 0L && now - lastSeekAt < attr.seekThrottleMs) return
        lastSeekAt = now
        event.seek?.invoke(ms)
    }

    private fun resetDrag() {
        isDragging = false
        lastSeekAt = 0L
    }

    private val displayRatio: Float
        get() {
            val d = attr.duration
            if (d <= 0f) return 0f
            val p = if (isDragging) dragValue else attr.position
            return (p / d).coerceIn(0f, 1f)
        }

    /**
     * 轨道圆角：不超过半高。以前固定 4，轨道只有 2~3 高时 Android 按超出半高的圆角裁剪，
     * 已播放段右端被削掉约 4，看起来和滑块断成两段。
     */
    internal val trackRadius: Float
        get() = (if (isDragging) attr.activeTrackHeight else attr.trackHeight) / 2f

    private val bufferedRatio: Float
        get() {
            val d = attr.duration
            if (d <= 0f) return 0f
            return (attr.buffered / d).coerceIn(0f, 1f)
        }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                height(ctx.attr.touchHeight)
                padding(0f, ctx.attr.paddingH, 0f, ctx.attr.paddingH)
                if (ctx.attr.alignBottom) justifyContentFlexEnd() else justifyContentCenter()
                val visible = ctx.attr.duration > 0f || ctx.attr.loading || !ctx.attr.hideWithoutDuration
                opacity(if (visible) 1f else 0f)
                animate(Animation.linear(0.3f), visible)
                // 没有时长（加载中 / 隐藏）时不吃触摸，点按落到下面的播放器上
                touchEnable(ctx.attr.duration > 0f)
            }
            event {
                pan { p ->
                    when (p.state) {
                        "start" -> {
                            val v = ctx.valueFromX(p.x) ?: return@pan
                            ctx.dragValue = v
                            ctx.isDragging = true
                            ctx.throttledSeek(v)
                            ctx.event.seekStart?.invoke(v)
                        }
                        "move" -> {
                            if (!ctx.isDragging) return@pan
                            val v = ctx.valueFromX(p.x) ?: return@pan
                            ctx.dragValue = v
                            ctx.throttledSeek(v)
                        }
                        else -> {
                            if (!ctx.isDragging) return@pan
                            val v = ctx.valueFromX(p.x) ?: ctx.dragValue
                            ctx.dragValue = v
                            ctx.resetDrag()
                            ctx.event.seekEnd?.invoke(v)
                        }
                    }
                }
                // 点击轨道直接跳转（Dart 在 pointerDown 就开始拖动）
                click { p ->
                    val v = ctx.valueFromX(p.x) ?: return@click
                    ctx.event.seekStart?.invoke(v)
                    ctx.event.seek?.invoke(v)
                    ctx.event.seekEnd?.invoke(v)
                }
            }

            // 轨道（撑满内容宽度；高度 / 滑块随拖动态变化）
            View {
                attr {
                    height(if (ctx.isDragging) ctx.attr.activeTrackHeight else ctx.attr.trackHeight)
                    borderRadius(ctx.trackRadius)
                    backgroundColor(ctx.attr.inactiveColor)
                    // 不要在轨道上挂 animate(…, isDragging)：Android 上这段动画跑过一次后，轨道里已播放段的宽度不再更新，
                    // 拖动松手后已播放段停在松手位置、滑块继续走，进度条断成两段（音乐详情真机问题）。拖动加粗改为即时变化。
                }
                event {
                    layoutFrameDidChange { ctx.trackWidth = it.width }
                }
                // 缓冲
                View {
                    attr {
                        absolutePosition(top = 0f, left = 0f, bottom = 0f)
                        width(ctx.trackWidth * ctx.bufferedRatio)
                        borderRadius(ctx.trackRadius)
                        backgroundColor(ctx.attr.bufferedColor)
                    }
                }
                // 加载扫光：裁在轨道内（单独一层裁剪，拖动时的滑块不受影响）
                vif({ ctx.attr.loading }) {
                    View {
                        attr {
                            absolutePositionAllZero()
                            borderRadius(ctx.trackRadius)
                            overflow(true)
                            touchEnable(false)
                        }
                        SDTrackSweep { attr { color = ctx.attr.loadingColor } }
                    }
                }
                // 已播放
                View {
                    attr {
                        absolutePosition(top = 0f, left = 0f, bottom = 0f)
                        width(ctx.trackWidth * ctx.displayRatio)
                        borderRadius(ctx.trackRadius)
                        backgroundColor(ctx.attr.activeColor)
                    }
                }
            }

            // 滑块：放在根视图上（高度 = 触摸区，装得下滑块），不挂在 2 高的轨道里 ——
            // 鸿蒙会裁掉超出父视图的部分，挂在轨道上时滑块只剩轨道那一条、看不见
            View {
                attr {
                    val r = if (ctx.isDragging) ctx.attr.activeThumbRadius else ctx.attr.thumbRadius
                    val th = if (ctx.isDragging) ctx.attr.activeTrackHeight else ctx.attr.trackHeight
                    val centerY = if (ctx.attr.alignBottom) ctx.attr.touchHeight - th / 2f else ctx.attr.touchHeight / 2f
                    absolutePosition(top = centerY - r, left = ctx.attr.paddingH + ctx.trackWidth * ctx.displayRatio - r)
                    size(r * 2f, r * 2f)
                    borderRadius(r)
                    backgroundColor(ctx.attr.activeColor)
                    touchEnable(false)
                }
            }

            // 时间提示
            vif({ ctx.attr.showTooltip && ctx.isDragging }) {
                View {
                    attr {
                        val app = ctx.attr.appVideoTooltip
                        val bottom = if (app) ctx.attr.touchHeight + 8f else ctx.attr.activeThumbRadius + ctx.attr.touchHeight
                        absolutePosition(left = 0f, right = 0f, bottom = bottom)
                        allCenter()
                        touchEnable(false)
                    }
                    View {
                        attr {
                            if (ctx.attr.appVideoTooltip) {
                                padding(4f, 10f, 4f, 10f)
                                borderRadius(8f)
                                backgroundColor(Color(0, 0, 0, 0.6f))
                            } else {
                                padding(4f, 8f, 4f, 8f)
                                borderRadius(6f)
                                backgroundColor(Color(0, 0, 0, 0.65f))
                            }
                        }
                        Text {
                            attr {
                                val d = if (ctx.attr.duration > 0f) ctx.attr.duration else 1f
                                if (ctx.attr.appVideoTooltip) {
                                    text("${formatScrubMs(ctx.dragValue)} / ${formatScrubMs(d)}")
                                    fontSize(13f)
                                    fontWeight600()
                                } else {
                                    text("${formatDurationMs(ctx.dragValue)} / ${formatDurationMs(d)}")
                                    fontSize(16f)
                                    fontWeight500()
                                    lineHeight(16f * 1.2f)
                                }
                                color(Color.WHITE)
                            }
                        }
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDSeekBar(init: SDSeekBarView.() -> Unit) {
    addChild(SDSeekBarView(), init)
}

class SDTrackSweepAttr : ComposeAttr() {
    var color: Color by observable(SDColors.white50)
}

/** 轨道内的不定进度扫光：整条宽度从左侧外滑到正好填满，一轮 800ms（同 Dart `CoverLoadingBar`）。 */
class SDTrackSweepView : ComposeView<SDTrackSweepAttr, ComposeEvent>() {
    private var running by observable(false)

    override fun createAttr(): SDTrackSweepAttr = SDTrackSweepAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun viewDidLoad() {
        super.viewDidLoad()
        setTimeout(pagerId, 16) { running = true }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { absolutePositionAllZero() }
            View {
                attr {
                    absolutePositionAllZero()
                    borderRadius(4f)
                    backgroundColor(ctx.attr.color)
                    transform(Translate(if (ctx.running) 0f else -1f, 0f))
                    animate(Animation.linear(0.8f).repeatForever(true), ctx.running)
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.SDTrackSweep(init: SDTrackSweepView.() -> Unit) {
    addChild(SDTrackSweepView(), init)
}

/**
 * 通用滑块（音量 / 速度 / 强度等取值）：就是 [SDSeekBar]，预设成「常显滑块、轨道居中、不显示时间提示」。
 * 取值范围用 `duration`（最大值）和 `position`（当前值）表达，例如 0..1：
 * ```
 * SDSlider {
 *     attr { duration = 1f; position = ctx.volume }
 *     event { onSeek { ctx.volume = it }; onSeekEnd { ctx.volume = it } }
 * }
 * ```
 */
fun ViewContainer<*, *>.SDSlider(init: SDSeekBarView.() -> Unit) {
    addChild(SDSeekBarView()) {
        attr {
            thumbRadius = 7f
            activeThumbRadius = 9f
            trackHeight = 4f
            activeTrackHeight = 4f
            alignBottom = false
            showTooltip = false
            seekThrottleMs = 0
        }
        init()
    }
}

// ============================================================================
// 不确定进度条（视频封面下方）
// ============================================================================

class SDCoverLoadingBarAttr : ComposeAttr() {
    /** 是否播放 */
    var active: Boolean by observable(true)
}

/**
 * Dart `CoverLoadingBar`：视频封面下的不确定进度条（左右 10、上下 2 内边距，轨道 2 高白 30%，
 * 填充白 50% 从左到右循环，一轮 800ms）。
 */
class SDCoverLoadingBarView : ComposeView<SDCoverLoadingBarAttr, ComposeEvent>() {
    private var running by observable(false)

    override fun createAttr(): SDCoverLoadingBarAttr = SDCoverLoadingBarAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun viewDidLoad() {
        super.viewDidLoad()
        setTimeout(pagerId, 16) { running = true }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { padding(2f, 10f, 2f, 10f) }
            View {
                attr {
                    height(2f)
                    borderRadius(4f)
                    backgroundColor(SDColors.white30)
                    overflow(true)
                }
                View {
                    attr {
                        absolutePositionAllZero()
                        borderRadius(4f)
                        backgroundColor(SDColors.white50)
                        visibility(ctx.attr.active)
                        // 从完全在左侧外（-100%）滑到正好填满
                        transform(Translate(if (ctx.running && ctx.attr.active) 0f else -1f, 0f))
                        animate(Animation.linear(0.8f).repeatForever(true), ctx.running && ctx.attr.active)
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDCoverLoadingBar(init: SDCoverLoadingBarView.() -> Unit) {
    addChild(SDCoverLoadingBarView(), init)
}
