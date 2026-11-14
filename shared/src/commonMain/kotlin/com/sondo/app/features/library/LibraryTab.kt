package com.sondo.app.features.library

import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.http.ApiResult
import com.sondo.app.core.http.AuthSession
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.storage.Sp
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.delayMs
import com.sondo.app.data.api.MusicActionApi
import com.sondo.app.data.models.MvModel
import com.sondo.app.data.models.PageResult
import com.sondo.app.data.models.SongModel
import com.sondo.app.data.models.UserActiveModel
import com.sondo.app.data.models.toEntity
import com.sondo.app.features.library.data.LibraryListApi
import com.sondo.app.features.main.MainController
import com.sondo.app.features.main.MainNavTab
import com.sondo.app.service.LibraryTabRefreshService
import com.sondo.app.service.MusicService
import com.sondo.app.service.MvItemType
import com.sondo.app.service.MvService
import com.sondo.app.service.PushService
import com.sondo.app.service.UserStore
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.SDWeight
import com.sondo.app.ui.atoms.VGap
import com.sondo.app.ui.input.hideKeyboard
import com.sondo.app.ui.list.PagingState
import com.sondo.app.ui.list.SDRefreshList
import com.sondo.app.ui.nav.SDTabBar
import com.sondo.app.ui.nav.SDTabIndicator
import com.sondo.app.ui.works.SDMusicItem
import com.sondo.app.ui.works.SDMvItem
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.base.event.layoutFrameDidChange
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.PageList
import com.tencent.kuikly.core.views.PageListView
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/** 资料库 Tab 的属性（跨切片契约，与 HomeTabAttr 同形）。 */
class LibraryTabAttr : ComposeAttr() {
    /** 当前 Tab 被选中且根页面可见。父级在 `attr {}` 里响应式赋值。 */
    var active: Boolean by observable(false)

    /** 悬浮导航栏遮住的底部高度；<= 0 时按参考工程留 64。 */
    var bottomInset: Float by observable(0f)
}

/**
 * 作品库 Tab 主体（Dart `LibraryPage` + `LibraryController` + `LibraryMvController` / `LibraryMusicController`）。
 * 根页面内嵌的 ComposeView，不是独立页面；生成进度轮询在 MvService / MusicService 里，这里只回写补丁。
 */
class LibraryTabView : ComposeView<LibraryTabAttr, ComposeEvent>() {
    internal val page: BasePager get() = getPager() as BasePager

    /** 顶部 Tab：0 视频、1 音乐。 */
    internal var tabIndex by observable(initialTabIndex())
    internal var mvTabIndex by observable(0)
    internal var musicTabIndex by observable(0)
    internal var isLogin by observable(UserStore.isLogin)

    /** 内容区尺寸（PageList 需要确定的宽高）。 */
    internal var boxW by observable(0f)
    internal var boxH by observable(0f)
    internal var innerH by observable(0f)

    internal var outerRef: ViewRef<PageListView<*, *>>? = null
    internal var mvInnerRef: ViewRef<PageListView<*, *>>? = null
    internal var musicInnerRef: ViewRef<PageListView<*, *>>? = null

    internal val bottomInset: Float get() = attr.bottomInset

    private var alive = true
    private var wasActive = false

    internal val myMvPaging: PagingState<MvModel> = PagingState(
        this,
        pageSize = 20,
        itemKey = { it.id },
        after = { list -> MvService.afterMv(list.toList(), myMvPaging.pageNo) },
    ) { pageNo, pageSize -> loadMvData(pageNo, pageSize) }

    internal val mvLikePaging: PagingState<MvModel> = PagingState(this, pageSize = 20, itemKey = { it.id }) { pageNo, pageSize ->
        LibraryListApi.fetchMvLikeList(pageNo, pageSize).orEmptyPage()
    }

