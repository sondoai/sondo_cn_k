package com.sondo.app.service

import com.sondo.app.base.AppEnv
import com.sondo.app.base.AppHooks
import com.sondo.app.base.AppHost
import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.base.currentPager
import com.sondo.app.base.toast
import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiResult
import com.sondo.app.core.http.AuthSession
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.storage.Sp
import com.sondo.app.core.utils.ConfirmDialogLite
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.EmailHelper
import com.sondo.app.core.utils.HostBinding
import com.sondo.app.core.utils.LoadingUtil
import com.sondo.app.core.utils.LogUtils
import com.sondo.app.core.utils.delayMs
import com.sondo.app.core.utils.isPagerAlive
import com.sondo.app.core.utils.nowMs
import com.sondo.app.core.utils.onHost
import com.sondo.app.data.api.AigcWatermarkApi
import com.sondo.app.data.api.AuthApi
import com.sondo.app.data.models.UserModel
import com.sondo.app.nativeapi.SDAppModule
import com.sondo.app.nativeapi.nativeModule
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.service.login.JverifyLogin
import com.sondo.app.service.login.JverifyLoginOutcome
import com.sondo.app.service.login.WechatAuthOutcome
import com.sondo.app.service.login.WechatLogin
import com.sondo.app.service.login.jverifyOutcomeMessageKey
import com.sondo.app.service.login.parseWechatLoginData
import com.sondo.app.service.pay.PayService
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.timer.setTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 账号切换时需要联动、但 UserStore 不直接依赖其实现的能力。
 * 各服务在自己的 init() 里注册（对应参考工程里的 `Get.isRegistered<X>()` 判空调用）。
 */
object AccountHooks {
    /** 登出 / 换号：清播放列表（PlayerService.init 注册）。 */
    var cleanListPlay: (() -> Unit)? = null
    /** 登出 / 换号：清试用活跃任务（TryFreeTaskService.init 注册）。 */
    var clearTryFreeTask: (() -> Unit)? = null
    /** 使用声明弹窗（UI 层注册 UsageStatement.show）；type = upload / share / download，返回是否同意。 */
    var showUsageStatement: (suspend (pager: BasePager, type: String) -> Boolean)? = null
}

/**
 * 当前账号会话（对应 Flutter 侧 UserController）：用户资料、登录态、积分、会员态，
 * 以及所有登录 / 登出 / 删号路径和登录后的联动。
 *
 * 字段都是普通属性。变化后广播：
 * - [Events.USER_CHANGED] `{login}`：登录态或资料变化；
 * - [Events.POINTS_CHANGED] `{points, delta, animate}`：积分变化（animate=true 时导航栏飘 +N / -N）；
 * - [Events.SESSION_INVALID]：刷新令牌失效被强制下线；
 * - [Events.LOGIN_STATE] `{isLoggingIn}`：登录流程进行中（登录页按钮防连点）。
 *
 * 会话代次（AuthSession.revision）是所有异步续体的护栏：await 前取代次，await 后比对，不一致就丢弃结果。
 */
object UserStore {
    // ------------------------------------------------------------------ 状态

    var userInfo: UserModel? = null
        private set

    /** 资料已加载（缓存或服务端）才算登录。 */
    var isLogin = false
        private set

    /** 会员是否有效（expireDate > now）。 */
    var isSubscribed = false
        private set

    /** 积分余额（userInfo.point 的镜像）。 */
    var points = 0
        private set

    var currentVersion = ""
        private set

    var isMusicGenerLoading = false
        private set

    /** 风格字典 `[{genre, genreId}]`，编辑歌曲时选择风格用。 */
    var musicGenerList: List<JSONObject> = emptyList()
        private set

    /** 登录流程内是否正在执行第三方登录 / 取号，防连点。 */
    var isLoggingIn = false
        private set

    /** 创作按钮 i18n key：未登录注册试用 / 可试用 / 常规创作。 */
    val createButtonLabelKey: String
        get() {
            if (!isLogin) return "create_sign_up_trial"
            if ((userInfo?.hasTried ?: 0) > 0) return "free_trial"
            return "create"
        }

    /** 是否展示积分后缀（仅已试用态展示）。 */
    val createButtonShowPoints: Boolean
        get() {
            if (!isLogin) return false
            return (userInfo?.hasTried ?: 0) <= 0
        }

    private val hostBinding = HostBinding()

    /** 多个业务入口共用同一次登录流程及其结果。 */
    private var loginInFlight = false
    private var loginHostPagerId = ""
    private val loginWaiters = ArrayList<Pair<String, (Boolean) -> Unit>>()

    /** 登录流程关闭后，旧授权与换票回调不能再生效。 */
    private var loginDialogAttempt = 0

    /** 每次账号会话首次资料就绪后补查支付宝订单，避免支付成功刷新资料时重复查单。 */
    private var alipayRepairRevision: Int? = null

    private enum class LoginEntry { loggedIn, cancelled, wechat, sms, retry, popupFailed, sheet }

