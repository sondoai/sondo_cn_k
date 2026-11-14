package com.sondo.app.features.square

import com.sondo.app.ui.works.SDToggleIcon
import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.constants.asset
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.formIntText
import com.sondo.app.data.models.MvModel
import com.sondo.app.data.models.VideoContentEntity
import com.sondo.app.data.models.toEntity
import com.sondo.app.data.models.toMvModel
import com.sondo.app.features.square.data.SquareApi
import com.sondo.app.features.square.data.SquarePlayArgs
import com.sondo.app.features.square.data.SquareProfileArgs
import com.sondo.app.data.models.toSquareModel
import com.sondo.app.ui.video.SDListMvVideoPlayer
import com.sondo.app.ui.video.VideoIcon
import com.sondo.app.ui.video.VideoIconKind
import com.sondo.app.nativeapi.SDAppModule
import com.sondo.app.service.MvItemType
import com.sondo.app.service.MvService
import com.sondo.app.service.PlayerService
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDLoading
import com.sondo.app.ui.nav.SDNavBack
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.vforIndex
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.PageList
import com.tencent.kuikly.core.views.PageListView
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/** 一条视频的互动数据（随事件变化；与播放器实体分开存放，改动时不重建播放器）。 */
internal data class SquareVideoStats(
    val isCollect: Boolean,
    val collectNum: Int,
    val repostCount: Int,
    val mvHitNum: Int,
)

/**
 * 广场全屏竖滑播放（Dart `SquarePlayPage(pageId:)` + `SquarePlayController`）。
 * [pageId] 区分 `Routes.squarePlay`（可加载更多、显示作者头像）与 `Routes.squarePlayDetail`（不显示头像；
 * 调用方一般传 `isLoadMore=false`）。
 *
 * 页面参数见 [SquarePlayArgs]：
 * `{videoType, listKey | videoList, initialIndex, pageNo, isFindHot, imageComp, isIndex, isLoadMore, showAuthorActions}`；
 * 用 [SquareNav.openPlay] / [SquareNav.openPlayDetail] 打开即可（列表经进程内 holder 交接）。
 */
internal abstract class SquarePlayPageBase(val pageId: String) : BasePager() {

    // —— 列表与索引 —— //
    private val videoList by observableList<VideoContentEntity>()
    private var currentIndex by observable(0)
    private var isScrolling by observable(false)
    /** 导航到个人主页期间为 true：卸载播放器释放解码器。 */
    private var isPageSuspended by observable(false)
    /** 页面在前台且应用在前台。 */
    internal var appForeground by observable(true)
    /** 退到后台时置位，回前台不清：Dart 后台把所有视频标成暂停，回来需用户点一下才播。滑页 / 点击清除。 */
    internal var pausedByBackground by observable(false)
    private var loadMoreFailed by observable(false)
    private var statsVersion by observable(0)
    private var sharingUrls by observable<Set<String>>(emptySet())

    private val stats = HashMap<Long, SquareVideoStats>()
    private val reportedViewMvIds = HashSet<Long>()
    private var pageListRef: ViewRef<PageListView<*, *>>? = null

    // —— 分页参数 —— //
    private var pageNo = 1
    private var isFindHot by observable(0)
    private var isIndex = 0
    private var imageComp = ""
    private var hasMore = true
    private var isLoadingMore = false
    private var videoType = 0
    private var initialIndex = 0
    private var isLoadMore = true
    private var showAuthorActions = true

    /** 顶部去创作始终展示。 */
    private val showCreateButton: Boolean get() = true

    /** 当前条有非空白剧情时显示做同款，否则是创建视频。 */
    private val createActionLabel: String
        get() {
            val index = currentIndex
            if (index < 0 || index >= videoList.size) return "create_mv".tr
            return if (videoList[index].canMakeSame) "make_same_mv".tr else "create_mv".tr
        }

    private val areaHeight: Float get() = screenHeight - statusBarHeight - safeBottom

    override fun pageBackgroundColor(): Color = Color.BLACK

