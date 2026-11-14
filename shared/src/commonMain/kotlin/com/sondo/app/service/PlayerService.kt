package com.sondo.app.service

import com.sondo.app.core.utils.onHost
import com.sondo.app.base.AppHost
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.base.currentPager
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.HostBinding
import com.sondo.app.data.models.PlayMode
import com.sondo.app.data.models.SongModel
import com.sondo.app.data.models.jRaw
import com.sondo.app.service.audio.AppAudioCompletionMode
import com.sondo.app.service.audio.AppAudioPlayMode
import com.sondo.app.service.audio.AppAudioPlaybackStatus
import com.sondo.app.service.audio.AppAudioPlayer
import com.sondo.app.service.audio.AppAudioPlayerState
import com.sondo.app.service.audio.AppAudioTrack
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuikly.core.log.KLog
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.math.roundToLong

/** 一句歌词：time 为毫秒。 */
class LyricLine(val time: Long, val lyric: String) {
    fun toJson(): JSONObject = JSONObject().put("time", time).put("lyric", lyric)
}

/**
 * 音乐播放服务（对应 Flutter 侧 PlayerController）：把歌曲列表适配到应用唯一的 [AppAudioPlayer]，
 * 并把播放器快照投影成业务字段。歌曲轨道 id 为 `song:<songId>`；URL 以 `.aac` 结尾的轨道不预加载。
 *
 * 状态不是响应式的：页面在 created() 里用 [snapshot] / 各只读属性初始化自己的 observable，随后跟随事件：
 * - [Events.PLAYER_STATE]：除播放进度以外的状态变化，data 见 [snapshot]；
 * - [Events.PLAYER_POSITION]：播放进度（原生约 200ms 一次，数值不变不发），data 见 [positionSnapshot]；
 * - [Events.AUDIO_STATE]：原始播放器状态（含试听轨道），供试听页面使用；
 * - [Events.PLAYER_SONG_CHANGED]：操作面板里的歌曲（[song]）变化。
 *
 * 挂起方法可在任意页面协程里调用（内部挂起点都在宿主页面上，页面销毁不影响进行中的切歌）；
 * 点击回调里可用 [launch] 包一层。
 */
object PlayerService {
    private const val TAG = "PlayerService"
    private const val SONG_PREFIX = "song:"

    // 歌曲列表（业务队列）
    private val _playlist = ArrayList<SongModel>()

    // 当前播放索引
    private var _currentIndex = -1

    /** idle / loading / buffering / ready / completed（播放器的 error 状态按 ready 上报）。 */
    var processingState: String = "idle"
        private set

    var isPlaying: Boolean = false
        private set

    /** 当前播放进度（毫秒）。 */
    var position: Long = 0L
        private set

    /** 当前歌曲总时长（毫秒）。 */
    var duration: Long = 0L
        private set

    private var bufferedPosition: Long = 0L

    /** 切歌后到新音源加载完成前为 true；期间播放器仍可能回报上一首的位置，需忽略。 */
    private var isSwitchingSource = false

    var playMode: PlayMode = PlayMode.loop
        private set

    /** 封面是否旋转（跟随 isPlaying）。 */
    var isRotating: Boolean = false
        private set

    /** 当前歌词下标：初始 0；清空后 -1；刚解析完、还没到第一句时 -2。 */
    var currentLyricIndex: Int = 0
        private set

    private val lyricTimestamps = ArrayList<Long>()
    private val _lyricContents = ArrayList<LyricLine>()

    /** 正在切换到的歌曲 id；0 表示无。 */
    var changeSongId: Long = 0L
        private set

    /** 当前正在播放的歌曲（切歌时立即更新，供详情页动画联动）。 */
    var currentPlayAudio: SongModel? = null
        private set

    /** 热门歌曲（只做容器）。 */
    val hotList = ArrayList<SongModel>()

    /** 操作面板里的歌曲。 */
    var song: SongModel? = null
        private set

    /** 「创作同款」的入参。 */
    var createData: JSONObject? = null
        private set

    var oldSongId: Long = -1L

    private var debounceHitTimer: String? = null
    private var hitRecorded = false
    private var playRequest = 0
    private var lastObservedTrack: AppAudioTrack? = null
    private var inited = false
    private val hostBinding = HostBinding()

    // 事件去重
    private var lastStateSignature = ""
    private var lastAudioSignature = ""
    private var lastPositionSignature = ""

    // ------------------------------------------------------------------ 只读访问

    /** 当前播放列表（只读视图）。 */
    val playlist: List<SongModel> get() = _playlist

    /** 播放队列数据源（供外部使用）。 */
    val playQueueDataSource: List<SongModel> get() = _playlist

