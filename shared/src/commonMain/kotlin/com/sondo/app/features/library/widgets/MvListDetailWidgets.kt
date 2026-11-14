package com.sondo.app.features.library.widgets

import com.sondo.app.base.BasePager
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.i18n.tr
import com.sondo.app.data.models.DanceFeedbackTag
import com.sondo.app.ui.atoms.SDLoading
import com.sondo.app.ui.atoms.VGap
import com.sondo.app.ui.input.SDTextArea
import com.sondo.app.ui.input.hideKeyboard
import com.sondo.app.ui.overlay.DialogEntry
import com.sondo.app.ui.overlay.DialogHandle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.timer.clearTimeout
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.Blur
import com.tencent.kuikly.core.views.Canvas
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import kotlin.math.pow
import kotlin.math.sqrt

// ============================================================================
// MvRatingBanner
// ============================================================================

/** Dart `MvRatingBanner`：播放末 3 秒的「喜欢吗」五星横幅。 */
fun ViewContainer<*, *>.MvRatingBanner(onDismiss: () -> Unit, onStarTapped: (Int) -> Unit, layout: ViewContainer<*, *>.() -> Unit = {}) {
    View {
        attr {
            padding(14f, 16f, 16f, 16f)
            backgroundColor(Color(0xFF1C1C1CL))
            borderRadius(24f)
            flexDirectionRow()
            alignItemsCenter()
        }
        layout()
        View {
            attr { flex(1f) }
            Text {
                attr {
                    text("mv_rating_do_you_like_it".tr)
                    fontSize(14f)
                    fontFamily("Poppins-SemiBold")
                    color(Color.WHITE)
                }
            }
            VGap(8f)
            View {
                attr { flexDirectionRow() }
                for (i in 0 until 5) {
                    Image {
                        attr {
                            size(28f, 28f)
                            if (i < 4) marginRight(10f)
                            src(ImageUri.commonAssets(AssetsImage.starOutline))
                            resizeContain()
                        }
                        event { click { onStarTapped(i + 1) } }
                    }
                }
            }
        }
        View {
            attr {
                marginLeft(12f)
                size(32f, 32f)
                borderRadius(16f)
                backgroundColor(Color(0x1AFFFFFFL))
                allCenter()
            }
            event { click { onDismiss() } }
            Image {
                attr {
                    size(20f, 20f)
                    src(ImageUri.commonAssets(AssetsImage.close))
                    tintColor(Color.WHITE)
                    resizeContain()
                }
            }
        }
    }
}

// ============================================================================
// TryFreeSubscribeBanner + AnimatedSubscribeArrow
// ============================================================================

/** Dart `TryFreeSubscribeBanner`：试用 MV 播放页末段订阅引导卡片（毛玻璃 15 + 黑 80%）。 */
fun ViewContainer<*, *>.TryFreeSubscribeBanner(onTap: () -> Unit, layout: ViewContainer<*, *>.() -> Unit = {}) {
    View {
        attr {
            borderRadius(16f)
        }
        layout()
        event { click { onTap() } }
        Blur {
            attr {
                absolutePositionAllZero()
                blurRadius(15f)
            }
        }
        View {
            attr {
                padding(16f, 16f, 16f, 16f)
                backgroundColor(Color(0xCC1C1C1CL))
                borderRadius(16f)
                flexDirectionRow()
                alignItemsCenter()
            }
            Text {
                attr {
                    flex(1f)
                    text("try_free_subscribe_to_create".tr)
                    fontSize(14f)
                    fontFamily("Poppins-SemiBold")
                    color(Color.WHITE)
                }
            }
            View {
                attr {
                    marginLeft(12f)
                    size(48f, 32f)
                    borderRadius(55f)
                    backgroundColor(Color(0x1AFFFFFFL))
                    allCenter()
                }
                AnimatedSubscribeArrow { }
            }
        }
    }
}

class AnimatedSubscribeArrowAttr : ComposeAttr() {
    /** 绘制区域边长，设计稿为 20。 */
    var arrowSize: Float = 20f
}

/**
 * Dart `AnimatedSubscribeArrow`：右侧描线箭头，500ms easeOut 描完后静止 900ms，循环。
 * Dart 用 PathMetrics.extractPath 截取路径；这里把三次曲线采样成折线后按长度截取。
 */
class AnimatedSubscribeArrowView : ComposeView<AnimatedSubscribeArrowAttr, ComposeEvent>() {
    private var progress by observable(0f)
    private var startMs = 0L
    private var timer: String? = null
    private var alive = true

