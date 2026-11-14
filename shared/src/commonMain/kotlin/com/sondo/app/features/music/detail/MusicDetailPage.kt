package com.sondo.app.features.music.detail

import com.sondo.app.features.square.SquareNav
import com.sondo.app.features.square.data.SquareProfileArgs
import com.sondo.app.base.AppEnv
import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.GlobalData
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.storage.Sp
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.EmailHelper
import com.sondo.app.core.utils.HostNative
import com.sondo.app.data.models.PlayMode
import com.sondo.app.data.models.SongModel
import com.sondo.app.data.models.jRaw
import com.sondo.app.data.models.toModel
import com.sondo.app.features.music.data.MusicDetailApi
import com.sondo.app.features.music.data.SongDetailEntity
import com.sondo.app.features.music.detail.widgets.MusicDetailBody
import com.sondo.app.features.music.detail.widgets.showPlayerQueueSheet
import com.sondo.app.ui.works.showReportTagOptions
import com.sondo.app.service.ConfigService
import com.sondo.app.service.LyricLine
import com.sondo.app.service.MusicItemType
import com.sondo.app.service.MusicService
import com.sondo.app.service.PlayerService
import com.sondo.app.service.UserStore
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.ui.dialog.showScoreDialog
import com.sondo.app.ui.overlay.DialogHandle
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.ListView
import com.tencent.kuikly.core.views.PageListView

/**
 * 音乐播放详情页（Dart: features/music/presentation/pages/detail/detail_page.dart + MusicDetailController），从下至上弹出。
 *
 * 参数：`{id: Long|String, original: 来源页面名（"push" 表示推送进入，一律拉详情）, type?: "other"|"nav"}`；
 * 没有 id 时（外链）读 `GlobalData.externalParams["id"]`。
 */
@Page(Routes.musicDetail)
internal class MusicDetailPage : BasePager() {

    // ── 页面数据 ──
    var musicDetail by observable<SongModel?>(null)
    var isLoading by observable(false)

    /** 本页已经为这些歌曲请求过详情。缺歌词只补一次。 */
    private val lyricFetchedIds = HashSet<Long>()

    /** 来源页标识。 */
    var original = ""

    var isMyMusic = false

    // ── 视图切换（唱片 ↔ 歌词）──
    var isLyricsView by observable(false)

    // ── 首次引导遮罩 ──
    var showFirstTimeMask by observable(false)

    // ── 播放器镜像 ──
    val queue by observableList<SongModel>()
    var currentIndex by observable(-1)
    var isPlaying by observable(false)
    var processingState by observable("idle")
    var playMode by observable(PlayMode.loop)
    var positionMs by observable(0L)
    var durationMs by observable(0L)
    var currentLyricIndex by observable(0)
    val lyricLines by observableList<LyricLine>()

    // ── 进度条拖动 ──
    var isUserDragging by observable(false)
    var dragMs by observable(0L)

    // ── 唱针 / 黑胶 ──
    /** 唱针是否落下（-60° 抬起 ↔ -38° 落下，0.3s easeInOut）。 */
    var tonearmDown by observable(false)
    /** 当前唱片旋转角度（度，10 秒一圈）。 */
    var vinylAngle by observable(0f)
    private var tonearmAnimating = false
    private var tonearmWantsDown = false
    private var vinylRotating = false
    private var vinylGeneration = 0

    // ── 唱片翻页 ──
    var pageListRef: ViewRef<PageListView<*, *>>? = null
    private var isPageChanging = false

    // ── 歌词滚动 ──
    var lyricsListRef: ViewRef<ListView<*, *>>? = null
    /** 歌词行在列表内容里的 y / 高（自动滚动定位用）。 */
    val lyricFrames = HashMap<Int, Pair<Float, Float>>()
    private var isUserScrollingLyrics = false
    private var lyricScrollTimer: String? = null
    private var pendingLyricReset = false
    private var lyricScrollGeneration = 0
    private var lyricSignature = ""

    // ── 上 / 下一首节流 ──
    private var prevNextThrottleActive = false

    private var lastAudioId = -1L
    private var disposed = false
    var queueSheet: DialogHandle? = null

    override fun pageBackgroundColor(): Color = Color.BLACK

    // ================================================================== 生命周期

