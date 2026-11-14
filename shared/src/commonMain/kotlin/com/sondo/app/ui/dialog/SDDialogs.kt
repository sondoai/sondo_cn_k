package com.sondo.app.ui.dialog

import com.sondo.app.base.BasePager
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.i18n.trParams
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.atoms.SDGradientButton
import com.sondo.app.ui.atoms.SDPrimaryButton
import com.sondo.app.ui.atoms.SDSecondaryButton
import com.sondo.app.ui.input.SDInput
import com.sondo.app.ui.input.SDInputView
import com.sondo.app.ui.input.hideKeyboard
import com.sondo.app.ui.overlay.DialogEntry
import com.sondo.app.ui.overlay.DialogHandle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/*
 * 参考工程里并存三套弹窗机制（SmartDialog / Get.dialog / Get.bottomSheet），关闭方式各不相同。
 * 这里全部统一到 BasePager.showDialog / showBottomSheet：每个 showXxx 都返回 DialogHandle，
 * 用 handle.dismiss() 关闭（等价于 SmartDialog.dismiss() / Get.back()）。
 */

// ============================================================================
// 确认弹窗（Dart ConfirmDialog）
// ============================================================================

/**
 * 应用统一样式的确认弹窗卡片内容（Dart `ConfirmDialog` 的 builder）。一般用 [showConfirmDialog]，
 * 需要把同款卡片嵌进别处时才直接用它。
 */
internal fun ViewContainer<*, *>.SDConfirmCard(
    screenWidth: Float,
    title: String,
    content: String,
    cancelText: String,
    confirmText: String,
    isShowCancel: Boolean,
    contentCenter: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    View {
        attr {
            width(screenWidth * 0.8f)
            minHeight(180f)
            maxHeight(300f)
            borderRadius(24f)
            backgroundColor(UIColor.c1C1C1C)
            padding(14f, 20f, 20f, 20f)
            alignItemsCenter()
        }
        Text {
            attr {
                text(title)
                fontSize(16f)
                fontWeightBold()
                color(Color.WHITE)
                lines(1)
            }
        }
        View {
            attr {
                marginTop(10f)
                alignSelfStretch()
                minHeight(60f)
                padding(0f, 16f, 0f, 16f)
                justifyContentCenter()
                // Dart Container(alignment: center)：文字收缩到自身宽度后整体居中（单行居中、多行左对齐）
                alignItemsCenter()
            }
            Text {
                attr {
                    text(content)
                    fontSize(14f)
                    fontWeightBold()
                    lineHeight(14f * 1.2f)
                    color(Color.WHITE)
                    lines(8)
                    textOverFlowTail()
                    if (contentCenter) textAlignCenter()
                }
            }
        }
        View {
            attr {
                marginTop(20f)
                alignSelfStretch()
                flexDirectionRow()
            }
            if (isShowCancel) {
                SDSecondaryButton {
                    attr {
                        flex(1f)
                        height(40f)
                        fontSize = 14f
                        text = cancelText
                    }
                    event { onTap { onCancel() } }
                }
                View { attr { width(20f) } }
            }
            SDPrimaryButton {
                attr {
                    flex(1f)
                    height(40f)
                    fontSize = 14f
                    text = confirmText
                }
                event { onTap { onConfirm() } }
            }
        }
    }
}

/**
 * Dart `ConfirmDialog.show(...)` → 确认弹窗：标题（默认「提示」）+ 内容 + 取消 / 确定。
 *
 * ⚠️ 与 Dart 的差别：Dart 版点「确定」**不会自动关闭**（调用处都在 onConfirm 里手写 `SmartDialog.dismiss()`）。
 * 这里默认 [autoDismiss] = true —— 先关弹窗再回调 [onConfirm]，照抄调用处时把那句 `SmartDialog.dismiss()` 删掉即可；
 * 确实需要「确定后保持打开」（如等待请求结果）时传 `autoDismiss = false`，用返回的 handle 自己关。
 *
 * @param isShowCancel false = 单按钮；此时点遮罩不关闭（与 Dart `clickMaskDismiss: isShowCancel` 一致），返回键仍可关闭
 * @param contentCenter 内容是否居中对齐（Dart `showChoice` 居中，`show` 靠左）
 * @param onCancel 点「取消」或遮罩 / 返回键关闭时回调
 */
fun BasePager.showConfirmDialog(
    content: String,
    title: String? = null,
    confirmText: String? = null,
    cancelText: String? = null,
    isShowCancel: Boolean = true,
    autoDismiss: Boolean = true,
    contentCenter: Boolean = false,
    onCancel: (() -> Unit)? = null,
    onConfirm: () -> Unit,
): DialogHandle {
    var confirmed = false
    val w = screenWidth
    return showDialog(
        align = DialogEntry.ALIGN_CENTER,
        dismissOnMask = isShowCancel,
        // SmartDialog 默认 backType normal：单按钮时遮罩点不掉，但返回键可以关
        dismissOnBack = true,
        maskColor = SDColors.mask50,
        onDismiss = { if (!confirmed) onCancel?.invoke() },
    ) { handle ->
        SDConfirmCard(
            screenWidth = w,
            title = title ?: "tip".tr,
            content = content,
            cancelText = cancelText ?: "cancel".tr,
            confirmText = confirmText ?: "confirm".tr,
            isShowCancel = isShowCancel,
            contentCenter = contentCenter,
            onCancel = { handle.dismiss() },
            onConfirm = {
                confirmed = true
                if (autoDismiss) handle.dismiss()
                onConfirm()
            },
        )
    }
}

