package com.sondo.app.features.music.detail.widgets

import com.sondo.app.ui.works.SDToggleIcon
import com.sondo.app.core.constants.AppTheme
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.i18n.tr
import com.sondo.app.data.models.PlayMode
import com.sondo.app.data.models.jStrOrNull
import com.sondo.app.data.models.jLongOrNull
import com.sondo.app.features.music.detail.MusicDetailPage
import com.sondo.app.ui.SDWeight
import com.sondo.app.ui.anim.SDBreathing
import com.sondo.app.ui.anim.SDRotating
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDMarqueeText
import com.sondo.app.ui.atoms.SDRingProgress
import com.sondo.app.ui.atoms.SDSeekBar
import com.sondo.app.ui.atoms.SDShimmer
import com.tencent.kuikly.core.base.Anchor
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.Rotate
import com.tencent.kuikly.core.base.Scale
import com.tencent.kuikly.core.base.Skew
import com.tencent.kuikly.core.base.Translate
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.base.event.layoutFrameDidChange
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.velseif
import com.tencent.kuikly.core.directives.vforIndex
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.List
import com.tencent.kuikly.core.views.PageList
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/** 进度时间 `m:ss`（Dart `_formatDuration`：分钟取 60 的余数）。 */
internal fun formatDetailDuration(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes = (totalSeconds / 60L) % 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${if (seconds < 10) "0$seconds" else "$seconds"}"
}

/** 播放模式对应图标。 */
private fun playModeAsset(mode: PlayMode): String = when (mode) {
    PlayMode.loop -> AssetsImage.repeatIcon
    PlayMode.single -> AssetsImage.repeatOneIcon
    PlayMode.random -> AssetsImage.randomIcon
}

