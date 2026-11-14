package com.sondo.app.features.edit_video

import com.sondo.app.base.BasePager
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.constants.asset
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.CancelToken
import com.sondo.app.core.utils.DevLog
import com.sondo.app.data.models.ShotHistory
import com.sondo.app.features.edit_video.data.EditVideoRepository
import com.sondo.app.features.edit_video.state.parseClockMs
import com.sondo.app.features.edit_video.state.parseShotTimestampMs
import com.sondo.app.features.edit_video.widgets.EvText
import com.sondo.app.features.edit_video.widgets.P500
import com.sondo.app.features.edit_video.widgets.P600
import com.sondo.app.features.edit_video.widgets.PausedPlayButton
import com.sondo.app.features.edit_video.widgets.PlayGlyph
import com.sondo.app.features.edit_video.widgets.Spinner
import com.sondo.app.nativeapi.SDVideo
import com.sondo.app.nativeapi.SDVideoState
import com.sondo.app.nativeapi.SDVideoView
import com.sondo.app.service.MergeMode
import com.sondo.app.service.VideoMergerService
import com.sondo.app.service.audio.AppAudioOutputCoordinator
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDMusicLoading
import com.sondo.app.ui.atoms.SafeBottom
import com.sondo.app.ui.atoms.VGap
import com.sondo.app.ui.nav.SDNavBar
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.velseif
import com.tencent.kuikly.core.directives.vforIndex
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.views.Canvas
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.List
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import kotlin.math.roundToLong

/**
 * 分镜历史（Dart `ShotHistoryPage` + `ShotHistoryController`）：预览历史版本并还原到当前分镜。
 * 列表固定拉第一页 30 条（同参考工程）；预览前把历史视频与歌曲片段端上混流。
 *
 * 参数（Dart `ShotHistoryArgs`）：
 * - `id: Long` 分镜 id（= shotCustomId，必填；为 0 时不请求，停在空态）
 * - `videoUrl: String` 当前分镜视频地址，用于默认选中同一条历史
 * - `genMode: Int` 生成模式；非 2 时分镜时间戳叠加音乐裁剪起点（参考工程此处传 0，等于不叠加）
 * - `audioUri: String` 歌曲地址
 * - `subDurationScope: String` 音乐裁剪区间 `mm:ss,mm:ss`
 *
 * 返回结果：还原成功 `nav.pop({"shot": MvShot json})`（后端返回的新分镜）；直接返回无结果。
 */
@Page(Routes.shotHistory)
internal class ShotHistoryPage : BasePager() {

    /** 当前分镜的历史版本，接口顺序即底部缩略图顺序。 */
    private val shots by observableList<ShotHistory>()
    /** 底部缩略图选中下标；找不到当前视频时落在 0。 */
    private var selectedIndex by observable(0)
    private var genMode = 0
    private var audioUri = ""
    private var musicStartMs = 0L
    private var musicEndMs = 0L
    private var isLoading by observable(false)

    /** 合并完成后的本地播放地址；空表示尚未可播。 */
    private var currentVideoUrl by observable("")
    private var isVideoInitialized by observable(false)
    private var isVideoError by observable(false)
    private var isVideoLoading by observable(false)
    private var isPaused by observable(false)
    /** 合并进度文案，如 `40%`；完成后清空。 */
    private var videoProgress by observable("")

    private var currentCancelToken: CancelToken? = null
    private var videoRef: ViewRef<SDVideoView>? = null
    private var nativePlaying = false
    private var closed = false
    private val audioOwner = Any()
    private var detachAudio: (() -> Unit)? = null

    override fun created() {
        super.created()
        detachAudio = AppAudioOutputCoordinator.attach(audioOwner, { nativePlaying }, { true }) {
            videoRef?.view?.pause()
            nativePlaying = false
            isPaused = true
        }
        val args = pagerData.params
        genMode = args.optInt("genMode", 0)
        audioUri = args.optString("audioUri", "")
        parseMusicStartDuration(args.optString("subDurationScope", ""))
        val id = args.optLong("id", 0L)
        // 只接受带 id 的参数；否则不请求，页面停在空态
        if (id != 0L) getShotHistoryList(id, args.optString("videoUrl", ""))
    }

