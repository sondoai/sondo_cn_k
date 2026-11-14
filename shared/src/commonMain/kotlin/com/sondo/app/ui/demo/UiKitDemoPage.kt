package com.sondo.app.ui.demo

import com.sondo.app.base.BasePager
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.http.ApiResult
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.delayMs
import com.sondo.app.data.models.PageResult
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.SDFont
import com.sondo.app.ui.SDTextAlign
import com.sondo.app.ui.SDWeight
import com.sondo.app.ui.anim.SDAnimatedBell
import com.sondo.app.ui.anim.SDBreathing
import com.sondo.app.ui.anim.SDEnterAnim
import com.sondo.app.ui.anim.SDEqualizerBars
import com.sondo.app.ui.anim.SDFadeIn
import com.sondo.app.ui.anim.SDFloatDelta
import com.sondo.app.ui.anim.SDRefreshLoading
import com.sondo.app.ui.anim.SDRotating
import com.sondo.app.ui.anim.SDScaleIn
import com.sondo.app.ui.anim.SDSlideIn
import com.sondo.app.ui.anim.SDTypingDots
import com.sondo.app.ui.atoms.HGap
import com.sondo.app.ui.atoms.SDAssetImage
import com.sondo.app.ui.atoms.SDAudioVisualizer
import com.sondo.app.ui.atoms.SDAvatar
import com.sondo.app.ui.atoms.SDBadge
import com.sondo.app.ui.atoms.SDButton
import com.sondo.app.ui.atoms.SDCheckbox
import com.sondo.app.ui.atoms.SDCoverLoadingBar
import com.sondo.app.ui.atoms.SDDashedLine
import com.sondo.app.ui.atoms.SDDivider
import com.sondo.app.ui.atoms.SDEmptyState
import com.sondo.app.ui.atoms.SDErrorState
import com.sondo.app.ui.atoms.SDExpandableText
import com.sondo.app.ui.atoms.SDFlipCounter
import com.sondo.app.ui.atoms.SDGradientButton
import com.sondo.app.ui.atoms.SDIconButton
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDLinkSpan
import com.sondo.app.ui.atoms.SDLinkText
import com.sondo.app.ui.atoms.SDLoading
import com.sondo.app.ui.atoms.SDLoadingState
import com.sondo.app.ui.atoms.SDLoadingType
import com.sondo.app.ui.atoms.SDMarqueeText
import com.sondo.app.ui.atoms.SDMusicLoading
import com.sondo.app.ui.atoms.SDOfferCountdownDigits
import com.sondo.app.ui.atoms.SDPrimaryButton
import com.sondo.app.ui.atoms.SDProgressBar
import com.sondo.app.ui.atoms.SDRadio
import com.sondo.app.ui.atoms.SDRingProgress
import com.sondo.app.ui.atoms.SDRollingDigit
import com.sondo.app.ui.atoms.SDSafeArea
import com.sondo.app.ui.atoms.SDSecondaryButton
import com.sondo.app.ui.atoms.SDSeekBar
import com.sondo.app.ui.atoms.SDShimmer
import com.sondo.app.ui.atoms.SDSlider
import com.sondo.app.ui.atoms.SDSongSkeleton
import com.sondo.app.ui.atoms.SDSongSkeletonAttr
import com.sondo.app.ui.atoms.SDSwitch
import com.sondo.app.ui.atoms.SDTag
import com.sondo.app.ui.atoms.SDText
import com.sondo.app.ui.atoms.SDUploadImage
import com.sondo.app.ui.atoms.SDUploadImageStatus
import com.sondo.app.ui.atoms.SafeBottom
import com.sondo.app.ui.atoms.SafeTop
import com.sondo.app.ui.atoms.Spacer
import com.sondo.app.ui.atoms.VGap
import com.sondo.app.ui.atoms.square
import com.sondo.app.ui.consent.SDPrivacyConsent
import com.sondo.app.ui.consent.SDPrivacyConsentView
import com.sondo.app.ui.dialog.ImagePreviewItem
import com.sondo.app.ui.dialog.SDActionItem
import com.sondo.app.ui.dialog.SDLinks
import com.sondo.app.ui.dialog.SDPointsLabel
import com.sondo.app.ui.dialog.SDPopupMenuItem
import com.sondo.app.ui.dialog.SDSheetHeader
import com.sondo.app.ui.dialog.UsageStatementType
import com.sondo.app.ui.dialog.VideoPreviewItem
import com.sondo.app.ui.dialog.closeLoading
import com.sondo.app.ui.dialog.confirm
import com.sondo.app.ui.dialog.ensurePermission
import com.sondo.app.ui.dialog.pickAction
import com.sondo.app.ui.dialog.previewVideos
import com.sondo.app.ui.dialog.showActionSheet
import com.sondo.app.ui.dialog.showAgree
import com.sondo.app.ui.dialog.showAiWatermarkStatement
import com.sondo.app.ui.dialog.showAlertDialog
import com.sondo.app.ui.dialog.showAppBottomSheet
import com.sondo.app.ui.dialog.showAppHotDialog
import com.sondo.app.ui.dialog.showCardDialog
import com.sondo.app.ui.dialog.showConfirmDialog
import com.sondo.app.ui.dialog.showGenerationCompleted
import com.sondo.app.ui.dialog.showImagePreview
import com.sondo.app.ui.dialog.showInputDialog
import com.sondo.app.ui.dialog.showLoadingDialog
import com.sondo.app.ui.dialog.showMvPreviewUnlock
import com.sondo.app.ui.dialog.showOptionPicker
import com.sondo.app.ui.dialog.showPermissionRationale
import com.sondo.app.ui.dialog.showPermissionSettingsDialog
import com.sondo.app.ui.dialog.showPlainBottomSheet
import com.sondo.app.ui.dialog.showPopupMenu
import com.sondo.app.ui.dialog.showProgressDialog
import com.sondo.app.ui.dialog.showScoreDialog
import com.sondo.app.ui.dialog.showSheetImageOption
import com.sondo.app.ui.dialog.showSuccessMessage
import com.sondo.app.ui.dialog.showTagOptions
import com.sondo.app.ui.dialog.showTopMessage
import com.sondo.app.ui.dialog.showUsageStatement
import com.sondo.app.ui.dialog.showVideoInfoDialog
import com.sondo.app.ui.dialog.showVideoPreview
import com.sondo.app.ui.dialog.showWarningMessage
import com.sondo.app.ui.input.SDCodeInput
import com.sondo.app.ui.input.SDInput
import com.sondo.app.ui.input.SDKeyboardAwareScroll
import com.sondo.app.ui.input.SDKeyboardTopInput
import com.sondo.app.ui.input.SDKeyboardTopInputController
import com.sondo.app.ui.input.SDKeyboardType
import com.sondo.app.ui.input.SDTextArea
import com.sondo.app.ui.input.SDUnfocus
import com.sondo.app.ui.input.keyboardAware
import com.sondo.app.ui.list.PagingState
import com.sondo.app.ui.list.SDGrid
import com.sondo.app.ui.list.SDGridSpec
import com.sondo.app.ui.list.SDHorizontalList
import com.sondo.app.ui.list.SDRefreshList
import com.sondo.app.ui.list.SDStaggeredGrid
import com.sondo.app.ui.list.SDSwiper
import com.sondo.app.ui.list.gridCell
import com.sondo.app.ui.list.gridContainer
import com.sondo.app.ui.nav.SDBottomBar
import com.sondo.app.ui.nav.SDNavAppBar
import com.sondo.app.ui.nav.SDNavBack
import com.sondo.app.ui.nav.SDNavBar
import com.sondo.app.ui.nav.SDPageScaffold
import com.sondo.app.ui.nav.SDSegmented
import com.sondo.app.ui.nav.SDTabBar
import com.sondo.app.ui.nav.SDTabIndicator
import com.sondo.app.ui.textStyle
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vforIndex
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.views.View

