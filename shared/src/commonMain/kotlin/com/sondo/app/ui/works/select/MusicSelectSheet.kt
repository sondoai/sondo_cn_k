package com.sondo.app.ui.works.select

import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.CommonUtils
import com.sondo.app.data.models.AudioItemStatus
import com.sondo.app.data.models.AudioUploadItem
import com.sondo.app.data.models.SongModel
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDLoading
import com.sondo.app.ui.input.SDInput
import com.sondo.app.ui.input.SDInputView
import com.sondo.app.ui.list.SDRefreshList
import com.sondo.app.ui.list.SDRefreshListView
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.velseif
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.List
import com.tencent.kuikly.core.views.PageList
import com.tencent.kuikly.core.views.PageListView
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import kotlin.math.max
import kotlin.math.min

internal class MusicSelectSheetAttr : ComposeAttr() {
    lateinit var state: MusicSelectState
    /** 弹层最大高度（屏高 60%）。 */
    var sheetHeight: Float = 0f
}

/** 上传区高度。 */
private const val UPLOAD_ZONE_HEIGHT = 80f

/** 列表区保底可视高度；低于此值时先藏上传区。 */
private const val MIN_LIST_VISIBLE_HEIGHT = 40f

/** 列表区默认高度上限（Dart kMusicSelectListAreaHeight）。 */
private const val LIST_AREA_MAX = 380f

/** 弹层顶部拖拽条区域（10 + 6 + 16）。 */
private const val HANDLE_CHROME = 32f

/** 搜索展开 / 收起动画（Dart 220ms easeOutCubic）。 */
private const val SEARCH_ANIM_S = 0.22f

/**
 * 选曲弹层内容（Dart `MusicSelectBottomSheet.build`）：上传区 → Tab / 搜索头（登录后）→ 列表区。
 * 未登录只有热门列表；搜索态是当前 Tab 的单列表；否则可左右滑动的三页（我的 / 上传 / 热门，页面保活）。
 */
internal class MusicSelectSheetView : ComposeView<MusicSelectSheetAttr, ComposeEvent>() {
    /** 软键盘高度（弹层随之上推并压缩高度）。 */
    private var keyboardHeight by observable(0f)

    /** 搜索框展开进度（0 收起 / 1 展开），驱动交叉淡入。 */
    private var searchExpanded by observable(false)

    internal var pageListRef: ViewRef<PageListView<*, *>>? = null
    internal var searchInputRef: ViewRef<SDInputView>? = null
    internal var myListRef: ViewRef<SDRefreshListView>? = null
    internal var uploadListRef: ViewRef<SDRefreshListView>? = null

    override fun createAttr(): MusicSelectSheetAttr = MusicSelectSheetAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    internal val state: MusicSelectState get() = attr.state

    override fun viewDestroyed() {
        // 页面直接销毁（未走 dismiss）时也要停试听
        attr.state.dispose()
        super.viewDestroyed()
    }

    /** 弹层内容区高度（扣掉拖拽条；键盘弹出时同步压缩）。 */
    internal fun contentHeight(): Float {
        val pd = pagerData
        val limit = min(attr.sheetHeight, max(0f, pd.pageViewHeight - keyboardHeight - pd.statusBarHeight))
        return max(0f, limit - HANDLE_CHROME)
    }

    private fun headerChrome(): Float = if (attr.state.isLogin) 16f + 42f + 16f else 16f

    internal fun showUpload(): Boolean = contentHeight() >= UPLOAD_ZONE_HEIGHT + headerChrome() + MIN_LIST_VISIBLE_HEIGHT

    /** 列表区可用高度。 */
    internal fun listAvailable(): Float {
        val used = (if (showUpload()) UPLOAD_ZONE_HEIGHT else 0f) + headerChrome() - (if (!showUpload() && attr.state.isLogin) 16f else 0f)
        return max(0f, contentHeight() - used)
    }

    /** 状态区高度 = min(380, 可用高度)。 */
    internal fun bodyHeight(): Float = min(LIST_AREA_MAX, listAvailable())

    internal fun onKeyboard(h: Float) {
        keyboardHeight = h
    }

    internal fun enterSearch() {
        attr.state.enterSearchMode()
        searchExpanded = true
        // 展开动画结束后聚焦
        com.tencent.kuikly.core.timer.setTimeout(pagerId, (SEARCH_ANIM_S * 1000).toInt()) {
            if (attr.state.isSearchMode) searchInputRef?.view?.focus()
        }
    }

