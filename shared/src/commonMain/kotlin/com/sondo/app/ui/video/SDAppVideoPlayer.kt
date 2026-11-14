package com.sondo.app.ui.video

import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.core.constants.AppTheme
import com.sondo.app.nativeapi.SDVideo
import com.sondo.app.nativeapi.SDVideoState
import com.sondo.app.nativeapi.SDVideoView
import com.sondo.app.service.Svc
import com.sondo.app.ui.SDFit
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDLoading
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.event.layoutFrameDidChange
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.IPagerEventObserver
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.clearTimeout
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

// =====================================================================================
// SDAppVideoPlayer —— Dart AppVideoPlayer / AbstractAppVideoPlayer（+ FeedVideoPlayer / MvVideoPlayer 预设）
// =====================================================================================

class SDAppVideoPlayerAttr : ComposeAttr() {
    // ---- 播放源（controller 与 url 二选一；都给时 controller 优先）
    /** 页面持有的控制器（obs）。为空时组件按 [url] 自建控制器并负责释放 */
    var controller: AppVideoController? by observable(null)
    /** 原始地址（obs）：http(s) / 本地路径 / `assets/…`；空串只显示封面、不创建解码器 */
    var url: String by observable("")

    // ---- 封面
    /** 封面地址（obs）：assets 相对路径或网络图 */
    var coverUrl: String by observable("")
    /** 自定义封面（优先于 coverUrl） */
    var cover: ViewBuilder? = null
    var showCover: Boolean = true
    var coverFadeMs: Int = 180
    /** 等待真正开始播放的最长时间 */
    var coverRevealTimeoutMs: Int = 3000
    /** 首帧之后再等一会儿再淡出封面，避免黑闪 */
    var coverRevealSettleMs: Int = 200

    // ---- 布局
    /** 占位宽高比（inline / thumbnail 且 [sizeByAspectRatio] 时生效）；0 = 视频比例，未知时 16:9 */
    var aspectRatio: Float = 0f
    /** inline / thumbnail 模式按宽高比自动算高度（调用方自己给了高度时设 false） */
    var sizeByAspectRatio: Boolean = true
    var fit: SDFit by observable(SDFit.CONTAIN)
    var radius: Float = 0f
    var displayMode: AppVideoDisplayMode = AppVideoDisplayMode.inlineBox
    var backgroundColor: Color = AppVideoColors.black

    // ---- 控制层
    var controlsMode: AppVideoControlsMode = AppVideoControlsMode.full
    var labels: AppVideoPlayerLabels = AppVideoPlayerLabels()
    var showCenterPlayButton: Boolean by observable(true)
    var autoHideControls: Boolean = true
    var controlsHideDelayMs: Int = 3000
    var playbackSpeeds: List<Float> = listOf(0.5f, 1f, 1.25f, 1.5f, 2f)
    /** 双击快进步长 */
    var seekStepMs: Long = 10_000L
    var tapToTogglePlayback: Boolean = true
    var enableDoubleTap: Boolean = true

    // ---- 状态层
    var showLoading: Boolean = true
    /** 总开关：加载、缓冲、错误重试、居中播放 */
    var showStatusOverlays: Boolean = true
    var showBufferingIndicator: Boolean = true

    // ---- 进度条
    var showProgress: Boolean by observable(true)
    /** 进度条外边距（上、左、下、右；Dart progressPadding） */
    var progressPadding: FloatArray = floatArrayOf(0f, 0f, 0f, 0f)
    var progressHeight: Float = 3f
    var allowScrubbing: Boolean = true
    var showScrubTime: Boolean = true

    // ---- 播放
    var autoPlay: Boolean = false
    var looping: Boolean by observable(false)
    var muted: Boolean by observable(false)
    var volume: Float by observable(1f)
    /** 准备好后先 seek 到这里（ms，-1 = 无；解码器重建时只要还 ≥0 就再 seek 一次） */
    var seekOnReadyMs: Long by observable(-1L)
    var resumeKey: String? = null
    var resumeStore: AppVideoResumeStore = InMemoryAppVideoResumeStore
    /** null → 场景默认（profile） */
    var mixWithOthers: Boolean? = null
    var profile: AppVideoPlaybackProfile = AppVideoPlaybackProfile.default

    // ---- 系统
    var pauseOnLifecycleChange: Boolean = true
    /** 自建控制器播放中保持亮屏 */
    var keepScreenOn: Boolean = true
    var resumeAfterLifecycle: Boolean = true
    /** 所在页面不可见（被盖住 / 切走）时暂停，回来按之前的播放状态恢复 */
    var pauseOnPageHidden: Boolean = true
    var disposeExternalController: Boolean = false

    // ---- 重试
    var maxInitializationRetries: Int = 3
    var retryBaseDelayMs: Int = 500
    var retryStrategy: AppVideoRetryStrategy? = null

    // ---- 活跃度
    /** 当前、可见、前台的播放器（obs） */
    var isActive: Boolean by observable(true)
    /** true → 不活跃 / 被门闩挡住时卸载解码器 */
    var deferInitUntilActive: Boolean = false
    /** 暂停但保留解码器与当前帧（obs） */
    var forcePaused: Boolean by observable(false)
    var playbackGate: AppVideoPlaybackGate? = null
    var bypassPlaybackGate: Boolean = false

