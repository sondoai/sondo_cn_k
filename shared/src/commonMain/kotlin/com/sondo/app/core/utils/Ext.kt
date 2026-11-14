package com.sondo.app.core.utils

import com.sondo.app.base.BasePager
import com.sondo.app.data.models.ModelDate
import com.tencent.kuikly.core.timer.setTimeout
import kotlin.math.abs
import kotlin.math.roundToLong

// 参考工程 lib/core/extension/** 中有调用方的扩展。
// Widget 相关扩展（verticalSpace / judge / tap / visible / pdSymmetric …）在 Kuikly DSL 里没有对应物，不移植。

// ------------------------------------------------------------------ String

private val emailRegex = Regex("^[\\w-.]+@([\\w-]+\\.)+[\\w-]{2,4}$")

/** 是否是有效的邮箱 */
val String.isEmail: Boolean get() = emailRegex.matches(this)

val String.urlEncoded: String get() = urlEncode(this)

val String.urlDecoded: String get() = urlDecode(this)

val String.isNetworkUrl: Boolean get() = startsWith("http://") || startsWith("https://")

val String?.isEmptyOrNull: Boolean get() = this == null || this.isEmpty()

// ------------------------------------------------------------------ 数字

/** Dart `toStringAsFixed`（四舍五入，固定小数位）。 */
fun Double.toFixed(digits: Int): String {
    if (digits <= 0) return roundToLong().toString()
    var scale = 1L
    repeat(digits) { scale *= 10 }
    val scaled = (abs(this) * scale).roundToLong()
    val intPart = scaled / scale
    val frac = (scaled % scale).toString().padStart(digits, '0')
    val sign = if (this < 0 && scaled != 0L) "-" else ""
    return "$sign$intPart.$frac"
}

/** 将数字转为 1k 1w 1y */
fun Int.formIntText(): String = toLong().formIntText()

fun Long.formIntText(): String {
    if (this <= 0) return "0"
    // 亿
    if (this >= 100_000_000) return "${(this / 100_000_000.0).toFixed(1)}y"
    // 万
    if (this >= 10_000) return "${(this / 10_000.0).toFixed(1)}w"
    // 千
    if (this >= 1000) return "${(this / 1000.0).toFixed(1)}k"
    return toString()
}

// ------------------------------------------------------------------ 日期（接收者是 epoch 毫秒）

/** 支持 yyyy / MM / dd / HH / mm / ss 的本地时间格式化。 */
fun Long.formatDate(pattern: String): String {
    if (pattern.isEmpty()) return ""
    val p = ModelDate.localParts(this)
    fun two(n: Int) = n.toString().padStart(2, '0')
    return Regex("(yyyy|MM|dd|HH|mm|ss)").replace(pattern) { m ->
        when (m.value) {
            "yyyy" -> p.year.toString().padStart(4, '0')
            "MM" -> two(p.month)
            "dd" -> two(p.day)
            "HH" -> two(p.hour)
            "mm" -> two(p.minute)
            "ss" -> two(p.second)
            else -> m.value
        }
    }
}

/** `12s ago` / `5m ago` / `3h ago` / `MMM d, yyyy`（参考工程固定英文）。 */
val Long.timeAgo: String
    get() {
        val diff = nowMs() - this
        val sec = diff / 1000
        if (sec < 60) return "${sec}s ago"
        val min = diff / 60_000
        if (min < 60) return "${min}m ago"
        val hours = diff / 3_600_000
        if (hours < 24) return "${hours}h ago"
        return ModelDate.formatMMMdyyyy(this)
    }

/** `MMM d, yyyy`（英文月份缩写）。 */
val Long.yyyymmdd: String get() = ModelDate.formatMMMdyyyy(this)

fun Long.isSameDay(other: Long): Boolean {
    val a = ModelDate.localParts(this)
    val b = ModelDate.localParts(other)
    return a.year == b.year && a.month == b.month && a.day == b.day
}

// ------------------------------------------------------------------ 节流 / 防抖（对应 FunctionProxy）

/**
 * 节流 / 防抖。一个实例对应一个点击入口；定时器挂在创建时的页面上。
 * - [throttle]：执行期间（同步执行完之前）忽略重复触发；
 * - [throttleWithTimeout]：立即执行，[timeoutMs] 内的再次触发忽略；
 * - [debounce]：延迟 [timeoutMs] 执行，期间再次触发则重新计时。
 */
class FunctionProxy(private val timeoutMs: Int = 300) {
    private val pagerId: String = currentPagerId()
    private var enabled = true
    private var debounceGen = 0

    fun throttle(target: () -> Unit) {
        if (!enabled) return
        enabled = false
        try {
            target()
        } finally {
            enabled = true
        }
    }

    /** 挂起版本：[target] 跑完之前忽略重复触发（参考工程的 throttle 会等异步回调结束）。 */
    fun throttleAsync(pager: BasePager, target: suspend () -> Unit) {
        if (!enabled) return
        enabled = false
        pager.launch {
            try {
                target()
            } finally {
                enabled = true
            }
        }
    }

    fun throttleWithTimeout(target: () -> Unit) {
        if (!enabled) return
        enabled = false
        setTimeout(pagerId.ifEmpty { currentPagerId() }, timeoutMs) { enabled = true }
        target()
    }

    fun debounce(target: () -> Unit) {
        val gen = ++debounceGen
        setTimeout(pagerId.ifEmpty { currentPagerId() }, timeoutMs) {
            if (gen == debounceGen) target()
        }
    }
}
