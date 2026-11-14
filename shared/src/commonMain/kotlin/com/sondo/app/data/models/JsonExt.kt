package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * models 包内共用的 JSON 取值辅助。
 *
 * - `jXxx`：宽松取值，缺失 / null / 类型不对时回落到默认值（对应 Dart 的 `json['k'] as T? ?? def`）。
 * - `reqXxx`：严格取值，缺失 / null / 类型不对时抛 [ModelParseException]（对应 Dart 的 `json['k'] as T`），
 *   只在各模型的 `fromJsonOrNull` 里用，外面用 [parseOrNull] 接住。
 */
class ModelParseException(key: String) : Exception("model field invalid: $key")

/** 把 JSON null 哨兵等非 JSON 值统一成 Kotlin null。 */
fun jsonValueOrNull(v: Any?): Any? = when (v) {
    null -> null
    is String, is Number, is Boolean, is JSONObject, is JSONArray -> v
    else -> null
}

fun JSONObject.jRaw(key: String): Any? = jsonValueOrNull(opt(key))

private fun numToString(n: Number): String {
    if (n is Double || n is Float) {
        val d = n.toDouble()
        if (d == d.toLong().toDouble() && d > -1e15 && d < 1e15) return d.toLong().toString()
        return d.toString()
    }
    return n.toString()
}

fun JSONObject.jStrOrNull(key: String): String? = when (val v = jRaw(key)) {
    null -> null
    is String -> v
    is Number -> numToString(v)
    else -> v.toString()
}

fun JSONObject.jStr(key: String, def: String = ""): String = jStrOrNull(key) ?: def

fun JSONObject.jLongOrNull(key: String): Long? = when (val v = jRaw(key)) {
    is Number -> v.toLong()
    is String -> v.trim().toLongOrNull() ?: v.trim().toDoubleOrNull()?.toLong()
    else -> null
}

fun JSONObject.jLong(key: String, def: Long = 0L): Long = jLongOrNull(key) ?: def

fun JSONObject.jIntOrNull(key: String): Int? = when (val v = jRaw(key)) {
    is Number -> v.toInt()
    is String -> v.trim().toIntOrNull() ?: v.trim().toDoubleOrNull()?.toInt()
    else -> null
}

fun JSONObject.jInt(key: String, def: Int = 0): Int = jIntOrNull(key) ?: def

fun JSONObject.jDblOrNull(key: String): Double? = when (val v = jRaw(key)) {
    is Number -> v.toDouble()
    is String -> v.trim().toDoubleOrNull()
    else -> null
}

fun JSONObject.jDbl(key: String, def: Double = 0.0): Double = jDblOrNull(key) ?: def

fun JSONObject.jBoolOrNull(key: String): Boolean? = when (val v = jRaw(key)) {
    is Boolean -> v
    is Number -> v.toInt() != 0
    is String -> when (v.trim().lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }
    else -> null
}

fun JSONObject.jBool(key: String, def: Boolean = false): Boolean = jBoolOrNull(key) ?: def

fun JSONObject.jObj(key: String): JSONObject? = jRaw(key) as? JSONObject

fun JSONObject.jArr(key: String): JSONArray? = jRaw(key) as? JSONArray

// —— 严格取值（Dart 的非空强转）—— //

fun JSONObject.reqStr(key: String): String = (jRaw(key) as? String) ?: throw ModelParseException(key)

fun JSONObject.reqLong(key: String): Long = (jRaw(key) as? Number)?.toLong() ?: throw ModelParseException(key)

fun JSONObject.reqInt(key: String): Int = (jRaw(key) as? Number)?.toInt() ?: throw ModelParseException(key)

fun JSONObject.reqDbl(key: String): Double = (jRaw(key) as? Number)?.toDouble() ?: throw ModelParseException(key)

fun JSONObject.reqBool(key: String): Boolean = (jRaw(key) as? Boolean) ?: throw ModelParseException(key)

fun JSONObject.reqObj(key: String): JSONObject = (jRaw(key) as? JSONObject) ?: throw ModelParseException(key)

/** 严格解析失败（Dart 侧会抛异常）时返回 null。 */
inline fun <T> parseOrNull(block: () -> T): T? = try {
    block()
} catch (e: ModelParseException) {
    null
}

// —— 数组 —— //

/** 逐项映射对象数组；非对象的元素跳过。 */
inline fun <T> JSONArray?.jMap(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    val out = ArrayList<T>(length())
    for (i in 0 until length()) {
        val o = jsonValueOrNull(opt(i)) as? JSONObject ?: continue
        out.add(transform(o))
    }
    return out
}

/**
 * 严格映射：任一元素不是对象或解析失败（transform 返回 null）则整体返回 null。
 * 对应 Dart 里「一条坏数据让整个列表解析抛异常」的行为。
 */
inline fun <T> JSONArray?.jMapStrict(transform: (JSONObject) -> T?): List<T>? {
    if (this == null) return emptyList()
    val out = ArrayList<T>(length())
    for (i in 0 until length()) {
        val o = jsonValueOrNull(opt(i)) as? JSONObject ?: return null
        out.add(transform(o) ?: return null)
    }
    return out
}

/** 元素转字符串（Dart `e.toString()`）；null 元素跳过。 */
fun JSONArray?.jStrList(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) {
        when (val v = jsonValueOrNull(opt(i))) {
            null -> {}
            is String -> out.add(v)
            is Number -> out.add(numToString(v))
            else -> out.add(v.toString())
        }
    }
    return out
}

fun JSONArray?.jLongList(): List<Long> {
    if (this == null) return emptyList()
    val out = ArrayList<Long>(length())
    for (i in 0 until length()) {
        when (val v = jsonValueOrNull(opt(i))) {
            is Number -> out.add(v.toLong())
            is String -> v.trim().toLongOrNull()?.let { out.add(it) }
            else -> {}
        }
    }
    return out
}

fun <T> List<T>.toJsonArray(transform: (T) -> Any?): JSONArray {
    val arr = JSONArray()
    for (e in this) arr.put(transform(e))
    return arr
}

fun List<String>.toJsonStringArray(): JSONArray {
    val arr = JSONArray()
    for (e in this) arr.put(e)
    return arr
}

fun List<Long>.toJsonLongArray(): JSONArray {
    val arr = JSONArray()
    for (e in this) arr.put(e)
    return arr
}

/** 安全解析 JSON 对象字符串。 */
fun parseJsonObjectOrNull(text: String?): JSONObject? {
    val t = text?.trim() ?: return null
    if (!t.startsWith("{")) return null
    return try {
        JSONObject(t)
    } catch (e: Throwable) {
        null
    }
}
