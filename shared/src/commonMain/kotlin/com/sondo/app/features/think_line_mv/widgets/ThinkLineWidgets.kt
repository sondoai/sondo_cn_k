package com.sondo.app.features.think_line_mv.widgets

import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.constants.c
import com.sondo.app.core.constants.whiteA
import com.sondo.app.core.i18n.tr
import com.sondo.app.data.models.DanceFeedbackTag
import com.sondo.app.data.models.MvThinkCardStatus
import com.sondo.app.data.models.MvThinkGeneratedCard
import com.sondo.app.nativeapi.SDVideo
import com.sondo.app.nativeapi.SDVideoState
import com.sondo.app.nativeapi.SDVideoView
import com.sondo.app.service.audio.AppAudioOutputCoordinator
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.atoms.SDAssetImage
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDLoading
import com.sondo.app.ui.input.SDTextArea
import com.sondo.app.ui.input.SDTextAreaView
import com.sondo.app.ui.input.hideKeyboard
import com.tencent.kuikly.core.base.Anchor
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.Rotate
import com.tencent.kuikly.core.base.Scale
import com.tencent.kuikly.core.base.Skew
import com.tencent.kuikly.core.base.Translate
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.event.layoutFrameDidChange
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.PageList
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.DivView
import com.tencent.kuikly.core.views.View

// 设计常量（Dart think_line_page.dart 顶部）
internal val kTlCardBg = c(0x1AFFFFFFL)
internal val kTlWhite60 = c(0x99FFFFFFL)
internal val kTlWhite30 = c(0x4DFFFFFFL)
internal val kTlErrorText = c(0xFFE85151L)

// ============================================================================
// ThinkLineAnimatedCard：从左滑入 + 淡入（380ms）；enableExit 时隐藏向右滑出
// ============================================================================

internal class TLAnimatedCardAttr : ComposeAttr() {
    var visible: Boolean by observable(true)
    var enableExit: Boolean = false
}

internal class TLAnimatedCardView : ComposeView<TLAnimatedCardAttr, ComposeEvent>() {
    /** 0 = 左侧待入场，1 = 已展示，2 = 右侧退场 */
    private var phase by observable(0)
    private var mounted by observable(false)
    private var dismissed by observable(false)
    private var hasAnimated = false
    private var contentBuilder: ViewBuilder? = null
    private var bound = false

    override fun createAttr(): TLAnimatedCardAttr = TLAnimatedCardAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    fun content(builder: ViewBuilder) {
        contentBuilder = builder
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        if (attr.visible) start()
        bindValueChange({ attr.visible }) { v ->
            if (!bound) {
                bound = true
                return@bindValueChange
            }
            val visible = v as Boolean
            if (visible && !hasAnimated) {
                start()
            } else if (attr.enableExit && !visible && hasAnimated && phase == 1 && !dismissed) {
                phase = 2
                setTimeout(pagerId, 380) { dismissed = true }
            }
        }
    }

    override fun viewDestroyed() {
        unbindAllValueChange()
        super.viewDestroyed()
    }

