package com.sondo.app.features.edit_video

import com.sondo.app.base.BasePager
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.constants.asset
import com.sondo.app.core.i18n.tr
import com.sondo.app.features.edit_video.state.EditVideoState
import com.sondo.app.features.edit_video.widgets.EvText
import com.sondo.app.features.edit_video.widgets.ExportOptionsPanel
import com.sondo.app.features.edit_video.widgets.P500
import com.sondo.app.features.edit_video.widgets.P600
import com.sondo.app.features.edit_video.widgets.ShotDescriptionCard
import com.sondo.app.features.edit_video.widgets.Spinner
import com.sondo.app.features.edit_video.widgets.StoryboardSkeleton
import com.sondo.app.features.edit_video.widgets.StoryboardTimeline
import com.sondo.app.features.edit_video.widgets.StoryboardVideoArea
import com.sondo.app.features.edit_video.widgets.showFullScreenPlayer
import com.sondo.app.features.edit_video.widgets.showIncompleteShotTip
import com.sondo.app.features.edit_video.widgets.showQualityUpgradeDialog
import com.sondo.app.ui.atoms.HGap
import com.sondo.app.ui.atoms.SafeBottom
import com.sondo.app.ui.dialog.ImagePreviewItem
import com.sondo.app.ui.dialog.showImagePreview
import com.sondo.app.ui.input.SDKeyboardAwareScroll
import com.sondo.app.ui.input.SDKeyboardAwareScrollView
import com.sondo.app.ui.input.SDKeyboardTopInput
import com.sondo.app.ui.input.hideKeyboard
import com.sondo.app.ui.nav.SDNavBar
import com.sondo.app.ui.works.SDNavPoints
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 编辑 MV（分镜精修 + Export Options + 再次合成）。Dart `EditVideoPage` + `EditVideoController`。
 *
 * 参数（pagerData.params）：
 * - `id: Long` MV id（必填）
 * - `index: Int` 进页选中的分镜下标（可选，默认 0）
 * - `type: String` 入口：`"mv_detail"`（成片播放页）/ `"think_line"`（思考线）——合成受理后直接回到根页作品库 Tab；其它值合成后只 pop。
 *
 * 返回结果：删除 MV 成功时 `nav.pop({"result": "deleted"})`；其它情况无结果。
 */
@Page(Routes.editVideo)
internal class EditVideoPage : BasePager() {

    val state by lazy(LazyThreadSafetyMode.NONE) { EditVideoState(this) }
    private var mainScrollRef: ViewRef<SDKeyboardAwareScrollView>? = null

    override fun created() {
        super.created()
        val s = state
        s.onInit(pagerData.params)
        s.fullScreenPresenter = { url, pos, onDownload, onClosed -> showFullScreenPlayer(url, pos, onDownload, onClosed) }
        s.incompleteTipPresenter = { showIncompleteShotTip { s.toStoryboard(0) } }
        s.qualityUpgradePresenter = { point, onClose, onUpgrade -> showQualityUpgradeDialog(point, onClose, onUpgrade) }
        s.imagePreviewPresenter = { url, index ->
            showImagePreview(listOf(ImagePreviewItem.success(url)), index, onDownload = if (url.isNotEmpty()) {
                { item, _ -> s.downloadImage(item.url) }
            } else {
                null
            }, aiStatusStyle = true)
        }
        s.scrollMainToBottom = { scrollMainToBottom() }
        s.onReady()
    }

    private var appearedOnce = false

    override fun pageDidAppear() {
        super.pageDidAppear()
        if (!appearedOnce) {
            appearedOnce = true
            state.onFirstPageAppear()
        }
    }

    override fun pageDidDisappear() {
        super.pageDidDisappear()
        // 被其它页面盖住时暂停分镜（离开期间不出声）
        state.videoPlayer.videoPause()
    }

    override fun pageWillDestroy() {
        state.onClose()
        super.pageWillDestroy()
    }

