package com.sondo.app.features.home

import com.sondo.app.base.AppEnv
import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.constants.asset
import com.sondo.app.core.http.ApiResult
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.storage.Sp
import com.sondo.app.core.utils.DevLog
import com.sondo.app.data.models.MvModel
import com.sondo.app.data.models.PageResult
import com.sondo.app.data.models.SongEntity
import com.sondo.app.data.models.SongModel
import com.sondo.app.data.models.VideoContentEntity
import com.sondo.app.data.models.toEntity
import com.sondo.app.data.models.toModel
import com.sondo.app.features.home.data.CategoryEntity
import com.sondo.app.features.home.data.HomeApi
import com.sondo.app.features.home.data.HomeBannerItem
import com.sondo.app.features.home.widgets.HomeBannerSwiper
import com.sondo.app.features.home.widgets.homeAdBanner
import com.sondo.app.features.home.widgets.homeCategorySection
import com.sondo.app.features.home.widgets.homeMvSection
import com.sondo.app.features.home.widgets.homeSectionTitle
import com.sondo.app.features.home.widgets.homeTrendingSection
import com.sondo.app.features.square.SquareNav
import com.sondo.app.features.square.data.SquarePlayArgs
import com.sondo.app.nativeapi.SDAppModule
import com.sondo.app.service.ConfigService
import com.sondo.app.service.MusicItemType
import com.sondo.app.service.MusicService
import com.sondo.app.service.PlayerService
import com.sondo.app.service.RecallFlow
import com.sondo.app.service.UserStore
import com.sondo.app.service.WorksUi
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.ui.atoms.SDSongSkeleton
import com.sondo.app.ui.atoms.SDSongSkeletonAttr
import com.sondo.app.ui.list.PagingState
import com.sondo.app.ui.list.SDRefreshList
import com.sondo.app.ui.list.SDRefreshListView
import com.sondo.app.ui.works.SDNavRootAppBar
import com.sondo.app.ui.works.SDSongItem
import com.sondo.app.ui.works.worksState
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.timer.clearTimeout
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.View

/** 首页 Tab 的属性（根页面在 `attr { }` 里响应式设置）。 */
class HomeTabAttr : ComposeAttr() {
    /** 本 Tab 被选中且根页面可见时为 true；false 时暂停一切视频、不触发首次加载。 */
    var active: Boolean by observable(false)

    /** 底部悬浮导航栏遮住的高度（列表底部留白）。 */
    var bottomInset: Float by observable(0f)
}

/**
 * 首页 Tab 主体（Dart `HomePage` + `HomeController`）。不是页面：由根页面（`Routes.root`）嵌入。
 *
 * - 第一次 [HomeTabAttr.active] 为 true 时才请求数据（之后每次切回首页补拉空区块，与 Dart barIndex worker 一致）；
 * - [HomeTabAttr.active] 为 false（切走 Tab / 根页面被盖住）时卸掉轮播解码器；
 * - 登录态变化（[Events.USER_CHANGED]）刷新新歌收藏态，登出清空收藏标记。
 */
class HomeTabView : ComposeView<HomeTabAttr, ComposeEvent>() {
    internal val page: BasePager get() = getPager() as BasePager

    // —— 顶栏显露 —— //
    /** 顶栏 logo 和背景色的显露。0 藏在轮播下，1 完全展开。 */
    internal var bannerHeaderReveal by observable(1f)
    internal var bannerHeaderImmersive by observable(false)

    // —— 热门与分类 —— //
    internal var isHotListLoading = false
    internal val hotList by observableList<SongEntity>()
    internal var isCategoryListLoading = false
    internal val categoryList by observableList<CategoryEntity>()

    // —— MV 区块 —— //
    internal var mvLoading by observable(false)
    internal var bannerMvLoading by observable(false)
    /** 轮播请求失败：骨架上显示重试。 */
    internal var bannerLoadFailed by observable(false)
    internal val mvList by observableList<VideoContentEntity>()
    internal var bannerList by observable<List<HomeBannerItem>>(emptyList())
    internal var currentBannerIndex = 0
    /** 轮播是否在视口内（可见超过一半）。只暂停，不拆解码器。 */
    internal var isBannerInViewport by observable(true)
    /** 进全屏播放前先拆掉横幅解码器。 */
    internal var navigatingToPlayer by observable(false)

    // —— 登录态 / 配置 —— //
    internal var isLogin by observable(UserStore.isLogin)
    internal var loginPoint by observable(ConfigService.loginPoint)

