package com.sondo.app.features.edit_video.widgets

import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.constants.asset
import com.sondo.app.core.i18n.tr
import com.sondo.app.features.edit_video.state.EditVideoState
import com.sondo.app.service.ConfigService
import com.sondo.app.ui.atoms.HGap
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDSwitch
import com.sondo.app.ui.atoms.VGap
import com.sondo.app.ui.input.SDInput
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.Rotate
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

private val white38 = Color(0x61FFFFFFL)
private val rowBg = Color(0x1AFFFFFFL)

/** Dart `ExportOptionsPanel`：封面 / 元数据 / 开关，合成按钮在页底。 */
internal fun ViewContainer<*, *>.ExportOptionsPanel(s: EditVideoState, screenWidth: Float) {
    View {
        attr { padding(8f, 16f, 12f, 16f) }
        View {
            attr {
                backgroundColor(Color(0xCC222222L))
                border(Border(1f, BorderStyle.SOLID, UIColor.transparent10))
                borderRadius(16f)
            }
            // 折叠标题栏（引导第三步目标）
            View {
                ref { s.optionsGuideRef = it }
                attr {
                    height(50f)
                    padding(0f, 14f, 0f, 14f)
                    flexDirectionRow()
                    alignItemsCenter()
                    borderRadius(16f)
                    backgroundColor(if (s.exportOptionsExpanded) Color.TRANSPARENT else Color(0xFF181818L))
                }
                event { click { s.toggleExportOptions() } }
                EvText("options".tr, 14f, Color.WHITE, P600) { attr { flex(1f) } }
                View {
                    attr { padding(3f, 3f, 3f, 3f); backgroundColor(UIColor.transparent10); borderRadius(256f) }
                    Image {
                        attr {
                            size(14f, 14f)
                            src(AssetsImage.arrowDown1.asset())
                            transform(Rotate(if (s.exportOptionsExpanded) 180f else 0f))
                            animate(Animation.linear(0.2f), s.exportOptionsExpanded)
                        }
                    }
                }
            }
            vif({ s.exportOptionsExpanded }) {
                View { attr { height(1f); backgroundColor(UIColor.transparent10) } }
                View {
                    attr {
                        padding(14f, 14f, 16f, 14f)
                        backgroundColor(Color(0xFF0A0A0AL))
                        borderRadius(0f, 0f, 16f, 16f)
                    }
                    CoverBox(s)
                    VGap(16f)
                    vif({ s.mvDetail.genMode == 2 }) {
                        View {
                            SwitchWithInput(
                                s,
                                label = "cover_description".tr,
                                switchValue = { false },
                                onSwitchChanged = {},
                                text = { s.coverDescriptionText },
                                onTextChanged = { s.updateCoverDescription(it) },
                                hintText = "enter_cover_description".tr,
                                maxLength = s.maxCoverDescriptionLength,
                                isReadOnly = true,
                                onTap = { s.openCoverDescriptionInput() },
                                rightChild = { GenerateCoverBtn(s) },
                            )
                            VGap(16f)
                        }
                    }
                    vif({ s.mvDetail.genMode == 2 && s.qualityInt != 720 }) {
                        View {
                            attr { marginBottom(16f); borderRadius(16f) }
                            event { click { s.upgradeQuality() } }
                            Image {
                                attr {
                                    val w = screenWidth - 32f - 28f - 2f
                                    size(w, w / 4f)
                                    src(AssetsImage.qualityUpgrade.asset())
                                    resizeCover()
                                }
                            }
                        }
                    }
                    SwitchWithInput(
                        s,
                        label = "mv_title".tr,
                        switchValue = { s.mvTitleEnabled },
                        onSwitchChanged = { s.toggleMvTitle(it) },
                        text = { s.mvTitleText },
                        onTextChanged = { s.updateMvTitle(it) },
                        hintText = "enter_mv_title".tr,
                        maxLength = s.maxTitleLength,
                    )
                    VGap(10f)
                    SwitchWithInput(
                        s,
                        label = "author_name".tr,
                        switchValue = { s.authorNameEnabled },
                        onSwitchChanged = { s.toggleAuthorName(it) },
                        text = { s.authorNameText },
                        onTextChanged = { s.updateAuthorName(it) },
                        hintText = "enter_author_name".tr,
                        maxLength = s.maxAuthorNameLength,
                    )
                    VGap(10f)
                    vif({ s.mvDetail.capcutSwitchVisible && ConfigService.mvSpecialEffectsOpen }) {
                        View {
                            SwitchRow("effects".tr, { s.capcutEnabled }, { s.toggleCapcut(it) }, isBg = true)
                            VGap(10f)
                        }
                    }
                    vif({ s.mvDetail.isLyric != null }) {
                        SwitchRow("lyrics_&_subtitles".tr, { s.lyricsSubtitlesEnabled }, { s.toggleLyricsSubtitles(it) }, isBg = true)
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.CoverBox(s: EditVideoState) {
    View {
        attr { height(200f); borderRadius(16f); backgroundColor(UIColor.transparent10) }
        event { click { s.showImagePreviewDialog(s.mvDetail.firstFrameImg ?: "", 0) } }
        SDImage { attr { absolutePositionAllZero(); url = s.mvDetail.firstFrameImg ?: "" } }
        vif({ s.coverStatus == 1 || s.coverStatus == 3 }) {
            View {
                attr { absolutePositionAllZero(); backgroundColor(cFFF10); allCenter() }
                vif({ s.coverStatus == 3 }) {
                    Image { attr { size(80f, 80f); src(AssetsImage.mvFailedIcon.asset()); resizeContain() } }
                }
                vif({ s.coverStatus != 3 }) { StarLoading(80f) }
                VGap(4f)
                Text {
                    attr {
                        fontSize(14f)
                        color(UIColor.transparent80)
                        fontFamily(P500)
                        lines(3)
                        textAlignCenter()
                        text(if (s.coverStatus == 1) "generating".tr else "generate_failed".tr)
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.GenerateCoverBtn(s: EditVideoState) {
    View {
        attr {
            height(32f)
            marginBottom(10f)
            padding(0f, 12f, 0f, 12f)
            borderRadius(29f)
            backgroundColor(UIColor.cF3EFF7)
            flexDirectionRow()
            allCenter()
        }
        event { click { s.refreshCover() } }
        EvText("generate".tr, 12f, UIColor.c0A0A0A, P600)
        HGap(4f)
        Image { attr { size(12f, 12f); src(AssetsImage.random3.asset()); tintColor(UIColor.c0A0A0A) } }
        vif({ s.reCoverPoint > 0 }) {
            View {
                attr { flexDirectionRow(); marginLeft(2f) }
                Text { attr { fontSize(12f); fontFamily(P600); color(UIColor.c0A0A0A); text("${s.reCoverPoint}") } }
            }
        }
    }
}

/** [isBg] 仅独立开关行（特效 / 歌词）铺灰底；[rightChild] 有值时替换开关。 */
private fun ViewContainer<*, *>.SwitchRow(
    label: String,
    value: () -> Boolean,
    onChanged: (Boolean) -> Unit,
    isBg: Boolean = false,
    rightChild: (ViewContainer<*, *>.() -> Unit)? = null,
) {
    View {
        attr {
            flexDirectionRow()
            alignItemsCenter()
            justifyContentSpaceBetween()
            if (isBg) {
                padding(4f, 16f, 4f, 16f)
                backgroundColor(rowBg)
                borderRadius(16f)
            }
        }
        EvText(label, 14f, Color.WHITE, P600)
        if (rightChild != null) {
            rightChild()
        } else {
            View {
                attr { size(120f, 31f) }
                Text {
                    attr {
                        absolutePosition(right = 40f, top = 7f)
                        fontSize(12f)
                        fontFamily(P500)
                        color(Color(0xB3FFFFFFL))
                        text(if (value()) "ON" else "OFF")
                    }
                }
                SDSwitch {
                    attr {
                        absolutePosition(right = 0f, top = 0f)
                        this.value = value()
                        alignment = 1
                    }
                    event { onChanged { onChanged(it) } }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.SwitchWithInput(
    s: EditVideoState,
    label: String,
    switchValue: () -> Boolean,
    onSwitchChanged: (Boolean) -> Unit,
    text: () -> String,
    onTextChanged: (String) -> Unit,
    hintText: String,
    maxLength: Int,
    isReadOnly: Boolean = false,
    onTap: (() -> Unit)? = null,
    rightChild: (ViewContainer<*, *>.() -> Unit)? = null,
) {
    View {
        SwitchRow(label, switchValue, onSwitchChanged, rightChild = rightChild)
        View {
            attr {
                padding(4f, 16f, 4f, 16f)
                backgroundColor(rowBg)
                borderRadius(16f)
                flexDirectionRow()
                alignItemsCenter()
            }
            event { click { onTap?.invoke() } }
            if (isReadOnly) {
                View {
                    attr { flex(1f); height(44f); justifyContentCenter() }
                    Text {
                        attr {
                            fontSize(14f)
                            lines(1)
                            textOverFlowTail()
                            val t = text()
                            text(t.ifEmpty { hintText })
                            color(if (t.isEmpty()) white38 else Color.WHITE)
                        }
                    }
                }
            } else {
                SDInput {
                    attr {
                        flex(1f)
                        height(44f)
                        this.text = text()
                        placeholder = hintText
                        this.maxLength = maxLength
                        fillColor = Color.TRANSPARENT
                        fontSize = 14f
                        hintColor = white38
                        contentPaddingH = 0f
                    }
                    event { onChanged { onTextChanged(it) } }
                }
            }
            HGap(4f)
            Text { attr { fontSize(12f); color(white38); text("${text().length}/$maxLength") } }
        }
    }
}
