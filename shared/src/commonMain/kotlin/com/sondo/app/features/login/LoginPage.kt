package com.sondo.app.features.login

import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.constants.asset
import com.sondo.app.core.i18n.tr
import com.sondo.app.service.UserStore
import com.sondo.app.ui.SDColors
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.Translate
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.event.ClickParams
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Canvas
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.RichText
import com.tencent.kuikly.core.views.Span
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 应用内登录页（Dart: `features/login/presentation/widgets/login_dialog.dart` 的 LoginDialog 底栏，
 * 由 UserStore.showLoginDialog 在本机号码取不到时打开）。
 *
 * 透明页：容器无转场，页面自己画遮罩并把底栏从下方推上来。
 * 结果：`nav.pop(UserStore.loginResult(success))`。无参数。
 */
@Page(Routes.login)
internal class LoginPage : BasePager() {
    /** 底栏是否展开（进场 / 退场动画） */
    var shown by observable(false)

    /** 协议是否已勾选。每次打开都从未勾选开始。 */
    var agreed by observable(false)

    /** 登录流程进行中（微信授权 / 打开手机号页 / 换票）：按钮半透明不可点，返回与遮罩不关闭。 */
    var isLoggingIn by observable(UserStore.isLoggingIn)

    /** 拖拽条下拉偏移 */
    var dragOffset by observable(0f)
    var dragging by observable(false)
    var dragStartY = 0f
    private var closing = false

    override fun pageBackgroundColor(): Color = Color.TRANSPARENT

    override fun created() {
        super.created()
        on(Events.LOGIN_STATE) { json ->
            isLoggingIn = json.optBoolean("isLoggingIn", false)
        }
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        if (!shown && !closing) postDelayed(16) { shown = true }
    }

    override fun interceptBack(): Boolean = true

    override fun onBackPressed(): Boolean {
        // PopScope(canPop: enabled)：登录进行中不响应返回
        if (!isLoggingIn) close(false)
        return true
    }

    /** 收起底栏后关闭页面并带回结果。 */
    fun close(success: Boolean) {
        if (closing) return
        closing = true
        shown = false
        postDelayed(250) { nav.pop(UserStore.loginResult(success)) }
    }

    /** 手机号登录、微信登录都要先勾选协议：没勾选只提示，底栏留在原地。 */
    fun enter(type: String) {
        if (!agreed) {
            toast("login_dialog_agree_first".tr)
            return
        }
        if (isLoggingIn || closing) return
        val ctx = this
        launch {
            val ok = if (type == "wechat") UserStore.loginByWechat(ctx) else UserStore.loginByPhone(ctx)
            if (ok) ctx.close(true)
        }
    }

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            attr { flex(1f) }
            // 遮罩：空闲时点遮罩关闭
            View {
                attr {
                    absolutePositionAllZero()
                    backgroundColor(SDColors.barrier)
                    opacity(if (ctx.shown) 1f else 0f)
                    animate(Animation.easeOut(0.25f), ctx.shown)
                }
                event {
                    click { if (!ctx.isLoggingIn) ctx.close(false) }
                }
            }
            View {
                attr {
                    absolutePosition(left = 0f, right = 0f, bottom = 0f)
                    transform(Translate(0f, if (ctx.shown) 0f else 1f))
                    animate(Animation.easeOut(0.25f), ctx.shown)
                }
                View {
                    attr {
                        backgroundColor(UIColor.c1C1C1C)
                        borderRadius(24f, 24f, 0f, 0f)
                        paddingBottom(ctx.safeBottom)
                        // 拖动中跟手，松手回弹
                        transform(Translate(0f, 0f, 0f, if (ctx.dragging) ctx.dragOffset else 0f))
                        animate(Animation.easeOut(0.2f), ctx.dragging)
                    }
                    // 点底栏自身不穿透到遮罩
                    event { click { } }
                    // 整个底栏都可下拉关闭（Dart Get.bottomSheet enableDrag）；内部可点控件优先消费手势
                    event {
                        pan { p ->
                            if (ctx.isLoggingIn) return@pan
                            when (p.state) {
                                "start" -> {
                                    ctx.dragStartY = p.pageY
                                    ctx.dragging = true
                                }
                                "move" -> {
                                    val dy = p.pageY - ctx.dragStartY
                                    ctx.dragOffset = if (dy > 0f) dy else 0f
                                }
                                else -> {
                                    if (ctx.dragOffset > 80f) {
                                        ctx.close(false)
                                    } else {
                                        ctx.dragging = false
                                        ctx.dragOffset = 0f
                                    }
                                }
                            }
                        }
                    }
                    loginSheetBody(ctx)
                }
            }
        }
    }
}

private val textMuted = Color(0x66FFFFFFL)
private val linkColor = Color(0xFF7F58F7L)