    internal fun cancelSearch() {
        searchInputRef?.view?.blur()
        searchInputRef?.view?.setText("")
        searchExpanded = false
        attr.state.exitSearchMode()
    }

    internal fun clearSearch() {
        searchInputRef?.view?.setText("")
        attr.state.clearSearch()
    }

    internal fun tapTab(tab: MusicSelectTab) {
        if (tab == MusicSelectTab.trending && attr.state.isSearchMode) {
            searchInputRef?.view?.setText("")
            searchExpanded = false
        }
        attr.state.switchTab(tab)
        pageListRef?.view?.scrollToPageIndex(tab.ordinal, true)
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val s = ctx.state
        return {
            attr {
                height(ctx.contentHeight())
                padding(0f, 20f, 0f, 20f)
            }
            vif({ ctx.showUpload() }) {
                musicSelectUploadZone { s.onUploadTap() }
            }
            vif({ s.isLogin }) {
                View {
                    attr { marginTop(if (ctx.showUpload()) 16f else 0f) }
                    musicSelectTabSearchHeader(ctx)
                }
            }
            View {
                attr {
                    marginTop(16f)
                    flex(1f)
                }
                // 点列表区域收起键盘
                event { click { CommonUtils.hideKeyboard() } }
                vif({ !s.isLogin }) {
                    songTabBody(ctx, MusicSelectTab.trending, refreshable = false)
                }
                velseif({ s.isSearchMode }) {
                    View {
                        attr { flex(1f) }
                        vif({ s.currentTab == MusicSelectTab.upload }) {
                            uploadTabBody(ctx, refreshable = false)
                        }
                        velse {
                            songTabBody(ctx, s.currentTab, refreshable = false)
                        }
                    }
                }
                velse {
                    musicSelectPager(ctx)
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.MusicSelectSheet(init: MusicSelectSheetView.() -> Unit) {
    addChild(MusicSelectSheetView(), init)
}

// ============================================================================ 分页容器

private fun ViewContainer<*, *>.musicSelectPager(ctx: MusicSelectSheetView) {
    val s = ctx.state
    val pageW = ctx.pagerData.pageViewWidth - 40f
    val initial = s.currentTab.ordinal
    PageList {
        ref { ctx.pageListRef = it }
        attr {
            flex(1f)
            flexDirectionRow()
            pageDirection(true)
            pageItemWidth(pageW)
            pageItemHeight(ctx.listAvailable())
            defaultPageIndex(initial)
            keepItemAlive(true)
            showScrollerIndicator(false)
        }
        event {
            pageIndexDidChanged {
                val index = (it as? JSONObject)?.optInt("index", 0) ?: 0
                val tab = MusicSelectTab.values().getOrNull(index) ?: return@pageIndexDidChanged
                if (tab != s.currentTab) s.switchTab(tab)
            }
        }
        View {
            attr { size(pageW, ctx.listAvailable()) }
            songTabBody(ctx, MusicSelectTab.my, refreshable = true)
        }
        View {
            attr { size(pageW, ctx.listAvailable()) }
            uploadTabBody(ctx, refreshable = true)
        }
        View {
            attr { size(pageW, ctx.listAvailable()) }
            songTabBody(ctx, MusicSelectTab.trending, refreshable = false)
        }
    }
}

// ============================================================================ 列表区（Dart MusicSelectListBody）

private fun stateOf(s: MusicSelectState, tab: MusicSelectTab): MusicSelectListState = when (tab) {
    MusicSelectTab.my -> s.myListState
    MusicSelectTab.upload -> s.uploadListState
    MusicSelectTab.trending -> s.trendingListState
}

/** loading / empty / error 三态；success 时调用 [content]。 */
private fun ViewContainer<*, *>.listBody(
    ctx: MusicSelectSheetView,
    tab: MusicSelectTab,
    onRetry: () -> Unit,
    content: ViewContainer<*, *>.() -> Unit,
) {
    val s = ctx.state
    View {
        attr {
            height(ctx.bodyHeight())
        }
        vif({ stateOf(s, tab) == MusicSelectListState.loading || stateOf(s, tab) == MusicSelectListState.idle }) {
            View {
                attr {
                    flex(1f)
                    allCenter()
                }
                SDLoading { attr { size = 32f } }
            }
        }
        velseif({ stateOf(s, tab) == MusicSelectListState.empty }) {
            musicSelectEmptyState(ctx, tab)
        }
        velseif({ stateOf(s, tab) == MusicSelectListState.error }) {
            View {
                attr {
                    flex(1f)
                    allCenter()
                }
                Text {
                    attr {
                        text("trash_load_failed".tr)
                        fontSize(12f)
                        fontFamily("Poppins-Medium")
                        color(SDColors.white50)
                        textAlignCenter()
                    }
                }
                View {
                    attr {
                        marginTop(16f)
                        height(34f)
                        padding(0f, 16f, 0f, 16f)
                        borderRadius(100f)
                        backgroundColor(SDColors.white10)
                        allCenter()
                    }
                    event { click { onRetry() } }
                    Text {
                        attr {
                            text("retry".tr)
                            fontSize(12f)
                            fontFamily("Poppins-SemiBold")
                            color(SDColors.white80)
                        }
                    }
                }
            }
        }
        velse {
            View {
                attr { flex(1f) }
                content()
            }
        }
    }
}

/** Dart `MusicSelectEmptyState`。 */
private fun ViewContainer<*, *>.musicSelectEmptyState(ctx: MusicSelectSheetView, tab: MusicSelectTab) {
    val s = ctx.state
    View {
        attr {
            flex(1f)
            allCenter()
        }
        Image {
            attr {
                size(80f, 80f)
                src(ImageUri.commonAssets(AssetsImage.audioEmpty))
            }
        }
        Text {
            attr {
                marginTop(12f)
                val type = s.emptyTypeForTab(tab)
                text(
                    when (type) {
                        MusicSelectEmptyType.my -> "dialog_no_songs_yet".tr
                        MusicSelectEmptyType.upload -> "dialog_please_upload_song".tr
                        MusicSelectEmptyType.search -> "dialog_no_music_found".tr
                    },
                )
                fontSize(12f)
                fontFamily(if (type == MusicSelectEmptyType.search) "Poppins-SemiBold" else "Poppins-Medium")
                lineHeight(19f)
                color(SDColors.white50)
                textAlignCenter()
            }
        }
        vif({ s.emptyTypeForTab(tab) == MusicSelectEmptyType.my }) {
            View {
                attr {
                    marginTop(12f)
                    height(34f)
                    minWidth(94f)
                    padding(0f, 12f, 0f, 12f)
                    borderRadius(100f)
                    backgroundColor(SDColors.primaryBtn)
                    allCenter()
                }
                event { click { s.onTryNow() } }
                Text {
                    attr {
                        text("try_now".tr)
                        fontSize(12f)
                        fontFamily("Poppins-SemiBold")
                        color(SDColors.primaryBtnText)
                    }
                }
            }
        }
    }
}

private fun songsOf(s: MusicSelectState, tab: MusicSelectTab) =
    if (tab == MusicSelectTab.my) s.dataList else s.filteredTrendingList

/** 歌曲 Tab（我的 / 热门 / 歌曲搜索结果）。 */
private fun ViewContainer<*, *>.songTabBody(ctx: MusicSelectSheetView, tab: MusicSelectTab, refreshable: Boolean) {
    val s = ctx.state
    val onRetry: () -> Unit = {
        if (tab == MusicSelectTab.my) s.pager.launch { s.refreshMyTab() } else s.pager.launch { s.refreshTrendingTab() }
    }
    listBody(ctx, tab, onRetry) {
        if (refreshable && tab == MusicSelectTab.my) {
            SDRefreshList {
                ref { ctx.myListRef = it }
                attr { flex(1f) }
                event {
                    onRefresh {
                        s.pager.launch { s.onRefresh { _, noMore -> ctx.myListRef?.view?.endRefresh(noMore) } }
                    }
                    onLoadMore {
                        s.pager.launch { s.onLoad { ok, noMore -> ctx.myListRef?.view?.endLoadMore(noMore, ok) } }
                    }
                }
                items {
                    vfor({ songsOf(s, tab) }) { song ->
                        View {
                            // Dart separatorBuilder：16 的间隔只在两项之间
                            attr { marginTop(if (songsOf(s, tab).firstOrNull() === song) 0f else 16f) }
                            musicSelectSongItem(s, song)
                        }
                    }
                }
            }
        } else {
            List {
                attr {
                    flex(1f)
                    showScrollerIndicator(false)
                }
                vfor({ songsOf(s, tab) }) { song ->
                    View {
                        // Dart separatorBuilder：16 的间隔只在两项之间
                        attr { marginTop(if (songsOf(s, tab).firstOrNull() === song) 0f else 16f) }
                        musicSelectSongItem(s, song)
                    }
                }
            }
        }
    }
}

/** 上传 Tab。 */
private fun ViewContainer<*, *>.uploadTabBody(ctx: MusicSelectSheetView, refreshable: Boolean) {
    val s = ctx.state
    listBody(ctx, MusicSelectTab.upload, { s.pager.launch { s.refreshUploadTab() } }) {
        if (refreshable) {
            SDRefreshList {
                ref { ctx.uploadListRef = it }
                attr { flex(1f) }
                event {
                    onRefresh {
                        s.pager.launch { s.onUploadRefresh { _, noMore -> ctx.uploadListRef?.view?.endRefresh(noMore) } }
                    }
                    onLoadMore {
                        s.pager.launch { s.onUploadLoad { ok, noMore -> ctx.uploadListRef?.view?.endLoadMore(noMore, ok) } }
                    }
                }
                items {
                    vfor({ s.visibleUploadList }) { item ->
                        View {
                            attr { marginTop(if (s.visibleUploadList.firstOrNull() === item) 0f else 16f) }
                            musicSelectUploadItem(s, item)
                        }
                    }
                }
            }
        } else {
            List {
                attr {
                    flex(1f)
                    showScrollerIndicator(false)
                }
                vfor({ s.visibleUploadList }) { item ->
                    View {
                        attr { marginTop(if (s.visibleUploadList.firstOrNull() === item) 0f else 16f) }
                        musicSelectUploadItem(s, item)
                    }
                }
            }
        }
    }
}

// ============================================================================ 行

/** 40×28 胶囊按钮（白 10%、圆角 55）。 */
private fun ViewContainer<*, *>.capsule(
    loading: () -> Boolean,
    icon: () -> String,
    onTap: () -> Unit,
) {
    View {
        attr {
            size(40f, 28f)
            borderRadius(55f)
            backgroundColor(SDColors.white10)
            allCenter()
        }
        event { click { if (!loading()) onTap() } }
        vif({ loading() }) {
            SDLoading {
                attr {
                    size = 12f
                    color = Color.WHITE
                }
            }
        }
        velse {
            Image {
                attr {
                    size(14f, 14f)
                    src(ImageUri.commonAssets(icon()))
                }
            }
        }
    }
}

/** Dart `MusicSelectSongListItem`。 */
private fun ViewContainer<*, *>.musicSelectSongItem(s: MusicSelectState, song: SongModel) {
    val key = song.musicAudioUrl ?: ""
    View {
        attr {
            flexDirectionRow()
            alignItemsCenter()
        }
        event { click { s.selectSong(song) } }
        SDImage {
            attr {
                size(54f, 54f)
                borderRadius(12f)
                url = song.musicCoverUrl ?: ""
            }
        }
        View {
            attr {
                flex(1f)
                marginLeft(10f)
            }
            Text {
                attr {
                    text((song.musicTitle ?: "").replace("\n", ""))
                    fontSize(14f)
                    fontFamily("Poppins-Medium")
                    color(Color.WHITE)
                    lines(1)
                    textOverFlowTail()
                }
            }
            Text {
                attr {
                    marginTop(8f)
                    text(song.musicDurationStr ?: "")
                    fontSize(10f)
                    fontFamily("Poppins-Medium")
                    color(SDColors.white40)
                }
            }
        }
        capsule(
            loading = { s.isPlaybackLoading(key) },
            icon = { if (s.playingKey == key && s.isPlaying) AssetsImage.itemPause1 else AssetsImage.play2 },
        ) { s.playSongPreview(song) }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${kotlin.math.round(bytes / 1024.0).toLong()}K"
    else -> "${kotlin.math.round(bytes / (1024.0 * 1024.0)).toLong()}M"
}

private fun formatDuration(ms: Int?): String {
    if (ms == null) return "00:00"
    val total = ms / 1000
    return "${(total / 60).toString().padStart(2, '0')}:${(total % 60).toString().padStart(2, '0')}"
}

private fun uploadActionIcon(item: AudioUploadItem, playing: Boolean): String = when (item.status) {
    AudioItemStatus.failed -> AssetsImage.uploadDel1
    AudioItemStatus.paused -> AssetsImage.uploadUp1
    AudioItemStatus.completed -> if (playing) AssetsImage.itemPause1 else AssetsImage.play2
    AudioItemStatus.waiting -> AssetsImage.uploadAwait
    else -> AssetsImage.itemPause1
}

/** Dart `MusicSelectUploadListItem`。 */
private fun ViewContainer<*, *>.musicSelectUploadItem(s: MusicSelectState, item: AudioUploadItem) {
    val key = item.playbackKey
    val isCompleted = item.status == AudioItemStatus.completed
    val isFailed = item.status == AudioItemStatus.failed
    val isPaused = item.status == AudioItemStatus.paused
    val isActive = item.status.isActive || isPaused
    val canDeleteInMode = isCompleted || isFailed || isPaused
    View {
        attr {
            flexDirectionRow()
            alignItemsCenter()
        }
        event {
            click {
                // 删除模式禁用选中
                if (!s.isUploadDeleteMode && isCompleted) s.selectUploadItem(item)
            }
        }
        Image {
            attr {
                size(54f, 54f)
                src(ImageUri.commonAssets(if (isCompleted) AssetsImage.uploadItemIcon else AssetsImage.uploadAudio1))
            }
        }
        View {
            attr {
                flex(1f)
                marginLeft(10f)
            }
            Text {
                attr {
                    text(item.fileName)
                    fontSize(14f)
                    fontFamily("Poppins-Medium")
                    color(Color.WHITE)
                    lines(1)
                    textOverFlowTail()
                }
            }
            if (isActive) {
                View {
                    attr {
                        marginTop(6f)
                        size(178f, 4f)
                        borderRadius(256f)
                        backgroundColor(SDColors.white10)
                        overflow(true)
                    }
                    View {
                        attr {
                            height(4f)
                            width(178f * item.progress.toFloat().coerceIn(0f, 1f))
                            backgroundLinearGradient(
                                Direction.TO_RIGHT,
                                ColorStop(SDColors.accentPurple, 0f),
                                ColorStop(SDColors.brandPurple, 1f),
                            )
                        }
                    }
                }
                Text {
                    attr {
                        marginTop(6f)
                        text("${formatFileSize(item.fileSize)} · ${formatDuration(item.duration)}")
                        fontSize(10f)
                        fontFamily("Poppins-Medium")
                        color(SDColors.white40)
                    }
                }
            } else {
                Text {
                    attr {
                        marginTop(8f)
                        text(if (isFailed) "upload_failed".tr else formatDuration(item.duration))
                        fontSize(10f)
                        fontFamily("Poppins-Medium")
                        color(if (isFailed) SDColors.errorRed else SDColors.white40)
                    }
                }
            }
        }
        View {
            attr { marginLeft(10f) }
            vif({ s.isUploadDeleteMode }) {
                View {
                    // 上传中占位，避免布局跳动
                    if (canDeleteInMode) {
                        capsule(
                            loading = { s.isDeletingItem(key) },
                            icon = { AssetsImage.uploadDel1 },
                        ) { s.onUploadRowAction(item) }
                    } else {
                        View { attr { size(40f, 28f) } }
                    }
                }
            }
            velse {
                capsule(
                    loading = { s.isDeletingItem(key) || (isCompleted && s.isPlaybackLoading(key)) },
                    icon = { uploadActionIcon(item, s.playingKey == key && s.isPlaying) },
                ) { s.onUploadRowAction(item) }
            }
        }
    }
}

// ============================================================================ 上传区 / 头部

/** Dart `MusicSelectUploadZone`：80 高虚线框。 */
private fun ViewContainer<*, *>.musicSelectUploadZone(onTap: () -> Unit) {
    View {
        attr {
            height(UPLOAD_ZONE_HEIGHT)
            backgroundColor(SDColors.white5)
            borderRadius(16f)
            border(Border(1f, BorderStyle.DASHED, SDColors.white10))
            allCenter()
        }
        event { click { onTap() } }
        Image {
            attr {
                size(18f, 18f)
                src(ImageUri.commonAssets(AssetsImage.uploadDance))
            }
        }
        Text {
            attr {
                marginTop(8f)
                text("upload".tr)
                fontSize(10f)
                fontFamily("Poppins-Medium")
                color(SDColors.white80)
            }
        }
    }
}

/** Dart `MusicSelectTabBar`：三个文字 Tab，无指示线。 */
private fun ViewContainer<*, *>.musicSelectTabBar(ctx: MusicSelectSheetView) {
    val s = ctx.state
    View {
        attr {
            flexDirectionRow()
            alignItemsCenter()
        }
        listOf(MusicSelectTab.my to "my", MusicSelectTab.upload to "upload", MusicSelectTab.trending to "trending")
            .forEachIndexed { i, (tab, key) ->
                Text {
                    attr {
                        marginLeft(if (i == 0) 0f else 16f)
                        text(key.tr)
                        fontSize(14f)
                        val active = s.currentTab == tab
                        fontFamily(if (active) "Poppins-SemiBold" else "Poppins")
                        color(if (active) SDColors.white80 else SDColors.white40)
                    }
                    event { click { ctx.tapTab(tab) } }
                }
            }
    }
}

/**
 * Dart `MusicSelectTabSearchHeader`（42 高）：左 Tab 栏，右搜索 / 删除图标；搜索态是整行搜索框 + 「取消」。
 * Dart 的「搜索区向左挤压展开」近似为 220ms 的交叉淡入。
 */
private fun ViewContainer<*, *>.musicSelectTabSearchHeader(ctx: MusicSelectSheetView) {
    val s = ctx.state
    View {
        attr { height(42f) }
        // 收起态：Tab 栏 + 图标
        View {
            attr {
                absolutePositionAllZero()
                flexDirectionRow()
                alignItemsCenter()
                opacity(if (s.isSearchMode) 0f else 1f)
                touchEnable(!s.isSearchMode)
                animate(Animation.easeOut(SEARCH_ANIM_S), s.isSearchMode)
            }
            View {
                attr { flex(1f) }
                musicSelectTabBar(ctx)
            }
            vif({ s.currentTab != MusicSelectTab.trending }) {
                View {
                    attr {
                        size(42f, 42f)
                        allCenter()
                    }
                    event { click { ctx.enterSearch() } }
                    Image {
                        attr {
                            size(18f, 18f)
                            src(ImageUri.commonAssets(AssetsImage.searchIcon2))
                        }
                    }
                }
            }
            vif({ s.currentTab == MusicSelectTab.upload && !s.isSearchMode }) {
                View {
                    attr {
                        size(25f, 42f)
                        alignItemsFlexEnd()
                        justifyContentCenter()
                    }
                    event { click { s.toggleUploadDeleteMode() } }
                    Image {
                        attr {
                            size(18f, 18f)
                            src(ImageUri.commonAssets(AssetsImage.delete))
                            // 删除模式中降低不透明度，提示当前处于管理态
                            opacity(if (s.isUploadDeleteMode) 0.7f else 1f)
                        }
                    }
                }
            }
        }
        // 展开态：搜索框 + 取消
        View {
            attr {
                absolutePositionAllZero()
                flexDirectionRow()
                alignItemsCenter()
                opacity(if (s.isSearchMode) 1f else 0f)
                touchEnable(s.isSearchMode)
                animate(Animation.easeOut(SEARCH_ANIM_S), s.isSearchMode)
            }
            View {
                attr {
                    flex(1f)
                    height(42f)
                    padding(0f, 12f, 0f, 12f)
                    borderRadius(100f)
                    backgroundColor(SDColors.white5)
                    border(Border(1f, BorderStyle.SOLID, SDColors.white10))
                    flexDirectionRow()
                    alignItemsCenter()
                }
                Image {
                    attr {
                        size(18f, 18f)
                        src(ImageUri.commonAssets(AssetsImage.searchIcon2))
                    }
                }
                SDInput {
                    ref { ctx.searchInputRef = it }
                    attr {
                        flex(1f)
                        marginLeft(10f)
                        height(40f)
                        contentPaddingH = 0f
                        fontSize = 14f
                        color = Color.WHITE
                        hintColor = SDColors.white40
                        cursorColor = Color.WHITE
                        placeholder = "search_music".tr
                        returnKey = com.sondo.app.ui.input.SDReturnKey.SEARCH
                    }
                    event {
                        onChanged { s.onSearchTextUpdated(it) }
                        onKeyboardHeight { ctx.onKeyboard(it) }
                    }
                }
                vif({ s.searchKeyword.isNotEmpty() }) {
                    Image {
                        attr {
                            size(20f, 20f)
                            src(ImageUri.commonAssets(AssetsImage.close3))
                        }
                        event { click { ctx.clearSearch() } }
                    }
                }
            }
            Text {
                attr {
                    marginLeft(10f)
                    text("cancel".tr)
                    fontSize(12f)
                    fontFamily("Poppins-Medium")
                    color(SDColors.white80)
                    lines(1)
                }
                event { click { ctx.cancelSearch() } }
            }
        }
    }
}
