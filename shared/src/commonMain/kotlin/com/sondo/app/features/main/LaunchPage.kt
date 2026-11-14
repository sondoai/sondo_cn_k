package com.sondo.app.features.main

import com.sondo.app.base.AppHost
import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Nav
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.c
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.CommonUtils
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.SystemNavMode
import com.sondo.app.core.utils.nowMs
import com.sondo.app.features.home.HomeTab
import com.sondo.app.features.library.LibraryTab
import com.sondo.app.features.main.widgets.MainMorphingNavBar
import com.sondo.app.features.main.widgets.MainNavColors
import com.sondo.app.features.try_free.widgets.TryFreeFloatingBanner
import com.sondo.app.nativeapi.SDAppModule
import com.sondo.app.service.AppBootstrap
import com.sondo.app.service.AppNav
import com.sondo.app.service.DeepLinkRouter
import com.sondo.app.service.LibraryTabRefreshService
import com.sondo.app.service.PrivacyConsentService
import com.sondo.app.service.PushService
import com.sondo.app.service.RecallFlow
import com.sondo.app.service.UserStore
import com.sondo.app.service.WorksUi
import com.sondo.app.service.analytics.TrackingInitializer
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.ui.consent.SDPrivacyConsent
import com.sondo.app.ui.consent.SDPrivacyConsentView
import com.sondo.app.ui.dialog.showGenerationCompleted
import com.sondo.app.ui.works.WorksUiInstaller
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.clearTimeout
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 根页面（`Routes.root` = "launch"），栈底永远是它。对应参考工程的
 * `main()` + `ConsentShell` + `_BootstrappedApp` + `MyApp`（三键导航抬升）+ `MainPage` + `MainController`。
 *
 * 1. 隐私 Gate：未同意当前政策版本时只展示 [SDPrivacyConsent]，此前不做任何联网 / SDK 初始化。
 * 2. 同意后（或已同意）`AppBootstrap.start()`；首启需要引导时把引导页压在上面，主框架在引导流程结束、
 *    根页面重新可见之前不挂载（不拉信息流、不播视频）。
 * 3. 主框架：首页 / 作品库两个 Tab 常驻（IndexedStack），试用浮窗，变形浮动导航栏，双击返回退出，
 *    [Events.SWITCH_TAB]、前台推送横幅、回到前台刷新用户与作品库。
 *
 * 页面参数：无。
 */
@Page(Routes.root)
internal class LaunchPage : BasePager() {

    // ------------------------------------------------------------------ Gate / 启动
    // 构造阶段页面尚未登记为当前页面，不能读本地存储；在 created() 里再取真实值
    private var needConsent by observable(true)
    private var bootstrapping by observable(false)
    private var consentRef: ViewRef<SDPrivacyConsentView>? = null

    /** 主框架已挂载（引导流程结束后才挂载）。 */
    private var mainReady by observable(false)

    /** 引导页压在上面，等根页面重新可见。 */
    private var waitingGuide = false

    // ------------------------------------------------------------------ 主框架状态
    /** 浮动导航当前 Tab（首页 / 作品库；MV、音乐是二级页）。 */
    private var selectedTab by observable(MainNavTab.home)
    private var rootVisible by observable(false)

    /** 应用是否在后台。 */
    private var appInBackground = false

    /** 已调用退出，后续返回键吞掉。 */
    private var isExiting = false

    /** 上次按返回键时间，用于双击退出。 */
    private var lastBackPressedMs = 0L

    // —— 双击退出提示（Get.snackbar） —— //
    private var snackShown by observable(false)
    private var snackMounted by observable(false)
    private var snackTimer = ""

    // —— Android 三键导航（参考工程 app.dart 的 system_nav 通道） —— //
    private var threeButtonNav by observable(false)
    private var navBottomInset by observable(0f)

    /** 三键时把页面抬到按钮上方（键盘弹起时 safeBottom 不变，不需要额外判断）。 */
    private val liftAboveNav: Float get() = if (threeButtonNav && navBottomInset > 0f) navBottomInset else 0f

