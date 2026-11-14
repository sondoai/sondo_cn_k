package com.sondo.app.service.audio

import com.sondo.app.base.AppHost
import com.sondo.app.core.utils.HostBinding
import com.sondo.app.nativeapi.SDAudioPlayerModule
import com.sondo.app.nativeapi.errorMsg
import com.sondo.app.nativeapi.ok
import com.sondo.app.service.AsyncMutex
import com.sondo.app.service.Svc
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuikly.core.log.KLog
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * 应用级音频播放器（对应 Flutter 侧 AppAudioPlayer + JustAudioAppBackend + AppAudioSessionCoordinator）。
 *
 * 进程内只有一个原生播放器（[SDAudioPlayerModule]）；队列、播放模式、完成策略、音频焦点、
 * 应用内出声仲裁都在这里。并发模型与参考工程一致：
 * - [generation]：每次换队列 / 换轨道递增，旧代次的异步结果一律丢弃；
 * - [playIntent]：每次 play / pause / stop / setQueue 递增，等待中的起播发现意图变化即放弃；
 * - 所有音源操作（stop、load）经 [sourceExecutor] 串行；
 * - 原生事件只在「已加载代次 == 当前代次」时生效，切歌期间上一首的事件不会漏进来。
 *
 * 所有挂起点都落在宿主页面的模块 / 定时器上，调用方页面销毁不影响进行中的操作。
 * 约定：原生 state 事件里的 `playing` 表示播放意图（playWhenReady），缓冲期间仍为 true。
 */
object AppAudioPlayer {
    private const val TAG = "AppAudioPlayer"

    private val module: SDAudioPlayerModule get() = Svc.module(SDAudioPlayerModule.MODULE_NAME)

    private var sourceExecutor = AsyncMutex()
    private val hostBinding = HostBinding()
    private val random: Random = Random.Default
    private var playbackStrategy: AppAudioPlaybackStrategy = AppAudioPlaybackStrategyFactory.create(AppAudioPlayMode.loop)

    private var queueValue: AppAudioQueue = AppAudioQueue.EMPTY
    private var playModeValue = AppAudioPlayMode.loop
    private var completionModeValue = AppAudioCompletionMode.queue
    private var generation = 0
    private var loadedGeneration: Int? = null
    private var loadedTrackId: String? = null
    private var completionGeneration: Int? = null
    private var resumeAfterInterruption = false
    private var volume = 1f
    private var volumeBeforeDuck: Float? = null
    private var initialized = false
    private var playIntent = 0

    /** 原生播放器是否处于播放意图。 */
    private var backendPlaying = false
    private var detachAudioOutput: (() -> Unit)? = null

    private val listeners = ArrayList<(AppAudioPlayerState) -> Unit>()

    /** 当前只读状态。 */
    var state: AppAudioPlayerState = AppAudioPlayerState()
        private set

    /** 当前播放队列。 */
    val queue: List<AppAudioTrack> get() = state.queue

    /** 当前轨道。 */
    val currentTrack: AppAudioTrack? get() = state.currentTrack

    val playMode: AppAudioPlayMode get() = state.playMode

    val completionMode: AppAudioCompletionMode get() = state.completionMode

