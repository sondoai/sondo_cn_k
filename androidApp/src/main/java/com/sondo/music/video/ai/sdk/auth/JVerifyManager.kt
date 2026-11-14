package com.sondo.music.video.ai.sdk.auth

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import cn.jiguang.api.utils.JCollectionAuth
import cn.jiguang.verifysdk.api.AuthPageEventListener
import cn.jiguang.verifysdk.api.JVerificationInterface
import cn.jiguang.verifysdk.api.LoginSettings
import com.sondo.music.video.ai.sdk.OnceCallback
import com.sondo.music.video.ai.sdk.SdkSupport

/**
 * 极光认证（一键登录）。
 *
 * 合规：`jverifySetup` 之前不碰 SDK；setup 内部先 `JCollectionAuth.setAuth(true)` 再 init
 * （Kuikly 侧只在用户点了登录之后才调 setup）。
 *
 * Android 的 AppKey 只认清单里的 `JPUSH_APPKEY`（build.gradle.kts 的 manifestPlaceholders），
 * 运行时传进来的 appKey 只用来核对，不一致时打日志。
 *
 * 除 SDK 回调外，所有状态都只在主线程读写。
 */
internal object JVerifyManager {

    private const val TAG = "SDAuth"

    private const val CODE_SETUP_OK = 8000
    private const val CODE_PRELOGIN_OK = 7000
    private const val CODE_TOKEN = 6000
    private const val CODE_CANCEL = 6002
    private const val CODE_UNSUPPORTED = 2016

    /** 授权页事件：1 关闭，2 打开，6 勾选协议，7 取消勾选。 */
    private const val PAGE_CLOSED = 1
    private const val PAGE_SHOWN = 2
    private const val PAGE_CHECKED = 6
    private const val PAGE_UNCHECKED = 7

    /** 部分机型 init 没有回调，等这么久还没回就当成功，交给后面的 checkVerifyEnable / 授权页去判断。 */
    private const val SETUP_CALLBACK_WAIT_MS = 15_000L

    /** 关页动画没走完就再拉，SDK 会把旧页的取消算到新的这一次上，表现为点了没反应。最多等这么久。 */
    private const val PREVIOUS_PAGE_WAIT_MS = 1_000L

    /** 授权页已关但结果回调没到时，等这么久按用户关闭处理，登录入口不能一直挂着。 */
    private const val CLOSED_GRACE_MS = 300L

    // ---- setup ----
    /** 进行中的那次 init；失败后允许重试，旧一次的迟到回调和兜底计时靠它识别并丢弃。 */
    private var setupAttempt: Any? = null
    private var ready = false
    private val setupWaiters = ArrayList<OnceCallback>()

    // ---- 授权页 ----
    private var session: Session? = null
    private var pageOpen = false
    private val pageCloseWaiters = ArrayList<Runnable>()
    private var sheetDismiss: JVerifySheetDismiss? = null

    private class Session(val callback: OnceCallback, val uncheckedToast: String) {
        var finished = false
        var shown = false
        var agreed = false
        var watchdog: Runnable? = null
        var closedGrace: Runnable? = null
    }

