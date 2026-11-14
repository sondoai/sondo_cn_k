package com.sondo.app.core.http

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

enum class ApiErrorType { TIMEOUT, NETWORK, CANCEL, BAD_RESPONSE, BUSINESS, UNAUTHORIZED }

/**
 * 接口错误。
 * @param code 业务码（BUSINESS / UNAUTHORIZED 时为后端 code，其余为 -1）
 * @param httpStatus HTTP 状态码（传输层失败为 0）
 * @param body 后端返回的完整信封（如有）
 */
class ApiException(
    val type: ApiErrorType,
    val code: Int = -1,
    message: String = "",
    val httpStatus: Int = 0,
    val body: JSONObject? = null,
) : Exception(message) {
    /** 后端 msg；没有则为空串。 */
    val serverMsg: String get() = body?.optString("msg", "") ?: ""
}

/** 对应 Flutter 侧 multiple_result 的 Result<T, Exception>：必须同时处理成功和失败。 */
sealed class ApiResult<out T> {
    class Success<T>(val data: T) : ApiResult<T>()
    class Failure(val error: ApiException) : ApiResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun getOrNull(): T? = (this as? Success)?.data
    fun errorOrNull(): ApiException? = (this as? Failure)?.error

    inline fun onSuccess(block: (T) -> Unit): ApiResult<T> {
        if (this is Success) block(data)
        return this
    }

    inline fun onFailure(block: (ApiException) -> Unit): ApiResult<T> {
        if (this is Failure) block(error)
        return this
    }

    inline fun <R> map(transform: (T) -> R): ApiResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }

    inline fun <R> fold(onSuccess: (T) -> R, onFailure: (ApiException) -> R): R = when (this) {
        is Success -> onSuccess(data)
        is Failure -> onFailure(error)
    }
}

// —— 信封 {code, msg, data} 取值辅助 —— //

/** data 为对象时返回；否则 null。 */
fun JSONObject.dataObj(): JSONObject? = optJSONObject("data")

/** data 为数组时返回；否则 null。 */
fun JSONObject.dataArr(): JSONArray? = optJSONArray("data")

/** 分页信封 data.list。 */
fun JSONObject.dataList(): JSONArray? = optJSONObject("data")?.optJSONArray("list")

/** 分页信封 data.total。 */
fun JSONObject.dataTotal(): Int = optJSONObject("data")?.optInt("total", 0) ?: 0

inline fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    val out = ArrayList<T>(length())
    for (i in 0 until length()) {
        val o = optJSONObject(i) ?: continue
        out.add(transform(o))
    }
    return out
}

fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return List(length()) { optString(it) ?: "" }
}

fun JSONArray?.toIntList(): List<Int> {
    if (this == null) return emptyList()
    return List(length()) { optInt(it) }
}

/** null / "null" 统一成空串（后端字段常返回 JSON null）。 */
fun JSONObject.str(key: String, def: String = ""): String {
    if (!has(key)) return def
    val v = opt(key) ?: return def
    val s = v.toString()
    return if (s == "null") def else s
}

/** 可空字符串：字段缺失或为 null 时返回 null。 */
fun JSONObject.strOrNull(key: String): String? {
    if (!has(key)) return null
    val v = opt(key) ?: return null
    val s = v.toString()
    return if (s == "null") null else s
}

fun JSONObject.intOrNull(key: String): Int? {
    if (!has(key)) return null
    val v = opt(key) ?: return null
    return when (v) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull() ?: v.toDoubleOrNull()?.toInt()
        else -> null
    }
}

fun JSONObject.long(key: String, def: Long = 0L): Long {
    val v = opt(key) ?: return def
    return when (v) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: v.toDoubleOrNull()?.toLong() ?: def
        else -> def
    }
}

fun JSONObject.dbl(key: String, def: Double = 0.0): Double {
    val v = opt(key) ?: return def
    return when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: def
        else -> def
    }
}

/** 兼容 true/false、1/0、"true"/"1"。 */
fun JSONObject.bool(key: String, def: Boolean = false): Boolean {
    val v = opt(key) ?: return def
    return when (v) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v == "true" || v == "1"
        else -> def
    }
}
