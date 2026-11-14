package com.sondo.app.ui.input

import com.sondo.app.core.i18n.tr
import com.sondo.app.ui.SDColors
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.views.InputView
import com.tencent.kuikly.core.views.LengthLimitType
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.TextArea
import com.tencent.kuikly.core.views.TextAreaView
import com.tencent.kuikly.core.views.View

// ============================================================================
// 多行输入框 + 计数
// ============================================================================

class SDTextAreaAttr : ComposeAttr() {
    /** 文本（语义同 [SDInputAttr.text]：初始值 + 父级后续改动会同步进输入框） */
    var text: String by observable("")
    var placeholder: String by observable("")
    var enabled: Boolean by observable(true)
    /** 输入区高度（不含计数行） */
    var areaHeight: Float = 120f
    var radius: Float = 16f
    var fillColor: Color by observable(SDColors.white10)
    var borderColor: Color by observable(Color.TRANSPARENT)
    var fontSize: Float = 14f
    /** 行高；<= 0 用默认 */
    var lineHeightDp: Float = 0f
    var color: Color = Color.WHITE
    var hintColor: Color = SDColors.white30
    var cursorColor: Color = Color.WHITE
    /** 最大长度（按字符）；<= 0 不限 */
    var maxLength: Int = 0
    /** 是否显示 `当前/最大` 计数（右下角，12 号，颜色 [counterColor]） */
    var showCounter: Boolean = true
    var counterColor: Color = SDColors.white30
    /**
     * 输入文字字族（如 "Poppins-Medium"）。TextAreaAttr 没有 fontFamily 方法，这里直接下发 `fontFamily` 属性；
     * 注意：Kuikly 2.30 的 Android 渲染器（KRTextFieldView）不读这个属性，Android 上暂不生效。
     */
    var fontFamily: String? = null
    /** 内边距 */
    var contentPadding: Float = 12f
    /** 分边内边距（Dart EdgeInsets.fromLTRB）；null 的边用 [contentPadding] */
    var contentPaddingLeft: Float? = null
    var contentPaddingTop: Float? = null
    var contentPaddingRight: Float? = null
    var contentPaddingBottom: Float? = null
    var autofocus: Boolean = false
}

class SDTextAreaEvent : ComposeEvent() {
    internal var changed: ((String) -> Unit)? = null
    internal var focus: (() -> Unit)? = null
    internal var blur: (() -> Unit)? = null
    internal var keyboard: ((Float) -> Unit)? = null

    fun onChanged(handler: (String) -> Unit) { changed = handler }
    /** 软键盘高度变化（dp；0 = 收起）。 */
    fun onKeyboardHeight(handler: (Float) -> Unit) { keyboard = handler }
    fun onFocus(handler: () -> Unit) { focus = handler }
    fun onBlur(handler: () -> Unit) { blur = handler }
}

/**
 * 多行输入框 + 字数计数（歌词 / 描述 / 提示词输入）。
 *
 * ```
 * SDTextArea {
 *     attr { areaHeight = 160f; maxLength = 500; placeholder = "please_enter_description".tr; text = ctx.desc }
 *     event { onChanged { ctx.desc = it } }
 * }
 * ```
 */
class SDTextAreaView : ComposeView<SDTextAreaAttr, SDTextAreaEvent>() {
    private var areaRef: ViewRef<TextAreaView>? = null
    private var length by observable(0)
    private var currentText = ""
    private var bound = false

    override fun createAttr(): SDTextAreaAttr = SDTextAreaAttr()
    override fun createEvent(): SDTextAreaEvent = SDTextAreaEvent()

    val text: String get() = currentText

    fun focus() { areaRef?.view?.focus() }
    fun blur() { areaRef?.view?.blur() }

    /** 主动替换内容，不触发 onChanged。 */
    fun setText(value: String) {
        currentText = value
        length = value.length
        areaRef?.view?.forceSetText(value)
    }

    override fun created() {
        super.created()
        currentText = attr.text
        length = currentText.length
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        bindValueChange({ attr.text }) { v ->
            if (!bound) {
                bound = true
            } else if (v != currentText) {
                setText(v as String)
            }
        }
    }

