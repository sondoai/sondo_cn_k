package com.sondo.app.ui.input

import com.sondo.app.core.i18n.tr
import com.sondo.app.nativeapi.SDAppModule
import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.DivView
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.views.InputView
import com.tencent.kuikly.core.views.LengthLimitType
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/** 键盘类型。 */
enum class SDKeyboardType { TEXT, NUMBER, PHONE, EMAIL, PASSWORD, DECIMAL }

/** 回车键类型。 */
enum class SDReturnKey { DEFAULT, DONE, SEARCH, SEND, NEXT, GO }

/** 收起键盘（Dart `CommonUtils.hideKeyboard()` / `FocusManager.instance.primaryFocus?.unfocus()`）。 */
fun DeclarativeBaseView<*, *>.hideKeyboard() {
    try {
        getPager().acquireModule<SDAppModule>(SDAppModule.MODULE_NAME).hideKeyboard()
    } catch (e: Throwable) {
        // 模块未注册（非 BasePager 页面）时忽略
    }
}

/**
 * Dart `UnfocusWidget` → `SDUnfocus`：点空白处收起键盘的容器。
 * 容器默认 `flex(1f)`；子节点自己的点击不受影响。
 */
fun ViewContainer<*, *>.SDUnfocus(onTap: (() -> Unit)? = null, init: DivView.() -> Unit) {
    View {
        attr { flex(1f) }
        event {
            click {
                this@View.hideKeyboard()
                onTap?.invoke()
            }
        }
        init()
    }
}

class SDInputAttr : ComposeAttr() {
    /**
     * 文本。初始值在构建时取一次；之后父级改它（如清空 / 回填）会同步进输入框。
     * 用户输入通过 `onChanged` 通知父级——父级把值写回自己的字段再绑定到这里是安全的
     * （与输入框当前内容相同就不会重复下发，不会打断拼音输入）。
     */
    var text: String by observable("")
    /** 占位文案；空串时用 `please_enter` */
    var placeholder: String by observable("")
    /** 是否可编辑 */
    var enabled: Boolean by observable(true)
    /**
     * ⚠️ 与 Dart `InputWidget.disabled` 同名同义：**命名是反的，true = 可编辑**（Dart 把它直接传给了
     * `TextField.enabled`）。只为照抄调用处时行为不变；新代码请用 [enabled]。
     */
    var disabled: Boolean
        get() = enabled
        set(value) { enabled = value }
    /** 输入框圆角（Dart `borderRadius`，默认 0） */
    var borderRadius: Float = 0f
    /** 填充色（默认透明） */
    var fillColor: Color by observable(Color.TRANSPARENT)
    /** 1px 描边色（默认透明） */
    var borderColor: Color by observable(Color.TRANSPARENT)
    /** 文字 */
    var fontSize: Float = 14f
    var color: Color = Color.WHITE
    /** 占位色（Dart 默认 black38；调用方多数会传 hintStyle） */
    var hintColor: Color = Color(0x61000000L)
    /** 光标色 */
    var cursorColor: Color = Color.WHITE
    /** 最大长度（按字符，默认 100） */
    var maxLength: Int = 100
    /** 是否在输入框下方右侧显示 `当前/最大`（12 号 white54） */
    var showCounter: Boolean = false
    /** 左 / 右附加内容（Dart prefixIcon / suffixIcon） */
    var prefixIcon: ViewBuilder? = null
    var suffixIcon: ViewBuilder? = null
    var keyboardType: SDKeyboardType = SDKeyboardType.TEXT
    var returnKey: SDReturnKey = SDReturnKey.DEFAULT
    var autofocus: Boolean = false
    /** 文字居中 */
    var textAlignCenter: Boolean = false
    /** 文字左右内边距（Dart contentPadding 水平 10；有 prefixIcon 时为 0） */
    var contentPaddingH: Float = 10f

    internal var fieldHeight: Float = -1f

    /** 输入框高度（Dart `height`）。 */
    override fun height(height: Float): Attr {
        fieldHeight = height
        return this
    }
}

class SDInputEvent : ComposeEvent() {
    internal var changed: ((String) -> Unit)? = null
    internal var submitted: ((String) -> Unit)? = null
    internal var focus: (() -> Unit)? = null
    internal var blur: (() -> Unit)? = null
    internal var keyboard: ((Float) -> Unit)? = null

    fun onChanged(handler: (String) -> Unit) { changed = handler }
    /** 软键盘高度变化（dp；0 = 收起）。弹窗 / 底部栏据此避让键盘。 */
    fun onKeyboardHeight(handler: (Float) -> Unit) { keyboard = handler }
    /** 按下回车键。 */
    fun onSubmitted(handler: (String) -> Unit) { submitted = handler }
    fun onFocus(handler: () -> Unit) { focus = handler }
    fun onBlur(handler: () -> Unit) { blur = handler }
}