    override fun created() {
        super.created()
        lastAudioId = PlayerService.currentPlayAudio?.id ?: -1L
        applyState(PlayerService.snapshot(), initial = true)
        refreshLyrics(force = true)
        // 进入时若已在播放，唱针直接落下并启动旋转
        if (PlayerService.isPlaying) {
            tonearmDown = true
            startVinylRotation()
        }
        on(Events.PLAYER_STATE) { applyState(it, initial = false) }
        on(Events.PLAYER_POSITION) { applyPosition(it) }
        on(Events.MUSIC_LIST_CHANGED) { data ->
            val item = data.optJSONObject("item") ?: return@on
            updateMusic(data.optString("type", ""), SongModel.fromJson(item))
        }
        checkFirstTimeMask()
        initData()
        postDelayed(2000) { showScore() }
    }

    override fun pageWillDestroy() {
        disposed = true
        lyricScrollGeneration++
        vinylRotating = false
        vinylGeneration++
        super.pageWillDestroy()
    }

    // ================================================================== 播放器镜像

    private fun applyState(json: JSONObject, initial: Boolean) {
        val wasPlaying = isPlaying
        isPlaying = json.optBoolean("isPlaying", false)
        processingState = json.optString("processingState", "idle")
        val modeIndex = json.optInt("playModeIndex", 0)
        playMode = PlayMode.values().getOrElse(modeIndex) { PlayMode.loop }
        currentIndex = json.optInt("currentIndex", -1)
        durationMs = json.optLong("durationMs", 0L)
        if (!isUserDragging) positionMs = json.optLong("positionMs", 0L)
        syncQueue()
        if (initial) {
            currentLyricIndex = json.optInt("currentLyricIndex", 0)
            return
        }
        // 歌曲切换
        val audio = PlayerService.currentPlayAudio
        if (audio != null && audio.id != lastAudioId) {
            lastAudioId = audio.id
            onCurrentAudioChanged(audio)
        }
        refreshLyrics(force = false)
        // 播放状态 → 唱针
        if (wasPlaying != isPlaying) {
            if (isPlaying) lowerTonearm() else liftTonearm()
        }
        onLyricIndex(json.optInt("currentLyricIndex", currentLyricIndex))
    }

    private fun applyPosition(json: JSONObject) {
        if (!json.optBoolean("isMainTrack", true)) return
        if (!isUserDragging) positionMs = json.optLong("positionMs", 0L)
        durationMs = json.optLong("durationMs", durationMs)
        onLyricIndex(json.optInt("currentLyricIndex", currentLyricIndex))
    }

    /** 播放列表镜像：id 序列或内容有变化时整体替换。 */
    private fun syncQueue() {
        val list = PlayerService.playlist
        var same = list.size == queue.size
        if (same) {
            for (i in list.indices) {
                if (list[i] != queue[i]) {
                    same = false
                    break
                }
            }
        }
        if (same) return
        queue.clear()
        queue.addAll(list)
    }

    /** 歌词镜像：歌曲或句数变化时重建（PlayerService.lyricContents 不是响应式的）。 */
    fun refreshLyrics(force: Boolean) {
        val lines = PlayerService.lyricContents
        val sig = "${PlayerService.changeSongId}|${lines.size}|${lines.firstOrNull()?.lyric}|${lines.lastOrNull()?.lyric}"
        if (!force && sig == lyricSignature) return
        lyricSignature = sig
        lyricFrames.clear()
        lyricLines.clear()
        lyricLines.addAll(lines)
    }

    private fun onLyricIndex(index: Int) {
        if (index == currentLyricIndex) return
        currentLyricIndex = index
        followLyric(index)
    }

    // ================================================================== 视图切换 / 引导

    fun toggleView() {
        if (disposed) return
        isLyricsView = !isLyricsView
        if (!isLyricsView) {
            // 切回封面：作废已排队的歌词滚动
            lyricScrollGeneration++
            postDelayed(50) {
                if (disposed) return@postDelayed
                syncTonearmState()
                ensureVinylRotating()
            }
        }
    }

    private fun checkFirstTimeMask() {
        val shown = Sp.getBool(StorageKey.playerLyricsGuideShown, false)
        if (!shown) showFirstTimeMask = true
    }

    fun dismissFirstTimeMask() {
        showFirstTimeMask = false
        Sp.setBool(StorageKey.playerLyricsGuideShown, true)
        isLyricsView = true
    }

    // ================================================================== 唱针 / 黑胶