    /** 监听状态变化（同步回调）。返回注销函数。 */
    fun addListener(listener: (AppAudioPlayerState) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    /**
     * 初始化原生事件监听与出声仲裁。可重复调用。
     * 宿主（根页面）重建后，挂在旧宿主上的事件监听和没跑完的协程都已失效：重新监听并复位到停止态。
     */
    fun initialize() {
        if (detachAudioOutput == null) {
            detachAudioOutput = AppAudioOutputCoordinator.attach(
                owner = this,
                isPlaying = { state.isPlaying || backendPlaying },
                isAudible = { volume > 0f && (state.isPlaying || backendPlaying) },
                pauseForHandoff = { pauseForAudioHandoff() },
            )
        }
        if (!hostBinding.needsBind()) return
        val firstTime = !initialized
        initialized = true
        module.addEventListener { onNativeEvent(it) }
        module.setLoopOne(false)
        if (!firstTime) resetAfterHostChange()
    }

    private fun resetAfterHostChange() {
        // 串行锁可能卡在旧协程手里；原生播放器状态未知，统一停掉
        sourceExecutor = AsyncMutex()
        AppAudioOutputCoordinator.resetAfterHostChange()
        generation++
        playIntent++
        loadedGeneration = null
        loadedTrackId = null
        completionGeneration = null
        resumeAfterInterruption = false
        volumeBeforeDuck = null
        stopBackend()
        emit(
            status = if (current == null) AppAudioPlaybackStatus.idle else AppAudioPlaybackStatus.ready,
            isPlaying = false,
            positionMs = 0L,
            bufferedPositionMs = 0L,
            currentLyricIndex = -1,
            clearError = true,
        )
    }

    // ------------------------------------------------------------------ 队列

    /**
     * 设置播放队列并加载 [initialIndex]。
     * [autoPlay] 为 true 时音源就绪后立即播放；方法只等到音源加载完成。
     */
    suspend fun setQueue(tracks: List<AppAudioTrack>, initialIndex: Int = 0, autoPlay: Boolean = false) {
        playIntent++
        AppAudioOutputCoordinator.release(this)
        queueValue = AppAudioQueue.of(tracks, initialIndex)
        val nextQueue = queueValue.tracks
        generation++
        loadedGeneration = null
        loadedTrackId = null
        completionGeneration = null
        emit(
            status = if (nextQueue.isEmpty()) AppAudioPlaybackStatus.idle else AppAudioPlaybackStatus.loading,
            positionMs = 0L,
            bufferedPositionMs = 0L,
            durationMs = current?.durationMs ?: 0L,
            currentLyricIndex = -1,
            isPlaying = false,
            clearError = true,
        )
        if (nextQueue.isEmpty()) {
            val gen = generation
            sourceExecutor.withLock {
                if (isGenerationActive(gen)) stopBackend()
            }
            if (!isGenerationActive(gen)) return
            releaseAudioSession()
            return
        }
        loadCurrent(autoPlay, generation)
    }

    /** 播放 [track]；传入 [queue] 时先替换整个队列。 */
    suspend fun playTrack(track: AppAudioTrack, queue: List<AppAudioTrack>? = null) {
        val nextQueue = if (queue == null) arrayListOf(track) else ArrayList(queue)
        val index = nextQueue.indexOfFirst { it.id == track.id }
        if (index == -1) nextQueue.add(track)
        setQueue(nextQueue, initialIndex = if (index == -1) nextQueue.size - 1 else index, autoPlay = true)
    }

    /** 队尾追加，不改变当前轨道。 */
    fun appendTrack(track: AppAudioTrack) {
        queueValue = queueValue.append(track)
        emit()
    }

    fun insertTrack(index: Int, track: AppAudioTrack) {
        queueValue = queueValue.insert(index, track)
        emit()
    }

    /** 重排队列，不重新加载当前音源。 */
    fun moveTrack(oldIndex: Int, newIndex: Int) {
        val next = queueValue.move(oldIndex, newIndex)
        if (next === queueValue) return
        queueValue = next
        emit()
    }

    /** 替换轨道元数据；当前轨道的音源变化时重新加载（原本在播则续播，从 0 开始）。 */
    suspend fun updateTrack(index: Int, track: AppAudioTrack) {
        if (index < 0 || index >= queueValue.length) return
        val previous = queueValue[index]
        queueValue = queueValue.replaceAt(index, track)
        val isCurrent = index == queueValue.currentIndex
        if (!isCurrent || !sourceChanged(previous, track)) {
            emit(currentLyricIndex = if (isCurrent) findLyricIndex(state.positionMs) else null)
            return
        }
        val shouldResume = state.isPlaying || backendPlaying
        generation++
        loadedGeneration = null
        loadedTrackId = null
        completionGeneration = null
        loadCurrent(shouldResume, generation)
    }

    /** 移除指定下标；移除当前轨道时自动切到新的当前轨道。 */
    suspend fun removeTrackAt(index: Int) {
        if (index < 0 || index >= queueValue.length) return
        val wasCurrent = index == queueValue.currentIndex
        val shouldResume = state.isPlaying || backendPlaying
        queueValue = queueValue.removeAt(index)
        if (!wasCurrent) {
            emit()
            return
        }
        playIntent++
        generation++
        loadedGeneration = null
        loadedTrackId = null
        completionGeneration = null
        val gen = generation
        sourceExecutor.withLock {
            if (isGenerationActive(gen)) stopBackend()
        }
        if (!isGenerationActive(gen)) return
        AppAudioOutputCoordinator.release(this)
        releaseAudioSession()
        if (!isGenerationActive(gen)) return
        if (queueValue.isEmpty) {
            emit(
                status = AppAudioPlaybackStatus.idle,
                positionMs = 0L,
                bufferedPositionMs = 0L,
                durationMs = 0L,
                currentLyricIndex = -1,
                isPlaying = false,
            )
            return
        }
        loadCurrent(shouldResume, gen)
    }

    /** 按业务 id 移除第一条匹配轨道。 */
    suspend fun removeTrackById(id: String) {
        val index = queueValue.indexOfId(id)
        if (index >= 0) removeTrackAt(index)
    }

    /** 选择指定下标并按需开始播放；同一下标只起播。 */
    suspend fun selectTrack(index: Int, autoPlay: Boolean = true) {
        if (index < 0 || index >= queueValue.length) return
        if (index == queueValue.currentIndex) {
            if (autoPlay) play()
            return
        }
        queueValue = queueValue.select(index)
        generation++
        loadedGeneration = null
        loadedTrackId = null
        completionGeneration = null
        AppAudioOutputCoordinator.release(this)
        loadCurrent(autoPlay, generation)
    }

    // ------------------------------------------------------------------ 播放控制

    /** 播放当前轨道；本代次尚未加载时先加载。 */
    suspend fun play() {
        val track = current ?: return
        if (loadedTrackId != track.id || loadedGeneration != generation) {
            loadCurrent(true, generation)
            return
        }
        startPlayback(generation)
    }

    suspend fun toggle() {
        if (state.isPlaying) pause() else play()
    }

    suspend fun pause() = pauseInternal(false)

    private suspend fun pauseInternal(preserveInterruptionResume: Boolean) {
        playIntent++
        if (!preserveInterruptionResume) resumeAfterInterruption = false
        module.pause()
        backendPlaying = false
        emit(isPlaying = false, status = AppAudioPlaybackStatus.ready)
        AppAudioOutputCoordinator.release(this)
        releaseAudioSession()
    }

    /** 被应用内另一个播放器抢占时调用；不再进入仲裁器，避免递归等待。 */
    private suspend fun pauseForAudioHandoff() {
        playIntent++
        resumeAfterInterruption = false
        module.pause()
        backendPlaying = false
        emit(isPlaying = false, status = AppAudioPlaybackStatus.ready)
        releaseAudioSession()
    }

    /** 停止当前轨道；默认保留队列和当前轨道。 */
    suspend fun stop(clearQueue: Boolean = false) {
        playIntent++
        generation++
        loadedGeneration = null
        loadedTrackId = null
        completionGeneration = null
        val gen = generation
        sourceExecutor.withLock {
            if (isGenerationActive(gen)) stopBackend()
        }
        if (!isGenerationActive(gen)) return
        AppAudioOutputCoordinator.release(this)
        releaseAudioSession()
        if (!isGenerationActive(gen)) return
        if (clearQueue) queueValue = AppAudioQueue.EMPTY
        emit(
            status = if (current == null) AppAudioPlaybackStatus.idle else AppAudioPlaybackStatus.ready,
            positionMs = 0L,
            bufferedPositionMs = 0L,
            durationMs = current?.durationMs ?: 0L,
            currentLyricIndex = -1,
            isPlaying = false,
        )
    }

    /** 下一首并开始播放。 */
    suspend fun next() {
        if (queueValue.isEmpty) return
        queueValue = queueValue.select(playbackStrategy.nextIndex(queueValue, random))
        generation++
        loadCurrent(true, generation)
    }

    /** 上一首并开始播放。 */
    suspend fun previous() {
        if (queueValue.isEmpty) return
        queueValue = queueValue.select(playbackStrategy.previousIndex(queueValue, random))
        generation++
        loadCurrent(true, generation)
    }

    /** 跳转（夹在 [0, duration]）；当前轨道未加载时忽略。 */
    fun seek(positionMs: Long) {
        val track = current ?: return
        if (loadedTrackId != track.id) return
        val duration = state.durationMs
        val target = if (duration > 0L) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        module.seek(target)
        emit(positionMs = target, currentLyricIndex = findLyricIndex(target))
    }

    /** 按 0~1 的比例跳转。 */
    fun seekPercent(percent: Double) {
        val value = percent.coerceIn(0.0, 1.0)
        seek((state.durationMs * value).roundToLong())
    }

    fun setPlayMode(mode: AppAudioPlayMode) {
        playModeValue = mode
        playbackStrategy = AppAudioPlaybackStrategyFactory.create(mode)
        emit()
    }

    fun setCompletionMode(mode: AppAudioCompletionMode) {
        completionModeValue = mode
        emit()
    }

    /** 设置音量 0~1：> 0 且在播时申请出声，降到 0 时让出。 */
    suspend fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        val claim = if (volume > 0f && state.isPlaying) AppAudioOutputCoordinator.claim(this) else null
        if (claim != null && !AppAudioOutputCoordinator.isCurrent(this, claim)) return
        module.setVolume(volume)
        if (volume <= 0f) AppAudioOutputCoordinator.release(this)
    }

