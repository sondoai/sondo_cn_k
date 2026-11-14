package com.sondo.music.video.ai.sdk.push

import android.content.Context
import android.os.SystemClock
import android.util.Log
import cn.jiguang.api.utils.JCollectionAuth
import cn.jpush.android.api.JPushInterface
import cn.jpush.android.api.NotificationMessage
import cn.jpush.android.data.JPushConfig
import com.sondo.music.video.ai.sdk.AppForeground
import com.sondo.music.video.ai.sdk.SdkEventHub
import com.sondo.music.video.ai.sdk.SdkSupport
import org.json.JSONArray
import org.json.JSONObject

/**
 * 极光推送。
 *
 * 合规：[setup] 之前不调用任何极光接口。极光自带的 InitProvider 会随进程启动，但它只记启动时刻、
 * 注册页面生命周期监听，真正的采集和长连接都要等 `JPushInterface.init`，所以不需要也不能把它摘掉
 * （推送进程靠它把消息转给主进程）。[setup] 里先 `JCollectionAuth.setAuth(true)` 再 init。
 *
 * 事件（发给所有页面）：connected / notificationReceived / notificationOpened。
 * 冷启动时点的通知没有页面可发，先存着，等 Kuikly 侧 `getLaunchNotification` 来取，取一次就清空。
 */
internal object JPushManager {

    private const val TAG = "SDPush"

    /** 冷启动通知最多保留这么久：正常几秒内就会被取走，超时的不再触发跳转。 */
    private const val LAUNCH_NOTIFICATION_TTL_MS = 2 * 60 * 1000L

    /** NotificationMessage.notificationType：0 远程推送，1 本地通知。 */
    private const val NOTIFICATION_TYPE_LOCAL = 1

    val events = SdkEventHub()

    @Volatile
    private var setupDone = false

    private val launchLock = Any()
    private var launchNotification: Map<String, Any>? = null
    private var launchNotificationAt = 0L

    val isSetup: Boolean get() = setupDone

    /** 先 setAuth 再 init，重复调用不会重新初始化。`production` 只对 iOS 的 APNs 环境有意义。 */
    @Synchronized
    fun setup(context: Context, appKey: String, channel: String, debug: Boolean): Map<String, Any> {
        val app = context.applicationContext
        if (setupDone) return SdkSupport.ok()
        return try {
            // JCore 5.6 把 setAuth 标了 @Deprecated，但没有给替代接口，实现也还在。
            @Suppress("DEPRECATION")
            JCollectionAuth.setAuth(app, true)
            JPushInterface.setDebugMode(debug)
            if (appKey.isNotEmpty()) {
                val config = JPushConfig()
                config.setjAppKey(appKey)
                JPushInterface.init(app, config)
            } else {
                // 没传就用清单里的 JPUSH_APPKEY。
                JPushInterface.init(app)
            }
            // 通知到达 / 点击走 JPushMessageReceiver 的回调，而不是旧的广播。
            JPushInterface.setNotificationCallBackEnable(app, true)
            if (channel.isNotEmpty()) JPushInterface.setChannel(app, channel)
            setupDone = true
            SdkSupport.ok()
        } catch (error: Throwable) {
            Log.w(TAG, "setup failed: ${error.javaClass.simpleName}")
            SdkSupport.fail(error.message ?: error.javaClass.simpleName)
        }
    }

