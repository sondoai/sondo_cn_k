package com.sondo.app.ui.video

import com.sondo.app.ui.SDFit
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.dialog.VideoPreviewDismissResult
import com.sondo.app.ui.input.hideKeyboard
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.View

class SDInlinePreviewVideoCardAttr : ComposeAttr() {
    var url: String by observable("")
    var coverUrl: String by observable("")
    var containerWidth: Float = 0f
    var containerHeight: Float = 0f
    var radius: Float = 0f
    /** Dart visibilityKey：列表里区分卡片（用于调用方计算可见比例时的标识） */
    var visibilityKey: String = ""
    /**
     * 可见比例 ≥ [VISIBILITY_THRESHOLD] 时为 true（obs）。Kuikly 没有 VisibilityDetector：
     * 放在滚动列表里时，由列表在 scroll 事件里用 [inlineVisibleFraction] 算出后赋值；不在滚动容器里保持默认 true。
     */
    var isVisible: Boolean by observable(true)
    /** 打开全屏预览：入参为当前播放位置（ms，可空），返回关闭结果 */
    var onOpenFullscreen: (suspend (positionMs: Long?) -> VideoPreviewDismissResult?)? = null

    companion object {
        const val VISIBILITY_THRESHOLD = 0.85f
    }
}

/** 卡片在视口中的可见比例（0..1）：itemTop / viewportTop 用同一坐标系（如列表内容坐标）。 */
fun inlineVisibleFraction(itemTop: Float, itemHeight: Float, viewportTop: Float, viewportHeight: Float): Float {
    if (itemHeight <= 0f) return 0f
    val top = maxOf(itemTop, viewportTop)
    val bottom = minOf(itemTop + itemHeight, viewportTop + viewportHeight)
    return ((bottom - top) / itemHeight).coerceIn(0f, 1f)
}

/**
 * Dart `InlinePreviewVideoCard`：列表内联视频预览卡片。可见（≥85%）且共享门闩未被占用时才挂载解码器
 * （VideoWidget 默认：有声、与其它音频混播、contain、无加载圈、无居中按钮、无双击）；
 * 点击：收起键盘 → 记下当前位置 → 卸载解码器 → 下一帧打开全屏预览 → 关闭后重新挂载。
 */
class SDInlinePreviewVideoCardView : ComposeView<SDInlinePreviewVideoCardAttr, ComposeEvent>() {
    private var suspendedForDialog by observable(false)
    private var gateBlocked by observable(VideoPlaybackGate.isBlocked)
    private var readyController: AppVideoController? = null
    private var removeGate: (() -> Unit)? = null
    private var alive = true

    override fun createAttr(): SDInlinePreviewVideoCardAttr = SDInlinePreviewVideoCardAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun created() {
        super.created()
        removeGate = VideoPlaybackGate.addListener { v ->
            // 门闩变化推迟一帧再生效（Dart addPostFrameCallback）
            setTimeout(pagerId, 0) {
                if (!alive) return@setTimeout
                gateBlocked = v > 0
                if (!shouldMountPlayer()) readyController = null
            }
        }
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        bindValueChange({ attr.isVisible }) { v -> if (!(v as Boolean)) readyController = null }
    }

    override fun viewDestroyed() {
        alive = false
        unbindAllValueChange()
        removeGate?.invoke()
        removeGate = null
        readyController = null
        super.viewDestroyed()
    }

    internal fun shouldMountPlayer(): Boolean = attr.isVisible && !suspendedForDialog && !gateBlocked

    private fun readCurrentPosition(): Long? {
        val c = readyController ?: return null
        return if (c.isInitialized) c.positionMs else null
    }

    internal fun onTap() {
        hideKeyboard()
        val position = readCurrentPosition()
        suspendedForDialog = true
        readyController = null
        // 等 VideoWidget 从树上移除（原生解码器释放）后再打开弹窗
        setTimeout(pagerId, 0) {
            if (!alive) return@setTimeout
            val open = attr.onOpenFullscreen
            if (open == null) {
                suspendedForDialog = false
                return@setTimeout
            }
            (getPager() as Pager).lifecycleScope.launch {
                open(position)
                if (alive) suspendedForDialog = false
            }
        }
    }

    internal fun onControllerReady(c: AppVideoController) {
        if (!alive || !shouldMountPlayer()) return
        readyController = c
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val a = attr
        return {
            attr {
                size(a.containerWidth, a.containerHeight)
                if (a.radius > 0f) borderRadius(a.radius)
                overflow(true)
            }
            event { click { ctx.onTap() } }
            // 封面
            vif({ a.coverUrl.isEmpty() }) {
                View { attr { absolutePositionAllZero(); backgroundColor(Color.BLACK) } }
            }
            vif({ a.coverUrl.isNotEmpty() }) {
                SDImage {
                    attr {
                        absolutePositionAllZero()
                        url = a.coverUrl
                        fit = SDFit.COVER
                    }
                }
            }
            vif({ ctx.shouldMountPlayer() && a.url.isNotEmpty() }) {
                SDVideoWidget {
                    attr {
                        absolutePositionAllZero()
                        containerWidth = a.containerWidth
                        containerHeight = a.containerHeight
                        radius = a.radius
                        isFill = false
                        deferInitUntilActive = true
                        needLoading = false
                        // 播放器默认单击切暂停会吃掉外层进全屏；居中播放钮也会单独 play
                        showCenterPlayButton = false
                        enableDoubleTap = false
                    }
                    attr {
                        url = a.url
                        coverUrl = a.coverUrl
                        isActive = true
                    }
                    event {
                        onTap { ctx.onTap() }
                        initCompleted { c -> ctx.onControllerReady(c) }
                    }
                }
            }
        }
    }
}

/** Dart `InlinePreviewVideoCard`。 */
fun ViewContainer<*, *>.SDInlinePreviewVideoCard(init: SDInlinePreviewVideoCardView.() -> Unit) {
    addChild(SDInlinePreviewVideoCardView(), init)
}
