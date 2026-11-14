package com.sondo.app.service

import com.sondo.app.base.AppEnv
import com.sondo.app.base.AppHost
import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.base.currentPager
import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.AuthSession
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.storage.Sp
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.HostBinding
import com.sondo.app.core.utils.PermissionUtil
import com.sondo.app.core.utils.delayMs
import com.sondo.app.data.api.AppConfigApi
import com.sondo.app.data.models.MessageModel
import com.sondo.app.data.models.MessageNoticeAction
import com.sondo.app.nativeapi.SDPermissionModule
import com.sondo.app.nativeapi.SDPushModule
import com.sondo.app.service.analytics.UmengUtils
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.timer.setTimeout
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 极光推送（对应 Flutter 侧 PushService）。
 *
 * - 只有通知权限已授予才初始化 SDK；没授权的用户完全不触碰极光。
 * - Registration ID 绑到账号：每个（会话代次, rid）只绑一次，最多 1 + 3 次尝试。
 * - 前台收到推送不弹系统横幅：广播 [Events.PUSH_FOREGROUND]，由根页面展示应用内横幅，
 *   点击后调用 [handleForegroundTap]。
 * - 状态变化广播 [Events.PUSH_STATE]。
 */
object PushService {
    const val GRANTED = "granted"
    const val DENIED = "denied"
    const val BLOCKED = "blocked"

    var retryCount = 0
        private set
    private var didSetup = false

    /** 系统通知权限是否已授予。 */
    var isNotified = false
        private set

    /** 启用通知按钮防重入，避免连点叠多个授权流程 */
    private var isEnablingNotify = false

    /** 极光 Registration ID。上报 bindUserJPushToken 时字段名仍叫 fcmToken。 */
    var token: String? = null
        private set

    private var ridInFlight = false
    private val ridWaiters = ArrayList<Continuation<String?>>()
    private var bindingInFlight = false
    private val bindingWaiters = ArrayList<Continuation<Unit>>()
    private var bindingGeneration = 0
    private var bindingRevision: Int? = null
    private var bindingRid: String? = null
    private var boundRevision: Int? = null
    private var boundRid: String? = null

    private var appResumed = true
    private val lifecycleBinding = HostBinding()
    private val listenerBinding = HostBinding()

    /** 前台推送在这些页面上直接丢弃（生成流程页）。 */
    val blackList = listOf(
        Routes.storyLine, // 故事线查看
        Routes.mvListDetail, // 视频列表详情
        Routes.thinkLineMv, // MV 信息流
        Routes.editVideo, // 编辑视频整合页
    )

    private val push: SDPushModule get() = AppHost.module(SDPushModule.MODULE_NAME)

    /** 设备 RID 与通知权限可复用，账号绑定和旧重试必须作废。 */
    fun resetOnLogout() {
        bindingGeneration++
        bindingInFlight = false
        val pending = ArrayList(bindingWaiters)
        bindingWaiters.clear()
        pending.forEach { it.resume(Unit) }
        bindingRevision = null
        bindingRid = null
        boundRevision = null
        boundRid = null
        retryCount = 0
    }

    suspend fun init() {
        if (bindLifecycle()) {
            // 根页面重建：上一个根页面上没跑完的取号 / 绑定不会再有结果，在途标记作废。
            ridInFlight = false
            ridWaiters.clear()
            bindingInFlight = false
            bindingWaiters.clear()
        }
        isNotified = isNotificationEnabled()
        DevLog.logger("PushService ---------$isNotified")
        notifyState()
        if (isNotified) {
            // 已经授权过的用户冷启动直接注册，登录后 autoBind 才能拿到 Registration ID。
            ensureSetup()
            AppHost.launch { bindRegistrationId() }
            AppHost.launch { consumeLaunchNotification() }
        }
    }

    /** @return 是否新绑定到了一个根页面 */
    private fun bindLifecycle(): Boolean {
        if (!lifecycleBinding.needsBind()) return false
        appResumed = true
        AppHost.pager.acquireModule<NotifyModule>(NotifyModule.MODULE_NAME).addNotify(Events.APP_LIFECYCLE) { data ->
            appResumed = data?.optBoolean("foreground", true) ?: true
        }
        return true
    }