    private fun liftTonearm() {
        if (disposed) return
        tonearmWantsDown = false
        if (tonearmAnimating) return
        if (!tonearmDown) return
        tonearmAnimating = true
        stopVinylRotation()
        tonearmDown = false
        postDelayed(300) {
            if (disposed) return@postDelayed
            tonearmAnimating = false
            applyPendingTonearm()
        }
    }

    private fun lowerTonearm() {
        if (disposed) return
        tonearmWantsDown = true
        if (tonearmAnimating) return
        if (tonearmDown) {
            // 唱针已落下，确保黑胶在转
            ensureVinylRotating()
            return
        }
        tonearmAnimating = true
        tonearmDown = true
        postDelayed(300) {
            if (disposed) return@postDelayed
            tonearmAnimating = false
            applyPendingTonearm()
        }
    }

    /** 当前唱针动画结束后补上期间错过的抬起 / 落下。 */
    private fun applyPendingTonearm() {
        if (disposed) return
        if (tonearmWantsDown) {
            lowerTonearm()
            return
        }
        if (tonearmDown) liftTonearm()
    }

    private fun syncTonearmState() {
        if (disposed) return
        if (PlayerService.isPlaying) lowerTonearm() else liftTonearm()
    }

    private fun startVinylRotation() {
        if (disposed || vinylRotating) return
        vinylRotating = true
        val gen = ++vinylGeneration
        tickVinyl(gen)
    }

    /** 10 秒一圈：每 50ms 转 1.8°。 */
    private fun tickVinyl(gen: Int) {
        if (disposed || gen != vinylGeneration || !vinylRotating) return
        vinylAngle += 1.8f
        postDelayed(50) { tickVinyl(gen) }
    }

    private fun stopVinylRotation() {
        vinylRotating = false
        vinylGeneration++
    }

    private fun ensureVinylRotating() {
        if (disposed || !PlayerService.isPlaying) return
        startVinylRotation()
    }

    // ================================================================== 唱片翻页

    /** 用户开始拖动唱片时抬起唱针。 */
    fun onPageScrollStart() {
        if (disposed) return
        liftTonearm()
    }

    /** 拖动结束后延迟同步唱针。 */
    fun onPageScrollEnd() {
        if (disposed) return
        postDelayed(100) { syncTonearmState() }
    }

    /** 单曲循环模式下禁止左右滑动切歌。 */
    fun canChangePage(): Boolean = playMode != PlayMode.single

    fun onPageChanged(index: Int) {
        val list = PlayerService.playQueueDataSource
        if (index < 0 || index >= list.size) return
        isPageChanging = true
        val song = list[index]
        // 只在切到不同歌曲时 playSong，避免恢复位置时误触暂停
        if (song.id != PlayerService.currentSong?.id) {
            PlayerService.launch { PlayerService.playSong(song) }
            musicDetail = song
            isMyMusic = UserStore.userInfo?.id == song.musicUid
        }
        postDelayed(400) { isPageChanging = false }
    }

    private fun onCurrentAudioChanged(newSong: SongModel) {
        if (disposed) return
        // 停转并复位，等新歌开始播放后由唱针同步重新开始
        stopVinylRotation()
        vinylAngle = 0f
        // 作废切歌前排队的滚动，下一帧跳回顶部
        lyricScrollGeneration++
        pendingLyricReset = true
        scheduleLyricJumpToTop(lyricScrollGeneration)

        musicDetail = newSong
        isMyMusic = UserStore.userInfo?.id == newSong.musicUid
        maybeSilentFetchLyric(newSong)

        if (!isPageChanging) {
            val index = PlayerService.currentPlayQueueIndex
            postDelayed(16) {
                if (disposed) return@postDelayed
                if (index < 0 || index >= PlayerService.playQueueDataSource.size) return@postDelayed
                pageListRef?.view?.scrollToPageIndex(index, false)
            }
        }
        postDelayed(350) {
            if (disposed) return@postDelayed
            syncTonearmState()
        }
    }

    // ================================================================== 歌词滚动

    private fun canFollowLyrics(): Boolean = !disposed && isLyricsView && lyricsListRef?.view != null

    fun onLyricsScrollStart() {
        isUserScrollingLyrics = true
        lyricScrollTimer?.let { com.tencent.kuikly.core.timer.clearTimeout(it) }
        lyricScrollTimer = null
    }

