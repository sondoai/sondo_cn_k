package com.sondo.app.ui.list

import com.sondo.app.base.BasePager
import com.sondo.app.core.http.ApiResult
import com.sondo.app.data.models.PageResult
import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** 分页加载函数：`(pageNo 从 1 开始, pageSize) → ApiResult<PageResult<T>>`。 */
typealias PageLoader<T> = suspend (pageNo: Int, pageSize: Int) -> ApiResult<PageResult<T>>

/** 一次刷新 / 加载结束的通知（Dart `PagingFinished`）：本次是否成功、是否已无下一页。 */
typealias PagingFinished = (success: Boolean, noMore: Boolean) -> Unit

/**
 * 分页列表状态（替代 Dart 的 `RefreshMixin` / `RefreshState`(MultiRefresh) / `PagingController`）。
 *
 * 一个页面可以创建多个实例（多 Tab 列表各一个）。字段都是响应式的，直接在 UI 闭包里读：
 * ```
 * private val paging = PagingState<SongModel>(this, pageSize = 10, itemKey = { it.id }) { pageNo, pageSize ->
 *     MusicApi.myList(pageNo, pageSize)          // suspend，返回 ApiResult<PageResult<SongModel>>
 * }
 * override fun created() { super.created(); paging.refresh() }
 * …
 * SDRefreshList { attr { flex(1f); state = ctx.paging }; items { vfor({ ctx.paging.list }) { … } } }
 * ```
 *
 * 语义与 Dart 一致：
 * - `refresh()`：页码回到 1，成功后整体替换列表；返回条数 < pageSize 即 [noMore]。
 * - `loadMore()`：请求 pageNo + 1，成功后才推进页码；按 [itemKey] 去重后追加，整页都是已有数据也视为没有更多；
 *   失败不改页码，[loadMoreFailed] 置 true（底部显示「点击重试」）。
 * - 同一时刻只有一个请求在飞；[clearData]（换号 / 退出登录）会让在途请求的结果作废。
 *
 * @param scope 所属页面（或页面内的组件）；页面销毁后在途请求的回调不再到达
 * @param pageSize 每页条数（Dart getPageSize() 默认 10）
 * @param itemKey 列表项唯一键，用于上拉去重
 * @param after 每次列表变更后的回调（Dart `after`，如补充派生字段）
 */