/**
 * Dart `InputWidget` → `SDInput`：单行输入框。
 *
 * ```
 * SDInput {
 *     attr {
 *         height(44f); borderRadius = 16f; fillColor = UIColor.cFFF10
 *         placeholder = "enter_song_name".tr; maxLength = 30; text = ctx.title
 *     }
 *     event { onChanged { ctx.title = it } }
 * }
 * ```
 * 放在 [SDKeyboardAwareScroll] 里时，聚焦后会自动滚到键盘上方。
 */
class SDInputView : ComposeView<SDInputAttr, SDInputEvent>() {
    private var inputRef: ViewRef<InputView>? = null
    private var length by observable(0)
    /** 输入框里当前的内容（原生回调或我们主动设置后更新） */
    private var currentText = ""
    private var bound = false

    override fun createAttr(): SDInputAttr = SDInputAttr()
    override fun createEvent(): SDInputEvent = SDInputEvent()

    /** 当前文本。 */
    val text: String get() = currentText

    fun focus() { inputRef?.view?.focus() }
    fun blur() { inputRef?.view?.blur() }

    /** 主动替换内容（Dart `controller.text = …`），不触发 onChanged。 */
    fun setText(value: String) {
        currentText = value
        length = value.length
        inputRef?.view?.setText(value)
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
            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                    // 没给高度时用 Material TextField 的默认高度
                    if (ctx.attr.fieldHeight >= 0f) height(ctx.attr.fieldHeight) else height(48f)
                    borderRadius(ctx.attr.borderRadius)
                    backgroundColor(ctx.attr.fillColor)
                    border(Border(1f, BorderStyle.SOLID, ctx.attr.borderColor))
                    // 部分 Android 机型输入框有渲染残留，打开裁剪规避
                    overflow(true)
                }
                if (ctx.attr.prefixIcon != null) {
                    View {
                        attr {
                            minWidth(30f)
                            allCenter()
                        }
                        ctx.attr.prefixIcon?.invoke(this)
                    }
                }
                Input {
                    ref { ctx.inputRef = it }
                    // 初始文本单独放在静态块里：只下发一次
                    attr {
                        text(initialText)
                        autofocus(ctx.attr.autofocus)
                    }
                    attr {
                        flex(1f)
                        alignSelfStretch()
                        val ph = if (ctx.attr.prefixIcon != null) 0f else ctx.attr.contentPaddingH
                        marginLeft(ph)
                        marginRight(if (ctx.attr.suffixIcon != null) 0f else ph)
                        fontSize(ctx.attr.fontSize)
                        color(ctx.attr.color)
                        tintColor(ctx.attr.cursorColor)
                        placeholder(ctx.attr.placeholder.ifEmpty { "please_enter".tr })
                        placeholderColor(ctx.attr.hintColor)
                        maxTextLength(ctx.attr.maxLength, LengthLimitType.CHARACTER)
                        editable(ctx.attr.enabled)
                        if (ctx.attr.textAlignCenter) textAlignCenter()
                        when (ctx.attr.keyboardType) {
                            SDKeyboardType.NUMBER -> keyboardTypeNumber()
                            // Kuikly 2.30 没有 phone / decimal 键盘类型，退化为数字键盘
                            SDKeyboardType.PHONE -> keyboardTypeNumber()
                            SDKeyboardType.EMAIL -> keyboardTypeEmail()
                            SDKeyboardType.PASSWORD -> keyboardTypePassword()
                            SDKeyboardType.DECIMAL -> keyboardTypeNumber()
                            SDKeyboardType.TEXT -> {}
                        }
                        when (ctx.attr.returnKey) {
                            SDReturnKey.DONE -> returnKeyTypeDone()
                            SDReturnKey.SEARCH -> returnKeyTypeSearch()
                            SDReturnKey.SEND -> returnKeyTypeSend()
                            SDReturnKey.NEXT -> returnKeyTypeNext()
                            SDReturnKey.GO -> returnKeyTypeGo()
                            SDReturnKey.DEFAULT -> {}
                        }
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
                        inputReturn {
                            ctx.event.submitted?.invoke(it.text)
                        }
                        keyboardHeightChange {
                            ctx.findKeyboardAwareScroll()?.updateKeyboardHeight(it.height)
                            ctx.event.keyboard?.invoke(it.height)
                        }
                    }
                }
                if (ctx.attr.suffixIcon != null) {
                    View {
                        attr { allCenter() }
                        ctx.attr.suffixIcon?.invoke(this)
                    }
                }
            }
            if (ctx.attr.showCounter && ctx.attr.maxLength > 0) {
                Text {
                    attr {
                        alignSelfFlexEnd()
                        marginTop(4f)
                        text("${ctx.length}/${ctx.attr.maxLength}")
                        fontSize(12f)
                        color(Color(0x8AFFFFFFL))
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDInput(init: SDInputView.() -> Unit) {
    addChild(SDInputView(), init)
}

/** 向上找到包着本视图的 [SDKeyboardAwareScrollView]（没有则为 null）。 */
internal fun DeclarativeBaseView<*, *>.findKeyboardAwareScroll(): SDKeyboardAwareScrollView? {
    var p: ViewContainer<*, *>? = parent
    while (p != null) {
        if (p is SDKeyboardAwareScrollView) return p
        p = p.parent
    }
    return null
}