    override fun createAttr(): AnimatedSubscribeArrowAttr = AnimatedSubscribeArrowAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun viewDidLoad() {
        super.viewDidLoad()
        startMs = com.sondo.app.core.utils.nowMs()
        tick()
    }

    override fun viewDestroyed() {
        alive = false
        timer?.let { clearTimeout(it) }
        super.viewDestroyed()
    }

    private fun tick() {
        if (!alive) return
        val t = ((com.sondo.app.core.utils.nowMs() - startMs) % CYCLE_MS).toFloat()
        val p = if (t >= DRAW_MS) 1f else {
            val x = t / DRAW_MS
            // Curves.easeOut ≈ cubic(0, 0, 0.58, 1)；用 1-(1-x)^3 近似
            1f - (1f - x).pow(3)
        }
        if (p != progress) progress = p
        timer = setTimeout(pagerId, 16) { tick() }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            Canvas({
                attr {
                    size(ctx.attr.arrowSize, ctx.attr.arrowSize)
                    touchEnable(false)
                }
            }) { c, w, _ ->
                val p = ctx.progress
                if (p <= 0f) return@Canvas
                val k = w / 20f
                val scaleX = DRAW_W / VIEW_W
                val scaleY = DRAW_H / VIEW_H
                val pts = ARROW_POINTS
                val total = arrowLength
                var remain = total * p
                c.beginPath()
                c.strokeStyle(Color.WHITE)
                c.lineWidth(2f * k)
                c.lineCapRound()
                fun map(x: Float, y: Float): Pair<Float, Float> =
                    ((OFFSET_X + x * scaleX) * k) to ((OFFSET_Y + y * scaleY) * k)
                val (sx, sy) = map(pts[0].first, pts[0].second)
                c.moveTo(sx, sy)
                for (i in 1 until pts.size) {
                    val (ax, ay) = pts[i - 1]
                    val (bx, by) = pts[i]
                    val seg = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
                    if (remain >= seg) {
                        val (mx, my) = map(bx, by)
                        c.lineTo(mx, my)
                        remain -= seg
                    } else {
                        val f = if (seg <= 0f) 0f else remain / seg
                        val (mx, my) = map(ax + (bx - ax) * f, ay + (by - ay) * f)
                        c.lineTo(mx, my)
                        break
                    }
                }
                c.stroke()
            }
        }
    }

    companion object {
        private const val DRAW_MS = 500L
        private const val CYCLE_MS = 1400L
        private const val VIEW_W = 16.6613f
        private const val VIEW_H = 7.33203f
        private const val OFFSET_X = 2.21f
        private const val OFFSET_Y = 6.08f
        private const val DRAW_W = 15.583f
        private const val DRAW_H = 5.332f

        /** Figma Vector 6707：直线 → 三次曲线 → 直线，曲线段采样 12 点（viewBox 坐标）。 */
        private val ARROW_POINTS: List<Pair<Float, Float>> = buildList {
            add(1f to 6.33203f)
            add(14.9913f to 6.33203f)
            val p0 = 14.9913f to 6.33203f
            val p1 = 15.5828f to 6.33203f
            val p2 = 15.8813f to 5.61907f
            val p3 = 15.4664f to 5.19763f
            for (i in 1..12) {
                val t = i / 12f
                val u = 1 - t
                val x = u * u * u * p0.first + 3 * u * u * t * p1.first + 3 * u * t * t * p2.first + t * t * t * p3.first
                val y = u * u * u * p0.second + 3 * u * u * t * p1.second + 3 * u * t * t * p2.second + t * t * t * p3.second
                add(x to y)
            }
            add(11.3333f to 1f)
        }

        private val arrowLength: Float by lazy {
            var len = 0f
            for (i in 1 until ARROW_POINTS.size) {
                val (ax, ay) = ARROW_POINTS[i - 1]
                val (bx, by) = ARROW_POINTS[i]
                len += sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
            }
            len
        }
    }
}

fun ViewContainer<*, *>.AnimatedSubscribeArrow(init: AnimatedSubscribeArrowView.() -> Unit) {
    addChild(AnimatedSubscribeArrowView(), init)
}

// ============================================================================
// MvFeedbackBottomSheet
// ============================================================================

