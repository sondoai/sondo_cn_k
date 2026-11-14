package com.sondo.app.ui.nav

import com.sondo.app.core.constants.UIColor
import com.sondo.app.ui.input.hideKeyboard
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.DivView
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.View

class SDPageScaffoldAttr : ComposeAttr() {
    /** 标题；配合默认导航栏使用 */
    var title: String by observable("")
    /** 是否显示默认导航栏（返回 + 标题 + [actions]）。用 `navBar { }` 可整体替换 */
    var showNavBar: Boolean = true
    /** 默认导航栏右侧操作区 */
    var actions: ViewBuilder? = null
    /** 没有导航栏时，是否仍在顶部留出状态栏高度 */
    var safeTop: Boolean = true
    /** 底部栏下方是否补底部安全区（手势条） */
    var safeBottom: Boolean = true
    /** 没有底部栏时，内容区底部是否也留出安全区 */
    var contentSafeBottom: Boolean = false
    /** 页面底色（默认用 BasePager 的底色，即不额外设置） */
    var bgColor: Color? = null
    /** 整页背景图（AssetsImage.xxx，等宽铺满） */
    var bgImage: String = ""
    /** 导航栏是否悬浮在内容之上（内容从屏幕顶开始，沉浸式头图） */
    var navOverlay: Boolean = false
    /** 点击内容空白处是否收起键盘（Dart 页面外层的 UnfocusWidget） */
    var unfocusOnTap: Boolean = false
    /** 底部栏底色 */
    var bottomBarColor: Color = Color.TRANSPARENT
}

class SDPageScaffoldEvent : ComposeEvent() {
    internal var backHandler: (() -> Unit)? = null

    /** 默认导航栏的返回点击；不设置则关闭当前页。 */
    fun onBack(handler: () -> Unit) { backHandler = handler }
}

/**
 * 页面脚手架（Dart `Scaffold(appBar: NavAppBarWidget(...), body: ..., bottomNavigationBar: ...)`）：
 * 状态栏留白 + 导航栏 + 内容区（flex 1）+ 可选底部栏（自动补底部安全区）。
 *
 * ```
 * override fun content(): ViewBuilder {
 *     val ctx = this
 *     return {
 *         SDPageScaffold {
 *             attr { title = "settings".tr }
 *             content { … }                       // 内容区，默认纵向排列，flex(1f)
 *             bottomBar { SDButton { … } }        // 可选
 *         }
 *     }
 * }
 * ```
 */
class SDPageScaffoldView : ComposeView<SDPageScaffoldAttr, SDPageScaffoldEvent>() {
    private var navBarBuilder: ViewBuilder? = null
    private var contentBuilder: ViewBuilder? = null
    private var bottomBarBuilder: ViewBuilder? = null
    private var overlayBuilder: ViewBuilder? = null

    override fun createAttr(): SDPageScaffoldAttr = SDPageScaffoldAttr()
    override fun createEvent(): SDPageScaffoldEvent = SDPageScaffoldEvent()

    override fun willInit() {
        super.willInit()
        attr.flex(1f)
    }

    /** 自定义导航栏（整体替换默认的 [SDNavBar]；状态栏留白要自己处理，SDNavBar 默认已带）。 */
    fun navBar(builder: ViewBuilder) { navBarBuilder = builder }

    /** 内容区。 */
    fun content(builder: ViewBuilder) { contentBuilder = builder }

    /** 底部栏（按钮区）。 */
    fun bottomBar(builder: ViewBuilder) { bottomBarBuilder = builder }

    /** 铺满整页、叠在最上层的内容（悬浮按钮、[com.sondo.app.ui.input.SDKeyboardTopInput] 等）。 */
    fun overlay(builder: ViewBuilder) { overlayBuilder = builder }