    // ---- 错误
    /** 业务错误（obs，如取签名地址失败）；非空时显示错误层 */
    var error: String? by observable(null)
    /** 自定义错误层：(message, retry) */
    var errorBuilder: (ViewContainer<*, *>.(message: String, retry: () -> Unit) -> Unit)? = null
    /** 业务叠加层（Dart overlayBuilder），可读 controller 的响应式状态 */
    var overlayBuilder: (ViewContainer<*, *>.(controller: AppVideoController) -> Unit)? = null

    var progressIntervalMs: Int = 250

    /** FeedVideoPlayer 默认值（fit cover、沉浸、无按钮、带进度、自动循环播放、延迟初始化）。 */
    fun feedDefaults() {
        profile = AppVideoPlaybackProfile.feed
        fit = SDFit.COVER
        displayMode = AppVideoDisplayMode.immersive
        controlsMode = AppVideoControlsMode.none
        showProgress = true
        autoPlay = true
        looping = true
        muted = false
        showCenterPlayButton = true
        enableDoubleTap = false
        isActive = true
        deferInitUntilActive = true
    }

    /** FeedVideoPlayer.banner 默认值（静音循环背景，无任何状态层，前后台由 isActive 覆盖）。 */
    fun bannerDefaults() {
        profile = AppVideoPlaybackProfile.feed
        aspectRatio = 16f / 9f
        fit = SDFit.COVER
        autoPlay = true
        looping = true
        muted = true
        displayMode = AppVideoDisplayMode.background
        controlsMode = AppVideoControlsMode.none
        showProgress = false
        showLoading = false
        showStatusOverlays = false
        showCenterPlayButton = false
        deferInitUntilActive = true
        pauseOnLifecycleChange = false
    }

    /** MvVideoPlayer 默认值（contain、沉浸、无按钮、带进度、循环、独占音频）。 */
    fun mvDefaults() {
        profile = AppVideoPlaybackProfile.mv
        fit = SDFit.CONTAIN
        displayMode = AppVideoDisplayMode.immersive
        controlsMode = AppVideoControlsMode.none
        showProgress = true
        looping = true
        showCenterPlayButton = true
    }
}

class SDAppVideoPlayerEvent : ComposeEvent() {
    internal var tap: (() -> Unit)? = null
    internal var doubleTap: (() -> Unit)? = null
    internal var fullscreen: (() -> Unit)? = null
    internal var retry: (() -> Unit)? = null
    internal var ready: ((AppVideoController) -> Unit)? = null
    internal var initialized: ((aspectRatio: Float, resolution: Int, durationSeconds: Int) -> Unit)? = null
    internal var completed: ((AppVideoController) -> Unit)? = null
    internal var controllerReleased: ((AppVideoController) -> Unit)? = null
    internal var error: ((String) -> Unit)? = null
    internal var playingChanged: ((Boolean) -> Unit)? = null
    internal var progress: ((positionMs: Long, durationMs: Long) -> Unit)? = null
    internal var seekApplied: (() -> Unit)? = null
    internal var firstFrame: (() -> Unit)? = null

    /** 业务接管单击（组件只短暂展示控制层） */
    fun onTap(handler: () -> Unit) { tap = handler }
    /** 替代双击快进 */
    fun onDoubleTap(handler: () -> Unit) { doubleTap = handler }
    /** 设置后显示全屏按钮 */
    fun onFullscreen(handler: () -> Unit) { fullscreen = handler }
    /** 替代内置重试 */
    fun onRetry(handler: () -> Unit) { retry = handler }
    /** 每个源一次：控制器可播放 */
    fun onReady(handler: (AppVideoController) -> Unit) { ready = handler }
    /** 旧 VideoWidget 的元数据回调 */
    fun onInitialized(handler: (aspectRatio: Float, resolution: Int, durationSeconds: Int) -> Unit) { initialized = handler }
    /** 非循环播完 */
    fun onCompleted(handler: (AppVideoController) -> Unit) { completed = handler }
    /** 换源 / 卸载前：页面应丢掉旧引用 */
    fun onControllerReleased(handler: (AppVideoController) -> Unit) { controllerReleased = handler }
    /** 最终失败（自动重试用完）或业务错误 */
    fun onError(handler: (String) -> Unit) { error = handler }
    fun onPlayingChanged(handler: (Boolean) -> Unit) { playingChanged = handler }
    fun onProgress(handler: (positionMs: Long, durationMs: Long) -> Unit) { progress = handler }
    fun onSeekApplied(handler: () -> Unit) { seekApplied = handler }
    fun onFirstFrame(handler: () -> Unit) { firstFrame = handler }
}

/**
 * 通用视频播放器（Dart `AppVideoPlayer`）：封面 → 首帧淡出、加载 / 缓冲 / 错误重试、居中播放按钮、
 * 控制条（播放、时间、静音、倍速、全屏）、可拖动进度条、单击 / 双击手势、控制层自动隐藏、
 * 活跃度策略（isActive / forcePaused / 门闩 / 延迟初始化）、页面不可见与前后台暂停恢复、续播。
 *
 * 只让一个播放器有声：有声播放统一经过 `AppAudioOutputCoordinator`（见 [AppVideoController]）。
 * 命令式控制：拿 `controller`（自己传入的，或 `onReady` 回调给出的）调用 play / pause / seekTo …。
 */
