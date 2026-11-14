package com.sondo.app.features.edit_video.state

import com.sondo.app.base.BasePager
import com.sondo.app.core.utils.CancelToken
import com.sondo.app.core.utils.DevLog
import com.sondo.app.data.models.MvShot
import com.sondo.app.nativeapi.SDVideoProgress
import com.sondo.app.nativeapi.SDVideoState
import com.sondo.app.nativeapi.SDVideoView
import com.sondo.app.service.MergeMode
import com.sondo.app.service.VideoMergerService
import com.sondo.app.service.audio.AppAudioOutputCoordinator
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.reactive.handler.observable
import kotlin.math.roundToLong

/** 分镜时间戳 `mm:ss[:fff]` → 毫秒（Dart `_handleEmptyShot` 的解析规则）。 */
internal fun parseShotTimestampMs(timestamp: String): Long {
    val date = timestamp.split(":")
    val minutes = date[0].toIntOrNull() ?: 0
    val seconds = if (date.size > 1) date[1].toIntOrNull() ?: 0 else 0
    val ms = if (date.size > 2) date[2].padEnd(3, '0').substring(0, 3).toIntOrNull() ?: 0 else 0
    return minutes * 60_000L + seconds * 1000L + ms
}

/** `mm:ss` → 毫秒；格式不对为 0。 */
internal fun parseClockMs(clock: String): Long {
    val units = clock.split(":")
    if (units.size < 2) return 0L
    val m = units[0].trim().toIntOrNull() ?: 0
    val s = units[1].trim().toIntOrNull() ?: 0
    return m * 60_000L + s * 1000L
}

/**
 * 编辑页 / 重试页的分镜播放器（Dart `StoryboardVideoPlayer`），由页面状态直接持有生命周期。
 *
 * 播放前先把分镜视频与歌曲对应片段在端上混流（[VideoMergerService.merge]），混好的本地文件交给原生 `SDVideo`。
 * 单曲循环用原生 loop；列表循环关掉 loop，播完（`completed`）切下一镜。
 * 出声经 [AppAudioOutputCoordinator] 与音乐播放器互斥。
 */
class StoryboardVideoPlayer(private val pager: BasePager, private val isActiveRoute: () -> Boolean) {
    var positions by pager.observable(0L)
    var durations by pager.observable(0L)
    var isPaused by pager.observable(false)
    var isVideoError by pager.observable(true)
    var videoLoading by pager.observable(false)
    @set:kotlin.jvm.JvmName("setIsVideoLoading")
    var isVideoLoading by pager.observable(false)
    var videoProgress by pager.observable("")
    /** 是否列表循环播放 */
    var isListRepeat by pager.observable(false)

    /** 已就绪的本地播放文件；空表示没有可渲染的播放器（Dart readyController == null）。 */
    var readySrc by pager.observable("")
    /** 变化时重建原生播放器（初始化失败重试用）。 */
    var playerKey by pager.observable(0)

    var videoRef: ViewRef<SDVideoView>? = null

    private var currentVideoUrl = ""
    private var disposed = false
    private var videoEpoch = 0
    private var isPlayCompleted = false
    private var nativeReady = false
    private var nativePlaying = false
    private var initRetry = 0
    private var pendingAutoPlay = true
    /** 就绪时本应自动播放、只因页面尚未出现（pageDidAppear 前）而暂停 */
    private var pausedByRouteGate = false
    private var pendingOnVideoReady: (() -> Unit)? = null
    private var pendingOnListRepeat: (() -> Unit)? = null

    private val owner = Any()
    private val detachAudio = AppAudioOutputCoordinator.attach(
        owner,
        isPlaying = { nativePlaying },
        isAudible = { true },
        pauseForHandoff = {
            videoRef?.view?.pause()
            nativePlaying = false
            isPaused = true
        },
    )

    /** 当前视频 URL（全屏等场景用）。 */
    val currentUrl: String get() = currentVideoUrl

    private fun detachControllerFromUi() {
        videoEpoch++
        try {
            videoRef?.view?.pause()
        } catch (e: Throwable) {
        }
        AppAudioOutputCoordinator.release(owner)
        nativePlaying = false
        nativeReady = false
        pendingOnVideoReady = null
        pendingOnListRepeat = null
        readySrc = ""
        currentVideoUrl = ""
        isVideoError = true
    }

