package com.sondo.app.features.login

import com.sondo.app.base.BasePager
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.i18n.tr
import com.sondo.app.features.login.widgets.LoginInputField
import com.sondo.app.features.login.widgets.LoginPrimaryButton
import com.sondo.app.features.login.widgets.LoginSendCodeAction
import com.sondo.app.features.login.widgets.LoginTermsFooter
import com.sondo.app.service.UserStore
import com.sondo.app.service.login.LoginFormRules
import com.sondo.app.ui.input.hideKeyboard
import com.sondo.app.ui.nav.SDNavBack
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.clearTimeout
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 手机号表单页的共用逻辑（Dart: EmailLoginController / SignUpController + CodeSendTicker）。
 * 手机号 + 6 位验证码；发码受理后 59 秒倒计时挡住重发；发码 / 提交进行中禁止返回。
 */
internal abstract class PhoneFormPager : BasePager() {
    var phone by observable("")
    var code by observable("")

    /** 发码成功后展示「验证码已发送」。 */
    var codeSent by observable(false)

    /** 发码或登录进行中，挡住重复提交。 */
    var isSubmitting by observable(false)

    var showEmailError by observable(false)

    // —— CodeSendTicker —— //
    var seconds by observable(LoginFormRules.codeSendPeriodSeconds)
    var isCounting by observable(false)
    private var tickerTimer: String? = null

    val isPhoneValid: Boolean get() = LoginFormRules.isPhoneValid(phone)
    val isCodeValid: Boolean get() = LoginFormRules.isCodeValid(code)
    val canSubmit: Boolean get() = isPhoneValid && isCodeValid && !isSubmitting
    val canSendCode: Boolean get() = isPhoneValid && !isCounting && !isSubmitting

    /** 导航栏标题 / 主按钮文案。 */
    abstract val titleText: String
    abstract val actionText: String

    /** 提交：返回是否已登录（UserStore 已提示「登录成功」）。 */
    abstract suspend fun doSubmit(mobile: String, code: String): Boolean

    /** 主按钮下方的附加内容（绑定页的一键绑定 / 注册页的「已有账号」）。 */
    open fun buildExtra(container: ViewContainer<*, *>) {}

    override fun interceptBack(): Boolean = isSubmitting

    override fun onBackPressed(): Boolean = isSubmitting

    override fun pageWillDestroy() {
        // 倒计时跟页面同生共死
        tickerTimer?.let { clearTimeout(it) }
        tickerTimer = null
        super.pageWillDestroy()
    }

    @kotlin.jvm.JvmName("setSubmittingFn")
    fun setSubmitting(value: Boolean) {
        isSubmitting = value
        refreshBackIntercept()
    }

    fun onPhoneChanged(value: String) {
        phone = value
        // 有内容就开始校验；空不报错（提交时再强制标空）
        showEmailError = value.trim().isNotEmpty() && !isPhoneValid
    }

    fun onCodeChanged(value: String) {
        code = value
        showEmailError = phone.trim().isNotEmpty() && !isPhoneValid
    }

    private fun startTicker() {
        if (isCounting) return
        isCounting = true
        seconds = LoginFormRules.codeSendPeriodSeconds
        tick()
    }

    private fun tick() {
        tickerTimer = postDelayed(1000) {
            if (seconds <= 1) {
                isCounting = false
                seconds = LoginFormRules.codeSendPeriodSeconds
                tickerTimer = null
            } else {
                seconds--
                tick()
            }
        }
    }

    /** 向当前手机号发验证码。业务失败由接口层提示，这里只在受理后开始倒计时。 */
    fun sendCode(view: com.tencent.kuikly.core.base.DeclarativeBaseView<*, *>) {
        view.hideKeyboard()
        showEmailError = !isPhoneValid
        if (!canSendCode) return
        setSubmitting(true)
        launch {
            try {
                if (UserStore.sendSmsCode(phone.trim())) {
                    codeSent = true
                    startTicker()
                }
            } finally {
                setSubmitting(false)
            }
        }
    }

