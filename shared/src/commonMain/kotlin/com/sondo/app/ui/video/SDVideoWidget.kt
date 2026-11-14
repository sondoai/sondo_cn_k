package com.sondo.app.ui.video

import com.sondo.app.ui.SDFit
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.reactive.handler.observable

class SDVideoWidgetAttr : ComposeAttr() {
    /** 播放地址（obs；给了 controller 时忽略） */
    var url: String by observable("")
    /** 页面持有的控制器（obs；组件不会释放它） */
    var controller: AppVideoController? by observable(null)
    var coverUrl: String by observable("")
    /** 循环（Dart 拼写 isLopper，默认 true） */
    var isLopper: Boolean by observable(true)
    /** true → cover，false → contain */
    var isFill: Boolean by observable(true)
    var needLoading: Boolean = true
    var showStatusOverlays: Boolean = true
    var showCenterPlayButton: Boolean by observable(true)
    /** 底部完整控制条 + 进度条 */
    var showBottomControls: Boolean = false
    /** null → 1；0 → 静音 */
    var volume: Float? by observable(null)
    /** 外框宽高（都给时占位比例 = w/h）；不给则由调用方的布局决定 */
    var containerWidth: Float? = null
    var containerHeight: Float? = null
    var bgColor: Color? = null
    /** 当前可播放位置（obs）：同时决定 autoPlay */
    var isActive: Boolean by observable(true)
    var deferInitUntilActive: Boolean = false
    var forcePaused: Boolean by observable(false)
    /** 为空时绑定共享门闩 */
    var playbackGate: AppVideoPlaybackGate? = null
    var bypassPlaybackGate: Boolean = false
    var radius: Float = 0f
    var enableDoubleTap: Boolean = true
}

class SDVideoWidgetEvent : ComposeEvent() {
    internal var initialized: ((Float, Int, Int) -> Unit)? = null
    internal var playCompleted: (() -> Unit)? = null
    internal var initCompleted: ((AppVideoController) -> Unit)? = null
    internal var controllerReleased: (() -> Unit)? = null
    internal var error: (() -> Unit)? = null
    internal var tap: (() -> Unit)? = null
    internal var progress: ((Long, Long) -> Unit)? = null
    internal var playingChanged: ((Boolean) -> Unit)? = null

    /** (aspectRatio, resolution, durationSeconds) */
    fun onInitialized(handler: (aspectRatio: Float, resolution: Int, durationSeconds: Int) -> Unit) { initialized = handler }
    fun onPlayCompleted(handler: () -> Unit) { playCompleted = handler }
    /** 控制器就绪（Dart initCompleted(VideoPlayerController)） */
    fun initCompleted(handler: (AppVideoController) -> Unit) { initCompleted = handler }
    fun onControllerReleased(handler: () -> Unit) { controllerReleased = handler }
    fun onError(handler: () -> Unit) { error = handler }
    /** 业务接管单击（不再切换播放 / 暂停） */
    fun onTap(handler: () -> Unit) { tap = handler }
    /** Kuikly 扩展：进度（ms） */
    fun onProgress(handler: (positionMs: Long, durationMs: Long) -> Unit) { progress = handler }
    /** Kuikly 扩展：播放状态变化 */
    fun onPlayingChanged(handler: (Boolean) -> Unit) { playingChanged = handler }
}

/**
 * Dart `VideoWidget`（→ `AppVideoLegacyWidget` → `AppVideoPlayer`）：旧参数名的薄包装。
 * 固定：绑定共享门闩（可用 playbackGate 换局部门闩）、mixWithOthers = true（不抢系统音频焦点）、autoPlay = isActive。
 */
class SDVideoWidgetView : ComposeView<SDVideoWidgetAttr, SDVideoWidgetEvent>() {
    private var playerRef: ViewRef<SDAppVideoPlayerView>? = null

    /** 当前控制器（自建或外部传入） */
    val controller: AppVideoController? get() = playerRef?.view?.controller

    override fun createAttr(): SDVideoWidgetAttr = SDVideoWidgetAttr()
    override fun createEvent(): SDVideoWidgetEvent = SDVideoWidgetEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        val a = attr
        return {
            attr {
                a.containerWidth?.let { width(it) }
                a.containerHeight?.let { height(it) }
            }
            SDAppVideoPlayer {
                ref { ctx.playerRef = it }
                attr {
                    // 静态参数
                    val w = a.containerWidth
                    val h = a.containerHeight
                    if (w != null && h != null && w > 0f && h > 0f) aspectRatio = w / h
                    sizeByAspectRatio = false
                    flex(1f)
                    backgroundColor = a.bgColor ?: AppVideoColors.black
                    radius = a.radius
                    showLoading = a.needLoading
                    showStatusOverlays = a.showStatusOverlays
                    controlsMode = if (a.showBottomControls) AppVideoControlsMode.full else AppVideoControlsMode.none
                    autoPlay = a.isActive
                    deferInitUntilActive = a.deferInitUntilActive
                    playbackGate = a.playbackGate ?: AppVideoPlaybackGate.instance
                    bypassPlaybackGate = a.bypassPlaybackGate
                    mixWithOthers = true
                    enableDoubleTap = a.enableDoubleTap
                }
                attr {
                    // 响应式参数
                    controller = a.controller
                    url = if (a.controller == null) a.url else ""
                    coverUrl = a.coverUrl
                    looping = a.isLopper
                    fit = if (a.isFill) SDFit.COVER else SDFit.CONTAIN
                    val v = (a.volume ?: 1f).coerceIn(0f, 1f)
                    volume = v
                    muted = a.volume == 0f
                    showCenterPlayButton = a.showCenterPlayButton
                    showProgress = a.showBottomControls
                    isActive = a.isActive
                    forcePaused = a.forcePaused
                }
                event {
                    onInitialized { r, res, d -> ctx.event.initialized?.invoke(r, res, d) }
                    onReady { c -> ctx.event.initCompleted?.invoke(c) }
                    onCompleted { ctx.event.playCompleted?.invoke() }
                    onControllerReleased { ctx.event.controllerReleased?.invoke() }
                    onError { ctx.event.error?.invoke() }
                    onProgress { p, d -> ctx.event.progress?.invoke(p, d) }
                    onPlayingChanged { ctx.event.playingChanged?.invoke(it) }
                    if (ctx.event.tap != null) onTap { ctx.event.tap?.invoke() }
                }
            }
        }
    }
}

/** Dart `VideoWidget`。 */
fun ViewContainer<*, *>.SDVideoWidget(init: SDVideoWidgetView.() -> Unit) {
    addChild(SDVideoWidgetView(), init)
}
