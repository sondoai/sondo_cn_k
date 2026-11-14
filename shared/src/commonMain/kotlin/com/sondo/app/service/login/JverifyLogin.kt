package com.sondo.app.service.login

import com.sondo.app.base.AppEnv
import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.withTimeoutOr
import com.sondo.app.nativeapi.SDAuthModule
import com.sondo.app.nativeapi.nativeModule
import com.sondo.app.nativeapi.ok
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuikly.core.manager.PagerManager
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager

/**
 * 一次授权弹窗的结果。
 * - unavailable：AppKey 未配置；unsupported：当前网络不支持一键登录；
 * - cancelled：用户关闭；tokenReady：拿到 loginToken；failed：取号 / 授权失败；
 * - wechat / sms：用户在弹窗里改走微信或短信登录。
 */
enum class JverifyLoginOutcome { unavailable, unsupported, cancelled, tokenReady, failed, wechat, sms }

/**
 * @param token 运营商 loginToken（一次性，换票失败后必须重新取）
 * @param shown 授权弹窗是否真的出现过：没出现过的失败 = 号码取不到，调用方直接落到应用内登录页
 */
class JverifyLoginResult(val outcome: JverifyLoginOutcome, val token: String? = null, val shown: Boolean = false)

/** 极光授权回调码 → 结果（6000 取到 token，6002 取消，2016 网络不支持）。 */
fun mapJverifyLoginCode(code: Int?): JverifyLoginOutcome = when (code) {
    6000 -> JverifyLoginOutcome.tokenReady
    6002 -> JverifyLoginOutcome.cancelled
    2016 -> JverifyLoginOutcome.unsupported
    else -> JverifyLoginOutcome.failed
}

/** 只有 tokenReady 才把 token 发给服务端。 */
fun shouldUploadJverifyToken(outcome: JverifyLoginOutcome): Boolean = outcome == JverifyLoginOutcome.tokenReady

/** 需要提示用户的结果对应的文案键；取消 / 改走其他方式不提示。 */
fun jverifyOutcomeMessageKey(outcome: JverifyLoginOutcome): String? = when (outcome) {
    JverifyLoginOutcome.unavailable -> "login_dialog_jverify_unavailable"
    JverifyLoginOutcome.unsupported -> "login_dialog_jverify_unsupported"
    JverifyLoginOutcome.failed, JverifyLoginOutcome.tokenReady -> "login_error"
    JverifyLoginOutcome.cancelled, JverifyLoginOutcome.wechat, JverifyLoginOutcome.sms -> null
}

/**
 * 极光一键登录（对应 Flutter 侧 JverifyLogin）。
 *
 * 参考工程在 Dart 里逐像素定制 SDK 授权页并监听页面事件；这里授权弹层由各端原生实现
 * （SDAuthModule.jverifyLoginAuth），本类只负责：初始化 → 检查网络环境 → 预取号 → 拉起弹层 → 把 action 映射成结果。
 * 必须在隐私同意之后调用。
 */
object JverifyLogin {
    /** setup 成功一次即可，后续不再等回调。 */
    private var ready = false

    private fun module(): SDAuthModule = nativeModule(SDAuthModule.MODULE_NAME)

    /** 初始化并检查一键登录是否可用。可用返回 null，否则返回不可用的原因。 */
    suspend fun check(): JverifyLoginOutcome? {
        val appKey = AppConstants.jverifyAppKey.trim()
        if (appKey.isEmpty()) return JverifyLoginOutcome.unavailable
        if (!ensureSetup(appKey)) return JverifyLoginOutcome.failed
        if (!module().jverifyCheckEnable()) {
            DevLog.logger("极光一键登录 checkVerifyEnable: false")
            return JverifyLoginOutcome.unsupported
        }
        return null
    }

