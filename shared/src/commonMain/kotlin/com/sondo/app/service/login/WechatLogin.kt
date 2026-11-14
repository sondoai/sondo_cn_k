package com.sondo.app.service.login

import com.sondo.app.base.AppEnv
import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.currentPagerId
import com.sondo.app.nativeapi.SDAuthModule
import com.sondo.app.nativeapi.nativeModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.timer.setTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.random.Random

/** 微信授权结果。只有 [codeReady] 才把 code 交给 wechat-login。 */
enum class WechatAuthOutcome { unavailable, notInstalled, cancelled, codeReady, failed }

/** 一次微信授权。code 只在成功且 state 对得上时有值。 */
class WechatAuthResult(val outcome: WechatAuthOutcome, val code: String? = null)

/**
 * state 对不上就丢掉这次回调，避免拿别的授权结果去换票。
 * -2 取消、-4 拒绝都不带 code。
 */
fun wechatResultFromAuth(errCode: Int?, code: String?, responseState: String?, expectedState: String): WechatAuthResult {
    if (responseState != expectedState) return WechatAuthResult(WechatAuthOutcome.failed)
    if (errCode == -2 || errCode == -4) return WechatAuthResult(WechatAuthOutcome.cancelled)
    val authCode = code?.trim() ?: ""
    if (errCode == 0 && authCode.isNotEmpty()) return WechatAuthResult(WechatAuthOutcome.codeReady, authCode)
    return WechatAuthResult(WechatAuthOutcome.failed)
}

/** 微信登录接口的两种结局：已绑定则 [login] 可直接入会话；未绑定只给 [bindToken]。 */
class WechatLoginResult(val needBindMobile: Boolean, val bindToken: String? = null, val login: JSONObject? = null)

/**
 * needBindMobile 为 true 时尚未登录，不能把 data 当成会话。
 * 为 false 时令牌在 data.login，不在 data 本身。
 */
fun parseWechatLoginData(data: Any?): WechatLoginResult? {
    if (data !is JSONObject) return null
    if (data.opt("needBindMobile") == true) {
        val token = data.opt("bindToken")?.toString()?.trim()?.takeIf { it != "null" } ?: ""
        if (token.isEmpty()) return null
        return WechatLoginResult(needBindMobile = true, bindToken = token)
    }
    val login = data.opt("login") as? JSONObject ?: return null
    val accessToken = login.opt("accessToken")?.toString()?.takeIf { it != "null" } ?: ""
    if (accessToken.isEmpty()) return null
    return WechatLoginResult(needBindMobile = false, login = login)
}

/** 拉起微信授权。点按钮才注册、才授权；必须在隐私同意之后调用。 */
object WechatLogin {
    private const val TIMEOUT_MS = 2 * 60 * 1000

    /** 用户取消或 state 不符时不返回 code。在调用方页面的上下文里执行（授权回调发到这个页面）。 */
    suspend fun start(): WechatAuthResult {
        val appId = AppConstants.wechatAppId.trim()
        // 开放平台 AppId 还没填时，不要向微信注册空应用。
        if (appId.isEmpty()) return WechatAuthResult(WechatAuthOutcome.unavailable)
        val link = AppConstants.wechatUniversalLink.trim()
        // iOS 在 Universal Link 为空时无法注册，按未开放处理。
        if (AppEnv.isIOS && link.isEmpty()) return WechatAuthResult(WechatAuthOutcome.unavailable)

        val auth: SDAuthModule = nativeModule(SDAuthModule.MODULE_NAME)
        val pagerId = currentPagerId()
        val registered = auth.wechatRegister(appId, link)
        if (!registered) return WechatAuthResult(WechatAuthOutcome.failed)
        if (!auth.wechatIsInstalled()) return WechatAuthResult(WechatAuthOutcome.notInstalled)

        val state = newAuthState()
        var done = false
        var finish: ((WechatAuthResult) -> Unit)? = null
        var pending: WechatAuthResult? = null
        var ref: String? = null
        val complete: (WechatAuthResult) -> Unit = { result ->
            if (!done) {
                done = true
                // 一次授权只收一条回调，避免回调闭包一直挂着。
                ref?.let { auth.removeCallback(it) }
                val f = finish
                if (f != null) f(result) else pending = result
            }
        }
        ref = auth.addEventListener { ev ->
            if (ev.optString("event", "") != "wechatAuthResp" || done) return@addEventListener
            val errCode = if (ev.has("errCode")) ev.optInt("errCode", -1) else null
            val code = ev.optString("code", "")
            val respState = if (ev.has("state")) ev.optString("state", "") else null
            DevLog.logger("微信授权 errCode=$errCode stateMatch=${respState == state} codeLen=${code.length}")
            complete(wechatResultFromAuth(errCode, code, respState, state))
        }

        val launched = auth.wechatAuth(state)
        if (!launched) complete(WechatAuthResult(WechatAuthOutcome.failed))

        return suspendCoroutine { cont ->
            val early = pending
            if (early != null) {
                cont.resume(early)
            } else {
                finish = { cont.resume(it) }
                setTimeout(pagerId, TIMEOUT_MS) {
                    if (!done) {
                        DevLog.logger("微信授权超时")
                        complete(WechatAuthResult(WechatAuthOutcome.failed))
                    }
                }
            }
        }
    }

    /** 当次授权的 state（16 位十六进制），回调对不上就丢弃。 */
    private fun newAuthState(): String {
        val sb = StringBuilder(16)
        repeat(16) { sb.append("0123456789abcdef"[Random.nextInt(16)]) }
        return sb.toString()
    }
}
