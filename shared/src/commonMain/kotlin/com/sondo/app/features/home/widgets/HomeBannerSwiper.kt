package com.sondo.app.features.home.widgets

import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.asset
import com.sondo.app.core.i18n.tr
import com.sondo.app.features.home.data.HomeBannerItem
import com.sondo.app.nativeapi.SDVideo
import com.sondo.app.nativeapi.SDVideoState
import com.sondo.app.nativeapi.SDVideoView
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDShimmer
import com.sondo.app.ui.list.SDSwiper
import com.sondo.app.ui.list.SDSwiperView
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.clearTimeout
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

class HomeBannerSwiperAttr : ComposeAttr() {
    var banners: List<HomeBannerItem> by observable(emptyList())
    /** 轮播当前页（由首页状态持有，刷新重建时从这一页开始）。 */
    var currentIndex: Int = 0
    var loading: Boolean by observable(false)
    var failed: Boolean by observable(false)
    /** 在首页 Tab 且根页面没被盖住：允许挂载解码器。 */
    var playbackEnabled: Boolean by observable(false)
    /** 轮播可见超过一半：否则只暂停（保留解码器）。 */
    var inViewport: Boolean by observable(true)
    /** 轮播边长（固定 1:1）。 */
    var side: Float = 0f
}

class HomeBannerSwiperEvent : ComposeEvent() {
    internal var retry: (() -> Unit)? = null
    internal var indexChanged: ((Int) -> Unit)? = null
    internal var tapCard: ((Int) -> Unit)? = null
    internal var tapButton: ((HomeBannerItem) -> Unit)? = null

    fun onRetry(h: () -> Unit) { retry = h }
    fun onIndexChanged(h: (Int) -> Unit) { indexChanged = h }
    /** 点整张卡（有视频进播放页，否则按 router 跳转）。 */
    fun onTapCard(h: (Int) -> Unit) { tapCard = h }
    /** 有视频时按钮单独跳转。 */
    fun onTapButton(h: (HomeBannerItem) -> Unit) { tapButton = h }
}

/**
 * 首页轮播（Dart `HomeBannerSwiper`）：视频静音播满 5 秒再切，图片停 5 秒再切；
 * 只有当前页且允许播放时挂载 SDVideo，滚出视口只暂停。空列表 / 失败都留骨架，失败时给重试。
 */
class HomeBannerSwiperView : ComposeView<HomeBannerSwiperAttr, HomeBannerSwiperEvent>() {
    private var current by observable(0)
    private var swiperRef: ViewRef<SDSwiperView>? = null
    private val videoRefs = HashMap<Int, ViewRef<SDVideoView>>()
    private var slideTimer: String? = null
    private var timedIndex: Int? = null
    private var alive = true

    // 视频播放计时
    private var playedMs = 0L
    private var lastPosition: Long? = null
    private var reportedAdvance = false
    private var videoPlaying = false

    override fun createAttr() = HomeBannerSwiperAttr()
    override fun createEvent() = HomeBannerSwiperEvent()

    override fun created() {
        super.created()
        current = attr.currentIndex
        bindValueChange({ attr.banners }) {
            val size = attr.banners.size
            current = if (size == 0 || current < 0) 0 else current.coerceAtMost(size - 1)
            resetClock()
            armSlideTimer()
        }
        bindValueChange({ attr.playbackEnabled }) {
            if (!attr.playbackEnabled) resetClock()
            armSlideTimer()
        }
        bindValueChange({ attr.inViewport }) {
            val ref = videoRefs[current]?.view
            if (attr.inViewport) ref?.play() else ref?.pause()
            armSlideTimer()
        }
    }

    override fun viewDestroyed() {
        alive = false
        cancelSlideTimer()
        unbindAllValueChange()
        super.viewDestroyed()
    }

    private val canCount: Boolean get() = attr.playbackEnabled && attr.inViewport

    private fun cancelSlideTimer() {
        slideTimer?.let { clearTimeout(it) }
        slideTimer = null
        timedIndex = null
    }

    /** 非视频停满 5 秒再切。离开首页、全屏或滚出视口时取消，回来重新计。 */
    private fun armSlideTimer() {
        if (!alive) return
        val banners = attr.banners
        val index = current
        val item = banners.getOrNull(index)
        val shouldTime = canCount && banners.size > 1 && item != null && !item.hasVideo
        if (!shouldTime) {
            cancelSlideTimer()
            return
        }
        if (slideTimer != null && timedIndex == index) return
        cancelSlideTimer()
        timedIndex = index
        slideTimer = setTimeout(pagerId, SLIDE_DWELL_MS) {
            slideTimer = null
            timedIndex = null
            if (!alive || current != index) return@setTimeout
            goNext()
        }
    }

