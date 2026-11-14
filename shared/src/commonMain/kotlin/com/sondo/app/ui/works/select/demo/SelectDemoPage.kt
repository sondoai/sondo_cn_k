package com.sondo.app.ui.works.select.demo

import com.sondo.app.base.BasePager
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.storage.Sp
import com.sondo.app.data.models.RoleModel
import com.sondo.app.service.MvService
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.nav.SDNavBack
import com.sondo.app.ui.works.cropper.SDAudioCropper
import com.sondo.app.ui.works.cropper.SDAudioCropperView
import com.sondo.app.ui.works.cropper.audioCrop
import com.sondo.app.ui.works.guide.GuideManager
import com.sondo.app.ui.works.guide.GuideStep
import com.sondo.app.ui.works.guide.GuideStepController
import com.sondo.app.ui.works.guide.GuideTipDirection
import com.sondo.app.ui.works.select.MusicSelectResult
import com.sondo.app.ui.works.select.PhotoSelectItem
import com.sondo.app.ui.works.select.PhotoSelectSectionType
import com.sondo.app.ui.works.select.musicSelect
import com.sondo.app.ui.works.select.photoSelect
import com.sondo.app.ui.works.upload.SDCreatorPhotoUploadSection
import com.sondo.app.ui.works.upload.ShotTagTextController
import com.sondo.app.ui.works.upload.showDialogShotTag
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.DivView
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 选择弹窗 / 裁剪 / 引导 / 上传组件演示页（模拟器冒烟）：`nav.push("select_demo")`。
 * 选曲弹窗走真实接口（我的 / 上传需登录）；裁剪器用公网示例音频。
 */
@Page("select_demo")
internal class SelectDemoPage : BasePager() {
    private var log by observable("—")
    private var shotText by observable("")
    private var hasImage by observable(false)
    private var uploading by observable(false)
    private var cropperRef: ViewRef<SDAudioCropperView>? = null
    private var guideTarget1: ViewRef<DivView>? = null
    private var guideTarget2: ViewRef<DivView>? = null

