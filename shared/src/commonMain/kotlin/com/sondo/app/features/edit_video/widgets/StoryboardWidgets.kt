package com.sondo.app.features.edit_video.widgets

import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.constants.asset
import com.sondo.app.core.i18n.tr
import com.sondo.app.data.models.MvShot
import com.sondo.app.data.models.ShotImageModel
import com.sondo.app.features.edit_video.state.StoryboardState
import com.sondo.app.nativeapi.SDLottie
import com.sondo.app.nativeapi.SDVideo
import com.sondo.app.ui.anim.SDRotating
import com.sondo.app.ui.atoms.HGap
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDRingProgress
import com.sondo.app.ui.atoms.SDSeekBar
import com.sondo.app.ui.atoms.SDShimmer
import com.sondo.app.ui.atoms.SDUploadImage
import com.sondo.app.ui.atoms.SDUploadImageStatus
import com.sondo.app.ui.atoms.VGap
import com.sondo.app.ui.list.scrollOver
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vforIndex
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.views.Canvas
import com.tencent.kuikly.core.views.DivView
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.List
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.TextView
import com.tencent.kuikly.core.views.View

// Poppins 字重名（见 shared_agent_guide：带字重的字体名不要再叠 fontWeight）
internal const val P400 = "Poppins"
internal const val P500 = "Poppins-Medium"
internal const val P600 = "Poppins-SemiBold"
internal const val P700 = "Poppins-Bold"

internal val cFFF10 = UIColor.cFFF10
internal val spinnerPurple = Color(0xAB8058F7L)

/** 静态文字（Dart TextWidget，默认白色单行）。 */
internal fun ViewContainer<*, *>.EvText(
    text: String,
    size: Float = 14f,
    color: Color = Color.WHITE,
    face: String = P400,
    maxLines: Int = 1,
    center: Boolean = false,
    init: (TextView.() -> Unit)? = null,
) {
    Text {
        attr {
            text(text)
            fontSize(size)
            color(color)
            fontFamily(face)
            lines(maxLines)
            textOverFlowTail()
            if (center) textAlignCenter()
        }
        init?.invoke(this)
    }
}

/** Dart CircularProgressIndicator：旋转的 1/4 圆环。 */
internal fun ViewContainer<*, *>.Spinner(size: Float = 36f, color: Color = spinnerPurple, stroke: Float = 4f) {
    SDRotating {
        attr { size(size, size); periodS = 1f }
        content {
            SDRingProgress {
                attr {
                    size(size, size)
                    strokeWidth = stroke
                    progress = 0.28f
                    progressColor = color
                    trackColor = Color.TRANSPARENT
                }
            }
        }
    }
}

/** star_loading lottie。 */
internal fun ViewContainer<*, *>.StarLoading(size: Float) {
    SDLottie { attr { size(size, size); src("lottie/star_loading.json"); loop(true); autoPlay(true) } }
}

/** Material play_arrow_rounded（Canvas 绘制三角）。 */
internal fun ViewContainer<*, *>.PlayGlyph(size: Float = 40f, color: Color = Color.WHITE) {
    Canvas({ attr { size(size, size) } }) { context, w, h ->
        context.beginPath()
        context.moveTo(w * 0.36f, h * 0.24f)
        context.lineTo(w * 0.78f, h * 0.5f)
        context.lineTo(w * 0.36f, h * 0.76f)
        context.closePath()
        context.fillStyle(color)
        context.fill()
    }
}

/** Material fullscreen（四角折线）。 */
internal fun ViewContainer<*, *>.FullscreenGlyph(size: Float = 24f, color: Color = Color.WHITE) {
    Canvas({ attr { size(size, size) } }) { context, w, h ->
        val a = w * 0.21f
        val b = w * 0.42f
        context.beginPath()
        context.strokeStyle(color)
        context.lineWidth(2f)
        context.moveTo(a, b); context.lineTo(a, a); context.lineTo(b, a)
        context.moveTo(w - b, a); context.lineTo(w - a, a); context.lineTo(w - a, b)
        context.moveTo(w - a, h - b); context.lineTo(w - a, h - a); context.lineTo(w - b, h - a)
        context.moveTo(b, h - a); context.lineTo(a, h - a); context.lineTo(a, h - b)
        context.stroke()
    }
}

