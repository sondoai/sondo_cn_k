package com.sondo.app.service.audio

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 播放模式。顺序与业务侧 PlayMode 一致，按下标互转。 */
enum class AppAudioPlayMode {
    /** 列表循环：最后一首结束后回到第一首。 */
    loop,

    /** 单曲循环。 */
    single,

    /** 随机播放。 */
    random;

    /** loop → single → random → loop */
    val next: AppAudioPlayMode get() = values()[(ordinal + 1) % values().size]

    /** 展示名（与参考工程 PlayMode.name 一致）。 */
    val label: String
        get() = when (this) {
            loop -> "列表循环"
            single -> "单曲循环"
            random -> "随机播放"
        }
}

/** 当前轨道播放完成后的处理方式。 */
enum class AppAudioCompletionMode {
    /** 按播放模式继续队列。 */
    queue,

    /** 停在当前轨道（试听用）。 */
    stop,
}

enum class AppAudioPlaybackStatus { idle, loading, buffering, ready, completed, error }

/** 音频片段（毫秒）。 */
data class AppAudioClip(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
    val isValid: Boolean get() = startMs >= 0 && endMs > startMs
}

data class AppAudioLyricLine(val positionMs: Long, val text: String)

/**
 * 一条可播放轨道。
 * @param id 稳定的业务 id；歌曲为 `song:<songId>`，试听轨道不得以 `song:` 开头
 * @param uri http(s) 地址或本地文件路径 / file:// 地址
 * @param preload false 表示边生成边播的直播流（live AAC）
 * @param loop 在本轨道（或片段）内循环
 */
data class AppAudioTrack(
    val id: String,
    val uri: String,
    val title: String = "",
    val artworkUri: String? = null,
    val durationMs: Long? = null,
    val lyrics: List<AppAudioLyricLine> = emptyList(),
    val preload: Boolean = true,
    val clip: AppAudioClip? = null,
    val loop: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
) {
    companion object {
        fun fromFile(
            id: String,
            path: String,
            title: String = "",
            artworkUrl: String? = null,
            durationMs: Long? = null,
            lyrics: List<AppAudioLyricLine> = emptyList(),
            preload: Boolean = true,
            clip: AppAudioClip? = null,
            loop: Boolean = false,
        ): AppAudioTrack {
            require(path.trim().isNotEmpty()) { "音频文件路径不能为空" }
            val uri = if (path.startsWith("file://")) path else "file://$path"
            return AppAudioTrack(id, uri, title, artworkUrl, durationMs, lyrics, preload, clip, loop)
        }

        fun fromUrl(
            id: String,
            url: String,
            title: String = "",
            artworkUrl: String? = null,
            durationMs: Long? = null,
            lyrics: List<AppAudioLyricLine> = emptyList(),
            preload: Boolean = true,
            clip: AppAudioClip? = null,
            loop: Boolean = false,
            headers: Map<String, String> = emptyMap(),
        ): AppAudioTrack {
            val u = url.trim()
            require(u.isNotEmpty()) { "音频地址不能为空" }
            return AppAudioTrack(id, u, title, artworkUrl, durationMs, lyrics, preload, clip, loop, headers)
        }
    }
}

/** 播放器只读快照。 */
data class AppAudioPlayerState(
    val status: AppAudioPlaybackStatus = AppAudioPlaybackStatus.idle,
    val queue: List<AppAudioTrack> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playMode: AppAudioPlayMode = AppAudioPlayMode.loop,
    val completionMode: AppAudioCompletionMode = AppAudioCompletionMode.queue,
    val currentLyricIndex: Int = -1,
    val error: String? = null,
) {
    val currentTrack: AppAudioTrack?
        get() = if (currentIndex >= 0 && currentIndex < queue.size) queue[currentIndex] else null

    /** 0~1 */
    val progress: Double
        get() = if (durationMs <= 0L) 0.0 else (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0)

    val hasNext: Boolean get() = queue.size > 1

    /** 事件用 JSON（不含队列内容）。 */
    fun toJson(): JSONObject {
        val t = currentTrack
        val o = JSONObject()
            .put("trackId", t?.id ?: "")
            .put("title", t?.title ?: "")
            .put("artworkUri", t?.artworkUri ?: "")
            .put("status", status.name)
            .put("isPlaying", isPlaying)
            .put("positionMs", positionMs)
            .put("bufferedMs", bufferedPositionMs)
            .put("durationMs", durationMs)
            .put("queueSize", queue.size)
            .put("currentIndex", currentIndex)
            .put("playMode", playMode.name)
            .put("completionMode", completionMode.name)
            .put("currentLyricIndex", currentLyricIndex)
        if (error != null) o.put("error", error)
        return o
    }
}