    private fun pushChannel(): String {
        // iOS 固定 apple。
        if (AppEnv.isIOS) return "apple"
        // Android 渠道写在 Walle 签名块，空则回落 developer-default。
        val installChannel = Api.appChannel.trim()
        if (installChannel.isNotEmpty()) return installChannel
        return AppConstants.jpushChannel
    }

    /** 极光要求先 setAuth 再 setup（原生 setup 内部处理）。AppKey 为空时不初始化。 */
    private suspend fun ensureSetup(): Boolean {
        val appKey = AppConstants.jpushAppKey
        if (appKey.isEmpty()) {
            DevLog.logger("极光跳过初始化：AppKey 为空")
            return false
        }
        val module = push
        // 事件监听挂在根页面上；根页面重建后重新注册（SDK 本身只初始化一次）。
        if (listenerBinding.needsBind()) {
            module.addEventListener { ev ->
                when (ev.optString("event", "")) {
                    "notificationReceived" -> onForegroundNotification(ev)
                    "notificationOpened" -> {
                        DevLog.logger("极光点击通知")
                        if (markHandled(ev)) handleMessage(ev, isNotification = true)
                    }
                    "connected" -> {
                        // 通道连上时带回 Registration ID；可能触发多次，绑定按（代次, rid）去重。
                        val rid = ev.optString("rid", "")
                        if (rid.isNotEmpty() && rid != token) {
                            token = rid
                            notifyState()
                        }
                        AppHost.launch { bindRegistrationId() }
                    }
                }
            }
        }
        if (didSetup) return true
        // 先占位，避免并发调用重复初始化。
        didSetup = true
        // 前台不弹系统通知（原生 setup 内已设置），改走应用内横幅，生成页可以按路由丢掉。
        // production 只对 iOS 生效，Android / 鸿蒙忽略。
        module.setup(appKey, pushChannel(), production = !AppEnv.isDebug, debug = AppEnv.isDebug)
        return true
    }

    private fun onForegroundNotification(message: JSONObject) {
        if (!appResumed) return
        val title = message.optString("title", "")
        val body = message.optString("alert", "").ifEmpty { message.optString("body", "") }
        DevLog.logger("极光前台推送: $title=$body")
        if (title.isEmpty() && body.isEmpty()) return
        val route = AppNav.currentRoute()
        if (blackList.contains(route)) {
            DevLog.logger("极光前台推送: 当前在生成页面")
            return
        }
        // 参考工程：主框架停在「我的」Tab（下标 3）时不提示。
        if ((route.isEmpty() || route == Routes.root) && AppNav.mainTabIndex == 3) {
            DevLog.logger("极光前台推送: 当前在 mine 页面")
            return
        }
        val extras = jpushBusinessExtras(message)
        EventBus.post(
            Events.PUSH_FOREGROUND,
            JSONObject().put("title", title).put("body", body).put("extras", extras),
        )
    }

    /** 应用内横幅被点击：按推送携带的 extras 跳转（isNotification = false）。 */
    fun handleForegroundTap(extras: JSONObject) = handleExtras(extras, isNotification = false)

    /** 已处理过点击的通知 msgId：冷启动通知与 notificationOpened 事件可能是同一条，只跳转一次。 */
    private val handledMsgIds = ArrayList<String>()

    /** @return 这条通知的点击是否还没处理过（没有 msgId 的通知无法去重，一律放行） */
    private fun markHandled(message: JSONObject): Boolean {
        val msgId = message.optString("msgId", "")
        if (msgId.isEmpty()) return true
        if (handledMsgIds.contains(msgId)) return false
        handledMsgIds.add(msgId)
        if (handledMsgIds.size > 20) handledMsgIds.removeAt(0)
        return true
    }

    /** 冷启动时点击的通知：原生只返回一次（鸿蒙上最长约 1.5 秒才返回）。 */
    private suspend fun consumeLaunchNotification() {
        try {
            val message = push.getLaunchNotification()
            if (!message.optBoolean("ok", false) && !message.has("extras")) return
            val extras = jpushBusinessExtras(message)
            if (!extras.has("jumpAddress") || extras.opt("jumpAddress") == null) return
            if (!markHandled(message)) return
            setTimeout(AppHost.pager.pagerId, 1000) { handleExtras(extras, isNotification = true) }
        } catch (e: Throwable) {
            DevLog.logger("极光启动通知 error: $e")
        }
    }