class PagingState<T>(
    private val scope: PagerScope,
    val pageSize: Int = 10,
    private val itemKey: ((T) -> Any?)? = null,
    private val after: ((ObservableList<T>) -> Unit)? = null,
    private val loader: PageLoader<T>,
) {
    /** 数据列表（配合 `vfor({ state.list })`）。 */
    val list: ObservableList<T> by scope.observableList<T>()

    /** 有请求在进行（刷新或加载更多）。 */
    var loading: Boolean by scope.observable(false)
        private set

    /** 正在刷新（第一页）。 */
    var refreshing: Boolean by scope.observable(false)
        private set

    /** 正在加载更多。 */
    var loadingMore: Boolean by scope.observable(false)
        private set

    /** 没有下一页了（Dart `!hasMore`）。 */
    var noMore: Boolean by scope.observable(false)
        private set

    /** 最近一次**刷新**失败的错误文案；成功后清空。列表为空且非空串时显示错误态。 */
    var error: String by scope.observable("")
        private set

    /** 最近一次**加载更多**是否失败。 */
    var loadMoreFailed: Boolean by scope.observable(false)
        private set

    /** 是否已经完成过至少一次刷新（区分「首屏加载中」与「空列表」）。 */
    var loaded: Boolean by scope.observable(false)
        private set

    /** 后端返回的总数（PageResult.total）。 */
    var total: Int by scope.observable(0)
        private set

    /** 当前页码（最后一次成功加载的页）。 */
    var pageNo: Int = 1
        private set

    /** 刷新时是否顺带弹全局 Loading（Dart `isShowLoading`）。 */
    var isShowLoading: Boolean = false

    /** Dart `hasMore`。 */
    val hasMore: Boolean get() = !noMore

    /** 首屏加载中：还没数据且正在请求。 */
    val isInitialLoading: Boolean get() = list.isEmpty() && (loading || !loaded) && error.isEmpty()

    /** 空列表：请求成功但没有数据。 */
    val isEmpty: Boolean get() = list.isEmpty() && loaded && !loading && error.isEmpty()

    /** 错误态：没有数据且刷新失败。 */
    val isError: Boolean get() = list.isEmpty() && !loading && error.isNotEmpty()

    private var generation = 0
    private var ownsLoading = false

    /** 绑定的列表组件（由 SDRefreshList 设置，用来在状态变化时收尾刷新头 / 加载底）。 */
    internal var listener: PagingListener? = null

    private fun pager(): Pager? = try {
        scope.getPager() as? Pager
    } catch (e: Throwable) {
        null
    }

    /**
     * 刷新（第一页）。已有请求在飞时直接以当前状态回调 [onFinished]（保证下拉头能收起）。
     */
    fun refresh(onFinished: PagingFinished? = null) {
        val p = pager() ?: return
        if (loading) {
            onFinished?.invoke(true, noMore)
            return
        }
        val gen = ++generation
        loading = true
        refreshing = true
        loadMoreFailed = false
        if (isShowLoading) {
            ownsLoading = true
            (p as? BasePager)?.showLoading()
        }
        p.lifecycleScope.launch {
            val result = loader(1, pageSize)
            if (gen != generation) return@launch
            var success = false
            when (result) {
                is ApiResult.Success -> {
                    val page = result.data
                    pageNo = 1
                    total = page.total
                    list.clear()
                    list.addAll(page.list)
                    after?.invoke(list)
                    noMore = page.list.size < pageSize
                    error = ""
                    loaded = true
                    success = true
                }
                is ApiResult.Failure -> {
                    error = result.error.message?.takeIf { it.isNotEmpty() } ?: "request_failed"
                    loaded = true
                }
            }
            loading = false
            refreshing = false
            releaseLoading(p)
            listener?.onRefreshFinished(success, noMore)
            onFinished?.invoke(success, noMore)
        }
    }

    /**
     * 加载下一页。没有更多 / 正在请求时不发请求，直接回调 [onFinished]。
     */
    fun loadMore(onFinished: PagingFinished? = null) {
        val p = pager() ?: return
        if (loading || noMore) {
            // noMore：告诉底部「没有更多」；刷新尚未结束：先结束 footer，避免一直转圈
            onFinished?.invoke(true, noMore)
            return
        }
        val gen = ++generation
        val requestedPage = pageNo + 1
        loading = true
        loadingMore = true
        loadMoreFailed = false
        p.lifecycleScope.launch {
            val result = loader(requestedPage, pageSize)
            if (gen != generation) return@launch
            var success = false
            when (result) {
                is ApiResult.Success -> {
                    val page = result.data
                    pageNo = requestedPage
                    total = page.total
                    val toAdd = filterNewItems(page.list)
                    list.addAll(toAdd)
                    after?.invoke(list)
                    if (toAdd.isEmpty() || page.list.size < pageSize) noMore = true
                    success = true
                }
                is ApiResult.Failure -> {
                    loadMoreFailed = true
                }
            }
            loading = false
            loadingMore = false
            listener?.onLoadMoreFinished(success, noMore)
            onFinished?.invoke(success, noMore)
        }
    }

    /** 挂起版本：返回是否成功。 */
    suspend fun refreshAwait(): Boolean = suspendCoroutine { cont ->
        refresh { success, _ -> cont.resume(success) }
    }

    suspend fun loadMoreAwait(): Boolean = suspendCoroutine { cont ->
        loadMore { success, _ -> cont.resume(success) }
    }

    /**
     * 清空数据并回到初始状态（Dart `clearData()` / `clear()`）。在途请求的结果会被丢弃。
     */
    fun clearData() {
        generation++
        if (loading) {
            loading = false
            refreshing = false
            loadingMore = false
            pager()?.let { releaseLoading(it) }
            listener?.onRefreshFinished(true, false)
        }
        list.clear()
        total = 0
        pageNo = 1
        noMore = false
        error = ""
        loadMoreFailed = false
        loaded = false
    }

    // —— 本地改动列表（删除 / 更新一项后不必整页刷新）—— //

    /** 替换满足条件的第一项（触发该行重建）。返回是否找到。 */
    fun replaceFirst(predicate: (T) -> Boolean, newItem: T): Boolean {
        val index = list.indexOfFirst(predicate)
        if (index < 0) return false
        list[index] = newItem
        return true
    }

    /** 删除满足条件的项，返回删除的条数（同步修正 total）。 */
    fun removeWhere(predicate: (T) -> Boolean): Int {
        var removed = 0
        var i = list.size - 1
        while (i >= 0) {
            if (predicate(list[i])) {
                list.removeAt(i)
                removed++
            }
            i--
        }
        if (removed > 0) total = (total - removed).coerceAtLeast(0)
        return removed
    }

    /** 头部插入一项（新生成的作品）。 */
    fun prepend(item: T) {
        list.add(0, item)
        total += 1
    }

    private fun releaseLoading(p: Pager) {
        if (ownsLoading) {
            ownsLoading = false
            (p as? BasePager)?.hideLoading()
        }
    }

    private fun filterNewItems(result: List<T>): List<T> {
        val keyOf = itemKey
        if (keyOf == null || result.isEmpty() || list.isEmpty()) return result
        val existing = HashSet<Any?>(list.size)
        for (e in list) existing.add(keyOf(e))
        return result.filter { !existing.contains(keyOf(it)) }
    }

    companion object {
        /**
         * 接口直接返回 `ApiResult<List<T>>`（没有 total）时的便捷构造。
         */
        fun <T> ofList(
            scope: PagerScope,
            pageSize: Int = 10,
            itemKey: ((T) -> Any?)? = null,
            loader: suspend (pageNo: Int, pageSize: Int) -> ApiResult<List<T>>,
        ): PagingState<T> = PagingState(scope, pageSize, itemKey, null) { pageNo, size ->
            when (val r = loader(pageNo, size)) {
                is ApiResult.Success -> ApiResult.Success(PageResult(r.data, 0))
                is ApiResult.Failure -> r
            }
        }
    }
}

/** 列表组件监听分页结果，用来收尾刷新头 / 加载底。 */
internal interface PagingListener {
    fun onRefreshFinished(success: Boolean, noMore: Boolean)
    fun onLoadMoreFinished(success: Boolean, noMore: Boolean)
}