    /** 浮动导航底部 inset：三键时页面已抬起，子树里不再算底部安全区。 */
    private val navBottomViewPadding: Float get() = if (liftAboveNav > 0f) 0f else safeBottom

    private val navTotalHeight: Float get() = MainNavColors.totalHeight(navBottomViewPadding)

    override fun pageBackgroundColor(): Color = Color.BLACK

    override fun created() {
        super.created()
        needConsent = !PrivacyConsentService.hasConsent()
        AppHost.attach(this)
        AppBootstrap.prepare()
        WorksUiInstaller.install()
        installMainHooks()
        syncNavigationMode()
        subscribeEvents()
        if (!needConsent) startApp()
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        rootVisible = true
        checkGuideFinished(retry = true)
    }

    /** 引导流程（引导页 → 试用创作 → 生成等待）结束、根页面重新露出时挂载主框架。 */
    private fun checkGuideFinished(retry: Boolean) {
        if (!waitingGuide || !rootVisible) return
        val top = try {
            nav.stack().lastOrNull() ?: ""
        } catch (e: Throwable) {
            ""
        }
        // 引导 / 试用创作仍在栈顶时不算结束；关页瞬间原生栈可能还没更新，稍后再看一次
        if (top == Routes.guide || top == Routes.tryFreeCreate || top == Routes.tryFreeGenWaiting) {
            if (retry) postDelayed(400) { checkGuideFinished(retry = false) }
            return
        }
        waitingGuide = false
        enterMain()
    }

    override fun pageDidDisappear() {
        super.pageDidDisappear()
        rootVisible = false
    }

    override fun pageWillDestroy() {
        if (WorksUi.openCreate === openCreateHook) WorksUi.openCreate = null
        AppHost.detach(this)
        super.pageWillDestroy()
    }

    // ------------------------------------------------------------------ 隐私 Gate

    private fun acceptConsent() {
        if (bootstrapping) return
        // 先把本地同意记录下来，后续 bootstrap 才可以安全地初始化 SDK。
        PrivacyConsentService.grant()
        startApp()
    }

    private fun startApp() {
        if (bootstrapping) return
        bootstrapping = true
        launch {
            val result = try {
                AppBootstrap.start()
            } catch (e: Throwable) {
                DevLog.logger("bootstrap error: $e")
                null
            }
            bootstrapping = false
            if (result == null) return@launch
            // systemNav 监听同意后才注册：期间 inset 可能已就绪，补读一次
            syncNavigationMode()
            needConsent = false
            refreshBackIntercept()
            if (result.initialRoute == Routes.guide) {
                waitingGuide = true
                // 引导页是首个业务页面，无转场
                nav.push(Routes.guide, JSONObject().put(Nav.KEY_TRANSITION, Nav.TRANSITION_NONE))
            } else {
                enterMain()
            }
            // 业务首帧之后再请求 ATT，避免系统权限框遮挡 Gate 或首帧；引导页也会调用，内部去重。
            postDelayed(300) { TrackingInitializer.initialize() }
        }
    }

    /** 同意前不加载应用内 WebView：协议链接交给系统浏览器（参考工程 LaunchMode.externalApplication）。 */
    private fun openConsentLink(url: String) {
        if (url.isEmpty()) return
        launch {
            try {
                acquireModule<SDAppModule>(SDAppModule.MODULE_NAME).openUrl(url, "external")
            } catch (e: Throwable) {
                DevLog.logger("open consent link error: $e")
            }
        }
    }

    private fun exitApp() {
        try {
            acquireModule<SDAppModule>(SDAppModule.MODULE_NAME).exitApp()
        } catch (e: Throwable) {
            DevLog.logger("exitApp error: $e")
        }
    }

    // ------------------------------------------------------------------ 主框架

    private fun enterMain() {
        if (mainReady) return
        mainReady = true
        // MainController.onReady：深链必须等进入首页再消费；没被深链占用时再判断满月自动展示。
        postDelayed(32) {
            DeepLinkRouter.markHomeReady()
            launch { RecallFlow.tryAuto() }
        }
    }

