package com.sondo.app.ui.video

import com.sondo.app.core.utils.nowMs
import com.sondo.app.nativeapi.SDVideoProgress
import com.sondo.app.nativeapi.SDVideoState
import com.sondo.app.nativeapi.SDVideoView
import com.sondo.app.service.audio.AppAudioOutputCoordinator
import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuikly.core.manager.PagerManager
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.handler.observable

/** 控制器事件监听（默认空实现，按需覆盖）。 */
interface AppVideoControllerListener {
    fun onStateChanged(controller: AppVideoController, state: String) {}
    fun onProgress(controller: AppVideoController, positionMs: Long, durationMs: Long) {}
    fun onFirstFrame(controller: AppVideoController) {}
    /** 非循环播放完成 */
    fun onCompleted(controller: AppVideoController) {}
    fun onError(controller: AppVideoController, error: String) {}
    /** 首次进入可播放状态（每个源一次） */
    fun onReady(controller: AppVideoController) {}
}

/**
 * Dart `VideoPlayerController` + `AbstractAppVideoPlayerState` 命令式 API 的 Kuikly 版。
 *
 * 页面持有它（`AppVideoController(this, url)`，this = Pager / ComposeView / 任何 PagerScope），
 * 交给 `SDAppVideoPlayer { attr { controller = ctx.player } }` 渲染；控制器本身不持有解码器，
 * 解码器属于当前挂载的原生 `SDVideo` 视图（视图卸载即释放，再挂载时按 [pendingSeekMs] / 续播位置恢复）。
 *
 * 所有字段都是响应式的，页面可以直接在 `attr { }` 里读 [positionMs] / [isPlaying] 等。
 * 有声播放统一经过 [AppAudioOutputCoordinator]：播放 / 取消静音前 claim，暂停 / 静音 / 释放时 release。
 */
