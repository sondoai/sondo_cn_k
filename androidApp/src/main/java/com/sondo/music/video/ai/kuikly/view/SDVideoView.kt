package com.sondo.music.video.ai.kuikly.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.sondo.music.video.ai.kuikly.module.SDAudioSession
import com.sondo.music.video.ai.kuikly.module.SDMediaKit
import com.sondo.music.video.ai.kuikly.module.SDVideoCache
import com.tencent.kuikly.core.render.android.export.IKuiklyRenderViewExport
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import java.io.File

/**
 * 原生视频播放视图（Media3 ExoPlayer）。属性 / 事件 / 方法见 shared 侧 `SDNativeViews.kt` 的 SDVideoView。
 *
 * 生命周期：
 * - 每个视图独占一个 ExoPlayer，[onDestroy]（Kuikly 移除视图 / 页面销毁）时释放；
 * - 视图从窗口上摘下来（且没有马上挂回去）时也会释放播放器，挂回来再按原位置恢复，保证列表里不泄漏；
 * - 宿主 Activity 进入 stopped（被完全盖住 / 应用退后台）时自动暂停，回到 started 时恢复。
 *
 * 音频互斥（与参考工程一致）：
 * - 「有声」= 未静音且音量 > 0；
 * - mixWithOthers=0 且有声的视频开始播放时：申请系统音频焦点，暂停全局音频播放器和其它独占的有声视频；
 *   反过来全局音频播放器开始播放时，这类视频会被暂停；
 * - mixWithOthers=1 或静音的视频不参与互斥，也不申请焦点；
 * - 持有焦点期间被系统打断：duck → 音量降到 20%，结束后恢复；暂时性打断 → 暂停，结束后自动续播；
 *   永久失去焦点 → 暂停；拔出耳机 → 有声的视频暂停。
 */
@OptIn(markerClass = [UnstableApi::class])
class SDVideoView(context: Context) : FrameLayout(context), IKuiklyRenderViewExport {

    // ---- 属性 ----
    private var src = ""
    private var autoPlay = false
    private var loop = false
    private var muted = false
    private var volume = 1f
    private var speed = 1f
    private var fit = FIT_CONTAIN
    private var mixWithOthers = false
    private var useCache = true
    private var progressIntervalMs = 250L

    // ---- 事件 ----
    private var stateCallback: KuiklyRenderCallback? = null
    private var progressCallback: KuiklyRenderCallback? = null
    private var firstFrameCallback: KuiklyRenderCallback? = null

    // ---- 运行时 ----
    private var player: ExoPlayer? = null
    private var renderView: View? = null
    private var destroyed = false
    private var explicitlyReleased = false
    private var suspendedByDetach = false
    private var playIntent = false
    private var resumePositionMs = 0L
    private var hasBeenReady = false
    private var hasStarted = false
    private var firstFrameSent = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var lastError: String? = null
    private var pausedByLifecycle = false
    private var resumeAfterInterruption = false
    private var ducked = false
    private var holdingOutput = false
    private var tickerRunning = false
    private var lastEmitted: String? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var reloadPending = false
    private var playRequestedBeforeLoad = false