    fun setup(context: Context, appKey: String, debug: Boolean, timeoutMs: Int, callback: OnceCallback) {
        val app = context.applicationContext
        if (ready) {
            callback.resolve(SdkSupport.ok("code" to CODE_SETUP_OK))
            return
        }
        val manifestKey = manifestAppKey(app)
        if (manifestKey.isEmpty()) {
            callback.resolve(SdkSupport.fail("JPUSH_APPKEY missing in manifest", "code" to -1))
            return
        }
        if (appKey.isNotEmpty() && appKey != manifestKey) {
            Log.w(TAG, "jverifySetup: appKey differs from manifest JPUSH_APPKEY, manifest value is used")
        }
        setupWaiters.add(callback)
        if (setupAttempt != null) return
        val attempt = Any()
        setupAttempt = attempt

        fun finish(code: Int, message: String?, timedOut: Boolean) {
            SdkSupport.runOnMain {
                if (setupAttempt !== attempt) return@runOnMain
                setupAttempt = null
                val success = code == CODE_SETUP_OK || timedOut
                ready = success
                val result = if (success) {
                    SdkSupport.ok("code" to code, "timeout" to if (timedOut) true else null)
                } else {
                    Log.w(TAG, "jverifySetup failed code=$code msg=$message")
                    SdkSupport.fail(message ?: "setup failed", "code" to code)
                }
                val waiters = ArrayList(setupWaiters)
                setupWaiters.clear()
                waiters.forEach { it.resolve(result) }
            }
        }

        try {
            JVerificationInterface.setDebugMode(debug)
            // 合规：点一键登录才开启采集。JCore 5.6 把它标了 @Deprecated，但没有给替代接口，实现也还在。
            @Suppress("DEPRECATION")
            JCollectionAuth.setAuth(app, true)
            val timeout = if (timeoutMs in 1..30000) timeoutMs else 10000
            JVerificationInterface.init(app, timeout) { code, message -> finish(code, message, false) }
        } catch (error: Throwable) {
            finish(-1, error.message ?: error.javaClass.simpleName, false)
            return
        }
        SdkSupport.mainHandler.postDelayed({
            if (setupAttempt === attempt) {
                val initialized = try {
                    JVerificationInterface.isInitSuccess()
                } catch (_: Throwable) {
                    false
                }
                Log.w(TAG, "jverifySetup: no init callback, continue (initSuccess=$initialized)")
                finish(if (initialized) CODE_SETUP_OK else -1, null, true)
            }
        }, SETUP_CALLBACK_WAIT_MS)
    }

    fun checkEnable(context: Context, callback: OnceCallback) {
        if (!ready) {
            callback.resolve(SdkSupport.fail("not_setup", "enable" to false))
            return
        }
        val enable = try {
            JVerificationInterface.checkVerifyEnable(context.applicationContext)
        } catch (_: Throwable) {
            false
        }
        callback.resolve(SdkSupport.ok("enable" to enable))
    }

    fun preLogin(context: Context, timeoutMs: Int, callback: OnceCallback) {
        if (!ready) {
            callback.resolve(SdkSupport.fail("not_setup", "code" to -1))
            return
        }
        val timeout = if (timeoutMs in 1..30000) timeoutMs else 5000
        try {
            JVerificationInterface.preLogin(context.applicationContext, timeout) { code, content, _ ->
                // content 只是运营商返回的说明文字，不含号码。
                if (code == CODE_PRELOGIN_OK) {
                    callback.resolve(SdkSupport.ok("code" to code))
                } else {
                    callback.resolve(SdkSupport.fail(content ?: "prelogin failed", "code" to code))
                }
            }
        } catch (error: Throwable) {
            callback.resolve(SdkSupport.fail(error.message ?: error.javaClass.simpleName, "code" to -1))
            return
        }
        // SDK 自己会按 timeout 回调；这里只是兜底，保证调用方的 await 一定能返回。
        SdkSupport.mainHandler.postDelayed({
            callback.resolve(SdkSupport.fail("timeout", "code" to -1))
        }, timeout + 5_000L)
    }