    /** 权限已授予后取 Registration ID 并绑定。Registration ID 要等通道连上才有值。 */
    private suspend fun setupAfterPermissionGranted(): Boolean {
        return try {
            if (!ensureSetup()) return false
            isNotified = true
            notifyState()
            bindRegistrationId()
            !token.isNullOrEmpty()
        } catch (e: Throwable) {
            DevLog.logger("极光 setupAfterPermissionGranted error: $e")
            false
        }
    }

    private suspend fun bindRegistrationId() {
        val revision = AuthSession.revision
        val generation = bindingGeneration
        val rid = registrationId()
        if (rid == null || !ownsBinding(generation, revision)) return
        bindForSession(rid, generation, revision)
    }

    private suspend fun registrationId(): String? {
        val current = token
        if (!current.isNullOrEmpty()) return current
        if (ridInFlight) return suspendCoroutine { ridWaiters.add(it) }
        ridInFlight = true
        val rid = fetchRegistrationId()
        ridInFlight = false
        val pending = ArrayList(ridWaiters)
        ridWaiters.clear()
        pending.forEach { it.resume(rid) }
        return rid
    }

    private suspend fun fetchRegistrationId(): String? {
        if (!didSetup) return null
        try {
            for (i in 0 until 8) {
                val rid = push.getRegistrationId()
                if (rid.isNotEmpty()) {
                    token = rid
                    notifyState()
                    return rid
                }
                if (i < 7) delayMs(1000)
            }
            DevLog.logger("极光 Registration ID 为空")
        } catch (e: Throwable) {
            DevLog.logger("极光获取 Registration ID 失败")
        }
        return null
    }

    private fun ownsBinding(generation: Int, revision: Int): Boolean =
        generation == bindingGeneration && AuthSession.isCurrent(revision) && AuthSession.accessToken.isNotEmpty()

    /** 用已拿到的 Registration ID 绑定当前账号。 */
    suspend fun bindUserFcmToken() {
        val rid = token
        if (rid.isNullOrEmpty()) return
        val revision = AuthSession.revision
        val generation = bindingGeneration
        if (!ownsBinding(generation, revision)) return
        bindForSession(rid, generation, revision)
    }

    private suspend fun bindForSession(rid: String, generation: Int, revision: Int) {
        // RID 绑到账号；同账号刷新 token 不需要重复绑定，也不能取消正在进行的绑定。
        if (boundRevision == revision && boundRid == rid) return
        if (bindingRevision == revision && bindingRid == rid && bindingInFlight) {
            suspendCoroutine<Unit> { bindingWaiters.add(it) }
            return
        }
        bindingRevision = revision
        bindingRid = rid
        bindingInFlight = true
        retryBinding(rid, generation, revision)
        // 登出会重置在途标记；只有仍属于这一轮时才收尾。
        if (generation == bindingGeneration && bindingRevision == revision && bindingRid == rid) {
            bindingInFlight = false
            val pending = ArrayList(bindingWaiters)
            bindingWaiters.clear()
            pending.forEach { it.resume(Unit) }
        }
    }

    private suspend fun retryBinding(rid: String, generation: Int, revision: Int) {
        for (attempt in 0..3) {
            if (!ownsBinding(generation, revision)) return
            retryCount = attempt
            // 请求及重试由 HTTP 层注入当下凭证，401 刷新仍属于当前 revision。
            val result = try {
                AppConfigApi.bindUserJPushToken(rid).getOrNull() ?: "error"
            } catch (e: Throwable) {
                DevLog.logger("极光 Registration ID 绑定异常")
                "error"
            }
            if (!ownsBinding(generation, revision)) return
            if (result == "success") {
                boundRevision = revision
                boundRid = rid
                retryCount = 0
                DevLog.logger("极光 Registration ID 绑定成功")
                return
            }
            if (attempt < 3) delayMs(1000)
        }
        DevLog.logger("极光 Registration ID 绑定失败，已达重试上限")
    }