    private fun start() {
        hasAnimated = true
        mounted = true
        setTimeout(pagerId, 16) { if (phase == 0) phase = 1 }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            vif({ ctx.mounted && !ctx.dismissed }) {
                View {
                    attr {
                        val p = ctx.phase
                        opacity(if (p == 1) 1f else 0f)
                        transform(
                            Rotate.DEFAULT,
                            Scale.DEFAULT,
                            Translate(if (p == 0) -1f else if (p == 1) 0f else 1f, 0f),
                            Anchor.DEFAULT,
                            Skew.DEFAULT,
                        )
                        animate(Animation.easeOut(0.38f), ctx.phase)
                    }
                    ctx.contentBuilder?.invoke(this)
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.TLAnimatedCard(init: TLAnimatedCardView.() -> Unit) {
    addChild(TLAnimatedCardView(), init)
}

// ============================================================================
// 失败占位图（ImageFailedPlace）/ 60×60 缩略图（MvGeneratedWidget）
// ============================================================================

internal fun ViewContainer<*, *>.tlImageFailedPlace(size: Float = 24f) {
    View {
        attr { allCenter() }
        SDAssetImage(AssetsImage.imageFailed, size)
    }
}

/** 60×60 缩略图，适配 MvThinkGeneratedCard 三态；后端标锁定就盖锁。 */
internal fun ViewContainer<*, *>.tlGeneratedThumb(item: MvThinkGeneratedCard) {
    View {
        attr {
            size(60f, 60f)
            backgroundColor(whiteA(0.1f))
            borderRadius(10f)
            allCenter()
        }
        if (item.status == MvThinkCardStatus.locked) {
            SDAssetImage(AssetsImage.imageLocked, 24f)
        } else {
            when (item.displayStatus) {
                MvThinkCardStatus.success -> SDImage {
                    attr {
                        size(60f, 60f)
                        borderRadius(10f)
                        url = item.previewUrl
                    }
                }
                MvThinkCardStatus.locked -> SDAssetImage(AssetsImage.imageLocked, 24f)
                MvThinkCardStatus.generating -> SDLoading { attr { size = 30f } }
                MvThinkCardStatus.failed -> SDAssetImage(AssetsImage.imageFailed, 24f)
            }
        }
    }
}

/**
 * 横向缩略图行（_ThumbnailRow）：高 60，间距 10。
 * [scrollToUnlocked]：试看分镜视频前面是锁定项时，首帧滚到第一个未锁定项。
 */
internal fun ViewContainer<*, *>.tlThumbnailRow(
    items: List<MvThinkGeneratedCard>,
    scrollToUnlocked: Boolean,
    onItemTap: (Int) -> Unit,
) {
    var jumped = false
    Scroller {
        attr {
            height(60f)
            flexDirectionRow()
            showScrollerIndicator(false)
        }
        event {
            scroll { }
            contentSizeChanged { w, _ ->
                if (jumped || !scrollToUnlocked) return@contentSizeChanged
                jumped = true
                if (!items.any { it.status == MvThinkCardStatus.locked }) return@contentSizeChanged
                val index = items.indexOfFirst { it.status != MvThinkCardStatus.locked }
                if (index <= 0) return@contentSizeChanged
                val viewW = this@Scroller.flexNode.layoutFrame.width
                val maxX = (w - viewW).coerceAtLeast(0f)
                this@Scroller.setContentOffset((index * 70f).coerceIn(0f, maxX), 0f, false)
            }
        }
        items.forEachIndexed { index, item ->
            View {
                attr { marginRight(if (index < items.size - 1) 10f else 0f) }
                event {
                    click {
                        // 未解锁时点击无任何效果，不弹窗不跳转
                        if (item.status != MvThinkCardStatus.locked) onItemTap(index)
                    }
                }
                tlGeneratedThumb(item)
            }
        }
    }
}

// ============================================================================
// 歌手四视图翻页（_SingerImagePageView）
// ============================================================================

internal class TLSingerPagerAttr : ComposeAttr() {
    var label: String = ""
    var items: List<MvThinkGeneratedCard> = emptyList()
    var pageWidth: Float = 0f
    var pageHeight: Float = 0f
    var isRetryable: Boolean = false
    var onRetry: (() -> Unit)? = null
    var onTap: ((Int) -> Unit)? = null
}

internal class TLSingerPagerView : ComposeView<TLSingerPagerAttr, ComposeEvent>() {
    private var currentPage by observable(0)

    override fun createAttr(): TLSingerPagerAttr = TLSingerPagerAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        val a = ctx.attr
        return {
            attr {
                size(a.pageWidth, a.pageHeight)
            }
            PageList {
                attr {
                    absolutePositionAllZero()
                    pageItemWidth(a.pageWidth)
                    pageItemHeight(a.pageHeight)
                    pageDirection(true)
                    showScrollerIndicator(false)
                    bouncesEnable(false)
                }
                event {
                    pageIndexDidChanged {
                        val index = (it as? JSONObject)?.optInt("index", 0) ?: 0
                        if (index != ctx.currentPage) ctx.currentPage = index
                    }
                }
                a.items.forEachIndexed { index, item ->
                    View {
                        attr { size(a.pageWidth, a.pageHeight) }
                        event { click { a.onTap?.invoke(index) } }
                        tlSingerImageCard(item, a.isRetryable) { a.onRetry?.invoke() }
                    }
                }
            }
            // 底部渐变：标签 + 指示点
            View {
                attr {
                    absolutePosition(left = 0f, right = 0f, bottom = 0f)
                    height(40f)
                    padding(0f, 8f, 0f, 8f)
                    flexDirectionRow()
                    alignItemsCenter()
                    justifyContentSpaceBetween()
                    backgroundLinearGradient(Direction.TO_BOTTOM, ColorStop(Color.TRANSPARENT, 0f), ColorStop(c(0xB3000000L), 1f))
                    borderRadius(0f, 0f, 12f, 12f)
                }
                event { click { a.onTap?.invoke(ctx.currentPage) } }
                Text {
                    attr {
                        text(a.label)
                        fontSize(12f)
                        fontFamily("Poppins-SemiBold")
                        color(Color.WHITE)
                        lines(1)
                    }
                }
                if (a.items.size > 1) {
                    View {
                        attr {
                            flexDirectionRow()
                            alignItemsCenter()
                        }
                        a.items.forEachIndexed { i, _ ->
                            View {
                                attr {
                                    size(6f, 6f)
                                    margin(0f, 2f, 0f, 2f)
                                    borderRadius(3f)
                                    backgroundColor(if (ctx.currentPage == i) Color.WHITE else whiteA(0.3f))
                                    animate(Animation.linear(0.2f), ctx.currentPage)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.TLSingerPager(init: TLSingerPagerView.() -> Unit) {
    addChild(TLSingerPagerView(), init)
}

/** 单张歌手图（_SingerImageCard），按 displayStatus 渲染。 */
private fun ViewContainer<*, *>.tlSingerImageCard(item: MvThinkGeneratedCard, isRetryable: Boolean, onRetry: () -> Unit) {
    View {
        attr {
            flex(1f)
            borderRadius(12f)
            overflow(true)
        }
        when (item.displayStatus) {
            MvThinkCardStatus.locked -> Unit
            MvThinkCardStatus.success -> SDImage {
                attr {
                    flex(1f)
                    url = item.previewUrl
                }
            }
            MvThinkCardStatus.generating -> View {
                attr {
                    flex(1f)
                    backgroundColor(whiteA(0.05f))
                    allCenter()
                }
                SDLoading { }
            }
            MvThinkCardStatus.failed -> View {
                attr {
                    flex(1f)
                    backgroundColor(whiteA(0.05f))
                    borderRadius(12f)
                    allCenter()
                }
                tlImageFailedPlace(42f)
                if (isRetryable) {
                    tlRetryBody(c(0xCCC685FFL), Color.WHITE, onRetry)
                }
            }
        }
    }
}

/** 失败图下方的「生成失败」+ 重试药丸（单图卡与歌手卡共用，颜色不同）。 */
internal fun ViewContainer<*, *>.tlRetryBody(pillColor: Color, textColor: Color, onRetry: () -> Unit) {
    Text {
        attr {
            margin(6f, 16f, 12f, 16f)
            text("generation_failed".tr)
            fontSize(12f)
            fontFamily("Poppins-Medium")
            color(whiteA(0.4f))
            textAlignCenter()
            lines(3)
        }
    }
    View {
        attr {
            height(30f)
            padding(0f, 16f, 0f, 16f)
            backgroundColor(pillColor)
            borderRadius(20f)
            allCenter()
        }
        event { click { onRetry() } }
        Text {
            attr {
                text("retry".tr)
                fontSize(10f)
                fontFamily("Poppins-SemiBold")
                color(textColor)
            }
        }
    }
}

// ============================================================================
// 失败步骤重试卡（ThinkLineRetryCard）
// ============================================================================

internal fun ViewContainer<*, *>.tlRetryCard(isLoading: () -> Boolean, onRepair: () -> Unit) {
    View {
        attr {
            padding(14f)
            backgroundColor(kTlCardBg)
            borderRadius(14f)
        }
        Text {
            attr {
                text("think_line_retry_hint".tr)
                fontSize(12f)
                fontFamily("Poppins-Medium")
                color(kTlWhite60)
            }
        }
        View {
            attr {
                marginTop(10f)
                flexDirectionRow()
                justifyContentFlexEnd()
            }
            View {
                attr {
                    height(36f)
                    padding(0f, 24f, 0f, 24f)
                    backgroundColor(UIColor.cFFF10)
                    borderRadius(100f)
                    flexDirectionRow()
                    allCenter()
                }
                event { click { onRepair() } }
                vif({ isLoading() }) {
                    View {
                        attr {
                            flexDirectionRow()
                            alignItemsCenter()
                        }
                        SDLoading { attr { size = 16f } }
                        View { attr { width(10f) } }
                    }
                }
                Text {
                    attr {
                        text("retry".tr)
                        fontSize(12f)
                        fontFamily("Poppins-SemiBold")
                        color(UIColor.cFFF80)
                    }
                }
            }
        }
    }
}

// ============================================================================
// 评价卡（ThinkLineFeedbackCard）
// ============================================================================

/** 评价卡外部给的初始值（Dart initialRating / initialTagCodes / initialContent / tags）。 */
internal data class TLFeedbackSeed(
    val rating: Int = 0,
    val tagCodes: List<String> = emptyList(),
    val content: String? = null,
    val tags: Map<Int, List<DanceFeedbackTag>> = emptyMap(),
)

internal class TLFeedbackCardAttr : ComposeAttr() {
    var totalStars: Int = 5
    var seed: TLFeedbackSeed by observable(TLFeedbackSeed())
    var onRatingChanged: ((Int) -> Unit)? = null
    var onSubmit: (suspend (Int, List<String>, String) -> Unit)? = null
    var onInputFocus: (() -> Unit)? = null
    var onKeyboardHeight: ((Float) -> Unit)? = null
}

internal class TLFeedbackCardView : ComposeView<TLFeedbackCardAttr, ComposeEvent>() {
    private var rating by observable(0)
    private var selectedVersion by observable(0)
    private val selectedTagCodes = LinkedHashSet<String>()
    private var text by observable("")
    private var isSubmitting by observable(false)
    private var tags: Map<Int, List<DanceFeedbackTag>> = emptyMap()
    private var tagsVersion by observable(0)
    private var areaRef: ViewRef<SDTextAreaView>? = null
    private var bound = false

    override fun createAttr(): TLFeedbackCardAttr = TLFeedbackCardAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun created() {
        super.created()
        applyInitialValues(attr.seed, force = true)
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        bindValueChange({ attr.seed }) { v ->
            if (!bound) {
                bound = true
                return@bindValueChange
            }
            applyInitialValues(v as TLFeedbackSeed, force = false)
        }
    }

    override fun viewDestroyed() {
        unbindAllValueChange()
        super.viewDestroyed()
    }

    private fun applyInitialValues(seed: TLFeedbackSeed, force: Boolean) {
        if (seed.tags.size != tags.size || seed.tags.keys.any { !tags.containsKey(it) }) {
            tags = seed.tags
            tagsVersion++
        }
        if (seed.rating > 0 && (force || seed.rating > rating)) rating = seed.rating
        if (seed.tagCodes.isNotEmpty() && (force || selectedTagCodes.isEmpty()) && seed.tagCodes.toSet() != selectedTagCodes) {
            selectedTagCodes.clear()
            selectedTagCodes.addAll(seed.tagCodes)
            selectedVersion++
        }
        val trimmed = seed.content?.trim() ?: ""
        if (trimmed.isNotEmpty() && (force || text.trim().isEmpty())) {
            text = trimmed
            areaRef?.view?.setText(trimmed)
        }
    }

    private val canSubmit: Boolean get() = (selectedVersion >= 0 && selectedTagCodes.isNotEmpty()) || text.trim().isNotEmpty()

    private val currentTags: List<DanceFeedbackTag>
        get() {
            if (tags.isEmpty()) return emptyList()
            if (rating > 0) tags[rating]?.let { return it }
            return tags[tags.keys.maxOrNull() ?: 0] ?: emptyList()
        }

    private val submitRating: Int
        get() {
            if (rating > 0) return rating
            if (tags.isEmpty()) return 0
            return tags.keys.maxOrNull() ?: 0
        }

    private fun handleSubmit() {
        hideKeyboard()
        val r = submitRating
        if (r <= 0) return
        val onSubmit = attr.onSubmit ?: return
        val tagCodes = currentTags.filter { selectedTagCodes.contains(it.code) }.map { it.code }
        isSubmitting = true
        (getPager() as Pager).lifecycleScope.launch {
            try {
                onSubmit(r, tagCodes, text.trim())
            } finally {
                isSubmitting = false
            }
        }
    }

    private fun capitalizeWords(value: String): String =
        value.split(" ").joinToString(" ") { w -> if (w.isEmpty()) w else w.substring(0, 1).uppercase() + w.substring(1) }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            Text {
                attr {
                    text("evaluate".tr)
                    fontSize(12f)
                    fontFamily("Poppins-SemiBold")
                    color(Color.WHITE)
                }
            }
            View {
                attr {
                    marginTop(10f)
                    height(30f)
                    flexDirectionRow()
                    alignItemsCenter()
                }
                for (i in 0 until ctx.attr.totalStars) {
                    View {
                        attr { marginRight(if (i < ctx.attr.totalStars - 1) 10f else 0f) }
                        event {
                            click {
                                ctx.hideKeyboard()
                                val next = i + 1
                                if (ctx.rating != next) {
                                    ctx.selectedTagCodes.clear()
                                    ctx.selectedVersion++
                                    ctx.rating = next
                                    ctx.attr.onRatingChanged?.invoke(next)
                                }
                            }
                        }
                        Image {
                            attr {
                                size(24f, 24f)
                                resizeContain()
                                src(ImageUri.commonAssets(if (i < ctx.rating) AssetsImage.starFilled else AssetsImage.starOutline))
                            }
                        }
                    }
                }
            }
            TLAnimatedCard {
                attr { visible = ctx.rating > 0 }
                content {
                    vbind({ "${ctx.rating}_${ctx.tagsVersion}" }) {
                        val list = ctx.currentTags
                        if (list.isNotEmpty()) {
                            View {
                                attr {
                                    marginTop(10f)
                                    flexDirectionRow()
                                    flexWrapWrap()
                                }
                                list.forEach { tag ->
                                    View {
                                        attr {
                                            height(32f)
                                            margin(0f, 0f, 10f, 10f)
                                            padding(0f, 10f, 0f, 10f)
                                            borderRadius(100f)
                                            allCenter()
                                            val sel = ctx.selectedVersion >= 0 && ctx.selectedTagCodes.contains(tag.code)
                                            backgroundColor(if (sel) c(0xCCC685FFL) else c(0x1AFFFFFFL))
                                        }
                                        event {
                                            click {
                                                ctx.hideKeyboard()
                                                if (ctx.selectedTagCodes.contains(tag.code)) {
                                                    ctx.selectedTagCodes.remove(tag.code)
                                                } else {
                                                    ctx.selectedTagCodes.add(tag.code)
                                                }
                                                ctx.selectedVersion++
                                            }
                                        }
                                        Text {
                                            attr {
                                                text(ctx.capitalizeWords(tag.label))
                                                fontSize(10f)
                                                fontFamily("Poppins-SemiBold")
                                                color(c(0xCCFFFFFFL))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    View {
                        attr {
                            marginTop(if (ctx.currentTags.isEmpty()) 10f else 0f)
                            height(180f)
                            padding(12f, 10f, 12f, 10f)
                            backgroundColor(c(0x1AFFFFFFL))
                            borderRadius(12f)
                        }
                        SDTextArea {
                            ref { ctx.areaRef = it }
                            attr {
                                flex(1f)
                                areaHeight = 140f
                                radius = 0f
                                contentPadding = 0f
                                fillColor = Color.TRANSPARENT
                                fontSize = 12f
                                hintColor = c(0x66FFFFFFL)
                                maxLength = 2000
                                showCounter = false
                                placeholder = "feedback_hint".tr
                                text = ctx.text
                            }
                            event {
                                onChanged { ctx.text = it }
                                onFocus { ctx.attr.onInputFocus?.invoke() }
                                onKeyboardHeight { ctx.attr.onKeyboardHeight?.invoke(it) }
                            }
                        }
                        Text {
                            attr {
                                alignSelfFlexEnd()
                                text("${ctx.text.length}/2000")
                                fontSize(12f)
                                lineHeight(14f)
                                fontFamily("Poppins-Medium")
                                color(c(0x66FFFFFFL))
                            }
                        }
                    }
                    View {
                        attr {
                            marginTop(10f)
                            height(42f)
                            borderRadius(100f)
                            flexDirectionRow()
                            allCenter()
                            if (ctx.canSubmit) {
                                backgroundLinearGradient(
                                    Direction.TO_RIGHT,
                                    ColorStop(SDColors.gradientStart, 0f),
                                    ColorStop(SDColors.gradientEnd, 1f),
                                )
                            } else {
                                backgroundLinearGradient(
                                    Direction.TO_RIGHT,
                                    ColorStop(c(0x33FFFFFFL), 0f),
                                    ColorStop(c(0x33FFFFFFL), 1f),
                                )
                            }
                        }
                        event {
                            click {
                                if (ctx.canSubmit && !ctx.isSubmitting) ctx.handleSubmit()
                            }
                        }
                        vif({ ctx.isSubmitting }) {
                            View {
                                attr {
                                    flexDirectionRow()
                                    alignItemsCenter()
                                }
                                SDLoading { attr { size = 16f } }
                                View { attr { width(10f) } }
                            }
                        }
                        Text {
                            attr {
                                text("feedback".tr)
                                fontSize(12f)
                                fontFamily("Poppins-SemiBold")
                                color(if (ctx.canSubmit) Color.WHITE else c(0x66FFFFFFL))
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.TLFeedbackCard(init: TLFeedbackCardView.() -> Unit) {
    addChild(TLFeedbackCardView(), init)
}

// ============================================================================
// 列表内联视频预览卡（InlinePreviewVideoCard）
// ============================================================================

internal class TLInlineVideoAttr : ComposeAttr() {
    var url: String = ""
    var coverUrl: String = ""
    var cardWidth: Float = 0f
    var cardHeight: Float = 0f
    var radius: Float = 12f
    /** 所在页面可见（Kuikly 没有 VisibilityDetector：页面在前台即视为可见） */
    var active: Boolean by observable(true)
    /** 在滚动视口中可见比例 ≥ 85%（Dart VisibilityDetector，初始不可见）；由页面在滚动 / 布局变化时赋值 */
    var scrollVisible: Boolean by observable(false)
    /** 布局变化（含首次布局）时回调，页面据此计算可见比例 */
    var onLayout: ((TLInlineVideoView) -> Unit)? = null
    /** 视图销毁时回调，页面移出登记 */
    var onDetach: ((TLInlineVideoView) -> Unit)? = null
    /** 打开全屏：传入当前播放位置（ms），全屏关闭后调用 done 恢复内联播放 */
    var onOpenFullscreen: ((positionMs: Long, done: () -> Unit) -> Unit)? = null
}

/**
 * 可见时才挂载解码器，打开全屏弹窗前主动卸载。出声播放器接入 [AppAudioOutputCoordinator]，
 * 避免与音乐播放器同时出声。
 */
internal class TLInlineVideoView : ComposeView<TLInlineVideoAttr, ComposeEvent>() {
    private var suspendedForDialog by observable(false)
    private var positionMs = 0L
    private var playing = false
    private var videoRef: ViewRef<SDVideoView>? = null
    private var detach: (() -> Unit)? = null
    private var claimed = false

    override fun createAttr(): TLInlineVideoAttr = TLInlineVideoAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    private var measureRef: ViewRef<DivView>? = null

    /** 卡片在页面里的占位视图（用于计算滚动可见比例）。 */
    val measureView: DivView? get() = measureRef?.view

    fun updateScrollVisible(visible: Boolean) {
        if (attr.scrollVisible != visible) attr.scrollVisible = visible
    }

    private val shouldMountPlayer: Boolean get() = attr.active && attr.scrollVisible && !suspendedForDialog && attr.url.isNotEmpty()

    override fun created() {
        super.created()
        val owner = this
        detach = AppAudioOutputCoordinator.attach(
            owner,
            isPlaying = { playing },
            isAudible = { true },
            pauseForHandoff = {
                playing = false
                videoRef?.view?.pause()
            },
        )
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        bindValueChange({ shouldMountPlayer }) { v ->
            if (!(v as Boolean)) {
                playing = false
                claimed = false
                AppAudioOutputCoordinator.release(this)
            }
        }
    }

    override fun viewDestroyed() {
        unbindAllValueChange()
        attr.onDetach?.invoke(this)
        detach?.invoke()
        detach = null
        super.viewDestroyed()
    }

    /** 播放器就绪后先申请出声再播放。 */
    private fun onReady() {
        if (claimed) return
        claimed = true
        (getPager() as Pager).lifecycleScope.launch {
            val claim = AppAudioOutputCoordinator.claim(this@TLInlineVideoView)
            if (claim < 0 || !shouldMountPlayer) return@launch
            playing = true
            videoRef?.view?.play()
        }
    }

    private fun onTap() {
        hideKeyboard()
        val pos = positionMs
        // 先卸载播放器（释放解码器）再打开弹窗
        suspendedForDialog = true
        val open = attr.onOpenFullscreen
        setTimeout(pagerId, 0) {
            if (open == null) {
                suspendedForDialog = false
            } else {
                open(pos) { suspendedForDialog = false }
            }
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val a = ctx.attr
        return {
            attr {
                size(a.cardWidth, a.cardHeight)
                borderRadius(a.radius)
                overflow(true)
                backgroundColor(Color.BLACK)
            }
            event { click { ctx.onTap() } }
            // 量位置用（ComposeView 根节点没有 layoutFrameDidChange）
            View {
                ref { ctx.measureRef = it }
                attr {
                    absolutePositionAllZero()
                    touchEnable(false)
                }
                event { layoutFrameDidChange { a.onLayout?.invoke(ctx) } }
            }
            if (a.coverUrl.isNotEmpty()) {
                SDImage {
                    attr {
                        absolutePositionAllZero()
                        url = a.coverUrl
                    }
                }
            }
            vif({ ctx.shouldMountPlayer }) {
                SDVideo {
                    ref { ctx.videoRef = it }
                    attr {
                        absolutePositionAllZero()
                        src(a.url)
                        autoPlay(false)
                        loop(true)
                        fitContain()
                        touchEnable(false)
                    }
                    event {
                        onState { st ->
                            if (st.state == SDVideoState.READY) ctx.onReady()
                            if (st.state == SDVideoState.PAUSED || st.state == SDVideoState.COMPLETED) ctx.playing = st.state == SDVideoState.COMPLETED
                            if (st.state == SDVideoState.PLAYING) ctx.playing = true
                        }
                        onProgress { p -> ctx.positionMs = p.positionMs }
                    }
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.TLInlineVideo(init: TLInlineVideoView.() -> Unit) {
    addChild(TLInlineVideoView(), init)
}