    override fun created() {
        super.created()
        appModule()?.setKeepScreenOn(true)
        PlayerService.launch { PlayerService.pause() }

        val args = SquarePlayArgs.fromJson(pagerData.params)
        videoType = args.videoType
        args.videoList.forEach { putStats(it) }
        videoList.addAll(args.videoList)
        initialIndex = args.initialIndex.coerceIn(0, (args.videoList.size - 1).coerceAtLeast(0))
        currentIndex = initialIndex
        pageNo = args.pageNo
        isFindHot = args.isFindHot
        imageComp = args.imageComp
        isIndex = args.isIndex
        isLoadMore = args.isLoadMore
        showAuthorActions = args.showAuthorActions
        DevLog.logger("SquarePlay: 接收到 ${videoList.size} 个视频, 初始索引: $initialIndex, 页码: $pageNo")
        prefetchNeighbours(currentIndex)

        on(Events.MV_LIST_CHANGED) { json ->
            val item = json.optJSONObject("item") ?: return@on
            updateVideoList(json.optString("type", ""), MvModel.fromJson(item).toEntity())
        }
        on(Events.MV_BUSY_CHANGED) { json ->
            val arr = json.optJSONArray("sharing")
            val set = LinkedHashSet<String>()
            if (arr != null) for (i in 0 until arr.length()) set.add(arr.optString(i, "") ?: "")
            sharingUrls = set
        }
        on(Events.APP_LIFECYCLE) { json ->
            appForeground = json.optBoolean("foreground", true)
            if (!appForeground) pausedByBackground = true
        }
        sharingUrls = MvService.sharingUrls
    }

    override fun pageWillDestroy() {
        appModule()?.setKeepScreenOn(false)
        super.pageWillDestroy()
    }

    private fun appModule(): SDAppModule? = try {
        acquireModule(SDAppModule.MODULE_NAME)
    } catch (e: Throwable) {
        null
    }

    private fun putStats(v: VideoContentEntity) {
        stats[v.id] = SquareVideoStats(v.isCollect, v.collectNum, v.repostCount, v.mvHitNum)
    }

    /** 读当前互动数据（依赖 [statsVersion] 刷新）。 */
    internal fun statsOf(video: VideoContentEntity): SquareVideoStats {
        statsVersion
        return stats[video.id] ?: SquareVideoStats(video.isCollect, video.collectNum, video.repostCount, video.mvHitNum)
    }

    /** 当前实体 + 最新互动数据。 */
    private fun latest(index: Int): VideoContentEntity {
        val v = videoList[index]
        val s = stats[v.id] ?: return v
        return v.copy(isCollect = s.isCollect, collectNum = s.collectNum, repostCount = s.repostCount, mvHitNum = s.mvHitNum)
    }

    // —— 翻页 —— //

    /** 页面完全停稳后切换当前索引。 */
    private fun onPageChanged(index: Int) {
        if (index < 0 || index >= videoList.size) return
        if (index == currentIndex) return
        currentIndex = index
        pausedByBackground = false
        prefetchNeighbours(index)
        checkAndLoadMore(index)
    }

    /** 相邻页预热磁盘缓存（只有当前页挂播放器）。 */
    private fun prefetchNeighbours(index: Int) {
        for (i in listOf(index - 1, index + 1)) {
            if (i in 0 until videoList.size) {
                com.sondo.app.service.VideoCacheService.prefetchInBackground(videoList[i].resultUrl)
            }
        }
    }

    private fun checkAndLoadMore(index: Int) {
        if (!isLoadMore) return
        val threshold = videoList.size - 4
        if (index >= threshold && !isLoadingMore && hasMore && !loadMoreFailed) {
            DevLog.logger("到达倒数第4个，自动加载下一页")
            loadMoreVideos()
        }
    }

    private fun loadMoreVideos() {
        if (isLoadingMore || !hasMore) return
        isLoadingMore = true
        loadMoreFailed = false
        pageNo++
        launch {
            val result = SquareApi.getPublishedList(
                pageNo = pageNo,
                isFindHot = isFindHot,
                imageComp = imageComp.ifEmpty { null },
                isIndex = isIndex,
            )
            result.fold(
                onSuccess = { data ->
                    if (data.total == -1) {
                        pageNo--
                        loadMoreFailed = true
                    } else if (data.list.isNotEmpty()) {
                        // 首页进来的列表不带空视频，翻页时同样丢掉
                        val incoming = if (showAuthorActions) data.list else data.list.filter { it.resultUrl.trim().isNotEmpty() }
                        val known = HashSet<Long>()
                        videoList.forEach { known.add(it.id) }
                        val add = incoming.filter { known.add(it.id) }
                        add.forEach { putStats(it) }
                        if (add.isNotEmpty()) videoList.addAll(add)
                        hasMore = data.list.size >= 10
                        loadMoreFailed = false
                        DevLog.logger("加载更多成功，新增 ${data.list.size} 个视频")
                    } else {
                        hasMore = false
                    }
                },
                onFailure = { e ->
                    DevLog.logger("加载更多异常: ${e.message}")
                    pageNo--
                    loadMoreFailed = true
                },
            )
            isLoadingMore = false
        }
    }