    fun onLyricsScrollEnd() {
        lyricScrollTimer?.let { com.tencent.kuikly.core.timer.clearTimeout(it) }
        lyricScrollTimer = postDelayed(500) {
            isUserScrollingLyrics = false
            lyricScrollTimer = null
        }
    }

    private fun scheduleLyricJumpToTop(generation: Int) {
        postDelayed(16) {
            if (generation != lyricScrollGeneration || !canFollowLyrics()) return@postDelayed
            lyricsListRef?.view?.setContentOffset(0f, 0f, false)
            clearLyricResetNextFrame(generation)
        }
    }

    private fun clearLyricResetNextFrame(generation: Int) {
        postDelayed(16) {
            if (generation != lyricScrollGeneration) return@postDelayed
            pendingLyricReset = false
        }
    }

    /** 自动滚动到当前句：对齐比例随进度从 0 到 0.85（ScrollablePositionedList alignment）。 */
    private fun followLyric(index: Int) {
        if (isUserScrollingLyrics) return
        if (disposed || !isLyricsView) return
        if (index < 0 || lyricLines.size <= 5) return
        val generation = lyricScrollGeneration
        postDelayed(16) {
            if (generation != lyricScrollGeneration || !canFollowLyrics()) return@postDelayed
            val list = lyricsListRef?.view ?: return@postDelayed
            if (pendingLyricReset) {
                list.setContentOffset(0f, 0f, false)
                clearLyricResetNextFrame(generation)
                return@postDelayed
            }
            val total = lyricLines.size
            val progress = if (total <= 1) 0f else (index.toFloat() / (total - 1)).coerceIn(0f, 0.85f)
            val frame = lyricFrames[index] ?: return@postDelayed
            val viewport = list.frame.height
            val contentH = list.contentView?.frame?.height ?: return@postDelayed
            val maxOffset = (contentH - viewport).coerceAtLeast(0f)
            val target = (frame.first - progress * viewport).coerceIn(0f, maxOffset)
            list.setContentOffset(0f, target, true)
        }
    }

    // ================================================================== 数据

    private fun initData() {
        val params = pagerData.params
        if (params.has("id")) {
            original = params.optString("original", "/").ifEmpty { "/" }
            val type = params.optString("type", "other").ifEmpty { "other" }
            val id = parseId(params.jRaw("id"))
            val song = PlayerService.getSongById(id)
            // 推送进入不信播放列表缓存，一律拉详情
            if (original != "push" && song != null) {
                musicDetail = song
                isMyMusic = UserStore.userInfo?.id == song.musicUid
                PlayerService.launch { PlayerService.playSong(song, isDetail = true, type = type) }
                // 已在播同一首时不会触发切歌，这里再判一次补歌词
                maybeSilentFetchLyric(song)
            } else {
                getMusicDetail(id, true)
            }
        } else {
            // 外链 id 可能非数字，解析失败回落 0
            getMusicDetail(GlobalData.externalParams["id"]?.toLongOrNull() ?: 0L, true)
        }
    }

    private fun parseId(raw: Any?): Long = when (raw) {
        is Number -> raw.toLong()
        is String -> raw.toLongOrNull() ?: 0L
        else -> 0L
    }

    fun getMusicDetail(id: Long, isOpen: Boolean) {
        // 整页详情会带回歌词，先占住 id，避免随后再静默请求同一接口
        lyricFetchedIds.add(id)
        isLoading = true
        showLoading()
        PlayerService.parseLyrics(JSONArray())
        refreshLyrics(force = true)
        launch {
            val result = MusicDetailApi.getMusicDetail(id)
            if (disposed) {
                hideLoading()
                return@launch
            }
            result.onSuccess { data ->
                isLoading = false
                hideLoading()
                // 优先用 lyricDetail，避免切歌时屏幕上还是上一首的歌词
                val detail = songWithAlignedLyric(data)
                musicDetail = detail
                isMyMusic = UserStore.userInfo?.id == detail.musicUid
                PlayerService.launch { PlayerService.playSong(detail, isDetail = true, type = "other") }
                // 部分状态在 playSong 异步完成后才稳定，延迟补刷一次
                postDelayed(500) {
                    if (!disposed) {
                        applyState(PlayerService.snapshot(), initial = false)
                        refreshLyrics(force = true)
                    }
                }
            }.onFailure {
                isLoading = false
                hideLoading()
                if (isOpen && !disposed) nav.pop()
            }
        }
    }