class SDAppVideoPlayerView : ComposeView<SDAppVideoPlayerAttr, SDAppVideoPlayerEvent>(), IPagerEventObserver {

    private var ownedController: AppVideoController? = null
    /** 当前生效的控制器（obs，换 controller 时刷新 UI） */
    var current: AppVideoController? by observable(null)
        private set

    private var gateCount by observable(0)
    private var pageVisible by observable(true)
    private var inBackground by observable(false)
    private var coverRevealed by observable(false)
    private var controlsVisible by observable(false)
    private var speedMenuOpen by observable(false)
    private var retrying by observable(false)
    private var boxWidth by observable(0f)

    private var mountedView: SDVideoView? = null
    private var mountedCtrl: AppVideoController? = null
    private var removeGateListener: (() -> Unit)? = null
    private var lifecycleRef: CallbackRef? = null
    private var removeCtrlListener: (() -> Unit)? = null
    private var hideTimer: String? = null
    private var revealTimer: String? = null
    private var retryTimer: String? = null
    private var retryIndex = 0
    private var readyNotified = false
    private var wasPlayingBeforeHidden = false
    private var lastEffectivelyActive = false
    private var policyInitialized = false
    private var autoPlayed = false
    private var lastPlaying = false
    private var alive = true

    /** 播放器控制器（自建或外部传入）；未初始化时为 null */
    val controller: AppVideoController? get() = current

    override fun createAttr(): SDAppVideoPlayerAttr = SDAppVideoPlayerAttr()
    override fun createEvent(): SDAppVideoPlayerEvent = SDAppVideoPlayerEvent()

    private val basePager: BasePager? get() = getPager() as? BasePager

    // ------------------------------------------------------------------ 生命周期

    override fun created() {
        super.created()
        pageVisible = basePager?.isPageVisible ?: true
        inBackground = Svc.appInBackground
        bindController(attr.controller)
        attr.playbackGate?.let { gate ->
            gateCount = gate.value
            removeGateListener = gate.addListener { v -> if (alive) gateCount = v }
        }
        (getPager() as Pager).addPagerEventObserver(this)
        lifecycleRef = (getPager() as Pager).acquireModule<NotifyModule>(NotifyModule.MODULE_NAME)
            .addNotify(Events.APP_LIFECYCLE) { data ->
                if (alive) inBackground = !(data?.optBoolean("foreground", true) ?: true)
            }
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        // 外部换 controller / url
        bindValueChange({ attr.controller ?: 0 }) { bindController(attr.controller) }
        bindValueChange({ attr.url }) { v ->
            val owned = ownedController
            if (owned != null && current === owned) {
                releaseCurrentDecoderCallbacks()
                owned.setSource(v as String, autoPlay = owned.playIntent || attr.autoPlay)
                resetForNewSource()
            }
        }
        // 播放参数推给控制器
        bindValueChange({ "${attr.looping}|${attr.muted}|${attr.volume}" }) {
            val c = current ?: return@bindValueChange
            c.setLooping(attr.looping)
            c.setVolume(attr.volume)
            c.setMuted(attr.muted || attr.volume <= 0f)
        }
        bindValueChange({ shouldMount() }) { m -> onMountChanged(m as Boolean) }
        // 活跃度 / 门闩 / 页面可见 / 前后台
        bindValueChange({ policyKey() }) { syncPolicy() }
        // 封面揭示：开始播放 + 首帧（或超时）后再等一帧
        bindValueChange({
            val c = current
            "${c?.generation}|${c?.isPlaying}|${c?.firstFrameRendered}|${c?.hasDecoder}"
        }) { syncCoverReveal() }
        bindValueChange({ current?.isPlaying ?: false }) { v ->
            val playing = v as Boolean
            if (playing != lastPlaying) {
                lastPlaying = playing
                event.playingChanged?.invoke(playing)
                if (playing) scheduleHideControls() else cancelHideControls()
            }
        }
    }

    override fun viewDestroyed() {
        alive = false
        cancelHideControls()
        revealTimer?.let { clearTimeout(it) }
        retryTimer?.let { clearTimeout(it) }
        removeGateListener?.invoke()
        removeGateListener = null
        (getPager() as Pager).removePagerEventObserver(this)
        lifecycleRef?.let {
            (getPager() as Pager).acquireModule<NotifyModule>(NotifyModule.MODULE_NAME).removeNotify(Events.APP_LIFECYCLE, it)
        }
        lifecycleRef = null
        unbindCurrent(disposeOwned = true)
        unbindAllValueChange()
        super.viewDestroyed()
    }

    override fun onPagerEvent(pagerEvent: String, eventData: JSONObject) {
        if (!alive) return
        when (pagerEvent) {
            Pager.PAGER_EVENT_DID_APPEAR -> pageVisible = true
            Pager.PAGER_EVENT_DID_DISAPPEAR -> pageVisible = false
        }
    }

