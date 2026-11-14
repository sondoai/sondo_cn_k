package com.sondo.app.core.utils

import com.sondo.app.base.BasePager
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.constants.blackA
import com.sondo.app.core.i18n.tr
import com.sondo.app.ui.overlay.DialogHandle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * core 层自用的二选一确认弹窗（权限说明、去设置引导、删号确认等），
 * 样式照抄参考工程 ConfirmDialog：宽 80%、圆角 24、底色 #1C1C1C、按钮高 40。
 * 业务页面请用 ui 层的通用弹窗；这里只为了让 core / service 不反向依赖 ui 组件。
 */
internal object ConfirmDialogLite {

    /** 展示二选一弹窗并返回用户选择；点遮罩 / 返回键关闭视为拒绝。 */
    suspend fun showChoice(
        pager: BasePager,
        content: String,
        title: String? = null,
        cancelText: String? = null,
        confirmText: String? = null,
    ): Boolean = suspendCoroutine { cont ->
        var result = false
        var resumed = false
        pager.showDialog(
            maskColor = blackA(0.5f),
            onDismiss = {
                if (!resumed) {
                    resumed = true
                    cont.resume(result)
                }
            },
        ) { handle ->
            dialogBody(
                pager, title ?: "tip".tr, content, centerContent = true,
                cancelText = cancelText ?: "cancel".tr, confirmText = confirmText ?: "confirm".tr, showCancel = true,
                onCancel = { handle.dismiss() },
                onConfirm = {
                    result = true
                    handle.dismiss()
                },
            )
        }
    }

    /**
     * 确认弹窗。点确认后先关闭弹窗再回调 [onConfirm]（参考工程由调用方在回调里自行 dismiss，效果一致）。
     * @param isShowCancel false 时没有取消按钮，点遮罩也不关闭
     */
    fun show(
        pager: BasePager,
        content: String,
        onConfirm: () -> Unit,
        title: String? = null,
        cancelText: String? = null,
        confirmText: String? = null,
        isShowCancel: Boolean = true,
    ): DialogHandle = pager.showDialog(
        dismissOnMask = isShowCancel,
        // 参考的确认框返回键总能关闭（单按钮也一样），只有遮罩点击受 isShowCancel 控制
        dismissOnBack = true,
        maskColor = blackA(0.5f),
    ) { handle ->
        dialogBody(
            pager, title ?: "tip".tr, content, centerContent = false,
            cancelText = cancelText ?: "cancel".tr, confirmText = confirmText ?: "confirm".tr, showCancel = isShowCancel,
            onCancel = { handle.dismiss() },
            onConfirm = {
                handle.dismiss()
                onConfirm()
            },
        )
    }

    private fun ViewContainer<*, *>.dialogBody(
        pager: BasePager,
        title: String,
        content: String,
        centerContent: Boolean,
        cancelText: String,
        confirmText: String,
        showCancel: Boolean,
        onCancel: () -> Unit,
        onConfirm: () -> Unit,
    ) {
        View {
            attr {
                width(pager.screenWidth * 0.8f)
                minHeight(180f)
                maxHeight(300f)
                borderRadius(24f)
                backgroundColor(UIColor.c1C1C1C)
                padding(top = 14f, left = 20f, bottom = 20f, right = 20f)
                alignItemsCenter()
            }
            Text {
                attr {
                    text(title)
                    fontSize(16f)
                    fontWeightBold()
                    color(UIColor.cFFFFFF)
                    lines(1)
                    textOverFlowTail()
                }
            }
            View {
                attr {
                    marginTop(10f)
                    padding(left = 16f, right = 16f)
                    minHeight(60f)
                    alignSelfStretch()
                    allCenter()
                }
                Text {
                    attr {
                        text(content)
                        fontSize(14f)
                        fontWeightBold()
                        color(UIColor.cFFFFFF)
                        lineHeight(14f * 1.2f)
                        lines(8)
                        textOverFlowTail()
                        if (centerContent) textAlignCenter()
                    }
                }
            }
            View {
                attr {
                    marginTop(20f)
                    flexDirectionRow()
                    alignSelfStretch()
                }
                if (showCancel) {
                    button(cancelText, Color(0x33FFFFFFL), UIColor.cFFFFFF, onCancel)
                    View { attr { width(20f) } }
                }
                button(confirmText, UIColor.cF3EFF7, UIColor.c0A0A0A, onConfirm)
            }
        }
    }

    private fun ViewContainer<*, *>.button(label: String, bg: Color, fg: Color, onTap: () -> Unit) {
        View {
            attr {
                flex(1f)
                height(40f)
                borderRadius(22f)
                backgroundColor(bg)
                allCenter()
            }
            event { click { onTap() } }
            Text {
                attr {
                    text(label)
                    fontSize(14f)
                    color(fg)
                    lines(1)
                }
            }
        }
    }
}
