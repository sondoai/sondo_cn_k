package com.sondo.app.features.square.profile

import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.asset
import com.sondo.app.core.http.ApiResult
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.formIntText
import com.sondo.app.data.models.MvModel
import com.sondo.app.data.models.PageResult
import com.sondo.app.data.models.SongEntity
import com.sondo.app.data.models.SongModel
import com.sondo.app.data.models.VideoContentEntity
import com.sondo.app.data.models.toEntity
import com.sondo.app.data.models.toModel
import com.sondo.app.data.models.toMvModel
import com.sondo.app.features.square.SquareNav
import com.sondo.app.features.square.data.SquareApi
import com.sondo.app.features.square.data.SquarePlayArgs
import com.sondo.app.features.square.data.SquareProfileArgs
import com.sondo.app.features.square.data.SquareProfileUserEntity
import com.sondo.app.service.MusicItemType
import com.sondo.app.service.MusicService
import com.sondo.app.service.MvItemType
import com.sondo.app.service.MvService
import com.sondo.app.service.UserStore
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDLoading
import com.sondo.app.ui.atoms.SDShimmer
import com.sondo.app.ui.dialog.showImagePreview
import com.sondo.app.ui.list.PagingState
import com.sondo.app.ui.list.SDRefreshList
import com.sondo.app.ui.list.SDRefreshListView
import com.sondo.app.ui.nav.SDTabBar
import com.sondo.app.ui.works.SDMusicItem
import com.sondo.app.ui.works.SDMvItem
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.sondo.app.core.utils.delayMs
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Blur
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.PageList
import com.tencent.kuikly.core.views.PageListView
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 广场个人主页（Dart `SquareProfilePage` + `SquareProfileController`）：上推收起用户信息，Tab + 作品列表在毛玻璃卡片内。
 *
 * 页面参数（[SquareProfileArgs]）：`{userId: Long（也接受字符串）, type: "mv"|"music" = "mv"}`（旧的 "dance" 落到视频 Tab）。
 * 从其它页面打开请用 [SquareNav.openProfile]：栈内已有个人主页时回到它并经 [Events.SQUARE_PROFILE_RELOAD] 重载。
 *
 * NestedScrollView 的移植：两个 Tab 各自一个列表，列表头部放用户信息 + Tab 条；
 * 滚过头部后在顶部显示一份吸顶 Tab 条；切 Tab 时同步另一列表的头部位置。
 */
@Page(Routes.profile)
internal class SquareProfilePage : BasePager() {
    /** 入口类型：mv / music。 */
    internal var type by observable("")
    internal var userId by observable(0L)
    internal var nickname by observable("")
    internal var avatar by observable("")

    /** 浏览量、点赞数；-1 表示加载中（骨架）。 */
    internal var totalViews by observable(-1)
    internal var totalLikes by observable(-1)

    internal var tabIndex by observable(0)
    /** Tab 横滑插值位置 0..1。 */
    internal var tabPage by observable(0f)
    /** 当前 Tab 列表的头部滚动偏移（已夹到 0..headerCollapseExtent）。 */
    internal var headerOffset by observable(0f)

    private val tabOffsets = floatArrayOf(0f, 0f)
    private var pageListRef: ViewRef<PageListView<*, *>>? = null
    internal val listRefs = arrayOfNulls<ViewRef<SDRefreshListView>>(2)
    private var loadGen = 0

    internal val videoMvPaging = PagingState<VideoContentEntity>(this, pageSize = 20, itemKey = { it.id }) { page, size ->
        loadMvData(page, size)
    }
    internal val songPaging = PagingState<SongEntity>(this, pageSize = 20, itemKey = { it.id }) { page, size ->
        loadSongData(page, size)
    }

    internal val isProfileLoading: Boolean get() = totalViews < 0
    internal val isMy: Boolean get() = userId == UserStore.userInfo?.id

    override fun pageBackgroundColor(): Color = Color(0xFF131313L)

