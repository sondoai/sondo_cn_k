package com.sondo.app.features.library

import com.sondo.app.base.BasePager
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.delayMs
import com.sondo.app.data.api.MvServiceApi
import com.sondo.app.data.models.MvModel
import com.sondo.app.data.models.MvPreviewTimeline
import com.sondo.app.data.models.MvStatus
import com.sondo.app.data.models.PreviewSeekAction
import com.sondo.app.data.models.PreviewSeekResult
import com.sondo.app.data.models.mvPreviewTimelineOf
import com.sondo.app.nativeapi.SDVideo
import com.sondo.app.nativeapi.SDVideoState
import com.sondo.app.nativeapi.SDVideoView
import com.sondo.app.service.MvService
import com.sondo.app.service.audio.AppAudioOutputCoordinator
import com.sondo.app.ui.atoms.SDLoading
import com.sondo.app.ui.atoms.VGap
import com.sondo.app.ui.dialog.SDPointsLabel
import com.sondo.app.ui.nav.SDNavBack
import com.sondo.app.ui.overlay.DialogHandle
import com.sondo.app.ui.video.VideoIcon
import com.sondo.app.ui.works.WorksPreviewUnlock
import com.sondo.app.ui.video.VideoIconKind
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.View

/**
 * 高潮试看未完成播放页（Dart `MvPreviewPlayPage` + `MvPreviewPlayController`）。
 * 只播试看区间，锁定区拖动弹解锁窗；不复用详情播放页的编辑、下载和订阅卡片。
 *
 * 页面参数：`{id: Long, item?: MvModel json}`。展示只用详情接口的数据（入参 item 仅为兼容，不参与展示）。
 */
@Page(Routes.mvPreviewPlay)
internal class MvPreviewPlayPage : BasePager() {
    internal var mv by observable<MvModel?>(null)
    internal var ready by observable(false)

    /** 拉详情（含失败后的 3 次重试）期间为 true。视频能播或彻底失败后关掉。 */
    internal var loading by observable(true)
    internal var positionSec by observable(0.0)

    /** 用户单击暂停后为 true，驱动中间暂停态图标。 */
    internal var isPaused by observable(false)

    /** 详情写入后递增，进度条和积分解锁按钮一起重建。 */
    internal var timelineRev by observable(0)
    internal var videoW by observable(0)
    internal var videoH by observable(0)

    internal var videoRef: ViewRef<SDVideoView>? = null
    private var endDialogShown = false
    private var unlockHandle: DialogHandle? = null

    /** 因跳转到其他页（如订阅）而暂停；回来后若用户未手动暂停则继续播。 */
    private var pausedByRoute = false
    private var playing = false
    private var completed = false
    private var durationMs = 0L
    private var positionMs = 0L
    private var closed = false
    private var detachAudio: (() -> Unit)? = null
    private val audioOwner = Any()

    val timeline: MvPreviewTimeline
        get() {
            val item = mv ?: return MvPreviewTimeline(0.0, 0.0, 0.0)
            return mvPreviewTimelineOf(item)
        }

    override fun pageBackgroundColor(): Color = Color.BLACK

    override fun created() {
        super.created()
        detachAudio = AppAudioOutputCoordinator.attach(
            audioOwner,
            isPlaying = { playing },
            isAudible = { true },
            pauseForHandoff = { videoRef?.view?.pause() },
        )
        val id = idOf()
        if (id == null) {
            loading = false
            return
        }
        launch { openFromId(id) }
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        resumeAfterRoute()
    }

    override fun pageDidDisappear() {
        super.pageDidDisappear()
        pauseForRoute()
    }

    override fun pageWillDestroy() {
        closed = true
        videoRef?.view?.pause()
        detachAudio?.invoke()
        detachAudio = null
        super.pageWillDestroy()
    }