private const val DEMO_IMAGE = "https://picsum.photos/seed/sondo/400/400"
private const val DEMO_VIDEO = "https://www.w3schools.com/html/mov_bbb.mp4"

/** 小节标题。 */
private fun ViewContainer<*, *>.section(title: String) {
    SDText(title, size = 16f, fontWeight = SDWeight.W700, color = SDColors.accentPurple) {
        attr { marginTop(28f); marginBottom(12f) }
    }
}

/** 一行按钮（演示弹窗入口）。 */
private fun ViewContainer<*, *>.demoButton(label: String, onTap: () -> Unit) {
    SDSecondaryButton {
        attr {
            marginBottom(8f)
            height(40f)
            fontSize = 13f
            text = label
        }
        event { onTap { onTap() } }
    }
}

/** 横排容器。 */
private fun ViewContainer<*, *>.row(init: ViewBuilder) {
    View {
        attr {
            flexDirectionRow()
            alignItemsCenter()
            flexWrapWrap()
        }
        init()
    }
}

/**
 * UI Kit 演示页：把 `ui/` 下的每个组件至少实例化一次。
 * 一是证明它们在真实用法下能编译，二是给视觉验收一个入口（`nav.push("ui_kit_demo")`）。
 */
@Page("ui_kit_demo")
internal class UiKitDemoPage : BasePager() {
    // —— 各组件的演示状态 —— //
    private var counter by observable(1280)
    private var seconds by observable(125)
    private var loading by observable(false)
    private var switchOn by observable(true)
    private var checked by observable(false)
    private var radio by observable(0)
    private var progress by observable(0.35f)
    private var positionMs by observable(30_000f)
    private var tab by observable(0)
    private var segment by observable(0)
    private var tagIndex by observable(0)
    private var inputText by observable("")
    private var areaText by observable("")
    private var topInputText by observable("点我用键盘顶部输入条编辑")
    private var uploadStatus by observable(SDUploadImageStatus.NONE)
    private var uploadProgress by observable(0f)
    private var uploadUrl by observable("")
    private var visualizer by observable("player")
    private var showConsent by observable(false)
    private var consentBusy by observable(false)
    private var breathing by observable(true)
    private var bannerIndex by observable(0)
    private val gridItems by observableList<String>()
    private val floatDeltas by observableList<Int>()
    private val topInput = SDKeyboardTopInputController()
    private var consentRef: ViewRef<SDPrivacyConsentView>? = null

    /** 假分页：共 35 条，每页 10 条，600ms 延迟。 */
    private val paging = PagingState<String>(this, pageSize = 10, itemKey = { it }) { pageNo, pageSize ->
        delayMs(600)
        val total = 35
        val from = (pageNo - 1) * pageSize
        val list = (from until minOf(from + pageSize, total)).map { "Item #${it + 1}" }
        ApiResult.Success(PageResult(list, total))
    }

    private val waterfall = PagingState<Int>(this, pageSize = 12) { pageNo, pageSize ->
        delayMs(300)
        ApiResult.Success(PageResult((0 until pageSize).map { (pageNo - 1) * pageSize + it }, 24))
    }