    // ------------------------------------------------------------------ 控制器绑定

    private fun bindController(external: AppVideoController?) {
        val target = external ?: ownedController ?: createOwned()
        if (target === current) return
        unbindCurrent(disposeOwned = external != null)
        current = target
        target.setLooping(attr.looping)
        target.setVolume(attr.volume)
        target.setMuted(attr.muted || attr.volume <= 0f)
        removeCtrlListener = target.addListener(listener)
        readyNotified = target.isInitialized
        resetForNewSource()
        if (target.isInitialized) notifyReady(target)
    }

    private fun createOwned(): AppVideoController {
        val c = AppVideoController(
            scope = this,
            url = attr.url,
            looping = attr.looping,
            muted = attr.muted || attr.volume <= 0f,
            volume = attr.volume,
            mixWithOthers = attr.mixWithOthers ?: attr.profile.mixWithOthers,
            resumeKey = attr.resumeKey,
            resumeStore = attr.resumeStore,
            keepScreenOn = attr.keepScreenOn,
        )
        ownedController = c
        return c
    }

    private fun unbindCurrent(disposeOwned: Boolean) {
        val c = current ?: return
        releaseCurrentDecoderCallbacks()
        removeCtrlListener?.invoke()
        removeCtrlListener = null
        if (mountedView != null) mountedCtrl?.detachCurrentView()
        mountedView = null
        mountedCtrl = null
        if (c === ownedController) {
            if (disposeOwned) {
                c.dispose()
                ownedController = null
            }
        } else if (attr.disposeExternalController) {
            c.dispose()
        } else {
            c.pause()
        }
        current = null
    }

    /** 换源 / 卸载前通知页面（Dart onControllerReleased）。 */
    private fun releaseCurrentDecoderCallbacks() {
        val c = current ?: return
        if (mountedView != null) event.controllerReleased?.invoke(c)
    }

    private fun resetForNewSource() {
        coverRevealed = false
        readyNotified = false
        retryIndex = 0
        retrying = false
        retryTimer?.let { clearTimeout(it) }
        retryTimer = null
        revealTimer?.let { clearTimeout(it) }
        revealTimer = null
    }

    private val listener = object : AppVideoControllerListener {
        override fun onReady(controller: AppVideoController) {
            if (!alive || controller !== current) return
            retryIndex = 0
            retrying = false
            if (attr.seekOnReadyMs >= 0L) event.seekApplied?.invoke()
            if (!readyNotified) {
                readyNotified = true
                notifyReady(controller)
            }
            // 准备好时策略已允许播放 → 自动播放
            if (attr.autoPlay && currentPolicy().canPlay && pageCanPlay()) controller.play()
        }

        override fun onProgress(controller: AppVideoController, positionMs: Long, durationMs: Long) {
            if (controller === current) event.progress?.invoke(positionMs, durationMs)
        }

        override fun onFirstFrame(controller: AppVideoController) {
            if (controller === current) event.firstFrame?.invoke()
        }

        override fun onCompleted(controller: AppVideoController) {
            if (controller === current) event.completed?.invoke(controller)
        }

        override fun onError(controller: AppVideoController, error: String) {
            if (!alive || controller !== current) return
            scheduleAutoRetry(error)
        }
    }

    private fun notifyReady(c: AppVideoController) {
        event.ready?.invoke(c)
        val ratio = if (c.aspectRatio > 0f) c.aspectRatio else (if (attr.aspectRatio > 0f) attr.aspectRatio else 16f / 9f)
        event.initialized?.invoke(ratio, appVideoResolutionBucket(c.videoWidth, c.videoHeight), (c.durationMs / 1000L).toInt())
    }

    // ------------------------------------------------------------------ 策略

    private fun currentPolicy(): AppVideoPlaybackPolicy = AppVideoPlaybackPolicy(
        isActive = attr.isActive,
        forcePaused = attr.forcePaused,
        deferInitUntilActive = attr.deferInitUntilActive,
        bypassPlaybackGate = attr.bypassPlaybackGate,
        playbackGateBlocked = attr.playbackGate != null && gateCount > 0,
        pauseOnLifecycleChange = attr.pauseOnLifecycleChange,
        isInBackground = inBackground,
        hasOwnedController = current != null && current === ownedController,
    )

    private fun pausedOnlyByForce(): Boolean = attr.isActive && attr.forcePaused

    private fun pageCanPlay(): Boolean = !attr.pauseOnPageHidden || pageVisible

    private fun policyKey(): String =
        "${attr.isActive}|${attr.forcePaused}|$gateCount|$inBackground|$pageVisible|${current?.hashCode()}"

    /** 是否挂载原生视图（= 持有解码器）。 */
    private fun shouldMount(): Boolean {
        val c = current ?: return false
        if (c.url.isEmpty() || !attr.error.isNullOrEmpty()) return false
        val p = currentPolicy()
        if (p.shouldReleaseOwnedDecoder) return false
        if (!p.canInitialize) return false
        // 外部控制器也遵守延迟初始化（页面队列只让当前页持有解码器）
        if (attr.deferInitUntilActive && !attr.isActive) return false
        return true
    }