    private val reloadRunnable = Runnable { reload() }
    private val detachReleaseRunnable = Runnable { suspendForDetach() }

    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            if (p == null || !p.isPlaying) {
                tickerRunning = false
                return
            }
            emitProgress()
            SDMediaKit.mainHandler.postDelayed(this, progressIntervalMs)
        }
    }

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_STOP -> onHostStopped()
            Lifecycle.Event.ON_START -> onHostStarted()
            Lifecycle.Event.ON_DESTROY -> destroy()
            else -> Unit
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY && !hasBeenReady) {
                hasBeenReady = true
                lastError = null
                // 首次就绪先报一次 ready（带时长 / 尺寸），再按播放意图报 playing
                emitState(STATE_READY)
            }
            syncState()
            if (playbackState == Player.STATE_ENDED) emitProgress()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            syncState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startTicker() else emitProgress()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            videoWidth = Math.round(videoSize.width * videoSize.pixelWidthHeightRatio)
            videoHeight = videoSize.height
            applyFit()
            syncState()
        }

        override fun onRenderedFirstFrame() {
            if (firstFrameSent) return
            firstFrameSent = true
            fire(firstFrameCallback, emptyMap())
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            emitProgress()
        }

        override fun onPlayerError(error: PlaybackException) {
            val msg = "${error.errorCodeName}: ${error.message ?: ""}".trim()
            Log.w(TAG, "player error: $msg")
            lastError = msg
            resumeAfterInterruption = false
            syncState()
        }
    }

    init {
        setWillNotDraw(false)
        clipChildren = true
    }

    // ---------------------------------------------------------------------------------
    // IKuiklyRenderViewExport
    // ---------------------------------------------------------------------------------

    override fun setProp(propKey: String, propValue: Any): Boolean {
        when (propKey) {
            "src" -> {
                val value = (propValue as? String)?.trim().orEmpty()
                if (value != src) {
                    src = value
                    scheduleReload()
                }
            }
            "autoPlay" -> {
                val value = SDMediaKit.propFlag(propValue)
                if (value != autoPlay) {
                    autoPlay = value
                    // 源已加载但还没开始播时切到 autoPlay，等同于调用 play
                    if (value && player != null && !hasStarted && !playIntent && !reloadPending) doPlay()
                }
            }
            "loop" -> {
                loop = SDMediaKit.propFlag(propValue)
                player?.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            }
            "muted" -> {
                muted = SDMediaKit.propFlag(propValue)
                applyVolume()
                updateOutputHold()
            }
            "volume" -> {
                volume = SDMediaKit.propFloat(propValue, 1f).coerceIn(0f, 1f)
                applyVolume()
                updateOutputHold()
            }
            "speed" -> {
                speed = SDMediaKit.propFloat(propValue, 1f).coerceIn(0.25f, 4f)
                player?.setPlaybackSpeed(speed)
            }
            "fit" -> {
                fit = when ((propValue as? String)?.lowercase()) {
                    FIT_COVER -> FIT_COVER
                    FIT_FILL -> FIT_FILL
                    else -> FIT_CONTAIN
                }
                applyFit()
            }
            "mixWithOthers" -> {
                mixWithOthers = SDMediaKit.propFlag(propValue)
                updateOutputHold()
            }
            "cache" -> {
                // 只影响之后的加载；已在播放的源不重建
                useCache = SDMediaKit.propFlag(propValue, true)
            }
            "progressIntervalMs" -> {
                progressIntervalMs = SDMediaKit.propInt(propValue, 250).coerceIn(50, 5_000).toLong()
            }
            EVENT_STATE -> {
                stateCallback = SDMediaKit.propCallback(propValue)
                lastEmitted = null
                if (!reloadPending) syncState()
            }
            EVENT_PROGRESS -> progressCallback = SDMediaKit.propCallback(propValue)
            EVENT_FIRST_FRAME -> {
                firstFrameCallback = SDMediaKit.propCallback(propValue)
                if (firstFrameSent) fire(firstFrameCallback, emptyMap())
            }
            else -> return super.setProp(propKey, propValue)
        }
        return true
    }

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        when (method) {
            "play" -> doPlay()
            "pause" -> doPause()
            "stop" -> doStop()
            "seekTo" -> doSeek(SDMediaKit.parse(params).optLong("positionMs", 0L))
            "release" -> {
                explicitlyReleased = true
                playIntent = false
                releasePlayer(removeRenderView = true)
                resetLoadState()
                syncState()
            }
            else -> return super.call(method, params, callback)
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        destroy()
    }

    override fun draw(canvas: Canvas) {
        val checkpoint = if (hasCustomClipPath()) canvas.save() else -1
        drawCommonDecoration(width, height, canvas)
        super.draw(canvas)
        if (checkpoint != -1) canvas.restoreToCount(checkpoint)
        drawCommonForegroundDecoration(width, height, canvas)
    }

    // ---------------------------------------------------------------------------------
    // View 生命周期
    // ---------------------------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        SDMediaKit.mainHandler.removeCallbacks(detachReleaseRunnable)
        if (destroyed) return
        if (lifecycleOwner == null) {
            val owner = activity as? LifecycleOwner
            if (owner != null) {
                lifecycleOwner = owner
                try {
                    owner.lifecycle.addObserver(lifecycleObserver)
                } catch (e: Throwable) {
                    lifecycleOwner = null
                }
            }
        }
        if (suspendedByDetach) {
            suspendedByDetach = false
            if (src.isNotEmpty() && !explicitlyReleased && player == null) {
                createPlayer()
                syncState()
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (destroyed || player == null) return
        // 重新挂载（换父节点）是「先摘后挂」，稍等一下再释放，避免无谓的重建
        SDMediaKit.mainHandler.removeCallbacks(detachReleaseRunnable)
        SDMediaKit.mainHandler.postDelayed(detachReleaseRunnable, DETACH_RELEASE_DELAY_MS)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyFit()
    }

    private fun suspendForDetach() {
        if (destroyed || isAttachedToWindow) return
        val p = player ?: return
        resumePositionMs = p.currentPosition.coerceAtLeast(0L)
        suspendedByDetach = true
        releasePlayer(removeRenderView = false)
    }

    private fun isHostStarted(): Boolean {
        val owner = lifecycleOwner ?: return true
        return owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    private fun onHostStopped() {
        val p = player ?: return
        if (p.playWhenReady) {
            pausedByLifecycle = true
            p.playWhenReady = false
        }
    }

    private fun onHostStarted() {
        if (!pausedByLifecycle) return
        pausedByLifecycle = false
        if (!playIntent || destroyed) return
        // 离开期间别的播放器已经在出声（例如在新页面里放了歌）：不抢回来，保持暂停
        if (!mixWithOthers && isAudible() && SDAudioSession.isOtherAudiblyPlaying(audioPeer)) {
            playIntent = false
            syncState()
            return
        }
        doPlay()
    }

    private fun destroy() {
        if (destroyed) return
        destroyed = true
        SDMediaKit.mainHandler.removeCallbacks(reloadRunnable)
        SDMediaKit.mainHandler.removeCallbacks(detachReleaseRunnable)
        releasePlayer(removeRenderView = true)
        lifecycleOwner?.let {
            try {
                it.lifecycle.removeObserver(lifecycleObserver)
            } catch (e: Throwable) {
                // ignore
            }
        }
        lifecycleOwner = null
        stateCallback = null
        progressCallback = null
        firstFrameCallback = null
    }

    // ---------------------------------------------------------------------------------
    // 加载 / 播放控制
    // ---------------------------------------------------------------------------------

    private fun scheduleReload() {
        // 同一批属性（src / autoPlay / loop …）设置完之后再统一加载
        reloadPending = true
        SDMediaKit.mainHandler.removeCallbacks(reloadRunnable)
        SDMediaKit.mainHandler.post(reloadRunnable)
    }

    private fun reload() {
        reloadPending = false
        val playRequested = playRequestedBeforeLoad
        playRequestedBeforeLoad = false
        if (destroyed) return
        releasePlayer(removeRenderView = true)
        resetLoadState()
        explicitlyReleased = false
        suspendedByDetach = false
        pausedByLifecycle = false
        if (src.isEmpty()) {
            playIntent = false
            syncState()
            return
        }
        playIntent = autoPlay || playRequested
        // 宿主页面此刻不可见（被盖住 / 在后台）：先不出声，回到前台再播
        pausedByLifecycle = playIntent && !isHostStarted()
        // 提前在后台探测体积，播放器真正发起请求时大概率已有结果
        if (useCache) SDVideoCache.warmUp(src)
        createPlayer()
        syncState()
    }

    private fun resetLoadState() {
        hasBeenReady = false
        hasStarted = false
        firstFrameSent = false
        videoWidth = 0
        videoHeight = 0
        lastError = null
        resumePositionMs = 0L
    }

    private fun createPlayer() {
        if (destroyed || player != null) return
        val uri = resolveUri(src)
        if (uri == null) {
            lastError = "invalid_src"
            return
        }
        val app = context.applicationContext
        try {
            val scheme = uri.scheme?.lowercase()
            val cacheable = useCache && (scheme == "http" || scheme == "https")
            val loadControl = DefaultLoadControl.Builder()
                // 列表里会同时存在多个实例：缩小缓冲区，起播门槛降到 1s
                .setBufferDurationsMs(10_000, 30_000, 1_000, 2_500)
                .build()
            val p = ExoPlayer.Builder(app)
                .setLooper(Looper.getMainLooper())
                .setMediaSourceFactory(DefaultMediaSourceFactory(SDVideoCache.playbackFactory(app, cacheable)))
                .setLoadControl(loadControl)
                .setHandleAudioBecomingNoisy(false)
                .build()
            // 焦点由 SDAudioSession 统一管理（只有独占且有声的视频才申请）
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                false
            )
            p.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            p.setPlaybackSpeed(speed)
            player = p
            applyVolume()
            attachRenderView(p)
            applyFit()
            SDAudioSession.addClient(app, audioPeer)
            SDAudioSession.attachOutput(audioPeer)

            p.setMediaItem(MediaItem.fromUri(uri))
            if (resumePositionMs > 0) p.seekTo(resumePositionMs)
            val shouldPlay = playIntent && !pausedByLifecycle
            if (shouldPlay) claimOutputIfNeeded()
            p.playWhenReady = shouldPlay
            // 初始化动作（seek / playWhenReady）产生的中间事件不需要上报，监听器在 prepare 之前才挂上
            p.addListener(playerListener)
            p.prepare()
        } catch (e: Throwable) {
            Log.w(TAG, "create player failed", e)
            lastError = SDMediaKit.errorText(e)
            releasePlayer(removeRenderView = true)
        }
    }

    private fun releasePlayer(removeRenderView: Boolean) {
        SDMediaKit.mainHandler.removeCallbacks(ticker)
        tickerRunning = false
        val p = player
        player = null
        if (p != null) {
            try {
                p.removeListener(playerListener)
                p.clearVideoSurface()
                p.release()
            } catch (e: Throwable) {
                Log.w(TAG, "release failed", e)
            }
        }
        SDAudioSession.detachOutput(audioPeer)
        SDAudioSession.removeClient(audioPeer)
        SDAudioSession.releaseFocus(audioPeer)
        holdingOutput = false
        ducked = false
        resumeAfterInterruption = false
        if (removeRenderView) {
            renderView?.let { removeView(it) }
            renderView = null
        }
    }

    private fun doPlay() {
        if (destroyed || src.isEmpty()) return
        if (reloadPending) {
            // src 刚变更、还没加载：记下播放请求，加载时生效
            playRequestedBeforeLoad = true
            return
        }
        playIntent = true
        pausedByLifecycle = false
        resumeAfterInterruption = false
        val p = player
        if (p == null) {
            // release() 之后或因摘除而挂起：重新创建
            explicitlyReleased = false
            if (suspendedByDetach && !isAttachedToWindow) return
            suspendedByDetach = false
            createPlayer()
            syncState()
            return
        }
        claimOutputIfNeeded()
        // 先置播放意图再 prepare / seek，避免中途报出一次 paused
        p.playWhenReady = true
        when (p.playbackState) {
            Player.STATE_IDLE -> {
                // stop() 或出错之后：重新准备
                lastError = null
                p.prepare()
            }
            Player.STATE_ENDED -> p.seekTo(0L)
            else -> Unit
        }
        syncState()
    }

    private fun doPause() {
        playRequestedBeforeLoad = false
        playIntent = false
        pausedByLifecycle = false
        resumeAfterInterruption = false
        player?.playWhenReady = false
        syncState()
    }

    private fun doStop() {
        playRequestedBeforeLoad = false
        playIntent = false
        pausedByLifecycle = false
        resumeAfterInterruption = false
        resumePositionMs = 0L
        val p = player ?: return
        p.stop()
        p.playWhenReady = false
        p.seekTo(0L)
        hasStarted = false
        lastError = null
        syncState()
        emitProgress()
    }

    private fun doSeek(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        val p = player
        if (p == null) {
            resumePositionMs = target
            return
        }
        p.seekTo(target)
        emitProgress()
    }

    // ---------------------------------------------------------------------------------
    // 音频互斥 / 焦点
    // ---------------------------------------------------------------------------------

    private fun isAudible(): Boolean = !muted && volume > 0f

    private fun applyVolume() {
        val base = if (muted) 0f else volume
        player?.volume = if (ducked) base * DUCK_FACTOR else base
    }

    /** 独占且有声：出声之前先拿焦点并暂停其它有声播放器。 */
    private fun claimOutputIfNeeded() {
        if (mixWithOthers || !isAudible() || holdingOutput) return
        holdingOutput = true
        SDAudioSession.acquireFocus(context, audioPeer)
        SDAudioSession.claimOutput(audioPeer)
    }

    private fun updateOutputHold() {
        val p = player
        val active = p != null && p.playWhenReady &&
            (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)
        val wants = active && !mixWithOthers && isAudible()
        if (wants) {
            claimOutputIfNeeded()
        } else if (holdingOutput && !resumeAfterInterruption) {
            holdingOutput = false
            if (ducked) {
                ducked = false
                applyVolume()
            }
            SDAudioSession.releaseFocus(audioPeer)
        }
    }

    private fun isAudiblyPlaying(): Boolean {
        val p = player ?: return false
        if (mixWithOthers || !isAudible() || !p.playWhenReady) return false
        return p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING
    }

    /** 系统打断 / 耳机拔出 / 应用内互斥的回调都转到这里（SDAudioSession 的接口是 internal，不直接由视图实现）。 */
    private val audioPeer = object : SDAudioSession.Client, SDAudioSession.OutputParticipant {
        override fun isAudiblyPlaying(): Boolean = this@SDVideoView.isAudiblyPlaying()
        override fun pauseForHandoff() = doPause()
        override fun onInterruption(begin: Boolean, type: String) = handleInterruption(begin, type)
        override fun onBecomingNoisy() = handleBecomingNoisy()
    }

    private fun handleInterruption(begin: Boolean, type: String) {
        if (!holdingOutput) return
        val p = player ?: return
        if (begin) {
            if (type == SDAudioSession.TYPE_DUCK) {
                ducked = true
                applyVolume()
                return
            }
            if (p.playWhenReady) {
                // 暂时性打断结束后自动续播；永久失去焦点则只暂停
                resumeAfterInterruption = type == SDAudioSession.TYPE_PAUSE
                if (!resumeAfterInterruption) playIntent = false
                p.playWhenReady = false
            }
            return
        }
        if (ducked) {
            ducked = false
            applyVolume()
        }
        if (type == SDAudioSession.TYPE_PAUSE && resumeAfterInterruption) {
            resumeAfterInterruption = false
            if (playIntent && !pausedByLifecycle) {
                p.playWhenReady = true
            }
        }
        syncState()
    }

    private fun handleBecomingNoisy() {
        val p = player ?: return
        if (isAudible() && p.playWhenReady) doPause()
    }

    // ---------------------------------------------------------------------------------
    // 渲染表面 / 缩放
    // ---------------------------------------------------------------------------------

    private fun attachRenderView(p: ExoPlayer) {
        var view = renderView
        if (view == null) {
            view = if (USE_SURFACE_VIEW) SurfaceView(context) else TextureView(context)
            renderView = view
            addView(view, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        }
        when (view) {
            is SurfaceView -> p.setVideoSurfaceView(view)
            is TextureView -> p.setVideoTextureView(view)
        }
    }

    private fun applyFit() {
        val view = renderView ?: return
        val w = width
        val h = height
        when (view) {
            is TextureView -> applyTextureFit(view, w, h)
            is SurfaceView -> applySurfaceFit(view, w, h)
        }
    }

    /** TextureView 始终铺满容器，用变换矩阵实现 contain / cover / fill。 */
    private fun applyTextureFit(view: TextureView, w: Int, h: Int) {
        val matrix = Matrix()
        if (fit != FIT_FILL && w > 0 && h > 0 && videoWidth > 0 && videoHeight > 0) {
            val viewAspect = w.toFloat() / h
            val videoAspect = videoWidth.toFloat() / videoHeight
            var sx = 1f
            var sy = 1f
            val videoWider = videoAspect > viewAspect
            if (fit == FIT_CONTAIN) {
                if (videoWider) sy = viewAspect / videoAspect else sx = videoAspect / viewAspect
            } else {
                if (videoWider) sx = videoAspect / viewAspect else sy = viewAspect / videoAspect
            }
            matrix.setScale(sx, sy, w / 2f, h / 2f)
        }
        view.setTransform(matrix)
        // contain / cover 在拿到视频尺寸前没法算矩阵：这时出的帧会被拉满整个视图（开播瞬间上下闪一下），
        // 先隐藏，尺寸到了、矩阵正确后再显示。
        view.alpha = if (fit != FIT_FILL && (videoWidth <= 0 || videoHeight <= 0)) 0f else 1f
        view.invalidate()
    }

    /**
     * SurfaceView 不能做矩阵变换：contain 用按比例缩小并居中的布局，
     * cover / fill 铺满容器并交给解码器的缩放模式（裁剪 / 拉伸）。
     */
    private fun applySurfaceFit(view: SurfaceView, w: Int, h: Int) {
        var lpW = LayoutParams.MATCH_PARENT
        var lpH = LayoutParams.MATCH_PARENT
        if (fit == FIT_CONTAIN && w > 0 && h > 0 && videoWidth > 0 && videoHeight > 0) {
            val viewAspect = w.toFloat() / h
            val videoAspect = videoWidth.toFloat() / videoHeight
            if (videoAspect > viewAspect) {
                lpW = w
                lpH = Math.round(w / videoAspect).coerceAtLeast(1)
            } else {
                lpH = h
                lpW = Math.round(h * videoAspect).coerceAtLeast(1)
            }
        }
        val lp = view.layoutParams as? LayoutParams
        if (lp == null || lp.width != lpW || lp.height != lpH) {
            view.layoutParams = LayoutParams(lpW, lpH, Gravity.CENTER)
        }
        player?.videoScalingMode = if (fit == FIT_COVER) {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        } else {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        }
    }

    // ---------------------------------------------------------------------------------
    // 事件
    // ---------------------------------------------------------------------------------

    private fun computeState(): String {
        val p = player
        if (lastError != null && (p == null || p.playbackState == Player.STATE_IDLE)) return STATE_ERROR
        if (p == null) {
            // 摘除后挂起的视图保持「加载中」，其余（无源 / 已 release）为空闲
            return if (suspendedByDetach && src.isNotEmpty()) STATE_LOADING else STATE_IDLE
        }
        return when (p.playbackState) {
            Player.STATE_BUFFERING -> when {
                !hasBeenReady -> STATE_LOADING
                p.playWhenReady -> STATE_BUFFERING
                else -> STATE_PAUSED
            }
            Player.STATE_READY -> when {
                p.playWhenReady -> STATE_PLAYING
                hasStarted -> STATE_PAUSED
                else -> STATE_READY
            }
            Player.STATE_ENDED -> STATE_COMPLETED
            else -> STATE_IDLE
        }
    }

    private fun syncState() {
        if (destroyed) return
        val state = computeState()
        if (state == STATE_PLAYING) hasStarted = true
        emitState(state)
        updateOutputHold()
    }

    private fun emitState(state: String) {
        val durationMs = durationMs()
        val error = if (state == STATE_ERROR) lastError.orEmpty() else ""
        val signature = "$state|$durationMs|$videoWidth|$videoHeight|$error"
        if (signature == lastEmitted) return
        lastEmitted = signature
        val payload = HashMap<String, Any>()
        payload["state"] = state
        payload["durationMs"] = durationMs
        payload["width"] = videoWidth
        payload["height"] = videoHeight
        if (error.isNotEmpty()) payload["error"] = error
        fire(stateCallback, payload)
    }

    private fun emitProgress() {
        val p = player ?: return
        val cb = progressCallback ?: return
        fire(
            cb,
            mapOf(
                "positionMs" to p.currentPosition.coerceAtLeast(0L),
                "durationMs" to durationMs(),
                "bufferedMs" to p.bufferedPosition.coerceAtLeast(0L),
            )
        )
    }

    private fun durationMs(): Long {
        val d = player?.duration ?: C.TIME_UNSET
        return if (d == C.TIME_UNSET || d < 0) 0L else d
    }

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        SDMediaKit.mainHandler.removeCallbacks(ticker)
        SDMediaKit.mainHandler.post(ticker)
    }

    private fun fire(callback: KuiklyRenderCallback?, payload: Map<String, Any>) {
        if (callback == null || destroyed) return
        try {
            callback.invoke(payload)
        } catch (e: Throwable) {
            Log.w(TAG, "fire event failed", e)
        }
    }

    // ---------------------------------------------------------------------------------
    // 源地址解析
    // ---------------------------------------------------------------------------------

    /**
     * - http(s) / content / android asset URI：原样；
     * - 绝对路径或 file://：本地文件；
     * - `assets://xxx`：APK assets；
     * - 其它相对路径：shared 模块的公共资源（APK assets 下的 `common/xxx`，找不到再按 assets 根目录找）。
     */
    private fun resolveUri(raw: String): Uri? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (SDMediaKit.isHttp(s)) return parse(s)
        SDMediaKit.localPath(s)?.let { return Uri.fromFile(File(it)) }
        if (s.startsWith("content://") || s.startsWith("asset:///") || s.startsWith("android.resource://")) return parse(s)
        val assetPath = when {
            s.startsWith("assets://") -> s.removePrefix("assets://").trimStart('/')
            s.startsWith("asset://") -> s.removePrefix("asset://").trimStart('/')
            s.contains("://") -> return parse(s)
            else -> {
                val relative = s.trimStart('/')
                if (assetExists("common/$relative")) "common/$relative" else relative
            }
        }
        return Uri.parse("asset:///$assetPath")
    }

    private fun parse(s: String): Uri? = try {
        Uri.parse(s)
    } catch (e: Throwable) {
        null
    }

    private fun assetExists(path: String): Boolean = try {
        context.assets.open(path).close()
        true
    } catch (e: Throwable) {
        false
    }

    companion object {
        const val VIEW_NAME = "SDVideoView"
        private const val TAG = "SDVideoView"

        private const val EVENT_STATE = "sdState"
        private const val EVENT_PROGRESS = "sdProgress"
        private const val EVENT_FIRST_FRAME = "sdFirstFrame"

        private const val FIT_CONTAIN = "contain"
        private const val FIT_COVER = "cover"
        private const val FIT_FILL = "fill"

        private const val STATE_IDLE = "idle"
        private const val STATE_LOADING = "loading"
        private const val STATE_READY = "ready"
        private const val STATE_BUFFERING = "buffering"
        private const val STATE_PLAYING = "playing"
        private const val STATE_PAUSED = "paused"
        private const val STATE_COMPLETED = "completed"
        private const val STATE_ERROR = "error"

        private const val DUCK_FACTOR = 0.2f
        private const val DETACH_RELEASE_DELAY_MS = 80L

        /**
         * 海思 OMX 解码器输出到 TextureView（SurfaceTexture / ImageReader）时 setBufferCount 会 EINVAL，
         * 华为 / 荣耀 / 麒麟 / 海思机型改用 SurfaceView。判定与参考工程一致：
         * 厂商、品牌、硬件名任一包含 huawei / honor / kirin / hisi。
         */
        val USE_SURFACE_VIEW: Boolean by lazy {
            val identity = "${Build.MANUFACTURER} ${Build.BRAND} ${Build.HARDWARE}".lowercase()
            listOf("huawei", "honor", "kirin", "hisi").any { identity.contains(it) }
        }
    }
}