    /** 用手机号和最新验证码登录（或绑定）。 */
    fun submit(view: com.tencent.kuikly.core.base.DeclarativeBaseView<*, *>) {
        view.hideKeyboard()
        showEmailError = !isPhoneValid
        if (!canSubmit) return
        setSubmitting(true)
        launch {
            var ok = false
            try {
                ok = doSubmit(phone.trim(), code.trim())
            } finally {
                setSubmitting(false)
            }
            if (ok) nav.pop(UserStore.loginResult(true))
        }
    }

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            // Dart LoginPageScaffold：黑底、居中标题、键盘不顶起底栏协议
            attr {
                flex(1f)
                backgroundColor(UIColor.c0A0A0A)
            }
            val root = this
            event { click { root.hideKeyboard() } }
            View { attr { height(ctx.statusBarHeight) } }
            View {
                attr {
                    height(56f)
                    flexDirectionRow()
                    alignItemsCenter()
                }
                SDNavBack(onPressed = { if (!ctx.isSubmitting) ctx.nav.pop() })
                Text {
                    attr {
                        flex(1f)
                        textAlignCenter()
                        text(ctx.titleText)
                        fontSize(18f)
                        fontWeight600()
                        color(Color.WHITE)
                        lines(1)
                    }
                }
                View { attr { width(48f) } }
            }
            View {
                attr {
                    flex(1f)
                    padding(10f, 20f, 30f + ctx.safeBottom, 20f)
                }
                Scroller {
                    attr {
                        flex(1f)
                        showScrollerIndicator(false)
                    }
                    LoginInputField(
                        label = "phone".tr,
                        hintText = "your_phone_number".tr,
                        maxLength = 11,
                        errorText = { if (ctx.showEmailError) "please_enter_a_valid_phone".tr else null },
                        showEmailCheck = { ctx.isPhoneValid },
                        onChanged = { ctx.onPhoneChanged(it) },
                    )
                    View { attr { height(10f) } }
                    LoginInputField(
                        label = "code".tr,
                        hintText = "six_digit_code".tr,
                        maxLength = 6,
                        errorText = { null },
                        successText = { if (ctx.codeSent) "code_sent".tr else null },
                        onChanged = { ctx.onCodeChanged(it) },
                        suffix = {
                            val host = this
                            LoginSendCodeAction(
                                counting = { ctx.isCounting },
                                seconds = { ctx.seconds },
                                enabled = { ctx.canSendCode },
                                onTap = { ctx.sendCode(host) },
                            )
                        },
                    )
                    View { attr { height(30f) } }
                    val host = this
                    LoginPrimaryButton(ctx.actionText, enabled = { ctx.canSubmit }) { ctx.submit(host) }
                    ctx.buildExtra(this)
                }
                LoginTermsFooter(ctx)
            }
        }
    }

}

/**
 * 手机号登录页（路由名沿用历史的 email_login）。
 *
 * 参数：`bindToken`（可选）——微信未绑定手机号时的待绑定凭证；非空时本页只做绑定（标题 / 按钮改「绑定手机号」，
 * 多一个本机号码一键绑定入口）。结果：成功 `nav.pop(UserStore.loginResult(true))`。
 */
@Page(Routes.emailLogin)
internal class EmailLoginPage : PhoneFormPager() {
    /** 微信待绑定凭证。非空时本页只做绑定。 */
    val bindToken: String by lazy(LazyThreadSafetyMode.NONE) {
        pagerData.params.optString("bindToken", "").trim().takeIf { it != "null" } ?: ""
    }

    val isBindMode: Boolean get() = bindToken.isNotEmpty()
    val canOneClickBind: Boolean get() = isBindMode && !isSubmitting

    override val titleText: String get() = if (isBindMode) "bind_mobile".tr else "login".tr
    override val actionText: String get() = if (isBindMode) "bind_mobile".tr else "login".tr

    override suspend fun doSubmit(mobile: String, code: String): Boolean =
        // 有 bindToken 时这页只负责把微信账号绑到手机号
        if (isBindMode) UserStore.wechatBindMobile(bindToken, mobile, code) else UserStore.smsLogin(mobile, code)

    /** 绑定页用新的极光 loginToken 验号。授权页里取消的旧凭证不能重放。 */
    fun bindByOneClick() {
        if (!canOneClickBind) return
        setSubmitting(true)
        launch {
            var ok = false
            try {
                ok = UserStore.bindByOneClick(bindToken)
            } finally {
                setSubmitting(false)
            }
            if (ok) nav.pop(UserStore.loginResult(true))
        }
    }

    override fun buildExtra(container: ViewContainer<*, *>) {
        if (!isBindMode) return
        val ctx = this
        with(container) {
            View {
                attr {
                    marginTop(12f)
                    height(40f)
                    allCenter()
                }
                event { click { ctx.bindByOneClick() } }
                Text {
                    attr {
                        text("bind_mobile_one_click".tr)
                        fontSize(14f)
                        fontWeight600()
                        color(if (ctx.canOneClickBind) Color.WHITE else Color(0x66FFFFFFL))
                    }
                }
            }
        }
    }
}

/**
 * 手机号注册页（Dart: SignUpPage）。与登录打同一个短信接口，未注册号码由后端建号。
 * 参考工程里只注册了路由、没有入口跳到这里；保留页面名供以后使用。无参数；成功 `nav.pop(UserStore.loginResult(true))`。
 */
@Page(Routes.emailRegister)
internal class EmailRegisterPage : PhoneFormPager() {
    override val titleText: String get() = "sign_up".tr
    override val actionText: String get() = "sign_up".tr

    override suspend fun doSubmit(mobile: String, code: String): Boolean = UserStore.smsLogin(mobile, code)

    fun goLogin() {
        if (isSubmitting) return
        nav.pop()
    }

    override fun buildExtra(container: ViewContainer<*, *>) {
        val ctx = this
        with(container) {
            View {
                attr {
                    marginTop(16f)
                    flexDirectionRow()
                    justifyContentCenter()
                    alignItemsCenter()
                }
                Text {
                    attr {
                        text("have_an_account".tr)
                        fontSize(12f)
                        fontWeight500()
                        color(Color(0x66FFFFFFL))
                    }
                }
                View { attr { width(10f) } }
                Text {
                    attr {
                        text("login".tr)
                        fontSize(12f)
                        fontWeight600()
                        color(Color(0xCCFFFFFFL))
                    }
                    event { click { ctx.goLogin() } }
                }
            }
        }
    }
}
