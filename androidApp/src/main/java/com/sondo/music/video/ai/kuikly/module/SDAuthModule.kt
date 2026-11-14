package com.sondo.music.video.ai.kuikly.module

import com.sondo.music.video.ai.SondoApplication
import com.sondo.music.video.ai.sdk.AppForeground
import com.sondo.music.video.ai.sdk.OnceCallback
import com.sondo.music.video.ai.sdk.SdkSupport
import com.sondo.music.video.ai.sdk.auth.JVerifyManager
import com.sondo.music.video.ai.sdk.auth.JVerifySheetParams
import com.sondo.music.video.ai.sdk.auth.WechatManager
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback

/**
 * 登录相关三方 SDK：微信授权 + 极光一键登录（契约见 shared 的 `SDAuthModule`）。
 *
 * 两个 SDK 都是进程级单例，状态放在 [WechatManager] / [JVerifyManager]；这里每个页面一个实例，只做转发。
 * 隐私同意前 Kuikly 侧不会调用这里的任何方法，原生也不做任何自动初始化。
 */
class SDAuthModule : KuiklyRenderBaseModule() {

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        val app = SondoApplication.application
        AppForeground.ensureInstalled(app, activity)
        when (method) {
            // ---- 同步 ----
            "wechatIsInstalled" -> return if (WechatManager.isInstalled(app)) "1" else "0"

            // ---- 微信 ----
            "wechatRegister" -> {
                val appId = SdkSupport.parseParams(params).optString("appId", "")
                val registered = WechatManager.register(app, appId)
                callback?.invoke(if (registered) SdkSupport.ok() else SdkSupport.fail("register failed"))
            }
            "wechatAuth" -> {
                val json = SdkSupport.parseParams(params)
                val launched = WechatManager.auth(
                    scope = json.optString("scope", "snsapi_userinfo"),
                    state = json.optString("state", ""),
                )
                callback?.invoke(
                    if (launched) SdkSupport.ok("launched" to true)
                    else SdkSupport.fail("launch failed", "launched" to false)
                )
            }

            // ---- 极光认证 ----
            "jverifySetup" -> {
                val json = SdkSupport.parseParams(params)
                val once = OnceCallback(callback)
                SdkSupport.runOnMain {
                    JVerifyManager.setup(
                        context = app,
                        appKey = json.optString("appKey", "").trim(),
                        debug = json.optBoolean("debug", false),
                        timeoutMs = json.optInt("timeoutMs", 10000),
                        callback = once,
                    )
                }
            }
            "jverifyCheckEnable" -> {
                val once = OnceCallback(callback)
                SdkSupport.runOnMain { JVerifyManager.checkEnable(app, once) }
            }
            "jverifyPreLogin" -> {
                val timeoutMs = SdkSupport.parseParams(params).optInt("timeoutMs", 5000)
                val once = OnceCallback(callback)
                SdkSupport.runOnMain { JVerifyManager.preLogin(app, timeoutMs, once) }
            }
            "jverifyLoginAuth" -> {
                val sheet = JVerifySheetParams.from(SdkSupport.parseParams(params))
                val once = OnceCallback(callback)
                val host = activity
                SdkSupport.runOnMain { JVerifyManager.loginAuth(app, host, sheet, once) }
            }
            "jverifyDismiss" -> {
                SdkSupport.runOnMain { JVerifyManager.dismiss() }
                callback?.invoke(SdkSupport.ok())
            }

            "addEventListener" -> WechatManager.events.add(this, callback)

            else -> callback?.invoke(SdkSupport.fail("unknown method: $method"))
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        WechatManager.events.removeOwner(this)
    }
}