/** 暂停时居中的毛玻璃播放按钮（50×50，白 10%）。 */
internal fun ViewContainer<*, *>.PausedPlayButton(onTap: () -> Unit) {
    View {
        attr { size(50f, 50f); borderRadius(25f); backgroundColor(UIColor.transparent10); allCenter() }
        event { click { onTap() } }
        PlayGlyph(40f)
    }
}

// ============================================================================
// 时间轴
// ============================================================================

/** Dart `_buildVideoLine`：标题 + 循环模式切换 + 分镜缩略图横向列表。 */
internal fun ViewContainer<*, *>.StoryboardTimeline(
    s: StoryboardState,
    bottomGap: Boolean,
    guideRef: ((ViewRef<DivView>) -> Unit)? = null,
) {
    vif({ s.shotList.isNotEmpty() }) {
        View {
            attr { flexDirectionRow(); alignItemsCenter(); justifyContentSpaceBetween(); padding(0f, 16f, 0f, 16f) }
            EvText("timeline".tr, 14f, UIColor.transparent80, P500)
            View {
                attr { paddingLeft(16f) }
                event { click { s.onRepeatVideo() } }
                Image {
                    attr {
                        size(20f, 20f)
                        src((if (s.videoPlayer.isListRepeat) AssetsImage.repeatIcon else AssetsImage.repeatOneIcon).asset())
                    }
                }
            }
        }
    }
    VGap(12f)
    View {
        if (guideRef != null) ref { guideRef(it) }
        attr { height(98f) }
        vif({ s.shotList.isEmpty() }) {
            View {
                attr { flex(1f); allCenter() }
                vif({ s.videoStatus == 3 }) {
                    EvText("shots_failed_please_recreate".tr, 10f, Color(0xCCEC6D6DL), P500, center = true)
                }
                velse { Spinner() }
            }
        }
        velse {
            List {
                ref { s.shotListRef = it }
                attr {
                    flex(1f)
                    flexDirectionRow()
                    showScrollerIndicator(false)
                    padding(0f, 16f, 0f, 16f)
                    scrollOver()
                }
                vforIndex({ s.shotList }) { shot, index, _ ->
                    TimelineItem(s, shot, index)
                }
            }
        }
    }
    if (bottomGap) VGap(12f)
}

private fun ViewContainer<*, *>.TimelineItem(s: StoryboardState, shot: MvShot, index: Int) {
    View {
        attr {
            val isFirst = index == 0
            val isLast = index == s.shotList.size - 1
            padding(10f, if (isFirst) 10f else 6f, 10f, if (isLast) 10f else 6f)
            backgroundColor(cFFF10)
            borderRadius(if (isFirst) 16f else 0f, if (isLast) 16f else 0f, if (isFirst) 16f else 0f, if (isLast) 16f else 0f)
        }
        View {
            attr {
                size(75f, 75f)
                borderRadius(8f)
                val selected = s.shotIndex == index
                border(com.tencent.kuikly.core.base.Border(if (selected) 1.5f else 0f, com.tencent.kuikly.core.base.BorderStyle.SOLID, if (selected) Color(0xB3FFFFFFL) else Color.TRANSPARENT))
            }
            event { click { s.toStoryboard(index) } }
            val img = shot.firstFrameImg ?: ""
            if (img.isNotEmpty() && shot.status == 2) {
                SDImage {
                    attr {
                        // Dart Container 的 1.5 描边会把子节点向内挤，图片不能盖住选中描边
                        val b = if (s.shotIndex == index) 1.5f else 0f
                        absolutePosition(top = b, left = b, bottom = b, right = b)
                        borderRadius(8f)
                        url = img
                    }
                }
            }
            if (shot.status != 2) {
                View {
                    attr { absolutePositionAllZero(); borderRadius(8f); backgroundColor(cFFF10); allCenter() }
                    if (shot.status == 3) {
                        Image { attr { size(40f, 40f); src(AssetsImage.mvFailedIcon.asset()); resizeContain() } }
                    } else {
                        StarLoading(40f)
                    }
                }
            }
            View {
                attr {
                    absolutePosition(top = 4f, left = 4f)
                    size(16f, 16f)
                    borderRadius(8f)
                    backgroundColor(Color(0xB3FFFFFFL))
                    allCenter()
                }
                EvText("${index + 1}", 8f, Color.BLACK, P500)
            }
        }
    }
}

// ============================================================================
// 分镜播放区
// ============================================================================

/** 16:9 播放框高度（Dart AspectRatio + minHeight 190 / maxHeight 218）。 */
internal fun storyboardVideoHeight(screenWidth: Float): Float = ((screenWidth - 32f) * 9f / 16f).coerceIn(190f, 218f)

