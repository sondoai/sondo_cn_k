package com.sondo.app.ui.works

import com.sondo.app.base.BasePager
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AppTheme
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.service.UserStore
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.atoms.SDImage
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
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

class SDNavRootAppBarAttr : ComposeAttr() {
    var title: String by observable("")
    /** 自定义标题（替代文字标题） */
    var titleWidget: ViewBuilder? = null
    /** 沉浸式头部：滚动显现进度 0..1；与 [headerImmersive] 同时给出才生效（给 null 关闭） */
    var headerReveal: Float? by observable<Float?>(null)
    var headerImmersive: Boolean? by observable<Boolean?>(null)
    /** 是否包含状态栏占位（Scaffold.appBar 自带 SafeArea） */
    var withStatusBar: Boolean = true
}

class SDNavRootAppBarEvent : ComposeEvent() {
    internal var onTapTitleHandler: (() -> Unit)? = null
    internal var onDoubleTapTitleHandler: (() -> Unit)? = null
    internal var onTapHelpHandler: (() -> Unit)? = null
    fun onTapTitle(handler: () -> Unit) { onTapTitleHandler = handler }
    fun onDoubleTapTitle(handler: () -> Unit) { onDoubleTapTitleHandler = handler }
    /** 注册后标题右侧显示帮助图标 */
    fun onTapHelp(handler: () -> Unit) { onTapHelpHandler = handler }
}

/**
 * Dart `NavRootAppBarWidget`：根页面导航栏（左标题 + 12 + 积分胶囊 + 12 + 头像 32 + 16），工具栏高 56。
 * 沉浸式：背景 = colorScheme.primary × reveal（仅 immersive 时），reveal < 0.8 不画标题。
 */
class SDNavRootAppBarView : ComposeView<SDNavRootAppBarAttr, SDNavRootAppBarEvent>() {
    override fun createAttr(): SDNavRootAppBarAttr = SDNavRootAppBarAttr()
    override fun createEvent(): SDNavRootAppBarEvent = SDNavRootAppBarEvent()

    private val reveal: Float
        get() {
            val r = attr.headerReveal
            val i = attr.headerImmersive
            if (r == null || i == null) return 1f
            return r.coerceIn(0f, 1f)
        }

    private val paintBackground: Boolean
        get() = attr.headerReveal != null && attr.headerImmersive == true

    override fun body(): ViewBuilder {
        val ctx = this
        val ws = worksState
        val pager = getPager() as BasePager
        return {
            attr {
                if (ctx.paintBackground) {
                    backgroundColor(Color(AppTheme.primary.hexColor and 0x00FFFFFFL, ctx.reveal))
                } else {
                    backgroundColor(Color.TRANSPARENT)
                }
            }
            if (ctx.attr.withStatusBar) {
                View { attr { height(pager.statusBarHeight) } }
            }
            View {
                attr {
                    height(56f)
                    flexDirectionRow()
                    alignItemsCenter()
                    padding(0f, 16f, 0f, 0f)
                }
                // 标题区
                View {
                    attr {
                        flex(1f)
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    vif({ ctx.reveal >= 0.8f }) {
                        View {
                            attr {
                                flexDirectionRow()
                                alignItemsCenter()
                            }
                            event {
                                click {
                                    val h = ctx.event.onTapTitleHandler
                                    if (h != null) {
                                        ctx.hideKeyboard()
                                        h()
                                    }
                                }
                                doubleClick {
                                    val h = ctx.event.onDoubleTapTitleHandler
                                    if (h != null) {
                                        ctx.hideKeyboard()
                                        h()
                                    }
                                }
                            }
                            val tw = ctx.attr.titleWidget
                            if (tw != null) {
                                tw()
                            } else {
                                Text {
                                    attr {
                                        text(ctx.attr.title)
                                        fontSize(24f)
                                        fontFamily("Montserrat-Bold")
                                        color(Color.WHITE)
                                        lines(1)
                                    }
                                }
                            }
                            if (ctx.event.onTapHelpHandler != null) {
                                View {
                                    attr { padding(0f, 4f, 0f, 0f) }
                                    event { click { ctx.event.onTapHelpHandler?.invoke() } }
                                    // Material Icons.help_outline_outlined → 白色问号圆圈
                                    View {
                                        attr {
                                            size(17f, 17f)
                                            borderRadius(8.5f)
                                            border(Border(1.6f, BorderStyle.SOLID, Color.WHITE))
                                            allCenter()
                                        }
                                        Text {
                                            attr {
                                                text("?")
                                                fontSize(11f)
                                                fontWeightBold()
                                                color(Color.WHITE)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                View { attr { width(12f) } }
                SDNavPoints()
                View { attr { width(12f) } }
                // 头像（与积分胶囊同款外壳，头像内缩 1px 不盖描边）
                View {
                    attr {
                        size(32f, 32f)
                        borderRadius(16f)
                        backgroundColor(Color(0x99000000L))
                        border(Border(1f, BorderStyle.SOLID, SDColors.white20))
                        allCenter()
                    }
                    event {
                        click {
                            ctx.hideKeyboard()
                            if (UserStore.isLogin) {
                                pager.nav.push(Routes.mine)
                            } else {
                                pager.launch { UserStore.showLoginDialog(pager) }
                            }
                        }
                    }
                    vif({ ws.isLogin }) {
                        View {
                            attr {
                                size(30f, 30f)
                                borderRadius(15f)
                            }
                            vif({ ws.avatar.isNotEmpty() }) {
                                SDImage {
                                    attr {
                                        absolutePositionAllZero()
                                        borderRadius(15f)
                                        // 参考的占位 ColoredBox 没有尺寸（看不见），加载中只露出外壳的黑色玻璃圆
                                        placeholderColor = Color.TRANSPARENT
                                        url = ws.avatar
                                    }
                                }
                            }
                        }
                    }
                    velse {
                        Image {
                            attr {
                                size(16f, 16f)
                                src(ImageUri.commonAssets(AssetsImage.noLoginUser))
                            }
                        }
                    }
                }
                View { attr { width(16f) } }
            }
        }
    }
}

/** Dart `NavRootAppBarWidget`：根页面导航栏。 */
fun ViewContainer<*, *>.SDNavRootAppBar(init: SDNavRootAppBarView.() -> Unit) {
    addChild(SDNavRootAppBarView(), init)
}
