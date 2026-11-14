package com.sondo.music.video.ai.kuikly.module

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 媒体类模块 / 视图共用的小工具：线程、入参解析、回参拼装、路径归一化。
 * 只给 SDAudioPlayer / SDVideo / SDMediaPicker / SDMediaProcess 这一组实现使用。
 */
internal object SDMediaKit {

    val mainHandler = Handler(Looper.getMainLooper())

    /** 文件拷贝、图片转码、缓存读写等短 IO 任务。 */
    val io: ExecutorService = pool("sd-media-io", 4)

    /** 编解码长任务（混流 / 波形 / 裁剪），并发数压低，避免同时占用过多硬件编解码器。 */
    val codec: ExecutorService = pool("sd-media-codec", 2)

    private fun pool(name: String, size: Int): ExecutorService {
        val seq = AtomicInteger()
        val factory = ThreadFactory { r ->
            Thread(r, "$name-${seq.incrementAndGet()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
        return ThreadPoolExecutor(size, size, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(), factory).apply {
            allowCoreThreadTimeOut(true)
        }
    }

    fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    /** 已在主线程则直接执行，否则 post。 */
    fun runOnMain(block: () -> Unit) {
        if (isMainThread()) block() else mainHandler.post(block)
    }

    /** 入参永远不抛异常：非法 JSON 当作空对象。 */
    fun parse(params: String?): JSONObject {
        if (params.isNullOrEmpty()) return JSONObject()
        return try {
            JSONObject(params)
        } catch (e: Throwable) {
            JSONObject()
        }
    }

    fun ok(vararg pairs: Pair<String, Any?>): Map<String, Any> {
        val map = LinkedHashMap<String, Any>()
        map["ok"] = true
        for ((k, v) in pairs) if (v != null) map[k] = v
        return map
    }

    fun fail(error: String, vararg pairs: Pair<String, Any?>): Map<String, Any> {
        val map = LinkedHashMap<String, Any>()
        map["ok"] = false
        map["error"] = error
        for ((k, v) in pairs) if (v != null) map[k] = v
        return map
    }

    /** 用户取消 / 任务被取消：与契约一致，只有 ok=false + cancelled=true，不带 error。 */
    fun cancelled(): Map<String, Any> = mapOf("ok" to false, "cancelled" to true)

    fun errorText(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    /** 回调一次；回调内部异常不外抛（页面可能已销毁）。 */
    fun reply(callback: KuiklyRenderCallback?, result: Map<String, Any>) {
        if (callback == null) return
        try {
            callback.invoke(result)
        } catch (e: Throwable) {
            // 页面已销毁等情况，忽略
        }
    }

    /** `file://` URI 或绝对路径 → 绝对路径；其它形式返回 null。 */
    fun localPath(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (s.startsWith("/")) return s
        if (s.startsWith("file://")) {
            // 调用方可能直接拼 "file://" + 路径（未做百分号编码），先按原样尝试
            val raw = s.removePrefix("file://")
            if (raw.startsWith("/") && File(raw).exists()) return raw
            return try {
                Uri.parse(s).path?.takeIf { it.isNotEmpty() }
            } catch (e: Throwable) {
                null
            }
        }
        return null
    }

    fun isHttp(raw: String?): Boolean {
        val s = raw?.trim().orEmpty()
        return s.startsWith("http://", true) || s.startsWith("https://", true)
    }

    /** App 临时目录下的子目录（不存在则创建）。 */
    fun tempDir(context: Context, name: String): File {
        val dir = File(context.cacheDir, name)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun ensureParent(file: File) {
        val parent = file.parentFile ?: return
        if (!parent.exists()) parent.mkdirs()
    }

    fun extensionOf(name: String?): String {
        val n = name.orEmpty().substringBefore('?').substringBefore('#')
        val last = n.substringAfterLast('/', n)
        val dot = last.lastIndexOf('.')
        if (dot < 0 || dot == last.length - 1) return ""
        val ext = last.substring(dot + 1).lowercase()
        return if (ext.length in 1..8 && ext.all { it.isLetterOrDigit() }) ext else ""
    }

    fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").takeIf { it.isNotEmpty() }
    }

    fun JSONObject.optLongOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        val v = opt(key)
        return when (v) {
            is Number -> v.toLong()
            is String -> v.toDoubleOrNull()?.toLong()
            else -> null
        }
    }

    fun JSONObject.optFlag(key: String, default: Boolean): Boolean {
        if (!has(key) || isNull(key)) return default
        return when (val v = opt(key)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v == "1" || v.equals("true", true)
            else -> default
        }
    }

    fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val s = optString(i, "")
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    /** 事件属性的值是一个回调函数；类型不对时返回 null。 */
    @Suppress("UNCHECKED_CAST")
    fun propCallback(value: Any?): KuiklyRenderCallback? = value as? KuiklyRenderCallback

    /** 视图属性值（Int / Float / Double / String / Boolean）→ Boolean。 */
    fun propFlag(value: Any?, default: Boolean = false): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value == "1" || value.equals("true", true)
        else -> default
    }

    fun propFloat(value: Any?, default: Float): Float = when (value) {
        is Number -> value.toFloat().takeIf { !it.isNaN() } ?: default
        is String -> value.toFloatOrNull() ?: default
        else -> default
    }

    fun propInt(value: Any?, default: Int): Int = when (value) {
        is Number -> value.toInt()
        is String -> value.toDoubleOrNull()?.toInt() ?: default
        else -> default
    }
}
