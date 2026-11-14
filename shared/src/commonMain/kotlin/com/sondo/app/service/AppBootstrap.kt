package com.sondo.app.service

import com.sondo.app.base.AppEnv
import com.sondo.app.base.AppHost
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.constants.AppTheme
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.http.Api
import com.sondo.app.core.storage.Sp
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.HostBinding
import com.sondo.app.core.utils.PermissionUtil
import com.sondo.app.nativeapi.SDAppModule
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.service.pay.PayService
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** 启动结果：首个要展示的页面（引导页或主框架）。 */
class AppBootstrapResult(val initialRoute: String)

/**
 * 应用级启动编排（对应 Flutter 侧 main() + AppBootstrap.start() + AppControllerBinding）。
 *
 * 这个类不负责展示 UI。根页面的用法：
 * 1. `created()` 里 `AppHost.attach(this)` 后调用 [prepare]（只做本地设置，不联网、不碰三方 SDK）；
 * 2. `PrivacyConsentService.hasConsent()` 为 true，或用户在 Gate 里点同意并 `grant()` 之后，调用 [start]；
 * 3. 按返回的 initialRoute 决定先进引导页还是直接展示主框架；主框架首帧后调用
 *    `DeepLinkRouter.markHomeReady()` 与 `TrackingInitializer.initialize()`。
 *
 * 所有会访问网络、账号会话或第三方 SDK 的启动工作都集中在 [start]，避免某个入口遗漏隐私授权门槛。
 */
object AppBootstrap {
    var initialRoute: String = Routes.root
        private set

    private var prepared = false
    private var inFlight = false
    private var finished: AppBootstrapResult? = null
    private var finishedHostId = ""
    private var inFlightHostId = ""
    private val waiters = ArrayList<Continuation<AppBootstrapResult>>()
    private val appListenerBinding = HostBinding()

    /**
     * 同意隐私政策之前就可以做的本地准备（对应 main() 里 runApp 之前的部分）：
     * 测试包恢复主接口地址、读安装渠道号、设置状态栏样式。
     */
    fun prepare() {
        if (prepared) return
        prepared = true
        // 这一步只需每进程一次；状态栏样式在页面重建后由原生容器保持。
        // 正式包忽略残留覆盖；测试包在首个请求前恢复主接口地址。
        if (AppEnv.showTokenEditor) {
            AppConstants.applyDebugApiBaseUrl(Sp.getString(StorageKey.debugApiBaseUrl))
        }
        try {
            val app: SDAppModule = AppHost.module(SDAppModule.MODULE_NAME)
            // 渠道号必须在联网之前读完，否则首批请求不带 App-Channel。iOS / 鸿蒙 / 未写渠道块的包保持空。
            Api.appChannel = if (AppEnv.isAndroid) app.getInstallChannel().trim() else ""
            app.setStatusBarStyle(AppTheme.statusBarStyle)
        } catch (e: Throwable) {
            DevLog.logger("AppBootstrap prepare error: $e")
        }
    }

    /**
     * 同意后才调用。并发调用共享同一次启动；同一个根页面上完成后再次调用直接返回上次的结果。
     * 根页面重建（Android 返回键退出后再进入，进程仍在）时会重新走一遍：
     * 各服务的 init 自身幂等，只重新挂监听 / 定时器并刷新数据。
     */
    suspend fun start(): AppBootstrapResult {
        val hostId = AppHost.pager.pagerId
        finished?.let { if (finishedHostId == hostId) return it }
        if (inFlight) {
            if (inFlightHostId == hostId) return suspendCoroutine { waiters.add(it) }
            // 上一个根页面在启动途中被销毁：那次启动不会再完成，等待者也已随页面失效。
            waiters.clear()
        }
        inFlight = true
        inFlightHostId = hostId
        val result = try {
            doStart()
        } catch (e: Throwable) {
            DevLog.logger("AppBootstrap start error: $e")
            AppBootstrapResult(Routes.root)
        }
        inFlight = false
        finished = result
        finishedHostId = hostId
        val pending = ArrayList(waiters)
        waiters.clear()
        pending.forEach { it.resume(result) }
        return result
    }

