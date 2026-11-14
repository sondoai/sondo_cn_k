package com.sondo.app.data.api

import com.sondo.app.core.http.ApiErrorType
import com.sondo.app.core.http.ApiException
import com.sondo.app.core.http.ApiResult
import com.sondo.app.data.models.jsonValueOrNull
import com.tencent.kuikly.core.datetime.DateTime
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/*
 * data/api 内部共用的小工具。
 *
 * 约定：Dart 数据源里「catch 后返回兜底值（null / [] / false / -1 / total=-1）」的方法，
 * 这里统一返回 ApiResult.Failure，由调用方决定兜底；各方法 KDoc 注明了 Dart 的兜底值。
 */

/** 信封里的 data（JSON null 归一为 null）。 */
internal fun JSONObject.dataAny(): Any? = jsonValueOrNull(opt("data"))

internal fun JSONObject.dataObject(): JSONObject? = dataAny() as? JSONObject

internal fun JSONObject.dataArray(): JSONArray? = dataAny() as? JSONArray

/** 回包能拿到但不是预期形状（对应 Dart 解析时抛异常被 catch）。 */
internal fun badResponse(message: String = "", body: JSONObject? = null): ApiResult.Failure =
    ApiResult.Failure(ApiException(ApiErrorType.BAD_RESPONSE, message = message, body = body))

/** 请求没发出去就判定无效（参数为空等）。 */
internal fun invalidRequest(message: String = ""): ApiResult.Failure =
    ApiResult.Failure(ApiException(ApiErrorType.CANCEL, message = message))

/** 成功即 Unit。 */
internal fun ApiResult<JSONObject>.asUnit(): ApiResult<Unit> = map { }

/** 成功即 true（Dart 的 `result.code == 0`）。 */
internal fun ApiResult<JSONObject>.asOk(): ApiResult<Boolean> = map { true }

/** data 为对象则取对象，否则给空对象（Dart 返回 dynamic data 的场景）。 */
internal fun ApiResult<JSONObject>.asDataObject(): ApiResult<JSONObject> = map { it.dataObject() ?: JSONObject() }

/** 用 [block] 解析信封；返回 null 视为解析失败。 */
internal inline fun <T> ApiResult<JSONObject>.parse(block: (JSONObject) -> T?): ApiResult<T> = when (this) {
    is ApiResult.Failure -> this
    is ApiResult.Success -> {
        val v = block(data)
        if (v == null) badResponse(body = data) else ApiResult.Success(v)
    }
}

internal fun nowMs(): Long = DateTime.currentTimestamp()

/** 去掉值为 null 的项（Dart 的 `'k': ?v`）。 */
internal fun queryOf(vararg pairs: Pair<String, Any?>): Map<String, Any?> {
    val m = LinkedHashMap<String, Any?>()
    for ((k, v) in pairs) if (v != null) m[k] = v
    return m
}

/** JSON body；值为 null 的项不写入（Dart 的 `'k': ?v`）。 */
internal fun bodyOf(vararg pairs: Pair<String, Any?>): JSONObject {
    val o = JSONObject()
    for ((k, v) in pairs) {
        when (v) {
            null -> {}
            is Boolean -> o.put(k, v)
            is Int -> o.put(k, v)
            is Long -> o.put(k, v)
            is Double -> o.put(k, v)
            else -> o.put(k, v)
        }
    }
    return o
}