    val currentPlayQueueIndex: Int get() = _currentIndex

    val currentSong: SongModel?
        get() = if (_currentIndex >= 0 && _currentIndex < _playlist.size) _playlist[_currentIndex] else null

    /** 播放器当前轨道是否是歌曲（而不是试听）。 */
    val isMainTrackActive: Boolean get() = AppAudioPlayer.currentTrack?.id?.startsWith(SONG_PREFIX) ?: false

    /** 0~1 */
    val progress: Double
        get() = if (duration > 0L) (position.toDouble() / duration).coerceAtMost(1.0) else 0.0

    /** 歌词内容列表。 */
    val lyricContents: List<LyricLine> get() = _lyricContents

    // ------------------------------------------------------------------ 生命周期

    /**
     * 由启动流程调用：接上播放器状态、登录态变化。
     * 宿主（根页面）重建后再次调用时，播放器会重新挂原生事件监听并复位到停止态。
     */
    fun init() {
        Svc.ensureBound()
        AccountHooks.cleanListPlay = { cleanListPlay() }
        if (hostBinding.needsBind() && inited) {
            // 旧宿主上没跑完的切歌不会再回来
            isSwitchingSource = false
            ++playRequest
            debounceHitTimer = null
        }
        AppAudioPlayer.initialize()
        if (inited) {
            applyAudioState()
            return
        }
        inited = true
        AppAudioPlayer.addListener { onAudioStateChanged(it) }
        onAudioStateChanged(AppAudioPlayer.state)
        Svc.onLogout { onLogout() }
    }

    /** 登出：歌单里的收藏态全部复位（清播放现场由 AccountHooks.cleanListPlay 触发）。 */
    private fun onLogout() {
        for (index in _playlist.indices) {
            _playlist[index] = _playlist[index].copy(isCollect = false)
        }
    }

