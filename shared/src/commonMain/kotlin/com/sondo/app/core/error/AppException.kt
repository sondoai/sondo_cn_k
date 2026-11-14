package com.sondo.app.core.error

import com.sondo.app.core.http.ApiErrorType
import com.sondo.app.core.http.ApiException

/**
 * 仓库层向页面暴露的错误（对应 Flutter 侧 AppException）。
 * [message] 是可直接展示的后端文案，或一个 i18n key（`request_failed` / `no_network_connection`）。
 */
class AppException(
    override val message: String,
    val code: String? = null,
    /** 仅用于日志的调试信息，不直接展示给用户。 */
    val debugMessage: String? = null,
) : Exception(message) {
    override fun toString(): String = message
}

/**
 * [ApiException] → [AppException]（对应 DioExceptionMapper.toAppException）：
 * 优先取业务 code/msg，其次 HTTP status，最后按网络类错误处理。
 */
fun ApiException.toAppException(): AppException {
    val payload = body
    if (payload != null) {
        val msg = serverMsg
        val bizCode = if (payload.has("code")) payload.opt("code")?.toString() else null
        return AppException(msg.ifEmpty { "request_failed" }, code = bizCode, debugMessage = toString())
    }
    if (type == ApiErrorType.BAD_RESPONSE) {
        return AppException("request_failed", code = if (httpStatus > 0) httpStatus.toString() else null, debugMessage = toString())
    }
    val key = when (type) {
        ApiErrorType.CANCEL -> "request_failed"
        else -> "no_network_connection"
    }
    return AppException(key, debugMessage = toString())
}