    private fun syncPolicy() {
        val c = current ?: return
        val p = currentPolicy()
        val effectivelyActive = p.isEffectivelyActive
        val canPlay = p.canPlay && pageCanPlay()
        val becameActive = !policyInitialized || !lastEffectivelyActive
        policyInitialized = true
        if (!canPlay) {
            if (c.playIntent || c.isPlaying) {
                // 只因前后台 / 页面被盖住而停：记住，回来再恢复
                // forcePaused（登录弹层盖住当前视频）同样在解除后恢复
                if (effectivelyActive || pausedOnlyByForce()) wasPlayingBeforeHidden = true
                c.pause()
            }
        } else if (wasPlayingBeforeHidden) {
            wasPlayingBeforeHidden = false
            if (attr.resumeAfterLifecycle || becameActive) c.play()
        } else if (attr.autoPlay && (becameActive || !autoPlayed)) {
            autoPlayed = true
            c.play()
        }
        if (!effectivelyActive && !pausedOnlyByForce()) {
            // 失活（滑走 / forcePaused / 门闩）：重新激活时按 autoPlay 处理，不按「之前在播」恢复
            wasPlayingBeforeHidden = false
            autoPlayed = false
        }
        lastEffectivelyActive = effectivelyActive
    }

    // ------------------------------------------------------------------ 原生视图回调

    private fun onVideoMounted(v: SDVideoView) {
        val c = current ?: return
        val old = mountedView
        val oldCtrl = mountedCtrl
        mountedView = v
        mountedCtrl = c
        // 构建期间不改响应式状态：下一拍再把视图交给控制器
        setTimeout(pagerId, 0) {
            if (!alive || mountedView !== v || mountedCtrl !== c) return@setTimeout
            if (old != null && old !== v) oldCtrl?.detachView(old)
            if (attr.seekOnReadyMs >= 0L) c.pendingSeekMs = attr.seekOnReadyMs
            val p = currentPolicy()
            if (attr.autoPlay && p.canPlay && pageCanPlay() && !c.playIntent && !c.isCompleted) {
                // 自动播放：先置 playIntent，attachView 里直接起播
                c.play()
            }
            c.attachView(v)
        }
    }

    internal fun onMountChanged(mounted: Boolean) {
        if (mounted) return
        val v = mountedView ?: return
        val c = mountedCtrl
        c?.let { event.controllerReleased?.invoke(it) }
        c?.detachCurrentView()
        mountedView = null
        mountedCtrl = null
        coverRevealed = false
    }

    // ------------------------------------------------------------------ 封面

    private fun syncCoverReveal() {
        val c = current
        if (c == null || !c.hasDecoder) {
            revealTimer?.let { clearTimeout(it) }
            revealTimer = null
            coverRevealed = false
            return
        }
        if (coverRevealed || revealTimer != null) return
        if (!c.isPlaying) return
        val gen = c.generation
        val delay = if (c.firstFrameRendered) attr.coverRevealSettleMs else attr.coverRevealTimeoutMs
        revealTimer = setTimeout(pagerId, delay) {
            revealTimer = null
            if (!alive) return@setTimeout
            val cur = current ?: return@setTimeout
            if (cur.generation != gen || !cur.hasDecoder) return@setTimeout
            if (cur.firstFrameRendered || delay == attr.coverRevealTimeoutMs) {
                coverRevealed = true
            } else {
                syncCoverReveal()
            }
        }
    }

    // ------------------------------------------------------------------ 重试

    private fun scheduleAutoRetry(error: String) {
        val c = current ?: return
        // 外部控制器：页面自己的 onRetry 优先
        val strategy = attr.retryStrategy
            ?: (if (attr.profile === AppVideoPlaybackProfile.default) {
                AppVideoLinearRetryStrategy(attr.maxInitializationRetries, attr.retryBaseDelayMs)
            } else {
                attr.profile.retryStrategy
            })
        val delay = strategy.nextDelayMs(retryIndex, error)
        if (delay == null) {
            retrying = false
            event.error?.invoke(error)
            return
        }
        retryIndex++
        retrying = true
        val gen = c.generation
        retryTimer = setTimeout(pagerId, delay) {
            retryTimer = null
            if (!alive || current !== c || c.generation != gen) return@setTimeout
            c.retry()
        }
    }

    /** 手动重试（错误层按钮）：onRetry 优先，否则重建解码器。 */
    fun retry() {
        val custom = event.retry
        if (custom != null) {
            custom()
            return
        }
        val c = current ?: return
        retryIndex = 0
        retrying = false
        coverRevealed = false
        c.retry()
        if (attr.autoPlay || c.playIntent) c.play()
    }

    // ------------------------------------------------------------------ 手势 / 控制层

    private fun hasControls(): Boolean =
        attr.controlsMode != AppVideoControlsMode.none && attr.displayMode != AppVideoDisplayMode.background

    internal fun showControls() {
        if (!hasControls()) return
        controlsVisible = true
        scheduleHideControls()
    }

