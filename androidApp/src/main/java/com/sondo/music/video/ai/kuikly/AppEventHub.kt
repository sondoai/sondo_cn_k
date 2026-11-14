package com.sondo.music.video.ai.kuikly

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 应用级事件中心：SDAppModule 的事件源（联网状态 / 前后台 / 深链 / 键盘）。
 *
 * 每个页面的 SDAppModule 实例各自注册一个 [Listener]；应用级事件向所有监听广播，
 * 页面级事件（键盘高度、系统导航栏变化）只发给对应 Activity 上的监听。
 *
 * 合规：这里不发起任何网络请求，也不读取设备标识；联网状态监听在 Kuikly 侧第一次用到时才注册。
 */
object AppEventHub {

    private const val TAG = "AppEventHub"
    private const val LOST_RECHECK_DELAY_MS = 500L

    /**
     * @param owner 注册它的 SDAppModule 实例（一个页面一个）
     * @param listenerId Kuikly 侧给的 id（removeEventListener 用）
     */
    class Listener(
        val activity: Activity?,
        val owner: Any? = null,
        val listenerId: String = "",
        val emit: (JSONObject) -> Unit,
    ) {
        /** 是否接收 deepLink：只有深链消费者在 getInitialLink 之后注册的监听才接收。 */
        @Volatile
        var receivesLinks = false
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val linkLock = Any()

    private var app: Application? = null
    private var startedActivities = 0

    /** 尚未投递的深链（冷启动链接，或到达时还没有消费者监听的链接；新链接覆盖旧的）。 */
    private var pendingLink: String? = null

    /** 深链消费者：最近一次调用 getInitialLink 的 SDAppModule 实例（页面销毁时清空）。规则见 shared/.../nativeapi/SDAppModule.kt。 */
    private var linkConsumer: Any? = null

    /** 消费者页面已销毁、还没有新的 getInitialLink：退回为向所有监听广播（与改动前一致，不会比原来更差）。 */
    private var linkConsumerLost = false

    /** App 是否在前台（至少有一个 Activity 处于 started）。 */
    @Volatile
    var isForeground: Boolean = false
        private set

    @Volatile
    private var connectivityTypes: List<String>? = null

    @Volatile
    private var connectivityRegistered = false

    fun install(application: Application) {
        if (app != null) return
        app = application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                if (startedActivities == 1) {
                    isForeground = true
                    broadcast(JSONObject().put("event", "lifecycle").put("state", "resumed"))
                }
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) {
                    isForeground = false
                    broadcast(JSONObject().put("event", "lifecycle").put("state", "paused"))
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    // ------------------------------------------------------------------ listeners

    fun addListener(listener: Listener) {
        val flush = synchronized(linkLock) {
            listeners.add(listener)
            // 消费者在 getInitialLink 之后注册的监听：接收 deepLink，并补发期间暂存的链接
            if (listener.owner != null && listener.owner === linkConsumer) {
                listener.receivesLinks = true
                pendingLink.also { pendingLink = null }
            } else {
                null
            }
        }
        ensureConnectivityMonitor()
        if (flush != null) mainHandler.post { onDeepLink(flush) }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** 页面的 SDAppModule 销毁：释放它的全部监听；它是深链消费者时回到「无消费者」，之后的链接重新暂存。 */
    fun removeOwner(owner: Any) {
        synchronized(linkLock) {
            listeners.removeAll { it.owner === owner }
            if (linkConsumer === owner) {
                linkConsumer = null
                linkConsumerLost = true
            }
        }
    }

    /** 向所有页面广播。 */
    fun broadcast(event: JSONObject) {
        for (l in listeners) safeEmit(l, event)
    }

    /** 只发给 [activity] 承载的页面。 */
    fun emitToPage(activity: Activity, event: JSONObject) {
        for (l in listeners) if (l.activity === activity) safeEmit(l, event)
    }

    private fun safeEmit(listener: Listener, event: JSONObject) {
        try {
            listener.emit(event)
        } catch (e: Throwable) {
            Log.w(TAG, "emit ${event.optString("event")} failed", e)
        }
    }

    // ------------------------------------------------------------------ deep link

    /** 收到深链（由 [DeepLinkActivity] 调用）。有消费者监听则投递给它们，否则暂存等待 getInitialLink / 补发。 */
    fun onDeepLink(url: String) {
        if (url.isEmpty()) return
        val targets = synchronized(linkLock) {
            val consumer = linkConsumer
            val t = when {
                consumer != null -> listeners.filter { it.receivesLinks && it.owner === consumer }
                linkConsumerLost -> listeners.toList()
                else -> emptyList()
            }
            if (t.isEmpty()) pendingLink = url
            t
        }
        for (l in targets) safeEmit(l, deepLinkEvent(url))
    }

    /** getInitialLink：把 [consumer] 登记为深链消费者，取走暂存的深链（没有则空串）。 */
    fun takeInitialLink(consumer: Any): String = synchronized(linkLock) {
        linkConsumerLost = false
        if (linkConsumer !== consumer) {
            linkConsumer = consumer
            // 之前注册的监听不接收深链（只有 getInitialLink 之后注册的才是深链入口）
            for (l in listeners) l.receivesLinks = false
        }
        val link = pendingLink.orEmpty()
        pendingLink = null
        link
    }

    private fun deepLinkEvent(url: String) = JSONObject().put("event", "deepLink").put("url", url)

    // ------------------------------------------------------------------ connectivity

    /** 当前联网类型：wifi / mobile / ethernet / other / none。可在任意线程调用。 */
    fun currentConnectivity(): List<String> {
        ensureConnectivityMonitor()
        // 监听注册失败时没有人更新缓存，只能每次现查
        if (!connectivityRegistered) return queryConnectivity()
        return connectivityTypes ?: queryConnectivity()
    }

    fun connectivityJson(types: List<String> = currentConnectivity()): JSONArray =
        JSONArray().also { arr -> types.forEach { arr.put(it) } }

    private fun connectivityManager(): ConnectivityManager? =
        app?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private fun queryConnectivity(): List<String> {
        return try {
            val cm = connectivityManager() ?: return listOf("none")
            val network = cm.activeNetwork ?: return listOf("none")
            typesOf(cm.getNetworkCapabilities(network))
        } catch (e: Throwable) {
            listOf("none")
        }
    }

    private fun typesOf(caps: NetworkCapabilities?): List<String> {
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return listOf("none")
        val types = ArrayList<String>(2)
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) types.add("wifi")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) types.add("mobile")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) types.add("ethernet")
        // 蓝牙共享 / 不带底层传输信息的 VPN 等：有网但不属于上面三类
        if (types.isEmpty()) types.add("other")
        return types
    }