class AppVideoController(
    scope: PagerScope,
    url: String = "",
    looping: Boolean = false,
    muted: Boolean = false,
    volume: Float = 1f,
    /** 与其它音频混播（Dart VideoPlayerOptions.mixWithOthers；只在解码器创建时生效） */
    val mixWithOthers: Boolean = true,
    /** 续播 key（null = 不续播） */
    var resumeKey: String? = null,
    var resumeStore: AppVideoResumeStore = InMemoryAppVideoResumeStore,
    /** 续播位置写入节流 */
    var resumeSaveIntervalMs: Long = 1000L,
    /** 播放中保持亮屏（Dart keepScreenOn） */
    var keepScreenOn: Boolean = true,
) : PagerScope {
    override val pagerId: String = scope.pagerId

    // ------------------------------------------------------------------ 响应式状态

    /** 当前播放源（已规整，见 [AppVideoSource.parse]）；空串 = 只显示封面 */
    var url by observable(AppVideoSource.parse(url))
        private set
    /** 原生状态：idle / loading / ready / buffering / playing / paused / completed / error */
    var state by observable(SDVideoState.IDLE)
        private set
    var positionMs by observable(0L)
        private set
    var durationMs by observable(0L)
        private set
    var bufferedMs by observable(0L)
        private set
    var videoWidth by observable(0)
        private set
    var videoHeight by observable(0)
        private set
    /** 当前解码器是否已上屏首帧 */
    var firstFrameRendered by observable(false)
        private set
    /** 运行时错误描述；空串 = 无错误 */
    var errorDescription by observable("")
        private set
    var volume by observable(volume.coerceIn(0f, 1f))
        private set
    var muted by observable(muted)
        private set
    var speed by observable(1f)
        private set
    var looping by observable(looping)
        private set
    /** 期望播放（play() 置 true，pause() / 播完置 false）；视图重新挂载时据此恢复 */
    var playIntent by observable(false)
        private set
    /** 源 / 重试代际：变化时组件重建原生视图 */
    var generation by observable(0)
        private set
    /** 是否已绑定原生视图（= 持有解码器） */
    var hasDecoder by observable(false)
        private set

    val isInitialized: Boolean
        get() = durationMs > 0L || state == SDVideoState.READY || state == SDVideoState.PLAYING ||
            state == SDVideoState.PAUSED || state == SDVideoState.COMPLETED ||
            (state == SDVideoState.BUFFERING && readyOnce)
    val isPlaying: Boolean get() = state == SDVideoState.PLAYING
    /** 加载 / 缓冲中（播放中忽略缓冲态：部分平台 seek 后会卡在 buffering） */
    val isBuffering: Boolean get() = (state == SDVideoState.BUFFERING || state == SDVideoState.LOADING) && hasDecoder
    val isCompleted: Boolean get() = state == SDVideoState.COMPLETED
    val hasError: Boolean get() = state == SDVideoState.ERROR || errorDescription.isNotEmpty()
    val isAudible: Boolean get() = !muted && volume > 0f
    /** 视频宽高比；未知时 0 */
    val aspectRatio: Float
        get() = if (videoWidth > 0 && videoHeight > 0) videoWidth.toFloat() / videoHeight.toFloat() else 0f
    /** 0..1 */
    val progress: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    // ------------------------------------------------------------------ 内部

    private var view: SDVideoView? = null
    private var readyOnce = false
    private var disposed = false
    private var claimToken = -1
    /** 准备好后要 seek 到的位置（-1 = 无） */
    var pendingSeekMs: Long = -1L
    private var lastResumeSaveAt = 0L
    private var resumeRestored = false
    private val listeners = ArrayList<AppVideoControllerListener>()
    private val detachAudio: () -> Unit

    val isDisposed: Boolean get() = disposed

    init {
        detachAudio = AppAudioOutputCoordinator.attach(
            owner = this,
            isPlaying = { isPlaying || (playIntent && state == SDVideoState.BUFFERING) },
            isAudible = { isAudible },
            pauseForHandoff = { pause() },
        )
    }

    fun addListener(listener: AppVideoControllerListener): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun removeListener(listener: AppVideoControllerListener) {
        listeners.remove(listener)
    }

    // ------------------------------------------------------------------ 命令式 API（Dart 同名）

    /** 换源（Dart: 换 controller / fromRaw）。旧源的续播位置先保存。 */
    fun setSource(raw: String, autoPlay: Boolean = playIntent) {
        val next = AppVideoSource.parse(raw)
        if (next == url) return
        saveResume(force = true)
        url = next
        resetForNewSource()
        playIntent = autoPlay && next.isNotEmpty()
        generation++
    }

    /** 重建解码器（Dart retry：新 controller 重新 initialize）。保留当前位置。 */
    fun retry() {
        if (disposed) return
        if (positionMs > 0L && pendingSeekMs < 0L) pendingSeekMs = positionMs
        errorDescription = ""
        state = SDVideoState.IDLE
        readyOnce = false
        firstFrameRendered = false
        generation++
    }

    fun play() {
        if (disposed) return
        playIntent = true
        if (url.isEmpty()) return
        if (isCompleted && !looping) pendingSeekMs = 0L
        issuePlay()
    }

    fun pause() {
        if (disposed && view == null) return
        playIntent = false
        view?.pause()
        releaseAudio()
        saveResume(force = true)
    }

    fun togglePlayback() {
        if (isPlaying || (playIntent && isBuffering)) pause() else play()
    }

    /** 停止并回到开头（原生 stop）。 */
    fun stop() {
        playIntent = false
        view?.stop()
        releaseAudio()
    }

    /** 跳转（夹在 0..duration）；未准备好时先记下，准备好后再 seek。 */
    fun seekTo(ms: Long) {
        if (disposed) return
        val d = durationMs
        val target = if (d > 0L) ms.coerceIn(0L, d) else ms.coerceAtLeast(0L)
        positionMs = target
        val v = view
        if (v != null && isInitialized) {
            v.seekTo(target)
        } else {
            pendingSeekMs = target
        }
    }

    fun seekBy(deltaMs: Long) = seekTo(positionMs + deltaMs)

    /** 0..1；播放中从 0 调到 >0 先申请出声。 */
    @kotlin.jvm.JvmName("setVolumeFn")
    fun setVolume(v: Float) {
        val next = v.coerceIn(0f, 1f)
        val wasAudible = isAudible
        volume = next
        onAudibilityChanged(wasAudible)
    }

    @kotlin.jvm.JvmName("setMutedFn")
    fun setMuted(value: Boolean) {
        val wasAudible = isAudible
        muted = value
        onAudibilityChanged(wasAudible)
    }

    fun toggleMute() = setMuted(!muted)

    /** Dart setPlaybackSpeed（夹在 0.25..4）。 */
    fun setPlaybackSpeed(s: Float) {
        speed = s.coerceIn(0.25f, 4f)
    }

    @kotlin.jvm.JvmName("setSpeedFn")
    fun setSpeed(s: Float) = setPlaybackSpeed(s)

    @kotlin.jvm.JvmName("setLoopingFn")
    fun setLooping(value: Boolean) {
        looping = value
    }

    /** 页面销毁时调用：暂停、保存续播、退出出声仲裁。 */
    fun dispose() {
        if (disposed) return
        saveResume(force = true)
        view?.pause()
        playIntent = false
        disposed = true
        AppVideoWakelock.release(this)
        releaseAudio()
        detachAudio()
        listeners.clear()
        view = null
        hasDecoder = false
    }

    // ------------------------------------------------------------------ 组件回调（SDAppVideoPlayer 使用）

    internal fun attachView(v: SDVideoView) {
        if (disposed) return
        view = v
        hasDecoder = true
        state = SDVideoState.LOADING
        readyOnce = false
        firstFrameRendered = false
        resumeRestored = false
        if (playIntent && url.isNotEmpty()) issuePlay()
    }

    /** 解除当前绑定的原生视图（不论是哪一个）。 */
    internal fun detachCurrentView() {
        view?.let { detachView(it) }
    }

    internal fun detachView(v: SDVideoView) {
        if (view !== v) return
        saveResume(force = true)
        // 下次挂载从当前位置继续（Dart: seekOnReady / 续播在解码器重建时生效）
        if (positionMs > 0L && pendingSeekMs < 0L && !isCompleted) pendingSeekMs = positionMs
        view = null
        hasDecoder = false
        readyOnce = false
        firstFrameRendered = false
        if (state != SDVideoState.ERROR) state = SDVideoState.IDLE
        AppVideoWakelock.release(this)
        releaseAudio()
    }

    internal fun handleState(st: SDVideoState, from: SDVideoView) {
        if (view !== from || disposed) return
        val prev = state
        state = st.state
        if (st.durationMs > 0L) durationMs = st.durationMs
        if (st.width > 0 && st.height > 0) {
            videoWidth = st.width
            videoHeight = st.height
        }
        when (st.state) {
            SDVideoState.READY, SDVideoState.PLAYING, SDVideoState.PAUSED -> {
                if (errorDescription.isNotEmpty()) errorDescription = ""
                if (!readyOnce) {
                    readyOnce = true
                    applyReadySeek()
                    listeners.toList().forEach { it.onReady(this) }
                    if (playIntent && st.state != SDVideoState.PLAYING) issuePlay()
                }
                // 不该播的时候原生仍在播（迟到的 play）→ 再暂停一次
                if (st.state == SDVideoState.PLAYING && !playIntent) from.pause()
            }
            SDVideoState.COMPLETED -> {
                if (!looping) {
                    playIntent = false
                    releaseAudio()
                    resumeKey?.let { resumeStore.remove(it) }
                    listeners.toList().forEach { it.onCompleted(this) }
                }
            }
            SDVideoState.ERROR -> {
                errorDescription = st.error.ifEmpty { "error" }
                releaseAudio()
                listeners.toList().forEach { it.onError(this, errorDescription) }
            }
        }
        if (st.state == SDVideoState.PLAYING && keepScreenOn) AppVideoWakelock.acquire(this) else AppVideoWakelock.release(this)
        if (prev != st.state) listeners.toList().forEach { it.onStateChanged(this, st.state) }
    }

    internal fun handleProgress(p: SDVideoProgress, from: SDVideoView) {
        if (view !== from || disposed) return
        if (p.durationMs > 0L) durationMs = p.durationMs
        positionMs = p.positionMs
        bufferedMs = p.bufferedMs
        saveResume(force = false)
        listeners.toList().forEach { it.onProgress(this, p.positionMs, durationMs) }
    }

    internal fun handleFirstFrame(from: SDVideoView) {
        if (view !== from || disposed) return
        firstFrameRendered = true
        listeners.toList().forEach { it.onFirstFrame(this) }
    }

    // ------------------------------------------------------------------ 私有

    private fun resetForNewSource() {
        state = SDVideoState.IDLE
        positionMs = 0L
        durationMs = 0L
        bufferedMs = 0L
        videoWidth = 0
        videoHeight = 0
        errorDescription = ""
        firstFrameRendered = false
        readyOnce = false
        resumeRestored = false
        pendingSeekMs = -1L
        releaseAudio()
    }

    private fun applyReadySeek() {
        val v = view ?: return
        var target = pendingSeekMs
        if (target < 0L && !resumeRestored) {
            val key = resumeKey
            val saved = if (key != null) resumeStore.read(key) else null
            if (saved != null && !saved.completed && saved.positionMs > 0L) target = saved.positionMs
        }
        resumeRestored = true
        pendingSeekMs = -1L
        if (target > 0L) {
            val d = durationMs
            val clamped = if (d > 0L) target.coerceAtMost(d) else target
            positionMs = clamped
            v.seekTo(clamped)
        }
    }

    /** 有声先 claim，令牌仍有效才真正播放（快速切换时丢弃过期的 play）。 */
    private fun issuePlay() {
        val v = view ?: return
        if (!isAudible) {
            v.play()
            return
        }
        val pager = try {
            PagerManager.getPager(pagerId) as? Pager
        } catch (e: Throwable) {
            null
        } ?: run {
            v.play()
            return
        }
        pager.lifecycleScope.launch {
            val token = AppAudioOutputCoordinator.claim(this@AppVideoController)
            if (token < 0 || !AppAudioOutputCoordinator.isCurrent(this@AppVideoController, token)) return@launch
            if (disposed || !playIntent || view !== v) {
                AppAudioOutputCoordinator.release(this@AppVideoController)
                return@launch
            }
            claimToken = token
            v.play()
        }
    }

    private fun onAudibilityChanged(wasAudible: Boolean) {
        val nowAudible = isAudible
        if (wasAudible == nowAudible) return
        if (!nowAudible) {
            releaseAudio()
        } else if (playIntent && view != null) {
            // 静音 → 有声：先让音乐等其它有声播放器暂停
            issuePlay()
        }
    }

    private fun releaseAudio() {
        claimToken = -1
        AppAudioOutputCoordinator.release(this)
    }

    private fun saveResume(force: Boolean) {
        val key = resumeKey ?: return
        if (!resumeRestored && !force) return
        if (positionMs <= 0L) return
        val now = nowMs()
        if (!force && now - lastResumeSaveAt < resumeSaveIntervalMs) return
        lastResumeSaveAt = now
        resumeStore.write(key, AppVideoResumePosition(positionMs, durationMs, false))
    }
}