    override fun body(): ViewBuilder {
        val ctx = this
        val pd = ctx.pagerData
        val hasNav = ctx.navBarBuilder != null || ctx.attr.showNavBar
        return {
            attr {
                ctx.attr.bgColor?.let { backgroundColor(it) }
            }
            if (ctx.attr.bgImage.isNotEmpty()) {
                Image {
                    attr {
                        absolutePositionAllZero()
                        src(ImageUri.commonAssets(ctx.attr.bgImage))
                        resizeCover()
                    }
                }
            }
            if (!hasNav && ctx.attr.safeTop) {
                View { attr { height(pd.statusBarHeight) } }
            }
            // 非悬浮导航栏：在内容上方占位
            if (hasNav && !ctx.attr.navOverlay) {
                ctx.buildNavBar(this, overlay = false)
            }
            View {
                attr {
                    flex(1f)
                    if (ctx.bottomBarBuilder == null && ctx.attr.contentSafeBottom) {
                        paddingBottom(pd.safeAreaInsets.bottom)
                    }
                }
                if (ctx.attr.unfocusOnTap) {
                    event { click { ctx.hideKeyboard() } }
                }
                ctx.contentBuilder?.invoke(this)
            }
            if (ctx.bottomBarBuilder != null) {
                View {
                    attr {
                        backgroundColor(ctx.attr.bottomBarColor)
                        if (ctx.attr.safeBottom) paddingBottom(pd.safeAreaInsets.bottom)
                    }
                    ctx.bottomBarBuilder?.invoke(this)
                }
            }
            // 悬浮导航栏：盖在内容上
            if (hasNav && ctx.attr.navOverlay) {
                ctx.buildNavBar(this, overlay = true)
            }
            ctx.overlayBuilder?.invoke(this)
        }
    }

    private fun buildNavBar(container: ViewContainer<*, *>, overlay: Boolean) {
        val ctx = this
        val custom = navBarBuilder
        if (custom != null) {
            if (overlay) {
                container.View {
                    attr { absolutePosition(top = 0f, left = 0f, right = 0f) }
                    custom.invoke(this)
                }
            } else {
                custom.invoke(container)
            }
            return
        }
        container.SDNavBar {
            attr {
                title = ctx.attr.title
                actions = ctx.attr.actions
                this.overlay = overlay
            }
            event {
                onBack {
                    val h = ctx.event.backHandler
                    if (h != null) h() else (ctx.getPager() as? com.sondo.app.base.BasePager)?.nav?.pop()
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDPageScaffold(init: SDPageScaffoldView.() -> Unit) {
    addChild(SDPageScaffoldView(), init)
}

// ============================================================================
// 底部操作栏
// ============================================================================

/**
 * 底部操作栏容器：固定在页面底部的按钮区（上内边距 12、左右 20、底部 = 安全区 + 12），可选顶部分割线。
 * 作为 `SDPageScaffold.bottomBar { }` 之外的独立用法（如放在弹层底部）。
 *
 * ```
 * SDBottomBar {
 *     SDSecondaryButton { attr { flex(1f); text = "cancel".tr } }
 *     HGap(12f)
 *     SDPrimaryButton { attr { flex(1f); text = "confirm".tr } }
 * }
 * ```
 * @param row 子节点是否横排（多个按钮并排）
 * @param safeBottom 是否叠加底部安全区
 */
fun ViewContainer<*, *>.SDBottomBar(
    row: Boolean = true,
    safeBottom: Boolean = true,
    paddingH: Float = 20f,
    paddingV: Float = 12f,
    bgColor: Color = Color.TRANSPARENT,
    divider: Boolean = false,
    init: DivView.() -> Unit,
) {
    val inset = if (safeBottom) getPager().pageData.safeAreaInsets.bottom else 0f
    View {
        attr {
            if (row) {
                flexDirectionRow()
                alignItemsCenter()
            }
            padding(paddingV, paddingH, paddingV + inset, paddingH)
            backgroundColor(bgColor)
            if (divider) borderTop(Border(0.5f, BorderStyle.SOLID, UIColor.transparent10))
        }
        init()
    }
}