    /** 列表接口通常不带对齐歌词：非纯音乐且缺歌词时静默补一次。 */
    private fun maybeSilentFetchLyric(song: SongModel?) {
        if (disposed || song == null) return
        if (song.instrumental == 1 || hasAlignedLyric(song.musicConvertLyric)) return
        if (!lyricFetchedIds.add(song.id)) return
        fetchLyricSilently(song.id)
    }

    private fun fetchLyricSilently(id: Long) {
        launch {
            val result = MusicDetailApi.getMusicDetail(id)
            if (disposed) return@launch
            result.onSuccess { data ->
                val detail = songWithAlignedLyric(data)
                val lyric = detail.musicConvertLyric
                if (lyric == null || !PlayerService.lyricStringHasLines(lyric)) return@onSuccess
                // 切走也写入播放列表，再切回来直接解析
                PlayerService.cacheConvertLyric(id, lyric)
                val current = musicDetail
                if (current == null || current.id != id) return@onSuccess
                try {
                    PlayerService.parseLyrics(JSONArray(lyric))
                } catch (e: Throwable) {
                    DevLog.logger("parseLyrics error: ${e.message}")
                }
                musicDetail = current.copy(musicConvertLyric = lyric)
                refreshLyrics(force = true)
            }.onFailure { e ->
                DevLog.logger("silent lyric fetch error: ${e.message}")
            }
        }
    }

    /** lyricDetail 优先；解不出句子时退回 musicConvertLyric。 */
    private fun songWithAlignedLyric(data: SongDetailEntity): SongModel {
        val entity = data.musicDetail
        val detail = entity.copy(userInfo = data.user ?: entity.userInfo).toModel()
        val lyricDetail = data.lyricDetail
        if (lyricDetail == null || lyricDetail.length() == 0) return detail
        val encoded = lyricDetail.toString()
        if (!PlayerService.lyricStringHasLines(encoded)) return detail
        return detail.copy(musicConvertLyric = encoded)
    }

    private fun hasAlignedLyric(lyric: String?): Boolean {
        if (lyric == null) return false
        val trimmed = lyric.trim()
        return trimmed.isNotEmpty() && trimmed != "[]"
    }

    /** 按钮切歌后解析当前 musicDetail 的歌词。 */
    private fun tryParseLyrics() {
        try {
            val lyric = musicDetail?.musicConvertLyric
            if (!lyric.isNullOrEmpty()) PlayerService.parseLyrics(JSONArray(lyric))
        } catch (e: Throwable) {
            DevLog.logger("parseLyrics error: ${e.message}")
        }
        refreshLyrics(force = true)
    }

    // ================================================================== 播放控制

    fun toProfile(userId: Long) {
        // 栈内已有 Profile 则回到它并重载（Dart SquareProfileController.open）
        SquareNav.openProfile(this, SquareProfileArgs(userId, "music"))
    }

    fun playOrPause() {
        PlayerService.launch { PlayerService.playOrPause() }
    }

    /** 500ms 内重复点击只执行一次。 */
    private fun throttled(action: () -> Unit) {
        if (prevNextThrottleActive) return
        prevNextThrottleActive = true
        action()
        postDelayed(500) { prevNextThrottleActive = false }
    }

    fun playNext() = throttled {
        launch {
            PlayerService.playNext()
            if (disposed) return@launch
            musicDetail = PlayerService.currentSong
            isMyMusic = UserStore.userInfo?.id == musicDetail?.musicUid
            tryParseLyrics()
        }
    }

    fun playPrevious() = throttled {
        launch {
            PlayerService.playPrevious()
            if (disposed) return@launch
            musicDetail = PlayerService.currentSong
            isMyMusic = UserStore.userInfo?.id == musicDetail?.musicUid
            tryParseLyrics()
        }
    }

    fun togglePlayMode() {
        PlayerService.togglePlayMode()
    }

    fun onSeekStart(ms: Float) {
        isUserDragging = true
        dragMs = ms.toLong()
    }

    fun onSeek(ms: Float) {
        dragMs = ms.toLong()
    }

    fun onSeekEnd(ms: Float) {
        isUserDragging = false
        positionMs = ms.toLong()
        if (durationMs > 0L) PlayerService.seekToPercent((ms / durationMs).toDouble().coerceIn(0.0, 1.0))
    }

