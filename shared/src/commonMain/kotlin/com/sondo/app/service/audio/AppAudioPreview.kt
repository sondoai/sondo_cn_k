package com.sondo.app.service.audio

import com.sondo.app.core.utils.onHost
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** 试听前的主播放现场。 */
class AppAudioPlaybackSnapshot(
    val queue: List<AppAudioTrack>,
    val currentIndex: Int,
    val playMode: AppAudioPlayMode,
    val completionMode: AppAudioCompletionMode,
    val positionMs: Long,
    val isPlaying: Boolean,
) {
    suspend fun restore() {
        AppAudioPlayer.setPlayMode(playMode)
        AppAudioPlayer.setCompletionMode(completionMode)
        AppAudioPlayer.setQueue(queue, initialIndex = currentIndex)
        if (queue.isEmpty()) return
        if (positionMs > 0L) AppAudioPlayer.seek(positionMs)
        if (isPlaying) AppAudioPlayer.play()
    }

    companion object {
        fun capture(): AppAudioPlaybackSnapshot {
            val state = AppAudioPlayer.state
            return AppAudioPlaybackSnapshot(
                queue = state.queue,
                currentIndex = state.currentIndex,
                playMode = state.playMode,
                completionMode = state.completionMode,
                positionMs = state.positionMs,
                isPlaying = state.isPlaying,
            )
        }
    }
}

/**
 * 试听会话：复用唯一的播放器，试听同样经过出声仲裁。
 * 开始试听时保存主播放现场，结束时按需恢复。
 */
class AppAudioPreviewSession {
    private var snapshot: AppAudioPlaybackSnapshot? = null
    private var activePreviewTrack: AppAudioTrack? = null

    val isActive: Boolean get() = snapshot != null

    /** 正在恢复的主播放现场（stop 里清掉 snapshot 之后、restore 完成之前仍算「主音轨被挂起」）。 */
    private var restoring: AppAudioPlaybackSnapshot? = null

    /**
     * 主播放（`song:` 轨道）是否只是被试听临时挂起：试听前保存的现场里有主音轨，结束试听时会恢复。
     * 导航栏据此在试听期间（及恢复过程中）保留音乐胶囊，不让它消失又出现。
     */
    val suspendsMainTrack: Boolean
        get() = (snapshot ?: restoring)?.queue?.any { it.id.startsWith(MAIN_TRACK_PREFIX) } == true

    /** 只加载不播放（用于拿时长）。 */
    suspend fun load(track: AppAudioTrack, clip: AppAudioClip? = null, loop: Boolean = false) {
        if (snapshot == null) snapshot = AppAudioPlaybackSnapshot.capture()
        AppAudioPlayer.setCompletionMode(AppAudioCompletionMode.stop)
        val previewTrack = track.copy(clip = clip ?: track.clip, loop = loop)
        activePreviewTrack = previewTrack
        AppAudioPlayer.setQueue(listOf(previewTrack))
    }

    suspend fun play(track: AppAudioTrack, clip: AppAudioClip? = null, loop: Boolean = false) {
        if (snapshot == null) snapshot = AppAudioPlaybackSnapshot.capture()
        AppAudioPlayer.setCompletionMode(AppAudioCompletionMode.stop)
        val previewTrack = track.copy(clip = clip ?: track.clip, loop = loop)
        activePreviewTrack = previewTrack
        AppAudioPlayer.playTrack(previewTrack)
    }

    /**
     * 结束试听。播放器当前轨道已不是试听轨道（被别的播放替换）时只把完成策略放回去；
     * 否则清空队列后恢复现场（[restore] 为 false 时只恢复完成策略）。
     */
    suspend fun stop(restore: Boolean = true) {
        val snap = snapshot ?: return
        val active = activePreviewTrack
        snapshot = null
        activePreviewTrack = null
        // 从清空试听队列到主现场恢复完成，中间的状态广播里仍算「主音轨被挂起」
        if (restore) restoring = snap
        try {
            if (AppAudioPlayer.currentTrack !== active) {
                if (AppAudioPlayer.completionMode == AppAudioCompletionMode.stop) {
                    AppAudioPlayer.setCompletionMode(snap.completionMode)
                }
                return
            }
            AppAudioPlayer.stop(clearQueue = true)
            if (AppAudioPlayer.currentTrack != null) {
                if (AppAudioPlayer.completionMode == AppAudioCompletionMode.stop) {
                    AppAudioPlayer.setCompletionMode(snap.completionMode)
                }
                return
            }
            if (restore) snap.restore() else AppAudioPlayer.setCompletionMode(snap.completionMode)
        } finally {
            restoring = null
        }
    }

    suspend fun dispose() = stop()

    private companion object {
        /** 主播放器歌曲轨道的 id 前缀（与 PlayerService 一致）。 */
        const val MAIN_TRACK_PREFIX = "song:"
    }
}

/**
 * 试听协调器（我的音频页、选曲弹窗、音频裁剪共用）：只认当前 owner，
 * 上一个入口关闭时不会停掉新入口的试听。owner 传页面或组件自身即可。
 * 状态跟随 Events.AUDIO_STATE / Events.PLAYER_POSITION（trackId 为试听轨道 id，不得以 `song:` 开头）。
 */
object AppAudioPreviewCoordinator {
    private val session = AppAudioPreviewSession()
    private var owner: Any? = null

    // —— 串行执行 —— //
    // load / play / stop 都会挂起（等原生播放器），以前各自并发跑：选曲弹窗选中歌曲后，弹窗的 stop 还在异步恢复主播放列表，
    // MV 创作页的裁剪器已经 load 了新歌，此时快照已清空，裁剪器重新「保存现场」抓到的是恢复了一半的播放器（只剩试听轨）。
    // 离开创作页时恢复的就不是主播放列表，主音轨一直不活跃 —— 导航栏的音乐胶囊先消失、之后才又出现。
    // 现在按调用顺序逐个执行，后一个操作一定看到前一个操作完成后的播放器。
    private var busy = false
    private val waiters = ArrayList<() -> Unit>()

    private suspend fun serial(block: suspend () -> Unit) = onHost {
        if (busy) suspendCoroutine<Unit> { cont -> waiters.add { cont.resume(Unit) } }
        busy = true
        try {
            block()
        } finally {
            if (waiters.isEmpty()) busy = false else waiters.removeAt(0).invoke()
        }
    }

    fun owns(owner: Any): Boolean = this.owner === owner

    val isActive: Boolean get() = session.isActive

    /** 主音轨是否只是被试听临时挂起（见 [AppAudioPreviewSession.suspendsMainTrack]）。 */
    val suspendsMainTrack: Boolean get() = session.suspendsMainTrack

    suspend fun load(owner: Any, track: AppAudioTrack, clip: AppAudioClip? = null, loop: Boolean = false) {
        this.owner = owner
        serial { session.load(track, clip, loop) }
    }

    suspend fun play(owner: Any, track: AppAudioTrack, clip: AppAudioClip? = null, loop: Boolean = false) {
        this.owner = owner
        serial { session.play(track, clip, loop) }
    }

    suspend fun resume(owner: Any) {
        if (owns(owner)) serial { AppAudioPlayer.play() }
    }

    suspend fun pause(owner: Any) {
        if (owns(owner)) serial { AppAudioPlayer.pause() }
    }

    fun seek(owner: Any, positionMs: Long) {
        if (owns(owner)) AppAudioPlayer.seek(positionMs)
    }

    suspend fun stop(owner: Any, restore: Boolean = true) {
        if (!owns(owner)) return
        this.owner = null
        serial { session.stop(restore) }
    }
}