    override fun pageDidDisappear() {
        super.pageDidDisappear()
        if (nativePlaying) {
            videoRef?.view?.pause()
            nativePlaying = false
            isPaused = true
            AppAudioOutputCoordinator.release(audioOwner)
        }
    }

    override fun pageWillDestroy() {
        // 离开页面时中断尚未完成的音视频合并，并断开播放器引用
        closed = true
        currentCancelToken?.cancel()
        disposeVideo()
        detachAudio?.invoke()
        super.pageWillDestroy()
    }

    /** 拉取历史列表，并默认选中与当前视频地址相同的一条。 */
    private fun getShotHistoryList(id: Long, videoUrl: String) {
        isLoading = true
        launch {
            val result = EditVideoRepository.fetchShotHistory(id)
            if (closed) return@launch
            val list = result.getOrNull()
            if (list == null) {
                toast("request_failed".tr)
            } else {
                shots.clear()
                shots.addAll(list)
                if (list.isNotEmpty()) {
                    val idx = list.indexOfFirst { it.mvShotVideo == videoUrl }
                    selectedIndex = if (idx == -1) 0 else idx
                    initVideo(list[selectedIndex])
                }
            }
            isLoading = false
        }
    }

    private fun parseMusicStartDuration(scope: String) {
        DevLog.logger("subDurationScope: $scope")
        if (scope.isEmpty() || !scope.contains(",")) return
        val parts = scope.split(",")
        if (parts.size < 2) return
        musicStartMs = parseClockMs(parts[0])
        musicEndMs = parseClockMs(parts[1])
    }

    /** 切换历史版本并重新合并预览。点中已选项时忽略。 */
    private fun selectShot(index: Int) {
        if (index == selectedIndex) return
        if (index < 0 || index >= shots.size) return
        selectedIndex = index
        disposeVideo()
        initVideo(shots[index])
    }

    private fun onVideoControllerReady() {
        if (closed) return
        if (!isPageVisible) {
            videoRef?.view?.pause()
            isPaused = true
        } else {
            isPaused = false
            launch {
                val t = AppAudioOutputCoordinator.claim(audioOwner)
                if (!isPaused && AppAudioOutputCoordinator.isCurrent(audioOwner, t)) videoRef?.view?.play()
            }
        }
        isVideoInitialized = true
    }

    private fun initVideo(shot: ShotHistory) {
        val url = shot.mvShotVideo
        if (url.isEmpty()) {
            isVideoError = true
            return
        }
        isVideoInitialized = false
        isVideoError = false
        isVideoLoading = true
        // 切到下一条时停掉上一次合并，过期回调不再写播放状态
        currentCancelToken?.cancel()
        val cancelToken = CancelToken()
        currentCancelToken = cancelToken
        launch {
            try {
                // 旧页预览传入零，不把裁剪起点叠到时间轴上
                val videoUrl = handleEmptyShot(shot, 0L, cancelToken, genMode != 2)
                if (closed || cancelToken.isCancelled) return@launch
                if (videoUrl.isNullOrEmpty()) {
                    isVideoError = true
                } else {
                    currentVideoUrl = videoUrl
                }
            } catch (e: Throwable) {
                DevLog.logger("ShotHistory video init error: ${e.message}")
                if (!closed && !cancelToken.isCancelled) isVideoError = true
            } finally {
                if (!closed && !cancelToken.isCancelled) isVideoLoading = false
            }
        }
    }

    /** 播放失败后重试当前选中版本。 */
    private fun reInitVideo() {
        if (shots.isEmpty()) return
        val index = selectedIndex
        if (index < 0 || index >= shots.size) return
        disposeVideo()
        initVideo(shots[index])
    }