    // ------------------------------------------------------------------ 启动

    /**
     * 应用级初始化（隐私同意后由 AppBootstrap 调用一次）：
     * 恢复缓存账号 → 拉最新资料；监听前后台做静默补单；3 秒后拉风格字典。
     */
    fun init() {
        bindSessionHooks()
        // 同一个根页面上只初始化一次；根页面重建后监听和定时器都要重新挂。
        if (!hostBinding.needsBind()) return
        currentVersion = AppEnv.appVersionName

        if (AuthSession.accessToken.isNotEmpty()) {
            val userInfoStr = Sp.getString(StorageKey.userInfoStr)
            if (userInfoStr.isNotEmpty()) {
                try {
                    val cached = UserModel.fromJsonOrNull(JSONObject(userInfoStr))
                    if (cached != null) {
                        userInfo = cached
                        isLogin = true
                        // 冷启动先用缓存账号，避免首批事件落成未登录。
                        UmengUtils.setAnalyticsUser("${cached.id}")
                        updateIsSubscribed()
                        updatePointsFromServer(cached.point)
                        notifyUser()
                    }
                } catch (e: Throwable) {
                    DevLog.logger("userInfoStr error: $e")
                }
            }
            AppHost.launch { getUserInfo() }
        }

        // 回到前台：iOS 静默补单；Android 从支付宝回来后查原订单，不重新下单。
        AppHost.pager.acquireModule<NotifyModule>(NotifyModule.MODULE_NAME).addNotify(Events.APP_LIFECYCLE) { data ->
            if (data?.optBoolean("foreground", false) == true) {
                AppHost.run {
                    if (AppEnv.isIOS) PayService.resumePurchase(showRestoreTips = false) else PayService.resumeAlipayOrder()
                }
            }
        }

        setTimeout(AppHost.pager.pagerId, 3000) { AppHost.launch { getMusicGenerList() } }
    }

    /**
     * 把「会话失效」接到自动登出（Api 在刷新令牌失效时回调）。
     * 启动最早期就要接上：配置等请求可能先于账号初始化发出。
     */
    fun bindSessionHooks() {
        AppHooks.sessionInvalidHandler = {
            // 未登录时匿名请求被 401 也会走到这里：照常清理，但不当作「被强制下线」广播。
            val hadSession = isLogin || AuthSession.accessToken.isNotEmpty()
            // 没有会话时不清播放现场：未登录播放音乐时，播放计数等匿名请求被 401 不能把正在播的歌停掉、
            // 进度和歌词清空（真机上表现为「播几秒就停」）。
            clearSession(keepPlayer = !hadSession)
            if (hadSession) EventBus.post(Events.SESSION_INVALID)
        }
    }

    private fun notifyUser() {
        EventBus.post(Events.USER_CHANGED, JSONObject().put("login", isLogin))
    }

    private fun setLoggingIn(value: Boolean) {
        if (isLoggingIn == value) return
        isLoggingIn = value
        EventBus.post(Events.LOGIN_STATE, JSONObject().put("isLoggingIn", value))
    }

    suspend fun getMusicGenerList() {
        if (isMusicGenerLoading) return
        if (musicGenerList.isNotEmpty()) return
        isMusicGenerLoading = true
        musicGenerList = AuthApi.musicGenerList().getOrNull() ?: emptyList()
        isMusicGenerLoading = false
    }

    // ------------------------------------------------------------------ 用户资料

    /** 同 [getUserInfo]（部分调用方用这个名字）。 */
    suspend fun refreshUserInfo(): Boolean = getUserInfo()

    /**
     * 拉取当前用户资料。只有当前会话的资料成功加载才返回 true；退出或换号后的旧响应直接丢弃。
     * 这是积分 / 会员态唯一的刷新途径：消费、购买、兑换、改资料、生成失败退款后都要调用。
     * 请求跑在根页面上：调用方页面中途关闭也会完成。
     */
    suspend fun getUserInfo(): Boolean = onHost { loadUserInfo() }