/** 详情页整体结构。 */
internal fun ViewContainer<*, *>.MusicDetailBody(ctx: MusicDetailPage) {
    val w = ctx.screenWidth
    View {
        attr {
            flex(1f)
            backgroundColor(AppTheme.primary)
        }
        // 背景图（fitWidth、顶左对齐）
        Image {
            attr {
                absolutePosition(top = 0f, left = 0f)
                size(w, w * 3200f / 1440f)
                src(ImageUri.commonAssets(AssetsImage.profileBg))
                resizeStretch()
            }
        }
        View {
            attr {
                flex(1f)
                paddingTop(ctx.statusBarHeight)
                paddingBottom(ctx.safeBottom)
            }
            DetailAppBar(ctx)
            DetailToggleArea(ctx)
            DetailSongInfo(ctx)
            View { attr { height(20f) } }
            DetailProgressBar(ctx)
            DetailControlButtons(ctx)
            View { attr { height(8f) } }
        }
        // 首次引导遮罩
        vif({ ctx.showFirstTimeMask }) {
            View {
                attr {
                    absolutePositionAllZero()
                    backgroundColor(Color(0, 0, 0, 0.65f))
                    allCenter()
                }
                event { click { ctx.dismissFirstTimeMask() } }
                SDBreathing {
                    attr {
                        minScale = 0.8f
                        maxScale = 1f
                        periodS = 2f
                    }
                    content {
                        Image {
                            attr {
                                size(100f, 115f)
                                src(ImageUri.commonAssets(AssetsImage.tapToView))
                                resizeContain()
                            }
                        }
                    }
                }
                Text {
                    attr {
                        marginTop(16f)
                        text("click_to_view_lyrics".tr)
                        fontSize(16f)
                        fontWeightMedium()
                        color(Color.WHITE)
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.DetailIconButton(icon: String, iconSize: Float, onTap: () -> Unit) {
    View {
        attr {
            size(48f, 48f)
            allCenter()
        }
        event { click { onTap() } }
        Image {
            attr {
                size(iconSize, iconSize)
                src(ImageUri.commonAssets(icon))
                resizeContain()
            }
        }
    }
}

private fun ViewContainer<*, *>.DetailAppBar(ctx: MusicDetailPage) {
    View {
        attr {
            height(56f)
            flexDirectionRow()
            alignItemsCenter()
            padding(0f, 4f, 0f, 4f)
        }
        DetailIconButton(AssetsImage.close, 24f) { ctx.nav.pop() }
        View { attr { flex(1f) } }
        DetailIconButton(AssetsImage.whiteShare, 22f) { ctx.share() }
        DetailIconButton(AssetsImage.reportMv, 22f) { ctx.openReportSong() }
    }
}

/** 唱片 ↔ 歌词切换区域：两层都常驻（IndexedStack），点击切换。 */
private fun ViewContainer<*, *>.DetailToggleArea(ctx: MusicDetailPage) {
    View {
        attr { flex(1f) }
        event { click { ctx.toggleView() } }
        addChild(DetailRecordView()) {
            attr {
                absolutePositionAllZero()
                page = ctx
                visibility(!ctx.isLyricsView)
            }
        }
        View {
            attr {
                absolutePositionAllZero()
                visibility(ctx.isLyricsView)
            }
            DetailLyricsArea(ctx)
        }
    }
}

internal class DetailRecordAttr : com.tencent.kuikly.core.base.ComposeAttr() {
    lateinit var page: MusicDetailPage
}

/**
 * 黑胶唱片 + 唱针。设计高 360，剩余高度更小时整体按比例缩小（Dart FittedBox.scaleDown）。
 */
internal class DetailRecordView :
    com.tencent.kuikly.core.base.ComposeView<DetailRecordAttr, com.tencent.kuikly.core.base.ComposeEvent>() {
    private var areaHeight by observable(360f)

    override fun createAttr(): DetailRecordAttr = DetailRecordAttr()
    override fun createEvent(): com.tencent.kuikly.core.base.ComposeEvent = com.tencent.kuikly.core.base.ComposeEvent()

    override fun body(): com.tencent.kuikly.core.base.ViewBuilder {
        val ctx = this
        val page = ctx.attr.page
        val w = page.screenWidth
        return {
            event { layoutFrameDidChange { ctx.areaHeight = it.height } }
            View {
                attr {
                    val h = ctx.areaHeight
                    absolutePosition(top = (h - 360f) / 2f, left = 0f, right = 0f)
                    height(360f)
                    val s = (h / 360f).coerceAtMost(1f).coerceAtLeast(0.01f)
                    transform(Scale(s, s))
                }
                // 唱片区域（支持左右滑动切歌）
                View {
                    attr { absolutePosition(top = 56f, left = 0f, right = 0f, bottom = 0f) }
                    // 外圈装饰光晕
                    View {
                        attr {
                            absolutePositionAllZero()
                            allCenter()
                        }
                        View {
                            attr {
                                size(290f, 290f)
                                borderRadius(145f)
                                backgroundColor(UIColor.transparent10)
                                border(Border(1f, BorderStyle.SOLID, UIColor.transparent20))
                                padding(16f)
                            }
                            View {
                                attr {
                                    flex(1f)
                                    borderRadius(129f)
                                    backgroundColor(UIColor.black6)
                                }
                            }
                        }
                    }
                    PageList {
                        ref { page.pageListRef = it }
                        attr {
                            absolutePositionAllZero()
                            pageDirection(true)
                            pageItemWidth(w)
                            defaultPageIndex(page.currentIndex.coerceAtLeast(0))
                            showScrollerIndicator(false)
                            // 单曲循环时禁止滑动
                            scrollEnable(page.canChangePage())
                        }
                        event {
                            pageIndexDidChanged {
                                val index = (it as? JSONObject)?.optInt("index", 0) ?: 0
                                page.onPageChanged(index)
                            }
                            dragBegin { page.onPageScrollStart() }
                            scrollEnd { page.onPageScrollEnd() }
                        }
                        vforIndex({ page.queue }) { song, index, _ ->
                            View {
                                attr {
                                    width(w)
                                    allCenter()
                                }
                                event { click { page.toggleView() } }
                                View {
                                    attr {
                                        size(290f, 290f)
                                        allCenter()
                                        // 只有当前播放的唱片跟着转
                                        val current = index == page.currentIndex
                                        transform(Rotate(if (current) page.vinylAngle else 0f))
                                        animate(Animation.linear(0.05f), page.vinylAngle)
                                    }
                                    Image {
                                        attr {
                                            absolutePositionAllZero()
                                            src(ImageUri.commonAssets("images/common/m_vinyl.webp"))
                                            resizeCover()
                                        }
                                    }
                                    // 中央圆形封面；无封面 / 加载失败用黑胶图
                                    View {
                                        attr {
                                            size(165f, 165f)
                                            borderRadius(82.5f)
                                            overflow(true)
                                        }
                                        Image {
                                            attr {
                                                absolutePositionAllZero()
                                                src(ImageUri.commonAssets("images/common/m_vinyl.webp"))
                                                resizeCover()
                                            }
                                        }
                                        val cover = song.musicCoverUrl ?: ""
                                        if (cover.isNotEmpty()) {
                                            SDImage {
                                                attr {
                                                    absolutePositionAllZero()
                                                    url = cover
                                                    placeholder = false
                                                    shimmer = false
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // 唱针叠在最上层，随播放状态升降（支点偏上，Alignment(0, -0.85)）
                View {
                    attr {
                        absolutePosition(top = 0f, left = 0f, right = 0f)
                        height(163f)
                        allCenter()
                        touchEnable(false)
                    }
                    Image {
                        attr {
                            size(53f, 53f * 490f / 160f)
                            src(ImageUri.commonAssets("images/common/m_tonearm.png"))
                            resizeContain()
                            transform(
                                Rotate(if (page.tonearmDown) -38f else -60f),
                                Scale.DEFAULT,
                                Translate.DEFAULT,
                                Anchor(0.5f, 0.075f),
                                Skew.DEFAULT,
                            )
                            animate(Animation.easeInOut(0.3f), page.tonearmDown)
                        }
                    }
                }
            }
        }
    }
}

/** 歌词区域：纯音乐 / 加载中 / 对齐歌词列表 / 无歌词。 */
private fun ViewContainer<*, *>.DetailLyricsArea(ctx: MusicDetailPage) {
    vif({ ctx.musicDetail?.instrumental == 1 }) {
        View {
            attr {
                absolutePositionAllZero()
                allCenter()
            }
            Text {
                attr {
                    text("pure_music_no_lyrics".tr)
                    fontSize(14f)
                    color(Color.WHITE)
                }
            }
        }
    }
    velseif({ ctx.isLoading }) {
        View {
            attr {
                absolutePositionAllZero()
                allCenter()
            }
            // Material CircularProgressIndicator(white54, strokeWidth 2)，默认 36
            SDRotating {
                content {
                    SDRingProgress {
                        attr {
                            size(36f, 36f)
                            strokeWidth = 2f
                            progress = 0.25f
                            progressColor = Color(0x8AFFFFFFL)
                            trackColor = Color.TRANSPARENT
                        }
                    }
                }
            }
        }
    }
    velseif({ ctx.lyricLines.isNotEmpty() }) {
        View {
            attr { absolutePositionAllZero() }
            List {
                ref { ctx.lyricsListRef = it }
                attr {
                    absolutePosition(top = 50f, left = 0f, right = 0f, bottom = 50f)
                    showScrollerIndicator(false)
                }
                event {
                    dragBegin { ctx.onLyricsScrollStart() }
                    scrollEnd { ctx.onLyricsScrollEnd() }
                }
                vforIndex({ ctx.lyricLines }) { line, index, _ ->
                    View {
                        attr {
                            minHeight(40f)
                            allCenter()
                            padding(5f, 20f, 5f, 20f)
                        }
                        event {
                            layoutFrameDidChange { ctx.lyricFrames[index] = it.y to it.height }
                            click { ctx.toggleView() }
                        }
                        Text {
                            attr {
                                val isCurrent = index == ctx.currentLyricIndex
                                text(line.lyric)
                                fontSize(if (isCurrent) 18f else 16f)
                                lineHeight((if (isCurrent) 18f else 16f) * 1.4f)
                                if (isCurrent) fontWeightBold() else fontWeightNormal()
                                color(if (isCurrent) Color(255, 255, 255, 0.88f) else Color(255, 255, 255, 0.38f))
                                lines(10)
                                textOverFlowTail()
                                textAlignCenter()
                            }
                        }
                    }
                }
            }
            // 复制歌词（仅歌词视图）
            View {
                attr {
                    absolutePosition(top = 20f, right = 20f)
                    size(48f, 48f)
                    allCenter()
                }
                event { click { ctx.copyLyrics() } }
                Image {
                    attr {
                        size(22f, 22f)
                        src(ImageUri.commonAssets(AssetsImage.copy1))
                        resizeContain()
                        tintColor(Color.WHITE)
                    }
                }
            }
        }
    }
    velse {
        View {
            attr {
                absolutePositionAllZero()
                allCenter()
            }
            Text {
                attr {
                    text("no_lyrics".tr)
                    fontSize(14f)
                    color(Color(0x8AFFFFFFL))
                }
            }
        }
    }
}

/** 歌曲信息：标题 + 收藏，作者行（点击进个人主页）；加载中显示骨架屏。 */
private fun ViewContainer<*, *>.DetailSongInfo(ctx: MusicDetailPage) {
    vif({ ctx.isLoading }) {
        View {
            attr { padding(8f, 16f, 8f, 16f) }
            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                }
                SDShimmer {
                    attr {
                        flex(1f)
                        height(22f)
                        baseColor = Color(255, 255, 255, 0.2f)
                        highlightColor = Color(255, 255, 255, 0.3f)
                    }
                }
                SDShimmer {
                    attr {
                        marginLeft(12f)
                        size(24f, 24f)
                        radius = 12f
                        baseColor = Color(255, 255, 255, 0.2f)
                        highlightColor = Color(255, 255, 255, 0.3f)
                    }
                }
            }
            View {
                attr {
                    marginTop(8f)
                    flexDirectionRow()
                    alignItemsCenter()
                }
                SDShimmer {
                    attr {
                        size(20f, 20f)
                        marginRight(8f)
                        baseColor = Color(255, 255, 255, 0.2f)
                        highlightColor = Color(255, 255, 255, 0.3f)
                    }
                }
                SDShimmer {
                    attr {
                        flex(1f)
                        height(14f)
                        baseColor = Color(255, 255, 255, 0.2f)
                        highlightColor = Color(255, 255, 255, 0.3f)
                    }
                }
            }
        }
    }
    velseif({ ctx.musicDetail != null }) {
        View {
            attr { padding(4f, 16f, 4f, 16f) }
            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                }
                SDMarqueeText {
                    attr {
                        flex(1f)
                        height(26f)
                        text = ctx.musicDetail?.musicTitle ?: ""
                        size = 20f
                        fontWeight = SDWeight.W700
                    }
                }
                // 两张图叠放只切透明度：点喜欢立即切换、图标不闪（乐观更新，失败由 MusicService 回滚）
                View {
                    event { click { ctx.toggleFavorite() } }
                    SDToggleIcon(24f, { ctx.isFavorite }, AssetsImage.likeActive2, AssetsImage.likeWhite)
                }
            }
            View { attr { height(10f) } }
            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                }
                event {
                    click {
                        val id = ctx.musicDetail?.userInfo?.jLongOrNull("id")
                        if (id != null) ctx.toProfile(id)
                    }
                }
                SDImage {
                    attr {
                        size(20f, 20f)
                        borderRadius(10f)
                        url = ctx.musicDetail?.userInfo?.jStrOrNull("avatar") ?: ""
                    }
                }
                Text {
                    attr {
                        marginLeft(8f)
                        maxWidth(ctx.screenWidth - 140f)
                        text(ctx.musicDetail?.nicknameStr ?: "")
                        fontSize(12f)
                        lineHeight(12f)
                        lines(1)
                        textOverFlowTail()
                        color(AppTheme.secondary)
                    }
                }
                // AI 生成标识
                View {
                    attr {
                        marginLeft(8f)
                        padding(4f, 6f, 4f, 6f)
                        backgroundColor(UIColor.cFFF10)
                        borderRadius(6f)
                    }
                    Text {
                        attr {
                            text("ai_generated_label".tr)
                            fontSize(8f)
                            lineHeight(8f)
                            lines(1)
                            fontFamily("Poppins-Medium")
                            color(UIColor.cFFF60)
                        }
                    }
                }
                Image {
                    attr {
                        size(16f, 16f)
                        src(ImageUri.commonAssets(AssetsImage.arrowRight1))
                        resizeContain()
                        tintColor(AppTheme.secondary)
                    }
                }
            }
        }
    }
}

/** 进度条 + 时间。拖动时显示拖动位置，松手才 seek。 */
private fun ViewContainer<*, *>.DetailProgressBar(ctx: MusicDetailPage) {
    View {
        attr { padding(0f, 6f, 0f, 6f) }
        SDSeekBar {
            attr {
                duration = ctx.durationMs.toFloat()
                position = ctx.positionMs.toFloat()
                buffered = 0f
                touchHeight = 32f
                paddingH = 12f
                trackHeight = 2f
                activeTrackHeight = 2f
                thumbRadius = 4f
                activeThumbRadius = 4f
                activeColor = Color.WHITE
                inactiveColor = Color(255, 255, 255, 0.2f)
                bufferedColor = Color.TRANSPARENT
                showTooltip = false
                alignBottom = false
            }
            event {
                onSeekStart { ctx.onSeekStart(it) }
                onSeek { ctx.onSeek(it) }
                onSeekEnd { ctx.onSeekEnd(it) }
            }
        }
        View {
            attr {
                padding(0f, 12f, 0f, 12f)
                flexDirectionRow()
                justifyContentSpaceBetween()
            }
            Text {
                attr {
                    text(formatDetailDuration(if (ctx.isUserDragging) ctx.dragMs else ctx.positionMs))
                    fontSize(11f)
                    color(Color(0x8AFFFFFFL))
                }
            }
            Text {
                attr {
                    // 加载中尚未获取时长
                    text(if (ctx.processingState == "loading") "0:00" else formatDetailDuration(ctx.durationMs))
                    fontSize(11f)
                    color(Color(0x8AFFFFFFL))
                }
            }
        }
    }
}

/** 控制按钮行：播放模式 / 上一首 / 播放暂停 / 下一首 / 播放队列。 */
private fun ViewContainer<*, *>.DetailControlButtons(ctx: MusicDetailPage) {
    View {
        attr {
            padding(0f, 10f, 4f, 10f)
            flexDirectionRow()
            alignItemsCenter()
            justifyContentSpaceBetween()
        }
        // 播放模式
        View {
            attr {
                size(48f, 48f)
                allCenter()
            }
            event { click { ctx.togglePlayMode() } }
            Image {
                attr {
                    size(24f, 24f)
                    src(ImageUri.commonAssets(playModeAsset(ctx.playMode)))
                    resizeContain()
                }
            }
        }
        // 上一首（队列只有一首时禁用）
        View {
            attr {
                size(52f, 52f)
                allCenter()
            }
            event { click { if (ctx.queue.size > 1) ctx.playPrevious() } }
            Image {
                attr {
                    // 参考是 Material skip 图标 36 号（可视约 18×18）；这张图字形占 80 画布的 64×57，按 24 显示可视大小相当
                    size(24f, 24f)
                    src(ImageUri.commonAssets(AssetsImage.navPrev1))
                    resizeContain()
                    tintColor(if (ctx.queue.size > 1) Color.WHITE else Color(0x3DFFFFFFL))
                }
            }
        }
        // 播放 / 暂停（加载中显示圆环）
        View {
            attr {
                size(60f, 60f)
                allCenter()
            }
            event { click { if (ctx.processingState != "loading") ctx.playOrPause() } }
            vif({ ctx.processingState == "loading" }) {
                SDRotating {
                    attr { periodS = 1f }
                    content {
                        SDRingProgress {
                            attr {
                                size(36f, 36f)
                                strokeWidth = 2.5f
                                progress = 0.25f
                                progressColor = Color(255, 255, 255, 0.6f)
                                trackColor = Color(255, 255, 255, 0.2f)
                            }
                        }
                    }
                }
            }
            velse {
                View {
                    attr {
                        size(50f, 50f)
                        borderRadius(25f)
                        backgroundColor(Color.WHITE)
                        allCenter()
                    }
                    Image {
                        attr {
                            size(24f, 24f)
                            src(ImageUri.commonAssets(if (ctx.isPlaying) AssetsImage.navPause1 else AssetsImage.navPlay1))
                            resizeContain()
                            tintColor(Color.BLACK)
                        }
                    }
                }
            }
        }
        // 下一首
        View {
            attr {
                size(52f, 52f)
                allCenter()
            }
            event { click { if (ctx.queue.size > 1) ctx.playNext() } }
            Image {
                attr {
                    // 参考是 Material skip 图标 36 号（可视约 18×18）；这张图字形占 80 画布的 64×57，按 24 显示可视大小相当
                    size(24f, 24f)
                    src(ImageUri.commonAssets(AssetsImage.navNext1))
                    resizeContain()
                    tintColor(if (ctx.queue.size > 1) Color.WHITE else Color(0x3DFFFFFFL))
                }
            }
        }
        // 播放队列
        DetailIconButton(AssetsImage.playList, 24f) { ctx.openPlayQueue() }
    }
}