class MvFeedbackSheetAttr : ComposeAttr() {
    var initialRating: Int = 0
    /** 星级 → 推荐标签（调用方的实时表：弹层打开后才到的标签在下次换星 / 输入时补上，同 Dart 每次 build 读 widget.tags） */
    var tags: Map<Int, List<DanceFeedbackTag>> = emptyMap()
    var onRatingChanged: ((Int) -> Unit)? = null
    /** 提交；返回后由调用方决定是否关闭（Dart 在 onSubmit 里 Get.back()） */
    var onSubmit: (suspend (starRating: Int, tagCodes: List<String>, content: String) -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var bottomInset: Float = 0f
}

/** Dart `MvFeedbackBottomSheet`：星级 + 推荐标签 + 文字反馈。 */
class MvFeedbackSheetView : ComposeView<MvFeedbackSheetAttr, ComposeEvent>() {
    private var rating by observable(0)
    private val selectedTagCodes by observableList<String>()
    private val currentTags by observableList<DanceFeedbackTag>()
    private var text by observable("")
    private var isSubmitting by observable(false)
    private var keyboardHeight by observable(0f)

    override fun createAttr(): MvFeedbackSheetAttr = MvFeedbackSheetAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun created() {
        super.created()
        rating = attr.initialRating
        syncTags()
    }

    private fun syncTags() {
        val next = attr.tags[rating] ?: emptyList()
        if (next == currentTags.toList()) return
        currentTags.clear()
        currentTags.addAll(next)
    }

    private val canSubmit: Boolean
        get() = rating > 0 && (selectedTagCodes.isNotEmpty() || text.trim().isNotEmpty())

    private fun onStar(newRating: Int) {
        hideKeyboard()
        if (rating != newRating) {
            rating = newRating
            selectedTagCodes.clear()
            syncTags()
            attr.onRatingChanged?.invoke(newRating)
        } else {
            syncTags()
        }
    }

    private fun toggleTag(code: String) {
        hideKeyboard()
        if (selectedTagCodes.contains(code)) selectedTagCodes.remove(code) else selectedTagCodes.add(code)
    }

    private fun handleSubmit() {
        if (!canSubmit || isSubmitting) return
        hideKeyboard()
        val tagCodes = currentTags.filter { selectedTagCodes.contains(it.code) }.map { it.code }
        isSubmitting = true
        val pager = getPager() as BasePager
        pager.launch {
            try {
                attr.onSubmit?.invoke(rating, tagCodes, text.trim())
            } finally {
                isSubmitting = false
            }
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                padding(20f, 20f, 4f + ctx.attr.bottomInset, 20f)
                backgroundColor(Color(0xFF1C1C1CL))
                borderRadius(24f, 24f, 0f, 0f)
            }
            View {
                attr { marginBottom(ctx.keyboardHeight) }
                event { click { ctx.hideKeyboard() } }
                Text {
                    attr {
                        text("mv_feedback_title".tr)
                        fontSize(14f)
                        fontFamily("Poppins-SemiBold")
                        color(Color.WHITE)
                    }
                }
                VGap(12f)
                View {
                    attr { flexDirectionRow() }
                    for (i in 0 until 5) {
                        Image {
                            attr {
                                size(32f, 32f)
                                if (i < 4) marginRight(12f)
                                resizeContain()
                                src(ImageUri.commonAssets(if (i < ctx.rating) AssetsImage.starFilled else AssetsImage.starOutline))
                            }
                            event { click { ctx.onStar(i + 1) } }
                        }
                    }
                }
                VGap(20f)
                vif({ ctx.currentTags.isNotEmpty() }) {
                    View {
                        Text {
                            attr {
                                text("mv_feedback_your_opinion".tr)
                                fontSize(14f)
                                fontFamily("Poppins-SemiBold")
                                color(Color.WHITE)
                            }
                        }
                        VGap(12f)
                        View {
                            attr {
                                flexDirectionRow()
                                flexWrapWrap()
                                marginRight(-8f)
                            }
                            vfor({ ctx.currentTags }) { tag ->
                                View {
                                    attr {
                                        height(32f)
                                        padding(0f, 12f, 0f, 12f)
                                        marginRight(8f)
                                        marginBottom(8f)
                                        borderRadius(10f)
                                        allCenter()
                                        val selected = ctx.selectedTagCodes.contains(tag.code)
                                        backgroundColor(if (selected) Color(0xFFF3EFF7L) else Color(0x1AFFFFFFL))
                                    }
                                    event { click { ctx.toggleTag(tag.code) } }
                                    Text {
                                        attr {
                                            text(tag.label)
                                            fontSize(10f)
                                            fontFamily("Poppins-SemiBold")
                                            val selected = ctx.selectedTagCodes.contains(tag.code)
                                            color(if (selected) Color(0xCC0A0A0AL) else Color(0x66FFFFFFL))
                                        }
                                    }
                                }
                            }
                        }
                        VGap(4f)
                    }
                }
                SDTextArea {
                    attr {
                        // Dart 整个输入框（含上下内边距 12 与计数行）高 140；areaHeight 只是输入区：140 - 24 - (6 + 计数行 ≈ 18)
                        areaHeight = 92f
                        radius = 12f
                        fillColor = Color(0x1AFFFFFFL)
                        fontSize = 12f
                        color = Color.WHITE
                        hintColor = Color(0x66FFFFFFL)
                        cursorColor = Color.WHITE
                        maxLength = 2000
                        showCounter = true
                        counterColor = Color(0x66FFFFFFL)
                        fontFamily = "Poppins-Medium"
                        contentPadding = 12f
                        placeholder = "feedback_hint".tr
                    }
                    event {
                        onChanged {
                            ctx.text = it
                            ctx.syncTags()
                        }
                        onKeyboardHeight { h -> ctx.keyboardHeight = if (h > ctx.attr.bottomInset) h - ctx.attr.bottomInset else 0f }
                    }
                }
                VGap(16f)
                View {
                    attr { flexDirectionRow() }
                    View {
                        attr {
                            flex(1f)
                            height(48f)
                            borderRadius(100f)
                            backgroundColor(Color(0x33FFFFFFL))
                            allCenter()
                        }
                        event {
                            click {
                                ctx.hideKeyboard()
                                ctx.attr.onCancel?.invoke()
                            }
                        }
                        Text {
                            attr {
                                text("cancel".tr)
                                fontSize(16f)
                                fontFamily("Poppins-SemiBold")
                                color(Color(0x66FFFFFFL))
                            }
                        }
                    }
                    View { attr { width(12f) } }
                    View {
                        attr {
                            flex(1f)
                            height(48f)
                            borderRadius(100f)
                            allCenter()
                            backgroundColor(
                                if (ctx.canSubmit && !ctx.isSubmitting) Color(0xFFF3EFF7L) else Color(0x33FFFFFFL)
                            )
                        }
                        event { click { ctx.handleSubmit() } }
                        vif({ ctx.isSubmitting }) {
                            SDLoading { attr { size = 20f } }
                        }
                        velse {
                            Text {
                                attr {
                                    text("feedback".tr)
                                    fontSize(16f)
                                    fontFamily("Poppins-SemiBold")
                                    color(if (ctx.canSubmit) Color(0xFF0A0A0AL) else Color(0x66FFFFFFL))
                                }
                            }
                        }
                    }
                }
                VGap(12f)
            }
        }
    }
}