    private fun ensureConnectivityMonitor() {
        synchronized(this) {
            if (connectivityRegistered) return
            val cm = connectivityManager() ?: return
            try {
                cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        updateConnectivity(typesOf(caps))
                    }

                    override fun onLost(network: Network) {
                        // 回调先于系统状态更新，且切换网络时新网络的 onCapabilitiesChanged 会紧随其后：
                        // 稍等再查，避免 wifi→蜂窝切换时多报一次 none
                        mainHandler.postDelayed({ updateConnectivity(queryConnectivity()) }, LOST_RECHECK_DELAY_MS)
                    }

                    override fun onUnavailable() {
                        updateConnectivity(listOf("none"))
                    }
                })
                connectivityRegistered = true
            } catch (e: Throwable) {
                // 个别 ROM 在后台注册会抛 SecurityException / TooManyRequestsException：退化为按需查询
                Log.w(TAG, "registerDefaultNetworkCallback failed", e)
            }
        }
    }

    private fun updateConnectivity(types: List<String>) {
        val changed = synchronized(this) {
            if (connectivityTypes == types) {
                false
            } else {
                connectivityTypes = types
                true
            }
        }
        if (changed) {
            broadcast(JSONObject().put("event", "connectivity").put("types", connectivityJson(types)))
        }
    }
}