/** 单按钮提示弹窗（`ConfirmDialog.show(isShowCancel: false)`）。点按钮后关闭。 */
fun BasePager.showAlertDialog(
    content: String,
    title: String? = null,
    confirmText: String? = null,
    onConfirm: (() -> Unit)? = null,
): DialogHandle = showConfirmDialog(
    content = content,
    title = title,
    confirmText = confirmText ?: "i_know".tr,
    isShowCancel = false,
    contentCenter = true,
    onConfirm = { onConfirm?.invoke() },
)

/**
 * Dart `ConfirmDialog.showChoice(...)` → 挂起直到用户选择：确定 = true；取消 / 点遮罩 / 返回键 = false。
 *
 * ```
 * ctx.launch { if (ctx.confirm("delete_confirm".tr)) ctx.doDelete() }
 * ```
 */
suspend fun BasePager.confirm(
    content: String,
    title: String? = null,
    cancelText: String? = null,
    confirmText: String? = null,
): Boolean = suspendCoroutine { cont ->
    var done = false
    showConfirmDialog(
        content = content,
        title = title,
        confirmText = confirmText,
        cancelText = cancelText,
        contentCenter = true,
        onCancel = {
            if (!done) {
                done = true
                cont.resume(false)
            }
        },
        onConfirm = {
            if (!done) {
                done = true
                cont.resume(true)
            }
        },
    )
}

// ============================================================================
// 通用卡片弹窗（标题 + 自定义内容 + 两个按钮）
// ============================================================================

/**
 * 标题 + 自定义内容 + 取消 / 确定 的卡片弹窗（Dart `CustomDialog.show(contentType: custom)` 与
 * `AiWatermarkDialogs._DialogShell` 的外壳）。
 *
 * @param widthFactor 卡片宽度占屏宽比例（ConfirmDialog 0.8，AI 声明 0.84）
 * @param onConfirm 点「确定」；**不会自动关闭**，在回调里用 handle 关（与 Dart CustomDialog 一致）
 * @param body 内容区
 */
fun BasePager.showCardDialog(
    title: String,
    cancelText: String? = null,
    confirmText: String? = null,
    widthFactor: Float = 0.84f,
    radius: Float = 24f,
    buttonGap: Float = 12f,
    dismissOnMask: Boolean = true,
    onDismiss: (() -> Unit)? = null,
    onConfirm: (DialogHandle) -> Unit,
    body: ViewBuilder,
): DialogHandle {
    val w = screenWidth
    return showDialog(
        align = DialogEntry.ALIGN_CENTER,
        dismissOnMask = dismissOnMask,
        maskColor = SDColors.mask50,
        onDismiss = onDismiss,
    ) { handle ->
        View {
            attr {
                width(w * widthFactor)
                padding(18f, 20f, 20f, 20f)
                borderRadius(radius)
                backgroundColor(UIColor.c1C1C1C)
                alignItemsCenter()
            }
            Text {
                attr {
                    text(title)
                    fontSize(16f)
                    fontWeightBold()
                    color(Color.WHITE)
                }
            }
            View {
                attr {
                    marginTop(12f)
                    alignSelfStretch()
                }
                body.invoke(this)
            }
            View {
                attr {
                    marginTop(20f)
                    alignSelfStretch()
                    flexDirectionRow()
                }
                SDSecondaryButton {
                    attr {
                        flex(1f)
                        height(40f)
                        fontSize = 14f
                        text = cancelText ?: "cancel".tr
                    }
                    event { onTap { handle.dismiss() } }
                }
                View { attr { width(buttonGap) } }
                SDPrimaryButton {
                    attr {
                        flex(1f)
                        height(40f)
                        fontSize = 14f
                        text = confirmText ?: "confirm".tr
                    }
                    event { onTap { onConfirm(handle) } }
                }
            }
        }
    }
}

// ============================================================================
// 输入弹窗（Dart CustomDialog input）
// ============================================================================

internal class SDInputDialogAttr : ComposeAttr() {
    var title: String = ""
    var hintText: String = ""
    var initialText: String = ""
    var maxLength: Int = 0
    var cancelText: String = ""
    var confirmText: String = ""
    var cardWidth: Float = 0f
    var number: Boolean = false
    lateinit var handle: DialogHandle
    var onConfirm: ((String, DialogHandle) -> Unit)? = null
}

/** 输入弹窗内容：需要响应式状态（超长提示），所以是个组件。 */
internal class SDInputDialogView : ComposeView<SDInputDialogAttr, ComposeEvent>() {
    private var overflow by observable(false)
    private var keyboardHeight by observable(0f)
    private var text = ""
    private var inputRef: ViewRef<SDInputView>? = null