    private suspend fun loadUserInfo(): Boolean {
        val revision = AuthSession.revision
        val isCurrent = { AuthSession.isCurrent(revision) && AuthSession.accessToken.isNotEmpty() }
        if (!isCurrent()) return false
        val needsPurchaseRepair = !isLogin
        try {
            var retryCount = 0
            var data = AuthApi.getUserInfo().getOrNull()
            if (!isCurrent()) return false
            while (data == null && retryCount < 3) {
                delayMs(1000)
                if (!isCurrent()) return false
                data = AuthApi.getUserInfo().getOrNull()
                if (!isCurrent()) return false
                retryCount++
            }
            if (data == null) return false
            userInfo = data
            isLogin = true
            // 以服务端用户 id 绑定友盟账号，换号后覆盖缓存里的旧 id。
            UmengUtils.setAnalyticsUser("${data.id}")
            if (needsPurchaseRepair && AppEnv.isIOS) {
                // 登录完成后触发静默补单。
                PayService.resumePurchase(showRestoreTips = false)
            } else if (!AppEnv.isIOS && alipayRepairRevision != revision) {
                // 缓存登录冷启动也要补查；先记录代次，防止成功收尾刷新资料时递归恢复。
                alipayRepairRevision = revision
                PayService.resumeAlipayOrder()
            }
            updateIsSubscribed()
            updatePointsFromServer(data.point)
            AppHost.launch { ConfigService.getCountryPricing() }
            try {
                Sp.setString(StorageKey.userInfoStr, data.toJson().toString())
            } catch (e: Throwable) {
                DevLog.logger("userInfoStr error: $e")
            }
            AppHost.launch { ConfigService.getDiscountConfig() }
            // 优惠查询和推送绑定不阻塞登录完成，各自仍受同一次会话约束。
            val userId = data.id
            AppHost.launch { afterUserInfoLoaded(userId, revision, needsPurchaseRepair) }
            setTimeout(AppHost.pager.pagerId, 2000) {
                if (isCurrent()) AppHost.launch { PushService.autoBindUserFcmToken() }
            }
            notifyUser()
            return true
        } catch (e: Throwable) {
            LogUtils.logRed("getUserInfo error $e")
            return false
        }
    }

    private suspend fun afterUserInfoLoaded(userId: Long, revision: Int, needsPurchaseRepair: Boolean) {
        val isCurrent = { AuthSession.isCurrent(revision) && isLogin }
        try {
            RecallFlow.storeEncryptedUserId(userId, isCurrent)
            if (!isCurrent()) return
            if (needsPurchaseRepair) RecallFlow.refreshCountdownEligibility(isCurrent)
            if (!isCurrent()) return
            RecallFlow.tryAuto(isCurrent)
        } catch (e: Throwable) {
            DevLog.logger("登录后优惠初始化失败: $e")
        }
    }

    /** 水印开关保存成功后写回当前资料，设置页直接读内存，不再另拉用户信息。 */
    fun applyAigcWatermarkEnabled(enabled: Boolean) {
        val current = userInfo
        if (current == null || current.aigcWatermarkEnabled == enabled) return
        val next = current.copy(aigcWatermarkEnabled = enabled)
        userInfo = next
        try {
            Sp.setString(StorageKey.userInfoStr, next.toJson().toString())
        } catch (e: Throwable) {
            DevLog.logger("userInfoStr error: $e")
        }
        notifyUser()
    }

    /**
     * 保存下载水印开关（关闭时提交声明版本），成功后写回内存资料。
     * 登录、会员校验与声明弹窗由设置页负责，这里只做保存。
     */
    suspend fun saveAigcWatermark(enabled: Boolean): Boolean = onHost {
        val saved = AigcWatermarkApi.save(enabled, AppConstants.aigcStatementVersion).getOrNull() == true
        if (saved) applyAigcWatermarkEnabled(enabled)
        saved
    }

    fun updateIsSubscribed() {
        val expireDate = userInfo?.expireDate
        isSubscribed = if (expireDate == null || expireDate <= 0) false else expireDate > nowMs()
        if (!isSubscribed) {
            Sp.setBool(StorageKey.isRemoveWatermark, true)
            Sp.setBool(StorageKey.isRemoveSubtitles, true)
        }
    }

    /** 更新积分。上一次是 0（首次加载）时静默赋值；否则带上差值让导航栏飘一下。 */
    fun updatePointsFromServer(newPoints: Int) {
        val delta = newPoints - points
        if (delta == 0) return
        val animate = points != 0
        points = newPoints
        EventBus.post(
            Events.POINTS_CHANGED,
            JSONObject().put("points", newPoints).put("delta", delta).put("animate", animate),
        )
    }

    // ------------------------------------------------------------------ 登出 / 删号

    /**
     * 自动失效立即清理；主动退出只在服务端成功且仍属于原会话时清理，成功后回到主框架。
     * 服务端失败（含断网）保持登录态。
     */
    suspend fun logout(isAuto: Boolean = false) {
        if (isAuto) {
            clearSession()
            return
        }
        val revision = AuthSession.revision
        LoadingUtil.show()
        try {
            val result = AuthApi.logout()
            if (!AuthSession.isCurrent(revision)) return
            if (result.isSuccess) {
                UmengUtils.logEventButtonClick("logout")
                clearSession()
                backToRootAfterAccountGone()
            }
        } finally {
            LoadingUtil.close()
        }
    }

    private fun backToRootAfterAccountGone() {
        AppNav.popToRoot()
        // 退栈中的页面销毁完成后再次清理，防止旧草稿回写。
        clearMemoryCache()
        setTimeout(AppHost.pager.pagerId, 600) { clearMemoryCache() }
    }

    /** 清凭证前递增代次，让在途资料、刷新和分页结果立即失效。 */
    private fun clearSession(keepPlayer: Boolean = false) {
        AuthSession.clear()
        resetAccountState(keepPlayer)
    }