    /** 重置播放器状态。 */
    fun reset() {
        if (disposed) return
        detachControllerFromUi()
        positions = 0L
        durations = 0L
        isPaused = false
        isVideoError = true
        videoLoading = false
        isVideoLoading = false
        isListRepeat = false
        videoProgress = ""
        isPlayCompleted = false
    }

    private fun claimAndPlay() {
        val epoch = videoEpoch
        pager.launch {
            val token = AppAudioOutputCoordinator.claim(owner)
            if (disposed || epoch != videoEpoch || isPaused) return@launch
            if (!AppAudioOutputCoordinator.isCurrent(owner, token)) return@launch
            videoRef?.view?.play()
        }
    }

    private fun pauseNative() {
        videoRef?.view?.pause()
        nativePlaying = false
        AppAudioOutputCoordinator.release(owner)
    }

    fun playOrPause() {
        if (disposed || isVideoError || videoLoading) return
        if (isPaused) {
            isPaused = false
            claimAndPlay()
        } else {
            pauseNative()
            isPaused = true
        }
    }

    /**
     * Dart 在 push 时即视为当前路由；Kuikly 要到 pageDidAppear 才可见。
     * 播放器在页面出现前就绪时补一次自动播放。
     */
    fun resumeIfRouteGated() {
        if (!pausedByRouteGate) return
        pausedByRouteGate = false
        if (!isPaused || !isActiveRoute() || readySrc.isEmpty()) return
        videoPlay()
    }

    fun videoPlay() {
        if (disposed || isVideoError || videoLoading) return
        isPaused = false
        claimAndPlay()
    }

    fun videoPause() {
        if (disposed || isPaused || isVideoError || videoLoading) return
        pauseNative()
        isPaused = true
    }

    fun seekTo(ms: Long) {
        if (disposed || isVideoError || videoLoading) return
        if (!nativeReady) return
        isPlayCompleted = false
        videoRef?.view?.seekTo(ms)
        positions = ms
    }

    /** 选择分镜并加载视频（先混流再初始化播放器）。 */
    suspend fun selectShot(
        shot: MvShot,
        audioUri: String,
        musicStartMs: Long,
        cancelToken: CancelToken?,
        autoPlay: Boolean = true,
        isMergeStartDuration: Boolean,
        onProgressUpdate: (String) -> Unit,
        onVideoReady: () -> Unit,
        onListRepeat: () -> Unit,
    ) {
        if (disposed) return
        DevLog.logger("StoryboardVideoPlayer: selectShot:${shot.id}")
        positions = 0L
        durations = 0L
        isPaused = false
        videoProgress = ""
        isVideoLoading = true
        isVideoError = false
        videoLoading = true
        isPlayCompleted = false
        onVideoReady()
        // 先断开 UI，再释放旧播放器
        detachControllerFromUi()
        val epoch = videoEpoch

        val url = shot.videoUrl
        if (url.isNullOrEmpty() || shot.status != 2) {
            handleVideoError()
            return
        }
        val mergedPath = handleEmptyShot(shot, url, audioUri, musicStartMs, cancelToken, isMergeStartDuration, onProgressUpdate)
        if (cancelToken?.isCancelled == true) {
            if (epoch == videoEpoch) handleCancellation()
            return
        }
        if (mergedPath == null || disposed || epoch != videoEpoch) return
        initControllerFromUrl(mergedPath, autoPlay, onVideoReady, onListRepeat)
    }

    private fun initControllerFromUrl(url: String, autoPlay: Boolean, onVideoReady: () -> Unit, onListRepeat: () -> Unit) {
        if (disposed) return
        currentVideoUrl = url
        pendingAutoPlay = autoPlay
        pendingOnVideoReady = onVideoReady
        pendingOnListRepeat = onListRepeat
        initRetry = 0
        nativeReady = false
        isVideoError = false
        readySrc = url
        playerKey++
    }