    override fun createAttr(): SDInputDialogAttr = SDInputDialogAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun created() {
        super.created()
        val max = attr.maxLength
        // 初始文本超长时先裁剪（Dart 同款处理）
        text = if (max > 0 && attr.initialText.length > max) attr.initialText.substring(0, max) else attr.initialText
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val a = ctx.attr
        return {
            attr {
                width(a.cardWidth)
                padding(24f, 20f, 24f, 20f)
                borderRadius(16f)
                backgroundColor(UIColor.c1C1C1C)
                alignItemsCenter()
                // 键盘弹出时整体上移半个键盘高度（卡片在「屏幕减去键盘」的区域里居中，同 Flutter Dialog）
                marginBottom(ctx.keyboardHeight)
            }
            Text {
                attr {
                    text(a.title)
                    fontSize(18f)
                    fontWeight600()
                    color(Color.WHITE)
                }
            }
            SDInput {
                ref { ctx.inputRef = it }
                attr {
                    marginTop(20f)
                    alignSelfStretch()
                    height(44f)
                    borderRadius = 8f
                    fillColor = UIColor.cFFF10
                    hintColor = Color(0xFFBDBDBDL)
                    placeholder = a.hintText
                    text = ctx.text
                    contentPaddingH = 8f
                    // 多放一位进来，才能检测到「超长」并提示（真正的上限在 onChanged 里裁剪）
                    maxLength = if (a.maxLength > 0) a.maxLength + 1 else 1000
                    if (a.number) keyboardType = com.sondo.app.ui.input.SDKeyboardType.NUMBER
                    autofocus = true
                }
                event {
                    onKeyboardHeight { ctx.keyboardHeight = it }
                    onChanged { value ->
                        val max = a.maxLength
                        if (max > 0 && value.length > max) {
                            val cut = value.substring(0, max)
                            ctx.text = cut
                            ctx.inputRef?.view?.setText(cut)
                            ctx.overflow = true
                        } else {
                            ctx.text = value
                            ctx.overflow = false
                        }
                    }
                }
            }
            vif({ ctx.overflow }) {
                Text {
                    attr {
                        alignSelfFlexEnd()
                        marginTop(4f)
                        text("please_enter_max_length_characters".trParams("maxLength" to a.maxLength))
                        fontSize(12f)
                        color(Color.RED)
                    }
                }
            }
            View {
                attr {
                    marginTop(24f)
                    alignSelfStretch()
                    flexDirectionRow()
                }
                SDGradientButton {
                    attr {
                        flex(1f)
                        padding(0f, 0f, 0f, 0f)
                        height(40f)
                        fontSize = 14f
                        gradientColors = listOf(UIColor.cFFF10, UIColor.cFFF10)
                        textColor = Color.WHITE
                        text = a.cancelText
                    }
                    event {
                        onTap {
                            ctx.hideKeyboard()
                            a.handle.dismiss()
                        }
                    }
                }
                View { attr { width(20f) } }
                SDPrimaryButton {
                    attr {
                        flex(1f)
                        height(40f)
                        fontSize = 14f
                        text = a.confirmText
                    }
                    event { onTap { a.onConfirm?.invoke(ctx.text, a.handle) } }
                }
            }
        }
    }
}

/**
 * Dart `CustomDialog.show(contentType: DialogContentType.input, …)` → 输入弹窗（如兑换激活码）。
 *
 * 「确定」**不会自动关闭**：在 [onConfirm] 里校验 / 请求成功后调用 `handle.dismiss()`（与 Dart `Get.back()` 对应）。
 *
 * ```
 * showInputDialog(title = "CDkey".tr, hintText = "please_enter_the_CDkey".tr) { text, handle ->
 *     if (text.isEmpty()) { toast("please_enter_the_CDkey".tr); return@showInputDialog }
 *     launch { if (exchange(text)) handle.dismiss() }
 * }
 * ```
 * @param maxLength > 0 时超长会被裁剪并显示红色提示
 */
fun BasePager.showInputDialog(
    title: String,
    hintText: String = "",
    initialText: String = "",
    maxLength: Int = 0,
    cancelText: String? = null,
    confirmText: String? = null,
    numberKeyboard: Boolean = false,
    onConfirm: (text: String, handle: DialogHandle) -> Unit,
): DialogHandle {
    val w = screenWidth
    return showDialog(
        align = DialogEntry.ALIGN_CENTER,
        maskColor = SDColors.mask50,
    ) { handle ->
        addChild(SDInputDialogView()) {
            attr {
                this.title = title
                this.hintText = hintText
                this.initialText = initialText
                this.maxLength = maxLength
                this.cancelText = cancelText ?: "cancel".tr
                this.confirmText = confirmText ?: "confirm".tr
                // Material Dialog 默认左右各留 40
                cardWidth = w - 80f
                number = numberKeyboard
                this.handle = handle
                this.onConfirm = onConfirm
            }
        }
    }
}
