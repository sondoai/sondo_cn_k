package com.sondo.app.features.think_line_mv

import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.constants.c
import com.sondo.app.core.constants.whiteA
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.i18n.trParams
import com.sondo.app.data.models.MvThinkCard
import com.sondo.app.data.models.MvThinkCardStatus
import com.sondo.app.data.models.MvThinkCardType
import com.sondo.app.features.think_line_mv.widgets.TLAnimatedCard
import com.sondo.app.features.think_line_mv.widgets.TLFeedbackCard
import com.sondo.app.features.think_line_mv.widgets.TLFeedbackSeed
import com.sondo.app.features.think_line_mv.widgets.TLInlineVideo
import com.sondo.app.features.think_line_mv.widgets.TLInlineVideoView
import com.sondo.app.ui.video.SDInlinePreviewVideoCardAttr
import com.sondo.app.ui.video.inlineVisibleFraction
import com.sondo.app.features.think_line_mv.widgets.TLSingerPager
import com.sondo.app.features.think_line_mv.widgets.kTlCardBg
import com.sondo.app.features.think_line_mv.widgets.kTlErrorText
import com.sondo.app.features.think_line_mv.widgets.kTlWhite30
import com.sondo.app.features.think_line_mv.widgets.kTlWhite60
import com.sondo.app.features.think_line_mv.widgets.tlImageFailedPlace
import com.sondo.app.features.think_line_mv.widgets.tlRetryBody
import com.sondo.app.features.think_line_mv.widgets.tlRetryCard
import com.sondo.app.features.think_line_mv.widgets.tlThumbnailRow
import com.sondo.app.nativeapi.SDAppModule
import com.sondo.app.nativeapi.SDLottie
import com.sondo.app.ui.atoms.SDAssetImage
import com.sondo.app.ui.atoms.SDDashedLine
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDLoading
import com.sondo.app.ui.SDFit
import com.sondo.app.ui.input.hideKeyboard
import com.sondo.app.ui.nav.SDNavBar
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.base.event.layoutFrameDidChange
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.directives.velseif
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.ScrollerView
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * MV 生成信息流（深度思考）页（Dart: think_line_mv/presentation/pages/think_line/think_line_page.dart）。
 *
 * 参数（api_notes / Dart `ThinkLineMvArgs`）：`{id: Long, type?: "create"|"push"|""}`。
 * `type == "create"`（创建完成后进入）时不触发「全部已读」。无返回结果。
 * 分镜失败页（[Routes.retryShots]）Recreate 受理后广播 [Events.MV_SHOT_RETRIED] `{mvId}`，本页据此恢复轮询。
 */
@Page(Routes.thinkLineMv)
internal class ThinkLineMvPage : BasePager() {

    val controller = ThinkLineMvController(this)

    // —— 展示态（由 controller.update() 写入）—— //
    val stepRows by observableList<TLStepVm>()
    var needPoint by observable(0)
    var imageComp by observable("")
    var needsPreviewUnlock by observable(false)
    var isDeletable by observable(false)
    var showUnlockButton by observable(false)
    var showPayButton by observable(false)
    var isPayLoading by observable(false)
    var isRetryLoading by observable(false)
    var feedbackSeed by observable(TLFeedbackSeed())
    var pageVisible by observable(false)
    var keyboardPad by observable(0f)

    // —— 滚动度量 —— //
    var scrollOffsetY = 0f
    private var reportedContentHeight = 0f
    /**
     * 内容高度优先取 Kuikly 侧布局结果（contentView.frame，本帧布局后即为最新）；原生回调的高度会滞后，
     * 与它取 max 会在评价卡退场 / 键盘垫高归零后保留旧的更大值，滚到底时越界。
     */
    val scrollContentHeight: Float
        get() {
            val laidOut = scrollerRef?.view?.contentView?.frame?.height ?: 0f
            return if (laidOut > 0f) laidOut else reportedContentHeight
        }
    val scrollViewHeight: Float get() = scrollerRef?.view?.frame?.height ?: 0f
    private var scrollerRef: ViewRef<ScrollerView<*, *>>? = null
    private var readyCalled = false
    /** 已挂载的内联 MV 预览卡（Dart InlinePreviewVideoCard 的 VisibilityDetector：可见 ≥85% 才播放） */
    private val inlineVideos = LinkedHashSet<TLInlineVideoView>()