    /** 新歌分页（Dart RefreshMixin）。失败当空页处理（Dart loadData 失败返回 []）。 */
    internal val newSongs = PagingState<SongEntity>(this, pageSize = 10, itemKey = { it.id }) { pageNo, size ->
        when (val r = HomeApi.getNewSongs(pageNo, size)) {
            is ApiResult.Success -> ApiResult.Success(PageResult(r.data.list, r.data.total))
            is ApiResult.Failure -> ApiResult.Success(PageResult(emptyList(), 0))
        }
    }

    private var activatedOnce = false
    /** 由本组件发起、尚未被 refreshing 监听消化的刷新次数。 */
    private var selfRefreshes = 0
    private var listRef: ViewRef<SDRefreshListView>? = null
    private var visibilityDebounce: String? = null
    private var scrollOffset = 0f
    private var alive = true

    /** 轮播边长 = 屏宽（固定 1:1）。 */
    internal val bannerSide: Float get() = page.screenWidth

    /** 轮播允许解码播放：在首页 Tab 且根页面可见、且不在进全屏途中。 */
    internal val bannerPlaybackEnabled: Boolean get() = attr.active && !navigatingToPlayer

    override fun createAttr(): HomeTabAttr = HomeTabAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun created() {
        super.created()
        val p = page
        p.on(Events.USER_CHANGED) { onLoginChanged(UserStore.isLogin) }
        p.on(Events.CONFIG_CHANGED) { loginPoint = ConfigService.loginPoint }
        p.on(Events.MUSIC_LIST_CHANGED) { json ->
            val item = json.optJSONObject("item") ?: return@on
            updateMusicList(json.optString("type", ""), SongModel.fromJson(item))
        }
        bindValueChange({ attr.active }) { if (attr.active) onActivated() }
        // 下拉刷新：SDRefreshList 只刷新新歌，首页要刷新全部区块（自己发起的刷新不算）
        bindValueChange({ newSongs.refreshing }) {
            if (!newSongs.refreshing) return@bindValueChange
            if (selfRefreshes > 0) selfRefreshes-- else refreshOthers()
        }
        syncBannerHeaderReveal()
    }

    override fun viewDestroyed() {
        alive = false
        visibilityDebounce?.let { clearTimeout(it) }
        unbindAllValueChange()
        super.viewDestroyed()
    }

    /** 切到首页 Tab：按需懒加载各区块；第一次激活时延时 2 秒静默调系统评分。 */
    private fun onActivated() {
        if (hotList.isEmpty() && !isHotListLoading) getTrendingList()
        if (categoryList.isEmpty() && !isCategoryListLoading) getMusicTypeList()
        if (newSongs.list.isEmpty() && !newSongs.loading) refreshNewSongs()
        if (mvList.isEmpty() && !mvLoading) getMvList()
        if (bannerList.isEmpty() && !bannerMvLoading) getHomeBanners()
        if (!activatedOnce) {
            activatedOnce = true
            setTimeout(pagerId, 2000) { if (alive) showScoreSilently() }
        }
    }

    /** Dart `DialogScore.show(isDialog: false)`：首页静默调系统评分，内部有开关与已评过拦截。 */
    private fun showScoreSilently() {
        if (!AppEnv.isAndroid && !AppEnv.isOhos && AppEnv.isDebug) {
            DevLog.logger("DialogScore: show: GetPlatform.isIOS && kDebugMode")
            return
        }
        if (Sp.getBool(StorageKey.isShowRating, false)) return
        if (!ConfigService.isHomeCommentOpen) return
        // 先落标记，避免首页每次进页都触发系统评分
        Sp.setBool(StorageKey.isShowRating, true)
        UmengUtils.logEvent("rating_continue")
        page.launch {
            try {
                page.acquireModule<SDAppModule>(SDAppModule.MODULE_NAME).requestReview()
                Sp.setBool(StorageKey.isShowRating, true)
            } catch (e: Throwable) {
                UmengUtils.logEvent("rating_continue_error")
            }
        }
    }

    // —— 登录态 —— //