    /**
     * 拉起授权页，只回一次：
     * `{ok, action:"token"|"wechat"|"sms"|"cancel"|"unsupported"|"error", token?, operator?, code?, error?, shown}`。
     * token / wechat / sms / cancel 是用户自己的选择，ok 为 true；unsupported / error 为 false。
     * `shown` 表示这次授权页是否真的出现过（出现过才失败，调用方可以重拉同一个弹窗）。
     */
    fun loginAuth(context: Context, hostActivity: Activity?, params: JVerifySheetParams, callback: OnceCallback) {
        val app = context.applicationContext
        if (!ready) {
            callback.resolve(actionResult(false, "error", error = "not_setup") + ("shown" to false))
            return
        }
        if (session != null) {
            callback.resolve(actionResult(false, "error", error = "busy") + ("shown" to false))
            return
        }
        val enable = try {
            JVerificationInterface.checkVerifyEnable(app)
        } catch (_: Throwable) {
            false
        }
        if (!enable) {
            callback.resolve(actionResult(false, "unsupported", code = CODE_UNSUPPORTED) + ("shown" to false))
            return
        }

        val current = Session(callback, params.privacyUncheckedToast)
        session = current
        // 授权页出现前的兜底计时：SDK 取号有自己的超时，正常情况下它先回调。弹窗出现后不再计时，
        // 停多久都留在屏幕上，由用户登录、改走其他方式或关闭来结束。
        val watchdog = Runnable {
            if (current.finished) return@Runnable
            Log.w(TAG, "jverifyLoginAuth: auth page did not show in time")
            // 先结束这次尝试，关页触发的取消或迟到的 6000 都不能再改变结果。
            finish(current, actionResult(false, "error", error = "timeout"))
            dismissPage()
        }
        current.watchdog = watchdog
        SdkSupport.mainHandler.postDelayed(watchdog, maxOf(30_000L, params.timeoutMs + 20_000L))

        whenPreviousPageClosed {
            if (current.finished) return@whenPreviousPageClosed
            try {
                launch(app, hostActivity, params, current)
            } catch (error: Throwable) {
                Log.w(TAG, "jverifyLoginAuth launch failed: ${error.javaClass.simpleName}")
                finish(current, actionResult(false, "error", error = error.message ?: "launch failed"))
            }
        }
    }

    private fun launch(app: Context, hostActivity: Activity?, params: JVerifySheetParams, current: Session) {
        val actions = object : JVerifySheetUi.Actions {
            override fun onOtherLoginClick() = leaveIfAgreed(app, current, "sms")
            override fun onWechatLoginClick() = leaveIfAgreed(app, current, "wechat")
        }
        JVerificationInterface.setCustomUIWithConfig(JVerifySheetUi.build(app, hostActivity, params, actions))

        // 点弹窗外空白处、按住顶部下滑：按取消处理。
        val dismiss = sheetDismiss ?: JVerifySheetDismiss {
            SdkSupport.runOnMain { session?.let { leave(it, "cancel") } }
        }.also { sheetDismiss = it }
        dismiss.setEnabled(app, true)

        val settings = LoginSettings()
        settings.isAutoFinish = true
        settings.timeout = params.timeoutMs
        settings.authPageEventListener = object : AuthPageEventListener() {
            override fun onEvent(cmd: Int, msg: String?) {
                SdkSupport.runOnMain { onPageEvent(current, cmd) }
            }
        }
        JVerificationInterface.loginAuth(app, settings) { code, content, operator, _ ->
            SdkSupport.runOnMain {
                if (current.finished) return@runOnMain
                val message = content?.trim().orEmpty()
                // message 在 6000 时是 loginToken，日志里只记长度。
                Log.d(TAG, "jverifyLoginAuth code=$code operator=$operator tokenLen=${if (code == CODE_TOKEN) message.length else 0}")
                finish(current, authResult(code, message, operator))
            }
        }
    }

    private fun authResult(code: Int, message: String, operator: String?): Map<String, Any> = when {
        // 6000 但没有 token 按失败处理，避免拿空串去换票。
        code == CODE_TOKEN && message.isNotEmpty() ->
            actionResult(true, "token", code = code, token = message, operator = operator)
        code == CODE_TOKEN -> actionResult(false, "error", code = code, error = "empty token")
        code == CODE_CANCEL -> actionResult(true, "cancel", code = code)
        code == CODE_UNSUPPORTED -> actionResult(false, "unsupported", code = code, error = message)
        else -> actionResult(false, "error", code = code, error = message.ifEmpty { "login auth failed" })
    }

