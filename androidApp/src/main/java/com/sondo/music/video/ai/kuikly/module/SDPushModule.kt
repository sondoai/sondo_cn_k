package com.sondo.music.video.ai.kuikly.module

import com.sondo.music.video.ai.SondoApplication
import com.sondo.music.video.ai.sdk.AppForeground
import com.sondo.music.video.ai.sdk.OnceCallback
import com.sondo.music.video.ai.sdk.SdkSupport
import com.sondo.music.video.ai.sdk.push.JPushManager
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback

/**
 * 极光推送（契约见 shared 的 `SDPushModule`）。
 *
 * Kuikly 侧在隐私同意且通知权限已授予之后才调 `setup`；在那之前这里的其他方法都不碰 SDK。
 * 通知的到达 / 点击由 [JPushManager] 广播给所有注册了 `addEventListener` 的页面。
 */
class SDPushModule : KuiklyRenderBaseModule() {

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        val app = SondoApplication.application
        AppForeground.ensureInstalled(app, activity)
        when (method) {
            "setup" -> {
                val json = SdkSupport.parseParams(params)
                val once = OnceCallback(callback)
                SdkSupport.runOnMain {
                    once.resolve(
                        JPushManager.setup(
                            context = app,
                            appKey = json.optString("appKey", "").trim(),
                            channel = json.optString("channel", "").trim(),
                            debug = json.optBoolean("debug", false),
                        )
                    )
                }
            }
            "getRegistrationId" -> {
                if (JPushManager.isSetup) {
                    callback?.invoke(SdkSupport.ok("rid" to JPushManager.registrationId(app)))
                } else {
                    callback?.invoke(SdkSupport.fail("not_setup", "rid" to ""))
                }
            }
            "getLaunchNotification" -> {
                val launch = JPushManager.takeLaunchNotification()
                val result = LinkedHashMap<String, Any>()
                result["ok"] = true
                if (launch != null) result.putAll(launch)
                callback?.invoke(result)
            }
            "setBadge" -> {
                val count = SdkSupport.parseParams(params).optInt("count", 0)
                JPushManager.setBadge(app, count)
                callback?.invoke(SdkSupport.ok())
            }
            "addEventListener" -> JPushManager.events.add(this, callback)
            else -> callback?.invoke(SdkSupport.fail("unknown method: $method"))
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        JPushManager.events.removeOwner(this)
    }
}