    /** 切换与失效共用清理，账号相关的连接、缓存和动画均不能带到新账号。 */
    private fun resetAccountState(keepPlayer: Boolean = false) {
        userInfo = null
        isLogin = false
        if (points != 0) {
            points = 0
            EventBus.post(Events.POINTS_CHANGED, JSONObject().put("points", 0).put("delta", 0).put("animate", false))
        }
        ConfigService.discountOpen = false
        updateIsSubscribed()
        UmengUtils.clearAnalyticsUser()
        Sp.setString(StorageKey.userInfoStr, "")
        if (!keepPlayer) AccountHooks.cleanListPlay?.invoke()
        PushService.resetOnLogout()
        clearMemoryCache()
        AccountHooks.clearTryFreeTask?.invoke()
        PayService.resetOnLogout()
        notifyUser()
    }

    private fun clearMemoryCache() = MemoryCacheService.clearAll()

    /** 注销账号：确认弹窗 → 服务端成功且仍属于原会话才清理，并清掉回归资格密文。 */
    fun delAccount(pager: BasePager? = null) {
        val host = pager ?: currentPager() ?: return
        ConfirmDialogLite.show(
            host,
            confirmText = "delete".tr,
            content = "delete_account_subscription_notice".tr,
            onConfirm = {
                host.launch {
                    val revision = AuthSession.revision
                    LoadingUtil.show(pager = host)
                    val result = AuthApi.delUser()
                    LoadingUtil.close()
                    if (!AuthSession.isCurrent(revision)) return@launch
                    if (result.isSuccess) {
                        UmengUtils.logEventButtonClick("delete_account")
                        clearSession()
                        RecallFlow.clearEncryptedUserId()
                        // 当前页马上被退栈，提示挂到根页面上。
                        (AppNav.host ?: host).toast("account_deleted_successfully".tr)
                        backToRootAfterAccountGone()
                    }
                }
            },
        )
    }

    // ------------------------------------------------------------------ 资料 / 兑换 / 邀请

    /**
     * 提交资料修改（编辑弹层的结果）。头像没换时沿用当前头像。成功后刷新用户资料。
     * @param name 新昵称
     * @param newImage 新头像 URL（没换传空串）
     */
    suspend fun updateUserInfo(name: String?, newImage: String = ""): Boolean {
        LoadingUtil.show()
        val image = newImage.ifEmpty { userInfo?.avatar ?: "" }
        val res = AuthApi.updateUserInfo(name, image)
        LoadingUtil.close()
        return if (res.isSuccess) {
            UmengUtils.logEventButtonClick("userinfo_edit_successful")
            AppHost.launch { getUserInfo() }
            true
        } else {
            UmengUtils.logEventButtonClick("userinfo_edit_failed")
            false
        }
    }

    /**
     * 兑换码（CDkey 输入弹窗点确认后调用）。空串只提示；成功提示并刷新资料。
     * @return 是否兑换成功（成功后调用方关闭输入弹窗）
     */
    suspend fun redeemCode(cdkey: String): Boolean {
        if (cdkey.isEmpty()) {
            toast("please_enter_the_CDkey".tr)
            return false
        }
        LoadingUtil.show()
        val res = AuthApi.exchangeGift(cdkey)
        LoadingUtil.close()
        if (!res.isSuccess) return false
        toast("activation_successful".tr)
        AppHost.launch { getUserInfo() }
        return true
    }

    /** 上报分享并领取分享奖励积分；成功后刷新资料。 */
    suspend fun shareAndSendPoints(): Boolean {
        val ok = AuthApi.shareAndSendPoints().isSuccess
        if (ok) AppHost.launch { getUserInfo() }
        return ok
    }

    /** 绑定上级邀请码。失败文案由接口层提示；成功后刷新资料（superRegCode）。 */
    suspend fun bindSuperRegCode(code: String): Boolean {
        val res = Api.put(ApiPaths.bindSuperRegCode, JSONObject().put("superRegCode", code))
        val ok = res.getOrNull()?.opt("data") == true
        if (ok) AppHost.launch { getUserInfo() }
        return ok
    }

    // ------------------------------------------------------------------ 杂项入口

    suspend fun sendEmail() {
        EmailHelper.sendEmail(content = "\n${"feedback_email_hint".tr}", userId = userInfo?.id)
    }

    /** 应用内浏览器打开《使用条款》。 */
    fun openTerms(pager: BasePager? = null) = openInApp(AppConstants.termsUrl, pager)

    /** 应用内浏览器打开《隐私政策》。 */
    fun openPrivacyPolicy(pager: BasePager? = null) = openInApp(AppConstants.privacyUrl, pager)

    private fun openInApp(url: String, pager: BasePager?) {
        val host = pager ?: currentPager() ?: return
        openInAppUrl(host, url)
    }