/**
 * Dart `Stack(children: [_buildVideoPlay(), _buildVideoStatus()])`。
 * [failedTextKey]：编辑页 `generation_failed_credit_refunded`，重试页 `generation_failed`。
 */
internal fun ViewContainer<*, *>.StoryboardVideoArea(s: StoryboardState, screenWidth: Float, failedTextKey: String) {
    val p = s.videoPlayer
    View {
        attr { margin(8f, 16f, 8f, 16f); height(storyboardVideoHeight(screenWidth)); borderRadius(16f); backgroundColor(cFFF10) }
        // 播放器
        View {
            attr { absolutePositionAllZero() }
            event { click { s.playOrPauseVideo() } }
            vbind({ "${p.readySrc}#${p.playerKey}" }) {
                if (p.readySrc.isNotEmpty()) {
                    SDVideo {
                        ref { p.videoRef = it }
                        attr {
                            absolutePositionAllZero()
                            src(p.readySrc)
                            autoPlay(false)
                            loop(!p.isListRepeat)
                            fitContain()
                            cache(false)
                            progressIntervalMs(100)
                        }
                        event {
                            onState { p.onNativeState(it) }
                            onProgress { p.onNativeProgress(it) }
                        }
                    }
                }
            }
            vif({ p.videoLoading }) {
                View {
                    attr { absolutePositionAllZero(); allCenter(); backgroundColor(cFFF10) }
                    Spinner()
                    Text {
                        attr {
                            absolutePositionAllZero()
                            textAlignCenter()
                            fontSize(12f)
                            color(Color.WHITE)
                            lineHeight(storyboardVideoHeight(screenWidth))
                            text(p.videoProgress)
                        }
                    }
                }
            }
        }
        // 进度条
        vif({ !p.videoLoading }) {
            SDSeekBar {
                attr {
                    absolutePosition(left = 16f, right = 16f, bottom = 0f)
                    duration = p.durations.toFloat()
                    position = p.positions.toFloat()
                }
                event { onSeekEnd { s.onVideoSeekEnd(it) } }
            }
        }
        // 暂停按钮
        vif({ p.isPaused && !p.isVideoError }) {
            View {
                attr { absolutePositionAllZero(); allCenter() }
                PausedPlayButton { s.playOrPauseVideo() }
            }
        }
        // 全屏按钮（分镜成功才可全屏）
        vif({ (s.currentShot?.status == 2) && !p.videoLoading && s.shotList.isNotEmpty() }) {
            View {
                attr { absolutePosition(top = 0f, right = 0f); size(48f, 48f); allCenter() }
                event { click { s.onEnterFullScreen() } }
                FullscreenGlyph(24f)
            }
        }
        // 分镜状态（生成中 / 失败）
        vif({ s.currentShot != null && s.currentShot?.status != 2 }) {
            VideoStatusOverlay(s, failedTextKey)
        }
    }
}

private fun ViewContainer<*, *>.VideoStatusOverlay(s: StoryboardState, failedTextKey: String) {
    View {
        // Dart 状态层与播放框同为左右 16 外边距、圆角 16，层内没有额外内边距
        attr { absolutePositionAllZero(); borderRadius(16f); backgroundColor(cFFF10); allCenter() }
        vbind({ "${s.currentShot?.status}|${s.currentShot?.errorMsg}" }) {
            val shot = s.currentShot
            val status = shot?.status ?: 0
            val errorMsg = shot?.errorMsg ?: ""
            if (status == 3) {
                Image { attr { size(80f, 80f); src("images/v2/mvFailedIcon1.png".asset()); resizeContain() } }
            } else {
                StarLoading(80f)
            }
            val text = when (status) {
                0, 1 -> "generating".tr
                3 -> failedTextKey.tr
                else -> ""
            }
            if (text.isNotEmpty()) {
                VGap(4f)
                EvText(text, 14f, UIColor.transparent80, P500, maxLines = 3, center = true)
            }
            if (status == 3) {
                if (errorMsg.isNotEmpty()) {
                    VGap(4f)
                    EvText(errorMsg, 12f, UIColor.transparent80, P500, maxLines = 3, center = true)
                }
                VGap(10f)
                View {
                    attr { flexDirectionRow(); alignItemsCenter() }
                    event { click { s.onFeedback() } }
                    Image { attr { size(16f, 16f); src(AssetsImage.help.asset()); tintColor(Color.WHITE) } }
                    HGap(4f)
                    EvText("feedback".tr, 12f, Color.WHITE, P500) { attr { lineHeight(14.4f) } }
                    Image { attr { size(20f, 20f); marginTop(1f); src(AssetsImage.arrowRight.asset()); tintColor(Color.WHITE) } }
                }
            }
        }
    }
}

