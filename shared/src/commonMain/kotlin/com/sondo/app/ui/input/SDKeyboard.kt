package com.sondo.app.ui.input

import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.i18n.tr
import com.sondo.app.nativeapi.SDAppModule
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.SDFont
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.LengthLimitType
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.ScrollerView
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.TextArea
import com.tencent.kuikly.core.views.TextAreaView
import com.tencent.kuikly.core.views.View
import kotlin.math.abs
import kotlin.math.min

// ============================================================================
// 键盘感知滚动容器
// ============================================================================

class SDKeyboardAwareScrollAttr : ComposeAttr() {
    /** 输入框底边与键盘顶部之间保留的间距（Dart extraPadding，默认 16） */
    var extraPadding: Float = 16f
    /** 滚动区之外、固定在页面底部的栏高度（Dart marginBottom） */
    var marginBottom: Float = 0f
    /** 是否显示滚动条 */
    var showIndicator: Boolean = false
    /** 是否回弹（Dart ScrollOverWidget：Android 关闭越界效果） */
    var bounces: Boolean = true
}

/**
 * Dart `KeyboardAwareScrollView` → `SDKeyboardAwareScroll`：竖向滚动容器，键盘弹出时把聚焦的输入框滚到键盘上方。
 *
 * ```
 * SDKeyboardAwareScroll {
 *     attr { flex(1f); marginBottom = 80f }
 *     content {
 *         SDInput { … }       // SDInput / SDTextArea 聚焦时会自动上报，无需额外包装
 *         SDTextArea { … }
 *     }
 * }
 * ```
 * 直接用原生 `Input` / `TextArea` 时，在它的 event 里调用 [ensureVisible] / [updateKeyboardHeight]
 * （对应 Dart 的 `KeyboardAwareField`），见 [keyboardAware]。
 *
 * 键盘高度来源：输入框的 `keyboardHeightChange` 事件 + `SDAppModule` 的 `keyboard` 事件，二者取其一即可。
 * 约定宿主窗口**不随键盘调整大小**（键盘盖在页面上），由本组件加底部占位并滚动。
 */
class SDKeyboardAwareScrollView : ComposeView<SDKeyboardAwareScrollAttr, ComposeEvent>() {
    private var keyboardHeight by observable(0f)
    private var contentBuilder: ViewBuilder? = null
    private var scrollerRef: ViewRef<ScrollerView<*, *>>? = null
    private var activeTarget: DeclarativeBaseView<*, *>? = null
    private var offsetY = 0f
    private var contentHeight = 0f
    private var eventRef: CallbackRef? = null
    private var settleSeq = 0

    override fun createAttr(): SDKeyboardAwareScrollAttr = SDKeyboardAwareScrollAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    /** 滚动区内容（Dart `builder`）。 */
    fun content(builder: ViewBuilder) {
        contentBuilder = builder
    }

    /** 底层滚动视图（需要手动滚动时用）。 */
    val scroller: ScrollerView<*, *>? get() = scrollerRef?.view

    override fun created() {
        super.created()
        try {
            val module = getPager().acquireModule<SDAppModule>(SDAppModule.MODULE_NAME)
            eventRef = module.addEventListener { e ->
                if (e.optString("event", "") == "keyboard") {
                    updateKeyboardHeight(e.optDouble("height", 0.0).toFloat())
                }
            }
        } catch (e: Throwable) {
            // 没有该模块时只依赖输入框自己的键盘事件
        }
    }

    override fun viewDestroyed() {
        val ref = eventRef
        if (ref != null) {
            try {
                getPager().acquireModule<SDAppModule>(SDAppModule.MODULE_NAME).removeEventListener(ref)
            } catch (e: Throwable) {
            }
        }
        eventRef = null
        activeTarget = null
        super.viewDestroyed()
    }

    /** 输入框获得焦点时调用：记下目标；键盘已弹出则立即滚动。 */
    fun ensureVisible(target: DeclarativeBaseView<*, *>) {
        activeTarget = target
        if (keyboardHeight > 0f) scrollToTarget(animated = true, allowNegative = false)
    }

    /** 输入框失去焦点时调用。 */
    fun clearActiveTarget(target: DeclarativeBaseView<*, *>? = null) {
        if (target == null || activeTarget === target) activeTarget = null
    }