    /** 登录后刷新新歌收藏态；登出时清空所有收藏标记。 */
    private fun onLoginChanged(login: Boolean) {
        if (login == isLogin) return
        isLogin = login
        if (login) {
            if (newSongs.list.isNotEmpty()) {
                page.launch {
                    when (val r = HomeApi.getNewSongs(1)) {
                        is ApiResult.Success -> {
                            // Dart 先 pageNo = 1：页码 / noMore 一并复位，下次上拉从第 2 页开始
                            newSongs.clearData()
                            if (r.data.list.isEmpty()) refreshNewSongs() else newSongs.list.addAll(r.data.list)
                        }
                        is ApiResult.Failure -> DevLog.logger("musicListPageApi failed: ${r.error.message}")
                    }
                }
            }
        } else {
            for (i in 0 until newSongs.list.size) {
                val s = newSongs.list[i]
                if (s.isCollect) newSongs.list[i] = s.copy(isCollect = false)
            }
        }
    }

    // —— 数据 —— //

    /** 拉取热门歌曲并同步播放器热榜。 */
    private fun getTrendingList() {
        isHotListLoading = true
        page.launch {
            when (val r = HomeApi.getTrending()) {
                is ApiResult.Success -> {
                    hotList.clear()
                    hotList.addAll(r.data.hotList)
                    PlayerService.hotList.clear()
                    PlayerService.hotList.addAll(r.data.hotList.map { it.toModel() })
                }
                is ApiResult.Failure -> DevLog.printIn("========musicListApi failed: ${r.error.message}")
            }
            isHotListLoading = false
        }
    }

    /** 拉取音乐分类列表。 */
    private fun getMusicTypeList() {
        isCategoryListLoading = true
        page.launch {
            when (val r = HomeApi.getCategories()) {
                is ApiResult.Success -> {
                    categoryList.clear()
                    categoryList.addAll(r.data)
                }
                is ApiResult.Failure -> DevLog.printIn("========musicTypeApi failed: ${r.error.message}")
            }
            isCategoryListLoading = false
        }
    }

    /** 拉取热门 MV 列表。 */
    private fun getMvList() {
        if (mvLoading) return
        mvLoading = true
        page.launch {
            val r = HomeApi.getTrendingMvList()
            if (r is ApiResult.Success && r.data.isNotEmpty()) {
                mvList.clear()
                mvList.addAll(r.data)
            }
            mvLoading = false
        }
    }

    /** 拉取首页轮播。失败留骨架并标记重试；空列表也留骨架。 */
    internal fun getHomeBanners(force: Boolean = false) {
        if (bannerMvLoading) return
        if (!force && bannerList.isNotEmpty()) return
        bannerMvLoading = true
        bannerLoadFailed = false
        syncBannerHeaderReveal()
        page.launch {
            when (val r = HomeApi.getHomeBanners()) {
                is ApiResult.Success -> {
                    val data = r.data
                    bannerLoadFailed = false
                    // 下拉刷新后列表仍盖住当前页就留在原页，避免指示点跳回第一项
                    if (data.isEmpty() || currentBannerIndex < 0) {
                        currentBannerIndex = 0
                    } else if (currentBannerIndex >= data.size) {
                        currentBannerIndex = data.size - 1
                    }
                    bannerList = data
                }
                is ApiResult.Failure -> {
                    currentBannerIndex = 0
                    bannerList = emptyList()
                    bannerLoadFailed = true
                }
            }
            bannerMvLoading = false
            syncBannerHeaderReveal()
        }
    }

    private fun refreshNewSongs() {
        if (newSongs.loading) return
        selfRefreshes++
        newSongs.refresh()
    }

    /** 下拉刷新全部首页区块（新歌由列表自己刷新）。 */
    private fun refreshOthers() {
        getTrendingList()
        getMusicTypeList()
        if (mvList.isEmpty() && !mvLoading) getMvList()
        // 下拉刷新要重新拉轮播，运营改配置后能立刻看到
        getHomeBanners(force = true)
    }

    // —— 滚动 / 顶栏 —— //

    internal fun onScroll(offsetY: Float) {
        scrollOffset = offsetY
        syncBannerHeaderReveal()
        // 可见度防抖：轮播可见超过一半才算在视口内
        val inViewport = offsetY < bannerSide * 0.5f
        visibilityDebounce?.let { clearTimeout(it) }
        visibilityDebounce = setTimeout(pagerId, 300) {
            visibilityDebounce = null
            if (alive && isBannerInViewport != inViewport) isBannerInViewport = inViewport
        }
    }

    /** 显露按轮播高度的 80% 走完。 */
    private fun syncBannerHeaderReveal() {
        val next = headerRevealFor(scrollOffset, bannerSide)
        if (!bannerHeaderImmersive) bannerHeaderImmersive = true
        if (bannerHeaderReveal != next) bannerHeaderReveal = next
    }

    /** 滚动回顶部。 */
    internal fun toTop() {
        listRef?.view?.scrollToTop(true)
    }