    override fun created() {
        super.created()
        on(Events.MV_SHOT_RETRIED) { data ->
            if (data.optLong("mvId", 0L) == controller.taskId) controller.resumePollingAfterShotRetry()
        }
        controller.onInit()
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        pageVisible = true
        if (!readyCalled) {
            readyCalled = true
            controller.onReady()
        }
    }

    override fun pageDidDisappear() {
        super.pageDidDisappear()
        pageVisible = false
    }

    override fun pageWillDestroy() {
        controller.onClose()
        super.pageWillDestroy()
    }

    fun setKeepScreenOn(on: Boolean) {
        try {
            acquireModule<SDAppModule>(SDAppModule.MODULE_NAME).setKeepScreenOn(on)
        } catch (e: Throwable) {
        }
    }

    fun onInlineVideoLayout(v: TLInlineVideoView) {
        inlineVideos.add(v)
        updateInlineVideoVisibility(v)
    }

    fun onInlineVideoDetach(v: TLInlineVideoView) {
        inlineVideos.remove(v)
    }

    private fun refreshInlineVideoVisibility() {
        if (inlineVideos.isEmpty()) return
        inlineVideos.toList().forEach { updateInlineVideoVisibility(it) }
    }

    private fun updateInlineVideoVisibility(v: TLInlineVideoView) {
        val content = scrollerRef?.view?.contentView ?: return
        val viewport = scrollViewHeight
        if (viewport <= 0f) return
        val m = v.measureView ?: return
        val f = m.convertFrame(m.frame, content)
        if (f.height <= 0f) return
        v.updateScrollVisible(
            inlineVisibleFraction(f.y, f.height, scrollOffsetY, viewport) >= SDInlinePreviewVideoCardAttr.VISIBILITY_THRESHOLD,
        )
    }

    fun setScrollOffset(y: Float, animated: Boolean) {
        scrollerRef?.view?.setContentOffset(0f, y, animated)
    }

    /** 卡片可用宽度：页面左右 20 + 图标 16 + 间距 12。 */
    val cardWidth: Float get() = screenWidth - 68f

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            attr { flex(1f) }
            event { click { ctx.hideKeyboard() } }
            Image {
                attr {
                    absolutePosition(top = 0f, left = 0f)
                    size(ctx.screenWidth, ctx.screenWidth * 948f / 720f)
                    src(ImageUri.commonAssets(AssetsImage.mainBg))
                    resizeCover()
                }
            }
            SDNavBar {
                attr {
                    title = "deep_thinking".tr
                    actionsPaddingRight = 0f
                    actions = {
                        View {
                            attr { padding(10f, 10f, 10f, 16f) }
                            event { click { ctx.controller.openDeleteDialog() } }
                            SDAssetImage(AssetsImage.deleteMv, 20f, tint = Color.WHITE)
                        }
                    }
                }
            }
            // Agent 头部
            View {
                attr {
                    padding(16f, 10f, 16f, 10f)
                    flexDirectionRow()
                    alignItemsCenter()
                }
                SDLottie {
                    attr {
                        size(28f, 28f)
                        src("lottie/HBlJYYr2Zn.json")
                    }
                }
                Text {
                    attr {
                        marginLeft(6f)
                        text("sondo_ai_agent".tr)
                        fontSize(18f)
                        lineHeight(18f * 1.3f)
                        fontFamily("Poppins")
                        color(Color.WHITE)
                        lines(1)
                    }
                }
            }
            Scroller {
                ref { ctx.scrollerRef = it }
                attr {
                    flex(1f)
                    showScrollerIndicator(false)
                    bouncesEnable(false)
                }
                event {
                    scroll { p ->
                        ctx.scrollOffsetY = p.offsetY
                        ctx.reportedContentHeight = p.contentHeight
                        ctx.refreshInlineVideoVisibility()
                    }
                    dragBegin { ctx.controller.onScrollPointerDown() }
                    dragEnd { ctx.controller.onScrollPointerUp() }
                    contentSizeChanged { _, h ->
                        ctx.reportedContentHeight = h
                        ctx.refreshInlineVideoVisibility()
                    }
                    layoutFrameDidChange { ctx.refreshInlineVideoVisibility() }
                }
                View {
                    attr {
                        padding(0f, 20f, 20f, 20f)
                    }
                    vfor({ ctx.stepRows }) { vm ->
                        View { stepItem(ctx, vm) }
                    }
                }
                // 评价输入框键盘避让
                View { attr { height(ctx.keyboardPad) } }
            }
            // 底部按钮
            vif({ ctx.showUnlockButton }) {
                bottomButton(ctx, isUnlock = true)
            }
            velseif({ ctx.showPayButton }) {
                bottomButton(ctx, isUnlock = false)
            }
            vif({ !ctx.showUnlockButton && !ctx.showPayButton }) {
                View { attr { height(ctx.safeBottom) } }
            }
        }
    }

    /** 评价输入框弹出键盘：底部垫出键盘高度并贴底。 */
    fun onFeedbackKeyboard(height: Float) {
        val h = if (height < 0f) 0f else height
        if (h == keyboardPad) return
        keyboardPad = h
        if (h > 0f) postDelayed(120) { controller.scrollToBottom(force = true) }
    }
}