    override fun created() {
        super.created()
        on(Events.SQUARE_PROFILE_RELOAD) { reload(SquareProfileArgs.fromJson(it)) }
        on(Events.MV_LIST_CHANGED) { json ->
            val item = json.optJSONObject("item") ?: return@on
            updateMvList(json.optString("type", ""), MvModel.fromJson(item))
        }
        on(Events.MUSIC_LIST_CHANGED) { json ->
            val item = json.optJSONObject("item") ?: return@on
            updateMusicList(json.optString("type", ""), SongModel.fromJson(item))
        }
        initData(SquareProfileArgs.fromJson(pagerData.params))
    }

    /** 栈内复用当前页时按新参数重载。 */
    private fun reload(args: SquareProfileArgs) {
        videoMvPaging.clearData()
        songPaging.clearData()
        totalViews = -1
        totalLikes = -1
        nickname = ""
        avatar = ""
        initData(args)
    }

    private fun initData(args: SquareProfileArgs) {
        val gen = ++loadGen
        userId = args.userId
        type = args.type
        when (type) {
            "music" -> selectTab(1, animated = false)
            else -> {
                // 舞蹈入口已下线，落到视频 Tab
                videoMvPaging.refresh()
                selectTab(0, animated = false)
            }
        }
        launch {
            getUserInfo(gen)
            if (gen != loadGen) return@launch
            if (type == "music") songPaging.refresh()
        }
    }

    /** 拉取用户资料，失败时最多重试 3 次。 */
    private suspend fun getUserInfo(gen: Int) {
        var user: SquareProfileUserEntity? = null
        for (attempt in 0 until 3) {
            if (gen != loadGen) return
            val result = SquareApi.getUserInfo(userId)
            if (gen != loadGen) return
            if (result is ApiResult.Success) {
                user = result.data
                break
            }
            if (attempt < 2) delayMs(1000)
        }
        val u = user ?: return
        if (gen != loadGen) return
        totalViews = u.totalViews
        totalLikes = u.totalLikes
        nickname = u.nickname
        avatar = u.avatar
    }

    // Dart PagingController 的 loadData 失败返回 []：同样当成空页
    private suspend fun loadMvData(page: Int, size: Int): ApiResult<PageResult<VideoContentEntity>> =
        when (val r = SquareApi.getUserVideoList(userId = userId, pageNo = page, pageSize = size)) {
            is ApiResult.Success -> ApiResult.Success(PageResult(r.data.list, r.data.total))
            is ApiResult.Failure -> ApiResult.Success(PageResult(emptyList(), 0))
        }

    private suspend fun loadSongData(page: Int, size: Int): ApiResult<PageResult<SongEntity>> =
        when (val r = SquareApi.getUserMusicList(userId, page, size, nickname, avatar)) {
            is ApiResult.Success -> ApiResult.Success(PageResult(r.data.list, r.data.total))
            is ApiResult.Failure -> ApiResult.Success(PageResult(emptyList(), 0))
        }

    // —— Tab —— //

    internal fun selectTab(index: Int, animated: Boolean = true) {
        if (index != tabIndex) {
            syncOffsetTo(index)
            tabIndex = index
        }
        pageListRef?.view?.scrollToPageIndex(index, animated)
        onTabChanged()
    }

    /** 切换到空列表 Tab 时自动触发首次加载。 */
    private fun onTabChanged() {
        when (tabIndex) {
            0 -> if (videoMvPaging.list.isEmpty() && !videoMvPaging.loading) videoMvPaging.refresh()
            1 -> if (songPaging.list.isEmpty() && !songPaging.loading) songPaging.refresh()
        }
    }

    /** 头部是两个列表共用的：切换前把目标列表的头部摆到与当前一致的位置。 */
    private fun syncOffsetTo(target: Int) {
        val cur = headerOffset
        val targetOffset = tabOffsets[target]
        val y = when {
            cur < SquareProfileLayout.headerCollapseExtent -> cur
            targetOffset < SquareProfileLayout.headerCollapseExtent -> SquareProfileLayout.headerCollapseExtent
            else -> return
        }
        tabOffsets[target] = y
        listRefs[target]?.view?.listView?.setContentOffset(0f, y, false)
    }

