package com.sondo.app.features.music_video.widgets

import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.c
import com.sondo.app.core.constants.whiteA
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.formFieldBorderColor
import com.sondo.app.data.models.AudioUploadItem
import com.sondo.app.features.music_video.MvCreateForm
import com.sondo.app.features.music_video.MvFormField
import com.sondo.app.ui.atoms.HGap
import com.sondo.app.ui.atoms.SDAssetImage
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDRingProgress
import com.sondo.app.ui.atoms.SDSwitch
import com.sondo.app.ui.atoms.VGap
import com.sondo.app.ui.anim.SDRotating
import com.sondo.app.ui.dialog.showVideoInfoDialog
import com.sondo.app.ui.input.SDInput
import com.sondo.app.ui.works.SDAudioTaskCard
import com.sondo.app.ui.works.cropper.SDAudioCropper
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.Rotate
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.velseif
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/** Dart: presentation/widgets/music_video_gen_colors.dart `MusicVideoGenColors`。 */
object MusicVideoGenColors {
    val bgPrimary = c(0xFF0A0A0AL)
    val btnPrimary = c(0xFFF3EFF7L)
    val btnPrimaryText = c(0xFF0A0A0AL)
    val tabContainer = c(0xCC222222L)
    val ultraGradientStart = c(0xFFC9A1F9L)
    val ultraGradientEnd = c(0xFF7F58F7L)
    val optionsExpandedBg = c(0xFF0A0A0AL)
    val optionsCollapsedBg = c(0xFF181818L)
    val cardFill = Color(0x22, 0x22, 0x22, 0.8f)
    val cardBorder = whiteA(0.1f)
    val cardBorderStrong = whiteA(0.2f)
    val tabSelected = whiteA(0.1f)
    val labelText = Color.WHITE
    val hintText = whiteA(0.4f)
    val secondaryText = whiteA(0.6f)
    val counterText = whiteA(0.3f)
    val actionButtonBg = whiteA(0.1f)
    val iconColor = whiteA(0.8f)
    val singerOverlay = Color(0, 0, 0, 0.6f)
    /** Colors.redAccent */
    val redAccent = c(0xFFFF5252L)
}

private fun sectionBorder(hasError: Boolean): Border =
    Border(if (hasError) 1.5f else 1f, BorderStyle.SOLID, formFieldBorderColor(hasError, MusicVideoGenColors.cardBorder))

/** 区块标题（14 / w600 / 左右 4）。 */
fun ViewContainer<*, *>.MvSectionLabel(text: String) {
    Text {
        attr {
            marginLeft(4f)
            marginRight(4f)
            text(text)
            fontSize(14f)
            fontFamily("Poppins-SemiBold")
            color(MusicVideoGenColors.labelText)
        }
    }
}

// ============================================================================
// 模式切换（标准 / Ultra）—— Dart: create/widgets/mode_segment.dart
// ============================================================================

fun ViewContainer<*, *>.MvModeSegment(form: MvCreateForm) {
    View {
        // 外层不带圆角，NEW 角标才能探出去
        View {
            attr {
                height(42f)
                marginLeft(16f)
                marginRight(16f)
                backgroundColor(MusicVideoGenColors.tabContainer)
                borderRadius(100f)
                flexDirectionRow()
            }
            for (ultra in listOf(false, true)) {
                View {
                    attr {
                        flex(1f)
                        allCenter()
                        borderRadius(100f)
                        val selected = form.isUltraMode == ultra
                        backgroundColor(if (selected) MusicVideoGenColors.tabSelected else Color.TRANSPARENT)
                        animate(Animation.linear(0.2f), form.mvVersion)
                    }
                    event { click { form.setMvMode(if (ultra) "2" else "1") } }
                    Text {
                        attr {
                            text((if (ultra) "ultra" else "premium").tr)
                            fontSize(14f)
                            val selected = form.isUltraMode == ultra
                            fontFamily(if (selected) "Poppins-SemiBold" else "Poppins-Medium")
                            color(if (selected) Color.WHITE else MusicVideoGenColors.secondaryText)
                        }
                    }
                }
            }
        }
        SDAssetImage(AssetsImage.newLabel, 40f, 19f) {
            attr { absolutePosition(top = -10f, right = 40f) }
        }
    }
}

