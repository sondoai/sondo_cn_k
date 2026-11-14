package com.sondo.app.features.edit_video.widgets

import com.sondo.app.base.BasePager
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.constants.asset
import com.sondo.app.core.i18n.tr
import com.sondo.app.nativeapi.SDVideo
import com.sondo.app.nativeapi.SDVideoState
import com.sondo.app.nativeapi.SDVideoView
import com.sondo.app.service.audio.AppAudioOutputCoordinator
import com.sondo.app.ui.atoms.HGap
import com.sondo.app.ui.atoms.Spacer
import com.sondo.app.ui.atoms.VGap
import com.sondo.app.ui.overlay.DialogEntry
import com.sondo.app.ui.overlay.DialogHandle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.View

/** Dart `openIncompleteShotTip`：分镜缺失提示（got_it / to_complete）。 */
internal fun BasePager.showIncompleteShotTip(onToComplete: () -> Unit): DialogHandle {
  val sw = screenWidth
  return showDialog(
    maskColor = Color(0, 0, 0, 0.54f),
  ) { handle ->
    View {
        attr { margin(0f, 16f, 0f, 16f); width(sw - 32f); borderRadius(16f); backgroundColor(Color.BLACK) }
        View {
            attr { padding(16f, 16f, 16f, 16f); borderRadius(16f); backgroundColor(UIColor.c1C1C1C) }
            EvText("content_missing".tr, 18f, Color(0xCCC685FFL), P600)
            VGap(12f)
            EvText("you_need_compositing_process".tr, 12f, UIColor.transparent80, P500, maxLines = 8)
            VGap(12f)
            View {
                attr { flexDirectionRow() }
                Spacer()
                View {
                    attr { height(30f); padding(0f, 16f, 0f, 16f); borderRadius(100f); backgroundColor(Color(0x3394ADFFL)); allCenter() }
                    event { click { handle.dismiss() } }
                    EvText("got_it".tr, 12f, Color.WHITE, P500)
                }
                HGap(12f)
                View {
                    attr { height(30f); padding(0f, 16f, 0f, 16f); borderRadius(100f); backgroundColor(UIColor.cF3EFF7); allCenter() }
                    event {
                        click {
                            handle.dismiss()
                            onToComplete()
                        }
                    }
                    EvText("to_complete".tr, 12f, UIColor.c0A0A0A, P500)
                }
            }
        }
    }
  }
}

/**
 * Dart `VideoIntroductionWidget(title: 'quality_upgrade', isShowCancelBtn: true)`（music_video 模块的弹窗，
 * 这里只用到「带标题」分支）：关闭 / 取消回调 [onClose]，按钮回调 [onUpgrade]。
 */
internal fun BasePager.showQualityUpgradeDialog(upgradePoint: Int, onClose: () -> Unit, onUpgrade: () -> Unit): DialogHandle {
    val w = screenWidth
    return showDialog(maskColor = UIColor.black90) { handle ->
        View {
            attr { width(w); padding(16f, 16f, 16f, 16f) }
            View {
                attr { padding(16f, 16f, 16f, 16f); backgroundColor(UIColor.c1C1C1C); borderRadius(24f) }
                View {
                    attr { flexDirectionRow(); alignItemsCenter(); justifyContentSpaceBetween() }
                    EvText("quality_upgrade".tr, 16f, UIColor.transparent80, P600)
                    Image {
                        attr { size(24f, 24f); src(AssetsImage.close.asset()) }
                        event {
                            click {
                                handle.dismiss()
                                onClose()
                            }
                        }
                    }
                }
                VGap(16f)
                Image {
                    attr {
                        val iw = w - 64f
                        size(iw, iw * 9f / 16f)
                        borderRadius(10f)
                        src(AssetsImage.imageQuality.asset())
                        resizeCover()
                    }
                }
                VGap(16f)
                EvText(
                    "once_upgraded_to_quality_subsequent_clip_edits_will_use_quality_and_this_change_is_irreversible".tr,
                    12f, UIColor.transparent80, P600, maxLines = 10,
                )
                VGap(16f)
                View {
                    attr { height(48f); borderRadius(48f); backgroundColor(UIColor.cF3EFF7); flexDirectionRow(); allCenter() }
                    event {
                        click {
                            handle.dismiss()
                            onUpgrade()
                        }
                    }
                    EvText("up_to_quality".tr, 16f, UIColor.c0A0A0A, P600)
                    HGap(4f)
                    Image { attr { size(12f, 12f); src(AssetsImage.random3.asset()); tintColor(UIColor.c0A0A0A) } }
                    HGap(2f)
                    EvText("$upgradePoint", 16f, UIColor.c0A0A0A, P600)
                }
                View {
                    attr { marginTop(6f); height(40f); allCenter() }
                    event {
                        click {
                            handle.dismiss()
                            onClose()
                        }
                    }
                    EvText("cancel".tr, 14f, UIColor.cFFF80, P600)
                }
            }
        }
    }
}

