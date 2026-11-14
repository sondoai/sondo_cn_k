package com.sondo.app.ui.dialog

import com.sondo.app.base.BasePager
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.nowMs
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.SDFont
import com.sondo.app.ui.anim.SDAnimatedBell
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDMusicLoading
import com.sondo.app.ui.atoms.SDProgressBar
import com.sondo.app.ui.overlay.BannerHandle
import com.sondo.app.ui.overlay.DialogEntry
import com.sondo.app.ui.overlay.DialogHandle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Blur
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

// ============================================================================
// Loading（Dart LoadingUtil）
// ============================================================================

/**
 * Dart `LoadingUtil.show(clickMaskDismiss:, text:)`：全局 Loading（music_loading 动画）。
 * `BasePager.showLoading(text)` 的增强版；关闭用 `hideLoading()`（对应 `LoadingUtil.close()`）。
 *
 * @param clickMaskDismiss 点遮罩是否关闭
 * @param blockBack 返回键不关闭 Loading（Dart `isBackTypeBlock`）；默认返回键关闭 Loading、页面不动
 */
fun BasePager.showLoadingDialog(text: String = "", clickMaskDismiss: Boolean = false, blockBack: Boolean = false) {
    overlay.showLoading(text, clickMaskDismiss, blockBack)
}

/** Dart `LoadingUtil.isOpenLoading()`。 */
fun BasePager.isLoadingShowing(): Boolean = overlay.isLoadingShowing()

/** Dart `LoadingUtil.close()`：有 Loading 才关（强制清零计数）。 */
fun BasePager.closeLoading() {
    if (overlay.isLoadingShowing()) overlay.hideLoading(force = true)
}

/**
 * 带进度的 Loading 弹窗的状态：页面持有它，随时改 [message] / [progress]，UI 自动刷新。
 */
class SDProgressDialogHandle internal constructor(scope: PagerScope) {
    /** 提示文案 */
    var message: String by scope.observable("")
    /** 进度 0..1；负数 = 不确定进度（只显示动画） */
    var progress: Float by scope.observable(-1f)
    internal var dialog: DialogHandle? = null

    val isShowing: Boolean get() = dialog?.isShowing ?: false

    fun dismiss() {
        dialog?.dismiss()
    }
}

/**
 * 带文案 / 进度条的 Loading 弹窗（下载、导出、合成等耗时操作）。
 *
 * ```
 * val loading = showProgressDialog("loading".tr)
 * loading.progress = 0.35f; loading.message = "download_to_storage".trParams("value" to "35%")
 * loading.dismiss()
 * ```
 * @param cancelable 点遮罩 / 返回键是否可取消；取消时回调 [onCancel]
 */
fun BasePager.showProgressDialog(
    message: String = "",
    progress: Float = -1f,
    cancelable: Boolean = false,
    onCancel: (() -> Unit)? = null,
): SDProgressDialogHandle {
    val state = SDProgressDialogHandle(this)
    state.message = message
    state.progress = progress
    state.dialog = showDialog(
        align = DialogEntry.ALIGN_CENTER,
        dismissOnMask = cancelable,
        dismissOnBack = cancelable,
        maskColor = Color(0, 0, 0, 0.3f),
        onDismiss = { if (cancelable) onCancel?.invoke() },
    ) { _ ->
        View {
            attr {
                minWidth(140f)
                maxWidth(240f)
                padding(16f, 20f, 16f, 20f)
                borderRadius(16f)
                backgroundColor(Color(0xE61C1C1CL))
                alignItemsCenter()
            }
            SDMusicLoading(size = 64f)
            vif({ state.progress >= 0f }) {
                SDProgressBar {
                    attr {
                        marginTop(10f)
                        width(140f)
                        barHeight = 4f
                        trackColor = SDColors.white10
                        gradientColors = listOf(SDColors.accentPurple, SDColors.brandPurple)
                        this.progress = state.progress
                    }
                }
            }
            vif({ state.message.isNotEmpty() }) {
                Text {
                    attr {
                        marginTop(10f)
                        text(state.message)
                        fontSize(12f)
                        color(Color.WHITE)
                        textAlignCenter()
                        lines(3)
                    }
                }
            }
        }
    }
    return state
}

// ============================================================================
// 顶部横幅（Dart delight_toast）
// ============================================================================

/** 近期展示过的横幅标识 → 时间戳（去重用；只存普通数据）。 */
private val recentBannerTags = HashMap<String, Long>()

private fun bannerDeduped(tag: String, windowMs: Long): Boolean {
    val now = nowMs()
    val last = recentBannerTags[tag]
    if (last != null && now - last < windowMs) return true
    recentBannerTags[tag] = now
    if (recentBannerTags.size > 32) {
        val it = recentBannerTags.entries.iterator()
        while (it.hasNext()) if (now - it.next().value > windowMs) it.remove()
    }
    return false
}

/**
 * Dart `Toast.instance.showGenerationCompleted(...)`：生成完成的顶部横幅
 * （80 高、圆角 16、毛玻璃 + 0xCC1C1C1C、响铃图标 + 标题 14 w500 + 副标题 10 白 60%）。
 * 相同内容在 [durationMs] 内只显示一次；点击后先关闭再回调 [onTap]。
 *
 * 与 Dart 的差别：同一页面同一时刻只保留一条横幅（新的替换旧的），不做多条堆叠缩放。
 *
 * @return 句柄；被去重时返回 null
 */