    override fun created() {
        super.created()
        for (i in 1..7) gridItems.add("Tag $i")
        paging.refresh()
        waterfall.refresh()
        tick()
    }

    /** 每秒走一次：倒计时、积分、播放进度。 */
    private fun tick() {
        postDelayed(1000) {
            seconds = if (seconds > 0) seconds - 1 else 125
            positionMs = if (positionMs >= 180_000f) 0f else positionMs + 1000f
            tick()
        }
    }

    override fun interceptBack(): Boolean = showConsent

    override fun onBackPressed(): Boolean = consentRef?.view?.handleBack() ?: false

    private fun simulateUpload() {
        uploadStatus = SDUploadImageStatus.UPLOADING
        uploadProgress = 0f
        uploadUrl = ""
        launch {
            for (i in 1..5) {
                delayMs(300)
                uploadProgress = i * 20f
            }
            uploadStatus = SDUploadImageStatus.NONE
            uploadUrl = DEMO_IMAGE
        }
    }

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            SDPageScaffold {
                attr {
                    title = "UI Kit"
                    unfocusOnTap = true
                    actions = {
                        SDIconButton {
                            attr { icon = AssetsImage.share; iconSize = 20f; hitSize = 40f }
                            event { onTap { ctx.toast("share") } }
                        }
                    }
                }
                content {
                    SDKeyboardAwareScroll {
                        attr {
                            flex(1f)
                            marginBottom = 72f
                        }
                        content {
                            View {
                                attr { padding(0f, 16f, 24f, 16f) }
                                ctx.buildTextSection(this)
                                ctx.buildImageSection(this)
                                ctx.buildButtonSection(this)
                                ctx.buildControlSection(this)
                                ctx.buildStateSection(this)
                                ctx.buildInputSection(this)
                                ctx.buildNavSection(this)
                                ctx.buildListSection(this)
                                ctx.buildAnimSection(this)
                                ctx.buildDialogSection(this)
                            }
                        }
                    }
                }
                bottomBar {
                    // 放在 scaffold 的 bottomBar 里时安全区由 scaffold 负责，这里关掉
                    SDBottomBar(divider = true, safeBottom = false) {
                        SDSecondaryButton {
                            attr { flex(1f); text = "cancel".tr }
                            event { onTap { ctx.nav.pop() } }
                        }
                        HGap(12f)
                        SDPrimaryButton {
                            attr { flex(1f); text = "confirm".tr; isLoading = ctx.loading }
                            event { onTap { ctx.loading = !ctx.loading } }
                        }
                    }
                }
                overlay {
                    SDKeyboardTopInput { attr { controller = ctx.topInput } }
                    vif({ ctx.showConsent }) {
                        SDPrivacyConsent {
                            ref { ctx.consentRef = it }
                            attr { isBusy = ctx.consentBusy }
                            event {
                                onAgree {
                                    ctx.consentBusy = true
                                    ctx.postDelayed(1200) {
                                        ctx.consentBusy = false
                                        ctx.showConsent = false
                                        ctx.refreshBackIntercept()
                                    }
                                }
                                onDisagree {
                                    ctx.showConsent = false
                                    ctx.refreshBackIntercept()
                                }
                                onOpenLink { url, title -> ctx.toast("$title\n$url") }
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 文本

    private fun buildTextSection(c: ViewContainer<*, *>) {
        val ctx = this
        with(c) {
            section("Text")
            SDText("SDText 静态写法：默认白色 14 单行")
            SDText(
                "参数写法：size / fontWeight / height / maxLines / textAlign，超出两行省略……" +
                    "这是一段很长很长很长很长很长很长很长很长很长很长很长很长的文字。",
                size = 13f, color = UIColor.cFFF60, height = 1.4f, maxLines = 2,
                textAlign = SDTextAlign.START, fontFamily = SDFont.poppins, ellipsis = true,
            )
            SDText {
                attr {
                    marginTop(6f)
                    text("闭包写法（响应式）：积分 ${ctx.counter}")
                    textStyle(size = 15f, weight = SDWeight.W600, color = Color.WHITE)
                }
            }
            SDLinkText(
                spans = listOf(
                    SDLinkSpan("带链接的富文本："),
                    SDLinkSpan("《用户协议》") { ctx.toast("terms") },
                    SDLinkSpan(" 与 "),
                    SDLinkSpan("《隐私政策》") { ctx.toast("privacy") },
                ),
                color = UIColor.cFFF60,
                linkColor = SDColors.brandPurple,
                attrs = { marginTop(6f) },
            )
            row {
                SDMarqueeText {
                    attr {
                        width(160f)
                        marginTop(8f)
                        text = "跑马灯：这是一首名字特别特别特别长的歌曲标题 Very Long Song Title"
                        fontWeight = SDWeight.W600
                    }
                }
                HGap(12f)
                SDMarqueeText {
                    attr { width(100f); marginTop(8f); text = "放得下不滚动" }
                }
            }
            SDExpandableText {
                attr {
                    marginTop(8f)
                    maxLines = 2
                    expandText = "more".tr
                    text = "可展开文本：" + "折叠时最多两行，被截断才显示展开入口。".repeat(6)
                }
            }
            row {
                SDFlipCounter { attr { marginTop(8f); value = ctx.counter } }
                HGap(12f)
                SDRollingDigit {
                    attr { value = (ctx.seconds % 10).toString(); boxWidth = 14f; boxHeight = 24f; fontSize = 18f }
                }
                HGap(12f)
                SDSecondaryButton {
                    attr { width(88f); height(32f); fontSize = 12f; text = "+37" }
                    event {
                        onTap {
                            ctx.counter += 37
                            ctx.floatDeltas.add(37)
                        }
                    }
                }
                // 飘起的 +N
                View {
                    attr { size(40f, 32f) }
                    vfor({ ctx.floatDeltas }) { delta ->
                        SDFloatDelta {
                            attr { absolutePosition(top = 8f, left = 8f); this.delta = delta }
                            event { onFinished { ctx.floatDeltas.remove(delta) } }
                        }
                    }
                }
            }
            SDOfferCountdownDigits { attr { marginTop(12f); remainingSeconds = ctx.seconds } }
        }
    }

    // ------------------------------------------------------------------ 图片

    private fun buildImageSection(c: ViewContainer<*, *>) {
        val ctx = this
        with(c) {
            section("Image")
            row {
                SDImage {
                    attr { size(72f, 72f); borderRadius(8f); url = DEMO_IMAGE }
                    event { click { ctx.showImagePreview(listOf(DEMO_IMAGE, DEMO_IMAGE)) } }
                }
                HGap(12f)
                // 空地址：一直显示占位骨架
                SDImage { attr { size(72f, 72f); borderRadius(8f); url = "" } }
                HGap(12f)
                SDAssetImage(AssetsImage.libraryEmpty, 48f)
                HGap(12f)
                SDAvatar { attr { avatar = DEMO_IMAGE; size = 48f; borderWidth = 1f } }
                HGap(12f)
                SDAvatar { attr { size = 32f; fallbackAsset = AssetsImage.close } }
            }
            VGap(12f)
            row {
                SDUploadImage {
                    attr {
                        size(80f, 80f)
                        url = ctx.uploadUrl
                        status = ctx.uploadStatus
                        progress = ctx.uploadProgress
                    }
                    event {
                        onEmptyTap { ctx.simulateUpload() }
                        onClear { ctx.uploadUrl = ""; ctx.uploadStatus = SDUploadImageStatus.NONE }
                        onRetry { ctx.simulateUpload() }
                        onImageTap { ctx.showImagePreview(listOf(ctx.uploadUrl)) }
                    }
                }
                HGap(12f)
                SDUploadImage {
                    attr { size(80f, 80f); status = SDUploadImageStatus.FAILED }
                    event { onRetry { ctx.toast("retry".tr) } }
                }
                HGap(12f)
                SDAudioVisualizer {
                    attr { status = ctx.visualizer; size = 40f }
                    event {
                        click {
                            ctx.visualizer = when (ctx.visualizer) {
                                "player" -> "loading"
                                "loading" -> "pause"
                                else -> "player"
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 按钮

    private fun buildButtonSection(c: ViewContainer<*, *>) {
        val ctx = this
        with(c) {
            section("Button")
            SDButton {
                attr {
                    padding(0f, 0f, 8f, 0f)
                    text = "SDButton（ButtonWidget）"
                    isLoading = ctx.loading
                }
                event { onTap { ctx.loading = true; ctx.postDelayed(1500) { ctx.loading = false } } }
            }
            SDGradientButton {
                attr {
                    padding(0f, 0f, 8f, 0f)
                    text = "SDGradientButton（GradientButton）"
                    disabled = ctx.loading
                }
                event {
                    onTap { ctx.toast("tap") }
                    onDisabledTap { ctx.toast("disabled") }
                }
            }
            View {
                attr { flexDirectionRow() }
                SDSecondaryButton {
                    attr { flex(1f); height(40f); fontSize = 14f; text = "cancel".tr }
                }
                HGap(12f)
                SDPrimaryButton {
                    attr { flex(1f); height(40f); fontSize = 14f; text = "confirm".tr; iconAsset = AssetsImage.share }
                }
            }
            VGap(12f)
            row {
                SDIconButton {
                    attr { icon = AssetsImage.close; iconSize = 22f; hitSize = 36f; bgColor = Color(0, 0, 0, 0.5f) }
                    event { onTap { ctx.toast("close") } }
                }
                HGap(12f)
                SDNavBack(onPressed = { ctx.toast("back") })
                HGap(12f)
                View {
                    SDTag { attr { text = "PRO"; selected = true; tagHeight = 24f; radius = 12f } }
                    SDBadge { attr { absolutePosition(top = -6f, right = -10f); count = 128 } }
                }
                HGap(24f)
                SDBadge { attr { dot = true } }
                HGap(12f)
                SDBadge { attr { text = "NEW"; bgColor = SDColors.brandPurple } }
            }
            VGap(12f)
            SDHorizontalList(height = 32f) {
                vforIndex({ ctx.gridItems }) { item, index, _ ->
                    SDTag {
                        attr {
                            marginRight(8f)
                            tagHeight = 32f
                            radius = 29f
                            text = item
                            selected = ctx.tagIndex == index
                            selectedBgColor = SDColors.white20
                            selectedTextColor = Color.WHITE
                            bgColor = SDColors.white5
                            textColor = SDColors.white40
                        }
                        event { onTap { ctx.tagIndex = index } }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 控件

    private fun buildControlSection(c: ViewContainer<*, *>) {
        val ctx = this
        with(c) {
            section("Controls")
            row {
                SDSwitch {
                    attr { value = ctx.switchOn }
                    event { onChanged { ctx.switchOn = it } }
                }
                SDSwitch {
                    attr { value = ctx.switchOn; compact = true; scale = 0.8f }
                    event { onChanged { ctx.switchOn = it } }
                }
                HGap(16f)
                SDCheckbox {
                    attr { checked = ctx.checked; text = "圆形" }
                    event { onChanged { ctx.checked = it } }
                }
                SDCheckbox {
                    attr { square(); checked = ctx.checked; text = "方形" }
                    event { onChanged { ctx.checked = it } }
                }
                SDRadio {
                    attr { checked = ctx.radio == 0; text = "A" }
                    event { onChanged { ctx.radio = 0 } }
                }
                SDRadio {
                    attr { checked = ctx.radio == 1; text = "B" }
                    event { onChanged { ctx.radio = 1 } }
                }
            }
            VGap(12f)
            SDProgressBar { attr { progress = ctx.progress } }
            VGap(8f)
            SDProgressBar {
                attr {
                    barHeight = 6f
                    trackColor = SDColors.white10
                    gradientColors = listOf(SDColors.accentPurple, SDColors.brandPurple)
                    progress = ctx.progress
                }
            }
            VGap(12f)
            row {
                SDRingProgress { attr { size(36f, 36f); strokeWidth = 3f; progress = ctx.progress } }
                HGap(12f)
                SDSecondaryButton {
                    attr { width(88f); height(32f); fontSize = 12f; text = "+10%" }
                    event { onTap { ctx.progress = if (ctx.progress >= 1f) 0f else ctx.progress + 0.1f } }
                }
                Spacer()
                SDText { attr { text("${(ctx.progress * 100).toInt()}%") } }
            }
            VGap(8f)
            SDSeekBar {
                attr {
                    duration = 180_000f
                    position = ctx.positionMs
                    buffered = ctx.positionMs + 20_000f
                    thumbRadius = 4f
                }
                event { onSeekEnd { ctx.positionMs = it } }
            }
            SDSlider {
                attr { duration = 1f; position = ctx.progress }
                event { onSeek { ctx.progress = it }; onSeekEnd { ctx.progress = it } }
            }
            SDCoverLoadingBar { }
            VGap(12f)
            SDDivider()
            VGap(8f)
            View {
                attr { flexDirectionRow(); height(20f); alignItemsCenter() }
                SDText("左")
                SDDivider(vertical = true, length = 12f, indent = 0f, color = UIColor.cFFF20, thickness = 1f)
                HGap(8f)
                SDText("右")
            }
            VGap(8f)
            SDDashedLine(color = UIColor.cFFF40, dashLength = 6f, dashGap = 4f)
        }
    }

    // ------------------------------------------------------------------ 加载 / 状态

    private fun buildStateSection(c: ViewContainer<*, *>) {
        with(c) {
            section("Loading / State")
            row {
                SDLoading { attr { size = 28f } }
                HGap(16f)
                SDLoading { attr { size = 40f; type = SDLoadingType.LOTTIE } }
                HGap(16f)
                SDMusicLoading("loading".tr, size = 48f)
                HGap(16f)
                SDShimmer { attr { size(80f, 40f); radius = 8f } }
            }
            VGap(12f)
            SDSongSkeleton { attr { type = SDSongSkeletonAttr.TYPE_ITEM } }
            VGap(12f)
            row {
                SDSongSkeleton { attr { type = SDSongSkeletonAttr.TYPE_CARD } }
                SDSongSkeleton { attr { type = SDSongSkeletonAttr.TYPE_MORE } }
            }
            View {
                attr { height(180f) }
                SDEmptyState {
                    attr { image = AssetsImage.libraryEmpty; buttonText = "retry".tr }
                }
            }
            View {
                attr { height(120f) }
                SDErrorState(onRetry = { })
            }
            View {
                attr { height(100f) }
                SDLoadingState(lottie = false)
            }
        }
    }

    // ------------------------------------------------------------------ 输入

    private fun buildInputSection(c: ViewContainer<*, *>) {
        val ctx = this
        with(c) {
            section("Input")
            SDInput {
                attr {
                    height(44f)
                    borderRadius = 16f
                    fillColor = UIColor.cFFF10
                    hintColor = UIColor.cFFF40
                    placeholder = "enter_song_name".tr
                    maxLength = 30
                    showCounter = true
                    text = ctx.inputText
                }
                event {
                    onChanged { ctx.inputText = it }
                    onSubmitted { ctx.toast(it) }
                }
            }
            VGap(8f)
            SDInput {
                attr {
                    height(44f)
                    borderRadius = 8f
                    borderColor = UIColor.cFFF20
                    keyboardType = SDKeyboardType.NUMBER
                    // Dart InputWidget.disabled 的反向语义：false = 不可编辑
                    disabled = false
                    text = "disabled = false → 不可编辑"
                }
            }
            VGap(8f)
            SDTextArea {
                attr {
                    areaHeight = 90f
                    maxLength = 200
                    placeholder = "please_enter_description".tr
                    text = ctx.areaText
                }
                event { onChanged { ctx.areaText = it } }
            }
            VGap(12f)
            SDCodeInput {
                attr { length = 6; autofocus = false; boxWidth = 40f }
                event { onCompleted { ctx.toast(it) } }
            }
            VGap(12f)
            // 原生 Input 接入键盘感知滚动
            Input {
                val aware = keyboardAware()
                attr {
                    height(40f)
                    fontSize(14f)
                    color(Color.WHITE)
                    placeholder("原生 Input + keyboardAware()")
                    placeholderColor(UIColor.cFFF40)
                }
                event {
                    inputFocus { aware.onFocus() }
                    inputBlur { aware.onBlur() }
                    keyboardHeightChange { aware.onKeyboardHeight(it.height) }
                }
            }
            VGap(8f)
            SDText {
                attr {
                    text(ctx.topInputText)
                    color(UIColor.cFFF60)
                    lines(2)
                }
                event {
                    click {
                        ctx.topInput.show(
                            text = ctx.topInputText,
                            hintText = "please_enter_prompt".tr,
                            maxLength = 60,
                            onDone = { ctx.topInputText = it },
                            onCancel = { ctx.topInputText = it },
                        )
                    }
                }
            }
            View {
                attr { marginTop(8f); height(32f) }
                SDUnfocus(onTap = { ctx.toast("unfocus") }) {
                    attr { backgroundColor(UIColor.cFFF10); borderRadius(8f); allCenter() }
                    SDText("SDUnfocus：点这里收起键盘", size = 12f)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 导航

    private fun buildNavSection(c: ViewContainer<*, *>) {
        val ctx = this
        with(c) {
            section("Navigation")
            SDNavBar {
                attr {
                    title = "SDNavBar"
                    withStatusBar = false
                    bgColor = UIColor.cFFF10
                    divider = true
                    actions = { SDText("more".tr, size = 13f) { attr { marginRight(12f) } } }
                }
                event {
                    onBack { ctx.toast("back") }
                    onDoubleTapTitle { ctx.toast("double tap") }
                }
            }
            VGap(8f)
            SDNavAppBar {
                attr { title = "titleLeft"; titleLeft = true; withStatusBar = false; showBack = false; barHeight = 44f }
            }
            VGap(8f)
            SDTabBar {
                attr {
                    tabs = listOf("my".tr, "all".tr, "more".tr, "settings".tr)
                    currentIndex = ctx.tab
                    tabMinWidth = 70f
                    tabBgColor = SDColors.white5
                }
                event { onChanged { ctx.tab = it } }
            }
            VGap(8f)
            SDTabBar {
                attr {
                    tabs = listOf("my".tr, "all".tr)
                    currentIndex = ctx.tab.coerceAtMost(1)
                    indicator = SDTabIndicator.UNDERLINE
                    isScrollable = false
                    tabHeight = 40f
                    labelSize = 16f
                    labelWeight = SDWeight.W700
                    unselectedLabelColor = Color(0x8AFFFFFFL)
                    indicatorColor = Color.WHITE
                }
                event { onChanged { ctx.tab = it } }
            }
            VGap(8f)
            SDSegmented {
                attr {
                    segments = listOf("simple", "custom")
                    currentIndex = ctx.segment
                    fontFamily = SDFont.poppins
                }
                event { onChanged { ctx.segment = it } }
            }
            // SafeTop / SafeBottom / SDSafeArea：演示占位（高度取自系统安全区）
            View {
                attr { marginTop(8f); backgroundColor(UIColor.cFFF10); borderRadius(8f) }
                SafeTop()
                SDText("SafeTop ↑ / SafeBottom ↓", size = 12f) { attr { alignSelfCenter() } }
                SafeBottom(min = 8f)
            }
            View {
                attr { marginTop(8f); height(120f); backgroundColor(UIColor.cFFF10); borderRadius(8f) }
                SDSafeArea(top = true, bottom = true) {
                    attr { allCenter() }
                    SDText("SDSafeArea", size = 12f)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 列表

    private fun buildListSection(c: ViewContainer<*, *>) {
        val ctx = this
        val w = ctx.screenWidth - 32f
        with(c) {
            section("List")
            SDSwiper {
                attr {
                    pageWidth = w
                    pageHeight = 120f
                    itemCount = 3
                    autoPlayIntervalMs = 3000
                    activeDotWidth = 14f
                    itemBuilder = { index ->
                        View {
                            attr {
                                flex(1f)
                                borderRadius(12f)
                                allCenter()
                                backgroundColor(if (index % 2 == 0) SDColors.brandPurple else SDColors.gradientStart)
                            }
                            SDText("Banner ${index + 1}", size = 18f, fontWeight = SDWeight.W700)
                        }
                    }
                }
                event { onIndexChanged { ctx.bannerIndex = it } }
            }
            SDText { attr { marginTop(4f); text("current banner: ${ctx.bannerIndex}"); fontSize(12f); color(UIColor.cFFF60) } }

            // 下拉刷新 + 上拉加载
            View {
                attr { marginTop(12f); height(300f); borderRadius(12f); backgroundColor(UIColor.cFFF10) }
                SDRefreshList {
                    attr {
                        flex(1f)
                        state = ctx.paging
                        emptyImage = AssetsImage.libraryEmpty
                    }
                    header {
                        SDText("header（total = ${ctx.paging.total}）", size = 12f, color = UIColor.cFFF60) {
                            attr { margin(8f, 12f, 4f, 12f) }
                        }
                    }
                    items {
                        vfor({ ctx.paging.list }) { item ->
                            View {
                                attr { height(44f); padding(0f, 12f, 0f, 12f); justifyContentCenter() }
                                SDText(item)
                            }
                        }
                    }
                }
            }
            row {
                SDSecondaryButton {
                    attr { width(100f); height(32f); fontSize = 12f; text = "refresh".tr; marginTop(8f) }
                    event { onTap { ctx.paging.refresh() } }
                }
                HGap(8f)
                SDSecondaryButton {
                    attr { width(100f); height(32f); fontSize = 12f; text = "clear"; marginTop(8f) }
                    event { onTap { ctx.paging.clearData() } }
                }
            }

            // 静态网格
            VGap(12f)
            val grid = SDGridSpec(columns = 4, totalWidth = w, spacing = 8f)
            SDGrid(listOf("A", "B", "C", "D", "E", "F"), grid) { item, _ ->
                attr { height(40f); borderRadius(8f); backgroundColor(UIColor.cFFF10); allCenter() }
                SDText(item)
            }
            // 响应式网格（vforIndex + gridCell）
            VGap(12f)
            val grid3 = SDGridSpec(columns = 3, totalWidth = w, spacing = 10f)
            View {
                attr { gridContainer(grid3) }
                vforIndex({ ctx.gridItems }) { item, index, _ ->
                    View {
                        attr { gridCell(grid3, index); height(36f); borderRadius(10f); backgroundColor(UIColor.cFFF10); allCenter() }
                        SDText(item, size = 12f)
                    }
                }
            }

            // 瀑布流
            View {
                attr { marginTop(12f); height(240f) }
                SDStaggeredGrid {
                    attr {
                        flex(1f)
                        columnCount = 2
                        listWidth = w
                        itemSpacing = 8f
                        lineSpacing = 8f
                        state = ctx.waterfall
                    }
                    items {
                        vfor({ ctx.waterfall.list }) { n ->
                            View {
                                attr {
                                    height(60f + (n % 3) * 30f)
                                    borderRadius(10f)
                                    backgroundColor(UIColor.cFFF10)
                                    allCenter()
                                }
                                SDText("#$n")
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 动画

    private fun buildAnimSection(c: ViewContainer<*, *>) {
        val ctx = this
        with(c) {
            section("Animation")
            row {
                SDFadeIn {
                    attr { durationS = 0.6f }
                    content { SDTag { attr { text = "FadeIn" } } }
                }
                HGap(8f)
                SDScaleIn {
                    attr { delayS = 0.2f }
                    content { SDTag { attr { text = "ScaleIn" } } }
                }
                HGap(8f)
                SDSlideIn {
                    attr { slideY = 1f; delayS = 0.4f }
                    content { SDTag { attr { text = "SlideIn" } } }
                }
                HGap(8f)
                SDEnterAnim {
                    attr { slideX = -1f; show = ctx.breathing }
                    content { SDTag { attr { text = "show=${ctx.breathing}" } } }
                }
            }
            VGap(12f)
            row {
                SDBreathing {
                    attr { maxScale = 1.2f; minOpacity = 0.6f; active = ctx.breathing }
                    content {
                        View {
                            attr { size(28f, 28f); borderRadius(14f); backgroundColor(SDColors.brandPurple) }
                            event { click { ctx.breathing = !ctx.breathing } }
                        }
                    }
                }
                HGap(16f)
                SDRotating {
                    attr { periodS = 2f }
                    content { SDAssetImage(AssetsImage.loadingIcon, 24f) }
                }
                HGap(16f)
                SDTypingDots { }
                HGap(16f)
                SDEqualizerBars { attr { playing = ctx.breathing } }
                HGap(16f)
                SDRefreshLoading { }
                HGap(16f)
                SDAnimatedBell { }
            }
        }
    }

    // ------------------------------------------------------------------ 弹窗

    private fun buildDialogSection(c: ViewContainer<*, *>) {
        val ctx = this
        with(c) {
            section("Dialog / Sheet / Toast")
            demoButton("toast / toastError") {
                ctx.toast("download_success".tr)
                ctx.toastError("network_timeout".tr)
            }
            demoButton("showConfirmDialog") {
                ctx.showConfirmDialog(content = "确定要删除这首歌吗？", onCancel = { ctx.toast("cancel".tr) }) {
                    ctx.toast("confirm".tr)
                }
            }
            demoButton("showAlertDialog（单按钮）") { ctx.showAlertDialog("app_hot_description".tr) }
            demoButton("suspend confirm()") {
                ctx.launch { ctx.toast("result = ${ctx.confirm("继续吗？")}") }
            }
            demoButton("showCardDialog（自定义内容）") {
                ctx.showCardDialog(title = "tip".tr, onConfirm = { it.dismiss() }) {
                    SDPointsLabel("unlock_full_video".tr, 20, Color.WHITE)
                }
            }
            demoButton("showInputDialog") {
                ctx.showInputDialog(title = "CDkey".tr, hintText = "please_enter_the_CDkey".tr, maxLength = 12) { text, handle ->
                    if (text.isEmpty()) {
                        ctx.toast("please_enter_the_CDkey".tr)
                    } else {
                        handle.dismiss()
                        ctx.toast(text)
                    }
                }
            }
            demoButton("showAppBottomSheet") {
                ctx.showAppBottomSheet { handle ->
                    View {
                        attr { padding(0f, 20f, 20f, 20f) }
                        SDSheetHeader("more".tr) { handle.dismiss() }
                        SDText("底部弹层内容：可按住顶部拖拽条下拉关闭", maxLines = 2) { attr { marginTop(16f) } }
                        SDPrimaryButton {
                            attr { marginTop(20f); text = "i_know".tr }
                            event { onTap { handle.dismiss() } }
                        }
                    }
                }
            }
            demoButton("showPlainBottomSheet") {
                ctx.showPlainBottomSheet { handle ->
                    SDSheetHeader("lyric_tag".tr) { handle.dismiss() }
                    SDText("旧样式弹层：圆角 16、无拖拽条") { attr { marginTop(16f); marginBottom(24f) } }
                }
            }
            demoButton("showActionSheet") {
                ctx.showActionSheet(
                    title = "more".tr,
                    items = listOf(
                        SDActionItem("download".tr, icon = AssetsImage.downloadMv1),
                        SDActionItem("share".tr, icon = AssetsImage.share, loading = true),
                        SDActionItem("delete".tr, danger = true),
                    ),
                ) { ctx.toast("action $it") }
            }
            demoButton("suspend pickAction()") {
                ctx.launch {
                    val i = ctx.pickAction(items = listOf(SDActionItem("camera".tr), SDActionItem("album".tr)))
                    ctx.toast("picked = $i")
                }
            }
            demoButton("showOptionPicker") {
                ctx.showOptionPicker("all".tr, listOf("16:9", "9:16", "1:1", "4:3"), selectedIndex = 1) { ctx.toast("option $it") }
            }
            View {
                attr { marginBottom(8f); height(40f); borderRadius(22f); backgroundColor(SDColors.secondaryBtn); allCenter() }
                event {
                    click { p ->
                        ctx.showPopupMenu(
                            p.pageX, p.pageY,
                            listOf(SDPopupMenuItem("Regenerate", "a"), SDPopupMenuItem("Edit prompt", "b"), SDPopupMenuItem("delete".tr, "c")),
                            selectedId = "b",
                        ) { ctx.toast(it.title) }
                    }
                }
                SDText("showPopupMenu（锚点 = 点击位置）", size = 13f)
            }
            demoButton("showUsageStatement(upload)") {
                ctx.launch { ctx.toast("agree = ${ctx.showUsageStatement(UsageStatementType.UPLOAD)}") }
            }
            demoButton("showAgree(share)") {
                ctx.launch { ctx.toast("agree = ${ctx.showAgree(UsageStatementType.SHARE)}") }
            }
            demoButton("showAiWatermarkStatement") {
                ctx.launch { ctx.toast("agree = ${ctx.showAiWatermarkStatement()}") }
            }
            demoButton("showPermissionRationale(camera)") {
                ctx.launch { ctx.toast("agree = ${ctx.showPermissionRationale("camera")}") }
            }
            demoButton("showPermissionSettingsDialog / ensurePermission") {
                ctx.showPermissionSettingsDialog("photos")
                ctx.launch { ctx.ensurePermission("microphone", purpose = "录制音频") }
            }
            demoButton("showLoadingDialog（2s）") {
                ctx.showLoadingDialog("loading".tr, clickMaskDismiss = true)
                ctx.postDelayed(2000) { ctx.closeLoading() }
            }
            demoButton("showProgressDialog") {
                val dialog = ctx.showProgressDialog("loading".tr, progress = 0f, cancelable = true)
                ctx.launch {
                    for (i in 1..10) {
                        delayMs(250)
                        dialog.progress = i / 10f
                        dialog.message = "${i * 10}%"
                    }
                    dialog.dismiss()
                }
            }
            demoButton("showGenerationCompleted（顶部横幅）") {
                ctx.showGenerationCompleted { ctx.toast("tap banner") }
            }
            demoButton("showTopMessage / success / warning") {
                ctx.showTopMessage("标题", "内容", imageUrl = DEMO_IMAGE)
                ctx.postDelayed(3200) { ctx.showSuccessMessage("download_success".tr) }
                ctx.postDelayed(6400) { ctx.showWarningMessage("download_failed".tr) }
            }
            demoButton("showImagePreview") {
                ctx.showImagePreview(
                    listOf(
                        ImagePreviewItem.success(DEMO_IMAGE),
                        ImagePreviewItem("", ImagePreviewItem.STATUS_GENERATING),
                        ImagePreviewItem("", ImagePreviewItem.STATUS_FAILED),
                    ),
                    initialIndex = 0,
                    onDownload = { _, index -> ctx.toast("download $index") },
                )
            }
            demoButton("showVideoPreview") {
                ctx.showVideoPreview(
                    listOf(VideoPreviewItem.success(DEMO_VIDEO, coverUrl = DEMO_IMAGE), VideoPreviewItem("", VideoPreviewItem.STATUS_FAILED)),
                    onDownload = { _, index -> ctx.toast("download $index") },
                ) { result -> ctx.toast("index=${result?.index} pos=${result?.positionMs}") }
            }
            demoButton("suspend previewVideos()") {
                ctx.launch {
                    val r = ctx.previewVideos(listOf(VideoPreviewItem.success(DEMO_VIDEO)))
                    ctx.toast("pos=${r?.positionMs}")
                }
            }
            demoButton("showVideoInfoDialog") {
                ctx.showVideoInfoDialog("玩法说明", DEMO_VIDEO, "这里是模板的说明文字。") { ctx.toast("try".tr) }
            }
            demoButton("showAppHotDialog") { ctx.showAppHotDialog() }
            demoButton("showScoreDialog") { ctx.launch { ctx.toast("rate = ${ctx.showScoreDialog()}") } }
            demoButton("showSheetImageOption") {
                ctx.showSheetImageOption(
                    onRoleLibrary = { ctx.toast("role") },
                    onCamera = { ctx.toast("camera".tr) },
                    onAlbum = { ctx.toast("album".tr) },
                    usageRecords = listOf(DEMO_IMAGE, DEMO_IMAGE),
                    onUsageRecordTap = { ctx.toast(it) },
                )
            }
            demoButton("showTagOptions") {
                ctx.showTagOptions { tag -> ctx.toast(tag) }
            }
            demoButton("showMvPreviewUnlock") {
                ctx.showMvPreviewUnlock(needPoint = 20) { ctx.toast("unlock") }
            }
            demoButton("SDPrivacyConsent（隐私同意 Gate）") {
                ctx.showConsent = true
                ctx.refreshBackIntercept()
            }
            demoButton("SDLinks.open（用户协议）") {
                SDLinks.open(ctx, com.sondo.app.core.constants.AppConstants.termsUrl, "terms_of_service".tr)
            }
        }
    }
}