// ============================================================================
// 提交按钮 —— Dart: create/widgets/create_button.dart
// ============================================================================

fun ViewContainer<*, *>.MvCreateButton(form: MvCreateForm, onTap: () -> Unit) {
    View {
        attr {
            height(48f)
            borderRadius(48f)
            backgroundColor(MusicVideoGenColors.btnPrimary)
            flexDirectionRow()
            allCenter()
        }
        event { click { onTap() } }
        Text {
            attr {
                text(form.createLabelKey.tr)
                color(MusicVideoGenColors.btnPrimaryText)
                fontSize(16f)
                fontWeight600()
                lines(1)
            }
        }
        vif({ form.createShowPoints }) {
            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                }
                HGap(10f)
                SDAssetImage(AssetsImage.c1Icon, 16f, tint = MusicVideoGenColors.btnPrimaryText)
                HGap(4f)
                Text {
                    attr {
                        text("${form.requiredPoints}")
                        color(MusicVideoGenColors.btnPrimaryText)
                        fontSize(16f)
                        fontWeight600()
                    }
                }
            }
        }
    }
}

/** 底部提交栏：渐变遮底 + 按钮（Dart `_buildBottomBar`）。 */
fun ViewContainer<*, *>.MvBottomBar(form: MvCreateForm, safeBottom: Float) {
    View {
        attr {
            absolutePosition(left = 0f, right = 0f, bottom = 0f)
            padding(10f, 16f, 4f + safeBottom, 16f)
            backgroundLinearGradient(
                com.tencent.kuikly.core.base.Direction.TO_BOTTOM,
                com.tencent.kuikly.core.base.ColorStop(Color(0x0A, 0x0A, 0x0A, 0f), 0.068f),
                com.tencent.kuikly.core.base.ColorStop(MusicVideoGenColors.bgPrimary, 0.773f),
            )
        }
        MvCreateButton(form) { form.generateMv() }
    }
}

// ============================================================================
// 标题 —— Dart: create/widgets/title_field.dart
// ============================================================================

fun ViewContainer<*, *>.MvTitleField(form: MvCreateForm) {
    View {
        attr {
            height(50f)
            padding(0f, 14f, 0f, 14f)
            backgroundColor(MusicVideoGenColors.cardFill)
            borderRadius(16f)
            flexDirectionRow()
            alignItemsCenter()
        }
        attr { border(sectionBorder(form.invalidField == MvFormField.title)) }
        SDInput {
            attr {
                flex(1f)
                height(50f)
                contentPaddingH = 0f
                fontSize = 12f
                color = Color.WHITE
                hintColor = MusicVideoGenColors.hintText
                placeholder = "enter_mv_title".tr
                maxLength = MvCreateForm.titleMaxLength
                text = form.title
            }
            event { onChanged { form.onTitleChanged(it) } }
        }
        MvClearCounter({ form.title }, MvCreateForm.titleMaxLength) { form.onTitleChanged("") }
    }
}

/** 输入框右侧「清空 + 字数」（无内容时不展示）。 */
fun ViewContainer<*, *>.MvClearCounter(text: () -> String, maxLength: Int, onClear: () -> Unit) {
    vif({ text().isNotEmpty() }) {
        View {
            attr {
                flexDirectionRow()
                alignItemsCenter()
            }
            SDAssetImage(AssetsImage.inputClear, 18f) { event { click { onClear() } } }
            HGap(8f)
            Text {
                attr {
                    text("${text().length}/$maxLength")
                    fontSize(12f)
                    fontFamily("Poppins")
                    color(MusicVideoGenColors.hintText)
                }
            }
        }
    }
}

// ============================================================================
// 选曲 —— Dart: create/widgets/music_section.dart
// ============================================================================