private fun ViewContainer<*, *>.loginSheetBody(ctx: LoginPage) {
    // 拖拽条（下拉手势挂在整个底栏上）
    View {
        attr {
            absolutePosition(top = 0f, left = 0f, right = 0f)
            height(32f)
            alignItemsCenter()
        }
        View {
            attr {
                marginTop(10f)
                size(40f, 6f)
                borderRadius(100f)
                backgroundColor(SDColors.white10)
            }
        }
    }
    Image {
        attr {
            absolutePosition(top = 16f, right = 20f)
            size(110f, 110f)
            src(AssetsImage.loginDialogHeader.asset())
            resizeCover()
        }
    }
    View {
        // 协议行自带上下各 8 的点击区，所以它上方的间距和底边距各让出 8，看起来仍是 30。
        attr { padding(20f, 20f, 22f, 20f) }
        // 标题区高 100，文字垂直居中，右侧让给头图。
        View {
            attr {
                height(100f)
                justifyContentCenter()
                alignItemsFlexStart()
            }
            View {
                attr {
                    width(210f)
                    paddingLeft(4f)
                    paddingRight(4f)
                }
                Text {
                    attr {
                        text("login_dialog_welcome_title".tr)
                        fontSize(20f)
                        fontFamily("Poppins-SemiBold")
                        letterSpacing(0.02f)
                        color(Color.WHITE)
                        lines(1)
                    }
                }
                Text {
                    attr {
                        marginTop(6f)
                        text("login_dialog_welcome_subtitle".tr)
                        fontSize(12f)
                        fontFamily("Poppins-Medium")
                        color(textMuted)
                        lines(2)
                        textOverFlowTail()
                    }
                }
            }
        }
        View { attr { height(30f) } }
        loginEntryButton(ctx, AssetsImage.loginPhone, "login_dialog_phone".tr, primary = true) { ctx.enter("email_login") }
        View { attr { height(16f) } }
        loginEntryButton(ctx, AssetsImage.loginWechat, "login_dialog_wechat".tr, primary = false) { ctx.enter("wechat") }
        View { attr { height(22f) } }
        loginAgreement(ctx)
    }
}

/** 整行按钮：图标在左、文案在右，整体居中。[primary] 是浅色底的主按钮。登录进行中时不可点。 */
private fun ViewContainer<*, *>.loginEntryButton(
    ctx: LoginPage,
    icon: String,
    text: String,
    primary: Boolean,
    onTap: () -> Unit,
) {
    View {
        attr {
            height(50f)
            borderRadius(48f)
            backgroundColor(if (primary) UIColor.cF3EFF7 else Color(0x0DFFFFFFL))
            flexDirectionRow()
            allCenter()
            opacity(if (ctx.isLoggingIn) 0.5f else 1f)
        }
        event {
            click { if (!ctx.isLoggingIn) onTap() }
        }
        Image {
            attr {
                size(20f, 20f)
                src(icon.asset())
                resizeContain()
            }
        }
        View { attr { width(if (primary) 8f else 10f) } }
        Text {
            attr {
                text(text)
                fontSize(14f)
                fontFamily(if (primary) "Poppins-SemiBold" else "Poppins-Medium")
                color(if (primary) UIColor.c0A0A0A else Color(0x99FFFFFFL))
                lines(1)
            }
        }
    }
}

/** 勾选框加"我已阅读并同意《使用条款》和《隐私政策》"，整体居中。点勾选框或普通文字切换，点条款名打开条款。 */
private fun ViewContainer<*, *>.loginAgreement(ctx: LoginPage) {
    View {
        attr {
            paddingTop(8f)
            paddingBottom(8f)
            flexDirectionRow()
            justifyContentCenter()
            alignItemsFlexStart()
        }
        // Dart 整行 GestureDetector(opaque) + 上下 8：点行内空白（含上下 8、勾选框与文字间隙）也切换勾选；条款 Span 自己消费点击
        event { click { ctx.agreed = !ctx.agreed } }
        View {
            attr {
                height(16f)
                width(16f)
                allCenter()
            }
            event { click { ctx.agreed = !ctx.agreed } }
            // 12 的圆形勾选框：未勾选是 40% 白的描边，勾选后紫底白勾
            View {
                attr {
                    size(12f, 12f)
                    borderRadius(6f)
                    if (ctx.agreed) {
                        backgroundColor(linkColor)
                        border(Border(0f, BorderStyle.SOLID, Color.TRANSPARENT))
                    } else {
                        backgroundColor(Color.TRANSPARENT)
                        border(Border(1.2f, BorderStyle.SOLID, textMuted))
                    }
                }
                Canvas({
                    attr {
                        size(12f, 12f)
                        opacity(if (ctx.agreed) 1f else 0f)
                    }
                }) { context, _, _ ->
                    // 坐标取自设计稿 12x12 的图标
                    context.beginPath()
                    context.strokeStyle(Color.WHITE)
                    context.lineWidth(1.2f)
                    context.lineCapRound()
                    context.moveTo(3.75f, 6.42f)
                    context.lineTo(5.44f, 7.92f)
                    context.lineTo(8.25f, 4.92f)
                    context.stroke()
                }
            }
        }
        View { attr { width(4f) } }
        RichText {
            attr {
                maxWidth(ctx.screenWidth - 60f)
                lineHeight(16f)
            }
            val toggle: (ClickParams) -> Unit = { ctx.agreed = !ctx.agreed }
            Span {
                text("login_dialog_jverify_privacy_prefix".tr)
                agreementSpanStyle(this, textMuted)
                click(toggle)
            }
            Span {
                text("《${"terms_of_use".tr}》")
                agreementSpanStyle(this, linkColor)
                click { UserStore.openTerms(ctx) }
            }
            Span {
                text("and".tr)
                agreementSpanStyle(this, textMuted)
                click(toggle)
            }
            Span {
                text("《${"privacy_policy".tr}》")
                agreementSpanStyle(this, linkColor)
                click { UserStore.openPrivacyPolicy(ctx) }
            }
        }
    }
}

private fun agreementSpanStyle(span: com.tencent.kuikly.core.views.TextAttr, color: Color) {
    span.fontSize(10f)
    span.fontFamily("Poppins-Medium")
    span.color(color)
}