// ============================================================================
// 步骤项
// ============================================================================

private fun ViewContainer<*, *>.stepItem(ctx: ThinkLineMvPage, vm: TLStepVm) {
    View {
        attr {
            flexDirectionRow()
        }
        // 左：图标
        View {
            attr {
                width(16f)
                alignItemsCenter()
            }
            View {
                attr {
                    padding(2f, 0f, 2f, 0f)
                }
                stepIcon(vm)
            }
        }
        // 连接下一步的虚线：相对整行绝对定位（图标区 20 + 间距 4 起，到行底上方 4 止）。
        // Dart 用 IntrinsicHeight + Expanded 撑满；Kuikly 里左列不会被拉伸到行高，flex(1f) 的虚线容器高度为 0，虚线不显示
        vif({ !vm.isLast }) {
            View {
                attr {
                    absolutePosition(top = 24f, bottom = 4f, left = 0f)
                    width(16f)
                    flexDirectionRow()
                    justifyContentCenter()
                    touchEnable(false)
                }
                SDDashedLine(horizontal = false, dashLength = 4f, dashGap = 3f, color = kTlWhite30, strokeWidth = 1.5f)
            }
        }
        // 右：标题 / 描述 / 卡片
        View {
            attr {
                flex(1f)
                marginLeft(12f)
                paddingBottom(if (vm.isLast) 0f else 30f)
            }
            Text {
                attr {
                    text(vm.title)
                    fontSize(14f)
                    fontFamily("Poppins-SemiBold")
                    color(if (vm.titleFailed) kTlErrorText else Color.WHITE)
                    lines(2)
                    textOverFlowTail()
                }
            }
            vif({ vm.durationSec > 0 && vm.title.isNotEmpty() }) {
                Text {
                    attr {
                        text("about_need_minutes".trParams("time" to "${vm.durationSec}"))
                        fontSize(12f)
                        fontFamily("Poppins-Medium")
                        color(kTlWhite60)
                    }
                }
            }
            vif({ vm.desc.isNotEmpty() }) {
                Text {
                    attr {
                        marginTop(8f)
                        text(vm.desc)
                        fontSize(12f)
                        lineHeight(18f)
                        fontFamily("Poppins-Medium")
                        color(kTlWhite60)
                    }
                }
            }
            vif({ vm.showCards }) {
                View {
                    attr { marginTop(10f) }
                    vfor({ vm.cards }) { cvm ->
                        View { stepCard(ctx, vm, cvm) }
                    }
                    vif({ vm.showRetry }) {
                        retryCardWrapper(ctx, vm.index)
                    }
                }
            }
            vif({ vm.noCardRetry && vm.showRetry }) {
                View {
                    attr { marginTop(10f) }
                    retryCardWrapper(ctx, vm.index)
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.stepIcon(vm: TLStepVm) {
    vbind({ vm.icon }) {
        when (vm.icon) {
            TLStepIcon.failed -> SDAssetImage(AssetsImage.thinkError, 16f)
            TLStepIcon.success -> SDAssetImage(AssetsImage.thinkSuccess1, 16f)
            TLStepIcon.generating -> View {
                attr { size(16f, 16f) }
                SDLoading { attr { size = 16f } }
            }
            TLStepIcon.none -> View { attr { size(16f, 0f) } }
        }
    }
}

private fun ViewContainer<*, *>.retryCardWrapper(ctx: ThinkLineMvPage, stepIndex: Int) {
    TLAnimatedCard {
        content {
            View {
                attr { paddingBottom(10f) }
                tlRetryCard({ ctx.isRetryLoading }) { ctx.controller.onRepairStep(stepIndex) }
            }
        }
    }
}

// ============================================================================
// 卡片分发
// ============================================================================

private fun ViewContainer<*, *>.stepCard(ctx: ThinkLineMvPage, vm: TLStepVm, cvm: TLCardVm) {
    if (cvm.card.type == MvThinkCardType.none) return
    if (cvm.animated) {
        TLAnimatedCard {
            content { cardBody(ctx, vm, cvm) }
        }
    } else {
        cardBody(ctx, vm, cvm)
    }
}

/**
 * 卡片内容。卡片类型在 [TLCardVm] 生命周期内不变（类型变了 controller 会换新的 vm），
 * 所以按类型分别决定重建粒度：
 * - 评价卡（star / feedback）不进 vbind：Dart 的 ThinkLineAnimatedCard / FeedbackCard 是 StatefulWidget，
 *   rebuild 时 State 保留，滑入动画不重播，已选星级 / 标签 / 输入也不丢；
 * - 歌手图只随 card 重建（不依赖画幅 / 解锁态）；
 * - 其余卡片随 card / 画幅 / 解锁态重建（Dart 每次 update 整体 rebuild，它们本身无状态）。
 */
private fun ViewContainer<*, *>.cardBody(ctx: ThinkLineMvPage, vm: TLStepVm, cvm: TLCardVm) {
    val stepIndex = vm.index
    when (cvm.card.type) {
        MvThinkCardType.star -> TLAnimatedCard {
            attr {
                enableExit = true
                visible = vm.starVisible
            }
            content {
                cardBox(onTap = { ctx.controller.onCardTap(stepIndex, cvm.card) }) { feedbackCard(ctx, cvm) }
            }
        }
        MvThinkCardType.feedback -> cardBox { feedbackCard(ctx, cvm) }
        MvThinkCardType.singerImage -> View {
            attr { paddingBottom(10f) }
            vbind({ cvm.card }) { singerImageCard(ctx, cvm.card) }
        }
        else -> vbind({ Triple(cvm.card, ctx.imageComp, ctx.needsPreviewUnlock) }) {
            val card = cvm.card
            when (card.type) {
                MvThinkCardType.image -> cardBox { imageCardContent(ctx, card) }
                MvThinkCardType.text -> cardBox { textCardContent(card) }
                MvThinkCardType.lyrics -> cardBox { lyricsCardContent(card) }
                MvThinkCardType.imageList, MvThinkCardType.videoList -> View {
                    attr { paddingBottom(10f) }
                    val items = card.items
                    if (!items.isNullOrEmpty()) {
                        val isVideo = card.type == MvThinkCardType.videoList
                        tlThumbnailRow(items, scrollToUnlocked = isVideo) { index ->
                            ctx.controller.showPreviewDialog(card, isVideo, index)
                        }
                    }
                }
                MvThinkCardType.mvPreview -> View {
                    attr {
                        paddingBottom(10f)
                        alignItemsFlexStart()
                    }
                    val isVertical = ctx.imageComp == "9:16"
                    TLInlineVideo {
                        attr {
                            url = card.videoUrl ?: ""
                            coverUrl = card.firstFrameImg ?: ""
                            cardWidth = if (isVertical) 168f else 272f
                            cardHeight = if (isVertical) 300f else 153f
                            radius = 12f
                            active = ctx.pageVisible
                            onLayout = { v -> ctx.onInlineVideoLayout(v) }
                            onDetach = { v -> ctx.onInlineVideoDetach(v) }
                            onOpenFullscreen = { pos, done -> ctx.controller.openMvPreviewFullscreen(card, pos, done) }
                        }
                    }
                }
                MvThinkCardType.actionRow -> cardBox(onTap = { ctx.controller.onCardTap(stepIndex, card) }) {
                    actionRowContent(card)
                }
                // 失败文案由后端 bodyText 下发，只展示不响应点击
                MvThinkCardType.error -> errorCard(ctx, card)
                MvThinkCardType.star, MvThinkCardType.feedback, MvThinkCardType.singerImage, MvThinkCardType.none -> Unit
            }
        }
    }
}

/** Dart `_buildCardBox`：底部间距 10、内边距 14、白 10% 底、圆角 14。 */
private fun ViewContainer<*, *>.cardBox(
    padding: Float = 14f,
    color: Color = kTlCardBg,
    onTap: (() -> Unit)? = null,
    content: ViewContainer<*, *>.() -> Unit,
) {
    View {
        attr {
            marginBottom(10f)
            padding(padding)
            backgroundColor(color)
            borderRadius(14f)
        }
        if (onTap != null) event { click { onTap() } }
        content()
    }
}

private fun ViewContainer<*, *>.labelText(text: String, marginBottom: Float) {
    Text {
        attr {
            marginBottom(marginBottom)
            text(text)
            fontSize(12f)
            fontFamily("Poppins-SemiBold")
            color(Color.WHITE)
        }
    }
}

private fun ViewContainer<*, *>.bodyText(text: String, color: Color = kTlWhite60) {
    Text {
        attr {
            text(text)
            fontSize(12f)
            fontFamily("Poppins-Medium")
            color(color)
        }
    }
}

private fun ViewContainer<*, *>.feedbackCard(ctx: ThinkLineMvPage, cvm: TLCardVm) {
    TLFeedbackCard {
        attr {
            totalStars = cvm.card.totalStars ?: 5
            seed = ctx.feedbackSeed
            onRatingChanged = { ctx.controller.onFeedbackRatingChanged(it) }
            onSubmit = { rating, tags, content -> ctx.controller.onFeedbackSubmit(rating, tags, content) }
            onKeyboardHeight = { ctx.onFeedbackKeyboard(it) }
        }
    }
}

private fun ViewContainer<*, *>.imageCardContent(ctx: ThinkLineMvPage, card: MvThinkCard) {
    val isVertical = ctx.imageComp == "9:16" && (card.isImageComp ?: false)
    val boxW = if (isVertical) 150f else ctx.cardWidth - 28f
    val boxH = if (isVertical) 260f else 153f
    if (card.label.isNotEmpty()) labelText(card.label, 10f)
    View {
        attr {
            height(boxH)
            allCenter()
        }
        event { click { ctx.controller.showSingleImagePreview(card) } }
        when (card.status) {
            // 锁定单图不在信息流里画锁，跟 sondo_app 一样留空。
            MvThinkCardStatus.locked -> Unit
            MvThinkCardStatus.success -> SDImage {
                attr {
                    size(boxW, boxH)
                    borderRadius(12f)
                    url = card.imageUrl ?: ""
                    fit = if (isVertical) SDFit.CONTAIN else SDFit.COVER
                }
            }
            MvThinkCardStatus.generating -> View {
                attr {
                    size(boxW, boxH)
                    backgroundColor(whiteA(0.05f))
                    borderRadius(12f)
                    allCenter()
                }
                SDLoading { }
            }
            MvThinkCardStatus.failed -> View {
                attr {
                    size(boxW, boxH)
                    backgroundColor(whiteA(0.05f))
                    borderRadius(12f)
                    allCenter()
                }
                tlImageFailedPlace(42f)
                if (card.isRetryable == true) {
                    tlRetryBody(c(0xCCF3EFF7L), UIColor.c0A0A0A) { ctx.controller.onRepairCrad(card.uniqueKey) }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.textCardContent(card: MvThinkCard) {
    if (card.label.isNotEmpty()) labelText(card.label, 4f)
    card.bodyText?.let { bodyText(it) }
}

private fun ViewContainer<*, *>.lyricsCardContent(card: MvThinkCard) {
    if (card.label.isNotEmpty()) labelText(card.label, 8f)
    val body = card.bodyText
    if (!body.isNullOrEmpty()) {
        Scroller {
            attr {
                height(120f)
                showScrollerIndicator(false)
            }
            bodyText(body)
        }
    }
}

private fun ViewContainer<*, *>.actionRowContent(card: MvThinkCard) {
    View {
        attr {
            flexDirectionRow()
            alignItemsCenter()
        }
        Text {
            attr {
                flex(1f)
                text(card.label)
                fontSize(12f)
                fontFamily("Poppins-SemiBold")
                color(Color.WHITE)
                lines(1)
                textOverFlowTail()
            }
        }
        View {
            attr {
                size(24f, 24f)
                allCenter()
            }
            SDAssetImage(AssetsImage.arrowRight, 14f, tint = kTlWhite30)
        }
    }
}

private fun ViewContainer<*, *>.errorCard(ctx: ThinkLineMvPage, card: MvThinkCard) {
    val text = card.bodyText
    if (text.isNullOrEmpty()) return
    cardBox {
        bodyText(text, kTlErrorText)
        if (card.route == "retryFailedShots" && !ctx.needsPreviewUnlock) {
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
                        borderRadius(20f)
                        allCenter()
                    }
                    event { click { ctx.controller.onErrorCardTap(card) } }
                    Text {
                        attr {
                            text("edit".tr)
                            fontSize(12f)
                            fontFamily("Poppins-SemiBold")
                            color(UIColor.cFFF80)
                        }
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.singerImageCard(ctx: ThinkLineMvPage, card: MvThinkCard) {
    val w = ctx.cardWidth
    val h = w * 9f / 16f
    val list = card.items
    if (list.isNullOrEmpty()) {
        View {
            attr {
                size(w, h)
                backgroundColor(whiteA(0.05f))
                borderRadius(12f)
                allCenter()
            }
            SDLoading { }
        }
        return
    }
    TLSingerPager {
        attr {
            label = card.label
            items = list
            pageWidth = w
            pageHeight = h
            isRetryable = card.isRetryable ?: false
            onRetry = { ctx.controller.onRepairCrad(card.uniqueKey) }
            onTap = { index -> ctx.controller.showPreviewDialog(card, false, index) }
        }
    }
}

// ============================================================================
// 底部按钮（解锁 / 继续生成）
// ============================================================================

private fun ViewContainer<*, *>.bottomButton(ctx: ThinkLineMvPage, isUnlock: Boolean) {
    View {
        attr {
            padding(8f, 16f, 16f + ctx.safeBottom, 16f)
            backgroundLinearGradient(
                Direction.TO_BOTTOM,
                ColorStop(c(0x000A0A0AL), 0f),
                ColorStop(c(0xFF0A0A0AL), 0.77f),
            )
        }
        View {
            attr {
                height(48f)
                backgroundColor(UIColor.cF3EFF7)
                borderRadius(53f)
                flexDirectionRow()
                allCenter()
            }
            event {
                click {
                    if (isUnlock) ctx.controller.unlockPreviewDirect() else ctx.controller.onPayToContinue()
                }
            }
            if (!isUnlock) {
                vif({ ctx.isPayLoading }) {
                    View {
                        attr {
                            flexDirectionRow()
                            alignItemsCenter()
                        }
                        SDLoading { attr { size = 20f; color = UIColor.c0A0A0A } }
                        View { attr { width(10f) } }
                    }
                }
            }
            Text {
                attr {
                    text(if (isUnlock) "unlock_full_video_points".trParams("point" to "${ctx.needPoint}") else "pay_to_continue".tr)
                    fontSize(16f)
                    fontFamily("Poppins-SemiBold")
                    color(UIColor.c0A0A0A)
                }
            }
        }
    }
}