// ============================================================================
// 参考图
// ============================================================================

/** Dart `_buildReferenceImages`：最多 4 张 + 添加格，敏感提示。仅 genMode == 2 显示。 */
internal fun ViewContainer<*, *>.ReferenceImages(s: StoryboardState) {
    vbind({ Triple(s.currentShot?.id, s.shotImageMap[s.currentShot?.id.toString()], s.genMode) }) {
        val shot = s.currentShot
        if (shot != null && s.genMode == 2) {
            val images = s.getShotImages(shot.id)
            val slotCount = if (s.shouldShowAddSlot(shot.id)) images.size + 1 else images.size
            val sensitive = images.firstOrNull { it.isHasSensitive }
            View {
                Scroller {
                    attr { height(60f); flexDirectionRow(); showScrollerIndicator(false) }
                    for (i in 0 until slotCount) {
                        if (i > 0) HGap(8f)
                        ReferenceImageSlot(s, shot, images, i)
                    }
                }
                if (sensitive != null) {
                    VGap(8f)
                    EvText(sensitive.errorMsg.ifEmpty { "image_violation".tr }, 12f, Color.RED, maxLines = 3)
                }
                VGap(12f)
            }
        }
    }
}

private fun ViewContainer<*, *>.ReferenceImageSlot(s: StoryboardState, shot: MvShot, images: kotlin.collections.List<ShotImageModel>, index: Int) {
    val isAddSlot = index >= images.size
    val shotImage = if (isAddSlot) null else images[index]
    val coverImg = shotImage?.imageUrl ?: ""
    val image = coverImg.ifEmpty { shotImage?.imageFile ?: "" }
    SDUploadImage {
        attr {
            size(60f, 60f)
            url = image
            status = when {
                shotImage?.imageLoading == true -> SDUploadImageStatus.UPLOADING
                shotImage?.isImageFailed == true -> SDUploadImageStatus.FAILED
                else -> SDUploadImageStatus.NONE
            }
            clearable = !isAddSlot
        }
        event {
            onImageTap { s.openImageOption(coverImg, shot.id, index) }
            onEmptyTap { s.openImageOption(coverImg, shot.id, index) }
            onClear { s.onClearImage(index) }
            onRetry {
                val file = shotImage?.imageFile
                if (file != null) s.pager.launch { s.uploadShotImage(shot.id, index, imageFile = file) }
            }
        }
    }
}

/** 描述输入框下方小胶囊（Dart `_buildPillButton`）。 */
internal fun ViewContainer<*, *>.PillButton(onTap: () -> Unit, body: ViewContainer<*, *>.() -> Unit) {
    View {
        attr { height(32f); padding(0f, 12f, 0f, 12f); backgroundColor(cFFF10); borderRadius(100f); flexDirectionRow(); allCenter() }
        event { click { onTap() } }
        body()
    }
}

// ============================================================================
// 骨架屏
// ============================================================================

/** Dart `_buildSkeletonView`。[withBottom]：编辑页底部有合成按钮占位。 */
internal fun ViewContainer<*, *>.StoryboardSkeleton(withBottom: Boolean) {
    View {
        attr { flex(1f) }
        View {
            attr { flex(1f); padding(0f, 16f, 0f, 16f) }
            View {
                attr { flexDirectionRow(); justifyContentSpaceBetween() }
                SDShimmer { attr { size(100f, 20f); radius = 4f } }
                SDShimmer { attr { size(20f, 20f); radius = 10f } }
            }
            VGap(10f)
            SDShimmer { attr { height(90f); radius = 10f } }
            VGap(16f)
            SDShimmer { attr { height(200f); radius = 10f } }
            VGap(16f)
            SDShimmer { attr { size(80f, 20f); radius = 4f } }
            VGap(8f)
            SDShimmer { attr { size(60f, 60f); radius = 12f } }
            VGap(20f)
            SDShimmer { attr { height(150f); radius = 10f } }
        }
        if (withBottom) {
            View {
                attr { padding(0f, 16f, 0f, 16f) }
                SDShimmer { attr { height(40f); radius = 40f } }
            }
        }
    }
}