    internal val myMusicPaging: PagingState<SongModel> = PagingState(
        this,
        pageSize = 20,
        itemKey = { it.id },
        after = { list -> MusicService.afterMusic(list.toList()) },
    ) { pageNo, pageSize ->
        // 失败时 Dart 抛出由 PagingController 处理（不改列表），这里保持 Failure
        MusicActionApi.mySongList(pageNo, pageSize)
    }

    internal val likedMusicPaging: PagingState<SongModel> = PagingState(this, pageSize = 20, itemKey = { it.id }) { pageNo, pageSize ->
        LibraryListApi.fetchCollectedSongList(pageNo, pageSize).orEmptyPage()
    }

    override fun createAttr(): LibraryTabAttr = LibraryTabAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun created() {
        super.created()
        MusicService.libraryMusicListProvider = {
            if (musicTabIndex == 0) myMusicPaging.list.toList() else likedMusicPaging.list.toList()
        }
        LibraryTabRefreshService.bind(
            refreshMv = { isCreate -> onRefreshMv(isCreate) },
            refreshSong = { isCreate -> onRefreshSong(isCreate) },
        )
        val p = page
        p.on(Events.USER_CHANGED) { onUserChanged() }
        p.on(Events.MV_TASK_UPDATED) { json -> if (alive) applyMvPatches(json.optJSONArray("items")) }
        p.on(Events.MV_LIST_CHANGED) { json ->
            if (!alive) return@on
            val item = json.optJSONObject("item") ?: return@on
            updateMvList(json.optString("type", ""), MvModel.fromJson(item))
        }
        p.on(Events.MUSIC_TASK_UPDATED) { json -> if (alive) applyMusicPatches(json.optJSONArray("items")) }
        p.on(Events.MUSIC_LIST_CHANGED) { json ->
            if (!alive) return@on
            val item = json.optJSONObject("item") ?: return@on
            updateMusicList(json.optString("type", ""), SongModel.fromJson(item))
        }
        initData()
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        bindValueChange({ attr.active }) {
            val now = attr.active
            if (now && !wasActive) loadTabData()
            wasActive = now
        }
    }

    override fun viewDestroyed() {
        alive = false
        unbindAllValueChange()
        LibraryTabRefreshService.unbind()
        MusicService.libraryMusicListProvider = null
        super.viewDestroyed()
    }

    // ------------------------------------------------------------------ 初始化 / Tab

    private fun initialTabIndex(): Int {
        val json = Sp.getJson(StorageKey.userActive)
        val saved = if (json == null) 0 else UserActiveModel.fromJson(json).libraryTabIndex
        // 旧三 Tab：0 视频、1 舞蹈、2 音乐。舞蹈下线后，记住的音乐下标落到 1，舞蹈落到视频。
        return if (saved >= 2) 1 else 0
    }

    /** 两个「我的」列表首次进入即加载（Dart 两个 Tab 控制器各自 onReady → _initData）。 */
    private fun initData() {
        if (AuthSession.accessToken.isEmpty()) return
        if (myMvPaging.list.isEmpty() && !myMvPaging.loading && mvTabIndex == 0) myMvPaging.refresh()
        if (myMusicPaging.list.isEmpty() && !myMusicPaging.loading && musicTabIndex == 0) myMusicPaging.refresh()
    }

    internal fun onLibraryTabChanged(index: Int) {
        if (index == tabIndex) return
        tabIndex = index
        hideKeyboard()
        val json = Sp.getJson(StorageKey.userActive)
        val model = if (json == null) UserActiveModel() else UserActiveModel.fromJson(json)
        model.libraryTabIndex = index
        Sp.setJson(StorageKey.userActive, model.toJson())
        loadTabData()
    }

    /** 点击顶部 Tab：翻页（翻页完成回调里会走 [onLibraryTabChanged]）。 */
    internal fun moveToTab(index: Int) {
        if (tabIndex == index) return
        outerRef?.view?.scrollToPageIndex(index, true)
        onLibraryTabChanged(index)
    }