fun ViewContainer<*, *>.MvFeedbackSheet(init: MvFeedbackSheetView.() -> Unit) {
    addChild(MvFeedbackSheetView(), init)
}

/**
 * 打开反馈弹层（Dart `Get.bottomSheet(MvFeedbackBottomSheet(...), barrierColor: 0xB3000000)`）。
 * [onSubmit] 里调用 handle.dismiss() 关闭（对应 Dart 的 Get.back()）。
 */
fun BasePager.showMvFeedbackSheet(
    initialRating: Int,
    tags: Map<Int, List<DanceFeedbackTag>>,
    onRatingChanged: (Int) -> Unit,
    onSubmit: suspend (handle: DialogHandle, starRating: Int, tagCodes: List<String>, content: String) -> Unit,
    onDismiss: () -> Unit,
): DialogHandle {
    val inset = safeBottom
    var self: DialogHandle? = null
    val h = showDialog(
        align = DialogEntry.ALIGN_BOTTOM,
        maskColor = Color(0xB3000000L),
        onDismiss = onDismiss,
    ) { handle ->
        MvFeedbackSheet {
            attr {
                this.initialRating = initialRating
                this.tags = tags
                this.onRatingChanged = onRatingChanged
                this.bottomInset = inset
                this.onCancel = { handle.dismiss() }
                this.onSubmit = { r, codes, content -> onSubmit(handle, r, codes, content) }
            }
        }
    }
    self = h
    return self
}

/** 弹窗没有关闭回调时轮询句柄，关闭后回调一次（如举报面板）。 */
fun BasePager.watchDialogDismiss(handle: DialogHandle, onDismiss: () -> Unit) {
    fun check() {
        if (!handle.isShowing) {
            onDismiss()
            return
        }
        setTimeout(pagerId, 150) { check() }
    }
    setTimeout(pagerId, 150) { check() }
}