    /**
     * 应用内打开网页（参考工程 `launchUrl` 默认模式：Android Custom Tabs / iOS SFSafariViewController）。
     * 鸿蒙没有对应的应用内浏览器标签，原生侧只能跳系统浏览器：http(s) 链接改用 App 自己的网页页。
     */
    fun openInAppUrl(host: BasePager, url: String) {
        val lower = url.lowercase()
        if (AppEnv.isOhos && (lower.startsWith("https://") || lower.startsWith("http://"))) {
            host.nav.push(Routes.webview, JSONObject().put("url", url))
            return
        }
        host.launch {
            try {
                host.acquireModule<SDAppModule>(SDAppModule.MODULE_NAME).openUrl(url, "inApp")
            } catch (e: Throwable) {
                DevLog.logger("openUrl error $e")
            }
        }
    }

    /** 复制用户 id。Android 13+ 有系统剪贴板浮层，仅在这些厂商机型上补自己的提示。 */
    fun copyId() {
        val text = "${userInfo?.id}"
        try {
            if (text.isEmpty()) return
            val app: SDAppModule = nativeModule(SDAppModule.MODULE_NAME)
            app.copyToClipboard(text)
            if (AppEnv.isAndroid) {
                val info = app.getDeviceInfo()
                if (info.optInt("sdkInt", 0) < 33 || androidOemSkipsClipboardToast(info)) {
                    toast("copy_to_clipboard".tr)
                }
            } else {
                toast("copy_to_clipboard".tr)
            }
        } catch (e: Throwable) {
            toast("copy_failed".tr)
        }
    }

    /** 小米/华为/OPPO/vivo 及其子品牌的 Android 13+ 经常没有系统复制提示。 */
    private fun androidOemSkipsClipboardToast(info: JSONObject): Boolean {
        val name = "${info.optString("manufacturer", "")} ${info.optString("brand", "")}".lowercase()
        val vendors = listOf("xiaomi", "redmi", "poco", "huawei", "honor", "oppo", "realme", "oneplus", "vivo", "iqoo")
        return vendors.any { name.contains(it) }
    }

    /** 社交媒体入口弹窗（Telegram / Twitter / Discord / YouTube / Blog）。 */
    fun openContactUs(pager: BasePager? = null) {
        val host = pager ?: currentPager() ?: return
        ContactUsDialog.show(host)
    }

    /**
     * 使用声明：远端开关关闭直接放行；否则每个 key 只弹一次，同意后记住。
     * @param key StorageKey.isAgreeUpload / isAgreeShare / isAgreeDown
     * @param type "upload" | "share" | "download"
     */
    suspend fun showAgree(key: String, type: String, pager: BasePager? = null): Boolean {
        // 不需要显示使用声明
        if (!ConfigService.displayUseExplain) return true
        // 已经同意
        if (Sp.getBool(key, false)) return true
        val host = pager ?: currentPager() ?: return false
        val presenter = AccountHooks.showUsageStatement ?: return false
        val agree = presenter(host, type)
        if (agree) {
            Sp.setBool(key, true)
            return true
        }
        return false
    }

    // ------------------------------------------------------------------ 登录入口

    /** 登录页 / 手机号登录页 pop 时带回的结果（`{success: Boolean}`）。 */
    fun loginResult(success: Boolean): JSONObject = JSONObject().put("success", success)

    private fun isSuccess(result: JSONObject?): Boolean = result?.optBoolean("success", false) == true

    /** 动作前要求登录：已登录直接执行；否则走登录流程，成功后执行 [onLogin]。 */
    fun checkLogin(pager: BasePager? = null, onLogin: () -> Unit) {
        if (isLogin) {
            onLogin()
            return
        }
        val host = pager ?: currentPager() ?: return
        host.launch { if (showLoginDialog(host)) onLogin() }
    }

    /** 同 [showLoginDialog]。 */
    suspend fun toLogin(pager: BasePager? = null): Boolean = showLoginDialog(pager)

    /**
     * 多个业务入口共同等待一次登录；成功必须与当前实际用户态一致。
     * 流程：本机号码授权弹层（一键登录 / 弹层里改走微信或短信）→ 取不到号码时落到应用内登录页 [Routes.login]。
     */
    suspend fun showLoginDialog(pager: BasePager? = null): Boolean {
        if (isLogin) return true
        val host = pager ?: currentPager() ?: return false
        if (loginInFlight) {
            if (isPagerAlive(loginHostPagerId)) {
                val waiterId = host.pagerId
                return suspendCoroutine { cont -> loginWaiters.add(waiterId to { ok -> cont.resume(ok) }) }
            }
            // 发起登录的页面已经销毁，那次流程不会再有结果。
            finishLoginFlow(false)
        }
        loginInFlight = true
        loginHostPagerId = host.pagerId
        val result = try {
            openLoginDialog(host)
        } catch (e: Throwable) {
            DevLog.logger("login flow error: $e")
            false
        }
        finishLoginFlow(result)
        return result
    }

    private fun finishLoginFlow(result: Boolean) {
        loginInFlight = false
        loginHostPagerId = ""
        val pending = ArrayList(loginWaiters)
        loginWaiters.clear()
        // 切回等待者自己的页面上下文再恢复
        pending.forEach { (pagerId, cb) ->
            if (isPagerAlive(pagerId)) setTimeout(pagerId, 0) { cb(result && isLogin) }
        }
    }