    internal fun bindList(ref: ViewRef<SDRefreshListView>) {
        listRef = ref
    }


    /** 调试：点首页 logo 按深链打开回归落地页。 */
    internal fun openRecallLanding() {
        if (!AppEnv.isDebug) return
        ConfigService.countdown.prepareNewRecallCycle()
        page.launch { RecallFlow.openFromDeepLink() }
    }

    // —— 跳转 —— //

    /** 首页分类进入创作：1=MV，3=音乐。 */
    internal fun toNavBar(index: Int) {
        if (index == 1) {
            UmengUtils.logEventButtonClick("enter_mv", pageName = "/home")
            WorksUi.openCreatePage(Routes.musicVideoGen, null)
            return
        }
        if (index == 3) {
            UmengUtils.logEventButtonClick("enter_music", pageName = "/home")
            WorksUi.openCreatePage(Routes.musicCreate, null)
        }
    }

    /** 跳转邀请页。主入口关闭时不进推荐奖励。 */
    internal fun toReferral() {
        if (!AppConstants.enableMemberPageEntry) return
        page.nav.push(Routes.referral)
        UmengUtils.logEventButtonClick("to_referral", pageName = "/home")
    }

    internal fun showLoginDialog() {
        page.launch { UserStore.showLoginDialog(page) }
    }

    /** 新歌列表（合上收藏 / 分享覆盖值），作播放列表用。 */
    internal fun newSongModels(): List<SongModel> {
        val ws = worksState
        return newSongs.list.map { ws.latestSong(it.toModel()) }
    }

    /** 跳转歌曲详情并设置播放列表。 */
    internal fun toMusicDetail(song: SongModel, list: List<SongModel>) {
        PlayerService.launch { PlayerService.setPlaylist(list) }
        page.nav.push(Routes.musicDetail, JSONObject().put("id", song.id).put("original", Routes.root))
    }

    internal fun playSong(song: SongModel, list: List<SongModel>) {
        PlayerService.launch { PlayerService.playSong(song, songs = list) }
    }

    /** 点赞新歌；未登录弹登录框。 */
    internal fun updateLike(song: SongModel) {
        val token = Sp.getString(StorageKey.refreshToken)
        if (token.isNotEmpty()) {
            MusicService.onTap(MusicItemType.like, song, emptyList())
        } else {
            showLoginDialog()
        }
    }

    /** 按轮播的 router 跳转。空路由、参数解析失败或认不出的值都进 MV 创作。 */
    internal fun openBannerTarget(item: HomeBannerItem) {
        // 按钮或整张封面的跳转都记一次。点视频进播放页不走这里
        UmengUtils.logEventButtonClick("banner_button", pageName = "/home", args = item.router)
        val router = item.router.trim()
        if (item.paramsInvalid || router.isEmpty() || router == "create_mv") {
            openBannerMv(item)
            return
        }
        if (router == "create_music") {
            UmengUtils.logEventButtonClick("enter_music", pageName = "/home")
            WorksUi.openCreatePage(Routes.musicCreate, item.routeArguments())
            return
        }
        val name = if (router.startsWith("/")) router else "/$router"
        if (name == "/library") {
            // 参考工程打开独立作品库页；本工程作品库只在主框架里，切到作品库 Tab
            page.nav.popToRoot(4)
            return
        }
        val target = flutterRouteToPage(name)
        if (target == null) {
            openBannerMv(item)
            return
        }
        val args = if (item.params.length() == 0) null else JSONObject(item.params.toString())
        when (target) {
            Routes.root -> page.nav.popToRoot()
            Routes.musicVideoGen, Routes.musicCreate -> WorksUi.openCreatePage(target, args)
            else -> page.nav.push(target, args)
        }
    }

    /** 进 MV 创作。描述词只在这里带上；参数解析失败时 params 是空的。 */
    private fun openBannerMv(item: HomeBannerItem) {
        UmengUtils.logEventButtonClick("enter_mv", pageName = "/home")
        WorksUi.openCreatePage(Routes.musicVideoGen, item.routeArguments(includePlot = true))
    }