    private fun manualLoadMore() {
        if (loadMoreFailed) {
            loadMoreFailed = false
            hasMore = true
            loadMoreVideos()
        }
    }

    // —— 播放进度 / 上报 —— //

    private fun onVideoProgress(index: Int, positionMs: Long) {
        if (index < 0 || index >= videoList.size) return
        val videoId = videoList[index].id
        if (positionMs >= 10_000 && !reportedViewMvIds.contains(videoId)) {
            reportedViewMvIds.add(videoId)
            reportVideoView(videoId, index)
        }
    }

    private fun reportVideoView(videoId: Long, index: Int) {
        try {
            val cur = latest(index)
            val videoItem = cur.copy(mvHitNum = cur.mvHitNum + 1)
            // 轮播转来的条目是负数 id，不是真实 MV
            if (videoId > 0) launch { SquareApi.reportMvHit(videoId) }
            MvService.refreshMv("play", videoItem.toMvModel())
        } catch (e: Throwable) {
            DevLog.logger("上报播放次数失败: $e")
            reportedViewMvIds.remove(videoId)
        }
    }

    // —— 互动 —— //

    private fun toggleLike(index: Int) {
        if (index < 0 || index >= videoList.size) return
        MvService.onTap(MvItemType.like, latest(index).toMvModel(), emptyList())
    }

    private fun share(index: Int) {
        val video = latest(index)
        launch { MvService.onShareMv(video.toMvModel(), video.resultUrl, isFullLoading = false) }
    }

    private fun reportVideo(index: Int) {
        MvService.reportMv(latest(index).toMvModel())
    }

    /** 跨页同步收藏 / 分享 / 播放数。 */
    private fun updateVideoList(type: String, item: VideoContentEntity) {
        if (!item.isMv || videoType != 0) return
        val old = stats[item.id] ?: return
        val next = when (type) {
            "like" -> old.copy(isCollect = item.isCollect, collectNum = item.collectNum)
            "share" -> old.copy(repostCount = item.repostCount)
            "play" -> old.copy(mvHitNum = item.mvHitNum)
            else -> return
        }
        stats[item.id] = next
        statsVersion++
    }

    private fun toCreateMv() {
        if (currentIndex < 0 || currentIndex >= videoList.size) return
        val video = videoList[currentIndex]
        // 有剧情才带进创作页，空值走原来的创建视频
        MvService.toCreateMv(customStoryPlot = if (video.canMakeSame) video.customStoryPlot else null)
        UmengUtils.logEventButtonClick("square_play_create_mv")
    }

    private fun toProfile(userId: Long) {
        isPageSuspended = true
        SquareNav.openProfile(this, SquareProfileArgs(userId = userId)) {
            isPageSuspended = false
        }
        // 栈内已有个人主页时本页会被关掉，不会再回来
    }

    // —— UI —— //