    private suspend fun handleEmptyShot(
        shot: ShotHistory,
        musicStart: Long,
        cancelToken: CancelToken,
        isMergeStartDuration: Boolean,
    ): String? {
        var start = parseShotTimestampMs(shot.timestamp)
        if (isMergeStartDuration) start += musicStart
        val shotDuration = if (shot.durationV1 > 0) shot.durationV1 else shot.duration.toDouble()
        val end = start + (shotDuration * 1000).roundToLong()
        return try {
            val outputPath = VideoMergerService.merge(
                videoUrl = shot.mvShotVideo,
                audioUrl = audioUri,
                outputFileName = "${shot.id}_merged.mp4",
                mode = MergeMode.replace,
                startMs = start,
                endMs = end,
                cancelToken = cancelToken,
                progressCallback = { step, progress ->
                    if (!cancelToken.isCancelled && !closed) {
                        if (progress == null) {
                            DevLog.logger(step)
                        } else {
                            val text = "${(progress * 100).roundToLong()}%"
                            if (text != "100%" && videoProgress != text) videoProgress = text
                            // 合并完成前离开本页要补暂停
                            if (!isPageVisible && nativePlaying) {
                                videoRef?.view?.pause()
                                isPaused = true
                            }
                        }
                    }
                },
            )
            if (!closed) videoProgress = ""
            outputPath
        } catch (e: Throwable) {
            DevLog.logger("视频合并失败: ${e.message}")
            null
        }
    }

    private fun togglePlayPause() {
        val player = videoRef?.view ?: return
        if (!isVideoInitialized) return
        if (nativePlaying || !isPaused) {
            player.pause()
            nativePlaying = false
            isPaused = true
            AppAudioOutputCoordinator.release(audioOwner)
        } else {
            isPaused = false
            launch {
                val t = AppAudioOutputCoordinator.claim(audioOwner)
                if (!isPaused && AppAudioOutputCoordinator.isCurrent(audioOwner, t)) player.play()
            }
        }
    }

    private fun disposeVideo() {
        try {
            videoRef?.view?.pause()
        } catch (e: Throwable) {
        }
        AppAudioOutputCoordinator.release(audioOwner)
        nativePlaying = false
        currentVideoUrl = ""
        isVideoInitialized = false
        isVideoError = false
        isVideoLoading = false
        isPaused = false
    }

