package com.sondo.app.ui.video

import com.sondo.app.ui.SDFit
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.event.layoutFrameDidChange
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.PageList
import com.tencent.kuikly.core.views.PageListView
import com.tencent.kuikly.core.views.View

/** Dart `VideoPlayerListItem`。 */
class VideoPlayerListItem(
    val id: Any,
    val url: String,
    val coverUrl: String = "",
    val aspectRatio: Float = 0f,
    /** 本页每次重建解码器都从这里开始（ms，-1 = 无） */
    val startPositionMs: Long = -1L,
    val resumeKey: String? = null,
) {
    val effectiveResumeKey: String get() = resumeKey ?: "video_player_list:$id"
}

class SDVideoPlayerListAttr : ComposeAttr() {
    var items: List<VideoPlayerListItem> by observable(emptyList())
    var initialIndex: Int = 0
    /** 路由可见 && 前台（obs）：false 时当前页也释放解码器 */
    var isActive: Boolean by observable(true)
    /** 只暂停、保留当前帧（obs） */
    var forcePaused: Boolean by observable(false)
    var playbackGate: AppVideoPlaybackGate? = null
    var bypassPlaybackGate: Boolean = false
    var fit: SDFit = SDFit.COVER
    var looping: Boolean = true
    var muted: Boolean = false
    var mixWithOthers: Boolean? = null
    var showProgress: Boolean = true
    var tapToTogglePlayback: Boolean = true
    var allowScrubbing: Boolean = true
    var progressPadding: FloatArray = floatArrayOf(0f, 0f, 0f, 0f)
    var preloadDistance: Int = 1
    var cacheNetworkSources: Boolean = true
    var endReachedThreshold: Int = 2
    var hasMore: Boolean by observable(true)
    /** 空列表（默认黑底） */
    var emptyBuilder: ViewBuilder? = null
    var errorBuilder: (ViewContainer<*, *>.(index: Int, message: String, retry: () -> Unit) -> Unit)? = null
    var overlayBuilder: (ViewContainer<*, *>.(index: Int, isCurrent: Boolean) -> Unit)? = null
}

class SDVideoPlayerListEvent : ComposeEvent() {
    internal var pageChanged: ((Int) -> Unit)? = null
    internal var endReached: ((done: () -> Unit) -> Unit)? = null
    internal var ready: ((Int, AppVideoController) -> Unit)? = null
    internal var progress: ((Int, Long, Long) -> Unit)? = null
    internal var completed: ((Int) -> Unit)? = null
    internal var playingChanged: ((Int, Boolean) -> Unit)? = null
    internal var doubleTap: ((Int) -> Unit)? = null

    fun onPageChanged(handler: (index: Int) -> Unit) { pageChanged = handler }
    /** 触底加载：加载结束（成功或失败）后调用 done()，之前不会再次触发 */
    fun onEndReached(handler: (done: () -> Unit) -> Unit) { endReached = handler }
    fun onReady(handler: (index: Int, controller: AppVideoController) -> Unit) { ready = handler }
    fun onProgress(handler: (index: Int, positionMs: Long, durationMs: Long) -> Unit) { progress = handler }
    fun onCompleted(handler: (index: Int) -> Unit) { completed = handler }
    fun onPlayingChanged(handler: (index: Int, isPlaying: Boolean) -> Unit) { playingChanged = handler }
    /** 不设置时不注册双击，单击立即响应 */
    fun onDoubleTap(handler: (index: Int) -> Unit) { doubleTap = handler }
}

/**
 * Dart `VideoPlayerList`：一屏一条的竖滑视频列表。只有当前页 `isActive`（`deferInitUntilActive`）→ 同时只有一个解码器，
 * 其它页显示封面；邻页按 [SDVideoPlayerListAttr.preloadDistance] 预热磁盘缓存；末尾区间触发加载更多。
 */
class SDVideoPlayerListView : ComposeView<SDVideoPlayerListAttr, SDVideoPlayerListEvent>() {
    var currentIndex by observable(0)
        private set
    private var pageW by observable(0f)
    private var pageH by observable(0f)
    private var pageListRef: ViewRef<PageListView<*, *>>? = null
    private var loadingMore = false
    private var lastEndReachedLength = -1

    override fun createAttr(): SDVideoPlayerListAttr = SDVideoPlayerListAttr()
    override fun createEvent(): SDVideoPlayerListEvent = SDVideoPlayerListEvent()

    override fun created() {
        super.created()
        currentIndex = attr.initialIndex.coerceAtLeast(0)
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        bindValueChange({ "${attr.items.size}|${attr.hasMore}" }) {
            if (currentIndex >= attr.items.size && attr.items.isNotEmpty()) currentIndex = attr.items.size - 1
            prefetchAround(currentIndex)
            maybeEndReached(currentIndex, fromUser = false)
        }
    }

