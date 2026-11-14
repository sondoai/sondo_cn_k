package com.sondo.app.ui.works.guide

import com.sondo.app.core.i18n.tr
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.event.layoutFrameDidChange
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.layout.Frame
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.clearTimeout
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.Canvas
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal class GuideOverlayAttr : ComposeAttr() {
    lateinit var controller: GuideStepController
}

/** 页面坐标系中的矩形。 */
internal data class GRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
    fun inflate(d: Float) = GRect(left - d, top - d, width + 2 * d, height + 2 * d)
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

private const val CARD_WIDTH = 240f
private const val CARD_MARGIN_LEFT = 42f
private const val DRAW_DURATION_MS = 800

/**
 * Dart guide_v3 `GuideOverlayWidget`：遮罩（黑 88%）挖出目标圆角孔；默认样式为说明卡片（Carter 标题 + 描述 + 渐变 Skip）
 * 和 800ms 逐步描出的白色贝塞尔箭头（最后 15% 淡入箭头）；自定义内容时换成调用方内容 + 右下角「SKIP」。
 * 点高亮区总是下一步，点遮罩仅在 `allowTapOverlayNext` 时下一步。目标位置每次重绘时实时读取。
 */
internal class GuideOverlayView : ComposeView<GuideOverlayAttr, ComposeEvent>() {
    /** 重绘计数（控制器变化 / repaint / 等待目标布局）。 */
    private var version by observable(0)
    private var drawProgress by observable(1f)
    private var cardW by observable(CARD_WIDTH)
    private var cardH by observable(130f)
    private var lastIndex = -1
    internal val vVersion: Int get() = version
    private var drawTimer: String? = null
    private var waitTimer: String? = null
    private var waitCount = 0
    private var removeListener: (() -> Unit)? = null
    private var destroyed = false

    override fun createAttr(): GuideOverlayAttr = GuideOverlayAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun created() {
        super.created()
        val c = attr.controller
        lastIndex = c.currentIndex
        removeListener = c.addListener {
            if (c.currentIndex != lastIndex) {
                lastIndex = c.currentIndex
                playDraw()
            }
            version++
            ensureTargetLaidOut()
        }
        GuideManager.repaintHook = { version++ }
        playDraw()
        ensureTargetLaidOut()
    }

    override fun viewDestroyed() {
        destroyed = true
        removeListener?.invoke()
        drawTimer?.let { clearTimeout(it) }
        waitTimer?.let { clearTimeout(it) }
        super.viewDestroyed()
    }

    /** 目标尚未布局时每帧重读，最多约 1 秒（Dart 依赖每帧 rebuild）。 */
    private fun ensureTargetLaidOut() {
        waitTimer?.let { clearTimeout(it) }
        waitCount = 0
        tickWait()
    }

    private fun tickWait() {
        waitTimer = setTimeout(pagerId, 16) {
            waitTimer = null
            if (destroyed) return@setTimeout
            version++
            waitCount++
            if (targetRect() == null && waitCount < 60) tickWait()
            // 布局可能在下一轮才稳定：再补一次
            else if (waitCount < 3) tickWait()
        }
    }

    /** 描线动画：0 → 1，easeInOut 在绘制时套用。 */
    private fun playDraw() {
        drawTimer?.let { clearTimeout(it) }
        val start = com.sondo.app.core.utils.nowMs()
        drawProgress = 0f
        fun step() {
            drawTimer = setTimeout(pagerId, 16) {
                drawTimer = null
                if (destroyed) return@setTimeout
                val p = ((com.sondo.app.core.utils.nowMs() - start).toFloat() / DRAW_DURATION_MS).coerceIn(0f, 1f)
                drawProgress = p
                if (p < 1f) step()
            }
        }
        step()
    }

    internal fun targetRect(): GRect? {
        val view = attr.controller.currentStep.target() ?: return null
        val f: Frame = view.frame
        if (f.width <= 0f && f.height <= 0f) return null
        val pf = view.convertFrame(f, null)
        return GRect(pf.x, pf.y, f.width, f.height)
    }

