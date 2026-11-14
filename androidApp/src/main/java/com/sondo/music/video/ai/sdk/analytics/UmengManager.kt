package com.sondo.music.video.ai.sdk.analytics

import android.content.Context
import android.util.Log
import com.umeng.analytics.MobclickAgent
import com.umeng.commonsdk.UMConfigure
import org.json.JSONObject

/**
 * 友盟统计。
 *
 * 合规：隐私同意前不调用友盟的任何接口，连 preInit 也不在 Application 里做（和参考工程一致）；
 * [init] 由 Kuikly 侧在用户同意后调用。[init] 之前到达的事件、页面、账号调用一律丢弃。
 * 页面统计是手动模式：页面名由 Kuikly 侧的路由决定，不采集 Activity 名。
 */
internal object UmengManager {

    private const val TAG = "SDAnalytics"

    /** 友盟对单个属性值的长度限制。 */
    private const val MAX_VALUE_LENGTH = 256

    @Volatile
    private var initialized = false

    @Synchronized
    fun init(context: Context, appKey: String, channel: String) {
        if (initialized) return
        if (appKey.isEmpty()) {
            Log.w(TAG, "init skipped: empty appKey")
            return
        }
        try {
            val app = context.applicationContext
            UMConfigure.preInit(app, appKey, channel)
            UMConfigure.init(app, appKey, channel, UMConfigure.DEVICE_TYPE_PHONE, null)
            MobclickAgent.setPageCollectionMode(MobclickAgent.PageMode.MANUAL)
            initialized = true
        } catch (error: Throwable) {
            Log.w(TAG, "init failed: ${error.javaClass.simpleName}")
        }
    }

    /** props 的值只接受 String / Number；布尔转成 0 / 1，其他类型转成字符串，null 丢掉。 */
    fun onEvent(context: Context, name: String, props: JSONObject?) {
        if (!initialized || name.isEmpty()) return
        try {
            val map = HashMap<String, Any>()
            if (props != null) {
                val keys = props.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    when (val value = props.opt(key)) {
                        null, JSONObject.NULL -> Unit
                        is String -> map[key] = value.take(MAX_VALUE_LENGTH)
                        is Number -> map[key] = value
                        is Boolean -> map[key] = if (value) 1 else 0
                        else -> map[key] = value.toString().take(MAX_VALUE_LENGTH)
                    }
                }
            }
            val app = context.applicationContext
            // 友盟不接受空的属性表，没有属性时走不带参数的接口。
            if (map.isEmpty()) MobclickAgent.onEvent(app, name) else MobclickAgent.onEventObject(app, name, map)
        } catch (_: Throwable) {
        }
    }

    fun onPageStart(name: String) {
        if (!initialized || name.isEmpty()) return
        try {
            MobclickAgent.onPageStart(name)
        } catch (_: Throwable) {
        }
    }

    fun onPageEnd(name: String) {
        if (!initialized || name.isEmpty()) return
        try {
            MobclickAgent.onPageEnd(name)
        } catch (_: Throwable) {
        }
    }

    fun signIn(userId: String) {
        if (!initialized || userId.isEmpty()) return
        try {
            MobclickAgent.onProfileSignIn(userId)
        } catch (_: Throwable) {
        }
    }

    fun signOff() {
        if (!initialized) return
        try {
            MobclickAgent.onProfileSignOff()
        } catch (_: Throwable) {
        }
    }
}
