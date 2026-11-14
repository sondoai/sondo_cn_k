package com.sondo.app.ui.list

import com.sondo.app.core.i18n.tr
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.anim.SDRefreshLoading
import com.sondo.app.ui.atoms.SDEmptyState
import com.sondo.app.ui.atoms.SDLoading
import com.sondo.app.ui.atoms.SDMusicLoading
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.event.layoutFrameDidChange
import com.tencent.kuikly.core.directives.velseif
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.Canvas
import com.tencent.kuikly.core.views.FooterRefresh
import com.tencent.kuikly.core.views.FooterRefreshEndState
import com.tencent.kuikly.core.views.FooterRefreshState
import com.tencent.kuikly.core.views.FooterRefreshView
import com.tencent.kuikly.core.views.List
import com.tencent.kuikly.core.views.ListView
import com.tencent.kuikly.core.views.Refresh
import com.tencent.kuikly.core.views.RefreshView
import com.tencent.kuikly.core.views.RefreshViewState
import com.tencent.kuikly.core.views.ScrollParams
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.WaterfallList
import kotlin.math.PI

class SDRefreshListAttr : ComposeAttr() {
    /**
     * 分页状态。给了它，刷新 / 加载更多 / 空态 / 错误态 / 首屏加载全部自动处理；
     * 不给则是「手动模式」：监听 `onRefresh` / `onLoadMore`，自己调 `endRefresh()` / `endLoadMore()`。
     */
    var state: PagingState<*>? = null
    /** 是否启用下拉刷新 / 上拉加载 */
    var enableRefresh: Boolean = true
    var enableLoadMore: Boolean = true
    /** 列数：1 = 普通列表；> 1 用瀑布流（`WaterfallList`），此时 [listWidth] 必须给 */
    var columnCount: Int = 1
    /** 瀑布流：列表宽度（一般 `ctx.screenWidth`）、列间距、行间距 */
    var listWidth: Float = 0f
    var itemSpacing: Float = 0f
    var lineSpacing: Float = 0f
    /** 内容内边距（单列 / 瀑布流都生效） */
    var contentPaddingTop: Float = 0f
    var contentPaddingH: Float = 0f
    var contentPaddingBottom: Float = 0f
    /** 滚动条 / 回弹 */
    var showIndicator: Boolean = false
    var bounces: Boolean = true
    /** 距离底部多远开始预加载下一页 */
    var preloadDistance: Float = 300f
    /** 空态：图片（AssetsImage.xxx）、文案（默认 `no_data`）、按钮文案 */
    var emptyImage: String = ""
    var emptyText: String = ""
    var emptyButtonText: String = ""
    /** 自定义空态 / 首屏加载 / 错误态（整块替换） */
    var emptyBuilder: ViewBuilder? = null
    var loadingBuilder: ViewBuilder? = null
    var errorBuilder: ViewBuilder? = null
    /** 状态占位块的高度；<= 0 时用列表可视高度的 70% */
    var placeholderHeight: Float = 0f
    /** 状态占位块撑满列表可视高度（空态 / 首屏加载在整个列表区域内居中，Dart `Center` 直接作为 SmartRefresher 的 child） */
    var placeholderFill: Boolean = false
    /** 没有更多时是否显示「没有更多数据」；false 则底部留白 */
    var showNoMoreText: Boolean = true
    /** 底部文案（默认用 i18n：上拉加载更多 / 加载中... / 没有更多数据 / 请求失败，请重试。） */
    var footerIdleText: String = ""
    var footerLoadingText: String = ""
    var footerNoMoreText: String = ""
    var footerFailedText: String = ""
    /** 底部额外留白（悬浮底栏 / 迷你播放器的高度） */
    var footerExtraPadding: Float = 0f
    /** 首屏最多同步创建多少个 item（Kuikly firstContentLoadMaxIndex）；<= 0 用默认 */
    var firstContentLoadMaxIndex: Int = 0
}

class SDRefreshListEvent : ComposeEvent() {
    internal var refreshHandler: (() -> Unit)? = null
    internal var loadMoreHandler: (() -> Unit)? = null
    internal var scrollHandler: ((ScrollParams) -> Unit)? = null
    internal var scrollEndHandler: ((ScrollParams) -> Unit)? = null
    internal var emptyButtonHandler: (() -> Unit)? = null

