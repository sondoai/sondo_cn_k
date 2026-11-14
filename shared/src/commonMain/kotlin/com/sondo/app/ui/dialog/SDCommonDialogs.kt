package com.sondo.app.ui.dialog

import com.sondo.app.base.BasePager
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.i18n.tr
import com.sondo.app.nativeapi.SDVideo
import com.sondo.app.nativeapi.SDVideoState
import com.sondo.app.nativeapi.SDVideoView
import com.sondo.app.service.audio.AppAudioOutputCoordinator
import com.sondo.app.ui.basePager
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.SDFont
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDLoading
import com.sondo.app.ui.overlay.DialogEntry
import com.sondo.app.ui.overlay.DialogHandle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.List
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

// ============================================================================
// 维护公告（Dart DialogAppHot）
// ============================================================================

/**
 * Dart `DialogAppHot.show(onGet:)`：服务维护提示。不可点遮罩 / 返回键关闭，只能点「我知道了」。
 * 卡片左右各距屏幕 40、圆角 20。
 */
fun BasePager.showAppHotDialog(onGet: (() -> Unit)? = null): DialogHandle {
    val cardWidth = screenWidth - 80f
    return showDialog(
        align = DialogEntry.ALIGN_CENTER,
        dismissOnMask = false,
        dismissOnBack = false,
        maskColor = Color(0, 0, 0, 0.6f),
    ) { handle ->
        View {
            attr {
                width(cardWidth)
                borderRadius(20f)
                backgroundColor(UIColor.c1C1C1C)
                alignItemsCenter()
                paddingTop(28f)
            }
            Text {
                attr {
                    text("app_hot_upgrading".tr)
                    fontSize(20f)
                    fontWeight700()
                    color(Color.WHITE)
                }
            }
            Image {
                attr {
                    marginTop(20f)
                    size(180f, 160f)
                    src(ImageUri.commonAssets(AssetsImage.appHot))
                    resizeContain()
                }
            }
            Text {
                attr {
                    margin(20f, 20f, 0f, 20f)
                    alignSelfStretch()
                    text("app_hot_description".tr)
                    fontSize(14f)
                    lineHeight(14f * 1.5f)
                    color(Color(0xCCFFFFFFL))
                }
            }
            View {
                attr {
                    margin(24f, 20f, 24f, 20f)
                    alignSelfStretch()
                    height(48f)
                    borderRadius(24f)
                    backgroundColor(UIColor.cF3EFF7)
                    allCenter()
                }
                event {
                    click {
                        handle.dismiss()
                        onGet?.invoke()
                    }
                }
                Text {
                    attr {
                        text("i_know".tr)
                        fontSize(16f)
                        fontWeight600()
                        fontFamily(SDFont.poppins)
                        color(UIColor.c0A0A0A)
                    }
                }
            }
        }
    }
}

// ============================================================================
// 评分引导（Dart DialogScore 的弹窗部分）
// ============================================================================

/**
 * Dart `DialogScore`（`isDialog = true` 的 UI）：「喜欢 Sondo 吗」评分引导。
 * 点「去评分」返回 true；点关闭 / 遮罩返回 false。
 *
 * 只负责 UI。Dart 里的前置条件（`StorageKey.isShowRating` 只弹一次、远程开关 `isCommentOpen`、
 * iOS debug 不弹、调系统评分面板、埋点 `rating_continue`）属于业务层，由调用方处理。
 */