    private suspend fun openLoginDialog(host: BasePager): Boolean {
        val attempt = ++loginDialogAttempt
        try {
            var currentName = UmengUtils.routePath(AppNav.currentRoute())
            if (currentName.isEmpty() || currentName == "/") {
                when (AppNav.mainTabIndex) {
                    0 -> currentName = "/home"
                    1 -> currentName = "/mv"
                    2 -> currentName = "/music"
                    3 -> currentName = "/library"
                }
            }
            UmengUtils.logEvent("open_login", mapOf("page_name" to currentName))
        } catch (e: Throwable) {
            DevLog.logger("send event:$e")
        }
        try {
            // 先用本机号码授权弹层：弹层里点微信或短信，办完没登录成功就回到同一个弹层。
            // 只有号码取不到、弹层拉不起来时，才落到应用内登录页，在那里走手机号或微信登录。
            var popupFailures = 0
            while (true) {
                val entry = quickLogin(attempt, host)
                if (!isLoginAttemptCurrent(attempt)) return false
                if (entry == LoginEntry.loggedIn) return isLogin
                if (entry == LoginEntry.cancelled) return false
                if (entry == LoginEntry.sheet) break
                if (entry == LoginEntry.retry) continue
                if (entry == LoginEntry.popupFailed) {
                    // 停留太久号码过期等：重拉同一个弹层换新号码。连续两次都失败才落到登录页，避免来回闪。
                    if (++popupFailures >= 2) break
                    continue
                }
                popupFailures = 0
                if (entry == LoginEntry.wechat) {
                    startWechatLogin(attempt, host)
                } else {
                    openPhoneLoginPage(attempt, host)
                }
                if (!isLoginAttemptCurrent(attempt)) return false
                if (isLogin) return true
            }
            val result = awaitPage(host, Routes.login, JSONObject())
            return result && isLogin
        } finally {
            if (loginDialogAttempt == attempt) {
                loginDialogAttempt++
                setLoggingIn(false)
            }
        }
    }

    private suspend fun awaitPage(pager: BasePager, page: String, args: JSONObject): Boolean =
        suspendCoroutine { cont -> pager.nav.push(page, args) { result -> cont.resume(isSuccess(result)) } }

    /** 只校验这次登录没有被登出或新一次登录顶掉（页面是否存活由协程所属页面保证）。 */
    private fun isLoginAttemptCurrent(attempt: Int): Boolean = attempt == loginDialogAttempt

    /**
     * 进登录先拉本机号码授权弹层，弹层里直接一键登录。
     * 取号慢时显示全局 loading；号码取不到时不提示，直接交给应用内登录页。
     */
    private suspend fun quickLogin(attempt: Int, host: BasePager): LoginEntry {
        val revision = AuthSession.revision
        setLoggingIn(true)
        // 取号通常很快；超过 300ms 才出 loading，取不到号码时不闪一下。
        var loadingShown = false
        var loadingCancelled = false
        host.postDelayed(300) {
            if (!loadingCancelled) {
                loadingShown = true
                LoadingUtil.show(pager = host)
            }
        }
        val closeLoading = {
            loadingCancelled = true
            if (loadingShown) {
                loadingShown = false
                LoadingUtil.close()
            }
        }
        try {
            val result = JverifyLogin.start(otherWays = true, onPresented = closeLoading)
            closeLoading()
            if (!isLoginAttemptCurrent(attempt) || !AuthSession.isCurrent(revision)) return LoginEntry.cancelled
            return when (result.outcome) {
                JverifyLoginOutcome.cancelled -> LoginEntry.cancelled
                JverifyLoginOutcome.wechat -> LoginEntry.wechat
                JverifyLoginOutcome.sms -> LoginEntry.sms
                JverifyLoginOutcome.unavailable, JverifyLoginOutcome.unsupported -> LoginEntry.sheet
                JverifyLoginOutcome.failed -> {
                    // 弹层没出现过就是号码取不到；出现过才失败，提示后由调用方重拉。
                    if (!result.shown) {
                        LoginEntry.sheet
                    } else {
                        toast("login_error".tr)
                        LoginEntry.popupFailed
                    }
                }
                JverifyLoginOutcome.tokenReady -> {
                    val token = result.token?.trim() ?: ""
                    if (token.isEmpty()) {
                        LoginEntry.sheet
                    } else {
                        // 换票失败已提示，回到同一个弹层让用户重试或改走其他方式。
                        if (exchangeJverifyToken(token, attempt, revision)) LoginEntry.loggedIn else LoginEntry.retry
                    }
                }
            }
        } catch (e: Throwable) {
            DevLog.logger("极光一键登录 error: $e")
            return LoginEntry.sheet
        } finally {
            closeLoading()
            if (attempt == loginDialogAttempt) setLoggingIn(false)
        }
    }