    fun openPlayQueue() {
        queueSheet = showPlayerQueueSheet()
    }

    fun reorderQueue(oldIndex: Int, newIndex: Int) {
        PlayerService.reorderPlaylist(oldIndex, newIndex)
        syncQueue()
    }

    /** 点击队列中的歌曲：切换播放并关闭弹层；点当前歌曲则播放 / 暂停。 */
    fun playSongFromQueue(index: Int) {
        val list = PlayerService.playlist
        if (index < 0 || index >= list.size) return
        val song = list[index]
        if (song.id == PlayerService.currentSong?.id) {
            playOrPause()
            return
        }
        PlayerService.launch { PlayerService.playSong(song) }
        queueSheet?.dismiss()
    }

    fun removeFromQueue(index: Int) {
        PlayerService.removeSongFromQueue(index)
        syncQueue()
        val current = PlayerService.currentPlayQueueIndex
        if (current >= 0) pageListRef?.view?.scrollToPageIndex(current, false)
        toast("removed_from_queue".tr)
    }

    // ================================================================== 歌词复制 / 收藏 / 分享 / 举报

    fun copyLyrics() {
        try {
            val lines = PlayerService.lyricContents
            if (lines.isEmpty()) return
            HostNative.app.copyToClipboard(lines.joinToString("\n") { it.lyric })
            if (AppEnv.isAndroid) {
                // Android 13 及以上系统自带复制提示
                val sdk = HostNative.app.getDeviceInfo().optInt("sdkInt", 0)
                if (sdk < 32) toast("copy_to_clipboard".tr)
            } else {
                toast("copy_to_clipboard".tr)
            }
        } catch (e: Throwable) {
            toast("copy_failed".tr)
        }
    }

    fun toggleFavorite() {
        val ctx = this
        launch {
            var isLogin = UserStore.isLogin
            if (!isLogin) isLogin = UserStore.showLoginDialog(ctx)
            if (!isLogin || disposed) return@launch
            val song = musicDetail ?: return@launch
            MusicService.onTap(MusicItemType.like, song, emptyList())
            // 乐观更新
            musicDetail = song.copy(isCollect = !song.isCollect)
        }
    }

    private fun updateMusic(type: String, item: SongModel) {
        val cur = musicDetail ?: return
        if (cur.id != item.id) return
        if (type == "like") musicDetail = cur.copy(isCollect = item.isCollect, isCollectNum = item.isCollectNum)
    }

    fun share() {
        val song = musicDetail ?: return
        if (song.musicAudioUrl.isNullOrEmpty()) return
        MusicService.onTap(MusicItemType.share, song, emptyList())
    }

    fun openReportSong() {
        val ctx = this
        if (musicDetail == null) return
        launch {
            var isLogin = true
            if (!UserStore.isLogin) isLogin = UserStore.showLoginDialog(ctx)
            if (!isLogin || disposed) return@launch
            ctx.showReportTagOptions { value ->
                val song = ctx.musicDetail
                val content = listOf(
                    "${"music_id".tr}: ${song?.id}",
                    "${"report_reason".tr}: $value",
                    "report_email_hint".tr,
                )
                ctx.launch { EmailHelper.sendEmail(content = content.joinToString("\n"), userId = UserStore.userInfo?.id) }
            }
        }
    }

    /** 评分引导（Dart DialogScore.show）：只弹一次，受远程开关控制。 */
    private fun showScore() {
        if (disposed) return
        if (AppEnv.isIOS && AppEnv.isDebug) return
        if (Sp.getBool(StorageKey.isShowRating, false)) return
        if (!ConfigService.isCommentOpen) return
        // 无论结果如何先落标记
        Sp.setBool(StorageKey.isShowRating, true)
        launch {
            if (showScoreDialog()) {
                UmengUtils.logEvent("rating_continue")
                try {
                    // 评分界面经本页发起（Android 上挂在根页面会被压在后面）
                    acquireModule<com.sondo.app.nativeapi.SDAppModule>(com.sondo.app.nativeapi.SDAppModule.MODULE_NAME).requestReview()
                    Sp.setBool(StorageKey.isShowRating, true)
                } catch (e: Throwable) {
                    UmengUtils.logEvent("rating_continue_error")
                }
            }
        }
    }

    override fun content(): ViewBuilder {
        val ctx = this
        return { MusicDetailBody(ctx) }
    }
}