suspend fun BasePager.showScoreDialog(): Boolean = suspendCoroutine { cont ->
    var done = false
    val finish: (Boolean) -> Unit = { v ->
        if (!done) {
            done = true
            cont.resume(v)
        }
    }
    val cardWidth = screenWidth - 48f
    showDialog(
        align = DialogEntry.ALIGN_CENTER,
        maskColor = Color(0, 0, 0, 0.7f),
        onDismiss = { finish(false) },
    ) { handle ->
        // 头图探出卡片顶部 60，关闭按钮在卡片右上角外侧：外层不设圆角，避免被裁剪
        View {
            attr {
                width(cardWidth + 10f)
                paddingTop(60f)
                paddingRight(5f)
                paddingLeft(5f)
            }
            View {
                attr {
                    padding(80f, 16f, 16f, 16f)
                    borderRadius(24f)
                    backgroundColor(UIColor.c1C1C1C)
                    alignItemsCenter()
                }
                Text {
                    attr {
                        text("like_sondo_cheer_us_on".tr)
                        fontSize(14f)
                        fontWeight500()
                        lineHeight(14f * 1.8f)
                        color(Color.WHITE)
                        textAlignCenter()
                    }
                }
                View {
                    attr {
                        marginTop(30f)
                        alignSelfStretch()
                        height(50f)
                        borderRadius(48f)
                        backgroundColor(UIColor.cF3EFF7)
                        allCenter()
                    }
                    event {
                        click {
                            finish(true)
                            handle.dismiss()
                        }
                    }
                    Text {
                        attr {
                            text("go_rating".tr)
                            fontSize(14f)
                            // Poppins w600：用 SemiBold 字体文件，不再叠字重
                            fontFamily("Poppins-SemiBold")
                            color(UIColor.c0A0A0A)
                        }
                    }
                }
            }
            View {
                attr {
                    absolutePosition(top = 0f, left = 0f, right = 0f)
                    alignItemsCenter()
                    touchEnable(false)
                }
                Image {
                    attr {
                        size(200f, 120f)
                        src(ImageUri.commonAssets(AssetsImage.scoreHeadImg))
                        resizeContain()
                    }
                }
            }
            Image {
                attr {
                    absolutePosition(top = 30f, right = 0f)
                    size(30f, 30f)
                    src(ImageUri.commonAssets(AssetsImage.close4))
                    resizeContain()
                }
                event { click { handle.dismiss() } }
            }
        }
    }
}

// ============================================================================
// 选择照片来源（Dart DialogSheetImageOption）
// ============================================================================

private fun ViewContainer<*, *>.imageOptionTile(icon: String, label: String, first: Boolean, onTap: () -> Unit) {
    View {
        attr {
            flex(1f)
            height(100f)
            if (!first) marginLeft(12f)
            padding(0f, 4f, 0f, 4f)
            borderRadius(10f)
            backgroundColor(SDColors.white10)
            justifyContentCenter()
            alignItemsCenter()
        }
        event { click { onTap() } }
        Image {
            attr {
                marginTop(10f)
                size(24f, 24f)
                src(ImageUri.commonAssets(icon))
                resizeContain()
            }
        }
        View {
            attr {
                height(36f)
                alignSelfStretch()
                allCenter()
            }
            Text {
                attr {
                    text(label)
                    fontSize(12f)
                    fontWeight600()
                    fontFamily(SDFont.poppins)
                    lineHeight(12f * 1.2f)
                    color(SDColors.white80)
                    textAlignCenter()
                    lines(2)
                    textOverFlowTail()
                }
            }
        }
    }
}

/**
 * Dart `DialogSheetImageOption.show(...)`：「选择照片」底部弹层 —— 角色库 / 相机 / 相册三个入口 + 使用记录缩略图。
 * 每个回调触发前弹层先关闭（与 Dart 一致）。
 *
 * @param usageRecords 最近用过的图片 URL；为空不显示「使用记录」
 */
