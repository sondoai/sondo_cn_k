package com.sondo.app.nativeapi

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 页面栈操作（Kuikly RouterModule 只有 open/close，这里补齐 popTo / popToRoot / 栈查询）。
 * 原生侧维护一份 Kuikly 容器栈（Android: Activity 栈；iOS: UINavigationController；鸿蒙: NavPathStack）。
 */
class SDNavModule : SDModule() {
    override fun moduleName(): String = MODULE_NAME

    /** 关闭 [pageName] 之上的所有页面；栈内没有该页面时回到根页面。返回是否命中。 */
    fun popTo(pageName: String): Boolean =
        invokeSync("popTo", JSONObject().put("pageName", pageName)) == "1"

    /** 回到根页面。 */
    fun popToRoot() {
        invoke("popToRoot")
    }

    /** 关闭当前页并打开新页（对应 Get.offNamed）。 */
    fun replace(pageName: String, pageData: JSONObject) {
        invoke("replace", JSONObject().put("pageName", pageName).put("pageData", pageData))
    }

    /** 清空整个栈并以 [pageName] 作为新的根（对应 Get.offAllNamed）。 */
    fun resetRoot(pageName: String, pageData: JSONObject) {
        invoke("resetRoot", JSONObject().put("pageName", pageName).put("pageData", pageData))
    }

    /** 当前页面栈（自底向上的 pageName 列表）。 */
    fun stack(): List<String> {
        val s = invokeSync("stack")
        if (s.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(s)
            List(arr.length()) { arr.optString(it) ?: "" }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    fun contains(pageName: String): Boolean = stack().contains(pageName)

    /** 是否允许系统侧滑 / 返回键关闭当前页。 */
    fun setSwipeBackEnabled(enabled: Boolean) {
        invoke("setSwipeBackEnabled", JSONObject().put("enabled", enabled))
    }

    companion object {
        const val MODULE_NAME = "SDNavModule"
    }
}