    /** 把选中历史还原为当前分镜。成功后把新分镜带回编辑页。 */
    private fun onConfirm() {
        if (shots.isEmpty()) return
        val index = selectedIndex
        if (index < 0 || index >= shots.size) return
        launch {
            showLoading()
            val result = EditVideoRepository.applyShotHistory(shots[index].id)
            hideLoading()
            if (closed) return@launch
            val shot = result.getOrNull()
            if (shot == null) {
                toast("restore_failed".tr)
            } else {
                toast("restore_success".tr)
                nav.pop(JSONObject().put("shot", shot.toJson()))
            }
        }
    }

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr { flex(1f); backgroundColor(Color(0xFF0A0A0AL)) }
                SDNavBar { attr { title = "history".tr } }
                vif({ ctx.shots.isEmpty() && ctx.isLoading }) {
                    View {
                        attr { flex(1f); allCenter() }
                        SDMusicLoading()
                    }
                }
                velseif({ ctx.shots.isEmpty() }) {
                    View {
                        attr { flex(1f); allCenter() }
                        Image { attr { size(80f, 80f); src(AssetsImage.mvEmpty.asset()) } }
                        VGap(16f)
                        EvText("no_data".tr, 16f, Color(0x8AFFFFFFL))
                    }
                }
                velse {
                    View {
                        attr { flex(1f); backgroundColor(Color.BLACK) }
                        ctx.buildPlayer(this)
                    }
                    // 底部缩略图 + 还原按钮
                    View {
                        attr { backgroundColor(Color(0xFF0A0A0AL)) }
                        List {
                            attr {
                                height(100f)
                                flexDirectionRow()
                                showScrollerIndicator(false)
                                padding(8f, 20f, 8f, 20f)
                            }
                            vforIndex({ ctx.shots }) { shot, index, _ ->
                                View {
                                    attr { marginRight(if (index == ctx.shots.size - 1) 0f else 12f); alignItemsCenter() }
                                    event { click { ctx.selectShot(index) } }
                                    View {
                                        attr {
                                            size(80f, 60f)
                                            borderRadius(8f)
                                            backgroundColor(Color(0x29FFFFFFL))
                                            val selected = ctx.selectedIndex == index
                                            border(Border(if (selected) 1.5f else 0f, BorderStyle.SOLID, if (selected) Color(0xFFB55AFFL) else Color.TRANSPARENT))
                                        }
                                        val imageUrl = shot.firstFrameImg ?: shot.mvShotImg
                                        if (imageUrl.isEmpty()) {
                                            View {
                                                attr { absolutePositionAllZero(); borderRadius(8f); backgroundColor(Color(0x1FFFFFFFL)); allCenter() }
                                                VideocamGlyph(24f, Color(0x61FFFFFFL))
                                            }
                                        } else {
                                            SDImage {
                                                attr {
                                                    // Dart 选中描边 1.5 把图片向内挤，图片不盖住描边
                                                    val b = if (ctx.selectedIndex == index) 1.5f else 0f
                                                    absolutePosition(top = b, left = b, bottom = b, right = b)
                                                    borderRadius(8f)
                                                    url = imageUrl
                                                }
                                            }
                                        }
                                    }
                                    VGap(6f)
                                    EvText("#${shot.historySeq}", 12f, Color.WHITE, P500)
                                }
                            }
                        }
                        View {
                            attr { padding(0f, 20f, 10f, 20f) }
                            View {
                                attr { height(48f); borderRadius(53f); backgroundColor(UIColor.cF3EFF7); allCenter() }
                                event { click { ctx.onConfirm() } }
                                EvText("restore".tr, 16f, UIColor.c0A0A0A, P600)
                            }
                        }
                        SafeBottom()
                    }
                }
            }
        }
    }

    private fun buildPlayer(container: com.tencent.kuikly.core.base.ViewContainer<*, *>) {
        val ctx = this
        with(container) {
            vif({ ctx.isVideoLoading }) {
                View {
                    attr { flex(1f); allCenter() }
                    Spinner(36f, Color(0xFF7F58F7L))
                    Text {
                        attr {
                            absolutePositionAllZero()
                            textAlignCenter()
                            fontSize(12f)
                            color(Color.WHITE)
                            text(ctx.videoProgress)
                        }
                    }
                }
            }
            velseif({ ctx.isVideoError || ctx.currentVideoUrl.isEmpty() }) {
                View {
                    attr { flex(1f); allCenter() }
                    View {
                        attr { size(64f, 64f); borderRadius(32f); border(Border(2f, BorderStyle.SOLID, Color(0x3DFFFFFFL))); allCenter() }
                        event { click { ctx.reInitVideo() } }
                        PlayGlyph(40f, Color(0x3DFFFFFFL))
                    }
                }
            }
            velse {
                View {
                    attr { flex(1f) }
                    event { click { ctx.togglePlayPause() } }
                    vbind({ ctx.currentVideoUrl }) {
                        if (ctx.currentVideoUrl.isNotEmpty()) {
                            SDVideo {
                                ref { ctx.videoRef = it }
                                attr {
                                    absolutePositionAllZero()
                                    src(ctx.currentVideoUrl)
                                    autoPlay(false)
                                    loop(true)
                                    fitContain()
                                    cache(false)
                                }
                                event {
                                    onState { st ->
                                        when (st.state) {
                                            SDVideoState.READY -> if (!ctx.isVideoInitialized) ctx.onVideoControllerReady()
                                            SDVideoState.PLAYING -> ctx.nativePlaying = true
                                            SDVideoState.PAUSED, SDVideoState.COMPLETED -> ctx.nativePlaying = false
                                            SDVideoState.ERROR -> {
                                                ctx.nativePlaying = false
                                                ctx.isVideoError = true
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    vif({ ctx.isPaused && !ctx.isVideoError }) {
                        View {
                            attr { absolutePositionAllZero(); allCenter() }
                            PausedPlayButton { ctx.togglePlayPause() }
                        }
                    }
                }
            }
        }
    }
}

/** Material `Icons.videocam`（Canvas 绘制：机身 + 右侧镜头梯形）。 */
private fun ViewContainer<*, *>.VideocamGlyph(size: Float, color: Color) {
    Canvas({ attr { size(size, size) } }) { context, w, h ->
        val u = w / 24f
        val v = h / 24f
        context.beginPath()
        context.moveTo(3f * u, 6f * v)
        context.lineTo(17f * u, 6f * v)
        context.lineTo(17f * u, 10.5f * v)
        context.lineTo(21f * u, 6.5f * v)
        context.lineTo(21f * u, 17.5f * v)
        context.lineTo(17f * u, 13.5f * v)
        context.lineTo(17f * u, 18f * v)
        context.lineTo(3f * u, 18f * v)
        context.closePath()
        context.fillStyle(color)
        context.fill()
    }
}
