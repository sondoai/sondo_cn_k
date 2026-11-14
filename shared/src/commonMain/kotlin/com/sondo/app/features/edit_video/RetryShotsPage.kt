package com.sondo.app.features.edit_video

import com.sondo.app.base.BasePager
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.i18n.tr
import com.sondo.app.features.edit_video.state.RetryShotsState
import com.sondo.app.features.edit_video.widgets.ShotDescriptionCard
import com.sondo.app.features.edit_video.widgets.StoryboardSkeleton
import com.sondo.app.features.edit_video.widgets.StoryboardTimeline
import com.sondo.app.features.edit_video.widgets.StoryboardVideoArea
import com.sondo.app.features.edit_video.widgets.showFullScreenPlayer
import com.sondo.app.ui.atoms.HGap
import com.sondo.app.ui.atoms.SDGradientButton
import com.sondo.app.ui.atoms.SafeBottom
import com.sondo.app.ui.input.SDKeyboardAwareScroll
import com.sondo.app.ui.input.hideKeyboard
import com.sondo.app.ui.nav.SDNavBar
import com.sondo.app.ui.works.SDNavPoints
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.views.View

/**
 * 思考线失败分镜重试页（Dart `RetryShotsPage` + `RetryShotsController`）：复刻编辑页分镜精修，不含导出选项与合成。
 * 由思考线错误卡片（route = retryFailedShots）打开。
 *
 * 参数（pagerData.params，同 Dart `Get.arguments`）：
 * - `id: Long` MV 任务 id（必填）
 * - `index: Int` 可选；本页忽略，进页总是落在第一个失败镜头（没有失败则 0）
 *
 * 无返回结果。Recreate 受理后广播 [com.sondo.app.base.Events.MV_SHOT_RETRIED] `{mvId}`，思考线页据此恢复轮询。
 */
@Page(Routes.retryShots)
internal class RetryShotsPage : BasePager() {

    val state by lazy(LazyThreadSafetyMode.NONE) { RetryShotsState(this) }

    override fun created() {
        super.created()
        val s = state
        s.onInit(pagerData.params)
        s.fullScreenPresenter = { url, pos, onDownload, onClosed -> showFullScreenPlayer(url, pos, onDownload, onClosed) }
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
        state.videoPlayer.videoPause()
    }

    override fun pageWillDestroy() {
        state.onClose()
        super.pageWillDestroy()
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
                    event { onBack { s.popRetryPage() } }
                }
                vif({ s.isLoadingMv || s.isLoadingShot }) {
                    StoryboardSkeleton(withBottom = false)
                }
                velse {
                    View {
                        attr { flex(1f) }
                        SDKeyboardAwareScroll {
                            attr { flex(1f) }
                            content {
                                StoryboardTimeline(s, bottomGap = true)
                                StoryboardVideoArea(s, ctx.screenWidth, "generation_failed")
                                ShotDescriptionCard(s)
                            }
                        }
                        // 仅有其他失败镜头时显示 Next
                        vif({ s.showNextFailed }) {
                            SDGradientButton {
                                attr {
                                    padding(10f, 16f, 10f, 16f)
                                    height(44f)
                                    text = "next".tr
                                }
                                event { onTap { s.onNextFailedShot() } }
                            }
                        }
                        SafeBottom()
                    }
                }
            }
        }
    }
}