    private val sampleAudio = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            attr {
                paddingTop(ctx.statusBarHeight)
                backgroundColor(SDColors.sheetBg)
            }
            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                }
                SDNavBack()
                Text {
                    attr {
                        text("select_demo")
                        fontSize(16f)
                        color(Color.WHITE)
                    }
                }
            }
            Scroller {
                attr {
                    flex(1f)
                    padding(12f, 20f, 40f, 20f)
                }
                Text {
                    attr {
                        text(ctx.log)
                        fontSize(12f)
                        color(SDColors.white60)
                        lines(4)
                    }
                }
                demoButton("选曲弹窗 showMusicSelect(\"mv\")") {
                    ctx.launch {
                        ctx.log = when (val r = ctx.musicSelect("mv")) {
                            is MusicSelectResult.Song -> "Song: ${r.song.musicTitle} ${r.song.musicAudioUrl}"
                            is MusicSelectResult.Upload -> "Upload started: ${r.item.fileName} (${r.item.uploadId})"
                            MusicSelectResult.TryNow -> "TryNow"
                            null -> "music select cancelled"
                        }
                    }
                }
                demoButton("选图弹窗 usageRecord") {
                    ctx.launch {
                        val items = MvService.roleList.filter { it.roleImg.startsWith("http") }.map { PhotoSelectItem.fromRole(it) } +
                            listOf(
                                PhotoSelectItem.fromRecommend("https://picsum.photos/seed/sd1/200/200"),
                                PhotoSelectItem.fromRecommend("https://picsum.photos/seed/sd2/200/200"),
                            )
                        val r = ctx.photoSelect(PhotoSelectSectionType.usageRecord, items)
                        ctx.log = "photo: ${r?.action} ${r?.imageSource ?: ""} ${r?.selectedItem?.imageUrl ?: ""}"
                    }
                }
                demoButton("选图弹窗 recommend") {
                    ctx.launch {
                        val r = ctx.photoSelect(
                            PhotoSelectSectionType.recommend,
                            listOf(PhotoSelectItem.fromRecommend("https://picsum.photos/seed/sd3/200/200")),
                        )
                        ctx.log = "photo: ${r?.action} ${r?.imageSource ?: ""}"
                    }
                }
                demoButton("裁剪弹层 showAudioCropper") {
                    ctx.launch {
                        val r = ctx.audioCrop(ctx.sampleAudio, "SoundHelix Song 1", title = "Crop")
                        ctx.log = "crop: ${r?.scope} (${r?.durationSeconds}s)"
                    }
                }
                demoButton("分镜标签 showDialogShotTag") {
                    if (MvService.mvTagList.isEmpty()) com.sondo.app.base.AppHost.launch { MvService.getMvTagsList() }
                    ctx.showDialogShotTag { tag ->
                        ctx.shotText = ShotTagTextController.insertTag(ctx.shotText, tag).first
                        ctx.log = "shot text: ${ctx.shotText}"
                    }
                }
                demoButton("引导（2 步，自定义锚点）") {
                    val c = GuideStepController(
                        listOf(
                            GuideStep("demo_1", { ctx.guideTarget1?.view }, "Step 1", "highlight the first box", GuideTipDirection.bottom),
                            GuideStep("demo_2", { ctx.guideTarget2?.view }, "", "tap the box to finish", GuideTipDirection.top,
                                allowTapOverlayNext = false),
                        ),
                    )
                    GuideManager.show(
                        ctx, c,
                        onFinish = { ctx.log = "guide finished" },
                        onClose = { ctx.log = "guide skipped" },
                        onStepChanged = { ctx.log = "guide step $it" },
                    )
                }
                demoButton("裁剪引导（手柄穿透遮罩）") {
                    val cropper = ctx.cropperRef?.view ?: return@demoButton
                    if (!cropper.isEligibleForCropGuide) {
                        ctx.toast("audio not loaded")
                        return@demoButton
                    }
                    val c = GuideStepController(
                        listOf(GuideStep("crop", { cropper.waveformView }, "Crop", "drag handles", GuideTipDirection.bottom)),
                    )
                    GuideManager.show(
                        ctx, c,
                        onFinish = { Sp.setBool(StorageKey.isCropGuide, true) },
                        onClose = { Sp.setBool(StorageKey.isCropGuide, true) },
                        cropHandles = cropper.buildCropGuideHandlesConfig { ctx.log = "guide crop drag end" },
                    )
                }
                View {
                    ref { ctx.guideTarget1 = it }
                    attr {
                        marginTop(16f)
                        height(40f)
                        borderRadius(8f)
                        backgroundColor(SDColors.white10)
                    }
                }
                Text {
                    attr {
                        marginTop(20f)
                        text("SDAudioCropper（内嵌）")
                        fontSize(14f)
                        color(Color.WHITE)
                    }
                }
                View {
                    attr { marginTop(10f) }
                    SDAudioCropper {
                        ref { ctx.cropperRef = it }
                        attr {
                            url = ctx.sampleAudio
                            fileName = "SoundHelix Song 1"
                            defaultSelectionSeconds = 60
                        }
                        event {
                            onChange { s, e, _, _ -> ctx.log = "cropper: $s,$e" }
                            onClear { ctx.log = "cropper cleared" }
                        }
                    }
                }
                Text {
                    attr {
                        marginTop(20f)
                        text("SDCreatorPhotoUploadSection")
                        fontSize(14f)
                        color(Color.WHITE)
                    }
                }
                View {
                    attr { marginTop(10f) }
                    SDCreatorPhotoUploadSection {
                        attr {
                            imageUrl = if (ctx.hasImage) "https://picsum.photos/seed/sd4/200/200" else ""
                            role = RoleModel()
                            isImageLoading = ctx.uploading
                            imageProgress = 40
                        }
                        event {
                            onUploadTap {
                                ctx.hasImage = true
                                ctx.uploading = !ctx.uploading
                            }
                            onClearImage {
                                ctx.hasImage = false
                                ctx.uploading = false
                            }
                            onRetryUpload { ctx.uploading = false }
                        }
                    }
                }
                View {
                    ref { ctx.guideTarget2 = it }
                    attr {
                        marginTop(20f)
                        height(40f)
                        borderRadius(8f)
                        backgroundColor(SDColors.white20)
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.demoButton(title: String, onTap: () -> Unit) {
    View {
        attr {
            marginTop(12f)
            height(44f)
            borderRadius(22f)
            backgroundColor(SDColors.white10)
            allCenter()
        }
        event { click { onTap() } }
        Text {
            attr {
                text(title)
                fontSize(14f)
                color(Color.WHITE)
            }
        }
    }
}
