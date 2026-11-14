package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 分页结果（Dart: lib/data/models/api_result.dart `PageData<T>`）。
 * 后端信封 `data: {list: [...], total: n}`。
 */
data class PageResult<T>(
    var list: List<T> = emptyList(),
    var total: Int = 0,
) {
    companion object {
        /** 从 `data` 对象解析；data 为空时得到空页。 */
        inline fun <T> fromData(data: JSONObject?, transform: (JSONObject) -> T): PageResult<T> {
            if (data == null) return PageResult()
            return PageResult(data.jArr("list").jMap(transform), data.jInt("total", 0))
        }

        /** 严格版：任一条解析失败返回 null（对应 Dart 整页解析抛异常）。 */
        inline fun <T> fromDataStrict(data: JSONObject?, transform: (JSONObject) -> T?): PageResult<T>? {
            if (data == null) return null
            val list = data.jArr("list").jMapStrict(transform) ?: return null
            return PageResult(list, data.jInt("total", 0))
        }
    }
}

/** 流式润色的错误回包（Dart: lib/data/models/api_result.dart `RefinementErrorResponse`）。 */
data class RefinementErrorResponse(
    var msg: String? = null,
    var code: Int = -1,
) {
    companion object {
        fun fromJson(jsonStr: String): RefinementErrorResponse {
            val json = parseJsonObjectOrNull(jsonStr) ?: return RefinementErrorResponse(null, -1)
            val code = json.jIntOrNull("code") ?: return RefinementErrorResponse(null, -1)
            return RefinementErrorResponse(json.jStrOrNull("msg"), code)
        }
    }
}