    // ------------------------------------------------------------------ 内部

    private val current: AppAudioTrack? get() = queueValue.currentTrack

    private suspend fun loadCurrent(autoPlay: Boolean, gen: Int) {
        val track = current ?: return
        loadedTrackId = null
        loadedGeneration = null
        completionGeneration = null
        emit(
            status = AppAudioPlaybackStatus.loading,
            positionMs = 0L,
            bufferedPositionMs = 0L,
            durationMs = track.durationMs ?: 0L,
            currentLyricIndex = -1,
            isPlaying = false,
            clearError = true,
        )
        sourceExecutor.withLock {
            if (isGenerationActive(gen) && current === track) {
                try {
                    stopBackend()
                    initialize()
                    val clip = track.clip
                    if (clip != null && !clip.isValid) throw IllegalArgumentException("invalid clip")
                    val headers = if (track.headers.isEmpty()) null else JSONObject().also { h ->
                        track.headers.forEach { (k, v) -> h.put(k, v) }
                    }
                    val res = module.setSource(
                        uri = track.uri,
                        autoPlay = false,
                        loopOne = track.loop,
                        clipStartMs = clip?.startMs,
                        clipEndMs = clip?.endMs,
                        headers = headers,
                        preload = track.preload,
                    )
                    if (isGenerationActive(gen)) {
                        if (!res.ok()) throw IllegalStateException(res.errorMsg().ifEmpty { "load failed" })
                        loadedGeneration = gen
                        loadedTrackId = track.id
                        val nativeDuration = res.optLong("durationMs", 0L)
                        emit(
                            status = AppAudioPlaybackStatus.ready,
                            positionMs = 0L,
                            bufferedPositionMs = 0L,
                            durationMs = if (nativeDuration > 0L) nativeDuration else (track.durationMs ?: 0L),
                            currentLyricIndex = -1,
                            isPlaying = false,
                        )
                        if (autoPlay) startPlayback(gen)
                    }
                } catch (e: Throwable) {
                    if (isGenerationActive(gen)) {
                        loadedGeneration = null
                        loadedTrackId = null
                        reportError(e.message ?: "error")
                    }
                }
            }
        }
    }

