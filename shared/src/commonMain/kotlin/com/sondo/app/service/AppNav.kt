package com.sondo.app.service

import com.sondo.app.base.AppHost
import com.sondo.app.base.BasePager
import com.sondo.app.base.Routes
import com.sondo.app.base.currentPager
import com.sondo.app.nativeapi.SDNavModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.timer.setTimeout

/**
 * service 层发起导航的入口（深链、推送点击、回归页自动弹出、登出回根页）。
 *
 * 这些跳转不属于任何业务页面：一律经根页面（宿主）的 Nav 执行，
 * 这样即使当前业务页被 popToRoot 关掉，后续的延时跳转仍然会执行。
 */
object AppNav {
    /** 主框架当前 Tab 下标（由根页面在切换时写入；统计 open_login 的页面名用）。 */
    var mainTabIndex: Int = 0

    /** 根页面；主框架尚未就绪时为 null。 */
    val host: BasePager? get() = if (AppHost.isReady) AppHost.pager as? BasePager else null

    private fun anyPager(): BasePager? = host ?: currentPager()

    /** 栈顶页面名（Get.currentRoute）；取不到时为空串。 */
    fun currentRoute(): String {
        val pager = anyPager() ?: return ""
        return try {
            pager.acquireModule<SDNavModule>(SDNavModule.MODULE_NAME).stack().lastOrNull() ?: ""
        } catch (e: Throwable) {
            ""
        }
    }

    fun isOnRoot(): Boolean = currentRoute().let { it.isEmpty() || it == Routes.root }

    /** 在根页面上下文打开页面。 */
    fun push(page: String, args: JSONObject? = null, onResult: ((JSONObject?) -> Unit)? = null) {
        val h = anyPager() ?: return
        setTimeout(h.pagerId, 0) { h.nav.push(page, args, onResult) }
    }

    /** 回到主框架（可同时切 Tab）。 */
    fun popToRoot(tab: Int? = null) {
        val h = anyPager() ?: return
        setTimeout(h.pagerId, 0) { h.nav.popToRoot(tab) }
    }

    /**
     * 先回到主框架卸掉栈里的旧页面，再延时打开目标页
     * （对应参考工程的 backTo(root) + 一帧 + 550ms 后 toNamed）。
     */
    fun backToRootThenPush(page: String, args: JSONObject? = null, delayMs: Int = 550, after: (() -> Unit)? = null) {
        val h = anyPager() ?: return
        setTimeout(h.pagerId, 0) {
            h.nav.popToRoot()
            setTimeout(h.pagerId, delayMs + 16) {
                h.nav.push(page, args)
                after?.invoke()
            }
        }
    }
}
