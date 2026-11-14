package com.sondo.app.nativeapi

import com.tencent.kuikly.core.manager.PagerManager
import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Sondo 原生扩展模块基类。
 *
 * 统一约定（三端一致）：
 * - 入参：一个 JSON 字符串（无参传 null）；
 * - 异步回参：一个 JSON 对象，至少包含 `ok: Boolean`，失败时带 `error: String`（可选 `code`）；
 * - 同步回参：字符串（简单值）或 JSON 字符串。
 * 原生侧模块名与 [moduleName] 完全一致，方法名与这里的 method 字符串完全一致。
 */
abstract class SDModule : Module() {

    /** 异步调用，不关心结果。 */
    protected fun invoke(method: String, params: JSONObject? = null) {
        toNative(false, method, params?.toString(), null, false)
    }

    /** 异步调用，回调一次。 */
    protected fun invoke(method: String, params: JSONObject?, callback: (JSONObject) -> Unit) {
        toNative(false, method, params?.toString(), { callback(it ?: JSONObject()) }, false)
    }

    /** 异步调用，回调常驻（进度 / 事件流）。返回的 ref 可用 [removeCallback] 注销。 */
    protected fun listen(method: String, params: JSONObject?, callback: (JSONObject) -> Unit): CallbackRef? {
        return toNative(true, method, params?.toString(), { callback(it ?: JSONObject()) }, false).callbackRef
    }

    /** 同步调用（原生侧运行在 Kuikly 线程，禁止耗时操作）。 */
    protected fun invokeSync(method: String, params: JSONObject? = null): String {
        return toNative(false, method, params?.toString(), null, true).toString()
    }

    protected fun invokeSyncJson(method: String, params: JSONObject? = null): JSONObject {
        val s = invokeSync(method, params)
        if (s.isEmpty()) return JSONObject()
        return try { JSONObject(s) } catch (e: Throwable) { JSONObject() }
    }

    /** 挂起直到原生回调。 */
    protected suspend fun await(method: String, params: JSONObject? = null): JSONObject {
        return suspendCoroutine { cont ->
            invoke(method, params) { cont.resume(it) }
        }
    }
}

/** 取当前页面上的原生模块（所有 SD 模块都在 BasePager.createExternalModules 里注册）。 */
inline fun <reified T : Module> nativeModule(name: String): T =
    PagerManager.getCurrentPager().acquireModule(name)

fun JSONObject.ok(): Boolean = optBoolean("ok", false)
fun JSONObject.errorMsg(): String = optString("error", "")
