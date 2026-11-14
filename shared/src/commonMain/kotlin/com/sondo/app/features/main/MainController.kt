package com.sondo.app.features.main

import com.sondo.app.base.AppHost
import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.base.currentPager
import com.sondo.app.core.utils.CommonUtils
import com.sondo.app.core.utils.DevLog
import com.sondo.app.service.AppNav
import com.sondo.app.service.EventBus
import com.sondo.app.service.analytics.UmengUtils
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.timer.setTimeout

/** 底部浮动导航 Tab（Dart `MainNavTab`）。 */
enum class MainNavTab { home, mv, music, library }

/**
 * 主框架控制器（Dart `MainController`）的跨页面部分：创作页打开规则、Tab 切换入口。
 *
 * 根页面（[LaunchPage]）持有可见状态（选中 Tab、前后台）；其它页面 / 服务经这里发起：
 * - [changeBarIndex] / [selectTab]：经 [Events.SWITCH_TAB] 交给根页面；
 * - [openCreate]：MV / 音乐创作页（已在栈内则回到它并用 [Events.CREATE_ARGUMENTS] 灌参数）；
 * - [leaveCreateAfterSuccess] / [openCreateResult]：创作成功后的去向。
 *
 * 本对象只保存普通数据，不含响应式字段。
 */
object MainController {
    /** 底部导航索引：0=首页, 1=MV, 3=音乐, 4=作品库（与 [AppNav.mainTabIndex] 同步）。 */
    val barIndex: Int get() = AppNav.mainTabIndex

    /** 正在入栈的创作路由；防止快速重复点击创建两个相同页面。 */
    private val openingCreateRoutes = HashSet<String>()

    /** 当前 Tab 在两个 Tab 体中的下标：0 首页 / 1 作品库。MV/音乐是二级页。 */
    fun tabOfIndex(index: Int): MainNavTab? = when (index) {
        0 -> MainNavTab.home
        1 -> MainNavTab.mv
        3 -> MainNavTab.music
        4 -> MainNavTab.library
        else -> null
    }

    /** 选择导航 Tab。首页/作品库切 Tab；MV/音乐弹出二级页。 */
    fun selectTab(tab: MainNavTab) {
        when (tab) {
            MainNavTab.home -> changeBarIndex(0)
            MainNavTab.mv -> openCreate(MainNavTab.mv)
            MainNavTab.music -> openCreate(MainNavTab.music)
            MainNavTab.library -> changeBarIndex(4)
        }
    }

    /** 切换底部导航：0=首页, 1=MV, 3=音乐, 4=作品库。旧的 2（舞蹈）已下线，直接忽略。 */
    fun changeBarIndex(index: Int) {
        when (index) {
            1 -> {
                openCreate(MainNavTab.mv)
                return
            }
            3 -> {
                openCreate(MainNavTab.music)
                return
            }
            0, 4 -> EventBus.post(Events.SWITCH_TAB, JSONObject().put("tab", index))
        }
    }

    private fun routeOf(tab: MainNavTab): String? = when (tab) {
        MainNavTab.mv -> Routes.musicVideoGen
        MainNavTab.music -> Routes.musicCreate
        else -> null
    }

    /**
     * 打开 MV 或音乐创作二级页，从下至上弹出。已在目标页时只灌参数。
     * @param replace true 时用目标页替换 [from]（创作菜单选项：避免先关菜单闪主页）
     */
    fun openCreate(tab: MainNavTab, arguments: JSONObject? = null, replace: Boolean = false, from: BasePager? = null) {
        val route = routeOf(tab) ?: return
        val pager = from ?: currentPager() ?: AppNav.host ?: return
        val current = AppNav.currentRoute()
        if (current == route) {
            if (arguments != null) applyCreateArguments(route, arguments)
            return
        }
        // 目标创作页可能被订阅页或其他弹窗压在栈内，复用原页面而不是再建一份。
        if (pager.nav.contains(route)) {
            CommonUtils.hideKeyboard()
            setTimeout(pager.pagerId, 16) {
                pager.nav.popTo(route)
                if (arguments != null) applyCreateArguments(route, arguments)
            }
            return
        }
        // 页面动画尚未完成时栈顶可能仍是旧值，用集合拦截连续点击。
        if (!openingCreateRoutes.add(route)) return
        CommonUtils.hideKeyboard()
        UmengUtils.logEvent("root_page_change", mapOf("pageName" to if (tab == MainNavTab.mv) "mv" else "music"))
        setTimeout(pager.pagerId, 16) {
            try {
                if (replace && pager.pageName != Routes.root) {
                    pager.nav.replace(route, arguments)
                    // replace 拿不到目标页关闭回调；等转场结束后放开拦截（目标页已在栈内，之后走上面的 contains 分支）
                    releaseLater(route, 800)
                } else {
                    // 关闭回调挂在宿主页面上，目标页关掉时放开拦截
                    val host = AppNav.host ?: pager
                    host.nav.push(route, arguments) { openingCreateRoutes.remove(route) }
                }
            } catch (e: Throwable) {
                DevLog.logger("openCreate error: $e")
                openingCreateRoutes.remove(route)
            }
        }
    }

    private fun releaseLater(route: String, delayMs: Int) {
        if (!AppHost.isReady) {
            openingCreateRoutes.remove(route)
            return
        }
        setTimeout(AppHost.pager.pagerId, delayMs) { openingCreateRoutes.remove(route) }
    }

    /** 已在创作页时灌入参数，避免再压一层同名页面。 */
    private fun applyCreateArguments(route: String, arguments: JSONObject) {
        EventBus.post(Events.CREATE_ARGUMENTS, JSONObject().put("page", route).put("args", arguments))
    }

    /** 打开创作菜单页。 */
    fun openCreateMenu(pager: BasePager) {
        CommonUtils.hideKeyboard()
        UmengUtils.logEvent("root_page_change", mapOf("pageName" to "create_menu_open"))
        pager.nav.push(Routes.createMenu)
    }

    /** 创作成功后回到作品库。Tab 态只切 Tab；二级页态再关闭创作页。 */
    fun leaveCreateAfterSuccess(pager: BasePager) {
        selectTab(MainNavTab.library)
        if (AppNav.currentRoute() != Routes.root && pager.pageName != Routes.root) pager.nav.pop()
    }

    /** 创作成功后打开进度页。主框架上直接打开；二级页态替换创作页。 */
    fun openCreateResult(pager: BasePager, route: String, arguments: JSONObject? = null) {
        selectTab(MainNavTab.library)
        if (pager.pageName == Routes.root || AppNav.currentRoute() == Routes.root) {
            pager.nav.push(route, arguments)
        } else {
            pager.nav.replace(route, arguments)
        }
    }

    /** 根据导航索引返回埋点 pageName。 */
    internal fun pageNameForIndex(index: Int): String = when (index) {
        0 -> "home"
        1 -> "mv"
        3 -> "music"
        4 -> "library"
        else -> ""
    }
}