    override fun viewDestroyed() {
        unbindAllValueChange()
        super.viewDestroyed()
    }

    fun scrollToIndex(index: Int, animated: Boolean = false) {
        pageListRef?.view?.scrollToPageIndex(index, animated)
    }

    private fun onPage(index: Int) {
        if (index == currentIndex) return
        currentIndex = index
        event.pageChanged?.invoke(index)
        prefetchAround(index)
        maybeEndReached(index, fromUser = true)
    }

    private fun prefetchAround(index: Int) {
        if (!attr.cacheNetworkSources || attr.preloadDistance <= 0) return
        AppVideoPrefetch.neighbours(attr.items.map { it.url }, index, attr.preloadDistance)
    }

    private fun maybeEndReached(index: Int, fromUser: Boolean) {
        val handler = event.endReached ?: return
        val len = attr.items.size
        if (!attr.hasMore || loadingMore || len == 0) return
        if (index < len - 1 - attr.endReachedThreshold) return
        // 初始化 / 列表长度变化时每个长度只触发一次；用户在末尾区间翻页时每次都触发（相当于重试）
        if (!fromUser && lastEndReachedLength == len) return
        lastEndReachedLength = len
        loadingMore = true
        handler { loadingMore = false }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val a = attr
        return {
            attr { backgroundColor(Color.BLACK) }
            View {
                attr {
                    absolutePositionAllZero()
                    touchEnable(false)
                }
                event {
                    layoutFrameDidChange { f ->
                        if (f.width != ctx.pageW) ctx.pageW = f.width
                        if (f.height != ctx.pageH) ctx.pageH = f.height
                    }
                }
            }
            vif({ a.items.isEmpty() }) {
                View {
                    attr { absolutePositionAllZero() }
                    a.emptyBuilder?.invoke(this)
                }
            }
            vbind({ "${ctx.pageW}|${ctx.pageH}|${a.items.size}|${a.items.firstOrNull()?.id}" }) {
                if (ctx.pageW > 0f && ctx.pageH > 0f && a.items.isNotEmpty()) {
                    val w = ctx.pageW
                    val h = ctx.pageH
                    PageList {
                        ref { ctx.pageListRef = it }
                        attr {
                            absolutePositionAllZero()
                            pageItemWidth(w)
                            pageItemHeight(h)
                            pageDirection(false)
                            defaultPageIndex(ctx.currentIndex.coerceIn(0, a.items.size - 1))
                            showScrollerIndicator(false)
                        }
                        event {
                            pageIndexDidChanged {
                                val index = (it as? JSONObject)?.optInt("index", 0) ?: 0
                                ctx.onPage(index)
                            }
                        }
                        a.items.forEachIndexed { index, item ->
                            View {
                                attr { size(w, h) }
                                SDFeedVideoPlayer {
                                    attr {
                                        absolutePositionAllZero()
                                        url = item.url
                                        coverUrl = item.coverUrl
                                        aspectRatio = item.aspectRatio
                                        fit = a.fit
                                        looping = a.looping
                                        muted = a.muted
                                        mixWithOthers = a.mixWithOthers
                                        tapToTogglePlayback = a.tapToTogglePlayback
                                        allowScrubbing = a.allowScrubbing
                                        progressPadding = a.progressPadding
                                        playbackGate = a.playbackGate
                                        bypassPlaybackGate = a.bypassPlaybackGate
                                        resumeKey = item.effectiveResumeKey
                                        enableDoubleTap = ctx.event.doubleTap != null
                                        seekOnReadyMs = item.startPositionMs
                                        a.errorBuilder?.let { eb ->
                                            errorBuilder = { msg, retry -> eb(index, msg, retry) }
                                        }
                                    }
                                    attr {
                                        val isCurrent = ctx.currentIndex == index
                                        isActive = isCurrent && a.isActive
                                        forcePaused = a.forcePaused
                                        showProgress = a.showProgress && isCurrent
                                        showCenterPlayButton = isCurrent
                                    }
                                    event {
                                        onReady { c -> ctx.event.ready?.invoke(index, c) }
                                        onProgress { p, d -> ctx.event.progress?.invoke(index, p, d) }
                                        onCompleted { ctx.event.completed?.invoke(index) }
                                        onPlayingChanged { ctx.event.playingChanged?.invoke(index, it) }
                                        if (ctx.event.doubleTap != null) onDoubleTap { ctx.event.doubleTap?.invoke(index) }
                                    }
                                }
                                val overlay = a.overlayBuilder
                                if (overlay != null) {
                                    vbind({ ctx.currentIndex == index }) {
                                        View {
                                            attr { absolutePositionAllZero() }
                                            overlay(index, ctx.currentIndex == index)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Dart `VideoPlayerList`。 */
fun ViewContainer<*, *>.SDVideoPlayerList(init: SDVideoPlayerListView.() -> Unit) {
    addChild(SDVideoPlayerListView(), init)
}