    private suspend fun doStart(): AppBootstrapResult {
        prepare()
        UserStore.bindSessionHooks()
        // 同意后才初始化友盟；初始化本身可能读取设备信息并开始采集。
        UmengUtils.initUmeng()
        // 系统深链独立于友盟统计，先接收并缓存，首页就绪后再消费。
        DeepLinkRouter.start()
        initConfigBefore()
        listenAppEvents()

        var route = Routes.root
        val isDisplayGuide = Sp.getBool(StorageKey.isDisplayGuide, true)
        if (isDisplayGuide) {
            getSplashConfig()
            route = Routes.guide
        }
        initConfigAfter()
        // 网络探测本身是联网行为，必须放在同意后的启动阶段。
        if (AppEnv.isIOS) {
            AppHost.launch { PermissionUtil.requestNetworkPermission() }
        }
        // 对应 AppControllerBinding：账号、播放器这类应用级状态在业务首帧之前就绪。
        initControllers()
        initialRoute = route
        return AppBootstrapResult(route)
    }

    private suspend fun initConfigBefore() {
        try {
            PayService.init()
            ConfigService.init()
            OssUploadService.init()
            // MemoryCacheService 是纯内存 object，无需初始化。
            LibraryTabRefreshService.init()
            MusicService.init()
            MvService.init()
            PushService.init()
        } catch (e: Throwable) {
            // 保留原来的容错策略：单个启动服务失败不阻断本地 App 进入。
            DevLog.logger("AppBootstrap -init before error: $e")
        }
    }

    /**
     * 应用级原生事件的唯一监听（挂在根页面上）：
     * - connectivity → [Api.hasNetwork] + [Events.NETWORK_CHANGED]；断网时关掉购买 loading；
     * - lifecycle → [Events.APP_LIFECYCLE]（各服务据此做前后台处理）；
     * - deepLink → [DeepLinkRouter.ingestUrl]；
     * - systemNav → [Events.SYSTEM_NAV]（根页面三键导航抬升）。
     *
     * 原生把冷启动 / 无人监听期间到达的深链补发给「第一个」注册的监听，所以根页面上不能有别的
     * SDAppModule 监听抢在这里之前注册；[DeepLinkRouter.start] 先用 getInitialLink 取走待处理链接。
     */
    private fun listenAppEvents() {
        if (!appListenerBinding.needsBind()) return
        val app: SDAppModule = AppHost.module(SDAppModule.MODULE_NAME)
        try {
            Api.hasNetwork = app.isConnected()
        } catch (e: Throwable) {
            // 取不到按有网处理
        }
        app.addEventListener { ev ->
            when (ev.optString("event", "")) {
                "connectivity" -> {
                    val types = ev.optJSONArray("types")
                    var none = false
                    if (types != null) {
                        for (i in 0 until types.length()) if (types.optString(i) == "none") none = true
                    }
                    Api.hasNetwork = !none
                    if (none) {
                        DevLog.logger("No network connection.")
                        PayService.disposeDialog(25)
                    }
                    EventBus.post(Events.NETWORK_CHANGED, JSONObject().put("connected", !none))
                }
                "lifecycle" -> {
                    val foreground = ev.optString("state", "") == "resumed"
                    EventBus.post(Events.APP_LIFECYCLE, JSONObject().put("foreground", foreground))
                }
                "deepLink" -> {
                    val url = ev.optString("url", "")
                    if (url.isNotEmpty()) DeepLinkRouter.ingestUrl(url)
                }
                "systemNav" -> EventBus.post(Events.SYSTEM_NAV, ev)
            }
        }
    }

    /** 引导页还没展示过时，先取启动页分流配置（2 秒超时，失败不阻塞）。 */
    private suspend fun getSplashConfig() {
        try {
            val result = ConfigService.fetchSplashConfig()
            DevLog.logger("result: ${result.toJson()}")
            if (result.exposureId.isNotEmpty()) {
                Sp.setJson(StorageKey.splashGuide, result.toJson())
            }
        } catch (e: Throwable) {
            DevLog.logger("fetch splash config during bootstrap error: $e")
        }
    }

    private fun initConfigAfter() {
        try {
            PollingService.init()
            TryFreeTaskService.init()
            AppUpdateService.init()
            VideoMergerService.init()
        } catch (e: Throwable) {
            DevLog.logger("AppBootstrap -init after error: $e")
        }
    }

    private fun initControllers() {
        try {
            UserStore.init()
            PlayerService.init()
        } catch (e: Throwable) {
            DevLog.logger("AppBootstrap -init controllers error: $e")
        }
    }
}
