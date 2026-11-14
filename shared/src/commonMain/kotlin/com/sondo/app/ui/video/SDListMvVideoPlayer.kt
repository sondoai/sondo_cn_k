package com.sondo.app.ui.video

import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.i18n.tr
import com.sondo.app.data.models.SquareModel
import com.sondo.app.service.VideoCacheService
import com.sondo.app.ui.SDFit
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDLoading
import com.sondo.app.ui.atoms.SDSeekBar
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.clearTimeout
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

class SDListMvVideoPlayerAttr : ComposeAttr() {
    /** 条目（obs：替换元素即可刷新） */
    var mv: SquareModel by observable(SquareModel())
    var index: Int = 0
    /** 是否当前页（obs）：只有当前页挂载解码器 */
    var isCurrentPage: Boolean by observable(false)
    /** 初始播放位置（ms，-1 = 无） */
    var initialPositionMs: Long = -1L
    var showProgress: Boolean = true
    /** 预加载（obs）：非当前页变为 true 时只预热磁盘缓存 */
    var isPreloading: Boolean by observable(false)
    /** 列表滑动中（obs）：卸掉叠加层 */
    var isScrolling: Boolean by observable(false)
    var bypassPlaybackGate: Boolean = false
    var isLopper: Boolean = true
    /** 只暂停、保留当前帧（obs；登录弹层 / 页面内弹窗盖住时） */
    var forcePaused: Boolean by observable(false)
    var showAiLabel: Boolean = false
}

class SDListMvVideoPlayerEvent : ComposeEvent() {
    internal var controllerReady: ((Int, AppVideoController) -> Unit)? = null
    internal var disposed: ((Int) -> Unit)? = null
    internal var progress: ((Int, Long, Long) -> Unit)? = null
    internal var pausedChanged: ((Int, Boolean) -> Unit)? = null
    internal var initialPositionApplied: (() -> Unit)? = null
    internal var playCompleted: (() -> Unit)? = null

    fun onControllerReady(handler: (index: Int, controller: AppVideoController) -> Unit) { controllerReady = handler }
    /** 控制器释放（滑走 / 重试 / 销毁） */
    fun onDisposed(handler: (index: Int) -> Unit) { disposed = handler }
    fun onProgress(handler: (index: Int, positionMs: Long, durationMs: Long) -> Unit) { progress = handler }
    fun onPausedChanged(handler: (index: Int, isPaused: Boolean) -> Unit) { pausedChanged = handler }
    fun onInitialPositionApplied(handler: () -> Unit) { initialPositionApplied = handler }
    fun onPlayCompleted(handler: () -> Unit) { playCompleted = handler }
}

/**
 * Dart `ListMvVideoPlayer`：整屏 MV 翻页里的一页（广场播放页、作品库 MV 详情、视频预览弹窗）。
 *
 * - 只有当前页挂载 [SDVideoWidget]（上下各留 12），封面常驻在上层，开始播放并上屏后 350ms 淡出；
 * - 单击切换播放 / 暂停（出错时重试）；双击快进 10 秒（同 VideoWidget 默认）；
 * - 页面不可见（Dart: `Get.currentRoute != pageId`）时暂停 —— 由播放器的 pauseOnPageHidden 负责；
 *   页面内弹层盖住当前视频时由调用方传 [SDListMvVideoPlayerAttr.forcePaused]；
 * - 叠加层：缓冲转圈（封面已隐藏后）、暂停图标（46 播放图标，黑 35% 圆底）、底部 AI 标识 + 进度条。
 */
class SDListMvVideoPlayerView : ComposeView<SDListMvVideoPlayerAttr, SDListMvVideoPlayerEvent>() {
    private var controller: AppVideoController? by observable(null)
    private var hasError by observable(false)
    private var coverHidden by observable(false)
    private var retryKey by observable(0)
    private var revealGen = 0
    private var revealTimer: String? = null
    private var initialApplied = false
    private var alive = true

    override fun createAttr(): SDListMvVideoPlayerAttr = SDListMvVideoPlayerAttr()
    override fun createEvent(): SDListMvVideoPlayerEvent = SDListMvVideoPlayerEvent()

    override fun viewDidLoad() {
        super.viewDidLoad()
        bindValueChange({ attr.isPreloading && !attr.isCurrentPage }) { v ->
            if (v as Boolean) VideoCacheService.prefetchInBackground(attr.mv.resultUrl)
        }
        bindValueChange({ attr.isCurrentPage }) { v ->
            if (!(v as Boolean)) {
                // 离开当前页：复位封面，下次回来重新揭示
                handleReleased()
                coverHidden = false
            }
        }
        // 封面揭示：开始播放后最多等 3000ms，再等 200ms
        bindValueChange({ "${controller?.isPlaying}|${controller?.firstFrameRendered}|${controller?.hashCode()}" }) {
            maybeReveal()
        }
    }