fun BasePager.showGenerationCompleted(
    title: String? = null,
    subtitle: String? = null,
    durationMs: Int = 3000,
    onTap: (() -> Unit)? = null,
): BannerHandle? {
    if (bannerDeduped("generation_completed_toast-$title-$subtitle", durationMs.toLong())) return null
    return overlay.showBanner(durationMs) { handle ->
        View {
            attr {
                margin(0f, 16f, 0f, 16f)
                height(80f)
                borderRadius(16f)
                overflow(true)
            }
            if (onTap != null) {
                event {
                    click {
                        handle.dismiss()
                        onTap()
                    }
                }
            }
            Blur {
                attr {
                    absolutePositionAllZero()
                    blurRadius(10f)
                }
            }
            View {
                attr {
                    absolutePositionAllZero()
                    backgroundColor(Color(0xCC1C1C1CL))
                }
            }
            View {
                attr {
                    flex(1f)
                    flexDirectionRow()
                    alignItemsCenter()
                    padding(10f, 12f, 10f, 12f)
                }
                SDAnimatedBell { }
                View {
                    attr {
                        flex(1f)
                        marginLeft(8f)
                        justifyContentCenter()
                    }
                    Text {
                        attr {
                            text(title ?: "generation_completed_title".tr)
                            fontSize(14f)
                            fontWeight500()
                            fontFamily(SDFont.poppins)
                            color(Color.WHITE)
                            lines(1)
                            textOverFlowTail()
                        }
                    }
                    Text {
                        attr {
                            text(subtitle ?: "generation_completed_subtitle".tr)
                            fontSize(10f)
                            fontWeight500()
                            fontFamily(SDFont.poppins)
                            color(SDColors.white60)
                            lines(2)
                            textOverFlowTail()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dart `Toast.instance.showMessage(...)`：通用顶部横幅（60 高，横向渐变 #E661F6 → 白 54%，警告为红 → 白；
 * 左 40×40 图、标题 14 黑、内容 12 黑、右侧 16 的对勾 / 关闭）。相同消息 3 秒内只显示一次。
 *
 * @param imageUrl 网络图（圆角 9）；为空时用 [imagePath]（AssetsImage.xxx）
 * @param isSingleLine 只有标题（最多两行），不显示内容行
 */
fun BasePager.showTopMessage(
    title: String,
    content: String = "",
    imageUrl: String = "",
    imagePath: String = "",
    isSingleLine: Boolean = false,
    isWarning: Boolean = false,
    isSuccess: Boolean = false,
    onTap: (() -> Unit)? = null,
): BannerHandle? {
    if (bannerDeduped("$title-$content-$isWarning", 3000L)) return null
    return overlay.showBanner(3000) { handle ->
        View {
            attr {
                margin(0f, 16f, 0f, 16f)
                height(60f)
                borderRadius(16f)
                overflow(true)
                flexDirectionRow()
                alignItemsCenter()
                padding(10f, 10f, 10f, 12f)
            }
            if (onTap != null) {
                event { click { onTap() } }
            }
            // 参考 BackdropFilter blur 40；Kuikly blurRadius 上限 12.5
            Blur {
                attr {
                    absolutePositionAllZero()
                    blurRadius(12.5f)
                }
            }
            View {
                attr {
                    absolutePositionAllZero()
                    backgroundLinearGradient(
                        Direction.TO_RIGHT,
                        ColorStop(if (isWarning) Color(0xE6FF5656L) else SDColors.gradientStart, 0f),
                        ColorStop(Color(255, 255, 255, 138f / 255f), 1f),
                    )
                }
            }
            if (imageUrl.isNotEmpty()) {
                SDImage {
                    attr {
                        size(40f, 40f)
                        borderRadius(9f)
                        url = imageUrl
                    }
                }
            } else if (imagePath.isNotEmpty()) {
                Image {
                    attr {
                        size(40f, 40f)
                        src(ImageUri.commonAssets(imagePath))
                        resizeContain()
                    }
                }
            }
            View {
                attr {
                    flex(1f)
                    margin(0f, 8f, 0f, 8f)
                    justifyContentCenter()
                }
                Text {
                    attr {
                        text(title)
                        fontSize(14f)
                        fontWeight500()
                        color(Color.BLACK)
                        lines(if (isSingleLine) 2 else 1)
                        textOverFlowTail()
                    }
                }
                if (!isSingleLine) {
                    Text {
                        attr {
                            marginTop(2f)
                            text(content)
                            fontSize(12f)
                            fontWeight400()
                            color(Color.BLACK)
                            lines(1)
                            textOverFlowTail()
                        }
                    }
                }
            }
            Image {
                attr {
                    size(16f, 16f)
                    src(ImageUri.commonAssets(if (isSuccess) AssetsImage.checkSuccess else AssetsImage.close2Black))
                    resizeContain()
                }
                event { click { if (!isSuccess) handle.dismiss() } }
            }
        }
    }
}

/** Dart `Toast.instance.showSuccessMessage(title)`。 */
fun BasePager.showSuccessMessage(title: String): BannerHandle? =
    showTopMessage(title, isSingleLine = true, isSuccess = true, imagePath = AssetsImage.successIcon)

/** Dart `Toast.instance.showWarningMessage(title)`。 */
fun BasePager.showWarningMessage(title: String): BannerHandle? =
    showTopMessage(title, isSingleLine = true, isWarning = true, imagePath = AssetsImage.errorIcon)