    suspend fun isNotificationEnabled(): Boolean {
        val permission: SDPermissionModule = AppHost.module(SDPermissionModule.MODULE_NAME)
        return permission.status(PermissionUtil.NOTIFICATION) == SDPermissionModule.GRANTED
    }

    private fun handleMessage(message: JSONObject, isNotification: Boolean) {
        handleExtras(jpushBusinessExtras(message), isNotification)
    }

    /** isNotification 是否是通知栏点击 true: 通知栏点击 false: 应用内点击 */
    private fun handleExtras(extras: JSONObject, isNotification: Boolean) {
        try {
            // jumpParams 不是合法 JSON 时参考工程解析抛错、整条不处理。
            val model = MessageModel.fromJsonOrNull(extras) ?: return
            UmengUtils.logEvent(
                if (isNotification) "notification_push_click" else "app_push_click",
                mapOf("pushType" to model.action.key),
            )
            MessageActionHandle.handle(model, isNotification)
        } catch (e: Throwable) {
            DevLog.logger("极光通知跳转 error: $e")
        }
    }

    /** 每次用户资料加载成功 2 秒后调用：权限已授予才绑定。 */
    suspend fun autoBindUserFcmToken() {
        if (!isNotified || !ensureSetup()) return
        bindRegistrationId()
    }

    /**
     * 申请通知权限。返回 granted / denied / blocked（永久拒绝）。
     * 只等系统授权结果；Registration ID 可能要等通道连上，绑定在后台继续。
     */
    suspend fun requestNotifyPermission(rationaleAlreadyShown: Boolean = false, pager: BasePager? = null): String {
        if (isEnablingNotify) return DENIED
        isEnablingNotify = true
        var permissionResult = DENIED
        try {
            val status = PermissionUtil.requestPermissionStatus(
                PermissionUtil.NOTIFICATION,
                purpose = "发送音乐、视频或舞蹈生成完成及任务状态更新通知",
                rationaleAlreadyShown = rationaleAlreadyShown,
                pager = pager,
            )
            if (PermissionUtil.isUsable(status)) {
                permissionResult = GRANTED
            } else if (status == SDPermissionModule.PERMANENTLY_DENIED) {
                permissionResult = BLOCKED
            }
        } catch (e: Throwable) {
            DevLog.logger("极光通知权限 error: $e")
        } finally {
            isEnablingNotify = false
        }
        if (permissionResult == GRANTED) {
            AppHost.launch { setupAfterPermissionGranted() }
        }
        return permissionResult
    }

    /**
     * 展示通知授权弹窗；返回授权结果：granted / denied / blocked。
     * 已授权直接绑定并返回 granted；弹窗一生只弹一次，弹过之后静默返回 denied。
     *
     * @param type 业务类型：video / music，用于弹窗配图与文案
     * @param popupViewName 非空时，弹窗实际展示时上报 popup_view
     */
    suspend fun showNotifiedDialog(type: String, popupViewName: String? = null, pager: BasePager? = null): String {
        val isShowNotified = Sp.getBool(StorageKey.isShowNotified, false)
        if (isNotified) {
            autoBindUserFcmToken()
            return GRANTED
        }
        if (isShowNotified) {
            DevLog.logger("PushService isShowNotified, skip notification dialog")
            return DENIED
        }
        val host = pager ?: currentPager() ?: return DENIED
        Sp.setBool(StorageKey.isShowNotified, true)
        var image = AssetsImage.notifiedMusic
        var message = "notification_music_desc".tr
        if (type == "video") {
            image = AssetsImage.notifiedVideo
            message = "notification_video_desc".tr
        }
        if (popupViewName != null) UmengUtils.logEventPopupView(popupViewName)

        return suspendCoroutine { cont ->
            var resumed = false
            val finish: (String) -> Unit = { result ->
                if (!resumed) {
                    resumed = true
                    cont.resume(result)
                }
            }
            ServiceDialogs.showNotified(
                host, image, message,
                onAgree = { handle ->
                    host.launch {
                        val result = requestNotifyPermission(rationaleAlreadyShown = true, pager = host)
                        // 连点时在途的那次负责关窗，这里的 denied 不关。
                        if (handle.isShowing && !isEnablingNotify) {
                            handle.dismiss()
                            finish(result)
                        }
                    }
                },
                onDeny = { finish(DENIED) },
            )
        }
    }