    override fun viewDestroyed() {
        alive = false
        revealTimer?.let { clearTimeout(it) }
        handleReleased()
        unbindAllValueChange()
        super.viewDestroyed()
    }

    private fun maybeReveal() {
        val c = controller ?: return
        if (coverHidden || revealTimer != null || !c.isPlaying) return
        val gen = ++revealGen
        val wait = if (c.firstFrameRendered) 200 else 3000
        revealTimer = setTimeout(pagerId, wait) {
            revealTimer = null
            if (!alive || gen != revealGen || controller !== c) return@setTimeout
            if (c.firstFrameRendered || wait == 3000) coverHidden = true else maybeReveal()
        }
    }

    private fun onControllerReady(c: AppVideoController) {
        if (!alive || !attr.isCurrentPage) return
        controller = c
        event.controllerReady?.invoke(attr.index, c)
        if (!initialApplied && attr.initialPositionMs >= 0L) {
            initialApplied = true
            val d = c.durationMs
            val target = if (d > 0L) attr.initialPositionMs.coerceAtMost(d) else attr.initialPositionMs
            c.seekTo(target)
            if (attr.isCurrentPage && !attr.forcePaused) c.play()
            event.initialPositionApplied?.invoke()
        }
    }

    private fun handleReleased() {
        if (controller != null) {
            controller = null
            event.disposed?.invoke(attr.index)
        }
        revealGen++
        revealTimer?.let { clearTimeout(it) }
        revealTimer = null
    }