    private fun scheduleHideControls() {
        cancelHideControls()
        if (!attr.autoHideControls || !controlsVisible) return
        val c = current ?: return
        if (!c.isPlaying) return
        hideTimer = setTimeout(pagerId, attr.controlsHideDelayMs) {
            hideTimer = null
            if (!alive) return@setTimeout
            if (current?.isPlaying == true && !speedMenuOpen && !scrubbing) controlsVisible = false
        }
    }

    private fun cancelHideControls() {
        hideTimer?.let { clearTimeout(it) }
        hideTimer = null
    }

    internal var scrubbing = false
        set(value) {
            field = value
            if (value) cancelHideControls() else scheduleHideControls()
        }

    internal fun handleTap() {
        if (attr.displayMode == AppVideoDisplayMode.background) return
        if (speedMenuOpen) {
            speedMenuOpen = false
            return
        }
        val tap = event.tap
        if (tap != null) {
            tap()
            showControls()
            return
        }
        val c = current ?: return
        if (attr.tapToTogglePlayback) {
            if (c.hasError) return
            c.togglePlayback()
            if (hasControls()) {
                controlsVisible = true
                scheduleHideControls()
            }
        } else if (hasControls()) {
            controlsVisible = !controlsVisible
            if (controlsVisible) scheduleHideControls()
        }
    }

    internal fun handleDoubleTap() {
        if (attr.displayMode == AppVideoDisplayMode.background) return
        val dt = event.doubleTap
        if (dt != null) {
            dt()
            return
        }
        current?.seekBy(attr.seekStepMs)
        showControls()
    }

    internal fun toggleMute() {
        val c = current ?: return
        if (c.muted || c.volume <= 0f) {
            // 取消静音回到 volume（为 0 时回到 1）
            if (c.volume <= 0f) c.setVolume(if (attr.volume > 0f) attr.volume else 1f)
            c.setMuted(false)
        } else {
            c.setMuted(true)
        }
        showControls()
    }

    internal fun selectSpeed(s: Float) {
        current?.setPlaybackSpeed(s)
        speedMenuOpen = false
        showControls()
    }

    // ------------------------------------------------------------------ 渲染

    private fun boxHeightFor(width: Float): Float {
        val c = current
        val ratio = when {
            attr.aspectRatio > 0f -> attr.aspectRatio
            c != null && c.aspectRatio > 0f -> c.aspectRatio
            else -> 16f / 9f
        }
        return width / ratio
    }

    private val statusEnabled: Boolean get() = attr.showStatusOverlays