    private suspend fun startPlayback(gen: Int) {
        if (!isGenerationActive(gen) || current == null) return
        val intent = ++playIntent
        val claim = if (volume > 0f) AppAudioOutputCoordinator.claim(this) else null
        if (!isGenerationActive(gen) || intent != playIntent) return
        if (claim != null && !AppAudioOutputCoordinator.isCurrent(this, claim)) return
        activateAudioSession()
        if (!isGenerationActive(gen) || intent != playIntent) return
        if (claim != null && !AppAudioOutputCoordinator.isCurrent(this, claim)) return
        emit(isPlaying = true, status = AppAudioPlaybackStatus.ready, clearError = true)
        backendPlaying = true
        module.play()
    }

    private suspend fun handleCompleted(gen: Int) {
        if (!isGenerationActive(gen) || completionGeneration == gen) return
        completionGeneration = gen
        backendPlaying = false
        emit(status = AppAudioPlaybackStatus.completed, isPlaying = false)
        AppAudioOutputCoordinator.release(this)
        releaseAudioSession()
        if (!isGenerationActive(gen)) return
        if (completionModeValue == AppAudioCompletionMode.stop) {
            stop()
            return
        }
        if (playbackStrategy.restartCurrentOnCompletion) {
            loadCurrent(true, gen)
            return
        }
        queueValue = queueValue.select(playbackStrategy.nextIndex(queueValue, random))
        generation++
        loadCurrent(true, generation)
    }