    /** 手动模式：触发下拉刷新。结束后调用 `SDRefreshListView.endRefresh()`。 */
    fun onRefresh(handler: () -> Unit) { refreshHandler = handler }
    /** 手动模式：触发上拉加载。结束后调用 `SDRefreshListView.endLoadMore(noMore, success)`。 */
    fun onLoadMore(handler: () -> Unit) { loadMoreHandler = handler }
    fun onScroll(handler: (ScrollParams) -> Unit) { scrollHandler = handler }
    fun onScrollEnd(handler: (ScrollParams) -> Unit) { scrollEndHandler = handler }
    /** 空态按钮点击。 */
    fun onEmptyButtonTap(handler: () -> Unit) { emptyButtonHandler = handler }
}

/**
 * 下拉刷新 + 上拉加载的列表脚手架（Dart `SmartRefresher` + `RefreshMixin`）。
 *
 * ```
 * SDRefreshList {
 *     attr { flex(1f); state = ctx.paging; emptyImage = AssetsImage.libraryEmpty }
 *     header { … }                      // 可选：随列表滚动的头部
 *     items {                           // 列表项：自己写 vfor（闭包接收者是 ListView）
 *         vfor({ ctx.paging.list }) { item -> SongRow { attr { song = item } } }
 *     }
 * }
 * ```
 * - 下拉头：60 高，`SDRefreshLoading` 动画，完成后短暂显示「刷新完成」（与参考工程全局 RefreshConfiguration 一致）。
 * - 底部：上拉加载 / 加载中 / 没有更多 / 失败点击重试。
 * - 列表为空时在列表内部渲染首屏加载 / 空态 / 错误态，仍可下拉刷新。
 * - 网格：`columnCount = 2; listWidth = ctx.screenWidth`，items 里每个 vfor 项就是一个格子。
 */
class SDRefreshListView : ComposeView<SDRefreshListAttr, SDRefreshListEvent>(), PagingListener {
    private var headerBuilder: ViewBuilder? = null
    private var itemsBuilder: (ListView<*, *>.() -> Unit)? = null
    private var refreshRef: ViewRef<RefreshView>? = null
    private var footerRef: ViewRef<FooterRefreshView>? = null
    private var listGetter: (() -> ListView<*, *>?)? = null

    /** 下拉头：0 空闲 / 1 可松手 / 2 刷新中 / 3 完成 */
    private var headerState by observable(0)
    /** 手动模式下的底部状态 */
    private var manualFooter by observable(FooterRefreshState.IDLE)
    private var listHeight by observable(0f)
    /** 正在下拉（头部已露出）：此时就让指示动画跑起来 */
    private var pulling by observable(false)
    private var pullRefreshing = false
    private var alive = true

    override fun createAttr(): SDRefreshListAttr = SDRefreshListAttr()
    override fun createEvent(): SDRefreshListEvent = SDRefreshListEvent()

    /** 随列表滚动的头部内容。 */
    fun header(builder: ViewBuilder) { headerBuilder = builder }

    /** 列表项构建（在里面写 `vfor`）。 */
    fun items(builder: ListView<*, *>.() -> Unit) { itemsBuilder = builder }

    /** 底层 ListView（滚动到某位置等）。 */
    val listView: ListView<*, *>? get() = listGetter?.invoke()

    override fun created() {
        super.created()
        attr.state?.listener = this
    }

    override fun viewDestroyed() {
        alive = false
        if (attr.state?.listener === this) attr.state?.listener = null
        super.viewDestroyed()
    }

    // ------------------------------------------------------------------ 对外方法

    /** 以下拉动画的方式触发刷新（Dart `refreshController.requestRefresh()`）。 */
    fun beginRefresh() {
        refreshRef?.view?.beginRefresh(true)
    }

    /** 回到顶部。 */
    fun scrollToTop(animated: Boolean = true) {
        listView?.setContentOffset(0f, 0f, animated)
    }

    /** 手动模式：结束下拉刷新。[noMore] 用于同步重置底部状态。 */
    fun endRefresh(noMore: Boolean = false) {
        finishRefresh(true, noMore)
    }

    /** 手动模式：结束上拉加载。 */
    fun endLoadMore(noMore: Boolean, success: Boolean = true) {
        finishLoadMore(success, noMore)
    }

    // ------------------------------------------------------------------ PagingListener

    override fun onRefreshFinished(success: Boolean, noMore: Boolean) {
        finishRefresh(success, noMore)
    }