    override fun body(): ViewBuilder {
        val ctx = this
        val a = attr
        val sizeByRatio = a.sizeByAspectRatio &&
            (a.displayMode == AppVideoDisplayMode.inlineBox || a.displayMode == AppVideoDisplayMode.thumbnail)
        return {
            attr {
                backgroundColor(a.backgroundColor)
                overflow(true)
                if (a.radius > 0f) borderRadius(a.radius)
            }
            attr {
                if (sizeByRatio && ctx.boxWidth > 0f) height(ctx.boxHeightFor(ctx.boxWidth))
            }
            event {
                click { ctx.handleTap() }
                if (a.enableDoubleTap && a.displayMode != AppVideoDisplayMode.background) {
                    doubleClick { ctx.handleDoubleTap() }
                }
            }

            if (sizeByRatio) {
                // 量宽度（ComposeView 根节点没有 layoutFrameDidChange）
                View {
                    attr {
                        absolutePosition(top = 0f, left = 0f, right = 0f)
                        height(0f)
                        touchEnable(false)
                    }
                    event { layoutFrameDidChange { f -> if (f.width != ctx.boxWidth) ctx.boxWidth = f.width } }
                }
            }

            // 视频画面：只在允许初始化时挂载原生视图；代际 / 控制器变化时重建
            vbind({
                val c = ctx.current
                "${c?.hashCode()}|${c?.generation}|${ctx.shouldMount()}"
            }) {
                vif({ ctx.shouldMount() }) {
                    SDVideo {
                        val video = this
                        val c = ctx.current
                        attr {
                            absolutePositionAllZero()
                            touchEnable(false)
                            mixWithOthers(c?.mixWithOthers ?: true)
                            progressIntervalMs(a.progressIntervalMs)
                            autoPlay(false)
                        }
                        attr {
                            val cc = ctx.current
                            if (cc != null) {
                                src(cc.url)
                                loop(cc.looping)
                                muted(cc.muted || cc.volume <= 0f)
                                volume(cc.volume)
                                speed(cc.speed)
                            }
                            when (a.fit) {
                                SDFit.COVER -> fitCover()
                                SDFit.FILL -> fitFill()
                                SDFit.CONTAIN -> fitContain()
                            }
                        }
                        event {
                            onState { st -> ctx.current?.handleState(st, video) }
                            onProgress { p -> ctx.current?.handleProgress(p, video) }
                            onFirstFrame { ctx.current?.handleFirstFrame(video) }
                        }
                        ctx.onVideoMounted(video)
                    }
                }
            }

            // 封面（首帧上屏后淡出）
            if (a.showCover) {
                View {
                    attr {
                        absolutePositionAllZero()
                        touchEnable(false)
                        opacity(if (ctx.coverRevealed) 0f else 1f)
                        animate(Animation.easeOut(a.coverFadeMs / 1000f), ctx.coverRevealed)
                    }
                    val custom = a.cover
                    if (custom != null) {
                        custom()
                    } else {
                        vif({ a.coverUrl.isNotEmpty() }) {
                            SDImage {
                                attr {
                                    absolutePositionAllZero()
                                    url = a.coverUrl
                                    fit = a.fit
                                    placeholderColor = AppVideoColors.surface
                                    shimmer = false
                                }
                            }
                        }
                    }
                }
            }

            // 进度条
            vif({
                a.showProgress && a.displayMode != AppVideoDisplayMode.background &&
                    (ctx.current?.isInitialized ?: false) && ctx.current?.hasError != true
            }) {
                View {
                    attr {
                        val pd = a.progressPadding
                        absolutePosition(left = pd[1], right = pd[3], bottom = pd[2])
                    }
                    SDAppVideoProgressBar {
                        attr {
                            controller = ctx.current
                            barHeight = a.progressHeight
                            allowScrubbing = a.allowScrubbing
                            showScrubTime = a.showScrubTime
                            progressLabel = a.labels.progress
                        }
                        event {
                            onScrubStart { ctx.scrubbing = true }
                            onScrubEnd { ctx.scrubbing = false }
                        }
                    }
                }
            }

            // 业务叠加层
            val overlay = a.overlayBuilder
            if (overlay != null) {
                vbind({ ctx.current?.hashCode() ?: 0 }) {
                    val c = ctx.current
                    if (c != null) {
                        View {
                            attr { absolutePositionAllZero() }
                            overlay(c)
                        }
                    }
                }
            }

            // 状态层：初始加载 / 缓冲
            vif({
                val c = ctx.current
                ctx.statusEnabled && c != null && ctx.errorMessage().isEmpty() && c.url.isNotEmpty() && ctx.shouldMount() &&
                    ((!c.isInitialized && a.showLoading) || (c.isInitialized && c.isBuffering && !c.isPlaying && c.playIntent && a.showBufferingIndicator) || ctx.retrying)
            }) {
                View {
                    attr {
                        absolutePositionAllZero()
                        allCenter()
                        touchEnable(false)
                    }
                    SDLoading { attr { size = 40f } }
                }
            }

            // 状态层：错误
            vif({ ctx.statusEnabled && ctx.errorMessage().isNotEmpty() && !ctx.retrying }) {
                View {
                    attr {
                        absolutePositionAllZero()
                        allCenter()
                    }
                    val custom = a.errorBuilder
                    if (custom != null) {
                        vbind({ ctx.errorMessage() }) { custom(ctx.errorMessage()) { ctx.retry() } }
                    } else {
                        View {
                            attr {
                                flexDirectionRow()
                                alignItemsCenter()
                                height(40f)
                                padding(0f, 16f, 0f, 24f)
                                borderRadius(20f)
                                backgroundColor(AppTheme.primary)
                            }
                            event { click { ctx.retry() } }
                            VideoIcon(VideoIconKind.REFRESH, 18f, Color.WHITE)
                            View { attr { width(8f) } }
                            Text {
                                attr {
                                    text(a.labels.retry)
                                    fontSize(14f)
                                    fontWeightMedium()
                                    color(Color.WHITE)
                                }
                            }
                        }
                    }
                }
            }

            // 居中播放按钮
            vif({
                val c = ctx.current
                ctx.statusEnabled && a.showCenterPlayButton && a.displayMode != AppVideoDisplayMode.background &&
                    c != null && c.url.isNotEmpty() && ctx.errorMessage().isEmpty() &&
                    (c.isInitialized || !ctx.shouldMount()) && !c.isPlaying &&
                    !(c.playIntent && c.hasDecoder && c.state != SDVideoState.PAUSED && c.state != SDVideoState.COMPLETED)
            }) {
                View {
                    attr {
                        absolutePositionAllZero()
                        allCenter()
                        touchEnable(false)
                    }
                    View {
                        attr {
                            size(64f, 64f)
                            borderRadius(32f)
                            backgroundColor(Color(0, 0, 0, 0.42f))
                            allCenter()
                        }
                        VideoIcon(VideoIconKind.PLAY, 48f, Color.WHITE)
                    }
                }
            }

            // 控制条
            if (ctx.hasControls()) {
                vif({ ctx.controlsVisible && (ctx.current?.isInitialized ?: false) && ctx.errorMessage().isEmpty() }) {
                    controlsBar(ctx, a)
                }
            }
        }
    }

    /** 错误文案：业务错误 > 运行时错误。 */
    internal fun errorMessage(): String {
        val business = attr.error
        if (!business.isNullOrEmpty()) return business
        val c = current ?: return ""
        return if (c.hasError) c.errorDescription.ifEmpty { attr.labels.errorMessage } else ""
    }

    internal fun hasFullscreen(): Boolean = event.fullscreen != null
    internal fun fireFullscreen() { event.fullscreen?.invoke() }
    internal fun basePagerSafeBottom(): Float =
        if (attr.displayMode == AppVideoDisplayMode.immersive) (basePager?.safeBottom ?: 0f) else 0f

