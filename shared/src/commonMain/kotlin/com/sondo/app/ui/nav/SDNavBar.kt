package com.sondo.app.ui.nav

import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.UIColor
import com.sondo.app.ui.SDFont
import com.sondo.app.ui.SDWeight
import com.sondo.app.ui.weight
import com.sondo.app.ui.atoms.SDIconButton
import com.sondo.app.ui.basePager
import com.sondo.app.ui.input.hideKeyboard
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * Dart `NavBackWidget` → `SDNavBack`：返回按钮（48 热区，`back-left.png` 24×24，默认白色）。
 * 不传 [onPressed] 时关闭当前页（Dart 默认 `Get.back()`）。
 *
 * ```
 * SDNavBack()                         // 默认返回
 * SDNavBack(onPressed = { ctx.confirmExit() })
 * ```
 */
fun ViewContainer<*, *>.SDNavBack(color: Color? = null, onPressed: (() -> Unit)? = null) {
    val host = this
    SDIconButton {
        attr {
            icon = AssetsImage.backLeft
            iconSize = 24f
            this.color = color ?: Color.WHITE
            hitSize = 48f
        }
        event {
            onTap {
                if (onPressed != null) onPressed() else host.basePager?.nav?.pop()
            }
        }
    }
}

class SDNavBarAttr : ComposeAttr() {
    /** 标题（居中，16 w700 白 Poppins） */
    var title: String by observable("")
    var titleColor: Color by observable(Color.WHITE)
    var titleSize: Float = 16f
    /** 标题字重 / 字体（默认 w700 Poppins；Dart 页面自写 `TextWidget` 标题时按实际传，系统字体给 null） */
    var titleWeight: SDWeight = SDWeight.W700
    var titleFontFamily: String? = SDFont.poppins
    /** 标题靠左（紧跟返回按钮），默认居中 */
    var titleLeft: Boolean = false
    /** 自定义标题区（替换文字） */
    var titleBuilder: ViewBuilder? = null
    /** 是否显示返回按钮 */
    var showBack: Boolean by observable(true)
    /** 返回图标颜色 */
    var backColor: Color by observable(Color.WHITE)
    /** 自定义左侧（替换返回按钮） */
    var leading: ViewBuilder? = null
    /** 右侧操作区（横排，自己控制间距；右边距由 [actionsPaddingRight] 给） */
    var actions: ViewBuilder? = null
    var actionsPaddingRight: Float = 4f
    /** 导航栏高度（Dart kToolbarHeight = 56） */
    var barHeight: Float = 56f
    /** 是否在顶部加状态栏高度的留白 */
    var withStatusBar: Boolean = true
    /** 底色（默认透明；沉浸式页面随滚动改这个值即可） */
    var bgColor: Color by observable(Color.TRANSPARENT)
    /** 背景图（Dart `assetBgImage`：fitWidth 等宽、顶对齐，铺在状态栏 + 导航栏后面，超出部分裁掉） */
    var assetBgImage: String = ""
    /** 浮在内容之上（绝对定位在页面顶部），用于透明 / 沉浸式页面 */
    var overlay: Boolean = false
    /** 底部分割线 */
    var divider: Boolean = false
}

class SDNavBarEvent : ComposeEvent() {
    internal var backHandler: (() -> Unit)? = null
    internal var doubleTapTitle: (() -> Unit)? = null

    /** 点击返回；不设置则关闭当前页。 */
    fun onBack(handler: () -> Unit) { backHandler = handler }
    /** 双击标题（Dart onDoubleTapTitle：先收键盘再回调，常用于回到顶部）。 */
    fun onDoubleTapTitle(handler: () -> Unit) { doubleTapTitle = handler }
}

/**
 * Dart `NavAppBarWidget` → `SDNavBar`（别名 [SDNavAppBar]）：页面导航栏 = 返回 + 居中标题 + 右侧操作。
 *
 * ```
 * SDNavBar {
 *     attr {
 *         title = "settings".tr
 *         actions = { SDIconButton { attr { icon = AssetsImage.share } ; event { onTap { ctx.share() } } } }
 *     }
 * }
 * // 透明悬浮（沉浸式详情页）
 * SDNavBar { attr { overlay = true; bgColor = ctx.navBg } }
 * ```
 */
class SDNavBarView : ComposeView<SDNavBarAttr, SDNavBarEvent>() {
    override fun createAttr(): SDNavBarAttr = SDNavBarAttr()
    override fun createEvent(): SDNavBarEvent = SDNavBarEvent()

    /** 背景图高 / 宽（加载到分辨率后才知道） */
    private var bgImageRatio by observable(0f)