fun ViewContainer<*, *>.MvMusicSection(form: MvCreateForm) {
    View {
        MvSectionLabel("music".tr)
        VGap(10f)
        vif({ form.isShowCropper }) {
            View {
                attr {
                    padding(14f)
                    backgroundColor(MusicVideoGenColors.cardFill)
                    borderRadius(16f)
                }
                attr {
                    val err = form.invalidField == MvFormField.music
                    border(Border(if (err) 1.5f else 1f, BorderStyle.SOLID, if (err) MusicVideoGenColors.redAccent else MusicVideoGenColors.cardBorderStrong))
                }
                // 音频源变化时必须销毁旧裁剪器，避免新歌曲沿用旧时长和裁剪区间。
                vbind({ "${form.selectedSong.id}:${form.selectedSong.musicAudioUrl ?: ""}" }) {
                    val song = form.selectedSong
                    SDAudioCropper {
                        attr {
                            url = song.musicAudioUrl ?: ""
                            fileName = song.musicTitle ?: ""
                            musicCoverUrl = song.musicCoverUrl ?: ""
                            durationMs = form.musicDuration?.let { it * 1000L }
                            defaultSelectionSeconds = form.defaultSelectionSeconds
                            initialSelectionScope = form.subDurationScope
                        }
                        attr { isPauseChooseSong = form.isPauseChooseSong }
                        event {
                            onChange { start, end, startMs, endMs ->
                                form.updateMusicCropScope(song, "$start,$end")
                                form.updateMusicCropDuration(song, ((endMs - startMs) / 1000L).toInt())
                            }
                            onClear { form.clearChooseSong() }
                        }
                    }
                }
            }
        }
        velseif({ form.mvUploadItem != null }) {
            View {
                attr {
                    padding(14f)
                    backgroundColor(MusicVideoGenColors.cardFill)
                    borderRadius(16f)
                }
                attr { border(sectionBorder(form.invalidField == MvFormField.music)) }
                SDAudioTaskCard {
                    attr {
                        item = form.mvUploadItem ?: AudioUploadItem()
                        index = 0
                        playingUploadId = ""
                        isFirstItem = true
                        isLastItem = true
                        isLoadingAudio = false
                        isPlaying = false
                    }
                    event {
                        onPause { form.pauseMvUpload(it) }
                        onResume { form.resumeMvUpload(it) }
                        onRetry { form.retryMvUpload(it) }
                        onRemove { form.onRemoveMvUpload(it) }
                    }
                }
            }
        }
        velse {
            View {
                attr {
                    height(80f)
                    backgroundColor(MusicVideoGenColors.cardFill)
                    borderRadius(16f)
                    allCenter()
                }
                attr { border(sectionBorder(form.invalidField == MvFormField.music)) }
                event { click { form.onChooseSong() } }
                SDAssetImage(AssetsImage.chooseSong, 18f, tint = MusicVideoGenColors.iconColor)
                VGap(8f)
                Text {
                    attr {
                        text("choose_a_song".tr)
                        fontSize(12f)
                        fontFamily("Poppins-Medium")
                        color(MusicVideoGenColors.secondaryText)
                    }
                }
            }
        }
    }
}

// ============================================================================
// 歌手图 —— Dart: create/widgets/singer_section.dart
// ============================================================================

/** 结构键：只在布局（槽数 / 是否有图 / 图源）变化时重建，进度和状态走响应式 attr。 */
private fun singerLayoutKey(form: MvCreateForm): String {
    form.singerRev
    val slots = form.singers
    val parts = slots.joinToString(";") { "${it.hasPreview}|${it.isImageLoading}|${it.fourViewId}|${it.coverUrl}|${it.imageFile ?: ""}" }
    return "${form.maxSingerCount}#$parts"
}