    internal val speedMenuShown: Boolean get() = speedMenuOpen
    internal fun toggleSpeedMenu() {
        speedMenuOpen = !speedMenuOpen
        if (speedMenuOpen) cancelHideControls() else scheduleHideControls()
    }
}

/** 控制条：底部、外边距 8/0/8/36、内边距 h4 v2、黑 54%、圆角 12；按钮同 Dart IconButton 48×48（倍速 12 内边距 + 22 = 46）。 */
private fun ViewContainer<*, *>.controlsBar(ctx: SDAppVideoPlayerView, a: SDAppVideoPlayerAttr) {
    View {
        attr {
            absolutePosition(left = 8f, right = 8f, bottom = 36f + (ctx.basePagerSafeBottom()))
        }
        // 倍速菜单（在条上方）
        vif({ ctx.speedMenuShown }) {
            View {
                attr {
                    absolutePosition(right = 40f, bottom = 52f)
                    backgroundColor(AppVideoColors.surface)
                    borderRadius(8f)
                    padding(4f, 0f, 4f, 0f)
                }
                for (s in a.playbackSpeeds) {
                    View {
                        attr {
                            height(36f)
                            padding(0f, 20f, 0f, 20f)
                            justifyContentCenter()
                        }
                        event { click { ctx.selectSpeed(s) } }
                        Text {
                            attr {
                                text("${formatSpeed(s)}x")
                                fontSize(14f)
                                color(if (ctx.controller?.speed == s) AppVideoColors.accent else Color.WHITE)
                            }
                        }
                    }
                }
            }
        }
        View {
            attr {
                flexDirectionRow()
                alignItemsCenter()
                padding(2f, 4f, 2f, 4f)
                backgroundColor(Color(0, 0, 0, 0.54f))
                borderRadius(12f)
            }
            event { click { ctx.showControls() } }
            // 播放 / 暂停
            View {
                attr {
                    size(48f, 48f)
                    allCenter()
                }
                event { click { ctx.controller?.togglePlayback(); ctx.showControls() } }
                vif({ ctx.controller?.isPlaying == true }) { VideoIcon(VideoIconKind.PAUSE, 24f) }
                velse { VideoIcon(VideoIconKind.PLAY, 24f) }
            }
            if (a.controlsMode == AppVideoControlsMode.full) {
                Text {
                    attr {
                        val c = ctx.controller
                        text("${formatVideoDuration(c?.positionMs ?: 0L)} / ${formatVideoDuration(c?.durationMs ?: 0L)}")
                        fontSize(11f)
                        color(Color.WHITE)
                    }
                }
            }
            View { attr { flex(1f) } }
            if (a.controlsMode == AppVideoControlsMode.full) {
                View {
                    attr {
                        size(48f, 48f)
                        allCenter()
                    }
                    event { click { ctx.toggleMute() } }
                    vif({ ctx.controller?.let { it.muted || it.volume <= 0f } == true }) {
                        VideoIcon(VideoIconKind.VOLUME_OFF, 24f)
                    }
                    velse { VideoIcon(VideoIconKind.VOLUME_UP, 24f) }
                }
                View {
                    attr {
                        size(46f, 46f)
                        allCenter()
                    }
                    event { click { ctx.toggleSpeedMenu() } }
                    VideoIcon(VideoIconKind.SPEED, 22f)
                }
            }
            if (ctx.hasFullscreen()) {
                View {
                    attr {
                        size(48f, 48f)
                        allCenter()
                    }
                    event { click { ctx.fireFullscreen() } }
                    VideoIcon(VideoIconKind.FULLSCREEN, 24f)
                }
            }
        }
    }
}

private fun formatSpeed(s: Float): String {
    val i = s.toInt()
    return if (s == i.toFloat()) "$i" else s.toString()
}


/** 通用播放器（Dart `AppVideoPlayer`）。 */
fun ViewContainer<*, *>.SDAppVideoPlayer(init: SDAppVideoPlayerView.() -> Unit) {
    addChild(SDAppVideoPlayerView(), init)
}

/** Dart `FeedVideoPlayer`：信息流 / 列表默认值（见 [SDAppVideoPlayerAttr.feedDefaults]），其余同 [SDAppVideoPlayer]。 */
fun ViewContainer<*, *>.SDFeedVideoPlayer(init: SDAppVideoPlayerView.() -> Unit) {
    addChild(SDAppVideoPlayerView()) {
        attr { feedDefaults() }
        init()
    }
}

/** Dart `FeedVideoPlayer.banner`：静音循环背景（见 [SDAppVideoPlayerAttr.bannerDefaults]）。 */
fun ViewContainer<*, *>.SDFeedBannerVideoPlayer(init: SDAppVideoPlayerView.() -> Unit) {
    addChild(SDAppVideoPlayerView()) {
        attr { bannerDefaults() }
        init()
    }
}

/** Dart `MvVideoPlayer`：MV 播放页默认值（独占音频，见 [SDAppVideoPlayerAttr.mvDefaults]）。 */
fun ViewContainer<*, *>.SDMvVideoPlayer(init: SDAppVideoPlayerView.() -> Unit) {
    addChild(SDAppVideoPlayerView()) {
        attr { mvDefaults() }
        init()
    }
}