    private fun goNext() {
        if (!alive || attr.banners.size < 2) return
        cancelSlideTimer()
        swiperRef?.view?.next()
    }

    private fun resetClock() {
        playedMs = 0L
        lastPosition = null
        reportedAdvance = false
    }

    private fun onIndexChanged(index: Int) {
        current = index
        resetClock()
        event.indexChanged?.invoke(index)
        armSlideTimer()
    }

    /** 只累计当前页正在播放的进度。播满 5 秒，或短片播完一遍，切下一张。 */
    private fun onClock(index: Int, position: Long, duration: Long, completed: Boolean) {
        if (!alive || reportedAdvance) return
        if (index != current) {
            playedMs = 0L
            lastPosition = null
            return
        }
        val last = lastPosition
        if (videoPlaying && last != null) {
            val delta = position - last
            // 循环回到开头时 delta 为负，不把回绕算进已播放时长
            if (delta in 1 until 2000) playedMs += delta
        }
        lastPosition = position
        if (!attr.playbackEnabled || (!videoPlaying && !completed)) return
        val shortClip = duration in 1 until SLIDE_DWELL_MS
        val shortFinished = shortClip &&
            (completed || (position > 0 && duration - position <= SHORT_END_SLOP_MS) || (last != null && position < last))
        if (playedMs < SLIDE_DWELL_MS && !shortFinished) return
        reportedAdvance = true
        goNext()
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { size(ctx.attr.side, ctx.attr.side) }
            vif({ ctx.attr.banners.isEmpty() }) {
                View {
                    attr {
                        absolutePositionAllZero()
                        allCenter()
                    }
                    SDShimmer {
                        attr {
                            absolutePositionAllZero()
                            highlightColor = Color(255, 255, 255, 0.3f)
                        }
                    }
                    vif({ ctx.attr.failed && !ctx.attr.loading }) {
                        homeBannerButton("retry".tr) { ctx.event.retry?.invoke() }
                    }
                }
            }
            vif({ ctx.attr.banners.isNotEmpty() }) {
                View {
                    attr { absolutePositionAllZero() }
                    vbind({ ctx.attr.banners }) {
                        val banners = ctx.attr.banners
                        val side = ctx.attr.side
                        SDSwiper {
                            ref { ctx.swiperRef = it }
                            attr {
                                pageWidth = side
                                pageHeight = side
                                itemCount = banners.size
                                initialIndex = ctx.current.coerceIn(0, (banners.size - 1).coerceAtLeast(0))
                                showIndicator = false
                                itemBuilder = { index -> homeBannerCard(ctx, banners[index], index, side) }
                            }
                            event { onIndexChanged { ctx.onIndexChanged(it) } }
                        }
                        // 圆点不进每一页，滑的时候停在原位；不接点击
                        View {
                            attr {
                                absolutePosition(left = 16f, right = 16f, bottom = 0f)
                                height(80f)
                                justifyContentCenter()
                                touchEnable(false)
                            }
                            vbind({ ctx.current }) {
                                val safe = ctx.current.coerceIn(0, banners.size - 1)
                                val hasTitle = banners[safe].title.isNotEmpty()
                                View {
                                    if (hasTitle) {
                                        Text {
                                            attr {
                                                text(banners[safe].title)
                                                fontSize(20f)
                                                fontFamily("Poppins-Bold")
                                                color(Color.WHITE)
                                                lines(1)
                                                opacity(0f)
                                            }
                                        }
                                        View { attr { height(4f) } }
                                    }
                                    View {
                                        attr { flexDirectionRow() }
                                        for (i in banners.indices) {
                                            Image {
                                                attr {
                                                    if (i > 0) marginLeft(8f)
                                                    size(8f, 8f)
                                                    src((if (i == safe) AssetsImage.homeBannerDotActive else AssetsImage.homeBannerDot).asset())
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
    }

    internal fun isCurrent(index: Int) = current == index
    internal val playbackEnabled: Boolean get() = attr.playbackEnabled
    internal fun tapCard(index: Int) { event.tapCard?.invoke(index) }
    internal fun tapButton(item: HomeBannerItem) { event.tapButton?.invoke(item) }
    internal fun bindVideo(index: Int, ref: ViewRef<SDVideoView>) {
        videoRefs[index] = ref
    }
    internal fun onVideoState(index: Int, state: String) {
        if (index != current) return
        when (state) {
            SDVideoState.PLAYING -> videoPlaying = true
            SDVideoState.COMPLETED -> {
                videoPlaying = false
                onClock(index, lastPosition ?: 0L, 0L, true)
            }
            SDVideoState.READY -> if (!attr.inViewport) videoRefs[index]?.view?.pause()
            else -> videoPlaying = false
        }
    }
    internal fun onVideoProgress(index: Int, position: Long, duration: Long) = onClock(index, position, duration, false)
    internal fun onVideoGone(index: Int) {
        videoRefs.remove(index)
        if (index == current) {
            videoPlaying = false
            resetClock()
        }
    }

    companion object {
        private const val SLIDE_DWELL_MS = 5000
        private const val SHORT_END_SLOP_MS = 300L
    }
}

private fun ViewContainer<*, *>.homeBannerCard(ctx: HomeBannerSwiperView, item: HomeBannerItem, index: Int, side: Float) {
    View {
        attr { size(side, side) }
        event { click { ctx.tapCard(index) } }
        SDImage {
            attr {
                absolutePositionAllZero()
                url = item.firstFrameImg
            }
        }
        if (item.hasVideo) {
            // 只给当前显示的视频挂解码器；离开首页 / 进全屏时卸掉
            vif({ ctx.playbackEnabled && ctx.isCurrent(index) }) {
                SDVideo {
                    ref { ctx.bindVideo(index, it) }
                    attr {
                        absolutePositionAllZero()
                        src(item.resultUrl.trim())
                        autoPlay(true)
                        loop(true)
                        muted(true)
                        volume(0f)
                        mixWithOthers(true)
                        fitCover()
                        cache(true)
                        progressIntervalMs(200)
                    }
                    event {
                        onState { ctx.onVideoState(index, it.state) }
                        onProgress { ctx.onVideoProgress(index, it.positionMs, it.durationMs) }
                    }
                }
            }
        }
        // 上下渐变遮罩
        View {
            attr {
                absolutePosition(top = 0f, left = 0f, right = 0f)
                height(100f)
                touchEnable(false)
                backgroundLinearGradient(Direction.TO_BOTTOM, ColorStop(Color(0x990A0A0AL), 0f), ColorStop(Color(0x000A0A0AL), 1f))
            }
        }
        View {
            attr {
                absolutePosition(left = 0f, right = 0f, bottom = 0f)
                height(80f)
                touchEnable(false)
                backgroundLinearGradient(Direction.TO_TOP, ColorStop(Color(0xFF0A0A0AL), 0f), ColorStop(Color(0x000A0A0AL), 1f))
            }
        }
        View {
            attr {
                absolutePosition(left = 16f, right = 16f, bottom = 0f)
                height(80f)
                flexDirectionRow()
                alignItemsCenter()
            }
            View {
                attr {
                    flex(1f)
                    justifyContentCenter()
                }
                if (item.title.isNotEmpty()) {
                    Text {
                        attr {
                            text(item.title)
                            fontSize(20f)
                            fontFamily("Poppins-Bold")
                            color(Color.WHITE)
                            lines(1)
                            textOverFlowTail()
                        }
                    }
                    // 圆点画在轮播外层，这里留出同样高度
                    View { attr { height(4f) } }
                }
                View { attr { height(8f) } }
            }
            View { attr { width(10f) } }
            val label = item.buttonTextFor()
            // 有视频时按钮单独跳转；无视频时不拦截，整张封面走同一个点击
            homeBannerButton(label.ifEmpty { "create".tr }, if (item.hasVideo) ({ ctx.tapButton(item) }) else null)
        }
    }
}

/** 半透明白色胶囊按钮（Dart `_BannerButton`；背景模糊 sigma 2 省略，底色已是白 80%）。 */
private fun ViewContainer<*, *>.homeBannerButton(label: String, onTap: (() -> Unit)?) {
    View {
        attr {
            height(34f)
            minWidth(100f)
            padding(0f, 16f, 0f, 16f)
            borderRadius(55f)
            backgroundColor(Color(255, 255, 255, 0.8f))
            allCenter()
        }
        if (onTap != null) event { click { onTap() } }
        Text {
            attr {
                text(label)
                fontSize(12f)
                color(Color(0xFF0A0A0AL))
                fontFamily("Poppins-Bold")
                lines(1)
                textOverFlowTail()
            }
        }
    }
}

fun ViewContainer<*, *>.HomeBannerSwiper(init: HomeBannerSwiperView.() -> Unit) {
    addChild(HomeBannerSwiperView(), init)
}