    private val openCreateHook: (String, JSONObject?) -> Unit = { page, args ->
        MainController.openCreate(if (page == Routes.musicVideoGen) MainNavTab.mv else MainNavTab.music, args)
    }

    private fun installMainHooks() {
        WorksUi.openCreate = openCreateHook
    }

    private fun subscribeEvents() {
        val ctx = this
        on(Events.SWITCH_TAB) { ctx.applyBarIndex(it.optInt("tab", -1)) }
        on(Events.APP_LIFECYCLE) { ctx.onAppLifecycle(it.optBoolean("foreground", true)) }
        // 原生在窗口 inset 就绪 / 用户切换导航方式时补发 systemNav，由 AppBootstrap 的唯一监听转发
        on(Events.SYSTEM_NAV) { ev ->
            val three = ev.optBoolean("threeButton", false)
            ctx.threeButtonNav = three
            ctx.navBottomInset = if (three) ev.optDouble("bottomInset", 0.0).toFloat() else 0f
        }
        // 前台推送横幅由 BasePager 在当前可见页面统一展示
    }

    /** MainController.changeBarIndex 的根页面部分：0=首页, 1=MV, 3=音乐, 4=作品库。 */
    private fun applyBarIndex(index: Int) {
        CommonUtils.hideKeyboard()
        when (index) {
            0 -> selectedTab = MainNavTab.home
            1 -> {
                MainController.openCreate(MainNavTab.mv)
                return
            }
            3 -> {
                MainController.openCreate(MainNavTab.music)
                return
            }
            4 -> selectedTab = MainNavTab.library
            else -> return
        }
        AppNav.mainTabIndex = index
        val pageName = MainController.pageNameForIndex(index)
        if (pageName.isNotEmpty()) UmengUtils.logEvent("root_page_change", mapOf("pageName" to pageName))
    }

    private fun onAppLifecycle(foreground: Boolean) {
        DevLog.logger("state: ${if (foreground) "resumed" else "paused"} ===${AppNav.currentRoute()}")
        if (!foreground) {
            appInBackground = true
            return
        }
        appInBackground = false
        // 进程未死被再次拉起时允许再次双击退出
        isExiting = false
        // 回到前台时停在主框架就刷新用户与作品库
        if (mainReady && AppNav.isOnRoot() && UserStore.isLogin) {
            launch { UserStore.getUserInfo() }
            launch { LibraryTabRefreshService.refreshSong() }
            launch { LibraryTabRefreshService.refreshMv() }
        }
    }

    // ------------------------------------------------------------------ 返回键

    override fun interceptBack(): Boolean = true

    override fun onBackPressed(): Boolean {
        if (needConsent) {
            consentRef?.view?.handleBack()
            return true
        }
        handleDoubleBackExit()
        return true
    }

    /** 2 秒内二次返回才退出应用。 */
    private fun handleDoubleBackExit() {
        // 退出中或已后台：忽略
        if (isExiting || appInBackground) return
        val now = nowMs()
        val isWarning = lastBackPressedMs == 0L || now - lastBackPressedMs > 2000L
        if (isWarning) {
            lastBackPressedMs = now
            showExitSnackbar()
        } else {
            isExiting = true
            exitApp()
        }
    }

    private fun showExitSnackbar() {
        if (snackTimer.isNotEmpty()) clearTimeout(snackTimer)
        snackMounted = true
        postDelayed(16) { snackShown = true }
        snackTimer = postDelayed(1500) {
            snackTimer = ""
            snackShown = false
            postDelayed(300) { if (!snackShown) snackMounted = false }
        }
    }

    // ------------------------------------------------------------------ 三键导航

    private fun syncNavigationMode() {
        threeButtonNav = SystemNavMode.isThreeButton()
        navBottomInset = SystemNavMode.threeButtonInset()
    }