    internal fun focusRect(): GRect? {
        val r = targetRect() ?: return null
        return r.inflate(attr.controller.currentStep.focusPadding)
    }

    private fun cardOffset(target: GRect, direction: GuideTipDirection): Pair<Float, Float> {
        val vGap = 48f
        val hGap = 20f
        var left = CARD_MARGIN_LEFT
        val top: Float
        when (direction) {
            GuideTipDirection.bottom -> top = target.bottom + vGap
            GuideTipDirection.top -> top = target.top - cardH - vGap
            GuideTipDirection.left -> {
                left = target.left - CARD_WIDTH - hGap
                top = target.centerY - cardH / 2f
            }
            GuideTipDirection.right -> {
                left = target.right + hGap
                top = target.centerY - cardH / 2f
            }
        }
        val sw = pagerData.pageViewWidth
        val sh = pagerData.pageViewHeight
        return left.coerceIn(12f, (sw - CARD_WIDTH - 12f).coerceAtLeast(12f)) to
            top.coerceIn(80f, (sh - cardH - 24f).coerceAtLeast(80f))
    }

    private fun arrowStart(card: Pair<Float, Float>, d: GuideTipDirection): Pair<Float, Float> {
        val (x, y) = card
        return when (d) {
            GuideTipDirection.bottom -> (x + cardW * 0.55f) to y
            GuideTipDirection.top -> (x + cardW * 0.45f) to (y + cardH)
            GuideTipDirection.left -> (x + cardW) to (y + cardH * 0.4f)
            GuideTipDirection.right -> x to (y + cardH * 0.4f)
        }
    }

    private fun arrowEnd(t: GRect, d: GuideTipDirection): Pair<Float, Float> = when (d) {
        GuideTipDirection.bottom -> t.centerX to t.bottom
        GuideTipDirection.top -> t.centerX to t.top
        GuideTipDirection.left -> t.left to t.centerY
        GuideTipDirection.right -> t.right to t.centerY
    }

    private fun tangent(d: GuideTipDirection): Pair<Float, Float> = when (d) {
        GuideTipDirection.bottom -> 0f to -1f
        GuideTipDirection.top -> 0f to 1f
        GuideTipDirection.left -> 1f to 0f
        GuideTipDirection.right -> -1f to 0f
    }