    internal fun onListScroll(tab: Int, offsetY: Float) {
        tabOffsets[tab] = offsetY
        if (tab != tabIndex) return
        val clamped = offsetY.coerceIn(0f, SquareProfileLayout.headerCollapseExtent)
        if (clamped != headerOffset) headerOffset = clamped
    }

    internal fun onPagerScroll(offsetX: Float) {
        if (screenWidth <= 0f) return
        tabPage = (offsetX / screenWidth).coerceIn(0f, 1f)
    }

    internal fun onPagerIndexChanged(index: Int) {
        if (index == tabIndex) return
        syncOffsetTo(index)
        tabIndex = index
        onTabChanged()
    }

    internal fun bindPageList(ref: ViewRef<PageListView<*, *>>) {
        pageListRef = ref
    }

    // —— 交互 —— //

    /** 打开头像大图预览。 */
    internal fun openImagePreview() {
        showImagePreview(listOf(avatar))
    }

    internal fun onMvItemTap(type: MvItemType, item: VideoContentEntity) {
        val list = ArrayList(videoMvPaging.list)
        val index = list.indexOfFirst { it.id == item.id }
        if (type == MvItemType.detail) {
            SquareNav.openPlayDetail(
                this,
                SquarePlayArgs(videoType = 0, videoList = list, initialIndex = index.coerceAtLeast(0), pageNo = 1, isFindHot = 0, isLoadMore = false),
            )
            return
        }
        MvService.onTap(type, item.toMvModel(), list.map { it.toMvModel() })
    }

    internal fun onMusicItemTap(type: MusicItemType, item: SongEntity) {
        // 从歌曲详情进来时不再进详情（避免详情页套娃）
        if (type == MusicItemType.detail && this.type == "music") return
        MusicService.onTap(type, item.toModel(), songPaging.list.map { it.toModel() })
    }

    /** MvService 跨页同步。 */
    private fun updateMvList(type: String, item: MvModel) {
        val entity = item.toEntity()
        val index = videoMvPaging.list.indexOfFirst { it.id == entity.id }
        if (index == -1) return
        val current = videoMvPaging.list[index]
        val updated = when (type) {
            "like" -> current.copy(isCollect = entity.isCollect, collectNum = entity.collectNum)
            "share" -> current.copy(repostCount = entity.repostCount)
            "play" -> current.copy(mvHitNum = entity.mvHitNum)
            else -> null
        }
        if (updated != null) videoMvPaging.list[index] = updated
    }

    /** MusicService 跨页同步。 */
    private fun updateMusicList(type: String, item: SongModel) {
        val index = songPaging.list.indexOfFirst { it.id == item.id }
        if (index == -1) return
        val current = songPaging.list[index]
        val updated = when (type) {
            "like" -> current.copy(
                isCollect = item.isCollect,
                musicHitNum = item.musicHitNum,
                repostCount = item.repostCount,
                isCollectNum = item.isCollectNum,
            )
            "share" -> current.copy(repostCount = item.repostCount)
            "play" -> current.copy(musicHitNum = item.musicHitNum)
            else -> null
        }
        if (updated != null) songPaging.list[index] = updated
    }