    internal fun onMvTabChanged(index: Int) {
        if (index == mvTabIndex) return
        mvTabIndex = index
        if (!isLogin) return
        if (index == 0) {
            if (myMvPaging.list.isEmpty() && !myMvPaging.loading) myMvPaging.refresh()
        } else if (mvLikePaging.list.isEmpty() && !mvLikePaging.loading) {
            mvLikePaging.refresh()
        }
    }

    internal fun onMusicTabChanged(index: Int) {
        if (index == musicTabIndex) return
        musicTabIndex = index
        if (!isLogin) return
        if (index == 0) {
            if (myMusicPaging.list.isEmpty() && !myMusicPaging.loading) myMusicPaging.refresh()
        } else if (likedMusicPaging.list.isEmpty() && !likedMusicPaging.loading) {
            likedMusicPaging.refresh()
        }
    }

    internal fun selectMvTab(index: Int) {
        if (index == mvTabIndex) return
        mvInnerRef?.view?.scrollToPageIndex(index, true)
        onMvTabChanged(index)
    }

    internal fun selectMusicTab(index: Int) {
        if (index == musicTabIndex) return
        musicInnerRef?.view?.scrollToPageIndex(index, true)
        onMusicTabChanged(index)
    }

    /** 未登录不拉列表：空态直接给登录引导，避免请求失败后界面停在转圈。 */
    private fun loadTabData() {
        if (!UserStore.isLogin) return
        if (tabIndex == 0) {
            if (mvTabIndex == 0 && myMvPaging.list.isEmpty()) {
                myMvPaging.refresh()
            } else if (mvTabIndex == 1 && mvLikePaging.list.isEmpty()) {
                mvLikePaging.refresh()
            }
        } else {
            if (musicTabIndex == 0 && myMusicPaging.list.isEmpty()) {
                myMusicPaging.refresh()
            } else if (musicTabIndex == 1 && likedMusicPaging.list.isEmpty()) {
                likedMusicPaging.refresh()
            }
        }
    }

    private fun onUserChanged() {
        if (!alive) return
        val now = UserStore.isLogin
        if (now == isLogin) return
        isLogin = now
        if (now) {
            loadTabData()
        } else {
            // 清除内部 tab 数据以及轮询（角色列表由 MvService 自己在登出时清空）
            MvService.cancelMvData()
            myMvPaging.clearData()
            mvLikePaging.clearData()
            MusicService.cancelMusicData()
            myMusicPaging.clearData()
            likedMusicPaging.clearData()
        }
    }

    // ------------------------------------------------------------------ 刷新入口（LibraryTabRefreshService）

    private suspend fun onRefreshMv(isCreate: Boolean) {
        if (!alive) return
        if (isCreate) {
            moveToTab(0)
            selectMvTab(0)
        }
        myMvPaging.refresh()
    }

    private suspend fun onRefreshSong(isCreate: Boolean) {
        if (!alive) return
        if (isCreate && tabIndex != 1) {
            moveToTab(1)
            getNotified()
        }
        if (isCreate) selectMusicTab(0)
        myMusicPaging.refresh()
    }

    /** 新歌生成后 2 秒检查通知权限引导。 */
    private fun getNotified() {
        val p = page
        setTimeout(pagerId, 2000) {
            DevLog.logger("push fcm initData 2")
            p.launch {
                try {
                    PushService.showNotifiedDialog("music", pager = p)
                } catch (e: Throwable) {
                    DevLog.logger("push fcm error")
                }
            }
        }
    }

    // ------------------------------------------------------------------ 数据加载

    /** 我的 MV：第一页为空时每秒重试，最多 5 次（新建任务可能尚未落库）。 */
    private suspend fun loadMvData(pageNo: Int, pageSize: Int): ApiResult<PageResult<MvModel>> {
        var result = LibraryListApi.fetchMyMvList(pageNo, pageSize).orEmptyPage()
        var retryCount = 0
        while ((result.getOrNull()?.list?.isEmpty() ?: true) && pageNo == 1 && retryCount < 5 && alive) {
            delayMs(1000)
            retryCount++
            result = LibraryListApi.fetchMyMvList(pageNo, pageSize).orEmptyPage()
        }
        if (!alive) return ApiResult.Success(PageResult())
        return result
    }

