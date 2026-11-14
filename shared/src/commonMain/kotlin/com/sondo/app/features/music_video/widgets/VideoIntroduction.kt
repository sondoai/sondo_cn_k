package com.sondo.app.features.music_video.widgets

import com.sondo.app.base.BasePager
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.storage.Sp
import com.sondo.app.ui.atoms.SDAssetImage
import com.sondo.app.ui.atoms.VGap
import com.sondo.app.ui.overlay.DialogEntry
import com.sondo.app.ui.overlay.DialogHandle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 画质说明弹窗（Dart presentation/widgets/video_introduction.dart `VideoIntroductionWidget`，经 `SmartDialog.show` 展示）。
 * 作品库 MV 详情（升级画质）、编辑页 / 重试分镜页使用。
 *
 * @param title 有标题时显示标题行 + 关闭按钮和「升级后不可逆」文案；为 null 时是创作页样式（两段标准/高清说明，
 *   点主按钮后写 `StorageKey.isShowVideoQuality = false`，不再弹出）
 * @param btnContent 自定义主按钮内容；为空时显示 `ok`
 * @param maskColor 遮罩色（retry_shots 用黑 90%）
 */
fun BasePager.showVideoIntroduction(
    title: String? = null,
    isShowBtn: Boolean = true,
    isShowCancelBtn: Boolean = false,
    maskColor: Color = Color(0, 0, 0, 0.6f),
    btnContent: (ViewContainer<*, *>.() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    onBtnTap: (() -> Unit)? = null,
): DialogHandle {
    val cardWidth = screenWidth - 32f
    return showDialog(align = DialogEntry.ALIGN_CENTER, maskColor = maskColor) { handle ->
        View {
            attr {
                width(cardWidth)
                padding(16f)
                backgroundColor(UIColor.c1C1C1C)
                borderRadius(24f)
            }
            if (title != null) {
                View {
                    attr {
                        flexDirectionRow()
                        alignItemsCenter()
                        justifyContentSpaceBetween()
                    }
                    Text {
                        attr {
                            text(title)
                            color(Color(0xCCFFFFFFL))
                            fontSize(16f)
                            fontFamily("Poppins-SemiBold")
                            lines(1)
                        }
                    }
                    SDAssetImage(AssetsImage.close, 24f) {
                        event {
                            click {
                                handle.dismiss()
                                onClose?.invoke()
                            }
                        }
                    }
                }
                VGap(16f)
            }
            Image {
                attr {
                    width(cardWidth - 32f)
                    height((cardWidth - 32f) * 9f / 16f)
                    borderRadius(10f)
                    src(ImageUri.commonAssets(AssetsImage.imageQuality))
                    resizeCover()
                }
            }
            VGap(16f)
            val texts = if (title != null) {
                listOf("once_upgraded_to_quality_subsequent_clip_edits_will_use_quality_and_this_change_is_irreversible".tr)
            } else {
                listOf("upgrade_to_quality_irreversible".tr, "standard_regular_points_quality_double_points".tr)
            }
            for (t in texts) {
                Text {
                    attr {
                        text(t)
                        color(Color(0xCCFFFFFFL))
                        fontSize(12f)
                        fontFamily("Poppins-SemiBold")
                        textAlignLeft()
                        lines(if (title != null) 10 else 5)
                    }
                }
            }
            VGap(16f)
            if (isShowBtn) {
                View {
                    attr {
                        height(48f)
                        borderRadius(48f)
                        backgroundColor(UIColor.cF3EFF7)
                        flexDirectionRow()
                        allCenter()
                    }
                    event {
                        click {
                            // 没有标题 = 创建 MV 时显示的，关闭后不再显示
                            if (title == null) Sp.setBool(StorageKey.isShowVideoQuality, false)
                            handle.dismiss()
                            onBtnTap?.invoke()
                        }
                    }
                    if (btnContent != null) {
                        btnContent()
                    } else {
                        Text {
                            attr {
                                text("ok".tr)
                                color(UIColor.c0A0A0A)
                                fontSize(16f)
                                fontFamily("Poppins-SemiBold")
                            }
                        }
                    }
                }
            }
            if (isShowCancelBtn) {
                View {
                    attr {
                        marginTop(6f)
                        height(40f)
                        allCenter()
                    }
                    event {
                        click {
                            handle.dismiss()
                            onClose?.invoke()
                        }
                    }
                    Text {
                        attr {
                            text("cancel".tr)
                            color(UIColor.cFFF80)
                            fontSize(14f)
                            fontFamily("Poppins-SemiBold")
                        }
                    }
                }
            }
        }
    }
}
