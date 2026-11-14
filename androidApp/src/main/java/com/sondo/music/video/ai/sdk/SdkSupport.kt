package com.sondo.music.video.ai.sdk

import android.os.Handler
import android.os.Looper
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 三方 SDK 模块（支付 / 登录 / 推送 / 统计）共用的小工具。
 *
 * 回参约定：传给 Kuikly 的 Map 只能放 Int / Long / Float / Double / String / Boolean / Map / List，
 * 其他类型会被渲染层序列化时丢掉，所以 JSON 一律先经 [toBridgeMap] / [toBridgeList] 转换。
 */
internal object SdkSupport {

    val mainHandler = Handler(Looper.getMainLooper())

    fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /** 入参永远是一个 JSON 字符串或 null；解析失败按空对象处理，调用方用 opt* 取值。 */
    fun parseParams(params: String?): JSONObject {
        if (params.isNullOrBlank()) return JSONObject()
        return try {
            JSONObject(params)
        } catch (_: Throwable) {
            JSONObject()
        }
    }

    fun ok(vararg pairs: Pair<String, Any?>): Map<String, Any> = result(true, null, *pairs)

    fun fail(error: String?, vararg pairs: Pair<String, Any?>): Map<String, Any> =
        result(false, error?.takeIf { it.isNotEmpty() } ?: "unknown", *pairs)

    private fun result(ok: Boolean, error: String?, vararg pairs: Pair<String, Any?>): Map<String, Any> {
        val map = LinkedHashMap<String, Any>()
        map["ok"] = ok
        if (error != null) map["error"] = error
        for ((key, value) in pairs) {
            if (value != null) map[key] = value
        }
        return map
    }

    /** iOS 专有能力在 Android 上的统一回参。 */
    fun unsupported(vararg pairs: Pair<String, Any?>): Map<String, Any> = fail("unsupported", *pairs)

    fun JSONObject.toBridgeMap(): Map<String, Any> {
        val map = LinkedHashMap<String, Any>()
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            toBridgeValue(opt(key))?.let { map[key] = it }
        }
        return map
    }

    fun JSONArray.toBridgeList(): List<Any> {
        val list = ArrayList<Any>(length())
        for (i in 0 until length()) {
            toBridgeValue(opt(i))?.let { list.add(it) }
        }
        return list
    }

    private fun toBridgeValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.toBridgeMap()
        is JSONArray -> value.toBridgeList()
        is Int, is Long, is Float, is Double, is String, is Boolean -> value
        is Number -> value.toDouble()
        else -> value.toString()
    }
}

/**
 * 包一层 Kuikly 回调，保证只回一次。
 * 异步 SDK 可能既回调结果又触发超时兜底，谁先到用谁，后到的丢弃。
 */
internal class OnceCallback(callback: KuiklyRenderCallback?) {
    private val target = callback
    private val done = AtomicBoolean(false)

    val isDone: Boolean get() = done.get()

    fun resolve(result: Map<String, Any>): Boolean {
        if (!done.compareAndSet(false, true)) return false
        try {
            target?.invoke(result)
        } catch (_: Throwable) {
            // 页面已销毁时回调可能抛异常，这里不能把 SDK 线程带崩。
        }
        return true
    }
}

/**
 * 进程级事件广播：每个 Kuikly 页面各有一个 Module 实例，`addEventListener` 的回调都登记到这里，
 * SDK 回调时发给所有还活着的页面；页面销毁时由 Module.onDestroy 按 owner 注销。
 */
internal class SdkEventHub {
    private class Entry(val owner: Any, val callback: KuiklyRenderCallback)

    private val entries = CopyOnWriteArrayList<Entry>()

    val hasListeners: Boolean get() = entries.isNotEmpty()

    fun add(owner: Any, callback: KuiklyRenderCallback?) {
        if (callback == null) return
        entries.add(Entry(owner, callback))
    }

    fun removeOwner(owner: Any) {
        entries.removeAll { it.owner === owner }
    }

    fun emit(event: String, vararg pairs: Pair<String, Any?>) {
        if (entries.isEmpty()) return
        val payload = LinkedHashMap<String, Any>()
        payload["event"] = event
        for ((key, value) in pairs) {
            if (value != null) payload[key] = value
        }
        for (entry in entries) {
            try {
                entry.callback.invoke(payload)
            } catch (_: Throwable) {
            }
        }
    }
}
