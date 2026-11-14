package com.sondo.app.ui.works

import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.core.utils.isPagerAlive
import com.sondo.app.data.models.MvModel
import com.sondo.app.data.models.SongModel
import com.sondo.app.service.ConfigService
import com.sondo.app.service.MusicService
import com.sondo.app.service.MvService
import com.sondo.app.service.PlayerService
import com.sondo.app.service.UserStore
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable

/**
 * 页面级的「作品相关全局状态」镜像：播放器、分享 / 下载进行中集合、登录态与积分、优惠倒计时。
 *
 * 全局单例（PlayerService / MusicService / MvService / UserStore / ConfigService）不持有 observable，
 * 列表项如果各自订阅事件，长列表会注册成百上千个监听；所以每个页面只建一份镜像（[of]），
 * 本包的组件在 `attr { }` 里读它的响应式字段。页面销毁时事件监听随 BasePager 自动注销。
 */
class WorksState private constructor(val pager: BasePager) {
    // ------------------------------------------------------------ 播放器（Events.PLAYER_STATE）
    var currentSongId by pager.observable(-1L)
    var hasCurrentSong by pager.observable(false)
    var currentTitle by pager.observable("")
    var currentCover by pager.observable("")
    var currentStatus by pager.observable(0)
    var isPlaying by pager.observable(false)
    /** "idle" | "loading" | "buffering" | "ready" | "completed" */
    var processingState by pager.observable("idle")
    /** 0..1（Events.PLAYER_POSITION，仅在 [watchPosition] 之后更新） */
    var progress by pager.observable(0f)

    // ------------------------------------------------------------ 进行中集合
    var musicSharing by pager.observable<Set<String>>(emptySet())
    var musicDownloading by pager.observable<Set<String>>(emptySet())
    var mvSharing by pager.observable<Set<String>>(emptySet())
    var mvDownloading by pager.observable<Set<String>>(emptySet())

    // ------------------------------------------------------------ 账号
    var isLogin by pager.observable(false)
    var isSubscribed by pager.observable(false)
    var avatar by pager.observable("")
    var points by pager.observable(0)

    // ------------------------------------------------------------ 优惠倒计时
    var recallRemainingSeconds by pager.observable(0)
    var recallTimerText by pager.observable("")
    var remainingSeconds by pager.observable(0)
    var timerText by pager.observable("")
    var discountActive by pager.observable(false)

    // ------------------------------------------------------------ 操作面板当前条目
    /** 歌曲「更多」面板展示的歌曲（PlayerService.song，Events.PLAYER_SONG_CHANGED） */
    var actionSong by pager.observable<SongModel?>(null)
    /** MV「更多」面板展示的 MV（MvService.currentMv，Events.MV_CURRENT_CHANGED） */
    var currentMv by pager.observable<MvModel?>(null)

    private var positionWatched = false

    private fun bind() {
        applyPlayer(PlayerService.snapshot())
        syncBusy()
        syncUser()
        syncCountdown()
        pager.on(Events.PLAYER_STATE) { applyPlayer(it) }
        pager.on(Events.MUSIC_BUSY_CHANGED) {
            musicSharing = it.optJSONArray("sharing").toStringSet()
            musicDownloading = it.optJSONArray("downloading").toStringSet()
        }
        pager.on(Events.MV_BUSY_CHANGED) {
            mvSharing = it.optJSONArray("sharing").toStringSet()
            mvDownloading = it.optJSONArray("downloading").toStringSet()
        }
        pager.on(Events.USER_CHANGED) { syncUser() }
        pager.on(Events.POINTS_CHANGED) { syncUser() }
        pager.on(Events.OFFER_COUNTDOWN) { syncCountdown() }
        pager.on(Events.CONFIG_CHANGED) { syncCountdown() }
        actionSong = PlayerService.song
        currentMv = MvService.currentMv
        pager.on(Events.PLAYER_SONG_CHANGED) { json ->
            actionSong = json.optJSONObject("song")?.let { SongModel.fromJson(it) }
        }
        pager.on(Events.MV_CURRENT_CHANGED) { json -> currentMv = MvModel.fromJson(json) }
    }

    private fun applyPlayer(json: JSONObject) {
        val has = json.optBoolean("hasCurrentSong", false)
        hasCurrentSong = has
        currentSongId = if (has) json.optLong("currentSongId", -1L) else -1L
        val song = json.optJSONObject("currentSong")
        currentTitle = song?.optString("musicTitle", "") ?: ""
        currentCover = song?.optString("musicCoverUrl", "") ?: ""
        currentStatus = song?.optInt("musicGenerateStatus", 0) ?: 0
        isPlaying = json.optBoolean("isPlaying", false)
        processingState = json.optString("processingState", "idle")
        if (positionWatched) progress = json.optDouble("progress", 0.0).toFloat().coerceIn(0f, 1f)
    }

    private fun syncBusy() {
        musicSharing = LinkedHashSet(MusicService.sharingUrls)
        musicDownloading = LinkedHashSet(MusicService.downloadingUrls)
        mvSharing = LinkedHashSet(MvService.sharingUrls)
        mvDownloading = LinkedHashSet(MvService.downloadingUrls)
    }

    private fun syncUser() {
        isLogin = UserStore.isLogin
        isSubscribed = UserStore.isSubscribed
        avatar = UserStore.userInfo?.avatar ?: ""
        points = UserStore.points
    }

    private fun syncCountdown() {
        val cd = ConfigService.countdown
        recallRemainingSeconds = cd.recallRemainingSeconds
        recallTimerText = cd.recallTimerText
        remainingSeconds = cd.remainingSeconds
        timerText = cd.timerText
        discountActive = ConfigService.discountOpen && !ConfigService.mvDiscountModel.isTimeOut && cd.remainingSeconds > 0
    }

    /** 订阅高频进度（迷你播放器用）。 */
    fun watchPosition() {
        if (positionWatched) return
        positionWatched = true
        progress = PlayerService.progress.toFloat().coerceIn(0f, 1f)
        pager.on(Events.PLAYER_POSITION) {
            if (it.optBoolean("isMainTrack", true)) progress = it.optDouble("progress", 0.0).toFloat().coerceIn(0f, 1f)
        }
    }

    /**
     * 歌曲封面上播放指示的状态（SDAudioVisualizer.status）：'player' | 'loading' | 'pause'。
     * @param bufferingAsLoading MusicItemWidget 把 buffering 也算 loading；SongItem / SongCard 只看 loading
     */
    fun playIcon(songId: Long, bufferingAsLoading: Boolean = false): String {
        if (!hasCurrentSong || currentSongId != songId) return "player"
        val st = processingState
        if (st == "loading" || (bufferingAsLoading && st == "buffering")) return "loading"
        if (st == "ready" && isPlaying) return "pause"
        return "player"
    }

    companion object {
        private val states = HashMap<String, WorksState>()

        fun of(pager: BasePager): WorksState {
            states[pager.pagerId]?.let { return it }
            // 顺手清掉已销毁页面的镜像
            states.keys.filter { !isPagerAlive(it) }.forEach { states.remove(it) }
            val s = WorksState(pager)
            states[pager.pagerId] = s
            s.bind()
            return s
        }
    }
}

private fun JSONArray?.toStringSet(): Set<String> {
    val out = LinkedHashSet<String>()
    if (this == null) return out
    for (i in 0 until length()) out.add(optString(i, "") ?: "")
    return out
}

/** 组件所在页面的 [WorksState]。 */
val DeclarativeBaseView<*, *>.worksState: WorksState
    get() = WorksState.of(getPager() as BasePager)
