package com.sondo.app.core.utils

import com.sondo.app.base.AppEnv
import com.tencent.kuikly.core.log.KLog
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 调试日志（仅调试包输出，对应 Flutter 侧 DevLog）。 */
object DevLog {
    var isOpenLog: Boolean? = null

    private val enabled: Boolean get() = isOpenLog ?: AppEnv.isDebug

    fun printIn(obj: String, falg: String? = null) = logger(obj, falg = falg)

    fun logger(obj: String, falg: String? = null) {
        if (!enabled) return
        if (!falg.isNullOrEmpty()) KLog.i(TAG, "Start: ==========$falg============")
        KLog.i(TAG, obj)
        if (!falg.isNullOrEmpty()) KLog.i(TAG, "End: ==========$falg============")
    }

    private const val TAG = "Sondo"
}

/** 分级日志（仅调试包输出，对应 Flutter 侧 LogUtils；终端颜色码在 KLog 下无意义，只保留级别）。 */
object LogUtils {
    private const val TAG = "Sondo"

    fun logger(message: Any?) {
        if (AppEnv.isDebug) KLog.i(TAG, "[Log]: $message")
    }

    fun error(message: Any?) {
        if (AppEnv.isDebug) KLog.e(TAG, "[Error]: $message")
    }

    fun info(message: Any?) {
        if (AppEnv.isDebug) KLog.i(TAG, "[Info]: $message")
    }

    fun warning(message: Any?) {
        if (AppEnv.isDebug) KLog.i(TAG, "[Warning]: $message")
    }

    fun debug(message: Any?) {
        if (AppEnv.isDebug) KLog.d(TAG, "[Debug]: $message")
    }

    fun logRed(msg: String) {
        if (AppEnv.isDebug) KLog.e(TAG, msg)
    }

    fun logGreen(msg: String) {
        if (AppEnv.isDebug) KLog.i(TAG, msg)
    }

    fun logYellow(msg: String) {
        if (AppEnv.isDebug) KLog.i(TAG, msg)
    }

    fun logBlue(msg: String) {
        if (AppEnv.isDebug) KLog.i(TAG, msg)
    }

    fun logPink(msg: String) {
        if (AppEnv.isDebug) KLog.i(TAG, msg)
    }
}

/** 请求日志脱敏：验证码、密码、Token 不得出现在日志或上报中。 */
object LogRedactor {
    const val redacted = "***"

    val sensitiveBodyKeys = setOf(
        "idToken",
        "captchaToken",
        "refreshToken",
        "accessToken",
        "loginToken",
        "bindToken",
        "password",
        "newPassword",
        "signedTransactionInfo",
        "appAccountToken",
    )

    fun headerValue(key: String, value: Any?): String = value.toString()

    /** 递归打码；JSONObject / JSONArray / Map / List 以外的值原样返回。 */
    fun body(data: Any?): Any? = when (data) {
        is JSONObject -> {
            val out = JSONObject()
            for (key in data.keySet()) {
                val v = data.opt(key)
                out.put(key, if (shouldRedact(key, v)) redacted else body(v))
            }
            out
        }
        is JSONArray -> {
            val out = JSONArray()
            for (i in 0 until data.length()) out.put(body(data.opt(i)))
            out
        }
        is Map<*, *> -> data.entries.associate { (k, v) ->
            k to if (shouldRedact(k.toString(), v)) redacted else body(v)
        }
        is List<*> -> data.map { body(it) }
        else -> data
    }

    /** 业务响应里的 `code` 是 int；验证码 `code` 是字符串。只打码后者。 */
    private fun shouldRedact(key: String, value: Any?): Boolean {
        if (key == "code") return value is String
        return sensitiveBodyKeys.contains(key)
    }
}