    // ------------------------------------------------------------------ 事件回写

    private fun applyMvPatches(items: JSONArray?) {
        if (items == null) return
        for (i in 0 until items.length()) {
            val patch = items.optJSONObject(i) ?: continue
            val id = patch.optLong("id", 0L)
            val index = myMvPaging.list.indexOfFirst { it.id == id }
            if (index == -1) continue
            myMvPaging.list[index] = MvService.applyTaskPatch(myMvPaging.list[index], patch)
        }
    }

    private fun applyMusicPatches(items: JSONArray?) {
        if (items == null) return
        for (i in 0 until items.length()) {
            val patch = items.optJSONObject(i) ?: continue
            val id = patch.optLong("id", 0L)
            val index = myMusicPaging.list.indexOfFirst { it.id == id }
            if (index == -1) continue
            myMusicPaging.list[index] = MusicService.applyProgress(myMusicPaging.list[index], patch)
        }
    }

    /** Dart `LibraryMvController.updateMvList`。 */
    private fun updateMvList(type: String, item: MvModel) {
        val myList = myMvPaging.list
        val index = myList.indexOfFirst { it.id == item.id }
        if (index != -1) {
            val data = myList[index]
            when (type) {
                "like" -> myList[index] = data.copy(isCollect = item.isCollect, collectNum = item.collectNum)
                "share" -> myList[index] = data.copy(repostCount = item.repostCount)
                "play" -> myList[index] = data.copy(mvHitNum = item.mvHitNum)
                "delete" -> myList.removeAt(index)
                "publish" -> myList[index] = data.copy(mvPublish = item.mvPublish)
            }
        }
        val likeList = mvLikePaging.list
        val likeIndex = likeList.indexOfFirst { it.id == item.id }
        if (likeIndex != -1) {
            when (type) {
                "like" -> {
                    if (mvTabIndex == 1) {
                        // 当前在收藏 Tab：取消收藏直接移除
                        if (!item.isCollect) likeList.removeAt(likeIndex)
                        return
                    }
                    if (likeList.isEmpty()) return
                    mvLikePaging.refresh()
                }
                "share" -> likeList[likeIndex] = likeList[likeIndex].copy(repostCount = item.repostCount)
                "play" -> likeList[likeIndex] = likeList[likeIndex].copy(mvHitNum = item.mvHitNum)
            }
        }
    }

    /** Dart `LibraryMusicController.updateMusicList`。 */
    private fun updateMusicList(type: String, item: SongModel) {
        val myList = myMusicPaging.list
        val myIndex = myList.indexOfFirst { it.id == item.id }
        if (myIndex != -1) {
            val currentSong = myList[myIndex]
            when (type) {
                "like" -> myList[myIndex] = currentSong.copy(isCollect = item.isCollect, isCollectNum = item.isCollectNum)
                "edit" -> myList[myIndex] = currentSong.copy(
                    musicCoverUrl = item.musicCoverUrl,
                    musicTitle = item.musicTitle,
                    musicCategoryId = item.musicCategoryId,
                    musicCategory = item.musicCategory,
                )
                "delete" -> myList.removeAt(myIndex)
                "publish" -> myList[myIndex] = currentSong.copy(musicPublish = item.musicPublish)
            }
            return
        }
        val likeList = likedMusicPaging.list
        if (type == "like") {
            if (musicTabIndex == 1) {
                likedMusicPaging.refresh()
                return
            }
            if (likeList.isEmpty()) return
            val index = likeList.indexOfFirst { it.id == item.id }
            if (index != -1) {
                if (!item.isCollect) likeList.removeAt(index)
            } else {
                likedMusicPaging.refresh()
            }
        } else if (type == "share") {
            // 参考工程这里按 id 比较写成了自比较（总命中第一条），按 id 修正
            val index = likeList.indexOfFirst { it.id == item.id }
            if (index != -1) likeList[index] = likeList[index].copy(repostCount = item.repostCount)
        }
    }

