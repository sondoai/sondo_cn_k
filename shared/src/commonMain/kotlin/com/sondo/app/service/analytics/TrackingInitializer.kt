package com.sondo.app.service.analytics

import com.sondo.app.base.AppEnv
import com.sondo.app.base.AppHost
import com.sondo.app.base.Events
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.HostBinding
import com.sondo.app.nativeapi.SDPermissionModule
import com.sondo.app.service.PrivacyConsentService
import com.tencent.kuikly.core.module.NotifyModule
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** ATT 请求结果。status 为 [SDPermissionModule] 的状态串。 */
class AttResult(val isHandle: Boolean = false, val status: String = SDPermissionModule.NOT_DETERMINED)

/**
 * iOS 跟踪透明度。系统未决时等到 App 回到前台再弹，避免和启动期其他系统框抢焦点。
 * 非 iOS 平台视为已处理。
 */
object AttRequest {
    private var inFlight = false
    private val waiters = ArrayList<Continuation<AttResult>>()
    /** App 是否在前台（由根页面广播的 [Events.APP_LIFECYCLE] 维护）。 */
    private var resumed = true
    private val lifecycleBinding = HostBinding()
    private val resumeWaiters = ArrayList<() -> Unit>()

    suspend fun request(): AttResult {
        if (!AppEnv.isIOS) return AttResult(isHandle = true, status = SDPermissionModule.GRANTED)
        if (inFlight) return suspendCoroutine { waiters.add(it) }
        inFlight = true
        val result = doRequest()
        inFlight = false
        val pending = ArrayList(waiters)
        waiters.clear()
        pending.forEach { it.resume(result) }
        return result
    }

    private fun bindLifecycle() {
        if (!lifecycleBinding.needsBind()) return
        AppHost.pager.acquireModule<NotifyModule>(NotifyModule.MODULE_NAME).addNotify(Events.APP_LIFECYCLE) { data ->
            resumed = data?.optBoolean("foreground", true) ?: true
            if (resumed) {
                val pending = ArrayList(resumeWaiters)
                resumeWaiters.clear()
                pending.forEach { it() }
            }
        }
    }

    private suspend fun doRequest(): AttResult {
        return try {
            bindLifecycle()
            val permission: SDPermissionModule = AppHost.module(SDPermissionModule.MODULE_NAME)
            var status = permission.status("tracking")
            if (status != SDPermissionModule.NOT_DETERMINED) return AttResult(isHandle = true, status = status)
            var requireNextResume = false
            while (status == SDPermissionModule.NOT_DETERMINED) {
                waitUntilResumed(requireNextResume)
                // 等待期间用户可能已在设置里选过，先读状态再决定要不要弹。
                status = permission.status("tracking")
                if (status != SDPermissionModule.NOT_DETERMINED) break
                status = permission.request("tracking")
                // 系统可能因应用状态变化或已有权限弹窗而不展示 ATT。
                // 当前 active 周期内不再连弹，等下一次回到前台。
                requireNextResume = status == SDPermissionModule.NOT_DETERMINED
            }
            AttResult(isHandle = status != SDPermissionModule.NOT_DETERMINED, status = status)
        } catch (e: Throwable) {
            DevLog.logger("Error requesting ATT permission: $e")
            AttResult(isHandle = false)
        }
    }

    private suspend fun waitUntilResumed(requireNextResume: Boolean) {
        if (!requireNextResume && resumed) return
        suspendCoroutine<Unit> { cont -> resumeWaiters.add { cont.resume(Unit) } }
    }
}

/**
 * 根页面与引导页共用的跟踪授权入口。未弹出或请求失败时不记完成，下次还能再试。
 * 必须在隐私同意、业务首帧出现之后调用。
 */
object TrackingInitializer {
    private var isInitialized = false
    private var inFlight = false

    fun initialize() {
        if (isInitialized || inFlight) return
        if (!PrivacyConsentService.hasConsent()) return
        inFlight = true
        AppHost.launch {
            isInitialized = try {
                AttRequest.request().isHandle
            } catch (e: Throwable) {
                DevLog.logger("tracking initialization error: $e")
                false
            }
            inFlight = false
        }
    }
}