    override fun onLoadMoreFinished(success: Boolean, noMore: Boolean) {
        finishLoadMore(success, noMore)
    }

    // ------------------------------------------------------------------ 内部

    private fun startRefresh() {
        pullRefreshing = true
        headerState = 2
        val st = attr.state
        if (st == null) {
            event.refreshHandler?.invoke()
            return
        }
        // 已有请求在飞：等它结束时由 listener 收尾；如果那是一次「加载更多」，这里直接收起刷新头
        if (st.loading) {
            if (!st.refreshing) finishRefresh(true, st.noMore)
            return
        }
        st.refresh()
    }

    private fun finishRefresh(success: Boolean, noMore: Boolean) {
        if (!alive) return
        // 刷新成功后底部状态复位
        syncFooter(noMore)
        if (!pullRefreshing) return
        pullRefreshing = false
        if (success) {
            // 短暂展示「刷新完成」
            headerState = 3
            setTimeout(pagerId, 500) {
                if (!alive) return@setTimeout
                refreshRef?.view?.endRefresh()
                headerState = 0
            }
        } else {
            refreshRef?.view?.endRefresh()
            headerState = 0
        }
    }

    private fun syncFooter(noMore: Boolean) {
        manualFooter = if (noMore) FooterRefreshState.NONE_MORE_DATA else FooterRefreshState.IDLE
        // footer 可能在本次数据到达后才被 vif 创建，下一帧再同步一次
        val apply = {
            footerRef?.view?.resetRefreshState(if (noMore) FooterRefreshState.NONE_MORE_DATA else FooterRefreshState.IDLE)
        }
        apply()
        setTimeout(pagerId, 32) { if (alive) apply() }
    }

    private fun startLoadMore() {
        val st = attr.state
        if (st == null) {
            manualFooter = FooterRefreshState.REFRESHING
            event.loadMoreHandler?.invoke()
            return
        }
        if (st.loading || st.noMore) {
            footerRef?.view?.endRefresh(
                if (st.noMore) FooterRefreshEndState.NONE_MORE_DATA else FooterRefreshEndState.SUCCESS
            )
            return
        }
        st.loadMore()
    }

    private fun finishLoadMore(success: Boolean, noMore: Boolean) {
        if (!alive) return
        val end = when {
            !success -> FooterRefreshEndState.FAILURE
            noMore -> FooterRefreshEndState.NONE_MORE_DATA
            else -> FooterRefreshEndState.SUCCESS
        }
        manualFooter = when (end) {
            FooterRefreshEndState.FAILURE -> FooterRefreshState.FAILURE
            FooterRefreshEndState.NONE_MORE_DATA -> FooterRefreshState.NONE_MORE_DATA
            else -> FooterRefreshState.IDLE
        }
        footerRef?.view?.endRefresh(end)
    }

    /** 底部当前应显示的状态（有 PagingState 时以它为准）。 */
    private fun footerState(): FooterRefreshState {
        val st = attr.state ?: return manualFooter
        return when {
            st.loadMoreFailed -> FooterRefreshState.FAILURE
            st.noMore -> FooterRefreshState.NONE_MORE_DATA
            st.loadingMore -> FooterRefreshState.REFRESHING
            else -> FooterRefreshState.IDLE
        }
    }

    private fun footerText(): String {
        val a = attr
        return when (footerState()) {
            FooterRefreshState.REFRESHING -> a.footerLoadingText.ifEmpty { "loading".tr }
            FooterRefreshState.NONE_MORE_DATA -> if (a.showNoMoreText) a.footerNoMoreText.ifEmpty { "no_more_data".tr } else ""
            FooterRefreshState.FAILURE -> a.footerFailedText.ifEmpty { "request_failed".tr }
            else -> a.footerIdleText.ifEmpty { "pull_up_to_load_more".tr }
        }
    }

    private fun placeholderHeight(): Float {
        if (attr.placeholderHeight > 0f) return attr.placeholderHeight
        if (attr.placeholderFill && listHeight > 0f) {
            return listHeight - attr.contentPaddingTop - attr.contentPaddingBottom
        }
        val h = listHeight * 0.7f
        return if (h < 240f) 240f else h
    }

    private fun hasItems(): Boolean = attr.state?.list?.isNotEmpty() ?: true