    /** 有视频的轮播进入广场播放页。只带有地址的条目，避免空视频进播放器。 */
    internal fun openBannerVideo(index: Int) {
        val banners = bannerList
        if (index < 0 || index >= banners.size) return
        val current = banners[index]
        if (!current.hasVideo) {
            openBannerTarget(current)
            return
        }
        val playable = ArrayList<VideoContentEntity>()
        var initial = 0
        for (i in banners.indices) {
            val item = banners[i]
            if (!item.hasVideo) continue
            if (i == index) initial = playable.size
            playable.add(item.toVideo(i))
        }
        navigateToSquarePlay(
            SquarePlayArgs(
                videoType = 0,
                videoList = playable,
                initialIndex = initial,
                pageNo = 1,
                isFindHot = 0,
                isIndex = 0,
                imageComp = current.imageComp,
                isLoadMore = false,
                showAuthorActions = false,
            ),
        )
    }

    /** 点击热门 MV 卡片跳转播放页。没有视频地址的不带进去；点到的那条没视频时不打开。 */
    internal fun onTapMv(index: Int, imageComp: String) {
        val source = mvList
        if (index < 0 || index >= source.size) return
        if (source[index].resultUrl.trim().isEmpty()) return
        val playable = ArrayList<VideoContentEntity>()
        var initial = 0
        for (i in 0 until source.size) {
            val item = source[i]
            if (item.resultUrl.trim().isEmpty()) continue
            if (i == index) initial = playable.size
            playable.add(worksState.latestVideo(item))
        }
        navigateToSquarePlay(
            SquarePlayArgs(
                videoType = 0,
                videoList = playable,
                initialIndex = initial,
                pageNo = 1,
                isFindHot = 1,
                isIndex = 1,
                imageComp = imageComp,
                // 首页热门视频入口也需显示喜欢按钮，与列表页行为保持一致
                showAuthorActions = true,
            ),
        )
    }

    /** 进入全屏播放前先拆掉横幅，返回后按 active 决定是否恢复。 */
    private fun navigateToSquarePlay(args: SquarePlayArgs) {
        navigatingToPlayer = true
        SquareNav.openPlay(page, args) {
            if (alive) navigatingToPlayer = false
        }
    }

    internal fun openSquare() {
        page.nav.push(Routes.square, JSONObject().put("type", "new").put("videoType", 0))
    }

    internal fun openGenre(genre: String) {
        page.nav.push(Routes.musicGenre, JSONObject().put("genre", genre))
    }

    // —— 跨页同步 —— //

    // 热门 MV 的收藏态由 WorksState 按 id 覆盖（卡片本身不展示收藏），进播放页时经 latestVideo 带过去，
    // 这里不再替换列表元素，否则整张卡片会重建、封面重新加载。

    /**
     * 跨页同步新歌列表：edit / delete 改列表；like / share 的计数由 WorksState 按 id 覆盖，
     * 行内只重跑图标和数字的 attr，不替换列表元素（替换会让整行重建）。
     */
    private fun updateMusicList(type: String, item: SongModel) {
        val entity = item.toEntity()
        val list = newSongs.list
        val index = list.indexOfFirst { it.id == entity.id }
        if (index == -1) return
        val cur = list[index]
        when (type) {
            "edit" -> list[index] = cur.copy(musicCoverUrl = entity.musicCoverUrl, musicTitle = entity.musicTitle)
            "delete" -> list.removeAt(index)
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                flex(1f)
                backgroundColor(Color.BLACK)
            }
            SDRefreshList {
                ref { ctx.bindList(it) }
                attr {
                    flex(1f)
                    state = ctx.newSongs
                    footerExtraPadding = ctx.attr.bottomInset
                    placeholderHeight = 5 * 76f
                    loadingBuilder = { homeSongSkeletons() }
                    emptyBuilder = { homeSongSkeletons() }
                    errorBuilder = { homeSongSkeletons() }
                }
                event {
                    onScroll { ctx.onScroll(it.offsetY) }
                }
                header { homeHeader(ctx) }
                items {
                    vfor({ ctx.newSongs.list }) { song ->
                        View {
                            attr { padding(0f, 16f, 16f, 16f) }
                            SDSongItem {
                                attr {
                                    this.song = song.toModel()
                                    type = "other"
                                }
                                event {
                                    onDetail { s -> ctx.toMusicDetail(s, ctx.newSongModels()) }
                                    addPlayList { s -> ctx.playSong(s, ctx.newSongModels()) }
                                    onLike { s -> ctx.updateLike(s) }
                                }
                            }
                        }
                    }
                }
            }
            // 顶栏浮在轮播之上，按滚动显露
            View {
                attr { absolutePosition(top = 0f, left = 0f, right = 0f) }
                SDNavRootAppBar {
                    attr {
                        title = "explore".tr
                        titleWidget = {
                            Image {
                                attr {
                                    size(90f, 24f)
                                    src(AssetsImage.homeLogo.asset())
                                }
                            }
                        }
                        headerReveal = ctx.bannerHeaderReveal
                        headerImmersive = ctx.bannerHeaderImmersive
                    }
                    event {
                        onTapTitle { ctx.openRecallLanding() }
                        onDoubleTapTitle { ctx.toTop() }
                    }
                }
            }
        }
    }


    companion object {
        /** 显露按轮播高度的 80% 走完。滚过这段后 logo 和背景色才完全出现。 */
        fun headerRevealFor(offset: Float, bannerHeight: Float): Float {
            if (offset <= 0f || bannerHeight <= 0f) return 0f
            val distance = bannerHeight * 0.8f
            return (offset / distance).coerceIn(0f, 1f)
        }
    }
}