    private suspend fun openPhoneLoginPage(attempt: Int, pager: BasePager): Boolean {
        if (!isLoginAttemptCurrent(attempt) || isLoggingIn) return false
        // 同步占住入口，重复点击不能堆叠多个手机号登录页。
        setLoggingIn(true)
        try {
            // 不带 bindToken：普通短信登录
            return awaitPage(pager, Routes.emailLogin, JSONObject())
        } finally {
            if (attempt == loginDialogAttempt) setLoggingIn(false)
        }
    }

    /** 授权弹层拿到的运营商 token 换业务会话，返回是否登录成功。失败会提示。 */
    private suspend fun exchangeJverifyToken(loginToken: String, attempt: Int, revision: Int): Boolean {
        LoadingUtil.show()
        var ok = false
        var savedRevision: Int? = null
        try {
            val login = AuthApi.oneClickLogin(loginToken)
            if (!isLoginAttemptCurrent(attempt) || !AuthSession.isCurrent(revision)) return false
            ok = login.fold(
                onSuccess = { applyLoginSession(it) },
                onFailure = {
                    DevLog.logger("oneClickLogin failed")
                    false
                },
            )
            if (ok) savedRevision = AuthSession.revision
        } finally {
            LoadingUtil.close()
        }
        if (!isLoginAttemptCurrent(attempt)) return false
        if (!ok) {
            toast("login_error".tr)
            return false
        }
        if (savedRevision == null || !AuthSession.isCurrent(savedRevision) || !isLogin) return false
        toast("login_success".tr)
        return true
    }

    /**
     * 所有登录方式的公共收尾：写入令牌 → 清掉上一个账号的状态 → 拉用户资料。
     * 没有 accessToken 或资料拉不到都视为失败（并回滚会话）。
     * @param data 登录接口的 data 对象 `{accessToken, refreshToken}`
     */
    suspend fun applyLoginSession(data: Any?): Boolean {
        if (data !is JSONObject) return false
        val accessToken = tokenField(data, "accessToken")
        val refreshToken = tokenField(data, "refreshToken")
        if (accessToken.isEmpty()) return false
        AuthSession.replace(accessToken, refreshToken)
        val revision = AuthSession.revision
        resetAccountState()
        val loaded = getUserInfo()
        if (!AuthSession.isCurrent(revision)) return false
        if (!loaded || !isLogin || userInfo == null) {
            clearSession()
            return false
        }
        return true
    }

    private fun tokenField(data: JSONObject, key: String): String {
        val v = data.opt(key) ?: return ""
        val s = v.toString().trim()
        return if (s == "null") "" else s
    }

    // ------------------------------------------------------------------ 微信登录

    /** 拉起微信授权。已绑定直接入会话；未绑定把 bindToken 交给手机号登录页。返回是否已登录。 */
    private suspend fun startWechatLogin(attempt: Int, pager: BasePager): Boolean {
        if (isLoggingIn || !isLoginAttemptCurrent(attempt)) return false
        val revision = AuthSession.revision
        setLoggingIn(true)
        try {
            val auth = WechatLogin.start()
            if (!isLoginAttemptCurrent(attempt) || !AuthSession.isCurrent(revision)) return false
            val code = auth.code?.trim() ?: ""
            // 取消、未安装、失败都不拿 code 去换票。
            if (auth.outcome != WechatAuthOutcome.codeReady || code.isEmpty()) {
                toastWechatOutcome(auth.outcome)
                return false
            }
            return exchangeWechatCode(code, attempt, revision, pager)
        } catch (e: Throwable) {
            DevLog.logger("微信登录 error")
            if (isLoginAttemptCurrent(attempt)) toast("login_error".tr)
            return false
        } finally {
            if (attempt == loginDialogAttempt) setLoggingIn(false)
        }
    }

    /** 授权 code 换会话或 bindToken。凭证约 300 秒，过期由服务端文案提示后重新授权。 */
    private suspend fun exchangeWechatCode(code: String, attempt: Int, revision: Int, pager: BasePager): Boolean {
        LoadingUtil.show()
        val login = try {
            AuthApi.wechatLogin(code)
        } finally {
            LoadingUtil.close()
        }
        if (!isLoginAttemptCurrent(attempt) || !AuthSession.isCurrent(revision)) return false
        // 接口失败已由接口层提示；既没有 bindToken 也没有 login.accessToken 时不能当成已登录。
        val data = parseWechatLoginData(login.getOrNull()) ?: run {
            DevLog.logger("wechatLogin failed")
            return false
        }
        if (!data.needBindMobile) {
            val ok = applyLoginSession(data.login)
            if (!isLoginAttemptCurrent(attempt)) return false
            if (!ok) {
                toast("login_error".tr)
                return false
            }
            toast("login_success".tr)
            return true
        }
        val bindToken = data.bindToken?.trim() ?: ""
        if (bindToken.isEmpty()) {
            toast("login_error".tr)
            return false
        }
        // 绑定模式：手机号登录页带 bindToken，只做绑定。
        return awaitPage(pager, Routes.emailLogin, JSONObject().put("bindToken", bindToken))
    }

