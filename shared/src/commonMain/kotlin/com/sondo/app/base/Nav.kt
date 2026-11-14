package com.sondo.app.base

import com.sondo.app.core.utils.Uuid
import com.sondo.app.nativeapi.SDNavModule
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 页面导航（对应 Flutter 侧 Get.toNamed / Get.back / AppPages.backTo / navigateToRoot）。
 * 每个 Kuikly 页面由一个原生容器承载，参数经 pageData.params 传入目标页。
 */
class Nav(private val pager: BasePager) {

    private val router: RouterModule get() = pager.acquireModule(RouterModule.MODULE_NAME)
    private val navModule: SDNavModule get() = pager.acquireModule(SDNavModule.MODULE_NAME)

    /**
     * 打开页面。
     * @param onResult 目标页 [pop] 时带回的结果；目标页未带结果直接关闭时回调 null。
     */
    fun push(page: String, args: JSONObject? = null, onResult: ((JSONObject?) -> Unit)? = null) {
        router.openPage(page, decorate(page, args, onResult))
    }

    /** 关闭当前页，可带返回结果。 */
    fun pop(result: JSONObject? = null) {
        pager.deliverPageResult(result)
        router.closePage()
    }

    /** 关闭当前页并打开新页（Get.offNamed）。 */
    fun replace(page: String, args: JSONObject? = null) {
        pager.deliverPageResult(null)
        navModule.replace(page, decorate(page, args, null))
    }

    /** 回到栈内的 [page]；未命中则停在根页面（AppPages.backTo）。 */
    fun popTo(page: String): Boolean = navModule.popTo(page)

    /** 回到主框架（AppPages.navigateToRoot）。可同时切换 Tab。 */
    fun popToRoot(tab: Int? = null) {
        if (tab != null) pager.emit(Events.SWITCH_TAB, JSONObject().put("tab", tab))
        navModule.popToRoot()
    }

    fun contains(page: String): Boolean = navModule.contains(page)

    fun stack(): List<String> = navModule.stack()

    private fun decorate(page: String, args: JSONObject?, onResult: ((JSONObject?) -> Unit)?): JSONObject {
        val data = args ?: JSONObject()
        Routes.transitionOf(page)?.let { if (!data.has(KEY_TRANSITION)) data.put(KEY_TRANSITION, it) }
        if (Routes.isTranslucent(page)) data.put(KEY_TRANSLUCENT, 1)
        if (onResult != null) {
            val reqId = Uuid.v4()
            data.put(KEY_REQ_ID, reqId)
            pager.awaitPageResult(reqId, onResult)
        }
        return data
    }

    companion object {
        const val KEY_TRANSITION = "sdTransition"
        const val KEY_TRANSLUCENT = "sdTranslucent"
        const val KEY_REQ_ID = "sdReqId"
        const val TRANSITION_NONE = "none"
        const val TRANSITION_FADE = "fade"
        const val TRANSITION_BOTTOM = "bottom"
    }
}