    internal fun togglePlay() {
        if (hasError) {
            retry()
            return
        }
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
            event.pausedChanged?.invoke(attr.index, true)
        } else if (pageVisible() && !attr.forcePaused) {
            c.play()
            event.pausedChanged?.invoke(attr.index, false)
        }
    }

    private fun pageVisible(): Boolean = (getPager() as? com.sondo.app.base.BasePager)?.isPageVisible ?: true

    internal fun retry() {
        handleReleased()
        hasError = false
        coverHidden = false
        retryKey++
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val a = attr
        return {
            attr { backgroundColor(Color.BLACK) }
            event { click { ctx.togglePlay() } }

            vif({ ctx.hasError }) {
                View {
                    attr {
                        absolutePositionAllZero()
                        backgroundColor(Color.BLACK)
                        allCenter()
                    }
                    VideoIcon(VideoIconKind.ERROR_OUTLINE, 64f, Color(0x8AFFFFFFL))
                    View { attr { height(16f) } }
                    Text {
                        attr {
                            text("video_load_failed".tr)
                            fontSize(14f)
                            color(Color(0x8AFFFFFFL))
                        }
                    }
                    View { attr { height(24f) } }
                    View {
                        attr {
                            flexDirectionRow()
                            alignItemsCenter()
                            padding(10f, 24f, 10f, 24f)
                            borderRadius(20f)
                            border(com.tencent.kuikly.core.base.Border(1f, com.tencent.kuikly.core.base.BorderStyle.SOLID, Color(0x61FFFFFFL)))
                        }
                        event { click { ctx.retry() } }
                        VideoIcon(VideoIconKind.REFRESH, 18f, Color(0xB3FFFFFFL))
                        View { attr { width(6f) } }
                        Text {
                            attr {
                                text("retry".tr)
                                fontSize(14f)
                                color(Color(0xB3FFFFFFL))
                            }
                        }
                    }
                }
            }
            vif({ !ctx.hasError }) {
                View {
                    attr { absolutePositionAllZero() }
                    // 仅当前页挂播放器；重试时换 key 重建
                    vif({ a.isCurrentPage }) {
                        vbind({ ctx.retryKey }) {
                            View {
                                attr {
                                    absolutePositionAllZero()
                                    padding(12f, 0f, 12f, 0f)
                                }
                                SDVideoWidget {
                                    attr {
                                        flex(1f)
                                        bgColor = Color(0, 0, 0, 0f)
                                        isFill = false
                                        showCenterPlayButton = false
                                        isLopper = a.isLopper
                                        bypassPlaybackGate = a.bypassPlaybackGate
                                    }
                                    attr {
                                        val cover = a.mv.cover
                                        url = a.mv.resultUrl
                                        coverUrl = if (cover.isNotEmpty()) "" else cover
                                        needLoading = cover.isEmpty()
                                        isActive = a.isCurrentPage
                                        forcePaused = a.forcePaused
                                    }
                                    event {
                                        initCompleted { c -> ctx.onControllerReady(c) }
                                        onControllerReleased { ctx.handleReleased() }
                                        onError { ctx.hasError = true; ctx.handleReleased() }
                                        onPlayCompleted { ctx.event.playCompleted?.invoke() }
                                        onProgress { p, d -> ctx.event.progress?.invoke(a.index, p, d) }
                                        onTap { ctx.togglePlay() }
                                    }
                                }
                            }
                        }
                    }
                    // 常驻封面
                    vif({ a.mv.cover.isNotEmpty() }) {
                        View {
                            attr {
                                absolutePositionAllZero()
                                touchEnable(false)
                            }
                            View {
                                attr {
                                    absolutePositionAllZero()
                                    padding(12f, 0f, 12f, 0f)
                                    // 封面上下留黑：解码器刚出首帧时还不知道视频尺寸，画面会先拉满整块区域，
                                    // 透明的留白处就会露出来（开始播放时顶部、底部闪一下）。封面淡出前一直盖住。
                                    backgroundColor(Color.BLACK)
                                    opacity(if (ctx.coverHidden) 0f else 1f)
                                    animate(Animation.easeOut(0.35f), ctx.coverHidden)
                                }
                                // Dart Center(ImageWidget(cover, width: ∞)) 不给高度：按屏宽、高度随图片比例居中，
                                // 与视频的 contain 一致，淡出时画面不跳
                                SDImage {
                                    attr {
                                        flex(1f)
                                        url = a.mv.cover
                                        fit = SDFit.CONTAIN
                                    }
                                }
                            }
                        }
                    }
                    // 叠加层
                    vif({ ctx.controller != null && a.isCurrentPage && !a.isScrolling }) {
                        View {
                            attr {
                                absolutePositionAllZero()
                                touchEnable(false)
                            }
                            vif({ ctx.controller?.let { it.isBuffering && !it.isPlaying } == true && ctx.coverHidden }) {
                                View {
                                    attr {
                                        absolutePositionAllZero()
                                        allCenter()
                                    }
                                    SDLoading { }
                                }
                            }
                            vif({ ctx.controller?.let { !it.isPlaying && !(it.isBuffering && it.playIntent) } == true }) {
                                View {
                                    attr {
                                        absolutePositionAllZero()
                                        allCenter()
                                    }
                                    View {
                                        attr {
                                            padding(4f)
                                            borderRadius(50f)
                                            backgroundColor(Color(0, 0, 0, 0.35f))
                                        }
                                        VideoIcon(VideoIconKind.PLAY, 46f, Color.WHITE)
                                    }
                                }
                            }
                        }
                    }
                    // 加载条与播放进度条是同一个 SDSeekBar（同一条轨道，宽高、位置完全一致）：
                    // 封面盖着时轨道里扫光表示加载，封面淡出后同一条轨道直接显示真实进度，不换条、不跳动。
                    // 没有时长时进度条不吃触摸（见 SDSeekBar），点按照常落到播放器上。
                    if (a.showProgress) {
                        View {
                            attr { absolutePosition(left = 0f, right = 0f, bottom = 0f) }
                            if (a.showAiLabel) {
                                vif({ ctx.controller != null && a.isCurrentPage && !a.isScrolling }) {
                                    Text {
                                        attr {
                                            marginLeft(12f)
                                            marginBottom(2f)
                                            text("ai_generated_label".tr)
                                            color(UIColor.transparent40)
                                            fontSize(12f)
                                            lineHeight(12f * 1.2f)
                                            fontWeightMedium()
                                        }
                                    }
                                }
                            }
                            SDSeekBar {
                                attr {
                                    paddingH = 10f
                                    val loading = a.mv.cover.isNotEmpty() && !ctx.coverHidden
                                    val seekable = ctx.controller != null && a.isCurrentPage && !a.isScrolling
                                    this.loading = loading
                                    duration = if (loading || !seekable) 0f else (ctx.controller?.durationMs ?: 0L).toFloat()
                                    position = (ctx.controller?.positionMs ?: 0L).toFloat()
                                    buffered = 0f
                                }
                                event { onSeekEnd { ms -> ctx.controller?.seekTo(ms.toLong()) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Dart `ListMvVideoPlayer`。 */
fun ViewContainer<*, *>.SDListMvVideoPlayer(init: SDListMvVideoPlayerView.() -> Unit) {
    addChild(SDListMvVideoPlayerView(), init)
}
