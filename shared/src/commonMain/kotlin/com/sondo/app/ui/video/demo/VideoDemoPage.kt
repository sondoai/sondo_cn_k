package com.sondo.app.ui.video.demo

import com.sondo.app.base.BasePager
import com.sondo.app.data.models.SquareModel
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.dialog.VideoPreviewItem
import com.sondo.app.ui.dialog.previewVideos
import com.sondo.app.ui.nav.SDPageScaffold
import com.sondo.app.ui.video.AppVideoControlsMode
import com.sondo.app.ui.video.AppVideoController
import com.sondo.app.ui.video.AppVideoDisplayMode
import com.sondo.app.ui.video.AppVideoPlaybackGate
import com.sondo.app.ui.video.SDAppVideoPlayer
import com.sondo.app.ui.video.SDAppVideoProgressBar
import com.sondo.app.ui.video.SDFeedBannerVideoPlayer
import com.sondo.app.ui.video.SDInlinePreviewVideoCard
import com.sondo.app.ui.video.SDListMvVideoPlayer
import com.sondo.app.ui.video.SDMvVideoPlayer
import com.sondo.app.ui.video.SDVideoPlayerList
import com.sondo.app.ui.video.SDVideoWidget
import com.sondo.app.ui.video.VideoPlayerListItem
import com.sondo.app.ui.video.formatVideoDuration
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.PageList
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

private object DemoMedia {
    const val flower = "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4"
    const val sintel = "https://media.w3.org/2010/05/sintel/trailer.mp4"
    const val blazes = "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"
    const val blazesCover = "https://storage.googleapis.com/gtv-videos-bucket/sample/images/ForBiggerBlazes.jpg"
    const val escapes = "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4"
    const val escapesCover = "https://storage.googleapis.com/gtv-videos-bucket/sample/images/ForBiggerEscapes.jpg"
    const val broken = "https://example.invalid/not-found.mp4"
}

private fun ViewContainer<*, *>.demoTitle(text: String) {
    Text {
        attr {
            marginTop(24f)
            marginBottom(8f)
            text(text)
            fontSize(15f)
            fontWeightBold()
            color(Color.WHITE)
        }
    }
}

private fun ViewContainer<*, *>.demoButton(label: () -> String, onTap: () -> Unit) {
    View {
        attr {
            height(32f)
            padding(0f, 12f, 0f, 12f)
            marginRight(8f)
            marginBottom(8f)
            borderRadius(16f)
            backgroundColor(SDColors.white20)
            allCenter()
        }
        event { click { onTap() } }
        Text {
            attr {
                text(label())
                fontSize(13f)
                color(Color.WHITE)
            }
        }
    }
}

/** 视频组件演示：`nav.push("video_demo")`。每个组件至少用一次，便于在模拟器上冒烟。 */
@Page("video_demo")
internal class VideoDemoPage : BasePager() {
    private val player = AppVideoController(this, DemoMedia.flower, looping = true, mixWithOthers = false)
    private var widgetActive by observable(true)
    private var widgetMuted by observable(false)
    private var showList by observable(false)
    private var showMvPager by observable(false)
    private var mvPage by observable(0)
    private var lastEvent by observable("")

    private val listItems = listOf(
        VideoPlayerListItem("a", DemoMedia.blazes, DemoMedia.blazesCover),
        VideoPlayerListItem("b", DemoMedia.escapes, DemoMedia.escapesCover),
        VideoPlayerListItem("c", DemoMedia.flower),
        VideoPlayerListItem("d", DemoMedia.sintel),
    )
    private val squareItems = listOf(
        SquareModel(id = 1L, title = "blazes", resultUrl = DemoMedia.blazes, coverImage = DemoMedia.blazesCover),
        SquareModel(id = 2L, title = "escapes", resultUrl = DemoMedia.escapes, coverImage = DemoMedia.escapesCover),
        SquareModel(id = 3L, title = "broken", resultUrl = DemoMedia.broken, coverImage = DemoMedia.blazesCover),
    )

    override fun pageWillDestroy() {
        if (showList || showMvPager) AppVideoPlaybackGate.instance.release()
        player.dispose()
        super.pageWillDestroy()
    }

    override fun interceptBack(): Boolean = showList || showMvPager

    override fun onBackPressed(): Boolean {
        if (showList || showMvPager) {
            closeFullscreen()
            return true
        }
        return false
    }

    private fun openFullscreen(list: Boolean) {
        if (showList || showMvPager) return
        // 全屏页占用门闩：绑定了门闩的 Banner / 卡片释放解码器
        AppVideoPlaybackGate.instance.acquire()
        player.pause()
        if (list) showList = true else showMvPager = true
        refreshBackIntercept()
    }