    // ------------------------------------------------------------------ UI

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            attr { flex(1f) }
            vif({ ctx.mainReady && !ctx.needConsent }) {
                ctx.mainFrame(this)
            }
            vif({ ctx.needConsent }) {
                SDPrivacyConsent {
                    ref { ctx.consentRef = it }
                    attr { isBusy = ctx.bootstrapping }
                    event {
                        onAgree { ctx.acceptConsent() }
                        onDisagree { ctx.exitApp() }
                        onOpenLink { url, _ -> ctx.openConsentLink(url) }
                    }
                }
            }
        }
    }

    private fun mainFrame(container: com.tencent.kuikly.core.base.ViewContainer<*, *>) {
        val ctx = this
        with(container) {
            // 三键导航：底部黑条 + 页面抬到按钮上方
            View {
                attr {
                    absolutePosition(left = 0f, right = 0f, bottom = 0f)
                    height(ctx.liftAboveNav)
                    backgroundColor(Color.BLACK)
                }
            }
            View {
                attr {
                    absolutePosition(top = 0f, left = 0f, right = 0f, bottom = ctx.liftAboveNav)
                }
                // IndexedStack：两个 Tab 常驻，只显示当前一个
                View {
                    attr {
                        absolutePositionAllZero()
                        visibility(ctx.selectedTab != MainNavTab.library)
                    }
                    HomeTab {
                        attr {
                            flex(1f)
                            active = ctx.rootVisible && ctx.selectedTab != MainNavTab.library
                            bottomInset = ctx.navTotalHeight
                        }
                    }
                }
                View {
                    attr {
                        absolutePositionAllZero()
                        visibility(ctx.selectedTab == MainNavTab.library)
                    }
                    LibraryTab {
                        attr {
                            flex(1f)
                            active = ctx.rootVisible && ctx.selectedTab == MainNavTab.library
                            bottomInset = ctx.navTotalHeight
                        }
                    }
                }
                TryFreeFloatingBanner {
                    attr { absolutePosition(right = 0f, bottom = ctx.navTotalHeight + 25f) }
                }
                MainMorphingNavBar {
                    attr {
                        // 三键时页面已经停在黑条上方，导航贴底；手势和 iOS 留 6 避开指示条。
                        absolutePosition(left = 0f, right = 0f, bottom = if (ctx.liftAboveNav > 0f) 0f else 6f)
                        barWidth = ctx.screenWidth - MainNavColors.horizontalPadding * 2f
                        selected = ctx.selectedTab
                    }
                    event {
                        onHomeTap { MainController.selectTab(MainNavTab.home) }
                        onMvTap { MainController.openCreate(MainNavTab.mv) }
                        onMusicTap { MainController.openCreate(MainNavTab.music) }
                        onLibraryTap { MainController.selectTab(MainNavTab.library) }
                    }
                }
                vif({ ctx.snackMounted }) {
                    ctx.exitSnackbar(this)
                }
            }
        }
    }

    /** Get.snackbar('notice', 'press_back_again_to_exit_the_app', bottom, 1500ms) 的默认样式。 */
    private fun exitSnackbar(container: com.tencent.kuikly.core.base.ViewContainer<*, *>) {
        val ctx = this
        with(container) {
            View {
                attr {
                    absolutePosition(left = 10f, right = 10f, bottom = ctx.navBottomViewPadding + 10f)
                    padding(16f, 16f, 16f, 16f)
                    borderRadius(15f)
                    backgroundColor(c(0x339E9E9EL))
                    opacity(if (ctx.snackShown) 1f else 0f)
                    transform(com.tencent.kuikly.core.base.Translate(0f, if (ctx.snackShown) 0f else 1f))
                    animate(Animation.easeOut(0.3f), ctx.snackShown)
                }
                Text {
                    attr {
                        text("notice".tr)
                        fontSize(16f)
                        fontWeightBold()
                        color(Color.WHITE)
                    }
                }
                Text {
                    attr {
                        marginTop(4f)
                        text("press_back_again_to_exit_the_app".tr)
                        fontSize(14f)
                        color(Color.WHITE)
                    }
                }
            }
        }
    }
}