// ============================================================================
// 全屏播放
// ============================================================================

internal class FullScreenPlayerAttr : ComposeAttr() {
    var url: String = ""
    var startMs: Long = 0L
    var onDownload: (() -> Unit)? = null
}

/** Dart `onEnterFullScreen` 的底部全屏层：循环播放 + 右侧下载按钮。 */
internal class FullScreenPlayerView : ComposeView<FullScreenPlayerAttr, ComposeEvent>() {
    private var aspect by observable(16f / 9f)
    private var videoRef: ViewRef<SDVideoView>? = null
    private var seeked = false
    private var playing = false
    private val owner = Any()
    private var detach: (() -> Unit)? = null

    override fun createAttr(): FullScreenPlayerAttr = FullScreenPlayerAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun created() {
        super.created()
        detach = AppAudioOutputCoordinator.attach(owner, { playing }, { true }) {
            videoRef?.view?.pause()
            playing = false
        }
    }

    override fun viewDestroyed() {
        detach?.invoke()
        detach = null
        super.viewDestroyed()
    }

    private fun onState(st: SDVideoState) {
        if (st.width > 0 && st.height > 0) aspect = st.width.toFloat() / st.height
        when (st.state) {
            SDVideoState.READY -> if (!seeked) {
                seeked = true
                if (attr.startMs > 0) videoRef?.view?.seekTo(attr.startMs)
                val pager = getPager() as BasePager
                pager.launch {
                    val t = AppAudioOutputCoordinator.claim(owner)
                    if (AppAudioOutputCoordinator.isCurrent(owner, t)) videoRef?.view?.play()
                }
            }
            SDVideoState.PLAYING -> playing = true
            SDVideoState.PAUSED, SDVideoState.COMPLETED, SDVideoState.ERROR -> playing = false
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { absolutePositionAllZero(); backgroundColor(Color.BLACK) }
            View {
                attr {
                    flex(1f)
                    justifyContentCenter()
                    alignItemsCenter()
                    paddingTop(ctx.pagerData.statusBarHeight)
                }
                View {
                    attr {
                        val w = ctx.pagerData.pageViewWidth
                        size(w, w / ctx.aspect)
                    }
                    SDVideo {
                        ref { ctx.videoRef = it }
                        attr {
                            absolutePositionAllZero()
                            src(ctx.attr.url)
                            autoPlay(false)
                            loop(true)
                            fitContain()
                            cache(false)
                        }
                        event { onState { ctx.onState(it) } }
                    }
                }
                VGap(20f)
                EvText("exit_full_screen".tr, 12f, Color(0x99FFFFFFL), P600)
            }
            View {
                attr { absolutePosition(right = 14f, top = 0f, bottom = 0f); justifyContentCenter() }
                View {
                    attr { padding(5f, 5f, 5f, 5f) }
                    event { click { ctx.attr.onDownload?.invoke() } }
                    Image { attr { size(27f, 27f); src(AssetsImage.downloadMv1.asset()); resizeContain() } }
                }
            }
        }
    }
}

/** 打开全屏播放；关闭后回调 [onClosed]（页面恢复分镜播放）。 */
internal fun BasePager.showFullScreenPlayer(url: String, positionMs: Long, onDownload: () -> Unit, onClosed: () -> Unit): DialogHandle =
    showDialog(
        align = DialogEntry.ALIGN_FILL,
        dismissOnMask = false,
        maskColor = Color.BLACK,
        onDismiss = onClosed,
    ) { handle ->
        // Dart 是整屏高的 bottomSheet：点画面没有反应，下拉关闭（文案 exit_full_screen 即「下滑退出全屏」）；返回键仍可关闭
        var panStartY = 0f
        var dismissed = false
        View {
            attr { absolutePositionAllZero() }
            event {
                pan { p ->
                    when (p.state) {
                        "start" -> panStartY = p.pageY
                        "end" -> if (!dismissed && p.pageY - panStartY > 80f) {
                            dismissed = true
                            handle.dismiss()
                        }
                        else -> {}
                    }
                }
            }
            addChild(FullScreenPlayerView()) {
                attr {
                    this.url = url
                    this.startMs = positionMs
                    this.onDownload = onDownload
                }
            }
        }
    }