    /**
     * 拉起授权弹层。
     * @param otherWays 弹层里是否带「短信登录」「微信登录」入口（微信绑定手机号的场景不带）
     * @param onPresented 取号结束、弹层即将出现时回调（用来关掉取号 loading）
     */
    suspend fun start(otherWays: Boolean = false, onPresented: (() -> Unit)? = null): JverifyLoginResult {
        var presented = false
        val markPresented = {
            if (!presented) {
                presented = true
                onPresented?.invoke()
            }
        }
        try {
            val blocked = check()
            if (blocked != null) return JverifyLoginResult(blocked)

            val auth = module()
            // 预取号：取不到号码时弹层不会出现，直接按「没出现过的失败」返回。
            val pre = auth.jverifyPreLogin(10000)
            if (!pre.ok()) {
                val code = if (pre.has("code")) pre.optInt("code", -1) else null
                DevLog.logger("极光一键登录 preLogin code=$code")
                val outcome = if (code == 2016) JverifyLoginOutcome.unsupported else JverifyLoginOutcome.failed
                return JverifyLoginResult(outcome, shown = false)
            }
            markPresented()

            val res = auth.jverifyLoginAuth(buildAuthParams(otherWays))
            val token = res.optString("token", "").trim()
            DevLog.logger("极光一键登录 action=${res.optString("action")} operator=${res.optString("operator")} tokenLen=${token.length}")
            // 原生回传授权页是否真的出现过；缺省时预取号已成功，按出现过处理。
            val shown = if (res.has("shown")) res.optBoolean("shown", false) else true
            return when (res.optString("action", "error")) {
                "token" -> if (token.isEmpty()) {
                    JverifyLoginResult(JverifyLoginOutcome.failed, shown = shown)
                } else {
                    JverifyLoginResult(JverifyLoginOutcome.tokenReady, token = token, shown = true)
                }
                "wechat" -> JverifyLoginResult(JverifyLoginOutcome.wechat, shown = true)
                "sms" -> JverifyLoginResult(JverifyLoginOutcome.sms, shown = true)
                "cancel" -> JverifyLoginResult(JverifyLoginOutcome.cancelled, shown = shown)
                "unsupported" -> JverifyLoginResult(JverifyLoginOutcome.unsupported, shown = false)
                else -> JverifyLoginResult(JverifyLoginOutcome.failed, shown = shown)
            }
        } finally {
            markPresented()
        }
    }

    /** 弹层文案与协议链接；与应用内其他入口打开同一份《使用条款》《隐私政策》。 */
    private fun buildAuthParams(otherWays: Boolean): JSONObject {
        val items = JSONArray()
            .put(JSONObject().put("name", "《${"terms_of_use".tr}》").put("url", AppConstants.termsUrl))
            .put(JSONObject().put("name", "《${"privacy_policy".tr}》").put("url", AppConstants.privacyUrl))
        val p = JSONObject()
            .put("title", "login_dialog_welcome_title".tr)
            .put("subtitle", "login_dialog_welcome_subtitle".tr)
            .put("loginButtonText", "login_dialog_one_click".tr)
            // 协议行：prefix +《使用条款》+ separator +《隐私政策》+ suffix + 运营商协议
            .put("privacyPrefix", "login_dialog_jverify_privacy_prefix".tr)
            .put("privacySeparator", "and".tr)
            .put("privacySuffix", "login_dialog_jverify_privacy_and_also".tr)
            .put("privacyItems", items)
            // 协议默认未勾选；未勾选时点微信 / 短信入口给这句提示
            .put("privacyUncheckedToast", "login_dialog_agree_first".tr)
            .put("timeoutMs", 10000)
        if (otherWays) {
            p.put("otherLoginText", "login_dialog_sms".tr)
            p.put("wechatLoginText", "login_dialog_wechat".tr)
        }
        return p
    }

    /**
     * 初始化 SDK。原生侧等初始化回调最多 15 秒，超时按成功返回（`{ok:true, timeout:true}`）；
     * 鸿蒙没有一键登录，返回 `{ok:true, code:-1}`，随后 checkEnable 为 false → unsupported。
     * 这里再套一层 20 秒兜底，原生始终不回调时也继续往下走。
     */
    private suspend fun ensureSetup(appKey: String): Boolean {
        if (ready) return true
        val auth = module()
        val pager = PagerManager.getCurrentPager() as Pager
        val ok = withTimeoutOr(20_000, onTimeout = {
            DevLog.logger("极光一键登录 setup 回调超时，继续")
            true
        }) { finish ->
            pager.lifecycleScope.launch {
                val res = auth.jverifySetup(appKey, AppConstants.jpushChannel, AppEnv.isDebug)
                val success = res.ok()
                if (!success) DevLog.logger("极光一键登录 setup code=${res.optInt("code", -1)} msg=${res.optString("error")}")
                finish(success)
            }
        }
        ready = ok
        return ok
    }
}