fun BasePager.showSheetImageOption(
    onRoleLibrary: () -> Unit,
    onCamera: () -> Unit,
    onAlbum: () -> Unit,
    usageRecords: List<String> = emptyList(),
    clickMaskDismiss: Boolean = true,
    onUsageRecordTap: (String) -> Unit = {},
): DialogHandle {
    val bottomInset = safeBottom
    return showDialog(
        align = DialogEntry.ALIGN_BOTTOM,
        dismissOnMask = clickMaskDismiss,
        maskColor = Color(0, 0, 0, 0.7f),
    ) { handle ->
        View {
            attr {
                padding(20f, 20f, 30f + bottomInset, 20f)
                backgroundColor(UIColor.c1C1C1C)
                borderRadius(16f, 16f, 0f, 0f)
            }
            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                    justifyContentSpaceBetween()
                }
                Text {
                    attr {
                        text("select_photo".tr)
                        fontSize(16f)
                        fontWeight600()
                        fontFamily(SDFont.poppins)
                        color(Color.WHITE)
                    }
                }
                Image {
                    attr {
                        size(26f, 26f)
                        src(ImageUri.commonAssets(AssetsImage.close))
                        resizeContain()
                    }
                    event { click { handle.dismiss() } }
                }
            }
            View {
                attr {
                    marginTop(16f)
                    flexDirectionRow()
                }
                imageOptionTile(AssetsImage.addLibraryIcon, "role_library_title".tr, true) {
                    handle.dismiss()
                    onRoleLibrary()
                }
                imageOptionTile(AssetsImage.cameraIcon, "camera".tr, false) {
                    handle.dismiss()
                    onCamera()
                }
                imageOptionTile(AssetsImage.albumIcon, "album".tr, false) {
                    handle.dismiss()
                    onAlbum()
                }
            }
            if (usageRecords.isNotEmpty()) {
                Text {
                    attr {
                        marginTop(16f)
                        text("usage_record".tr)
                        fontSize(14f)
                        fontWeight600()
                        fontFamily(SDFont.poppins)
                        color(Color.WHITE)
                    }
                }
                List {
                    attr {
                        marginTop(12f)
                        height(68f)
                        flexDirectionRow()
                        showScrollerIndicator(false)
                    }
                    usageRecords.forEachIndexed { index, url ->
                        SDImage {
                            attr {
                                size(68f, 68f)
                                if (index > 0) marginLeft(10f)
                                borderRadius(12f)
                                this.url = url
                            }
                            event {
                                click {
                                    handle.dismiss()
                                    onUsageRecordTap(url)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 歌词结构标签（Dart DialogTagOptions）
// ============================================================================

/** 歌词结构标签（参考工程里就是写死的英文方括号标签，不走 i18n）。 */
val LYRIC_STRUCTURE_TAGS: List<String> =
    listOf("[Intro]", "[Verse]", "[Pre-Chorus]", "[Chorus]", "[Break]", "[Bridge]", "[Outro]")

/**
 * Dart `DialogTagOptions`：歌词结构标签面板（标题「歌词标签」+ 关闭 + 一组 chip）。
 *
 * 与 Dart 一致：点 chip **只回调 [onSelect]，不自动关闭**（可连续插入多个标签）；要关闭就在回调里用返回的 handle。
 */
fun BasePager.showTagOptions(
    tags: List<String> = LYRIC_STRUCTURE_TAGS,
    onSelect: (tag: String) -> Unit,
): DialogHandle = showPlainBottomSheet { handle ->
    SDSheetHeader("lyric_tag".tr) { handle.dismiss() }
    Scroller {
        attr {
            marginTop(24f)
            height(150f)
            showScrollerIndicator(false)
        }
        View {
            attr {
                flexDirectionRow()
                flexWrapWrap()
            }
            for (tag in tags) {
                View {
                    attr {
                        height(42f)
                        padding(0f, 16f, 0f, 16f)
                        marginRight(10f)
                        marginBottom(10f)
                        borderRadius(10f)
                        backgroundColor(UIColor.cFFF10)
                        allCenter()
                    }
                    event { click { onSelect(tag) } }
                    Text {
                        attr {
                            text(tag)
                            fontSize(12f)
                            color(SDColors.white80)
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 解锁完整视频（Dart MvPreviewUnlockDialog）
// ============================================================================

/**
 * Dart `MvPreviewPointsLabel`：`文字 ✦ N 积分`（16 w600 Poppins）。
 */
fun ViewContainer<*, *>.SDPointsLabel(text: String, points: Int, color: Color = Color.WHITE) {
    View {
        attr {
            flexDirectionRow()
            allCenter()
        }
        Text {
            attr {
                text(text)
                fontSize(16f)
                fontWeight600()
                fontFamily(SDFont.poppins)
                color(color)
            }
        }
        Image {
            attr {
                size(16f, 16f)
                margin(0f, 8f, 0f, 4f)
                src(ImageUri.commonAssets(AssetsImage.mvPreviewSpark))
                resizeContain()
                tintColor(color)
            }
        }
        Text {
            attr {
                text("$points ${"credits".tr}")
                fontSize(16f)
                fontWeight600()
                fontFamily(SDFont.poppins)
                color(color)
            }
        }
    }
}

/**
 * Dart `MvPreviewUnlockDialog.show(needPoint:, onUnlock:)`：解锁完整视频的底部弹层。
 * 点 CTA 时先关闭弹层，再回调 [onUnlock]（只触发一次）。
 */
fun BasePager.showMvPreviewUnlock(needPoint: Int, onUnlock: () -> Unit): DialogHandle {
    val bottomInset = safeBottom
    var busy = false
    return showDialog(align = DialogEntry.ALIGN_BOTTOM) { handle ->
        View {
            attr {
                backgroundColor(UIColor.c1C1C1C)
                // 源码里左右圆角不对称（20 / 24），保持一致
                borderRadius(20f, 24f, 0f, 0f)
                padding(30f, 20f, 30f + bottomInset, 20f)
                alignItemsCenter()
            }
            Image {
                attr {
                    size(130f, 130f)
                    src(ImageUri.commonAssets(AssetsImage.mvPreviewUnlock))
                    resizeContain()
                }
            }
            Text {
                attr {
                    marginTop(20f)
                    text("unlock_full_video".tr)
                    fontSize(18f)
                    fontWeight600()
                    fontFamily(SDFont.poppins)
                    lineHeight(18f * 1.4f)
                    color(Color.WHITE)
                    textAlignCenter()
                }
            }
            Text {
                attr {
                    marginTop(12f)
                    text("unlock_full_video_desc".tr)
                    fontSize(14f)
                    fontFamily(SDFont.poppins)
                    color(SDColors.white60)
                    textAlignCenter()
                }
            }
            View {
                attr {
                    marginTop(20f)
                    alignSelfStretch()
                    height(48f)
                    borderRadius(53f)
                    backgroundColor(SDColors.brandPurple)
                    allCenter()
                }
                event {
                    click {
                        if (!busy) {
                            busy = true
                            handle.dismiss()
                            onUnlock()
                        }
                    }
                }
                SDPointsLabel("unlock_full_video".tr, needPoint, Color.WHITE)
            }
            View {
                attr {
                    absolutePosition(top = 20f, right = 20f)
                    size(28f, 28f)
                    borderRadius(14f)
                    backgroundColor(SDColors.white10)
                    allCenter()
                }
                event { click { handle.dismiss() } }
                Image {
                    attr {
                        size(13f, 13f)
                        src(ImageUri.commonAssets(AssetsImage.mvPreviewClose))
                        resizeContain()
                    }
                }
            }
        }
    }
}

// ============================================================================
// 视频说明（Dart VideoInfoDialog）
// ============================================================================

internal class SDLoopVideoAttr : ComposeAttr() {
    var url: String = ""
}

/**
 * 循环播放的小预览视频：封面期间显示转圈。
 * 有声，加入 [AppAudioOutputCoordinator]（Dart VideoWidget → AppVideoPlayer 同样会暂停音乐）：就绪后先 claim 再播放，销毁时释放。
 */
internal class SDLoopVideoView : ComposeView<SDLoopVideoAttr, ComposeEvent>() {
    private var ready by observable(false)
    private var videoRef: ViewRef<SDVideoView>? = null
    private var playing = false
    private var readyHandled = false
    private var alive = true
    private var detachAudio: (() -> Unit)? = null

    override fun createAttr(): SDLoopVideoAttr = SDLoopVideoAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun created() {
        super.created()
        detachAudio = AppAudioOutputCoordinator.attach(
            owner = this,
            isPlaying = { playing },
            isAudible = { true },
            pauseForHandoff = {
                playing = false
                videoRef?.view?.pause()
            },
        )
    }

    override fun viewDestroyed() {
        alive = false
        videoRef?.view?.pause()
        detachAudio?.invoke()
        detachAudio = null
        super.viewDestroyed()
    }

    private fun onPlayerState(state: String) {
        when (state) {
            SDVideoState.PLAYING -> {
                playing = true
                ready = true
            }
            SDVideoState.PAUSED, SDVideoState.ERROR -> playing = false
        }
        if (!readyHandled && (state == SDVideoState.READY || state == SDVideoState.PLAYING)) {
            readyHandled = true
            val pager = basePager ?: return
            pager.launch {
                val token = AppAudioOutputCoordinator.claim(this@SDLoopVideoView)
                if (!alive) return@launch
                if (AppAudioOutputCoordinator.isCurrent(this@SDLoopVideoView, token)) videoRef?.view?.play()
            }
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                backgroundColor(Color.BLACK)
                overflow(true)
            }
            SDVideo {
                ref { ctx.videoRef = it }
                attr {
                    absolutePositionAllZero()
                    src(ctx.attr.url)
                    // 先申请出声再播放
                    autoPlay(false)
                    loop(true)
                    fitCover()
                }
                event {
                    onFirstFrame { ctx.ready = true }
                    onState { ctx.onPlayerState(it.state) }
                }
            }
            vif({ !ctx.ready }) {
                View {
                    attr {
                        absolutePositionAllZero()
                        allCenter()
                    }
                    SDLoading { }
                }
            }
        }
    }
}

/**
 * Dart `VideoInfoDialog.show(title:, videoUrl:, description:, onTry:)`：玩法 / 模板说明弹窗 ——
 * 标题 + 关闭、3:4 循环预览视频、说明文字、可选「试试」按钮（点后先关闭再回调）。
 */
fun BasePager.showVideoInfoDialog(
    title: String,
    videoUrl: String,
    description: String,
    onTry: (() -> Unit)? = null,
): DialogHandle {
    val cardWidth = screenWidth - 32f
    val videoWidth = cardWidth - 32f
    return showDialog(
        align = DialogEntry.ALIGN_CENTER,
        maskColor = Color(0, 0, 0, 0.7f),
    ) { handle ->
        View {
            attr {
                width(cardWidth)
                padding(20f, 16f, 30f, 16f)
                borderRadius(24f)
                backgroundColor(UIColor.c1C1C1C)
            }
            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                }
                Text {
                    attr {
                        flex(1f)
                        text(title)
                        fontSize(16f)
                        fontWeight600()
                        fontFamily(SDFont.poppins)
                        color(Color(0xCCFFFFFFL))
                        lines(1)
                    }
                }
                Image {
                    attr {
                        size(26f, 26f)
                        src(ImageUri.commonAssets(AssetsImage.close))
                        resizeContain()
                    }
                    event { click { handle.dismiss() } }
                }
            }
            addChild(SDLoopVideoView()) {
                attr {
                    marginTop(16f)
                    size(videoWidth, videoWidth * 4f / 3f)
                    borderRadius(10f)
                    url = videoUrl
                }
            }
            Text {
                attr {
                    marginTop(16f)
                    text(description)
                    fontSize(12f)
                    fontWeight500()
                    fontFamily(SDFont.poppins)
                    color(Color(0x80FFFFFFL))
                    lines(10)
                    textOverFlowTail()
                }
            }
            if (onTry != null) {
                View {
                    attr {
                        marginTop(16f)
                        height(48f)
                        borderRadius(53f)
                        backgroundColor(UIColor.cF3EFF7)
                        allCenter()
                    }
                    event {
                        click {
                            handle.dismiss()
                            onTry()
                        }
                    }
                    Text {
                        attr {
                            text("try".tr)
                            fontSize(16f)
                            fontWeight600()
                            fontFamily(SDFont.poppins)
                            color(UIColor.c0A0A0A)
                        }
                    }
                }
            }
        }
    }
}