    internal val progress: Float get() = SquareProfileLayout.collapseProgress(headerOffset)

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            profileBanner(ctx)
            profileBlurPanel(ctx)
            View {
                attr {
                    absolutePosition(top = ctx.statusBarHeight + SquareProfileLayout.topBarHeight, left = 0f, right = 0f, bottom = 0f)
                }
                PageList {
                    ref { ctx.bindPageList(it) }
                    attr {
                        flex(1f)
                        pageDirection(true)
                        pageItemWidth(ctx.screenWidth)
                        pageItemHeight(ctx.screenHeight - ctx.statusBarHeight - SquareProfileLayout.topBarHeight)
                        showScrollerIndicator(false)
                        keepItemAlive(true)
                        defaultPageIndex(if (ctx.type == "music") 1 else 0)
                    }
                    event {
                        scroll { ctx.onPagerScroll(it.offsetX) }
                        pageIndexDidChanged {
                            ctx.onPagerIndexChanged((it as? JSONObject)?.optInt("index", 0) ?: 0)
                        }
                    }
                    for (tab in 0 until 2) {
                        View {
                            attr { size(ctx.screenWidth, ctx.screenHeight - ctx.statusBarHeight - SquareProfileLayout.topBarHeight) }
                            profileTabList(ctx, tab)
                        }
                    }
                }
                // 吸顶 Tab 条：头部完全收起后出现
                vif({ ctx.headerOffset >= SquareProfileLayout.headerCollapseExtent }) {
                    View {
                        attr { absolutePosition(top = 0f, left = 0f, right = 0f) }
                        profileTabBar(ctx)
                    }
                }
            }
            profileTopBar(ctx)
        }
    }
}

private fun ViewContainer<*, *>.profileBanner(ctx: SquareProfilePage) {
    View {
        attr {
            absolutePosition(top = 0f, left = 0f, right = 0f)
            height(SquareProfileLayout.bannerHeight(ctx.screenWidth))
        }
        Image {
            attr {
                absolutePositionAllZero()
                src(AssetsImage.profileTopBg.asset())
                resizeCover()
            }
        }
        View {
            attr {
                absolutePositionAllZero()
                backgroundColor(Color(0, 0, 0, SquareProfileLayout.bannerOverlayAlpha * ctx.progress))
            }
        }
    }
}

private fun ViewContainer<*, *>.profileBlurPanel(ctx: SquareProfilePage) {
    View {
        attr {
            absolutePosition(
                top = SquareProfileLayout.panelTop(ctx.progress, ctx.screenWidth, ctx.statusBarHeight),
                left = 0f,
                right = 0f,
                bottom = 0f,
            )
            borderRadius(SquareProfileLayout.panelCornerRadius, SquareProfileLayout.panelCornerRadius, 0f, 0f)
        }
        Blur {
            attr {
                absolutePositionAllZero()
                blurRadius(SquareProfileLayout.panelBlurSigma)
            }
        }
        View {
            attr {
                absolutePositionAllZero()
                backgroundColor(SquareProfileLayout.panelBlurTintAt(ctx.tabPage))
            }
        }
    }
}

