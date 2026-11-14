package com.sondo.app.service.audio

import com.sondo.app.core.utils.onHost

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
    }

    suspend fun dispose() = stop()
}

/**
 * 试听协调器（我的音频页、选曲弹窗、音频裁剪共用）：只认当前 owner，
 * 上一个入口关闭时不会停掉新入口的试听。owner 传页面或组件自身即可。
 * 状态跟随 Events.AUDIO_STATE / Events.PLAYER_POSITION（trackId 为试听轨道 id，不得以 `song:` 开头）。
 */
object AppAudioPreviewCoordinator {
    private val session = AppAudioPreviewSession()
    private var owner: Any? = null

    fun owns(owner: Any): Boolean = this.owner === owner

    val isActive: Boolean get() = session.isActive

    suspend fun load(owner: Any, track: AppAudioTrack, clip: AppAudioClip? = null, loop: Boolean = false) {
        this.owner = owner
        onHost { session.load(track, clip, loop) }
    }

    suspend fun play(owner: Any, track: AppAudioTrack, clip: AppAudioClip? = null, loop: Boolean = false) {
        this.owner = owner
        onHost { session.play(track, clip, loop) }
    }

    suspend fun resume(owner: Any) {
        if (owns(owner)) onHost { AppAudioPlayer.play() }
    }

    suspend fun pause(owner: Any) {
        if (owns(owner)) onHost { AppAudioPlayer.pause() }
    }

    fun seek(owner: Any, positionMs: Long) {
        if (owns(owner)) AppAudioPlayer.seek(positionMs)
    }

    suspend fun stop(owner: Any, restore: Boolean = true) {
        if (!owns(owner)) return
        this.owner = null
        onHost { session.stop(restore) }
    }
}
