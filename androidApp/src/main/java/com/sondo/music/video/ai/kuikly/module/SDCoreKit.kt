package com.sondo.music.video.ai.kuikly.module

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import com.sondo.music.video.ai.SondoApplication
import com.sondo.music.video.ai.kuikly.KuiklyRenderActivity
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 核心模块（SDHttp / SDNav / SDApp / SDPermission / SDFile / SDCrypto）共用的小工具：
 * 线程、入参解析、回参拼装、路径归一化。
 */
internal object SDCoreKit {

    const val TAG = "SDCore"

    /** FileProvider authority，与 AndroidManifest 里的 provider 声明一致。 */
    const val FILE_PROVIDER_SUFFIX = ".fileprovider"

    val mainHandler = Handler(Looper.getMainLooper())

    /** 文件读写、摘要、分享前拷贝等短 IO 任务。 */
    val io: ExecutorService = pool("sd-core-io", 4)

    val app: Application get() = SondoApplication.application

    private fun pool(name: String, size: Int): ExecutorService {
        val seq = AtomicInteger()
        val factory = ThreadFactory { r ->
            Thread(r, "$name-${seq.incrementAndGet()}").apply { isDaemon = true }
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

    fun ok(): JSONObject = JSONObject().put("ok", true)

    fun fail(error: String?): JSONObject =
        JSONObject().put("ok", false).put("error", error?.takeIf { it.isNotBlank() } ?: "unknown error")

    fun errorText(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    /** `file://` URI 或绝对路径 → 绝对路径（其它形式原样返回，由调用方判存在性）。 */
    fun localPath(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (!s.startsWith("file://")) return s
        val stripped = s.removePrefix("file://")
        // 调用方可能直接拼 "file://" + 路径（未做百分号编码），先按原样尝试
        if (stripped.startsWith("/") && File(stripped).exists()) return stripped
        return try {
            Uri.parse(s).path?.takeIf { it.isNotEmpty() } ?: stripped
        } catch (e: Throwable) {
            stripped
        }
    }

    fun pxToDp(context: Context, px: Int): Double {
        val density = context.resources.displayMetrics.density
        if (density <= 0f) return px.toDouble()
        // 保留两位小数，避免 JSON 里出现一长串浮点尾数
        return Math.round(px / density * 100.0) / 100.0
    }

    fun dpToPx(context: Context, dp: Double): Int =
        Math.round(dp * context.resources.displayMetrics.density).toInt()

    fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(digits[v ushr 4]).append(digits[v and 0x0f])
        }
        return sb.toString()
    }

    /** 把 App 私有目录下的文件转成可授权给其它应用的 content:// URI。目录白名单见 res/xml/file_paths.xml。 */
    fun contentUriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, file)

    fun logE(msg: String, e: Throwable? = null) {
        Log.e(TAG, msg, e)
    }
}

/**
 * 核心模块基类：统一做入参解析、异常兜底与「页面已销毁则不再回调」。
 *
 * 线程约定（Kuikly）：异步调用到达时在主线程，同步调用在 Kuikly 线程；两者都不能阻塞，
 * 耗时工作一律丢给 [SDCoreKit.io] 或网络线程，再通过 [reply] 回调（任意线程均可调用）。
 */
abstract class SDCoreBaseModule : KuiklyRenderBaseModule() {

    @Volatile
    protected var destroyed = false
        private set

    final override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        return try {
            handle(method, SDCoreKit.parse(params), callback)
        } catch (e: Throwable) {
            SDCoreKit.logE("${javaClass.simpleName}.$method failed", e)
            reply(callback, SDCoreKit.fail(SDCoreKit.errorText(e)))
            ""
        }
    }

    /** @return 同步调用的返回值（字符串）；异步方法返回 null 即可。 */
    protected abstract fun handle(method: String, p: JSONObject, callback: KuiklyRenderCallback?): Any?

    /** 未知方法：异步回 `{ok:false}`，同步回空串。 */
    protected fun unknown(method: String, callback: KuiklyRenderCallback?): Any? {
        reply(callback, SDCoreKit.fail("unknown method: $method"))
        return ""
    }

    protected fun reply(callback: KuiklyRenderCallback?, result: JSONObject) {
        if (callback == null || destroyed) return
        try {
            callback.invoke(result)
        } catch (e: Throwable) {
            // 页面销毁与回调竞争，忽略
        }
    }

    /** 在 IO 线程执行并回调一次；任务抛出的异常转成 `{ok:false, error}`。 */
    protected fun async(callback: KuiklyRenderCallback?, task: () -> JSONObject) {
        SDCoreKit.io.execute {
            val result = try {
                task()
            } catch (e: Throwable) {
                SDCoreKit.fail(SDCoreKit.errorText(e))
            }
            reply(callback, result)
        }
    }

    protected val appContext: Context get() = SDCoreKit.app

    /** 承载当前页面的容器 Activity（页面销毁后为 null）。 */
    protected val hostActivity: KuiklyRenderActivity? get() = activity as? KuiklyRenderActivity

    override fun onDestroy() {
        destroyed = true
        super.onDestroy()
    }
}
