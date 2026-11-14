package com.sondo.app.features.edit_video.widgets

import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.constants.asset
import com.sondo.app.core.i18n.tr
import com.sondo.app.features.edit_video.state.EditVideoState
import com.sondo.app.features.edit_video.state.RetryShotsState
import com.sondo.app.features.edit_video.state.StoryboardState
import com.sondo.app.ui.atoms.HGap
import com.sondo.app.ui.atoms.Spacer
import com.sondo.app.ui.atoms.VGap
import com.sondo.app.ui.input.SDTextArea
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.views.DivView
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * Dart `_buildShotDescription`：分镜描述卡（参考图 + 描述输入 + 操作行）。
 * 编辑页操作行：@Tag / History / Recreate|Cancel；重试页只有 Recreate（生成中转圈，不可取消）。
 */
internal fun ViewContainer<*, *>.ShotDescriptionCard(
    s: StoryboardState,
    guideRef: ((ViewRef<DivView>) -> Unit)? = null,
) {
    View {
        if (guideRef != null) ref { guideRef(it) }
        attr { margin(8f, 16f, 16f, 16f) }
        View {
            attr { flexDirectionRow(); alignItemsCenter() }
            EvText("shot_description".tr, 14f, UIColor.transparent80, P600) { attr { flex(1f) } }
            Image {
                attr { size(14f, 14f); src(AssetsImage.clear3.asset()) }
                event { click { s.onClearShotDescription() } }
            }
            HGap(8f)
            Text { attr { fontSize(12f); fontFamily(P500); color(UIColor.transparent30); text("${s.shortDescText.length}/1500") } }
        }
        VGap(12f)
        View {
            attr { padding(16f, 14f, 16f, 14f); backgroundColor(cFFF10); borderRadius(16f) }
            ReferenceImages(s)
            vif({ s.currentShot != null }) {
                SDTextArea {
                    attr {
                        areaHeight = 72f
                        fillColor = Color.TRANSPARENT
                        radius = 0f
                        contentPadding = 0f
                        fontSize = 12f
                        color = Color.WHITE
                        hintColor = UIColor.transparent30
                        maxLength = 1500
                        showCounter = false
                        placeholder = "describe_the_details_of_the_camera_shot".tr
                        text = s.shortDescText
                        val st = s.currentShot?.status ?: 0
                        enabled = !(st == 0 || st == 1)
                    }
                    event { onChanged { s.shortDescText = it } }
                }
            }
            VGap(8f)
            View {
                attr { flexDirectionRow(); alignItemsCenter() }
                if (s is EditVideoState) {
                    PillButton({ s.onShotTag() }) { EvText("@Tag", 10f, Color.WHITE, P600) }
                    HGap(8f)
                    PillButton({ s.onShotHistory() }) {
                        Image { attr { size(16f, 16f); src(AssetsImage.historyIcon.asset()); tintColor(Color.WHITE) } }
                        HGap(6f)
                        EvText("history".tr, 12f, Color.WHITE, P600)
                    }
                    Spacer()
                    EditRecreateChip(s)
                } else if (s is RetryShotsState) {
                    Spacer()
                    RetryRecreateChip(s)
                }
            }
        }
    }
}

private fun chipAttrs(v: com.tencent.kuikly.core.views.DivAttr) {
    v.height(32f)
    v.padding(0f, 12f, 0f, 12f)
    v.backgroundColor(UIColor.cF3EFF7)
    v.borderRadius(100f)
    v.flexDirectionRow()
    v.allCenter()
}

/** 描述框右侧紧凑 Recreate / Cancel。 */
private fun ViewContainer<*, *>.EditRecreateChip(s: EditVideoState) {
    vif({ s.currentShot != null }) {
        View {
            attr { chipAttrs(this) }
            event {
                click {
                    val shot = s.currentShot ?: return@click
                    if (shot.status == 0 || shot.status == 1) {
                        s.onCancelRecreate(shot.id)
                        return@click
                    }
                    if (s.shortDescText.trim().isEmpty()) {
                        s.pager.toast("please_enter_description".tr)
                        return@click
                    }
                    s.onRecreate()
                }
            }
            vif({ s.currentShot?.status == 0 || s.currentShot?.status == 1 }) {
                EvText("cancel".tr, 12f, UIColor.c0A0A0A, P600)
            }
            vif({ !(s.currentShot?.status == 0 || s.currentShot?.status == 1) }) {
                View {
                    attr { flexDirectionRow(); alignItemsCenter() }
                    EvText("recreate".tr, 12f, UIColor.c0A0A0A, P600)
                    HGap(4f)
                    Image { attr { size(12f, 12f); src(AssetsImage.random3.asset()); tintColor(UIColor.c0A0A0A) } }
                    HGap(2f)
                    Text { attr { fontSize(12f); fontFamily(P600); color(UIColor.c0A0A0A); text("${s.point}") } }
                }
            }
        }
    }
}

/** 重试页 Recreate：生成中显示 loading，不可取消；retryNeedPoint 为 0 时不展示积分数。 */
private fun ViewContainer<*, *>.RetryRecreateChip(s: RetryShotsState) {
    vif({ s.currentShot != null }) {
        View {
            attr { chipAttrs(this) }
            event {
                click {
                    val shot = s.currentShot ?: return@click
                    if (shot.status == 0 || shot.status == 1) return@click
                    if (s.shortDescText.trim().isEmpty()) {
                        s.pager.toast("please_enter_description".tr)
                        return@click
                    }
                    s.onRecreate()
                }
            }
            vif({ s.currentShot?.status == 0 || s.currentShot?.status == 1 }) {
                Spinner(16f, UIColor.c0A0A0A, 2f)
            }
            vif({ !(s.currentShot?.status == 0 || s.currentShot?.status == 1) }) {
                View {
                    attr { flexDirectionRow(); alignItemsCenter() }
                    EvText("recreate".tr, 12f, UIColor.c0A0A0A, P600)
                    vif({ (s.currentShot?.retryNeedPoint ?: 0) > 0 }) {
                        View {
                            attr { flexDirectionRow(); alignItemsCenter(); marginLeft(4f) }
                            Image { attr { size(12f, 12f); src(AssetsImage.random3.asset()); tintColor(UIColor.c0A0A0A) } }
                            HGap(2f)
                            Text {
                                attr { fontSize(12f); fontFamily(P600); color(UIColor.c0A0A0A); text("${s.currentShot?.retryNeedPoint ?: 0}") }
                            }
                        }
                    }
                }
            }
        }
    }
}