    /** `w:h` 画幅 → 宽高比；解析失败按 9:16。 */
internal fun videoRatio(imageComp: String): Float {
    val parts = imageComp.split(":")
    if (parts.size == 2) {
        val w = parts[0].trim().toFloatOrNull()
        val h = parts[1].trim().toFloatOrNull()
        if (w != null && h != null && w > 0f && h > 0f) return w / h
    }
    return 9f / 16f
}

/** 视频在可视区域里的位置（上下各留 12）。返回 left, top, width, height。 */
    internal fun videoRect(comp: String): FloatArray {
        val ratio = videoRatio(comp)
        val boxW = screenWidth
        val boxH = (areaHeight - 24f).coerceIn(0f, areaHeight)
        val vw: Float
        val vh: Float
        if (boxH <= 0f || boxW / boxH > ratio) {
            vh = boxH
            vw = boxH * ratio
        } else {
            vw = boxW
            vh = boxW / ratio
        }
        return floatArrayOf((boxW - vw) / 2f, 12f + (boxH - vh) / 2f, vw, vh)
    }

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            vif({ ctx.videoList.isEmpty() }) {
                View {
                    attr {
                        flex(1f)
                        backgroundColor(Color.BLACK)
                        allCenter()
                    }
                    SDLoading { attr { color = Color(0xFF7F58F7L) } }
                }
            }
            vif({ ctx.videoList.isNotEmpty() }) {
                View {
                    attr {
                        flex(1f)
                        marginTop(ctx.statusBarHeight)
                        marginBottom(ctx.safeBottom)
                    }
                    PageList {
                        ref { ctx.pageListRef = it }
                        attr {
                            flex(1f)
                            pageDirection(false)
                            pageItemWidth(ctx.screenWidth)
                            pageItemHeight(ctx.areaHeight)
                            defaultPageIndex(ctx.initialIndex)
                            showScrollerIndicator(false)
                            keepItemAlive(false)
                            offscreenPageLimit(1)
                        }
                        event {
                            dragBegin { ctx.isScrolling = true }
                            scrollEnd { ctx.isScrolling = false }
                            pageIndexDidChanged {
                                val index = (it as? JSONObject)?.optInt("index", 0) ?: 0
                                ctx.onPageChanged(index)
                            }
                        }
                        vforIndex({ ctx.videoList }) { video, index, _ ->
                            View {
                                attr { size(ctx.screenWidth, ctx.areaHeight) }
                                SDListMvVideoPlayer {
                                    attr {
                                        absolutePositionAllZero()
                                        mv = video.toSquareModel()
                                        this.index = index
                                        isCurrentPage = !ctx.isPageSuspended && ctx.currentIndex == index
                                        isPreloading = kotlin.math.abs(ctx.currentIndex - index) == 1
                                        isScrolling = ctx.isScrolling
                                        // 应用退到后台时暂停（页面被盖住由播放器自己处理）
                                        forcePaused = !ctx.appForeground || ctx.pausedByBackground
                                        showProgress = true
                                        // 成片水印已去掉才盖 UI 标识；0 仍带水印，不重复盖
                                        showAiLabel = video.isWatermark == 1
                                    }
                                    event {
                                        onProgress { i, pos, _ -> ctx.onVideoProgress(i, pos) }
                                    }
                                }
                                // 后台回来保持暂停：盖一层透明点击区，点一下才恢复（Dart playOrPauseVideo）
                                vif({ ctx.pausedByBackground && ctx.currentIndex == index }) {
                                    View {
                                        attr { absolutePositionAllZero() }
                                        event { click { ctx.pausedByBackground = false } }
                                    }
                                }
                                squarePlayRightActions(ctx, video, index)
                            }
                        }
                    }
                    squarePlayTopBar(ctx)
                    vif({ ctx.loadMoreFailed && ctx.currentIndex == ctx.videoList.size - 1 }) {
                        View {
                            attr {
                                absolutePosition(left = 0f, right = 0f, bottom = 20f)
                                allCenter()
                            }
                            View {
                                attr {
                                    flexDirectionRow()
                                    alignItemsCenter()
                                    padding(8f, 16f, 8f, 16f)
                                    backgroundColor(Color(0, 0, 0, 0.5f))
                                    borderRadius(20f)
                                }
                                event { click { ctx.manualLoadMore() } }
                                VideoIcon(VideoIconKind.REFRESH, 16f, Color.WHITE)
                                Text {
                                    attr {
                                        marginLeft(8f)
                                        text("pull_up_to_load_more".tr)
                                        fontSize(12f)
                                        color(Color.WHITE)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 供顶层构建函数访问
    internal val showCreate get() = showCreateButton
    internal val createLabel get() = createActionLabel
    internal fun onTapCreate() = toCreateMv()
    internal val hotMode get() = isFindHot == 1
    internal val authorActions get() = showAuthorActions
    internal fun isSharing(url: String) = url.isNotEmpty() && sharingUrls.contains(url)
    internal fun onTapAvatar(userId: Long) = toProfile(userId)
    internal fun onTapLike(index: Int) = toggleLike(index)
    internal fun onTapShare(index: Int) = share(index)
    internal fun onTapReport(index: Int) = reportVideo(index)
}

private fun ViewContainer<*, *>.squarePlayTopBar(ctx: SquarePlayPageBase) {
    View {
        attr {
            absolutePosition(top = 0f, left = 0f, right = 0f)
            height(56f)
            flexDirectionRow()
            alignItemsCenter()
            justifyContentSpaceBetween()
        }
        View {
            attr { marginLeft(4f) }
            SDNavBack()
        }
        if (ctx.showCreate) {
            View {
                attr {
                    height(32f)
                    marginRight(12f)
                    padding(0f, 12f, 0f, 12f)
                    borderRadius(32f)
                    backgroundColor(UIColor.cF3EFF7)
                    allCenter()
                }
                event { click { ctx.onTapCreate() } }
                Text {
                    attr {
                        text(ctx.createLabel)
                        fontSize(12f)
                        color(UIColor.c0A0A0A)
                        fontFamily("Poppins-Medium")
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.squarePlayRightActions(ctx: SquarePlayPageBase, video: VideoContentEntity, index: Int) {
    val rect = ctx.videoRect(video.imageComp)
    val right = (ctx.screenWidth - (rect[0] + rect[2])) + 16f
    View {
        attr {
            absolutePosition(right = right, bottom = if (AppConstants.enableMvDanceShare) 80f else 150f)
            alignItemsCenter()
        }
        if (ctx.pageId == Routes.squarePlay && ctx.authorActions) {
            View {
                attr {
                    size(50f, 50f)
                    borderRadius(25f)
                    border(Border(1f, BorderStyle.SOLID, UIColor.cFFF50))
                    allCenter()
                    marginBottom(24f)
                }
                event { click { ctx.onTapAvatar(video.userId) } }
                SDImage {
                    attr {
                        size(48f, 48f)
                        borderRadius(24f)
                        url = video.avatar
                    }
                }
            }
        }
        if (ctx.authorActions) {
            View {
                attr { marginBottom(24f) }
                squarePlayActionButton(
                    ctx,
                    icon = { AssetsImage.like21 },
                    activeIcon = AssetsImage.likeActive2,
                    isActive = { ctx.statsOf(video).isCollect },
                    num = { ctx.statsOf(video).collectNum },
                    showNum = true,
                ) { ctx.onTapLike(index) }
            }
        }
        if (AppConstants.enableMvDanceShare) {
            View {
                attr { marginBottom(24f) }
                vif({ ctx.isSharing(video.resultUrl) }) {
                    View {
                        attr {
                            size(32f, 32f)
                            allCenter()
                        }
                        SDLoading { attr { size = 24f } }
                    }
                }
                vif({ !ctx.isSharing(video.resultUrl) }) {
                    View {
                        squarePlayActionButton(ctx, icon = { AssetsImage.share3 }, num = { ctx.statsOf(video).repostCount }, showNum = true) {
                            ctx.onTapShare(index)
                        }
                    }
                }
            }
        }
        squarePlayActionButton(ctx, icon = { AssetsImage.reportMv2 }, num = { 0 }, showNum = false) { ctx.onTapReport(index) }
    }
}

private fun ViewContainer<*, *>.squarePlayActionButton(
    ctx: SquarePlayPageBase,
    icon: () -> String,
    num: () -> Int,
    showNum: Boolean,
    activeIcon: String? = null,
    isActive: () -> Boolean = { false },
    onTap: () -> Unit,
) {
    View {
        attr { alignItemsCenter() }
        event { click { onTap() } }
        if (activeIcon != null) {
            // 两态（收藏）：两张图叠放只切透明度，切换不闪
            SDToggleIcon(32f, isActive, activeIcon, icon())
        } else {
            Image {
                attr {
                    size(32f, 32f)
                    src(icon().asset())
                }
            }
        }
        if (ctx.hotMode) {
            View { attr { height(10f) } }
        } else if (showNum) {
            Text {
                attr {
                    text(num().formIntText())
                    fontSize(12f)
                    color(Color.WHITE)
                    lines(1)
                    fontWeightMedium()
                }
            }
        }
    }
}

/** 广场全屏播放（可加载更多）。参数见 [SquarePlayPageBase]。 */
@Page(Routes.squarePlay)
internal class SquarePlayPage : SquarePlayPageBase(Routes.squarePlay)

/** 详情全屏播放（个人主页作品入口，不加载更多）。参数见 [SquarePlayPageBase]。 */
@Page(Routes.squarePlayDetail)
internal class SquarePlayDetailPage : SquarePlayPageBase(Routes.squarePlayDetail)
