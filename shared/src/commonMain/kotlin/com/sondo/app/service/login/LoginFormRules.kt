package com.sondo.app.service.login

/** 登录表单规则。账号是大陆手机号，短信登录只提交手机号和 6 位验证码。 */
object LoginFormRules {
    /** 大陆手机号：1 开头，第二位 3–9，共 11 位。 */
    val phone = Regex("^1[3-9]\\d{9}$")

    val code = Regex("^\\d{6}$")

    fun isPhoneValid(value: String): Boolean = phone.matches(value.trim())

    fun isCodeValid(value: String): Boolean = code.matches(value.trim())

    /** 发码后重发倒计时（秒）。倒计时本身是页面状态，由手机号登录页用 observable 实现。 */
    const val codeSendPeriodSeconds = 59
}