    /** 在宿主页面上启动一段播放逻辑（点击回调里用）；异常只记日志。 */
    fun launch(block: suspend () -> Unit) {
        AppHost.pager.lifecycleScope.launch {
            try {
                block()
            } catch (e: Throwable) {
                KLog.e(TAG, "launch error: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------ 快照 / 事件

    /**
     * 同步快照（= [Events.PLAYER_STATE] 的 data）：
     * {currentSongId, currentIndex, hasCurrentSong, currentSong?{id,musicTitle,musicCoverUrl,musicAudioUrl,musicGenerateStatus},
     *  isPlaying, isRotating, processingState, buffering, positionMs, durationMs, bufferedMs, progress,
     *  playMode, playModeIndex, playModeName, queueSize, isMainTrackActive, hasTrack, trackId,
     *  currentLyricIndex, lyricCount, isSwitchingSource, navPlayerChanged}
     */
    fun snapshot(navPlayerChanged: Boolean = false): JSONObject {
        val track = AppAudioPlayer.currentTrack
        val current = currentSong
        val o = JSONObject()
            .put("currentSongId", changeSongId)
            .put("currentIndex", _currentIndex)
            .put("hasCurrentSong", current != null)
            .put("isPlaying", isPlaying)
            .put("isRotating", isRotating)
            .put("processingState", processingState)
            .put("buffering", processingState == "loading" || processingState == "buffering")
            .put("positionMs", position)
            .put("durationMs", duration)
            .put("bufferedMs", bufferedPosition)
            .put("progress", progress)
            .put("playMode", playMode.name)
            .put("playModeIndex", playMode.ordinal)
            .put("playModeName", playMode.displayName)
            .put("queueSize", _playlist.size)
            .put("isMainTrackActive", isMainTrackActive)
            .put("hasTrack", track != null)
            .put("trackId", track?.id ?: "")
            .put("currentLyricIndex", currentLyricIndex)
            .put("lyricCount", _lyricContents.size)
            .put("isSwitchingSource", isSwitchingSource)
            .put("navPlayerChanged", navPlayerChanged)
        if (current != null) {
            o.put(
                "currentSong",
                JSONObject()
                    .put("id", current.id)
                    .put("musicTitle", current.musicTitle ?: "")
                    .put("musicCoverUrl", current.musicCoverUrl ?: "")
                    .put("musicAudioUrl", current.musicAudioUrl ?: "")
                    .put("musicGenerateStatus", current.musicGenerateStatus),
            )
        }
        return o
    }

    /**
     * 进度快照（= [Events.PLAYER_POSITION] 的 data）：
     * {trackId, isMainTrack, currentSongId, positionMs, durationMs, bufferedMs, progress, currentLyricIndex}
     * 试听轨道播放时 positionMs / durationMs 为播放器原始值。
     */
    fun positionSnapshot(): JSONObject {
        val raw = AppAudioPlayer.state
        val main = isMainTrackActive || raw.currentTrack == null
        val pos = if (main) position else raw.positionMs
        val dur = if (main) duration else raw.durationMs
        return JSONObject()
            .put("trackId", raw.currentTrack?.id ?: "")
            .put("isMainTrack", main)
            .put("currentSongId", changeSongId)
            .put("positionMs", pos)
            .put("durationMs", dur)
            .put("bufferedMs", raw.bufferedPositionMs)
            .put("progress", if (dur > 0L) (pos.toDouble() / dur).coerceIn(0.0, 1.0) else 0.0)
            .put("currentLyricIndex", currentLyricIndex)
    }

    /** 广播业务状态。[force] 时即使签名没变也发（对应参考工程的 update(['player'])）。 */
    private fun notifyState(navPlayerChanged: Boolean = false, force: Boolean = false) {
        val signature = buildString {
            append(changeSongId).append('|').append(_currentIndex).append('|').append(isPlaying).append('|')
            append(processingState).append('|').append(duration).append('|').append(playMode.ordinal).append('|')
            append(_playlist.size).append('|').append(AppAudioPlayer.currentTrack?.id ?: "").append('|')
            append(currentLyricIndex).append('|').append(_lyricContents.size).append('|').append(isSwitchingSource)
            val cur = currentSong
            append('|').append(cur?.musicTitle ?: "").append('|').append(cur?.musicCoverUrl ?: "")
            append('|').append(cur?.musicAudioUrl ?: "").append('|').append(cur?.isCollect ?: false)
        }
        if (!force && !navPlayerChanged && signature == lastStateSignature) return
        lastStateSignature = signature
        Svc.emit(Events.PLAYER_STATE, snapshot(navPlayerChanged))
    }

    private fun notifyPosition(force: Boolean = false) {
        val data = positionSnapshot()
        val signature = "${data.optString("trackId")}|${data.optLong("positionMs")}|${data.optLong("durationMs")}|" +
            "${data.optLong("bufferedMs")}|$currentLyricIndex"
        if (!force && signature == lastPositionSignature) return
        lastPositionSignature = signature
        Svc.emit(Events.PLAYER_POSITION, data)
    }

    private fun notifyAudio(state: AppAudioPlayerState) {
        val signature = "${state.currentTrack?.id}|${state.status}|${state.isPlaying}|${state.durationMs}|" +
            "${state.queue.size}|${state.currentIndex}|${state.playMode}|${state.completionMode}|${state.error}"
        if (signature == lastAudioSignature) return
        lastAudioSignature = signature
        Svc.emit(Events.AUDIO_STATE, state.toJson())
    }

    // ------------------------------------------------------------------ 播放器状态投影

    private fun onAudioStateChanged(state: AppAudioPlayerState) {
        notifyAudio(state)
        applyAudioState(state)
    }

    /** 将通用播放快照投影为业务字段。 */
    private fun applyAudioState(state: AppAudioPlayerState = AppAudioPlayer.state) {
        val track = state.currentTrack
        val navTrackChanged = track !== lastObservedTrack
        lastObservedTrack = track
        if (track != null && !track.id.startsWith(SONG_PREFIX)) {
            // 试听轨道：只通知迷你播放条，其余字段不动
            if (navTrackChanged) notifyState(navPlayerChanged = true)
            notifyPosition()
            return
        }
        if (track != null && state.currentIndex >= 0 && state.currentIndex < _playlist.size) {
            val song = _playlist[state.currentIndex]
            if (track.id == "$SONG_PREFIX${song.id}") {
                if (_currentIndex != state.currentIndex) {
                    _currentIndex = state.currentIndex
                    changeSongId = song.id
                    oldSongId = song.id
                    currentPlayAudio = song
                    hitRecorded = false
                    _lyricContents.clear()
                    lyricTimestamps.clear()
                    currentLyricIndex = -1
                    parseLyricString(song.musicConvertLyric)
                }
            }
        }
        isPlaying = state.isPlaying
        isRotating = state.isPlaying
        playMode = PlayMode.values()[state.playMode.ordinal]
        processingState = when (state.status) {
            AppAudioPlaybackStatus.idle -> "idle"
            AppAudioPlaybackStatus.loading -> "loading"
            AppAudioPlaybackStatus.buffering -> "buffering"
            AppAudioPlaybackStatus.completed -> "completed"
            AppAudioPlaybackStatus.ready, AppAudioPlaybackStatus.error -> "ready"
        }
        if (state.status == AppAudioPlaybackStatus.completed) hitRecorded = false
        if (!isSwitchingSource) {
            position = state.positionMs
            duration = state.durationMs
            bufferedPosition = state.bufferedPositionMs
            updateCurrentLyric(state.positionMs)
            if (!hitRecorded && state.positionMs / 1000L >= 5L && state.isPlaying) {
                hitRecorded = true
                addMusicHit(5)
            }
        }
        notifyState(navPlayerChanged = navTrackChanged)
        notifyPosition()
    }

    // ------------------------------------------------------------------ 对外方法

    fun setSong(song: SongModel?, updateId: String) {
        this.song = song
        val data = JSONObject().put("updateId", updateId)
        if (song != null) data.put("song", song.toJson())
        Svc.emit(Events.PLAYER_SONG_CHANGED, data)
    }

    /** 更新歌单里某首歌的收藏态。 */
    fun updatePlayList(id: Long, isCollect: Boolean) {
        val index = _playlist.indexOfFirst { it.id == id }
        if (index != -1) {
            _playlist[index] = _playlist[index].copy(isCollect = isCollect)
            notifyState(force = true)
        }
    }

    /** 只把对齐歌词写进播放列表。正在播这首时解析，不重新起播。 */
    fun cacheConvertLyric(id: Long, lyric: String) {
        val index = _playlist.indexOfFirst { it.id == id }
        if (index != -1) _playlist[index] = _playlist[index].copy(musicConvertLyric = lyric)
        if (currentSong?.id != id || lyric.isEmpty()) return
        parseLyricString(lyric)
        notifyState(force = true)
    }

    /** 更新歌曲地址 / 状态；status == 3（生成完成）时用新地址开始播放这首。 */
    fun updatePlayInfo(id: Long, musicAudioUrl: String? = null, status: Int? = null) {
        DevLog.logger("updatePlayInfo: $id $musicAudioUrl $status")
        val index = _playlist.indexOfFirst { it.id == id }
        if (index != -1) {
            val cur = _playlist[index]
            _playlist[index] = cur.copy(
                musicAudioUrl = musicAudioUrl ?: cur.musicAudioUrl,
                musicGenerateStatus = status ?: cur.musicGenerateStatus,
            )
            if (status == 3) {
                launch { playIndex(index) }
                notifyState(navPlayerChanged = true)
            }
            notifyState(force = true)
        }
    }

    /**
     * 播放计数：3 秒防抖后 GET /member/music/addMusicHit/{当前歌曲 id}。[seconds] 未使用（与参考工程一致）。
     */
    @Suppress("UNUSED_PARAMETER")
    fun addMusicHit(seconds: Int) {
        if (currentSong == null) return
        Svc.clearTimeout(debounceHitTimer)
        debounceHitTimer = Svc.setTimeout(3000) {
            debounceHitTimer = null
            val id = currentSong?.id ?: return@setTimeout
            Svc.launch { WorksApi.addMusicHit(id) }
        }
    }

    /** 播放边生成边播的 AAC 直播流：队列替换成这一条。 */
    suspend fun playLiveAac(url: String) = onHost { playLiveAacImpl(url) }

    private suspend fun playLiveAacImpl(url: String) {
        try {
            AppAudioPlayer.playTrack(
                AppAudioTrack.fromUrl(
                    id = "$SONG_PREFIX$changeSongId",
                    url = url,
                    title = currentSong?.musicTitle ?: "",
                    artworkUrl = currentSong?.musicCoverUrl,
                    preload = false,
                )
            )
        } catch (e: Throwable) {
            DevLog.logger("playLiveAac error: ${e.message}")
        }
    }

    /** 按现有三种歌词结构判断能不能解析出至少一句。不改播放器里的歌词列表。 */
    fun lyricStringHasLines(lyric: String): Boolean {
        return try {
            val decoded = JSONArray(lyric)
            if (decoded.length() == 0) return false
            val first = decoded.optJSONObject(0)
            if (first != null && first.has("aligned_lyrics")) {
                val lines = first.optJSONArray("aligned_lyrics")
                return lines != null && lines.length() > 0
            }
            if (first != null && first.has("aligned_words")) {
                val lines = first.optJSONArray("aligned_words")
                return lines != null && lines.length() > 0
            }
            for (i in 0 until decoded.length()) {
                val section = decoded.optJSONObject(i) ?: continue
                val lines = section.optJSONArray("lines") ?: continue
                for (j in 0 until lines.length()) {
                    val line = lines.optJSONObject(j) ?: continue
                    if (line.jRaw("start") != null) return true
                }
            }
            false
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 解析歌词，三种结构：
     * 1. `[{aligned_lyrics: [{start_s: 秒, text}]}]`
     * 2. `[{aligned_words: [{start_s: 秒, word}]}]`
     * 3. 分段 `[{section_type, start, lines: [{start: 毫秒, text}]}]`（段落名不是歌词，只收有 start 的句子）
     */
    fun parseLyrics(lyrics: JSONArray): List<LyricLine> {
        currentLyricIndex = -2
        lyricTimestamps.clear()
        _lyricContents.clear()
        if (lyrics.length() == 0) return emptyList()

        val first = lyrics.optJSONObject(0)
        if (first != null && first.has("aligned_lyrics")) {
            val lines = first.optJSONArray("aligned_lyrics")
            if (lines != null) {
                for (i in 0 until lines.length()) {
                    val line = lines.optJSONObject(i) ?: continue
                    // 0.7180851063829787 秒 → 毫秒取整
                    val time = (line.optDouble("start_s", 0.0) * 1000).roundToLong()
                    _lyricContents.add(LyricLine(time, line.optString("text", "")))
                    lyricTimestamps.add(time)
                }
            }
            return _lyricContents
        }
        if (first != null && first.has("aligned_words")) {
            val lines = first.optJSONArray("aligned_words")
            if (lines != null) {
                for (i in 0 until lines.length()) {
                    val line = lines.optJSONObject(i) ?: continue
                    val time = (line.optDouble("start_s", 0.0) * 1000).roundToLong()
                    _lyricContents.add(LyricLine(time, line.optString("word", "")))
                    lyricTimestamps.add(time)
                }
            }
            return _lyricContents
        }
        for (i in 0 until lyrics.length()) {
            val section = lyrics.optJSONObject(i) ?: continue
            val lines = section.optJSONArray("lines") ?: continue
            for (j in 0 until lines.length()) {
                val line = lines.optJSONObject(j) ?: continue
                if (line.jRaw("start") == null) continue
                val time = line.optLong("start", 0L)
                _lyricContents.add(LyricLine(time, line.optString("text", "")))
                lyricTimestamps.add(time)
            }
        }
        return _lyricContents
    }

    private fun parseLyricString(lyric: String?) {
        if (lyric.isNullOrEmpty()) return
        try {
            parseLyrics(JSONArray(lyric))
        } catch (e: Throwable) {
            DevLog.logger("parseLyrics error: ${e.message}")
        }
    }

    /** 当前句 = 最后一个时间戳 ≤ 进度的句子。 */
    private fun updateCurrentLyric(positionMs: Long) {
        if (lyricTimestamps.isEmpty()) return
        for (i in lyricTimestamps.indices.reversed()) {
            if (positionMs >= lyricTimestamps[i]) {
                currentLyricIndex = i
                break
            }
        }
    }

    fun getSongById(id: Long): SongModel? = _playlist.firstOrNull { it.id == id }

    /** 播放或暂停。歌单为空或当前是试听轨道时不响应。 */
    suspend fun playOrPause() = onHost { playOrPauseImpl() }

    private suspend fun playOrPauseImpl() {
        if (_playlist.isEmpty() || !isMainTrackActive) return
        try {
            if (isPlaying) AppAudioPlayer.pause() else AppAudioPlayer.play()
        } catch (e: Throwable) {
            // 来电或其它应用占用音频会话时激活失败，不能变成未捕获的致命错误。
            DevLog.logger("playOrPause error: ${e.message}")
        }
    }

    /** 播放下一首。 */
    suspend fun playNext() = onHost { playNextImpl() }

    private suspend fun playNextImpl() {
        if (_playlist.isEmpty() || (AppAudioPlayer.currentTrack != null && !isMainTrackActive)) return
        if (AppAudioPlayer.queue.isEmpty()) {
            playIndex((_currentIndex + 1) % _playlist.size)
            return
        }
        AppAudioPlayer.next()
    }

    /** 播放上一首。 */
    suspend fun playPrevious() = onHost { playPreviousImpl() }

    private suspend fun playPreviousImpl() {
        if (_playlist.isEmpty() || (AppAudioPlayer.currentTrack != null && !isMainTrackActive)) return
        if (AppAudioPlayer.queue.isEmpty()) {
            playIndex((_currentIndex - 1 + _playlist.size) % _playlist.size)
            return
        }
        AppAudioPlayer.previous()
    }

    /** 停止播放，进度归零。 */
    suspend fun stop() = onHost { stopImpl() }

    private suspend fun stopImpl() {
        AppAudioPlayer.stop()
        position = 0L
        notifyState(force = true)
        notifyPosition(force = true)
    }

    /** 暂停播放（仅在播时）。 */
    suspend fun pause() = onHost { pauseImpl() }

    private suspend fun pauseImpl() {
        if (isPlaying) {
            AppAudioPlayer.pause()
            notifyState(force = true)
        }
    }

    /** 切换播放模式：列表循环 → 单曲循环 → 随机播放。 */
    fun togglePlayMode() {
        playMode = playMode.next
        AppAudioPlayer.setPlayMode(appPlayMode)
        notifyState(force = true)
    }

    /** 作废进行中的起播请求，停止并清空队列，再清业务字段（登出、音乐详情页调用）。 */
    fun cleanListPlay() {
        ++playRequest
        launch {
            AppAudioPlayer.stop(clearQueue = true)
            clearPlayerUi()
        }
    }

    /** 清除歌曲状态（歌单、下标、当前歌曲、进度、歌词）。 */
    fun clearPlayerUi() {
        _playlist.clear()
        _currentIndex = -1
        changeSongId = 0L
        oldSongId = -1L
        currentPlayAudio = null
        isPlaying = false
        isRotating = false
        position = 0L
        duration = 0L
        bufferedPosition = 0L
        _lyricContents.clear()
        lyricTimestamps.clear()
        currentLyricIndex = -1
        notifyState(navPlayerChanged = true)
        notifyPosition(force = true)
    }

    /** 设置播放进度（毫秒）。松手后立刻切歌时结果丢弃，避免把上一首进度写到新歌。 */
    fun seekTo(positionMs: Long) {
        val songId = changeSongId
        AppAudioPlayer.seek(positionMs)
        if (songId != changeSongId || isSwitchingSource) return
        position = positionMs
        notifyState(force = true)
        notifyPosition(force = true)
    }

    /** 按 0~1 的比例设置进度；时长为 0 时不响应。 */
    fun seekToPercent(percent: Double) {
        if (duration == 0L) return
        seekTo((percent * duration).roundToLong())
    }

    /** 设置播放列表：只保留生成完成（status 3）的歌曲并深拷贝，然后同步播放器队列。 */
    suspend fun setPlaylist(songs: List<SongModel>) = onHost { setPlaylistImpl(songs) }

    private suspend fun setPlaylistImpl(songs: List<SongModel>) {
        if (songs.isEmpty()) return
        val cur = currentSong
        if (cur != null) oldSongId = cur.id
        val list = songs.filter { it.musicGenerateStatus == 3 }
        if (list.isNotEmpty()) {
            _playlist.clear()
            list.forEach { _playlist.add(deepCopy(it)) }
            syncQueueWithPlaylist()
            notifyState(force = true)
        }
    }

    /**
     * 播放歌曲。
     * @param isDetail 是否来自详情页
     * @param type 'other' | 'nav'（从迷你播放条进详情）
     * @param songs 给出时替换播放列表（深拷贝，只保留 status 3）
     */
    suspend fun playSong(
        song: SongModel,
        isDetail: Boolean = false,
        type: String = "other",
        songs: List<SongModel>? = null,
    ) = onHost { playSongImpl(song, isDetail, type, songs) }

    private suspend fun playSongImpl(song: SongModel, isDetail: Boolean, type: String, songs: List<SongModel>?) {
        // 是否与当前歌曲相同：type == 'other' 且上次意图播放的是别的歌时需要重新播放
        var isSameSong = currentSong?.id == song.id
        if (isSameSong && oldSongId > -1L && type == "other") {
            isSameSong = oldSongId == song.id
        }
        oldSongId = song.id
        notifyState(navPlayerChanged = true)
        if (songs != null) {
            _playlist.clear()
            songs.forEach { if (it.musicGenerateStatus == 3) _playlist.add(deepCopy(it)) }
        }
        val index = _playlist.indexOfFirst { it.id == song.id }
        if (songs != null && index == -1) isSameSong = false
        if (index != -1) {
            // 更新播放信息（为 null 的字段保留原值）
            val cur = _playlist[index]
            _playlist[index] = cur.copy(
                musicConvertLyric = song.musicConvertLyric ?: cur.musicConvertLyric,
                musicAudioUrl = song.musicAudioUrl ?: cur.musicAudioUrl,
                musicCoverUrl = song.musicCoverUrl ?: cur.musicCoverUrl,
                userInfo = song.userInfo ?: cur.userInfo,
                musicTitle = song.musicTitle ?: cur.musicTitle,
            )
            val queue = AppAudioPlayer.queue
            if (isSameSong && index < queue.size && queue[index].id == "$SONG_PREFIX${song.id}") {
                AppAudioPlayer.updateTrack(index, trackFromSong(_playlist[index]))
            }
        }
        if (isSameSong) {
            if (songs != null) syncQueueWithPlaylist()
            // 详情页即使已在播同一首，也用本次歌曲重写歌词，避免缓冲仍是上一首
            val lyric = song.musicConvertLyric
            if (!lyric.isNullOrEmpty() && (isDetail || _lyricContents.isEmpty())) {
                parseLyricString(lyric)
                notifyState(force = true)
            }
            if (!isDetail) {
                // 非详情页点击同一首歌：切换播放 / 暂停
                playOrPause()
                return
            }
            // 详情页通过导航栏进入且当前未播放：不自动开始
            if (type == "nav" && !isPlaying) return
            // 已在播放中，无需重新开始
            if (isPlaying) return
        }

        if (index != -1) {
            playIndex(index)
        } else {
            // 不在播放列表中：追加并播放
            _playlist.add(deepCopy(song))
            playIndex(_playlist.size - 1)
        }
    }

    /** 重新排列播放队列。 */
    fun reorderPlaylist(oldIndex: Int, newIndex: Int) {
        if (oldIndex < 0 || oldIndex >= _playlist.size) return
        val song = _playlist.removeAt(oldIndex)
        _playlist.add(newIndex.coerceIn(0, _playlist.size), song)
        // 同步调整当前播放索引
        if (oldIndex == _currentIndex) {
            _currentIndex = newIndex
        } else if (oldIndex < _currentIndex && newIndex >= _currentIndex) {
            _currentIndex--
        } else if (oldIndex > _currentIndex && newIndex <= _currentIndex) {
            _currentIndex++
        }
        AppAudioPlayer.moveTrack(oldIndex, newIndex)
        notifyState(force = true)
    }

    /** 「创作同款」：保留创作参数，离开当前页（不在主框架时），打开音乐创作页。 */
    fun createSong(song: SongModel) {
        val data = JSONObject()
            .put("categoryId", song.musicCategoryId)
            .put("categoryName", song.musicCategory)
            .put("title", song.musicTitle)
            .put("lyrics", song.musicLyric)
            .put("prompt", song.musicPrompt)
            .put("musicCreativeMode", song.musicCreativeMode)
            .put("isInstrumental", song.instrumental != 0)
        createData = data
        val pager = currentPager()
        if (pager != null && !AppNav.isOnRoot()) pager.nav.pop()
        // 等当前页关闭后再打开创作页
        Svc.setTimeout(0) { WorksUi.openCreatePage(Routes.musicCreate, data) }
    }

    /** 列表变更同步：type 为 'like' | 'delete'。 */
    fun updateMusicList(type: String, song: SongModel) {
        if (type == "like") updatePlayList(song.id, song.isCollect)
        if (type == "delete") launch { handleSongDeletion(song) }
    }

    fun removeSongFromQueue(index: Int) {
        if (index < 0 || index >= _playlist.size) return
        _playlist.removeAt(index)
        if (index == _currentIndex) {
            _currentIndex = -1
        } else if (index < _currentIndex) {
            _currentIndex--
        }
        launch { AppAudioPlayer.removeTrackAt(index) }
        notifyState(force = true)
    }

    // ------------------------------------------------------------------ 内部

    private val appPlayMode: AppAudioPlayMode
        get() = when (playMode) {
            PlayMode.loop -> AppAudioPlayMode.loop
            PlayMode.single -> AppAudioPlayMode.single
            PlayMode.random -> AppAudioPlayMode.random
        }

    private fun trackFromSong(song: SongModel): AppAudioTrack {
        val url = song.musicAudioUrl ?: ""
        return AppAudioTrack(
            id = "$SONG_PREFIX${song.id}",
            uri = url,
            title = song.musicTitle ?: "",
            artworkUri = song.musicCoverUrl,
            preload = !url.lowercase().endsWith(".aac"),
        )
    }

    private fun deepCopy(song: SongModel): SongModel = SongModel.fromJson(song.toJson())

    /** 播放指定下标：每次都把整个歌单推给播放器作为队列。 */
    private suspend fun playIndex(index: Int) {
        if (index < 0 || index >= _playlist.size) return
        val request = ++playRequest

        _currentIndex = index
        val song = _playlist[index]
        changeSongId = song.id
        oldSongId = song.id

        // 立即触发 UI 联动（封面、歌词、动画），无需等待音频加载完成
        currentPlayAudio = song
        // stop() 会保留旧音源位置，新音源加载前进度条会停在上一首；这里先归零
        isSwitchingSource = true
        hitRecorded = false
        position = 0L
        duration = 0L
        _lyricContents.clear()
        lyricTimestamps.clear()
        currentLyricIndex = -1
        parseLyricString(song.musicConvertLyric)
        notifyState(navPlayerChanged = true)
        notifyPosition(force = true)

        if (request != playRequest) return
        try {
            AppAudioPlayer.setCompletionMode(AppAudioCompletionMode.queue)
            AppAudioPlayer.setPlayMode(appPlayMode)
            AppAudioPlayer.setQueue(_playlist.map { trackFromSong(it) }, initialIndex = index, autoPlay = true)
        } catch (e: Throwable) {
            DevLog.logger("playSong error: ${e.message}")
        }
        if (request == playRequest) endSourceSwitch(song.id)
        if (request != playRequest) return
        // 静默补拉可能在 setQueue 期间写回歌词：用列表里的最新值再解析一次
        reparseCachedLyric(index)
        notifyState(navPlayerChanged = true, force = true)
    }

    /** 切歌清空之后若列表里已经有对齐歌词，补解析一次。已经有句子或已经切走则不动。 */
    private fun reparseCachedLyric(index: Int) {
        if (_lyricContents.isNotEmpty()) return
        if (index < 0 || index >= _playlist.size || _currentIndex != index) return
        val lyric = _playlist[index].musicConvertLyric
        if (lyric == null || !lyricStringHasLines(lyric)) return
        parseLyricString(lyric)
    }

    /** 新音源就绪后恢复进度同步；期间又切了歌（id 不符）则保持屏蔽，交给新一次切歌复位。 */
    private fun endSourceSwitch(songId: Long) {
        if (!isSwitchingSource) return
        if (songId != changeSongId) return
        isSwitchingSource = false
        position = AppAudioPlayer.state.positionMs
        notifyState(force = true)
        notifyPosition(force = true)
    }

    /** 替换业务列表后，让播放器的下一首与页面展示的队列保持一致。 */
    private suspend fun syncQueueWithPlaylist() {
        val activeTrack = AppAudioPlayer.currentTrack
        if (activeTrack == null || !activeTrack.id.startsWith(SONG_PREFIX)) return
        val nextIds = _playlist.map { "$SONG_PREFIX${it.id}" }
        val currentIds = AppAudioPlayer.queue.map { it.id }
        if (nextIds == currentIds) return
        val activeIndex = nextIds.indexOf(activeTrack.id)
        if (activeIndex < 0) {
            AppAudioPlayer.stop(clearQueue = true)
            _currentIndex = -1
            changeSongId = 0L
            currentPlayAudio = null
            position = 0L
            duration = 0L
            notifyState(navPlayerChanged = true, force = true)
            notifyPosition(force = true)
            return
        }
        val request = ++playRequest
        val pos = AppAudioPlayer.state.positionMs
        val resume = AppAudioPlayer.state.isPlaying
        isSwitchingSource = true
        try {
            // 会重新加载音源，有一小段可闻的间隙（与参考工程一致）
            AppAudioPlayer.setQueue(_playlist.map { trackFromSong(it) }, initialIndex = activeIndex)
            if (request == playRequest) {
                if (pos > 0L) AppAudioPlayer.seek(pos)
                if (resume) AppAudioPlayer.play()
            }
        } finally {
            if (request == playRequest) {
                isSwitchingSource = false
                applyAudioState()
            }
        }
    }

    /** 歌曲删除后：从歌单和播放器队列移除，并按播放器当前轨道重新对齐下标。 */
    private suspend fun handleSongDeletion(deletedSong: SongModel) {
        val deletedIndex = _playlist.indexOfFirst { it.id == deletedSong.id }
        if (deletedIndex == -1) return
        _playlist.removeAt(deletedIndex)
        AppAudioPlayer.removeTrackById("$SONG_PREFIX${deletedSong.id}")
        if (_playlist.isEmpty()) {
            _currentIndex = -1
            changeSongId = 0L
            oldSongId = -1L
            currentPlayAudio = null
            _lyricContents.clear()
            lyricTimestamps.clear()
            currentLyricIndex = -1
            notifyState(navPlayerChanged = true)
            return
        }
        val activeTrack = AppAudioPlayer.currentTrack
        if (activeTrack != null && activeTrack.id.startsWith(SONG_PREFIX)) {
            val activeIndex = _playlist.indexOfFirst { "$SONG_PREFIX${it.id}" == activeTrack.id }
            if (activeIndex >= 0) {
                _currentIndex = activeIndex
                changeSongId = _playlist[activeIndex].id
                currentPlayAudio = _playlist[activeIndex]
            }
        }
        notifyState(navPlayerChanged = true)
    }
}