    /** 键盘高度变化（dp）。重复上报同一高度是安全的。 */
    fun updateKeyboardHeight(height: Float) {
        val h = if (height < 0f) 0f else height
        if (abs(h - keyboardHeight) < 0.5f) return
        keyboardHeight = h
        if (h <= 0f) return
        // 占位高度生效（重新布局）后再滚；键盘动画结束后再校正一次（允许往回滚）
        val seq = ++settleSeq
        setTimeout(pagerId, 32) {
            if (seq == settleSeq) scrollToTarget(animated = true, allowNegative = false)
        }
        setTimeout(pagerId, 320) {
            if (seq == settleSeq) scrollToTarget(animated = true, allowNegative = true)
        }
    }

    private fun scrollToTarget(animated: Boolean, allowNegative: Boolean) {
        val target = activeTarget ?: return
        val scroller = scrollerRef?.view ?: return
        val content = scroller.contentView ?: return
        val kb = keyboardHeight
        if (kb <= 0f) return
        val pageHeight = getPager().pageData.pageViewHeight
        val scrollerInPage = scroller.convertFrame(scroller.frame, null)
        val targetInContent = target.convertFrame(target.frame, content)
        val viewportBottom = scrollerInPage.y + scrollerInPage.height
        val keyboardTop = pageHeight - kb
        val fieldBottomOnScreen = scrollerInPage.y + targetInContent.y + targetInContent.height - offsetY
        val overlap = fieldBottomOnScreen - (min(keyboardTop, viewportBottom) - attr.extraPadding)
        if (overlap < 0f && !allowNegative) return
        val maxOffset = (contentHeight - scrollerInPage.height).coerceAtLeast(0f)
        val targetOffset = (offsetY + overlap).coerceIn(0f, maxOffset)
        if (abs(targetOffset - offsetY) < 0.5f) return
        scroller.setContentOffset(0f, targetOffset, animated)
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            Scroller {
                ref { ctx.scrollerRef = it }
                attr {
                    flex(1f)
                    showScrollerIndicator(ctx.attr.showIndicator)
                    bouncesEnable(ctx.attr.bounces)
                }
                event {
                    scroll { ctx.offsetY = it.offsetY }
                    contentSizeChanged { _, h -> ctx.contentHeight = h }
                }
                ctx.contentBuilder?.invoke(this)
                // 底部占位：键盘高度 + 被固定底栏挡住的部分（Dart: kb + min(kb, marginBottom + extraPadding)）
                View {
                    attr {
                        val kb = ctx.keyboardHeight
                        height(kb + min(kb, ctx.attr.marginBottom + ctx.attr.extraPadding))
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDKeyboardAwareScroll(init: SDKeyboardAwareScrollView.() -> Unit) {
    addChild(SDKeyboardAwareScrollView(), init)
}

/**
 * Dart `KeyboardAwareField` 的等价物：原生 `Input` / `TextArea` 想接入 [SDKeyboardAwareScroll] 时，
 * 在它的 event 里把三个回调转给这个对象。
 *
 * ```
 * val aware = keyboardAware()          // 在 Input { } 闭包里
 * event {
 *     inputFocus { aware.onFocus() }
 *     inputBlur { aware.onBlur() }
 *     keyboardHeightChange { aware.onKeyboardHeight(it.height) }
 * }
 * ```
 */
class SDKeyboardAwareBinding internal constructor(private val view: DeclarativeBaseView<*, *>) {
    fun onFocus() { view.findKeyboardAwareScroll()?.ensureVisible(view) }
    fun onBlur() { view.findKeyboardAwareScroll()?.clearActiveTarget(view) }
    fun onKeyboardHeight(height: Float) { view.findKeyboardAwareScroll()?.updateKeyboardHeight(height) }
}

fun DeclarativeBaseView<*, *>.keyboardAware(): SDKeyboardAwareBinding = SDKeyboardAwareBinding(this)

// ============================================================================
// 键盘顶部输入条
// ============================================================================

/** 一次 [SDKeyboardTopInputController.show] 的参数。 */
class SDKeyboardTopInputRequest(
    val text: String,
    val hintText: String,
    val maxLines: Int,
    val maxLength: Int,
    val numberKeyboard: Boolean,
    val onChanged: ((String) -> Unit)?,
    val onDone: ((String) -> Unit)?,
    val onCancel: ((String) -> Unit)?,
)

/**
 * Dart `KeyboardTopInputController`。页面持有一个实例，传给 [SDKeyboardTopInput]。
 *
 * 与 Dart 的差别：没有 `TextEditingController` 可回写，所以最终文本通过回调交还——
 * `onDone(text)`（点「完成」/ 回车）和 `onCancel(text)`（点空白 / 键盘被系统收起）都会带上当前文本，
 * 调用方在两个回调里都把文本写回目标字段，即等价于 Dart 的「同步回目标 controller」。
 */
class SDKeyboardTopInputController {
    internal var view: SDKeyboardTopInputView? = null

    val isShowing: Boolean get() = view?.isShowing ?: false

    fun show(
        text: String,
        hintText: String = "",
        maxLines: Int = 4,
        maxLength: Int = 20,
        numberKeyboard: Boolean = false,
        onChanged: ((String) -> Unit)? = null,
        onDone: ((String) -> Unit)? = null,
        onCancel: ((String) -> Unit)? = null,
    ) {
        view?.showInput(SDKeyboardTopInputRequest(text, hintText, maxLines, maxLength, numberKeyboard, onChanged, onDone, onCancel))
    }

    fun hide() {
        view?.hideInput(byDone = false)
    }
}

class SDKeyboardTopInputAttr : ComposeAttr() {
    lateinit var controller: SDKeyboardTopInputController
    /** 页面是否是带底部导航的根页（Dart isNavRoot：面板要再减去底栏高度） */
    var isNavRoot: Boolean = false
    /** isNavRoot 时要扣掉的底栏高度（Dart kBottomNavigationBarHeight = 56）+ 底部安全区 */
    var navBarHeight: Float = 56f
    /** 自定义「完成」按钮内容；为空用默认的 `complete` 小胶囊 */
    var doneWidget: ViewBuilder? = null
}

/**
 * Dart `KeyboardTopInputWrapper` → `SDKeyboardTopInput`：贴在软键盘上沿的多行编辑条。
 *
 * 用法：放在页面内容的**最后**（它是一个铺满页面的绝对定位层，隐藏时不拦截触摸）：
 * ```
 * // content() 里
 * …页面内容…
 * SDKeyboardTopInput { attr { controller = ctx.topInput } }
 *
 * // 点击某个只读文本时
 * ctx.topInput.show(text = shot.prompt, hintText = "please_enter_prompt".tr, maxLength = 200,
 *     onDone = { shot.prompt = it }, onCancel = { shot.prompt = it })
 * ```
 */
class SDKeyboardTopInputView : ComposeView<SDKeyboardTopInputAttr, ComposeEvent>() {
    private var visible by observable(false)
    private var keyboardHeight by observable(0f)
    private var text by observable("")
    private var hintText by observable("")
    private var maxLines by observable(4)
    private var maxLength by observable(20)
    private var numberKeyboard by observable(false)
    private var request: SDKeyboardTopInputRequest? = null
    private var areaRef: ViewRef<TextAreaView>? = null
    private var keyboardWasShown = false

    val isShowing: Boolean get() = visible

    override fun createAttr(): SDKeyboardTopInputAttr = SDKeyboardTopInputAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun created() {
        super.created()
        attr.controller.view = this
    }

    override fun viewDestroyed() {
        if (attr.controller.view === this) attr.controller.view = null
        super.viewDestroyed()
    }

    internal fun showInput(req: SDKeyboardTopInputRequest) {
        request = req
        text = req.text
        hintText = req.hintText
        maxLines = req.maxLines
        maxLength = req.maxLength
        numberKeyboard = req.numberKeyboard
        keyboardWasShown = false
        visible = true
        // 面板创建后再聚焦并回填文本
        setTimeout(pagerId, 32) {
            val area = areaRef?.view
            area?.forceSetText(req.text)
            area?.focus()
        }
    }

    /** 关闭面板。[byDone] = 点了「完成」/ 回车；否则视为取消（点空白 / 键盘被收起 / controller.hide）。 */
    internal fun hideInput(byDone: Boolean) {
        if (!visible) return
        val req = request
        val value = text
        visible = false
        keyboardHeight = 0f
        request = null
        areaRef?.view?.blur()
        hideKeyboard()
        if (byDone) req?.onDone?.invoke(value) else req?.onCancel?.invoke(value)
    }

    private fun onKeyboardHeight(h: Float) {
        if (!visible) return
        if (h > 0f) {
            keyboardWasShown = true
            keyboardHeight = h
            return
        }
        keyboardHeight = 0f
        // 键盘被系统收起：同步回目标并关闭
        if (keyboardWasShown) hideInput(byDone = false)
    }

    private val panelHeight: Float
        get() = (maxLines * 30f).coerceIn(120f, 300f)

    private val panelBottom: Float
        get() {
            var b = keyboardHeight
            if (attr.isNavRoot) b -= attr.navBarHeight + getPager().pageData.safeAreaInsets.bottom
            return if (b < 0f) 0f else b
        }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                absolutePositionAllZero()
                // 隐藏时整层不拦截触摸
                touchEnable(ctx.visible)
            }
            vif({ ctx.visible }) {
                // 点空白处：收起并回调取消
                View {
                    attr { absolutePositionAllZero() }
                    event { click { ctx.hideInput(byDone = false) } }
                }
                View {
                    attr {
                        absolutePosition(left = 0f, right = 0f, bottom = ctx.panelBottom)
                        height(ctx.panelHeight)
                        padding(20f, 20f, 6f, 20f)
                        backgroundColor(SDColors.sheetBg)
                        borderRadius(6f, 6f, 0f, 0f)
                        // 键盘还没起来时先透明，避免面板在屏幕底部闪一下（Dart: opacity = kb / 200）
                        opacity((ctx.keyboardHeight / 200f).coerceIn(0f, 1f))
                        animate(Animation.easeOut(0.2f), ctx.keyboardHeight)
                    }
                    event { click { } }
                    View {
                        attr {
                            flex(1f)
                            borderRadius(6f)
                            backgroundColor(SDColors.white10)
                            padding(0f, 6f, 0f, 6f)
                            overflow(true)
                        }
                        TextArea {
                            ref { ctx.areaRef = it }
                            attr {
                                flex(1f)
                                fontSize(12f)
                                color(Color.WHITE)
                                tintColor(Color.WHITE)
                                placeholder(ctx.hintText)
                                placeholderColor(SDColors.white30)
                                maxTextLength(ctx.maxLength, LengthLimitType.CHARACTER)
                                if (ctx.numberKeyboard) keyboardTypeNumber()
                                returnKeyTypeDone()
                            }
                            event {
                                textDidChange {
                                    ctx.text = it.text
                                    ctx.request?.onChanged?.invoke(it.text)
                                }
                                keyboardHeightChange { ctx.onKeyboardHeight(it.height) }
                            }
                        }
                    }
                    View {
                        attr {
                            marginTop(6f)
                            flexDirectionRow()
                            justifyContentSpaceBetween()
                            alignItemsCenter()
                        }
                        View {
                            attr {
                                flexDirectionRow()
                                alignItemsCenter()
                            }
                            vif({ ctx.text.isNotEmpty() }) {
                                View {
                                    attr { padding(6f) }
                                    event {
                                        click {
                                            ctx.text = ""
                                            ctx.areaRef?.view?.forceSetText("")
                                            ctx.request?.onChanged?.invoke("")
                                        }
                                    }
                                    Image {
                                        attr {
                                            size(14f, 14f)
                                            src(ImageUri.commonAssets(AssetsImage.clear3))
                                            resizeContain()
                                        }
                                    }
                                }
                            }
                            Text {
                                attr {
                                    text("${ctx.text.length}/${ctx.maxLength}")
                                    fontSize(12f)
                                    fontFamily(SDFont.poppins)
                                    color(SDColors.white30)
                                }
                            }
                        }
                        View {
                            event { click { ctx.hideInput(byDone = true) } }
                            if (ctx.attr.doneWidget != null) {
                                ctx.attr.doneWidget?.invoke(this)
                            } else {
                                View {
                                    attr {
                                        padding(8f, 14f, 8f, 14f)
                                        borderRadius(6f)
                                        backgroundColor(SDColors.white10)
                                        allCenter()
                                    }
                                    Text {
                                        attr {
                                            text("complete".tr)
                                            fontSize(12f)
                                            fontFamily(SDFont.poppins)
                                            fontWeight600()
                                            color(Color.WHITE)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDKeyboardTopInput(init: SDKeyboardTopInputView.() -> Unit) {
    addChild(SDKeyboardTopInputView(), init)
}