    /** 取消不提示。AppId 未配置和未安装微信分开提示。 */
    private fun toastWechatOutcome(outcome: WechatAuthOutcome) {
        val key = when (outcome) {
            WechatAuthOutcome.unavailable -> "login_dialog_wechat_unavailable"
            WechatAuthOutcome.notInstalled -> "login_dialog_wechat_not_installed"
            WechatAuthOutcome.failed, WechatAuthOutcome.codeReady -> "login_error"
            WechatAuthOutcome.cancelled -> null
        } ?: return
        toast(key.tr)
    }

    // ------------------------------------------------------------------ 供登录页调用

    /**
     * 应用内登录页（[Routes.login]）的「微信登录」按钮。
     * @return true 表示已登录，登录页应 `nav.pop(UserStore.loginResult(true))`
     */
    suspend fun loginByWechat(pager: BasePager): Boolean = startWechatLogin(loginDialogAttempt, pager)

    /**
     * 应用内登录页的「手机号登录」按钮：打开 [Routes.emailLogin] 并等待结果。
     * @return true 表示已登录，登录页应 `nav.pop(UserStore.loginResult(true))`
     */
    suspend fun loginByPhone(pager: BasePager): Boolean = openPhoneLoginPage(loginDialogAttempt, pager)

    /** 手机号登录页：发验证码。业务失败由接口层提示；返回是否已受理（受理后页面开始 59 秒倒计时）。 */
    suspend fun sendSmsCode(mobile: String): Boolean {
        val res = AuthApi.sendSmsCode(mobile.trim())
        if (res.isFailure) DevLog.logger("sendSmsCode failed")
        return res.isSuccess
    }

    /**
     * 手机号登录页：短信登录（未注册手机号由后端自动注册）。
     * @return true 表示已登录（已提示「登录成功」），页面应 `nav.pop(UserStore.loginResult(true))`
     */
    suspend fun smsLogin(mobile: String, code: String): Boolean =
        submitSmsSession(isBind = false) { AuthApi.smsLogin(mobile.trim(), code.trim()) }

    /** 手机号登录页（绑定模式）：微信 bindToken + 短信验证码完成绑定并登录。 */
    suspend fun wechatBindMobile(bindToken: String, mobile: String, code: String): Boolean =
        submitSmsSession(isBind = true) { AuthApi.wechatBindMobile(bindToken, mobile.trim(), code.trim()) }

    private suspend fun submitSmsSession(
        isBind: Boolean,
        request: suspend () -> ApiResult<JSONObject>,
    ): Boolean {
        val revision = AuthSession.revision
        val result = request()
        if (!AuthSession.isCurrent(revision)) return false
        // 成功响应却没有令牌对象时按失败，避免空会话被当成已登录。
        val data = result.getOrNull()?.takeIf { it.length() > 0 }
        if (data == null) {
            DevLog.logger(if (isBind) "wechatBindMobile failed" else "smsLogin failed")
            return false
        }
        if (!saveSession(data)) return false
        toast("login_success".tr)
        return true
    }

    /**
     * 手机号登录页（绑定模式）：用新的极光 loginToken 验号并完成绑定。授权页里取消的旧凭证不能重放。
     * @return true 表示已登录（已提示「登录成功」）
     */
    suspend fun bindByOneClick(bindToken: String): Boolean {
        val revision = AuthSession.revision
        try {
            val auth = JverifyLogin.start()
            if (!AuthSession.isCurrent(revision)) return false
            val loginToken = auth.token?.trim() ?: ""
            if (auth.outcome != JverifyLoginOutcome.tokenReady || loginToken.isEmpty()) {
                val key = jverifyOutcomeMessageKey(auth.outcome)
                if (key != null) toast(key.tr)
                return false
            }
            var saved = false
            var savedRevision: Int? = null
            LoadingUtil.show()
            try {
                val result = AuthApi.wechatBindMobileOneClick(bindToken, loginToken)
                if (!AuthSession.isCurrent(revision)) return false
                val data = result.getOrNull()?.takeIf { it.length() > 0 }
                if (data == null) {
                    DevLog.logger("wechatBindMobileOneClick failed")
                } else {
                    saved = saveSession(data)
                }
                if (saved) savedRevision = AuthSession.revision
            } finally {
                LoadingUtil.close()
            }
            if (!saved || savedRevision == null || !AuthSession.isCurrent(savedRevision)) return false
            if (!isLogin) return false
            toast("login_success".tr)
            return true
        } catch (e: Throwable) {
            DevLog.logger("微信一键绑定 error")
            toast("login_error".tr)
            return false
        }
    }

    /** 仅在认证接口成功分支调用，不重复后端业务失败的提示。 */
    private suspend fun saveSession(data: JSONObject): Boolean {
        val saved = applyLoginSession(data)
        if (!saved) toast("login_error".tr)
        return saved
    }
}