    private fun buildList(list: ListView<*, *>) {
        val ctx = this
        val a = attr
        val fullWidth = a.columnCount > 1
        with(list) {
            if (a.enableRefresh) {
                Refresh {
                    ref { ctx.refreshRef = it }
                    attr {
                        height(60f)
                        allCenter()
                    }
                    event {
                        refreshStateDidChange { state ->
                            when (state) {
                                RefreshViewState.REFRESHING -> ctx.startRefresh()
                                RefreshViewState.PULLING -> if (ctx.headerState != 3) ctx.headerState = 1
                                RefreshViewState.IDLE -> if (ctx.headerState != 3) ctx.headerState = 0
                            }
                        }
                        pullingPercentageChanged { percent ->
                            val now = percent > 0.02f
                            if (now != ctx.pulling) ctx.pulling = now
                        }
                    }
                    vif({ ctx.headerState == 3 }) {
                        View {
                            attr {
                                padding(6f, 12f, 6f, 12f)
                                borderRadius(400f)
                                backgroundColor(SDColors.white10)
                            }
                            Text {
                                attr {
                                    text("refresh_complete".tr)
                                    fontSize(12f)
                                    color(SDColors.white60)
                                }
                            }
                        }
                    }
                    vif({ ctx.headerState != 3 }) {
                        SDRefreshLoading {
                            attr { active = ctx.headerState != 0 || ctx.pulling }
                        }
                    }
                }
            }

            if (ctx.headerBuilder != null) {
                View {
                    attr { if (fullWidth) width(a.listWidth) }
                    ctx.headerBuilder?.invoke(this)
                }
            }

            ctx.itemsBuilder?.invoke(this)

            // —— 状态占位（仅有 PagingState 时）—— //
            val st = a.state
            if (st != null) {
                vif({ st.isInitialLoading }) {
                    View {
                        attr {
                            if (fullWidth) width(a.listWidth)
                            height(ctx.placeholderHeight())
                            // 自定义加载（骨架）拿满宽、从顶部排，自己决定是否居中；默认 SDMusicLoading 居中
                            if (a.loadingBuilder == null) allCenter()
                        }
                        if (a.loadingBuilder != null) a.loadingBuilder?.invoke(this) else SDMusicLoading()
                    }
                }
                velseif({ st.isError }) {
                    View {
                        attr {
                            if (fullWidth) width(a.listWidth)
                            height(ctx.placeholderHeight())
                        }
                        if (a.errorBuilder != null) {
                            a.errorBuilder?.invoke(this)
                        } else {
                            SDEmptyState {
                                attr {
                                    text = st.error.tr
                                    buttonText = "retry".tr
                                }
                                event { onButtonTap { st.refresh() } }
                            }
                        }
                    }
                }
                velseif({ st.isEmpty }) {
                    View {
                        attr {
                            if (fullWidth) width(a.listWidth)
                            height(ctx.placeholderHeight())
                        }
                        if (a.emptyBuilder != null) {
                            a.emptyBuilder?.invoke(this)
                        } else {
                            SDEmptyState {
                                attr {
                                    image = a.emptyImage
                                    text = a.emptyText
                                    buttonText = a.emptyButtonText
                                }
                                event { onButtonTap { ctx.event.emptyButtonHandler?.invoke() } }
                            }
                        }
                    }
                }
            }

            // —— 底部加载更多 —— //
            if (a.enableLoadMore) {
                vif({ ctx.hasItems() }) {
                    FooterRefresh {
                        ref { ctx.footerRef = it }
                        attr {
                            if (fullWidth) width(a.listWidth)
                            preloadDistance(a.preloadDistance)
                            height(60f + a.footerExtraPadding)
                            paddingBottom(a.footerExtraPadding)
                            flexDirectionRow()
                            allCenter()
                        }
                        event {
                            refreshStateDidChange { state ->
                                if (state == FooterRefreshState.REFRESHING) ctx.startLoadMore()
                            }
                            click {
                                // 失败后点击重试
                                if (ctx.footerState() == FooterRefreshState.FAILURE) {
                                    ctx.footerRef?.view?.beginRefresh()
                                }
                            }
                        }
                        // ClassicFooter：空闲上箭头 / 加载中转圈 / 失败错误图标（灰色 16，与文字间距 8）
                        vif({ ctx.footerState() == FooterRefreshState.REFRESHING }) {
                            SDLoading {
                                attr {
                                    size = 16f
                                    marginRight(8f)
                                }
                            }
                        }
                        vif({ ctx.footerState() == FooterRefreshState.IDLE }) {
                            footerIcon(failed = false)
                        }
                        vif({ ctx.footerState() == FooterRefreshState.FAILURE }) {
                            footerIcon(failed = true)
                        }
                        Text {
                            attr {
                                text(ctx.footerText())
                                fontSize(14f)
                                color(Color(0xFF9E9E9EL))
                            }
                        }
                    }
                }
            } else if (a.footerExtraPadding > 0f) {
                View {
                    attr {
                        if (fullWidth) width(a.listWidth)
                        height(a.footerExtraPadding)
                    }
                }
            }
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val a = ctx.attr
        return {
            if (a.columnCount > 1) {
                WaterfallList {
                    ref { r -> ctx.listGetter = { r.view } }
                    attr {
                        flex(1f)
                        listWidth(a.listWidth)
                        columnCount(a.columnCount)
                        itemSpacing(a.itemSpacing)
                        lineSpacing(a.lineSpacing)
                        contentPadding(a.contentPaddingTop, a.contentPaddingH, a.contentPaddingBottom, a.contentPaddingH)
                        showScrollerIndicator(a.showIndicator)
                        bouncesEnable(a.bounces)
                        if (a.firstContentLoadMaxIndex > 0) firstContentLoadMaxIndex(a.firstContentLoadMaxIndex)
                    }
                    event {
                        layoutFrameDidChange { ctx.listHeight = it.height }
                        scroll { ctx.event.scrollHandler?.invoke(it) }
                        scrollEnd { ctx.event.scrollEndHandler?.invoke(it) }
                    }
                    ctx.buildList(this)
                }
            } else {
                List {
                    ref { r -> ctx.listGetter = { r.view } }
                    attr {
                        flex(1f)
                        // ScrollerAttr.padding 作用在内容视图上，等同 ListView.builder(padding: …)
                        if (a.contentPaddingTop > 0f || a.contentPaddingH > 0f || a.contentPaddingBottom > 0f) {
                            padding(a.contentPaddingTop, a.contentPaddingH, a.contentPaddingBottom, a.contentPaddingH)
                        }
                        showScrollerIndicator(a.showIndicator)
                        bouncesEnable(a.bounces)
                        if (a.firstContentLoadMaxIndex > 0) firstContentLoadMaxIndex(a.firstContentLoadMaxIndex)
                    }
                    event {
                        layoutFrameDidChange { ctx.listHeight = it.height }
                        scroll { ctx.event.scrollHandler?.invoke(it) }
                        scrollEnd { ctx.event.scrollEndHandler?.invoke(it) }
                    }
                    ctx.buildList(this)
                }
            }
        }
    }
}

/** ClassicFooter 的灰色 16 图标：Material `arrow_upward` / `error`（Canvas 画，避免新增资源）。 */
private fun ViewContainer<*, *>.footerIcon(failed: Boolean) {
    Canvas({
        attr {
            size(16f, 16f)
            marginRight(8f)
        }
    }) { context, width, _ ->
        val grey = Color(0xFF9E9E9EL)
        val u = width / 24f
        if (failed) {
            // 实心圆 + 白色感叹号
            context.beginPath()
            context.fillStyle(grey)
            context.arc(12f * u, 12f * u, 10f * u, 0f, (2.0 * PI).toFloat(), false)
            context.fill()
            context.beginPath()
            context.fillStyle(Color.WHITE)
            context.moveTo(11f * u, 7f * u)
            context.lineTo(13f * u, 7f * u)
            context.lineTo(13f * u, 13f * u)
            context.lineTo(11f * u, 13f * u)
            context.closePath()
            context.fill()
            context.beginPath()
            context.moveTo(11f * u, 15f * u)
            context.lineTo(13f * u, 15f * u)
            context.lineTo(13f * u, 17f * u)
            context.lineTo(11f * u, 17f * u)
            context.closePath()
            context.fill()
        } else {
            // 竖线 + 箭头
            context.beginPath()
            context.strokeStyle(grey)
            context.lineWidth(2f * u)
            context.lineCapButt()
            context.moveTo(12f * u, 20f * u)
            context.lineTo(12f * u, 5f * u)
            context.moveTo(5f * u, 12f * u)
            context.lineTo(12f * u, 5f * u)
            context.lineTo(19f * u, 12f * u)
            context.stroke()
        }
    }
}

fun ViewContainer<*, *>.SDRefreshList(init: SDRefreshListView.() -> Unit) {
    addChild(SDRefreshListView(), init)
}