    /** 通道还没连上时是空串，Kuikly 侧自己轮询或等 connected 事件。 */
    fun registrationId(context: Context): String {
        if (!setupDone) return ""
        return try {
            JPushInterface.getRegistrationID(context.applicationContext).orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    fun setBadge(context: Context, count: Int) {
        if (!setupDone) return
        try {
            JPushInterface.setBadgeNumber(context.applicationContext, count.coerceAtLeast(0))
        } catch (_: Throwable) {
        }
    }

    /** 取走冷启动时点击的通知；没有（或已过期）返回 null。 */
    fun takeLaunchNotification(): Map<String, Any>? {
        synchronized(launchLock) {
            val pending = launchNotification ?: return null
            launchNotification = null
            val age = SystemClock.elapsedRealtime() - launchNotificationAt
            return if (age <= LAUNCH_NOTIFICATION_TTL_MS) pending else null
        }
    }

    // ---- 以下由 SondoJPushReceiver 回调，线程不固定 ----

    fun onConnectionChanged(context: Context, connected: Boolean) {
        if (connected) events.emit("connected", "rid" to registrationId(context))
    }

    /** 首次拿到 Registration ID。和连上一样通知 Kuikly 侧去绑定。 */
    fun onRegistered(rid: String?) {
        events.emit("connected", "rid" to rid.orEmpty())
    }

    /**
     * 应用在前台时不弹系统通知，改发 notificationReceived，由 Kuikly 侧弹应用内提示。
     *
     * 注意极光 Android SDK 只在推送带了 `display_foreground` 时才来问这个方法：
     * 没带 → 一律进通知栏（同时回调 arrived）；带 "0" → SDK 自己判断前台并不展示；带其他值 → 以这里的返回为准。
     * 所以“前台不弹横幅”要后端下发 `display_foreground`，客户端单方面做不到。
     */
    fun shouldShowNotification(context: Context): Boolean {
        AppForeground.ensureInstalled(context)
        return !(setupDone && AppForeground.isForeground)
    }

    /** 通知进了通知栏。应用在前台时 Kuikly 侧照样可以弹应用内提示。 */
    fun onNotificationArrived(message: NotificationMessage) {
        events.emit("notificationReceived", *payload(message))
    }

    /** 通知没有展示。只有因为应用在前台而没展示的才转成应用内提示。 */
    fun onNotificationUnShow(message: NotificationMessage) {
        if (AppForeground.isForeground) {
            events.emit("notificationReceived", *payload(message))
        }
    }

    /**
     * 点了通知。有页面活着就发 notificationOpened；一个页面都没有说明是冷启动（或页面都被回收了），
     * 存成启动通知。
     *
     * 把应用带回前台这件事：远程推送由极光自己的跳转逻辑完成（没配自定义 intent 时它会拉起启动页），
     * 这里再拉一次反而可能在旧系统上多开一个根页面；只有本地通知（notificationType = 1）极光不管，才由这里拉。
     */
    fun onNotificationOpened(context: Context, message: NotificationMessage) {
        AppForeground.ensureInstalled(context)
        val pairs = payload(message)
        if (events.hasListeners) {
            events.emit("notificationOpened", *pairs)
        } else {
            val map = LinkedHashMap<String, Any>()
            for ((key, value) in pairs) {
                if (value != null) map[key] = value
            }
            synchronized(launchLock) {
                launchNotification = map
                launchNotificationAt = SystemClock.elapsedRealtime()
            }
        }
        if (message.notificationType == NOTIFICATION_TYPE_LOCAL && !AppForeground.isForeground) {
            AppForeground.bringAppToFront(context)
        }
    }

    private fun payload(message: NotificationMessage): Array<Pair<String, Any?>> = arrayOf(
        "title" to message.notificationTitle.orEmpty(),
        "alert" to message.notificationContent.orEmpty(),
        "extras" to flattenExtras(message.notificationExtras),
        "msgId" to message.msgId.orEmpty(),
    )

    /**
     * 业务字段在极光里是 `cn.jpush.android.EXTRA` 这一个 JSON 字符串，这里直接摊平成 extras 本身。
     * 值只保留 String / Number / Boolean，嵌套的对象和数组保持 JSON 字符串
     * （后端的 jumpParams 本来就是 JSON 字符串，Kuikly 侧统一再解一次）。
     */
    private fun flattenExtras(raw: String?): Map<String, Any> {
        val result = LinkedHashMap<String, Any>()
        if (raw.isNullOrBlank()) return result
        try {
            val json = JSONObject(raw)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                when (val value = json.opt(key)) {
                    null, JSONObject.NULL -> Unit
                    is String, is Int, is Long, is Double, is Boolean -> result[key] = value
                    is Number -> result[key] = value.toDouble()
                    is JSONObject, is JSONArray -> result[key] = value.toString()
                    else -> result[key] = value.toString()
                }
            }
        } catch (_: Throwable) {
            // extras 不是 JSON 时按没有业务字段处理。
        }
        return result
    }
}