    private fun notifyState() {
        EventBus.post(Events.PUSH_STATE, JSONObject().put("isNotified", isNotified).put("token", token ?: ""))
    }

    // ------------------------------------------------------------------ extras 解析

    /** 极光回调里的业务字段。Android 包在 `cn.jpush.android.EXTRA`，iOS 直接放在 extras。 */
    fun jpushBusinessExtras(message: JSONObject): JSONObject {
        val extras = stringKeyMap(message.opt("extras"))
        val nested = stringKeyMap(extras.opt("cn.jpush.android.EXTRA"))
        val business = if (nested.length() > 0) nested else extras
        if (business.has("jumpAddress") || business.has("jumpParams")) return business
        val topAddress = message.opt("jumpAddress")
        if (topAddress != null && topAddress.toString() != "null") {
            val out = JSONObject().put("jumpAddress", topAddress)
            val topParams = message.opt("jumpParams")
            if (topParams != null && topParams.toString() != "null") out.put("jumpParams", topParams)
            return out
        }
        return business
    }

    private fun stringKeyMap(raw: Any?): JSONObject {
        if (raw is JSONObject) return raw
        if (raw is String && raw.isNotEmpty()) {
            try {
                if (raw.trim().startsWith("{")) return JSONObject(raw)
            } catch (e: Throwable) {
                // 不是 JSON 对象
            }
        }
        return JSONObject()
    }
}

/**
 * 通知消息跳转（对应 Flutter 侧 MessageActionHandle）。
 * 所有跳转先回到主框架卸掉旧页面，再延时 550ms 打开目标页。
 */
object MessageActionHandle {
    private fun idOf(raw: Any?): Long? = when (raw) {
        is Number -> raw.toLong()
        null -> null
        else -> raw.toString().trim().toLongOrNull()
    }

    fun handle(model: MessageModel, isNotification: Boolean = false) {
        DevLog.logger("handle: ${model.action}==")
        val params = model.params
        when (model.action) {
            MessageNoticeAction.openMusicPage -> {
                val id = idOf(params?.opt("id")) ?: return
                // 先回到首页卸掉栈里的旧详情，避免停在上一首。
                AppNav.backToRootThenPush(
                    Routes.musicDetail,
                    JSONObject().put("id", id).put("original", "push"),
                    after = { AppHost.launch { LibraryTabRefreshService.refreshSong() } },
                )
            }
            MessageNoticeAction.openMvPage -> {
                val rawId = params?.opt("id")
                if (rawId == null || rawId.toString() == "null") return
                // 推送进播放页时消费同源试用任务，避免返回主页后悬浮球残留
                val mvId = idOf(rawId)
                if (mvId != null) TryFreeTaskService.clearIfMatching(mvId)
                // 试看成片只带 id，播放页自己拉详情；否则仍进普通详情播放页。
                if (model.isTrial && mvId != null) {
                    AppNav.backToRootThenPush(Routes.mvPreviewPlay, JSONObject().put("id", mvId))
                } else {
                    AppNav.backToRootThenPush(
                        Routes.mvListDetail,
                        JSONObject().put("id", mvId ?: rawId).put("type", "push").put("dataType", 0),
                    )
                }
            }
            MessageNoticeAction.openThinkLineV2 -> {
                val rawId = params?.opt("id")
                if (rawId == null || rawId.toString() == "null") return
                // 推送 payload 的 id 可能是数字或字符串，统一成数字
                AppNav.backToRootThenPush(
                    Routes.thinkLineMv,
                    JSONObject().put("id", idOf(rawId) ?: rawId).put("type", "push"),
                )
            }
            MessageNoticeAction.openPushMessage -> {
                val rawId = params?.opt("landingPageId")
                if (rawId == null || rawId.toString() == "null") return
                AppNav.backToRootThenPush(Routes.pushMessage, JSONObject().put("id", idOf(rawId) ?: rawId))
            }
            MessageNoticeAction.createError -> {
                // 只在主框架上才切到作品库 Tab
                if (AppNav.isOnRoot()) EventBus.post(Events.SWITCH_TAB, JSONObject().put("tab", 4))
            }
            else -> {}
        }
    }
}
