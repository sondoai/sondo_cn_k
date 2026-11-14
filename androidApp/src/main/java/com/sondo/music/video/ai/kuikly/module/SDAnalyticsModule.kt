package com.sondo.music.video.ai.kuikly.module

import com.sondo.music.video.ai.SondoApplication
import com.sondo.music.video.ai.sdk.AppForeground
import com.sondo.music.video.ai.sdk.SdkSupport
import com.sondo.music.video.ai.sdk.analytics.UmengManager
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback

/**
 * 友盟统计（契约见 shared 的 `SDAnalyticsModule`）。
 * 所有方法都是“发了就走”，Kuikly 侧不等结果；带了回调时统一回 `{ok:true}`。
 */
class SDAnalyticsModule : KuiklyRenderBaseModule() {

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        val app = SondoApplication.application
        AppForeground.ensureInstalled(app, activity)
        val json = SdkSupport.parseParams(params)
        when (method) {
            "init" -> {
                val appKey = json.optString("appKey", "").trim()
                val channel = json.optString("channel", "").trim()
                // 友盟初始化要注册页面生命周期监听，放到主线程做。
                SdkSupport.runOnMain { UmengManager.init(app, appKey, channel) }
            }
            "onEvent" -> UmengManager.onEvent(app, json.optString("name", ""), json.optJSONObject("props"))
            "onPageStart" -> UmengManager.onPageStart(json.optString("name", ""))
            "onPageEnd" -> UmengManager.onPageEnd(json.optString("name", ""))
            "signIn" -> UmengManager.signIn(json.optString("userId", ""))
            "signOff" -> UmengManager.signOff()
            else -> {
                callback?.invoke(SdkSupport.fail("unknown method: $method"))
                return null
            }
        }
        callback?.invoke(SdkSupport.ok())
        return null
    }
}