fun ViewContainer<*, *>.MvSingerSection(form: MvCreateForm) {
    View {
        MvSectionLabel("role".tr)
        VGap(10f)
        vbind({ singerLayoutKey(form) }) {
            val slots = form.singers
            val first = slots.firstOrNull()
            // 第一槽无预览且非 loading 时仍画「上传」，避免取消选图后露出空白卡。
            val showEmpty = first == null || (!first.hasPreview && !first.isImageLoading)
            // 开关打开且第一位已成功，才并排展示第二槽。
            val showSecond = form.maxSingerCount > 1 && first != null && first.isReady
            if (showEmpty) {
                MvEmptyUpload(form)
            } else if (!showSecond) {
                View {
                    attr {
                        padding(14f)
                        backgroundColor(MusicVideoGenColors.cardFill)
                        borderRadius(16f)
                    }
                    attr { border(sectionBorder(form.invalidField == MvFormField.cover)) }
                    View {
                        attr { height(80f) }
                        MvSingerPreview(form, 0, fillColumn = false)
                    }
                    MvSlotError(form, 0, 8f)
                }
            } else {
                View {
                    attr {
                        padding(14f)
                        backgroundColor(MusicVideoGenColors.cardFill)
                        borderRadius(16f)
                        flexDirectionRow()
                        alignItemsFlexStart()
                    }
                    attr { border(sectionBorder(form.invalidField == MvFormField.cover)) }
                    View {
                        attr { flex(1f) }
                        View {
                            attr { height(80f) }
                            MvSingerPreview(form, 0, fillColumn = true)
                        }
                        MvSlotError(form, 0, 2f)
                    }
                    HGap(10f)
                    View {
                        attr { flex(1f) }
                        val second = slots.getOrNull(1)
                        if (second != null && second.hasPreview) {
                            View {
                                attr { height(80f) }
                                MvSingerPreview(form, 1, fillColumn = true)
                            }
                            MvSlotError(form, 1, 2f)
                        } else {
                            View {
                                attr {
                                    height(80f)
                                    backgroundColor(MusicVideoGenColors.actionButtonBg)
                                    borderRadius(12f)
                                    allCenter()
                                }
                                event { click { form.openSelectPhoto(1) } }
                                SDAssetImage(AssetsImage.uploadIcon, 18f, tint = MusicVideoGenColors.iconColor)
                                VGap(8f)
                                Text {
                                    attr {
                                        text("upload".tr)
                                        fontSize(10f)
                                        fontFamily("Poppins-Medium")
                                        color(MusicVideoGenColors.secondaryText)
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

private fun ViewContainer<*, *>.MvEmptyUpload(form: MvCreateForm) {
    View {
        attr {
            height(80f)
            backgroundColor(whiteA(0.05f))
            borderRadius(16f)
            allCenter()
        }
        attr {
            // 校验失败改实线红框，与选曲空态一致
            val err = form.invalidField == MvFormField.cover
            border(Border(if (err) 1.5f else 1f, BorderStyle.SOLID, if (err) MusicVideoGenColors.redAccent else whiteA(0.1f)))
        }
        event { click { form.openSelectPhoto(0) } }
        SDAssetImage(AssetsImage.uploadIcon, 20f, tint = MusicVideoGenColors.iconColor)
        VGap(8f)
        Text {
            attr {
                text("upload".tr)
                fontSize(12f)
                fontFamily("Poppins-Medium")
                color(MusicVideoGenColors.secondaryText)
            }
        }
    }
}

/** 违规与上传失败共用卡片下方错误位。 */
private fun ViewContainer<*, *>.MvSlotError(form: MvCreateForm, index: Int, gap: Float) {
    vif({ form.singerRev; (form.singers.getOrNull(index)?.errorMessage ?: "").isNotEmpty() }) {
        Text {
            attr {
                // Dart Column 默认居中：短文案居中，换行后左对齐
                alignSelfCenter()
                marginTop(gap)
                form.singerRev
                text(form.singers.getOrNull(index)?.errorMessage ?: "")
                fontSize(11f)
                color(MusicVideoGenColors.redAccent)
                lines(0)
            }
        }
    }
}

/**
 * 单槽预览：四视图是 16:9 合成图——单槽 145×80 cover，双槽铺满列宽；普通图 80×80。
 * 底部条：左「重新选」、右「删除」；上传中盖进度、失败盖「重试」。
 */
private fun ViewContainer<*, *>.MvSingerPreview(form: MvCreateForm, index: Int, fillColumn: Boolean) {
    val slot = form.singers.getOrNull(index) ?: return
    val isFourView = slot.fourViewId > 0
    val url = when {
        slot.role.id > 0 && slot.role.roleImg.isNotEmpty() -> slot.role.roleImg
        slot.imageUrl.isNotEmpty() -> slot.imageUrl
        else -> slot.imageFile ?: ""
    }
    // Dart _SingerPreviewStack：灰底与底部操作条铺满整卡 / 整列（80 高），图片区居中；
    // 只有四视图图片裁 12 圆角，加载 / 失败遮罩只盖图片区
    View {
        attr {
            absolutePositionAllZero()
            borderRadius(12f)
            backgroundColor(MusicVideoGenColors.actionButtonBg)
        }
        View {
            attr {
                absolutePositionAllZero()
                allCenter()
            }
            View {
                attr {
                    height(80f)
                    if (isFourView && fillColumn) alignSelfStretch() else width(if (isFourView) 145f else 80f)
                    if (isFourView) {
                        borderRadius(12f)
                        overflow(true)
                    }
                }
                SDImage {
                    attr {
                        absolutePositionAllZero()
                        this.url = url
                    }
                }
                // 上传中：黑 45% + 转圈 + 百分比
                vif({ form.singerRev; form.singers.getOrNull(index)?.isImageLoading == true }) {
                    View {
                        attr {
                            absolutePositionAllZero()
                            backgroundColor(Color(0, 0, 0, 0.45f))
                            allCenter()
                        }
                        SDRotating {
                            attr { periodS = 1f }
                            content {
                                SDRingProgress {
                                    attr {
                                        size(36f, 36f)
                                        strokeWidth = 3f
                                        progress = 0.25f
                                        progressColor = Color.WHITE
                                        trackColor = Color.TRANSPARENT
                                    }
                                }
                            }
                        }
                        Text {
                            attr {
                                absolutePositionAllZero()
                                textAlignCenter()
                                lineHeight(80f)
                                form.singerRev
                                text("${form.singers.getOrNull(index)?.imageProgress ?: 0}%")
                                fontSize(10f)
                                color(Color.WHITE)
                            }
                        }
                    }
                }
                velseif({ form.singerRev; form.singers.getOrNull(index)?.isImageFailed == true }) {
                    View {
                        attr {
                            absolutePositionAllZero()
                            backgroundColor(Color(0, 0, 0, 0.45f))
                            allCenter()
                        }
                        event { click { form.retryUpload(index) } }
                        Text {
                            attr {
                                text("retry".tr)
                                fontSize(12f)
                                color(Color.WHITE)
                            }
                        }
                    }
                }
            }
        }
        vif({ form.singerRev; form.singers.getOrNull(index)?.isImageLoading != true }) {
            View {
                attr {
                    absolutePosition(left = 0f, right = 0f, bottom = 0f)
                    height(20f)
                    padding(2f, 4f, 2f, 4f)
                    backgroundColor(MusicVideoGenColors.singerOverlay)
                    borderRadiusBottom12()
                    allCenter()
                }
                View {
                    attr {
                        width(80f)
                        height(16f)
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    View {
                        attr {
                            flex(1f)
                            height(16f)
                            justifyContentCenter()
                            alignItemsFlexStart()
                        }
                        event { click { form.openSelectPhoto(index) } }
                        SDAssetImage(AssetsImage.musicVideoGenReload, 12f)
                    }
                    View { attr { size(1f, 10f); backgroundColor(whiteA(0.2f)) } }
                    View {
                        attr {
                            flex(1f)
                            height(16f)
                            justifyContentCenter()
                            alignItemsFlexEnd()
                        }
                        event { click { form.clearSingerImage(index) } }
                        SDAssetImage(AssetsImage.aDelete, 12f)
                    }
                }
            }
        }
    }
}

private fun com.tencent.kuikly.core.base.Attr.borderRadiusBottom12() {
    borderRadius(com.tencent.kuikly.core.base.BorderRectRadius(0f, 0f, 12f, 12f))
}

// ============================================================================
// 高级选项 —— Dart: create/widgets/options_panel.dart
// ============================================================================

fun ViewContainer<*, *>.MvOptionsPanel(form: MvCreateForm) {
    View {
        attr {
            backgroundColor(MusicVideoGenColors.cardFill)
            border(Border(1f, BorderStyle.SOLID, MusicVideoGenColors.cardBorder))
            borderRadius(16f)
        }
        View {
            attr {
                height(50f)
                padding(0f, 14f, 0f, 14f)
                borderRadius(16f)
                flexDirectionRow()
                alignItemsCenter()
            }
            attr { backgroundColor(if (form.optionsExpanded) Color.TRANSPARENT else MusicVideoGenColors.optionsCollapsedBg) }
            event { click { form.toggleOptions() } }
            Text {
                attr {
                    flex(1f)
                    text("options".tr)
                    fontSize(14f)
                    fontFamily("Poppins-SemiBold")
                    color(MusicVideoGenColors.labelText)
                }
            }
            vif({ !form.optionsExpanded }) {
                View {
                    attr {
                        flexDirectionRow()
                        alignItemsCenter()
                        marginRight(12f)
                    }
                    MvSummaryChip { form.videoAspectRatio }
                    View { attr { size(1f, 12f); margin(0f, 10f, 0f, 10f); backgroundColor(whiteA(0.2f)) } }
                    MvSummaryChip { if (form.quality == "720") "mv_gen_quality".tr else "mv_gen_standard".tr }
                }
            }
            View {
                attr {
                    padding(3f)
                    backgroundColor(MusicVideoGenColors.actionButtonBg)
                    borderRadius(256f)
                }
                SDAssetImage(AssetsImage.arrowDown1, 14f) {
                    attr {
                        transform(Rotate(if (form.optionsExpanded) 180f else 0f))
                        animate(Animation.easeInOut(0.2f), form.optionsExpanded)
                    }
                }
            }
        }
        vif({ form.optionsExpanded }) {
            View {
                attr {
                    padding(14f, 14f, 16f, 14f)
                    backgroundColor(MusicVideoGenColors.optionsExpandedBg)
                    borderRadius(com.tencent.kuikly.core.base.BorderRectRadius(0f, 0f, 16f, 16f))
                }
                // Dart 只有顶部 1 像素分隔线（Border(top:)），左右下由外层描边负责
                View {
                    attr {
                        absolutePosition(top = 0f, left = 0f, right = 0f)
                        height(1f)
                        backgroundColor(MusicVideoGenColors.cardBorder)
                    }
                }
                MvOptionLabel("name".tr)
                VGap(12f)
                View {
                    attr {
                        height(50f)
                        padding(0f, 12f, 0f, 12f)
                        backgroundColor(MusicVideoGenColors.actionButtonBg)
                        borderRadius(12f)
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    attr {
                        val err = form.invalidField == MvFormField.author
                        border(Border(if (err) 1.5f else 0f, BorderStyle.SOLID, if (err) MusicVideoGenColors.redAccent else Color.TRANSPARENT))
                    }
                    SDInput {
                        attr {
                            flex(1f)
                            height(50f)
                            contentPaddingH = 0f
                            fontSize = 12f
                            color = Color.WHITE
                            hintColor = MusicVideoGenColors.hintText
                            placeholder = "enter_author_name".tr
                            maxLength = MvCreateForm.authorMaxLength
                            text = form.author
                        }
                        event { onChanged { form.onAuthorChanged(it) } }
                    }
                    MvClearCounter({ form.author }, MvCreateForm.authorMaxLength) { form.onAuthorChanged("") }
                }
                VGap(16f)
                MvOptionLabel("aspect_ratio".tr)
                VGap(12f)
                View {
                    attr { flexDirectionRow() }
                    MvOptionChip("9:16", AssetsImage.r9_16, 16f, { form.videoAspectRatio == "9:16" }, width = 80f) { form.updateVideoAspectRatio("9:16") }
                    HGap(10f)
                    MvOptionChip("16:9", AssetsImage.r16_9, 16f, { form.videoAspectRatio == "16:9" }, width = 80f) { form.updateVideoAspectRatio("16:9") }
                }
                VGap(16f)
                MvOptionLabel("video_parameters".tr)
                VGap(12f)
                View {
                    attr { flexDirectionRow() }
                    MvOptionChip("mv_gen_standard".tr, AssetsImage.sd, 18f, { form.quality == "480" }) { form.updateQuality("480") }
                    HGap(8f)
                    MvOptionChip("mv_gen_quality".tr, AssetsImage.hd, 18f, { form.quality == "720" }) { form.updateQuality("720") }
                }
                VGap(16f)
                // 需要不是 Ultra 模式，并且配置了显示特效开关
                vif({ !form.isUltraMode && form.mvSpecialEffectsOpen }) {
                    View {
                        MvSwitchRow(
                            label = "effects".tr,
                            value = { form.isEffectsEnabled },
                            onChanged = { form.changeEffects(it) },
                            onInfoTap = {
                                // Effects 说明：问号弹出视频预览；Try 走开关开启逻辑（含登录/订阅校验）
                                form.pager.showVideoInfoDialog(
                                    title = "effects_info_title".tr,
                                    videoUrl = "https://cdn.sondo.ai/trial/qhy_nb.mp4",
                                    description = "effects_info_description".tr,
                                    onTry = { form.changeEffects(true) },
                                )
                            },
                        )
                        VGap(8f)
                    }
                }
                MvSwitchRow("subtitles".tr, { form.isSubtitles }, { form.changeSubtitles(it) }, null)
            }
        }
    }
}

private fun ViewContainer<*, *>.MvSummaryChip(text: () -> String) {
    Text {
        attr {
            text(text())
            fontSize(12f)
            fontFamily("Poppins-Medium")
            color(MusicVideoGenColors.labelText)
        }
    }
}

private fun ViewContainer<*, *>.MvOptionLabel(text: String) {
    Text {
        attr {
            text(text)
            fontSize(14f)
            fontFamily("Poppins-Medium")
            color(MusicVideoGenColors.labelText)
        }
    }
}

/** 画幅 / 清晰度选项卡：60 高、白 10% 底、选中白描边。[width] 为 null 时平分。 */
private fun ViewContainer<*, *>.MvOptionChip(
    label: String,
    icon: String,
    iconSize: Float,
    selected: () -> Boolean,
    width: Float? = null,
    onTap: () -> Unit,
) {
    View {
        attr {
            if (width != null) width(width) else flex(1f)
            height(60f)
            backgroundColor(MusicVideoGenColors.actionButtonBg)
            borderRadius(10f)
            allCenter()
            border(Border(1f, BorderStyle.SOLID, if (selected()) Color.WHITE else Color.TRANSPARENT))
        }
        event { click { onTap() } }
        Image {
            attr {
                size(iconSize, iconSize)
                src(com.tencent.kuikly.core.base.attr.ImageUri.commonAssets(icon))
                resizeContain()
                tintColor(if (selected()) MusicVideoGenColors.labelText else MusicVideoGenColors.secondaryText)
            }
        }
        VGap(4f)
        Text {
            attr {
                text(label)
                fontSize(12f)
                fontFamily("Poppins-Medium")
                color(if (selected()) MusicVideoGenColors.labelText else MusicVideoGenColors.secondaryText)
            }
        }
    }
}

private fun ViewContainer<*, *>.MvSwitchRow(
    label: String,
    value: () -> Boolean,
    onChanged: (Boolean) -> Unit,
    onInfoTap: (() -> Unit)?,
) {
    View {
        attr {
            height(40f)
            flexDirectionRow()
            alignItemsCenter()
            justifyContentSpaceBetween()
        }
        View {
            attr {
                flexDirectionRow()
                alignItemsCenter()
            }
            Text {
                attr {
                    text(label)
                    fontSize(14f)
                    fontFamily("Poppins-Medium")
                    color(MusicVideoGenColors.labelText)
                }
            }
            if (onInfoTap != null) {
                HGap(4f)
                SDAssetImage(AssetsImage.infoIcon, 16f, tint = MusicVideoGenColors.labelText) {
                    event { click { onInfoTap() } }
                }
            }
        }
        // Dart Stack(centerRight)：大小由开关的 51×31 布局盒决定，ON/OFF 用 Positioned(left: 0) 叠在盒内左侧
        View {
            attr { size(51f, 31f) }
            View {
                attr {
                    absolutePosition(top = 0f, left = 0f, bottom = 0f)
                    justifyContentCenter()
                }
                Text {
                    attr {
                        text(if (value()) "ON" else "OFF")
                        fontSize(12f)
                        fontFamily("Poppins")
                        color(MusicVideoGenColors.labelText)
                    }
                }
            }
            SDSwitch {
                attr {
                    alignment = 1
                    this.value = value()
                }
                event { onChanged { onChanged(it) } }
            }
        }
    }
}

