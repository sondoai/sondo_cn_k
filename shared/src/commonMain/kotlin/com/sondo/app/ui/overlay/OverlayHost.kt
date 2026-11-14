package com.sondo.app.ui.overlay

import com.sondo.app.core.i18n.tr
import com.sondo.app.nativeapi.SDLottie
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.Translate
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.event.PanGestureParams
import com.tencent.kuikly.core.base.event.layoutFrameDidChange
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.layout.undefined
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.Blur
import com.tencent.kuikly.core.views.Modal
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import kotlin.math.abs

class OverlayHostAttr : ComposeAttr() {
    lateinit var state: OverlayState
}

/**
 * 页面浮层宿主：由 BasePager.body() 自动挂在内容之上。
 * 层级自下而上：顶部横幅（非模态，挂在页面内）→ 弹窗栈 → Loading → Toast → 顶部错误条（后四者是窗口级 Modal）。
 */
class OverlayHostView : ComposeView<OverlayHostAttr, ComposeEvent>() {
    override fun createAttr(): OverlayHostAttr = OverlayHostAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                absolutePositionAllZero()
                // 自身不拦截触摸，只有浮层内容可交互
                touchEnable(false)
            }

            // —— 顶部横幅（非模态）—— //
            // Modal(false) 会把内容挂到页面根节点上；这里把它改成只占顶部一条，其余区域照常可点
            vfor({ ctx.attr.state.banners }) { entry ->
                Modal(false) {
                    attr {
                        positionAbsolute()
                        top(70f)
                        left(0f)
                        right(0f)
                        bottom(Float.undefined)
                    }
                    BannerContainer {
                        attr {
                            this.entry = entry
                            this.state = ctx.attr.state
                        }
                    }
                }
            }

            // —— 弹窗栈 —— //
            vfor({ ctx.attr.state.dialogs }) { entry ->
                Modal(true) {
                    DialogContainer {
                        attr { this.entry = entry }
                    }
                }
            }

            // —— Loading（Dart LoadingUtil：music_loading 动画 80×80 + 可选文字）—— //
            vif({ ctx.attr.state.loadingVisible }) {
                Modal(true) {
                    View {
                        attr {
                            absolutePositionAllZero()
                            allCenter()
                            backgroundColor(Color(0, 0, 0, 0.3f))
                        }
                        event {
                            click {
                                if (ctx.attr.state.loadingMaskDismiss) ctx.attr.state.hideLoading(force = true)
                            }
                        }
                        SDLottie {
                            attr {
                                size(80f, 80f)
                                src("lottie/music_loading.json")
                                loop(true)
                                autoPlay(true)
                            }
                        }
                        vif({ ctx.attr.state.loadingText.isNotEmpty() }) {
                            Text {
                                attr {
                                    marginTop(10f)
                                    fontSize(12f)
                                    color(Color.WHITE)
                                    text(ctx.attr.state.loadingText)
                                }
                            }
                        }
                    }
                }
            }

            // —— Toast（参考工程 toastBuilder：底部居中胶囊，白 80% 底、黑字、最多 3 行）—— //
            vif({ ctx.attr.state.toastVisible }) {
                Modal(true) {
                    attr { touchEnable(false) }
                    View {
                        attr {
                            absolutePositionAllZero()
                            justifyContentFlexEnd()
                            alignItemsCenter()
                            // 参考工程：SafeArea(padding 全部 40) 底部 40 + SizedBox(40 + 原始底部安全区)
                            paddingBottom(80f + ctx.pagerData.safeAreaInsets.bottom)
                            touchEnable(false)
                        }
                        View {
                            attr {
                                // 0.8 屏宽，且受 SafeArea 左右各 40 限制
                                val w = ctx.pagerData.pageViewWidth
                                maxWidth(minOf(w * 0.8f, w - 80f))
                                minWidth(50f)
                                padding(6f, 20f, 6f, 20f)
                                borderRadius(34f)
                                backgroundColor(Color(255, 255, 255, 0.8f))
                                alignItemsCenter()
                            }
                            Text {
                                attr {
                                    fontSize(14f)
                                    lineHeight(14f * 1.3f)
                                    color(Color.BLACK)
                                    textAlignCenter()
                                    lines(3)
                                    textOverFlowTail()
                                    text(ctx.attr.state.toastText)
                                }
                            }
                        }
                    }
                }
            }

            // —— 顶部错误条 —— //
            vif({ ctx.attr.state.topErrorMounted }) {
                Modal(true) {
                    attr { touchEnable(false) }
                    View {
                        attr {
                            absolutePosition(
                                top = ctx.pagerData.statusBarHeight + 8f,
                                left = 16f,
                                right = 16f,
                            )
                            minHeight(60f)
                            padding(10f, 10f, 10f, 10f)
                            borderRadius(12f)
                            overflow(true)
                            // 参考：白 54%（ARGB 138）+ BackdropFilter blur 10；SmartDialog toast 默认淡入淡出
                            touchEnable(false)
                            opacity(if (ctx.attr.state.topErrorVisible) 1f else 0f)
                            animate(Animation.easeOut(0.2f), ctx.attr.state.topErrorVisible)
                        }
                        Blur {
                            attr {
                                absolutePositionAllZero()
                                blurRadius(10f)
                                touchEnable(false)
                            }
                        }
                        View {
                            attr {
                                absolutePositionAllZero()
                                backgroundColor(Color(255, 255, 255, 138f / 255f))
                            }
                        }
                        Text {
                            attr {
                                fontSize(16f)
                                fontWeightMedium()
                                color(Color.BLACK)
                                text("tip".tr)
                            }
                        }
                        Text {
                            attr {
                                marginTop(8f)
                                fontSize(14f)
                                fontWeightMedium()
                                color(Color.BLACK)
                                lines(3)
                                textOverFlowTail()
                                text(ctx.attr.state.topErrorText)
                            }
                        }
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.OverlayHost(init: OverlayHostView.() -> Unit) {
    addChild(OverlayHostView(), init)
}

// ---------------------------------------------------------------------------

class DialogContainerAttr : ComposeAttr() {
    lateinit var entry: DialogEntry
}

/** 单个弹窗：遮罩 + 进场 / 退场动画（居中淡入淡出 / 底部上滑下滑 / 顶部下滑上滑）。 */
class DialogContainerView : ComposeView<DialogContainerAttr, ComposeEvent>() {
    private var shown by observable(false)
    private var alive = true

    override fun createAttr(): DialogContainerAttr = DialogContainerAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun created() {
        super.created()
        attr.entry.leave = { done ->
            if (alive && shown) {
                shown = false
                setTimeout(pagerId, 220) { done() }
            } else {
                done()
            }
        }
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        setTimeout(pagerId, 16) { if (alive && !attr.entry.leaving) shown = true }
    }

    override fun viewDestroyed() {
        alive = false
        attr.entry.leave = null
        super.viewDestroyed()
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { absolutePositionAllZero() }
            // 遮罩
            View {
                attr {
                    absolutePositionAllZero()
                    backgroundColor(ctx.attr.entry.maskColor)
                    opacity(if (ctx.shown) 1f else 0f)
                    animate(Animation.easeOut(0.2f), ctx.shown)
                }
                event {
                    click {
                        if (ctx.attr.entry.dismissOnMask) ctx.attr.entry.handle.dismiss()
                    }
                }
            }
            // 内容定位层（不拦截空白处点击，交给遮罩）
            View {
                attr {
                    absolutePositionAllZero()
                    when (ctx.attr.entry.align) {
                        DialogEntry.ALIGN_BOTTOM -> {
                            justifyContentFlexEnd()
                            alignItemsStretch()
                            transform(Translate(0f, if (ctx.shown) 0f else 1f))
                        }
                        DialogEntry.ALIGN_TOP -> {
                            justifyContentFlexStart()
                            alignItemsStretch()
                            transform(Translate(0f, if (ctx.shown) 0f else -1f))
                        }
                        DialogEntry.ALIGN_FILL -> {
                            alignItemsStretch()
                            opacity(if (ctx.shown) 1f else 0f)
                        }
                        else -> {
                            allCenter()
                            opacity(if (ctx.shown) 1f else 0f)
                        }
                    }
                    animate(Animation.easeOut(if (ctx.shown) 0.25f else 0.2f), ctx.shown)
                }
                event {
                    click {
                        if (ctx.attr.entry.dismissOnMask) ctx.attr.entry.handle.dismiss()
                    }
                }
                // 内容（吞掉点击，避免穿透到遮罩）
                View {
                    attr {
                        if (ctx.attr.entry.align == DialogEntry.ALIGN_FILL) flex(1f)
                    }
                    event { click { } }
                    ctx.attr.entry.builder.invoke(this, ctx.attr.entry.handle)
                }
            }
        }
    }
}

fun ViewContainer<*, *>.DialogContainer(init: DialogContainerView.() -> Unit) {
    addChild(DialogContainerView(), init)
}

// ---------------------------------------------------------------------------

class BannerContainerAttr : ComposeAttr() {
    lateinit var entry: BannerEntry
    lateinit var state: OverlayState
}

/**
 * 单条顶部横幅：从上方滑入（带回弹）+ 淡入；关闭时向左滑出 500ms 后移除
 * （Dart RawDelightToast：距顶 70，elasticOut 进场，easeInOut 向左退场）。
 * 可左右横滑关闭（Dart `Dismissible`）：位移超过宽度 1/3 松手即滑出移除，否则回弹。
 */
class BannerContainerView : ComposeView<BannerContainerAttr, ComposeEvent>() {
    /** 0 进场前 / 1 展示中 / 2 退场中 / 3 横滑移出中 */
    private var phase by observable(0)
    private var dragging by observable(false)
    private var dragX by observable(0f)
    private var dragStartX = 0f
    private var bannerWidth = 0f
    private var alive = true

    override fun createAttr(): BannerContainerAttr = BannerContainerAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun created() {
        super.created()
        val id = attr.entry.id
        attr.entry.leave = {
            if (phase < 2) {
                phase = 2
                setTimeout(pagerId, 520) { attr.state.removeBanner(id) }
            }
        }
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        setTimeout(pagerId, 16) { if (alive && phase == 0) phase = 1 }
    }

    override fun viewDestroyed() {
        alive = false
        attr.entry.leave = null
        super.viewDestroyed()
    }

    private fun onPan(p: PanGestureParams) {
        if (phase != 1) return
        when (p.state) {
            "start" -> {
                dragStartX = p.pageX
                dragging = true
            }
            "move" -> dragX = p.pageX - dragStartX
            else -> {
                val dx = dragX
                if (bannerWidth > 0f && abs(dx) > bannerWidth / 3f) {
                    val id = attr.entry.id
                    phase = 3
                    attr.entry.handle.isShowing = false
                    setTimeout(pagerId, 220) { attr.state.removeBanner(id) }
                } else {
                    dragging = false
                    dragX = 0f
                }
            }
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                when (ctx.phase) {
                    0 -> {
                        opacity(0f)
                        transform(Translate(0f, -2f))
                    }
                    1 -> {
                        opacity(1f)
                        transform(Translate(0f, 0f, if (ctx.dragging) ctx.dragX else 0f, 0f))
                    }
                    3 -> {
                        opacity(0f)
                        transform(Translate(if (ctx.dragX < 0f) -1.2f else 1.2f, 0f))
                    }
                    else -> {
                        opacity(0f)
                        transform(Translate(-1.2f, 0f))
                    }
                }
                // 注册的动画在「下一次 key 变化」时生效：0 → 1 用回弹；拖动中注册 easeOut（松手回弹 / 横滑移出）；
                // 展示中注册 easeInOut（向左退场）。拖动过程中 key 不变，位移直接跟手。
                animate(
                    when {
                        ctx.phase == 0 -> Animation.springEaseOut(0.7f, 0.7f, 1f)
                        ctx.phase == 3 || ctx.dragging -> Animation.easeOut(0.2f)
                        else -> Animation.easeInOut(0.5f)
                    },
                    "${ctx.phase}-${ctx.dragging}",
                )
            }
            event {
                layoutFrameDidChange { ctx.bannerWidth = it.width }
                pan { p -> ctx.onPan(p) }
            }
            ctx.attr.entry.builder.invoke(this, ctx.attr.entry.handle)
        }
    }
}

fun ViewContainer<*, *>.BannerContainer(init: BannerContainerView.() -> Unit) {
    addChild(BannerContainerView(), init)
}