    override fun viewDestroyed() {
        unbindAllValueChange()
        super.viewDestroyed()
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val initialText = ctx.currentText
        return {
            attr {
                borderRadius(ctx.attr.radius)
                backgroundColor(ctx.attr.fillColor)
                border(Border(1f, BorderStyle.SOLID, ctx.attr.borderColor))
                val p = ctx.attr.contentPadding
                padding(
                    ctx.attr.contentPaddingTop ?: p,
                    ctx.attr.contentPaddingLeft ?: p,
                    ctx.attr.contentPaddingBottom ?: p,
                    ctx.attr.contentPaddingRight ?: p,
                )
                overflow(true)
            }
            TextArea {
                ref { ctx.areaRef = it }
                attr {
                    text(initialText)
                    autofocus(ctx.attr.autofocus)
                }
                attr {
                    height(ctx.attr.areaHeight)
                    fontSize(ctx.attr.fontSize)
                    val family = ctx.attr.fontFamily
                    if (family != null) "fontFamily" with family
                    if (ctx.attr.lineHeightDp > 0f) lineHeight(ctx.attr.lineHeightDp)
                    color(ctx.attr.color)
                    tintColor(ctx.attr.cursorColor)
                    placeholder(ctx.attr.placeholder.ifEmpty { "please_enter".tr })
                    placeholderColor(ctx.attr.hintColor)
                    if (ctx.attr.maxLength > 0) maxTextLength(ctx.attr.maxLength, LengthLimitType.CHARACTER)
                    editable(ctx.attr.enabled)
                }
                event {
                    textDidChange {
                        ctx.currentText = it.text
                        ctx.length = it.text.length
                        ctx.event.changed?.invoke(it.text)
                    }
                    inputFocus {
                        ctx.findKeyboardAwareScroll()?.ensureVisible(ctx)
                        ctx.event.focus?.invoke()
                    }
                    inputBlur {
                        ctx.findKeyboardAwareScroll()?.clearActiveTarget(ctx)
                        ctx.event.blur?.invoke()
                    }
                    keyboardHeightChange {
                        ctx.findKeyboardAwareScroll()?.updateKeyboardHeight(it.height)
                        ctx.event.keyboard?.invoke(it.height)
                    }
                }
            }
            if (ctx.attr.showCounter && ctx.attr.maxLength > 0) {
                Text {
                    attr {
                        alignSelfFlexEnd()
                        marginTop(6f)
                        text("${ctx.length}/${ctx.attr.maxLength}")
                        fontSize(12f)
                        color(ctx.attr.counterColor)
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDTextArea(init: SDTextAreaView.() -> Unit) {
    addChild(SDTextAreaView(), init)
}

/**
 * 主动替换 TextArea 的内容。
 * `TextAreaView.setText()` 已废弃，官方建议用 attr 的 `text()`；但 Kuikly 的属性「值没变就不下发」，
 * 用户输入后再设回同一个值（如清空两次）会被吞掉，所以这里临时打开 forceUpdate 强制下发。
 */
internal fun TextAreaView.forceSetText(value: String) {
    getViewAttr().apply {
        forceUpdate = true
        text(value)
        forceUpdate = false
    }
}

// ============================================================================
// 验证码输入（N 个格子）
// ============================================================================

class SDCodeInputAttr : ComposeAttr() {
    /** 位数 */
    var length: Int = 6
    /** 单格尺寸 / 间距 */
    var boxWidth: Float = 44f
    var boxHeight: Float = 52f
    var gap: Float = 8f
    var radius: Float = 12f
    var fillColor: Color = SDColors.white10
    /** 当前待输入格的描边色 */
    var activeBorderColor: Color = SDColors.brandPurple
    var fontSize: Float = 20f
    var color: Color = Color.WHITE
    var autofocus: Boolean = true
    /** 密码样式：已输入的格子显示圆点 */
    var obscure: Boolean = false
}

class SDCodeInputEvent : ComposeEvent() {
    internal var changed: ((String) -> Unit)? = null
    internal var completed: ((String) -> Unit)? = null

    fun onChanged(handler: (String) -> Unit) { changed = handler }
    /** 输满 [SDCodeInputAttr.length] 位时回调。 */
    fun onCompleted(handler: (String) -> Unit) { completed = handler }
}

/**
 * 验证码 / 激活码输入：N 个格子显示数字，实际由一个透明的数字输入框接收键盘输入。
 *
 * ```
 * SDCodeInput {
 *     attr { length = 6 }
 *     event { onCompleted { code -> ctx.verify(code) } }
 * }
 * ```
 */
class SDCodeInputView : ComposeView<SDCodeInputAttr, SDCodeInputEvent>() {
    private var code by observable("")
    private var focused by observable(false)
    private var inputRef: ViewRef<InputView>? = null

    override fun createAttr(): SDCodeInputAttr = SDCodeInputAttr()
    override fun createEvent(): SDCodeInputEvent = SDCodeInputEvent()

    val text: String get() = code

    fun focus() { inputRef?.view?.focus() }
    fun blur() { inputRef?.view?.blur() }

    /** 清空（验证失败后重输）。 */
    fun clear() {
        code = ""
        inputRef?.view?.setText("")
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val n = ctx.attr.length
        return {
            attr {
                flexDirectionRow()
                justifyContentCenter()
            }
            for (i in 0 until n) {
                View {
                    attr {
                        size(ctx.attr.boxWidth, ctx.attr.boxHeight)
                        if (i > 0) marginLeft(ctx.attr.gap)
                        borderRadius(ctx.attr.radius)
                        backgroundColor(ctx.attr.fillColor)
                        allCenter()
                        val active = ctx.focused && ctx.code.length == i
                        border(Border(1f, BorderStyle.SOLID, if (active) ctx.attr.activeBorderColor else Color.TRANSPARENT))
                    }
                    Text {
                        attr {
                            val ch = if (i < ctx.code.length) ctx.code[i].toString() else ""
                            text(if (ctx.attr.obscure && ch.isNotEmpty()) "●" else ch)
                            fontSize(ctx.attr.fontSize)
                            fontWeight600()
                            color(ctx.attr.color)
                        }
                    }
                }
            }
            // 透明输入框盖在格子上：点哪里都能聚焦
            Input {
                ref { ctx.inputRef = it }
                attr {
                    absolutePositionAllZero()
                    opacity(0.02f)
                    color(Color.TRANSPARENT)
                    tintColor(Color.TRANSPARENT)
                    fontSize(1f)
                    keyboardTypeNumber()
                    maxTextLength(n, LengthLimitType.CHARACTER)
                    autofocus(ctx.attr.autofocus)
                }
                event {
                    textDidChange {
                        val digits = it.text.filter { c -> c in '0'..'9' }.take(n)
                        if (digits != it.text) ctx.inputRef?.view?.setText(digits)
                        val was = ctx.code
                        ctx.code = digits
                        if (digits != was) {
                            ctx.event.changed?.invoke(digits)
                            if (digits.length == n) ctx.event.completed?.invoke(digits)
                        }
                    }
                    inputFocus { ctx.focused = true }
                    inputBlur { ctx.focused = false }
                    keyboardHeightChange {
                        ctx.findKeyboardAwareScroll()?.updateKeyboardHeight(it.height)
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDCodeInput(init: SDCodeInputView.() -> Unit) {
    addChild(SDCodeInputView(), init)
}