/** 列表头：轮播、分类、热门 MV、广告、趋势、新歌标题（舞蹈区块已移除）。 */
private fun ViewContainer<*, *>.homeHeader(ctx: HomeTabView) {
    View {
        attr { width(ctx.bannerSide) }
        HomeBannerSwiper {
            attr {
                side = ctx.bannerSide
                currentIndex = ctx.currentBannerIndex
                banners = ctx.bannerList
                loading = ctx.bannerMvLoading
                failed = ctx.bannerLoadFailed
                playbackEnabled = ctx.bannerPlaybackEnabled
                inViewport = ctx.isBannerInViewport
            }
            event {
                onRetry { ctx.getHomeBanners(force = true) }
                onIndexChanged { ctx.currentBannerIndex = it }
                onTapCard { index ->
                    val item = ctx.bannerList.getOrNull(index)
                    if (item != null) {
                        if (item.hasVideo) ctx.openBannerVideo(index) else ctx.openBannerTarget(item)
                    }
                }
                onTapButton { ctx.openBannerTarget(it) }
            }
        }
        homeCategorySection(ctx)
        homeMvSection(ctx)
        homeAdBanner(ctx)
        homeTrendingSection(ctx)
        homeSectionTitle("new_songs".tr)
    }
}

private fun ViewContainer<*, *>.homeSongSkeletons() {
    View {
        attr { flex(1f) }
        for (i in 0 until 5) {
            View {
                attr { padding(0f, 16f, 16f, 16f) }
                SDSongSkeleton { attr { type = SDSongSkeletonAttr.TYPE_ITEM } }
            }
        }
    }
}

/**
 * Flutter 路由 → Kuikly 页面名（只收参考工程 `Routes.allRoutes` 里、本工程也有的页面）。
 * 本工程没有的旧页面（/create_v2、/edit_mv、/storyboard…）返回 null，由调用方落到 MV 创作。
 */
internal fun flutterRouteToPage(route: String): String? = when (route) {
    "/" -> Routes.root
    "/music/detail" -> Routes.musicDetail
    "/edit_video" -> Routes.editVideo
    "/profile" -> Routes.profile
    "/square" -> Routes.square
    "/square/play" -> Routes.squarePlay
    "/square/play-detail" -> Routes.squarePlayDetail
    // 产品要求：会员入口关闭时轮播也不进邀请页（返回 null，由调用方落到 MV 创作）
    "/referral" -> if (AppConstants.enableMemberPageEntry) Routes.referral else null
    "/setting" -> Routes.setting
    "/trash" -> Routes.trash
    "/discount" -> Routes.discount
    "/recall" -> Routes.recall
    "/recall_product" -> Routes.recallProduct
    "/my_audio" -> Routes.myAudio
    "/shot_history" -> Routes.shotHistory
    "/referral_record" -> Routes.referralRecord
    "/four-view" -> Routes.fourView
    "/four-view/form" -> Routes.fourViewForm
    "/music_create" -> Routes.musicCreate
    "/music_video_gen" -> Routes.musicVideoGen
    "/mv_activity" -> Routes.mvActivity
    "/mv_style_template_select" -> Routes.mvStyleTemplateSelect
    "/mine" -> Routes.mine
    "/subscribe" -> Routes.subscribe
    "/credits_record" -> Routes.creditsRecord
    "/credits_purchase" -> Routes.creditsPurchase
    "/story_line" -> Routes.storyLine
    "/mv_list_detail" -> Routes.mvListDetail
    "/mv_preview_play" -> Routes.mvPreviewPlay
    "/retry_shots" -> Routes.retryShots
    else -> null
}

fun ViewContainer<*, *>.HomeTab(init: HomeTabView.() -> Unit) {
    addChild(HomeTabView(), init)
}