    internal fun onTap(x: Float, y: Float) {
        val c = attr.controller
        val focus = focusRect() ?: return
        if (focus.contains(x, y) || c.currentStep.allowTapOverlayNext) c.next()
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val c = ctx.attr.controller
        return {
            attr { flex(1f) }
            // 遮罩 + 挖孔 + 箭头
            Canvas({
                attr { absolutePositionAllZero() }
                event { click { p -> ctx.onTap(p.pageX, p.pageY) } }
            }) { context, w, h ->
                ctx.version
                val focus = ctx.focusRect() ?: return@Canvas
                val step = c.currentStep
                val r = step.focusRadius.coerceAtMost(minOf(focus.width, focus.height) / 2f).coerceAtLeast(0f)
                // 外框顺时针 + 孔逆时针，非零环绕规则下孔不填充
                context.beginPath()
                context.moveTo(0f, 0f)
                context.lineTo(w, 0f)
                context.lineTo(w, h)
                context.lineTo(0f, h)
                context.closePath()
                val l = focus.left
                val t = focus.top
                val rr = focus.right
                val b = focus.bottom
                context.moveTo(l + r, t)
                context.arc(l + r, t + r, r, (1.5 * PI).toFloat(), PI.toFloat(), true)
                context.lineTo(l, b - r)
                context.arc(l + r, b - r, r, PI.toFloat(), (0.5 * PI).toFloat(), true)
                context.lineTo(rr - r, b)
                context.arc(rr - r, b - r, r, (0.5 * PI).toFloat(), 0f, true)
                context.lineTo(rr, t + r)
                context.arc(rr - r, t + r, r, 0f, (1.5 * PI).toFloat(), true)
                context.closePath()
                context.fillStyle(Color(0, 0, 0, 0.88f))
                context.fill()

                if (step.customContentBuilder != null) return@Canvas
                // 贝塞尔箭头
                val card = ctx.cardOffset(focus, step.direction)
                val (sx, sy) = ctx.arrowStart(card, step.direction)
                val (ex, ey) = ctx.arrowEnd(focus, step.direction)
                val dx = ex - sx
                val dy = ey - sy
                val dist = sqrt(dx * dx + dy * dy)
                if (dist < 1f) return@Canvas
                val (tx, ty) = ctx.tangent(step.direction)
                val tanLen = dist * 0.45f
                val c1x = sx + tx * tanLen
                val c1y = sy + ty * tanLen
                val c2x = ex - tx * tanLen
                val c2y = ey - ty * tanLen
                val p = ctx.drawProgress
                val tt = if (p < 0.5f) 2 * p * p else 1 - (-2 * p + 2) * (-2 * p + 2) / 2
                if (tt > 0f) {
                    // 按参数采样折线近似「提取前 t 段路径」
                    context.beginPath()
                    context.strokeStyle(Color.WHITE)
                    context.lineWidth(2f)
                    context.lineCapRound()
                    context.moveTo(sx, sy)
                    val n = 48
                    val last = (n * tt).toInt().coerceAtLeast(1)
                    for (i in 1..last) {
                        val u = (i.toFloat() / n).coerceAtMost(tt)
                        val m = 1 - u
                        val x = m * m * m * sx + 3 * m * m * u * c1x + 3 * m * u * u * c2x + u * u * u * ex
                        val y = m * m * m * sy + 3 * m * m * u * c1y + 3 * m * u * u * c2y + u * u * u * ey
                        context.lineTo(x, y)
                    }
                    context.stroke()
                }
                if (tt > 0.85f) {
                    val alpha = ((tt - 0.85f) / 0.15f).coerceIn(0f, 1f)
                    val angle = atan2(ty, tx)
                    val half = (PI / 4.5).toFloat()
                    val len = 7f
                    context.beginPath()
                    context.strokeStyle(Color(255, 255, 255, alpha))
                    context.lineWidth(2f)
                    context.lineCapRound()
                    context.moveTo(ex - len * cos(angle - half), ey - len * sin(angle - half))
                    context.lineTo(ex, ey)
                    context.lineTo(ex - len * cos(angle + half), ey - len * sin(angle + half))
                    context.stroke()
                }
            }
            // 裁剪手柄层
            vif({ ctx.version >= 0 && GuideManager.cropHandles != null && ctx.targetRect() != null }) {
                cropHandlesLayer(ctx)
            }
            // 说明卡片 / 自定义内容（步骤切换时重建）
            vbind({ c.currentIndex to (c.currentStep.customContentBuilder != null) }) {
                val step = c.currentStep
                val custom = step.customContentBuilder
                if (custom != null) {
                    View {
                        attr {
                            ctx.version
                            val f = ctx.focusRect()
                            if (f == null) {
                                visibility(false)
                            } else {
                                visibility(true)
                                absolutePosition(top = f.bottom + 16f, left = f.left, right = 20f)
                            }
                        }
                        custom(this) { c.close() }
                    }
                    Text {
                        attr {
                            absolutePosition(right = 24f, bottom = 24f + ctx.pagerData.safeAreaInsets.bottom)
                            text("SKIP")
                            color(Color(255, 255, 255, 0.9f))
                            fontSize(14f)
                            fontWeight600()
                        }
                        event { click { c.close() } }
                    }
                } else {
                    View {
                        attr {
                            ctx.version
                            val f = ctx.focusRect()
                            if (f == null) {
                                visibility(false)
                            } else {
                                visibility(true)
                                val (x, y) = ctx.cardOffset(f, step.direction)
                                absolutePosition(top = y, left = x)
                            }
                            width(CARD_WIDTH)
                        }
                        event {
                            layoutFrameDidChange {
                                if (kotlin.math.abs(it.height - ctx.cardH) > 1f) ctx.cardH = it.height
                                if (kotlin.math.abs(it.width - ctx.cardW) > 1f) ctx.cardW = it.width
                            }
                        }
                        Text {
                            attr {
                                text(step.title)
                                fontFamily("Carter")
                                color(Color.WHITE)
                                fontSize(26f)
                                lineHeight(26f * 1.15f)
                            }
                        }
                        if (step.description.isNotEmpty()) {
                            Text {
                                attr {
                                    marginTop(10f)
                                    text(step.description)
                                    fontFamily("Poppins-Medium")
                                    color(Color(255, 255, 255, 0.8f))
                                    fontSize(13f)
                                    lineHeight(13f * 1.45f)
                                }
                            }
                        }
                        View {
                            attr {
                                marginTop(18f)
                                size(80f, 32f)
                                borderRadius(43f)
                                backgroundLinearGradient(
                                    Direction.TO_RIGHT,
                                    ColorStop(Color(0xFFE661F6L), 0f),
                                    ColorStop(Color(0xFF7F58F7L), 1f),
                                )
                                allCenter()
                            }
                            event { click { c.close() } }
                            Text {
                                attr {
                                    text("skip".tr)
                                    fontFamily("Poppins-SemiBold")
                                    color(Color.WHITE)
                                    fontSize(12f)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Dart `GuideCropHandlesOverlay`：目标底部 trackHeight 条带上的左右手柄 + 中间拖动区（默认透明）。 */
private fun ViewContainer<*, *>.cropHandlesLayer(ctx: GuideOverlayView) {
    val cfg = GuideManager.cropHandles ?: return
    var lastX = 0f
    fun pan(state: String, pageX: Float, cb: ((Float) -> Unit)?) {
        when (state) {
            "start" -> lastX = pageX
            "move" -> {
                val d = pageX - lastX
                lastX = pageX
                if (d != 0f) cb?.invoke(d)
            }
            else -> cfg.onDragEnd?.invoke()
        }
    }
    View {
        attr {
            ctx.vVersion
            val rect = ctx.targetRect()
            if (rect != null) {
                absolutePosition(top = rect.bottom - cfg.trackHeight, left = rect.left)
                size(rect.width, cfg.trackHeight)
            }
        }
        // 中间：整体平移
        View {
            attr {
                ctx.vVersion
                ctx.targetRect()?.let { rect ->
                    val trackW = rect.width - 2 * cfg.handleWidth
                    val l = cfg.getStartPercent() * trackW + cfg.handleWidth
                    val r = cfg.handleWidth + cfg.getEndPercent() * trackW
                    absolutePosition(top = 0f, left = l)
                    size((r - l).coerceAtLeast(0f), cfg.trackHeight)
                }
                border(com.tencent.kuikly.core.base.Border(2f, com.tencent.kuikly.core.base.BorderStyle.SOLID, cfg.selectionBorderColor))
            }
            event { pan { p -> pan(p.state, p.pageX, cfg.onSelectionDragUpdate) } }
        }
        listOf(true, false).forEach { isLeft ->
            View {
                attr {
                    ctx.vVersion
                    ctx.targetRect()?.let { rect ->
                        val trackW = rect.width - 2 * cfg.handleWidth
                        val left = if (isLeft) cfg.getStartPercent() * trackW else cfg.handleWidth + cfg.getEndPercent() * trackW
                        absolutePosition(top = 0f, left = left)
                    }
                    size(cfg.handleWidth, cfg.trackHeight)
                    backgroundColor(cfg.handleColor)
                    if (isLeft) borderRadius(8f, 0f, 8f, 0f) else borderRadius(0f, 8f, 0f, 8f)
                    allCenter()
                }
                event { pan { p -> pan(p.state, p.pageX, if (isLeft) cfg.onStartDragUpdate else cfg.onEndDragUpdate) } }
                View {
                    attr {
                        size(2f, 10f)
                        backgroundColor(cfg.handleGripColor)
                    }
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.GuideOverlay(init: GuideOverlayView.() -> Unit) {
    addChild(GuideOverlayView(), init)
}