    // ------------------------------------------------------------------ 原生事件

    private fun onNativeEvent(e: JSONObject) {
        when (e.optString("event", "")) {
            "state" -> {
                onPlaying(e.optBoolean("playing", false))
                onProcessingState(e.optString("state", ""))
            }
            "position" -> {
                if (loadedGeneration != generation) return
                val duration = e.optLong("durationMs", 0L)
                val position = e.optLong("positionMs", 0L)
                emit(
                    positionMs = position,
                    bufferedPositionMs = e.optLong("bufferedMs", state.bufferedPositionMs),
                    durationMs = if (duration > 0L) duration else null,
                    currentLyricIndex = findLyricIndex(position),
                )
            }
            "duration" -> {
                if (loadedGeneration != generation) return
                val duration = e.optLong("durationMs", 0L)
                if (duration > 0L) emit(durationMs = duration)
            }
            "error" -> {
                backendPlaying = false
                reportError(e.optString("msg", "error"))
            }
            "interruption" -> handleInterruption(e.optBoolean("begin", false), e.optString("type", "unknown"))
            "becomingNoisy" -> handleBecomingNoisy()
        }
    }

    private fun onPlaying(playing: Boolean) {
        backendPlaying = playing
        if (loadedGeneration != generation) return
        emit(isPlaying = playing)
    }

    private fun onProcessingState(processingState: String) {
        if (loadedGeneration != generation) return
        val status = when (processingState) {
            "idle" -> AppAudioPlaybackStatus.idle
            "loading" -> AppAudioPlaybackStatus.loading
            "buffering" -> AppAudioPlaybackStatus.buffering
            "ready" -> AppAudioPlaybackStatus.ready
            "completed" -> AppAudioPlaybackStatus.completed
            else -> return
        }
        emit(status = status)
        if (status == AppAudioPlaybackStatus.completed) {
            val gen = generation
            go { handleCompleted(gen) }
        }
    }