    private fun scrollMainToBottom() {
        val scroller = mainScrollRef?.view?.scroller ?: return
        val contentH = scroller.contentView?.frame?.height ?: return
        val maxOffset = (contentH - scroller.frame.height).coerceAtLeast(0f)
        scroller.setContentOffset(0f, maxOffset, true)
    }

    override fun content(): ViewBuilder {
        val ctx = this
        val s = ctx.state
        return {
            View {
                attr { flex(1f); backgroundColor(UIColor.pageBg) }
                event { click { ctx.hideKeyboard() } }
                SDNavBar {
                    attr {
                        title = "edit_music_video".tr
                        actions = {
                            SDNavPoints()
                            HGap(12f)
                        }
                    }
                }
                vif({ s.isLoadingMv || s.isLoadingShot }) {
                    StoryboardSkeleton(withBottom = true)
                }
                velse {
                    View {
                        attr { flex(1f) }
                        SDKeyboardAwareScroll {
                            ref { ctx.mainScrollRef = it }
                            attr { flex(1f); bounces = false; marginBottom = 64f }
                            content {
                                StoryboardTimeline(s, bottomGap = true) { s.timelineGuideRef = it }
                                StoryboardVideoArea(s, ctx.screenWidth, "generation_failed_credit_refunded")
                                ShotDescriptionCard(s) { s.editAreaGuideRef = it }
                                ExportOptionsPanel(s, ctx.screenWidth)
                            }
                        }
                        // 合成按钮（引导第四步目标）：Dart Padding.fromLTRB(16, 8, 16, 12)
                        View {
                            attr { padding(8f, 16f, 12f, 16f) }
                            View {
                                ref { s.mergeGuideRef = it }
                                attr { height(44f); borderRadius(22f); backgroundColor(UIColor.cF3EFF7); flexDirectionRow(); allCenter() }
                                event {
                                    click {
                                        if (s.mergeLoading || s.isMerging) {
                                            ctx.toast("video_is_being_merged".tr)
                                        } else {
                                            s.onSynthesis()
                                        }
                                    }
                                }
                                vif({ s.isMerging }) {
                                    View {
                                        attr { flexDirectionRow(); alignItemsCenter() }
                                        Spinner(20f, Color.BLACK, 2f)
                                        HGap(6f)
                                        EvText("merging".tr, 16f, UIColor.c0A0A0A, P500)
                                    }
                                }
                                velse {
                                    View {
                                        attr { flexDirectionRow(); alignItemsCenter() }
                                        EvText("merge_video".tr, 16f, UIColor.c0A0A0A, P500)
                                        HGap(4f)
                                        Image { attr { size(16f, 16f); src(AssetsImage.random3.asset()); tintColor(UIColor.c0A0A0A) } }
                                        HGap(4f)
                                        Text {
                                            attr { fontSize(16f); fontFamily(P600); color(UIColor.c0A0A0A); text("${s.mergePoint}") }
                                        }
                                    }
                                }
                            }
                        }
                        SafeBottom()
                    }
                }
            }
            SDKeyboardTopInput {
                attr {
                    controller = s.keyboardController
                    // 完成按钮 = Generate + 封面重生积分
                    doneWidget = {
                        View {
                            attr {
                                height(32f)
                                padding(0f, 12f, 0f, 12f)
                                borderRadius(29f)
                                backgroundColor(UIColor.cF3EFF7)
                                flexDirectionRow()
                                allCenter()
                            }
                            EvText("generate".tr, 12f, UIColor.c1C1C1C, P600)
                            HGap(4f)
                            Image { attr { size(12f, 12f); src(AssetsImage.random3.asset()); tintColor(UIColor.c1C1C1C) } }
                            HGap(2f)
                            // 价格刷新后要重绘（reCoverPoint 读 pricingTick）
                            vif({ s.reCoverPoint > 0 }) {
                                EvText("", 12f, UIColor.c1C1C1C, P600) { attr { text("${s.reCoverPoint}") } }
                            }
                        }
                    }
                }
            }
        }
    }
}