    private fun onBackTap() {
        val h = event.backHandler
        if (h != null) h() else basePager?.nav?.pop()
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val statusBar = if (ctx.attr.withStatusBar) ctx.pagerData.statusBarHeight else 0f
        return {
            attr {
                if (ctx.attr.overlay) {
                    absolutePosition(top = 0f, left = 0f, right = 0f)
                    zIndex(10)
                }
                paddingTop(statusBar)
                backgroundColor(ctx.attr.bgColor)
                if (ctx.attr.assetBgImage.isNotEmpty()) overflow(true)
            }
            if (ctx.attr.assetBgImage.isNotEmpty()) {
                // Dart BoxFit.fitWidth + Alignment.topCenter：等宽、高按图片比例、顶对齐，超出导航栏的部分裁掉
                Image {
                    attr {
                        absolutePosition(top = 0f, left = 0f, right = 0f)
                        val w = ctx.pagerData.pageViewWidth
                        height(if (ctx.bgImageRatio > 0f) w * ctx.bgImageRatio else w)
                        src(ImageUri.commonAssets(ctx.attr.assetBgImage))
                        resizeStretch()
                    }
                    event {
                        loadResolution {
                            if (it.width > 0) ctx.bgImageRatio = it.height.toFloat() / it.width.toFloat()
                        }
                    }
                }
            }
            View {
                attr {
                    height(ctx.attr.barHeight)
                    flexDirectionRow()
                    alignItemsCenter()
                }
                // 居中标题：绝对居中，左右各留 56 给返回 / 操作区
                if (!ctx.attr.titleLeft) {
                    View {
                        attr {
                            absolutePosition(top = 0f, left = 56f, right = 56f, bottom = 0f)
                            allCenter()
                        }
                        if (ctx.attr.titleBuilder != null) {
                            ctx.attr.titleBuilder?.invoke(this)
                        } else {
                            Text {
                                attr {
                                    text(ctx.attr.title)
                                    fontSize(ctx.attr.titleSize)
                                    weight(ctx.attr.titleWeight)
                                    ctx.attr.titleFontFamily?.let { fontFamily(it) }
                                    color(ctx.attr.titleColor)
                                    lines(1)
                                    textOverFlowTail()
                                }
                                event {
                                    doubleClick {
                                        ctx.hideKeyboard()
                                        ctx.event.doubleTapTitle?.invoke()
                                    }
                                }
                            }
                        }
                    }
                }
                // 左侧
                if (ctx.attr.leading != null) {
                    ctx.attr.leading?.invoke(this)
                } else {
                    vif({ ctx.attr.showBack }) {
                        View {
                            attr {
                                width(56f)
                                alignItemsCenter()
                            }
                            SDIconButton {
                                attr {
                                    icon = AssetsImage.backLeft
                                    iconSize = 24f
                                    color = ctx.attr.backColor
                                    hitSize = 48f
                                }
                                event { onTap { ctx.onBackTap() } }
                            }
                        }
                    }
                }
                if (ctx.attr.titleLeft) {
                    View {
                        attr {
                            flex(1f)
                            justifyContentCenter()
                            if (!ctx.attr.showBack && ctx.attr.leading == null) paddingLeft(16f)
                        }
                        if (ctx.attr.titleBuilder != null) {
                            ctx.attr.titleBuilder?.invoke(this)
                        } else {
                            Text {
                                attr {
                                    text(ctx.attr.title)
                                    fontSize(ctx.attr.titleSize)
                                    weight(ctx.attr.titleWeight)
                                    ctx.attr.titleFontFamily?.let { fontFamily(it) }
                                    color(ctx.attr.titleColor)
                                    lines(1)
                                    textOverFlowTail()
                                }
                            }
                        }
                    }
                } else {
                    View { attr { flex(1f) } }
                }
                // 右侧
                if (ctx.attr.actions != null) {
                    View {
                        attr {
                            flexDirectionRow()
                            alignItemsCenter()
                            paddingRight(ctx.attr.actionsPaddingRight)
                        }
                        ctx.attr.actions?.invoke(this)
                    }
                }
            }
            if (ctx.attr.divider) {
                View {
                    attr {
                        height(0.5f)
                        backgroundColor(UIColor.transparent10)
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDNavBar(init: SDNavBarView.() -> Unit) {
    addChild(SDNavBarView(), init)
}

/** 与 Dart 类名对应的别名：`NavAppBarWidget(title:, actions:, assetBgImage:)`。 */
fun ViewContainer<*, *>.SDNavAppBar(init: SDNavBarView.() -> Unit) = SDNavBar(init)
