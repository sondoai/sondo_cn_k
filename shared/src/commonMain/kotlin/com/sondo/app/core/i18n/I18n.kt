package com.sondo.app.core.i18n

/**
 * 文案表。国内版只有简体中文，缺 key 时直接返回 key 本身（与 GetX `.tr` 行为一致）。
 *
 * 用法：`"confirm".tr`、`"resend_in_seconds".trParams("seconds" to "30")`
 */
object I18n {
    private val table: HashMap<String, String> by lazy(LazyThreadSafetyMode.NONE) {
        HashMap<String, String>(1400).also { ZhCn.load(it) }
    }

    fun t(key: String): String = table[key] ?: key

    fun has(key: String): Boolean = table.containsKey(key)

    fun t(key: String, params: Map<String, String>): String {
        var s = t(key)
        if (params.isEmpty()) return s
        // 先替换长名字，避免 @price 误伤 @priceUnit
        params.entries.sortedByDescending { it.key.length }.forEach { (k, v) ->
            s = s.replace("@$k", v)
        }
        return s
    }

    /** 后端 language 请求头固定值。 */
    const val LANGUAGE = "zh-cn"
}

val String.tr: String get() = I18n.t(this)

fun String.trParams(vararg params: Pair<String, Any?>): String =
    I18n.t(this, params.associate { it.first to (it.second?.toString() ?: "") })

fun String.trParams(params: Map<String, String>): String = I18n.t(this, params)