private fun ViewContainer<*, *>.profileTopBar(ctx: SquareProfilePage) {
    View {
        attr {
            absolutePosition(top = ctx.statusBarHeight, left = 0f, right = 0f)
            height(SquareProfileLayout.topBarHeight)
            padding(0f, 20f, 0f, 16f)
            flexDirectionRow()
            alignItemsCenter()
        }
        View {
            attr {
                flex(1f)
                opacity(ctx.progress)
                touchEnable(ctx.progress > 0f)
            }
            vif({ ctx.isProfileLoading }) {
                SDShimmer { attr { size(120f, 22f); radius = 4f; highlightColor = Color(255, 255, 255, 0.3f) } }
            }
            vif({ !ctx.isProfileLoading }) {
                Text {
                    attr {
                        text(ctx.nickname)
                        fontSize(18f)
                        lineHeight(21.6f)
                        fontFamily("Poppins-Bold")
                        color(Color.WHITE)
                        lines(1)
                        textOverFlowTail()
                    }
                }
            }
        }
        View {
            attr {
                marginLeft(16f)
                size(32f, 32f)
                borderRadius(16f)
                backgroundColor(Color(255, 255, 255, 0.1f))
                allCenter()
            }
            event { click { ctx.nav.pop() } }
            Image {
                attr {
                    size(20f, 20f)
                    src(AssetsImage.close.asset())
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.profileTabBar(ctx: SquareProfilePage) {
    View {
        attr {
            height(SquareProfileLayout.tabBarPinnedHeight)
            padding(SquareProfileLayout.tabBarVerticalPadding, SquareProfileLayout.horizontalPadding, SquareProfileLayout.tabBarVerticalPadding, SquareProfileLayout.horizontalPadding)
            flexDirectionRow()
        }
        SDTabBar {
            attr {
                // 父级是横排：可滚动 Tab 栏的根是 Scroller，不给宽度会被压成 0 宽而整条不可见
                flex(1f)
                tabs = listOf("tab_video".tr, "songs1".tr)
                currentIndex = ctx.tabIndex
                tabHeight = SquareProfileLayout.tabBarPillHeight
                tabMinWidth = 70f
                tabPaddingH = 16f
                gap = 0f
                fontFamily = "Poppins-SemiBold"
                labelWeight = null
                unselectedLabelWeight = null
            }
            event { onChanged { ctx.selectTab(it) } }
        }
    }
}

/** 头部：用户信息（随滚动淡出）+ Tab 条。 */
private fun ViewContainer<*, *>.profileHeader(ctx: SquareProfilePage) {
    View {
        attr {
            width(ctx.screenWidth)
            padding(20f, SquareProfileLayout.horizontalPadding, 10f, SquareProfileLayout.horizontalPadding)
            opacity(SquareProfileLayout.profileInfoOpacity(ctx.progress))
        }
        // 头像
        View {
            attr {
                size(SquareProfileLayout.avatarSize, SquareProfileLayout.avatarSize)
                borderRadius(SquareProfileLayout.avatarSize / 2f)
                border(Border(2f, BorderStyle.SOLID, Color.WHITE))
                allCenter()
            }
            // 2px 描边占掉内边距：内容 76（参考 Container 把 border 算进 padding）
            vif({ ctx.isProfileLoading }) {
                View {
                    attr {
                        size(SquareProfileLayout.avatarSize - 4f, SquareProfileLayout.avatarSize - 4f)
                        borderRadius((SquareProfileLayout.avatarSize - 4f) / 2f)
                        backgroundColor(Color.BLACK)
                    }
                    SDShimmer {
                        attr {
                            flex(1f)
                            radius = SquareProfileLayout.avatarSize
                            baseColor = Color(255, 255, 255, 0.4f)
                            highlightColor = Color(255, 255, 255, 0.3f)
                        }
                    }
                }
            }
            vif({ !ctx.isProfileLoading }) {
                SDImage {
                    attr {
                        size(SquareProfileLayout.avatarSize - 4f, SquareProfileLayout.avatarSize - 4f)
                        borderRadius((SquareProfileLayout.avatarSize - 4f) / 2f)
                        url = ctx.avatar
                    }
                    event { click { ctx.openImagePreview() } }
                }
            }
        }
        // 昵称
        View {
            attr {
                marginTop(20f)
                height(SquareProfileLayout.nicknameHeight)
                justifyContentCenter()
            }
            vif({ ctx.isProfileLoading }) {
                SDShimmer { attr { size(120f, SquareProfileLayout.nicknameHeight); radius = 4f; highlightColor = Color(255, 255, 255, 0.3f) } }
            }
            vif({ !ctx.isProfileLoading }) {
                Text {
                    attr {
                        text(ctx.nickname)
                        fontSize(18f)
                        lineHeight(21.6f)
                        fontFamily("Poppins-SemiBold")
                        color(Color.WHITE)
                        lines(1)
                        textOverFlowTail()
                    }
                }
            }
        }
        View {
            attr {
                marginTop(14f)
                flexDirectionRow()
            }
            profileStat(ctx, { ctx.totalViews }, "views".tr)
            View { attr { width(40f) } }
            profileStat(ctx, { ctx.totalLikes }, "likes".tr)
        }
        View { attr { height(10f) } }
    }
    profileTabBar(ctx)
}

private fun ViewContainer<*, *>.profileStat(ctx: SquareProfilePage, value: () -> Int, label: String) {
    View {
        attr {
            height(SquareProfileLayout.statRowHeight)
            flexDirectionRow()
            alignItemsCenter()
        }
        vif({ value() < 0 }) {
            SDShimmer { attr { size(72f, SquareProfileLayout.statRowHeight); radius = 4f; highlightColor = Color(255, 255, 255, 0.3f) } }
        }
        vif({ value() >= 0 }) {
            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                }
                Text {
                    attr {
                        text(value().formIntText())
                        fontSize(14f)
                        lineHeight(16.8f)
                        fontFamily("Poppins-Medium")
                        color(Color.WHITE)
                    }
                }
                Text {
                    attr {
                        marginLeft(6f)
                        text(label)
                        fontSize(12f)
                        lineHeight(14.4f)
                        fontFamily("Poppins-Medium")
                        color(Color(255, 255, 255, 0.5f))
                    }
                }
            }
        }
    }
}

/** 空列表 / 加载中占位（Dart `SquareProfileEmptyView`）。 */
private fun ViewContainer<*, *>.profileEmpty(loading: () -> Boolean, image: String) {
    View {
        attr {
            flex(1f)
            allCenter()
        }
        vif({ loading() }) {
            SDLoading { attr { size = 30f } }
        }
        vif({ !loading() }) {
            View {
                attr { alignItemsCenter() }
                Image {
                    attr {
                        size(60f, 60f)
                        src(image.asset())
                    }
                }
                Text {
                    attr {
                        marginTop(10f)
                        text("no_works_released_yet".tr)
                        fontSize(14f)
                        color(Color(0x8AFFFFFFL))
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.profileTabList(ctx: SquareProfilePage, tab: Int) {
    SDRefreshList {
        ref { ctx.listRefs[tab] = it }
        attr {
            flex(1f)
            state = if (tab == 0) ctx.videoMvPaging else ctx.songPaging
            showNoMoreText = false
            footerExtraPadding = ctx.safeBottom
            placeholderHeight = (ctx.screenHeight - ctx.statusBarHeight - SquareProfileLayout.topBarHeight - SquareProfileLayout.headerCollapseExtent - SquareProfileLayout.tabBarPinnedHeight).coerceAtLeast(240f)
            val st = if (tab == 0) ctx.videoMvPaging else ctx.songPaging
            val img = if (tab == 0) AssetsImage.mvEmpty else AssetsImage.libraryEmpty
            loadingBuilder = { profileEmpty({ true }, img) }
            emptyBuilder = { profileEmpty({ st.loading }, img) }
        }
        event { onScroll { ctx.onListScroll(tab, it.offsetY) } }
        header { profileHeader(ctx) }
        items {
            if (tab == 0) {
                vfor({ ctx.videoMvPaging.list }) { item ->
                    View {
                        attr {
                            // 间隔只加在两项之间（参考奇数下标是 SizedBox(30)）；用首项判断而非 vforIndex，删除后下标不刷新
                            padding(if (ctx.videoMvPaging.list.firstOrNull() !== item) SquareProfileLayout.listItemSpacing else 0f, SquareProfileLayout.horizontalPadding, 0f, SquareProfileLayout.horizontalPadding)
                        }
                        SDMvItem {
                            attr {
                                this.item = item.toMvModel()
                                isMy = ctx.isMy
                            }
                            event { onTap { type -> ctx.onMvItemTap(type, item) } }
                        }
                    }
                }
            } else {
                vfor({ ctx.songPaging.list }) { item ->
                    View {
                        attr {
                            padding(if (ctx.songPaging.list.firstOrNull() !== item) SquareProfileLayout.listItemSpacing else 0f, SquareProfileLayout.horizontalPadding, 0f, SquareProfileLayout.horizontalPadding)
                        }
                        SDMusicItem {
                            attr {
                                this.item = item.toModel()
                                isMy = ctx.isMy
                            }
                            event { onTap { type -> ctx.onMusicItemTap(type, item) } }
                        }
                    }
                }
            }
        }
    }
}