    private fun closeFullscreen() {
        showList = false
        showMvPager = false
        AppVideoPlaybackGate.instance.release()
        refreshBackIntercept()
    }

    override fun content(): ViewBuilder {
        val ctx = this
        val w = ctx.screenWidth - 32f
        return {
            SDPageScaffold {
                attr { title = "Video demo" }
                content {
                    Scroller {
                        attr {
                            flex(1f)
                            padding(0f, 16f, 40f, 16f)
                        }
                        // 1. 通用播放器（完整控制条）
                        demoTitle("SDAppVideoPlayer · full controls")
                        SDAppVideoPlayer {
                            attr {
                                width(w)
                                url = DemoMedia.sintel
                                aspectRatio = 16f / 9f
                                radius = 8f
                                playbackGate = AppVideoPlaybackGate.instance
                            }
                            event {
                                onFullscreen { ctx.toast("onFullscreen") }
                                onReady { ctx.lastEvent = "ready ${formatVideoDuration(it.durationMs)}" }
                                onPlayingChanged { ctx.lastEvent = "playing=$it" }
                                onError { ctx.lastEvent = "error $it" }
                            }
                        }
                        Text {
                            attr {
                                marginTop(4f)
                                text("event: ${ctx.lastEvent}")
                                fontSize(12f)
                                color(SDColors.white60)
                            }
                        }

                        // 2. 页面持有控制器
                        demoTitle("AppVideoController + SDMvVideoPlayer + SDAppVideoProgressBar")
                        SDMvVideoPlayer {
                            attr {
                                size(w, w * 9f / 16f)
                                controller = ctx.player
                                showProgress = false
                            }
                        }
                        SDAppVideoProgressBar { attr { controller = ctx.player; barHeight = 3f } }
                        Text {
                            attr {
                                text("${formatVideoDuration(ctx.player.positionMs)} / ${formatVideoDuration(ctx.player.durationMs)} · ${ctx.player.state} · x${ctx.player.speed}")
                                fontSize(12f)
                                color(SDColors.white60)
                            }
                        }
                        View {
                            attr {
                                marginTop(8f)
                                flexDirectionRow()
                                flexWrapWrap()
                            }
                            demoButton({ if (ctx.player.isPlaying) "pause" else "play" }) { ctx.player.togglePlayback() }
                            demoButton({ "-10s" }) { ctx.player.seekBy(-10_000L) }
                            demoButton({ "+10s" }) { ctx.player.seekBy(10_000L) }
                            demoButton({ if (ctx.player.muted) "unmute" else "mute" }) { ctx.player.toggleMute() }
                            demoButton({ "speed" }) {
                                ctx.player.setPlaybackSpeed(if (ctx.player.speed >= 2f) 0.5f else ctx.player.speed + 0.5f)
                            }
                            demoButton({ "source" }) {
                                ctx.player.setSource(if (ctx.player.url == DemoMedia.flower) DemoMedia.escapes else DemoMedia.flower, autoPlay = true)
                            }
                            demoButton({ "error src" }) { ctx.player.setSource(DemoMedia.broken, autoPlay = true) }
                        }

                        // 3. 旧 VideoWidget
                        demoTitle("SDVideoWidget (legacy, cover + isActive)")
                        SDVideoWidget {
                            attr {
                                containerWidth = w
                                containerHeight = w * 9f / 16f
                                radius = 12f
                                showBottomControls = true
                            }
                            attr {
                                url = DemoMedia.blazes
                                coverUrl = DemoMedia.blazesCover
                                isActive = ctx.widgetActive
                                volume = if (ctx.widgetMuted) 0f else 1f
                            }
                            event {
                                onInitialized { r, res, sec -> ctx.lastEvent = "init ratio=$r res=$res ${sec}s" }
                                onPlayCompleted { ctx.lastEvent = "completed" }
                            }
                        }
                        View {
                            attr {
                                marginTop(8f)
                                flexDirectionRow()
                            }
                            demoButton({ "isActive=${ctx.widgetActive}" }) { ctx.widgetActive = !ctx.widgetActive }
                            demoButton({ "muted=${ctx.widgetMuted}" }) { ctx.widgetMuted = !ctx.widgetMuted }
                        }

                        // 4. Banner（静音循环背景）
                        demoTitle("SDFeedBannerVideoPlayer (muted loop, gate bound)")
                        SDFeedBannerVideoPlayer {
                            attr {
                                size(w, w * 9f / 16f)
                                sizeByAspectRatio = false
                                radius = 12f
                                url = DemoMedia.escapes
                                coverUrl = DemoMedia.escapesCover
                                playbackGate = AppVideoPlaybackGate.instance
                                isActive = !ctx.showList && !ctx.showMvPager
                            }
                        }

                        // 5. 缩略图卡片（点击才播放）
                        demoTitle("SDAppVideoPlayer · thumbnail, tap to play")
                        SDAppVideoPlayer {
                            attr {
                                size(160f, 213f)
                                sizeByAspectRatio = false
                                radius = 10f
                                url = DemoMedia.flower
                                displayMode = AppVideoDisplayMode.thumbnail
                                controlsMode = AppVideoControlsMode.none
                                showProgress = false
                                enableDoubleTap = false
                            }
                        }

                        // 6. 内联预览卡片 → 全屏预览弹窗
                        demoTitle("SDInlinePreviewVideoCard → showVideoPreview")
                        SDInlinePreviewVideoCard {
                            attr {
                                containerWidth = 272f
                                containerHeight = 153f
                                radius = 12f
                                url = DemoMedia.blazes
                                coverUrl = DemoMedia.blazesCover
                                visibilityKey = "demo_inline"
                                onOpenFullscreen = { pos ->
                                    ctx.previewVideos(
                                        listOf(VideoPreviewItem(DemoMedia.blazes, VideoPreviewItem.STATUS_SUCCESS, DemoMedia.blazesCover)),
                                        initialPositionMs = pos ?: 0L,
                                        showDownloadButton = false,
                                    )
                                }
                            }
                        }

                        // 7. 整页列表
                        demoTitle("Full-screen pagers")
                        View {
                            attr { flexDirectionRow() }
                            demoButton({ "SDVideoPlayerList" }) { ctx.openFullscreen(list = true) }
                            demoButton({ "SDListMvVideoPlayer" }) { ctx.openFullscreen(list = false) }
                        }
                    }
                }
                overlay {
                    vif({ ctx.showList }) {
                        View {
                            attr {
                                absolutePositionAllZero()
                                backgroundColor(Color.BLACK)
                            }
                            SDVideoPlayerList {
                                attr {
                                    absolutePositionAllZero()
                                    items = ctx.listItems
                                    hasMore = false
                                    progressPadding = floatArrayOf(0f, 0f, ctx.safeBottom, 0f)
                                    overlayBuilder = { index, isCurrent ->
                                        Text {
                                            attr {
                                                absolutePosition(left = 16f, bottom = ctx.safeBottom + 40f)
                                                text("item $index${if (isCurrent) " (current)" else ""}")
                                                fontSize(14f)
                                                color(Color.WHITE)
                                            }
                                        }
                                    }
                                }
                                event {
                                    onPageChanged { ctx.lastEvent = "page $it" }
                                    onDoubleTap { ctx.toast("double tap $it") }
                                }
                            }
                            closeButton(ctx)
                        }
                    }
                    vif({ ctx.showMvPager }) {
                        View {
                            attr {
                                absolutePositionAllZero()
                                backgroundColor(Color.BLACK)
                            }
                            PageList {
                                attr {
                                    absolutePositionAllZero()
                                    pageItemWidth(ctx.screenWidth)
                                    pageItemHeight(ctx.screenHeight)
                                    pageDirection(false)
                                    showScrollerIndicator(false)
                                }
                                event {
                                    pageIndexDidChanged {
                                        ctx.mvPage = (it as? JSONObject)?.optInt("index", 0) ?: 0
                                    }
                                }
                                ctx.squareItems.forEachIndexed { index, mv ->
                                    SDListMvVideoPlayer {
                                        attr {
                                            size(ctx.screenWidth, ctx.screenHeight)
                                            this.mv = mv
                                            this.index = index
                                            showAiLabel = true
                                            bypassPlaybackGate = true
                                        }
                                        attr {
                                            isCurrentPage = ctx.mvPage == index
                                            isPreloading = ctx.mvPage + 1 == index
                                        }
                                        event {
                                            onPausedChanged { i, paused -> ctx.lastEvent = "mv $i paused=$paused" }
                                        }
                                    }
                                }
                            }
                            closeButton(ctx)
                        }
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.closeButton(ctx: VideoDemoPage) {
    View {
        attr {
            absolutePosition(top = ctx.statusBarHeight + 8f, right = 16f)
            size(36f, 36f)
            borderRadius(18f)
            backgroundColor(Color(0, 0, 0, 0.5f))
            allCenter()
        }
        event { click { ctx.closeFromButton() } }
        Text {
            attr {
                text("✕")
                fontSize(16f)
                color(Color.WHITE)
            }
        }
    }
}

private fun VideoDemoPage.closeFromButton() = onBackPressed()