    // ------------------------------------------------------------------ 交互

    internal fun onMvLikeTap(type: MvItemType, item: MvModel) {
        val list = mvLikePaging.list.toList()
        if (type == MvItemType.detail) {
            val arr = JSONArray()
            list.forEach { arr.put(it.toEntity().toJson()) }
            val index = list.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
            page.nav.push(
                Routes.squarePlay,
                JSONObject()
                    .put("videoType", 0)
                    .put("videoList", arr)
                    .put("initialIndex", index)
                    .put("pageNo", 1)
                    .put("isFindHot", 0)
                    .put("isLoadMore", false),
            )
        } else {
            MvService.onTap(type, item, list)
        }
    }

    /** 空态按钮：已登录 → 去创作 / 回首页；未登录 → 登录弹窗。 */
    internal fun onEmptyTap(type: Int, isMv: Boolean) {
        if (isLogin) {
            if (type == 1) {
                MainController.openCreate(if (isMv) MainNavTab.mv else MainNavTab.music)
            } else {
                MainController.changeBarIndex(0)
            }
        } else {
            val p = page
            p.launch { UserStore.showLoginDialog(p) }
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                flex(1f)
                // Dart colorScheme.primary = 纯黑
                backgroundColor(Color.BLACK)
                paddingTop(ctx.page.statusBarHeight)
            }
            SDTabBar {
                attr {
                    tabs = listOf("tab_video".tr, "music".tr)
                    currentIndex = ctx.tabIndex
                    tabHeight = 46f
                    tabPaddingH = 0f
                    gap = 24f
                    paddingH = 16f
                    labelColor = Color.WHITE
                    unselectedLabelColor = Color(0x8AFFFFFFL)
                    labelSize = 20f
                    labelWeight = SDWeight.W700
                    unselectedLabelWeight = SDWeight.W600
                    fontFamily = "Poppins"
                    // 用真字重字族，避免 Android 对 Poppins 做假粗体
                    selectedFontFamily = "Poppins-Bold"
                    unselectedFontFamily = "Poppins-SemiBold"
                    indicator = SDTabIndicator.NONE
                }
                event { onChanged { ctx.moveToTab(it) } }
            }
            VGap(10f)
            View {
                attr { flex(1f) }
                event {
                    layoutFrameDidChange {
                        ctx.boxW = it.width
                        ctx.boxH = it.height
                    }
                }
                vif({ ctx.boxW > 0f && ctx.boxH > 0f }) {
                    val startTab = ctx.tabIndex
                    PageList {
                        ref { ctx.outerRef = it }
                        attr {
                            size(ctx.boxW, ctx.boxH)
                            pageItemWidth(ctx.boxW)
                            pageItemHeight(ctx.boxH)
                            pageDirection(true)
                            defaultPageIndex(startTab)
                            keepItemAlive(true)
                            showScrollerIndicator(false)
                        }
                        event {
                            pageIndexDidChanged {
                                val index = (it as? JSONObject)?.optInt("index", 0) ?: 0
                                ctx.onLibraryTabChanged(index)
                            }
                        }
                        View {
                            attr { size(ctx.boxW, ctx.boxH) }
                            libraryMvSection(ctx)
                        }
                        View {
                            attr { size(ctx.boxW, ctx.boxH) }
                            libraryMusicSection(ctx)
                        }
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.LibraryTab(init: LibraryTabView.() -> Unit) {
    addChild(LibraryTabView(), init)
}

/** Dart 数据源出错返回空页：页面按空列表处理。 */
private fun <T> ApiResult<PageResult<T>>.orEmptyPage(): ApiResult<PageResult<T>> = when (this) {
    is ApiResult.Success -> this
    is ApiResult.Failure -> ApiResult.Success(PageResult())
}

/** 「我的 / 收藏」小胶囊 Tab（MV、音乐共用样式）。 */
private fun ViewContainer<*, *>.librarySubTabs(current: () -> Int, onTap: (Int) -> Unit) {
    View {
        attr { paddingBottom(20f) }
        SDTabBar {
            attr {
                tabs = listOf("my".tr, "like".tr)
                currentIndex = current()
                tabHeight = 32f
                tabMinWidth = 70f
                tabPaddingH = 16f
                gap = 8f
                paddingH = 16f
                labelColor = Color.WHITE
                unselectedLabelColor = SDColors.white40
                labelSize = 12f
                labelWeight = SDWeight.W600
                unselectedLabelWeight = SDWeight.W600
                indicator = SDTabIndicator.PILL
                indicatorColor = Color(0x26FFFFFFL)
                indicatorRadius = 29f
                tabBgColor = SDColors.white5
            }
            event { onChanged { onTap(it) } }
        }
    }
}

/** 底部留白：参考工程固定 64（悬浮导航盖在上面，「我的 MV」的安全区由列表自己处理）。 */
private fun ViewContainer<*, *>.libraryBottomGap(ctx: LibraryTabView) {
    View {
        attr { height(64f) }
    }
}

private fun ViewContainer<*, *>.libraryMvSection(ctx: LibraryTabView) {
    librarySubTabs({ ctx.mvTabIndex }) { ctx.selectMvTab(it) }
    View {
        attr { flex(1f) }
        event { layoutFrameDidChange { if (it.height > 0f) ctx.innerH = it.height } }
        vif({ ctx.innerH > 0f }) {
            PageList {
                ref { ctx.mvInnerRef = it }
                attr {
                    size(ctx.boxW, ctx.innerH)
                    pageItemWidth(ctx.boxW)
                    pageItemHeight(ctx.innerH)
                    pageDirection(true)
                    keepItemAlive(true)
                    bouncesEnable(false)
                    showScrollerIndicator(false)
                }
                event {
                    pageIndexDidChanged {
                        ctx.onMvTabChanged((it as? JSONObject)?.optInt("index", 0) ?: 0)
                    }
                }
                View {
                    attr { size(ctx.boxW, ctx.innerH) }
                    libraryMvList(ctx, mine = true)
                }
                View {
                    attr { size(ctx.boxW, ctx.innerH) }
                    libraryMvList(ctx, mine = false)
                }
            }
        }
    }
    libraryBottomGap(ctx)
}

private fun ViewContainer<*, *>.libraryMusicSection(ctx: LibraryTabView) {
    librarySubTabs({ ctx.musicTabIndex }) { ctx.selectMusicTab(it) }
    View {
        attr { flex(1f) }
        vif({ ctx.innerH > 0f }) {
            PageList {
                ref { ctx.musicInnerRef = it }
                attr {
                    size(ctx.boxW, ctx.innerH)
                    pageItemWidth(ctx.boxW)
                    pageItemHeight(ctx.innerH)
                    pageDirection(true)
                    keepItemAlive(true)
                    bouncesEnable(false)
                    showScrollerIndicator(false)
                }
                event {
                    pageIndexDidChanged {
                        ctx.onMusicTabChanged((it as? JSONObject)?.optInt("index", 0) ?: 0)
                    }
                }
                View {
                    attr { size(ctx.boxW, ctx.innerH) }
                    libraryMusicList(ctx, mine = true)
                }
                View {
                    attr { size(ctx.boxW, ctx.innerH) }
                    libraryMusicList(ctx, mine = false)
                }
            }
        }
    }
    libraryBottomGap(ctx)
}

private fun ViewContainer<*, *>.libraryMvList(ctx: LibraryTabView, mine: Boolean) {
    val paging = if (mine) ctx.myMvPaging else ctx.mvLikePaging
    val type = if (mine) 1 else 2
    vif({ !ctx.isLogin }) {
        libraryEmpty(ctx, type, isMv = true)
    }
    velse {
        SDRefreshList {
            attr {
                flex(1f)
                state = paging
                // 我的 MV 列表在参考工程里包了 SafeArea(bottom)
                if (mine) footerExtraPadding = ctx.page.safeBottom
                // Dart 空 / 首屏加载直接替换整个列表，在整页内居中
                placeholderFill = true
                emptyBuilder = { libraryEmpty(ctx, type, isMv = true) }
            }
            items {
                vfor({ paging.list }) { item ->
                    View {
                        attr {
                            // Dart separatorBuilder 30：间隔只加在两项之间
                            padding(if (paging.list.firstOrNull() === item) 0f else 30f, 16f, 0f, 16f)
                        }
                        SDMvItem {
                            attr {
                                this.item = item
                                isMy = mine
                                list = paging.list
                            }
                            if (!mine) {
                                event { onTap { t -> ctx.onMvLikeTap(t, item) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.libraryMusicList(ctx: LibraryTabView, mine: Boolean) {
    val paging = if (mine) ctx.myMusicPaging else ctx.likedMusicPaging
    val type = if (mine) 1 else 2
    vif({ !ctx.isLogin }) {
        libraryEmpty(ctx, type, isMv = false)
    }
    velse {
        SDRefreshList {
            attr {
                flex(1f)
                state = paging
                placeholderFill = true
                emptyBuilder = { libraryEmpty(ctx, type, isMv = false) }
                // 「我的音乐」首屏失败：Dart 只结束刷新、列表为空 → 仍显示作品库空态
                errorBuilder = { libraryEmpty(ctx, type, isMv = false) }
            }
            items {
                vfor({ paging.list }) { item ->
                    View {
                        attr {
                            // Dart separatorBuilder 30：间隔只加在两项之间
                            padding(if (paging.list.firstOrNull() === item) 0f else 30f, 16f, 0f, 16f)
                        }
                        SDMusicItem {
                            attr {
                                this.item = item
                                isMy = mine
                                list = paging.list
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 空态 / 未登录引导（Dart `_buildEmpty(type)`；type 1 我的、2 收藏）。 */
private fun ViewContainer<*, *>.libraryEmpty(ctx: LibraryTabView, type: Int, isMv: Boolean) {
    View {
        attr {
            // Dart Container(height: 500) 在 TabBarView 的紧约束下铺满整页并居中
            flex(1f)
            allCenter()
        }
        Image {
            attr {
                size(80f, 80f)
                resizeContain()
                val image = if (ctx.isLogin) {
                    if (type == 1) (if (isMv) AssetsImage.mvEmpty else AssetsImage.libraryEmpty) else AssetsImage.likeEmpty
                } else {
                    AssetsImage.noLogin
                }
                src(ImageUri.commonAssets(image))
            }
        }
        VGap(16f)
        Text {
            attr {
                fontSize(12f)
                color(Color(0x8AFFFFFFL))
                lines(1)
                textOverFlowTail()
                val text = if (type == 1) {
                    if (isMv) "create_your_very_own_ai_mv".tr else "create_your_very_own_ai_music".tr
                } else {
                    if (isMv) "bookmark_your_favorite_ai_mv".tr else "bookmark_your_favorite_ai_music".tr
                }
                text(if (ctx.isLogin) text else "launch_your_ai_creative_debut".tr)
            }
        }
        View {
            attr {
                margin(20f, 0f, 20f, 0f)
                flexDirectionRow()
                justifyContentCenter()
            }
            View {
                attr {
                    height(32f)
                    minWidth(100f)
                    padding(0f, 16f, 0f, 16f)
                    allCenter()
                    backgroundColor(UIColor.cF3EFF7)
                    borderRadius(53f)
                }
                event { click { ctx.onEmptyTap(type, isMv) } }
                Text {
                    attr {
                        fontSize(12f)
                        fontFamily("Poppins-SemiBold")
                        color(UIColor.c0A0A0A)
                        lines(1)
                        text(if (ctx.isLogin) (if (type == 1) "start_creating".tr else "browse".tr) else "log_in".tr)
                    }
                }
            }
        }
    }
}