    /**
     * 系统音频中断。
     * - 开始 + duck：在播则记下音量并降到 50%；
     * - 开始 + 其它：在播则记下「中断后恢复」并暂停；
     * - 结束：恢复被压低的音量；类型为 pause 且有恢复标记则继续播放。
     */
    fun handleInterruption(begin: Boolean, type: String) {
        if (begin) {
            if (type == "duck") {
                if (state.isPlaying) {
                    if (volumeBeforeDuck == null) volumeBeforeDuck = volume
                    val target = volume * 0.5f
                    go { setVolume(target) }
                }
                return
            }
            if (state.isPlaying) {
                resumeAfterInterruption = true
                go { pauseInternal(true) }
            }
            return
        }
        val before = volumeBeforeDuck
        if (before != null) {
            volumeBeforeDuck = null
            go { setVolume(before) }
        }
        if (type == "pause" && resumeAfterInterruption) {
            resumeAfterInterruption = false
            go { play() }
        }
    }

    /** 耳机拔出等：暂停。 */
    fun handleBecomingNoisy() {
        go { pause() }
    }

    private fun reportError(message: String) {
        emit(status = AppAudioPlaybackStatus.error, isPlaying = false, error = message)
    }

    private fun findLyricIndex(positionMs: Long): Int {
        val lyrics = current?.lyrics ?: return -1
        for (index in lyrics.indices.reversed()) {
            if (positionMs >= lyrics[index].positionMs) return index
        }
        return -1
    }

    private fun emit(
        status: AppAudioPlaybackStatus? = null,
        isPlaying: Boolean? = null,
        positionMs: Long? = null,
        bufferedPositionMs: Long? = null,
        durationMs: Long? = null,
        currentLyricIndex: Int? = null,
        error: String? = null,
        clearError: Boolean = false,
    ) {
        val prev = state
        val next = AppAudioPlayerState(
            status = status ?: prev.status,
            queue = queueValue.tracks,
            currentIndex = queueValue.currentIndex,
            isPlaying = isPlaying ?: prev.isPlaying,
            positionMs = positionMs ?: prev.positionMs,
            bufferedPositionMs = bufferedPositionMs ?: prev.bufferedPositionMs,
            durationMs = durationMs ?: prev.durationMs,
            playMode = playModeValue,
            completionMode = completionModeValue,
            currentLyricIndex = currentLyricIndex ?: prev.currentLyricIndex,
            error = error ?: if (clearError) null else prev.error,
        )
        state = next
        // 监听者可能在回调里增删自己
        for (l in listeners.toList()) {
            try {
                l(next)
            } catch (e: Throwable) {
                KLog.e(TAG, "listener error: ${e.message}")
            }
        }
    }

    private fun isGenerationActive(gen: Int): Boolean = gen == generation

    /** stop 用于切歌，底层 idle 状态下的异常不应阻断新音源。 */
    private fun stopBackend() {
        try {
            module.stop()
        } catch (e: Throwable) {
            // ignore
        }
        backendPlaying = false
    }

    private suspend fun activateAudioSession() {
        try {
            module.setSessionActive(true)
        } catch (e: Throwable) {
            // 来电或其它应用占用音频会话时激活失败，不阻断播放流程
        }
    }

    /** 每次暂停、停止、播完都释放音频焦点。 */
    private suspend fun releaseAudioSession() {
        try {
            module.setSessionActive(false)
        } catch (e: Throwable) {
            // ignore
        }
    }

    private fun sourceChanged(previous: AppAudioTrack, next: AppAudioTrack): Boolean =
        previous.uri != next.uri ||
            previous.headers != next.headers ||
            previous.preload != next.preload ||
            previous.clip?.startMs != next.clip?.startMs ||
            previous.clip?.endMs != next.clip?.endMs ||
            previous.loop != next.loop

    /** 在宿主页面上跑一段挂起逻辑（事件回调里用）。 */
    private fun go(block: suspend () -> Unit) {
        AppHost.pager.lifecycleScope.launch {
            try {
                block()
            } catch (e: Throwable) {
                KLog.e(TAG, "async error: ${e.message}")
            }
        }
    }
}