    private fun idOf(): Long? {
        val params = pagerData.params
        val raw = params.opt("id")
        val id = when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        }
        if (id != null) return id
        return params.optJSONObject("item")?.optLong("id", 0L)?.takeIf { it > 0L }
    }

    /** 展示只用详情。首次失败后再请求 3 次，间隔 2 秒；仍失败就留空页面。 */
    private suspend fun openFromId(id: Long) {
        var detail = MvServiceApi.getMvDetailApiV2(id).getOrNull()
        if (closed) return
        var retryCount = 0
        while (detail == null && retryCount < 3) {
            retryCount++
            delayMs(2000)
            if (closed) return
            detail = MvServiceApi.getMvDetailApiV2(id).getOrNull()
            if (closed) return
        }
        if (detail == null) {
            loading = false
            return
        }
        mv = detail
        if (timeline.hasRange) positionSec = timeline.startSec
        timelineRev++
        if ((detail.resultUrl ?: "").isEmpty()) loading = false
    }

    // ------------------------------------------------------------------ 播放器回调

    internal fun onVideoState(state: SDVideoState) {
        if (closed) return
        if (state.width > 0 && state.height > 0) {
            videoW = state.width
            videoH = state.height
        }
        if (state.durationMs > 0) durationMs = state.durationMs
        playing = state.isPlaying
        when (state.state) {
            SDVideoState.READY -> {
                if (!ready) {
                    ready = true
                    loading = false
                    isPaused = false
                    playVideo()
                }
            }
            SDVideoState.PLAYING -> {
                completed = false
                if (!ready) {
                    ready = true
                    loading = false
                }
                if (!isPageVisible) {
                    videoRef?.view?.pause()
                    pausedByRoute = true
                }
            }
            SDVideoState.COMPLETED -> {
                completed = true
                onTick()
            }
            SDVideoState.ERROR -> loading = false
        }
        if (!playing) AppAudioOutputCoordinator.release(audioOwner)
    }

    internal fun onVideoProgress(position: Long, duration: Long) {
        if (closed) return
        positionMs = position
        if (duration > 0) durationMs = duration
        onTick()
    }

    private fun onTick() {
        if (!ready) return
        if (!isPageVisible) {
            if (playing) {
                videoRef?.view?.pause()
                pausedByRoute = true
            }
            return
        }
        positionSec = timelineFromVideo(positionMs, durationMs)
        if (!completed || endDialogShown) return
        // 自然播完只停在终点并显示播放按钮，不弹解锁。拖进锁定区仍弹窗。
        endDialogShown = true
        videoRef?.view?.pause()
        isPaused = true
        if (timeline.hasRange) positionSec = timeline.endSec
    }

    private fun playVideo() {
        launch {
            val token = AppAudioOutputCoordinator.claim(audioOwner)
            if (closed || !AppAudioOutputCoordinator.isCurrent(audioOwner, token)) return@launch
            videoRef?.view?.play()
        }
    }

    private fun pauseForRoute() {
        if (!ready || !playing) return
        videoRef?.view?.pause()
        pausedByRoute = true
    }

    private fun resumeAfterRoute() {
        if (!pausedByRoute || closed || isPaused) {
            pausedByRoute = false
            return
        }
        pausedByRoute = false
        if (!ready) return
        playVideo()
    }

    // ------------------------------------------------------------------ 交互

    /** 单击视频区域：播放中则暂停并显示图标，暂停中则继续播。 */
    internal fun togglePlayPause() {
        if (closed || !ready || !isPageVisible) return
        pausedByRoute = false
        if (playing) {
            videoRef?.view?.pause()
            isPaused = true
            return
        }
        // 播完停在终点，再点播放要回到试看起点，否则完成态直接 play 不会重头播。
        if (completed || endDialogShown) {
            endDialogShown = false
            completed = false
            val start = if (timeline.hasRange) timeline.startSec else 0.0
            videoRef?.view?.seekTo(videoFromTimeline(start, durationMs))
            positionSec = start
        }
        playVideo()
        isPaused = false
    }

    internal fun onSeek(targetSec: Double) {
        applySeek(timeline.seekTo(targetSec))
    }

    private fun applySeek(result: PreviewSeekResult) {
        if (closed || !ready) return
        videoRef?.view?.seekTo(videoFromTimeline(result.positionSec, durationMs))
        positionSec = result.positionSec
        when (result.action) {
            PreviewSeekAction.seek -> {
                completed = false
                if (!playing) playVideo()
                isPaused = false
            }
            PreviewSeekAction.snapStartAndDialog -> {
                completed = false
                playVideo()
                isPaused = false
                showUnlock()
            }
            PreviewSeekAction.snapEndAndDialog -> {
                videoRef?.view?.pause()
                isPaused = true
                showUnlock()
            }
        }
    }

    private suspend fun afterPaid(item: MvModel) {
        MvService.refreshMvStatus(item.id, MvStatus.generating, unlock = 1, forcePoll = true)
        if (!closed && isPageVisible) {
            // 试看页点的解锁直接回首页，不退回信息流或资源库。
            nav.popToRoot()
        }
    }

    /** 底部解锁：不弹窗，直接调弹窗内解锁按钮逻辑。 */
    internal fun unlockDirect() {
        val item = mv ?: return
        if (closed) return
        launch {
            WorksPreviewUnlock.unlock(this@MvPreviewPlayPage, item.id, item.continueFullMvNeedPoint) { afterPaid(item) }
        }
    }

    private fun showUnlock() {
        val item = mv ?: return
        if (closed || unlockHandle?.isShowing == true) return
        unlockHandle = WorksPreviewUnlock.show(this, item.id, item.continueFullMvNeedPoint) { afterPaid(item) }
    }

    private fun timelineFromVideo(position: Long, videoLength: Long): Double {
        val t = timeline
        if (!t.hasRange) return position / 1000.0
        if (videoLength <= 0) return t.startSec
        val progress = (position.toDouble() / videoLength).coerceIn(0.0, 1.0)
        return t.startSec + progress * (t.endSec - t.startSec)
    }

    private fun videoFromTimeline(timelineSec: Double, videoLength: Long): Long {
        val t = timeline
        if (!t.hasRange || videoLength <= 0) return (timelineSec * 1000).toLong()
        val span = t.endSec - t.startSec
        val progress = ((timelineSec - t.startSec) / span).coerceIn(0.0, 1.0)
        return (progress * videoLength).toLong()
    }

    // ------------------------------------------------------------------ UI

    internal fun videoBoxHeight(): Float {
        val w = screenWidth
        if (videoW <= 0 || videoH <= 0) return w * 16f / 9f
        return w * videoH / videoW
    }

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    flex(1f)
                    backgroundColor(Color.BLACK)
                }
                // 视频层
                vif({ !(ctx.mv?.resultUrl ?: "").isEmpty() }) {
                    View {
                        attr {
                            absolutePositionAllZero()
                            val portrait = ctx.mv?.imageComp == "9:16"
                            if (portrait) {
                                justifyContentFlexStart()
                                paddingTop(ctx.statusBarHeight)
                            } else {
                                justifyContentCenter()
                            }
                            alignItemsCenter()
                            opacity(if (ctx.ready) 1f else 0f)
                        }
                        event { click { ctx.togglePlayPause() } }
                        View {
                            attr {
                                width(ctx.screenWidth)
                                height(ctx.videoBoxHeight())
                                borderRadius(if (ctx.mv?.imageComp == "16:9") 0f else 16f)
                                allCenter()
                            }
                            SDVideo {
                                ref { ctx.videoRef = it }
                                attr {
                                    absolutePositionAllZero()
                                    src(ctx.mv?.resultUrl ?: "")
                                    autoPlay(false)
                                    loop(false)
                                    fitContain()
                                    cache(true)
                                    progressIntervalMs(200)
                                }
                                event {
                                    onState { ctx.onVideoState(it) }
                                    onProgress { ctx.onVideoProgress(it.positionMs, it.durationMs) }
                                }
                            }
                            // 顶部由黑到透明，底部到黑
                            vif({ ctx.mv?.imageComp == "9:16" }) {
                                View {
                                    attr {
                                        absolutePosition(top = 0f, left = 0f, right = 0f)
                                        height(80f)
                                        touchEnable(false)
                                        backgroundLinearGradient(
                                            Direction.TO_BOTTOM,
                                            ColorStop(Color(0, 0, 0, 0.8f), 0f),
                                            ColorStop(Color(0, 0, 0, 0f), 1f),
                                        )
                                    }
                                }
                                View {
                                    attr {
                                        absolutePosition(left = 0f, right = 0f, bottom = 0f)
                                        height(160f)
                                        touchEnable(false)
                                        backgroundLinearGradient(
                                            Direction.TO_BOTTOM,
                                            ColorStop(Color(0, 0, 0, 0f), 0f),
                                            ColorStop(Color.BLACK, 1f),
                                        )
                                    }
                                }
                            }
                            vif({ ctx.isPaused }) {
                                View {
                                    attr {
                                        padding(4f)
                                        borderRadius(50f)
                                        backgroundColor(Color(0, 0, 0, 0.35f))
                                        touchEnable(false)
                                    }
                                    VideoIcon(VideoIconKind.PLAY, 46f)
                                }
                            }
                        }
                    }
                }
                vif({ !ctx.ready && ctx.loading }) {
                    View {
                        attr {
                            absolutePositionAllZero()
                            allCenter()
                            touchEnable(false)
                        }
                        SDLoading { }
                    }
                }
                // 顶部返回 + 底部进度 / 解锁
                View {
                    attr {
                        absolutePosition(top = 0f, left = 0f, right = 0f)
                        paddingTop(ctx.statusBarHeight)
                        alignItemsFlexStart()
                    }
                    SDNavBack()
                }
                vif({ ctx.mv != null }) {
                    View {
                        attr {
                            absolutePosition(left = 0f, right = 0f, bottom = 0f)
                            paddingBottom(ctx.safeBottom)
                        }
                        previewProgressBar(ctx)
                        VGap(16f)
                        View {
                            attr { padding(0f, 20f, 16f, 20f) }
                            View {
                                attr {
                                    height(48f)
                                    allCenter()
                                    backgroundColor(UIColor.cF3EFF7)
                                    borderRadius(53f)
                                }
                                event { click { ctx.unlockDirect() } }
                                vbindPoints(ctx)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.vbindPoints(ctx: MvPreviewPlayPage) {
    vbind({ ctx.timelineRev }) {
        SDPointsLabel("unlock_full_video".tr, ctx.mv?.continueFullMvNeedPoint ?: 0, UIColor.c0A0A0A)
    }
}

/** 试看进度条（Dart `_PreviewProgressBar`）：锁标钉在试看两端；白段只覆盖已播放到的试看进度。 */
private fun ViewContainer<*, *>.previewProgressBar(ctx: MvPreviewPlayPage) {
    val width = ctx.screenWidth - 40f
    fun seekAt(x: Float) {
        val t = ctx.timeline
        ctx.onSeek((x / width).toDouble() * t.totalSec)
    }
    View {
        attr {
            margin(0f, 20f, 0f, 20f)
            height(24f)
            justifyContentCenter()
        }
        event {
            click { seekAt(it.x) }
            pan { seekAt(it.x) }
        }
        View {
            attr {
                height(4f)
                borderRadius(4f)
                backgroundColor(Color(0x33FFFFFFL))
            }
        }
        View {
            attr {
                absolutePosition(top = 10f, left = 0f)
                height(4f)
                borderRadius(4f)
                backgroundColor(Color.WHITE)
                val t = ctx.timeline
                ctx.timelineRev
                val total = if (t.totalSec <= 0) 1.0 else t.totalSec
                val pos = ctx.positionSec
                var playedLeft = 0f
                val playedWidth: Float
                if (t.hasRange) {
                    val playEnd = pos.coerceIn(t.startSec, t.endSec)
                    playedLeft = (width * (t.startSec / total)).toFloat()
                    playedWidth = (width * ((playEnd - t.startSec) / total)).toFloat()
                } else {
                    playedWidth = (width * (pos / total).coerceIn(0.0, 1.0)).toFloat()
                }
                marginLeft(playedLeft)
                width(if (playedWidth > 0f) playedWidth else 0f)
                opacity(if (playedWidth > 0f) 1f else 0f)
            }
        }
        vif({
            ctx.timelineRev
            ctx.timeline.hasRange
        }) {
            previewLockMark { ctx.lockLeft(width, start = true) }
            previewLockMark { ctx.lockLeft(width, start = false) }
        }
    }
}

/** 起点锁在试看起点左侧，终点锁在试看终点右侧；各向中间收 2px。 */
internal fun MvPreviewPlayPage.lockLeft(width: Float, start: Boolean): Float {
    val t = timeline
    val total = if (t.totalSec <= 0) 1.0 else t.totalSec
    val rangeLeft = (width * (t.startSec / total)).toFloat()
    val rangeWidth = (width * ((t.endSec - t.startSec) / total)).toFloat()
    return if (start) rangeLeft - 16f + 2f else rangeLeft + rangeWidth - 2f
}

private fun ViewContainer<*, *>.previewLockMark(left: () -> Float) {
    View {
        attr {
            absolutePosition(top = 4f, left = left())
            size(16f, 16f)
            borderRadius(8f)
            backgroundColor(UIColor.cF3EFF7)
            allCenter()
            touchEnable(false)
        }
        Image {
            attr {
                size(10f, 10f)
                src(ImageUri.commonAssets(AssetsImage.imageLocked))
                tintColor(UIColor.c0A0A0A)
                resizeContain()
            }
        }
    }
}