    private suspend fun handleEmptyShot(
        shot: MvShot,
        videoUrl: String,
        audioUri: String,
        musicStartMs: Long,
        cancelToken: CancelToken?,
        isMergeStartDuration: Boolean,
        onProgressUpdate: (String) -> Unit,
    ): String? {
        var start = parseShotTimestampMs(shot.timestamp)
        if (isMergeStartDuration) start += musicStartMs
        val end = if (shot.durationV1 > 0) {
            start + (shot.durationV1 * 1000).roundToLong()
        } else {
            start + shot.duration * 1000L
        }
        return try {
            val outputPath = VideoMergerService.merge(
                videoUrl = videoUrl,
                audioUrl = audioUri,
                outputFileName = "${shot.id}_merged.mp4",
                mode = MergeMode.replace,
                startMs = start,
                endMs = end,
                cancelToken = cancelToken,
                progressCallback = { step, progress ->
                    if (cancelToken?.isCancelled != true) {
                        if (progress != null) {
                            val progressText = "${(progress * 100).roundToLong()}%"
                            if (progressText != "100%") onProgressUpdate(progressText)
                        } else {
                            DevLog.logger(step)
                        }
                    }
                },
            )
            videoProgress = ""
            outputPath
        } catch (e: Throwable) {
            DevLog.logger("StoryboardVideoPlayer: 视频合并失败: ${e.message}")
            if (cancelToken?.isCancelled != true) handleVideoError()
            null
        }
    }

    /** 原生播放器状态事件（由视图转发）。 */
    fun onNativeState(state: SDVideoState) {
        if (disposed) return
        when (state.state) {
            SDVideoState.READY -> if (!nativeReady) {
                nativeReady = true
                setupVideoController(state.durationMs)
            }
            SDVideoState.PLAYING -> {
                nativePlaying = true
                if (!nativeReady) {
                    nativeReady = true
                    setupVideoController(state.durationMs)
                }
            }
            SDVideoState.PAUSED -> nativePlaying = false
            SDVideoState.COMPLETED -> {
                nativePlaying = false
                onPlayEnd()
            }
            SDVideoState.ERROR -> onNativeError()
        }
    }

    fun onNativeProgress(p: SDVideoProgress) {
        if (disposed || isVideoError || !nativeReady) return
        if (p.durationMs > 0) durations = p.durationMs
        positions = p.positionMs
        if (p.durationMs > 0 && p.durationMs - p.positionMs > 500) isPlayCompleted = false
    }

    private fun setupVideoController(durationMs: Long) {
        positions = 0L
        if (durationMs > 0) durations = durationMs
        val autoPlay = pendingAutoPlay && isActiveRoute()
        pausedByRouteGate = pendingAutoPlay && !autoPlay
        if (autoPlay) {
            isPaused = false
            claimAndPlay()
        } else {
            // 首次引导 / 页面不可见时只展示首帧，等页面解除门禁
            isPaused = true
        }
        videoLoading = false
        isVideoLoading = false
        pendingOnVideoReady?.invoke()
    }

    private fun onPlayEnd() {
        if (!isListRepeat || isPlayCompleted) return
        isPlayCompleted = true
        if (isPaused) return
        if (!isActiveRoute()) {
            forceStop()
            return
        }
        DevLog.logger("StoryboardVideoPlayer: 视频播放完成，切换下一分镜")
        pendingOnListRepeat?.invoke()
    }

    private fun onNativeError() {
        if (!nativeReady && initRetry < 3 && readySrc.isNotEmpty()) {
            initRetry++
            val epoch = videoEpoch
            pager.postDelayed(500 * initRetry) {
                if (!disposed && epoch == videoEpoch) playerKey++
            }
            return
        }
        handleVideoError()
    }

    private fun handleVideoError() {
        detachControllerFromUi()
        positions = 0L
        durations = 0L
        isPaused = false
        videoProgress = ""
        isVideoLoading = false
        videoLoading = false
    }

    private fun handleCancellation() {
        DevLog.logger("StoryboardVideoPlayer: 视频选择被取消")
        handleVideoError()
    }

    fun forceStop() {
        if (disposed) return
        pauseNative()
    }

    /** 释放当前解码器，供重新生成等场景复用播放器。 */
    fun forceDispose() {
        if (disposed) return
        detachControllerFromUi()
        isVideoLoading = false
        videoLoading = false
        positions = 0L
        durations = 0L
        isPaused = false
        videoProgress = ""
        isPlayCompleted = false
    }

    fun dispose() {
        if (disposed) return
        detachControllerFromUi()
        disposed = true
        detachAudio()
    }
}
