package com.sondo.music.video.ai.kuikly.module

import com.sondo.music.video.ai.kuikly.ActivityStack
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import org.json.JSONArray
import org.json.JSONObject

/**
 * 页面栈操作（契约：shared/nativeapi/SDNavModule.kt）。
 *
 * Android 上一个 Kuikly 页面就是一个 KuiklyRenderActivity，栈由 [ActivityStack] 维护；
 * 打开页面统一走 KuiklyRenderActivity.start，转场 / 透明页等 pageData 标记照常生效。
 */
class SDNavModule : SDCoreBaseModule() {

    override fun handle(method: String, p: JSONObject, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            // ---- 同步（Kuikly 线程）：只读注册表并标记，真正的 finish 在主线程 ----
            "popTo" -> if (ActivityStack.popTo(p.optString("pageName"))) "1" else "0"
            "stack" -> JSONArray().also { arr -> ActivityStack.pageNames().forEach { arr.put(it) } }.toString()

            // ---- 异步（主线程） ----
            "popToRoot" -> {
                ActivityStack.popToRoot()
                null
            }
            "replace" -> {
                val pageName = p.optString("pageName")
                if (pageName.isNotEmpty()) {
                    val current = hostActivity
                    current?.markLeaving()
                    ActivityStack.replace(current, appContext, pageName, p.optJSONObject("pageData") ?: JSONObject())
                }
                null
            }
            "resetRoot" -> {
                val pageName = p.optString("pageName")
                if (pageName.isNotEmpty()) {
                    ActivityStack.resetRoot(appContext, pageName, p.optJSONObject("pageData") ?: JSONObject())
                }
                null
            }
            "setSwipeBackEnabled" -> {
                // Android 没有侧滑返回容器；这里控制的是系统返回键 / 返回手势能否关闭当前页
                val enabled = p.optBoolean("enabled", true)
                val host = hostActivity
                SDCoreKit.runOnMain { host?.backEnabled = enabled }
                null
            }
            else -> unknown(method, callback)
        }
    }

    companion object {
        const val MODULE_NAME = "SDNavModule"
    }
}