    private fun onPageEvent(current: Session, cmd: Int) {
        when (cmd) {
            PAGE_SHOWN -> {
                pageOpen = true
                current.shown = true
                current.watchdog?.let(SdkSupport.mainHandler::removeCallbacks)
                current.watchdog = null
            }
            PAGE_CHECKED -> current.agreed = true
            PAGE_UNCHECKED -> current.agreed = false
            PAGE_CLOSED -> {
                pageOpen = false
                val waiters = ArrayList(pageCloseWaiters)
                pageCloseWaiters.clear()
                waiters.forEach { it.run() }
                // 结果回调通常紧跟着到；等不到就按用户关闭处理。
                if (current.shown && !current.finished && current.closedGrace == null) {
                    val grace = Runnable { finish(current, actionResult(true, "cancel")) }
                    current.closedGrace = grace
                    SdkSupport.mainHandler.postDelayed(grace, CLOSED_GRACE_MS)
                }
            }
        }
    }

    /** 微信、短信和一键登录一样要先勾选协议：没勾选就提示，弹窗留在原地。 */
    private fun leaveIfAgreed(app: Context, current: Session, action: String) {
        if (current.finished) return
        if (!current.agreed) {
            if (current.uncheckedToast.isNotEmpty()) {
                // 授权页盖住了应用界面，提示只能由原生弹。
                Toast.makeText(app, current.uncheckedToast, Toast.LENGTH_SHORT).show()
            }
            return
        }
        leave(current, action)
    }

    /** 先定结果再关弹窗：关页带出的 6002 不会再改结果，登录入口也立刻放开，不用等 SDK 的回调。 */
    private fun leave(current: Session, action: String) {
        if (current.finished) return
        finish(current, actionResult(true, action))
        dismissPage()
    }

    private fun finish(current: Session, result: Map<String, Any>) {
        if (current.finished) return
        current.finished = true
        current.watchdog?.let(SdkSupport.mainHandler::removeCallbacks)
        current.closedGrace?.let(SdkSupport.mainHandler::removeCallbacks)
        if (session === current) session = null
        current.callback.resolve(result + ("shown" to current.shown))
    }

    /** Kuikly 侧主动关闭（超时、离开登录流程）。进行中的这次按取消结束。 */
    fun dismiss() {
        val current = session
        if (current != null) {
            leave(current, "cancel")
        } else if (ready) {
            dismissPage()
        }
    }

    private fun dismissPage() {
        try {
            JVerificationInterface.dismissLoginAuthActivity(true) { _, _ -> }
        } catch (_: Throwable) {
        }
    }

    /** 上一个授权页还没报“已关闭”就先等一下，最多 [PREVIOUS_PAGE_WAIT_MS]，原生没报关闭也不会卡住。 */
    private fun whenPreviousPageClosed(block: () -> Unit) {
        if (!pageOpen) {
            block()
            return
        }
        var ran = false
        val once = Runnable {
            if (!ran) {
                ran = true
                block()
            }
        }
        pageCloseWaiters.add(once)
        SdkSupport.mainHandler.postDelayed({
            // 还在等才算超时；已经等到关闭的话，这时 pageOpen 可能已经属于新的授权页了。
            if (pageCloseWaiters.remove(once)) {
                pageOpen = false
                once.run()
            }
        }, PREVIOUS_PAGE_WAIT_MS)
    }

    private fun actionResult(
        ok: Boolean,
        action: String,
        code: Int? = null,
        token: String? = null,
        operator: String? = null,
        error: String? = null,
    ): Map<String, Any> {
        val map = LinkedHashMap<String, Any>()
        map["ok"] = ok
        map["action"] = action
        if (code != null) map["code"] = code
        if (token != null) map["token"] = token
        if (!operator.isNullOrEmpty()) map["operator"] = operator
        if (!error.isNullOrEmpty()) map["error"] = error
        return map
    }

    private fun manifestAppKey(context: Context): String {
        return try {
            val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            // 纯数字的值会被清单解析成数字，getString 取不到，所以用不分类型的 get。
            @Suppress("DEPRECATION")
            val value = info.metaData?.get("JPUSH_APPKEY")
            value?.toString()?.trim().orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }
}
