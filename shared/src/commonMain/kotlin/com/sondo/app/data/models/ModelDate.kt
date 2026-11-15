package com.sondo.app.data.models

import com.sondo.app.core.i18n.tr
import com.sondo.app.core.i18n.trParams
import com.tencent.kuikly.core.datetime.DateTime

/**
 * 模型里用到的日期格式化 / 解析（对应 Dart 的 intl DateFormat 与 lib/core/utils/date_utils.dart）。
 *
 * commonMain 没有时区 API；国内版固定按东八区（无夏令时）换算本地时间。
 */
object ModelDate {
    /** 本地时区偏移（毫秒）。 */
    const val LOCAL_OFFSET_MS = 8L * 3600_000L


    class Parts(
        val year: Int,
        val month: Int,
        val day: Int,
        val hour: Int,
        val minute: Int,
        val second: Int,
        val millisecond: Int,
    )

    fun now(): Long = DateTime.currentTimestamp()

    private fun floorDiv(a: Long, b: Long): Long {
        var q = a / b
        if ((a % b != 0L) && ((a < 0) != (b < 0))) q--
        return q
    }

    /** epoch 毫秒 → 本地（东八区）年月日时分秒。 */
    fun localParts(epochMs: Long): Parts = utcParts(epochMs + LOCAL_OFFSET_MS)

    fun utcParts(epochMs: Long): Parts {
        val days = floorDiv(epochMs, 86_400_000L)
        val msOfDay = (epochMs - days * 86_400_000L).toInt()
        // civil_from_days（Howard Hinnant）
        val z = days + 719_468L
        val era = floorDiv(z, 146_097L)
        val doe = (z - era * 146_097L).toInt()
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era.toInt() * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val year = if (m <= 2) y + 1 else y
        return Parts(
            year, m, d,
            msOfDay / 3_600_000,
            (msOfDay / 60_000) % 60,
            (msOfDay / 1000) % 60,
            msOfDay % 1000,
        )
    }

    /** 年月日（公历）→ 自 1970-01-01 起的天数。 */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = floorDiv(y.toLong(), 400L)
        val yoe = (y - era * 400).toInt()
        val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }

    /**
     * 统一的日期展示格式 `yyyy年MM月dd日`（本地时间），如 `2026年11月03日`。
     * 参考工程是英文 `MMM d, yyyy`（`Nov 3, 2026`），国内版按产品要求改为中文年月日。
     */
    fun formatDate(epochMs: Long): String {
        val p = localParts(epochMs)
        return "${p.year}年${pad2(p.month)}月${pad2(p.day)}日"
    }

    /** `yyyy-MM-dd HH:mm:ss`（本地时间）。 */
    fun formatYmdHms(epochMs: Long): String {
        val p = localParts(epochMs)
        return "${p.year}-${pad2(p.month)}-${pad2(p.day)} ${pad2(p.hour)}:${pad2(p.minute)}:${pad2(p.second)}"
    }

    fun pad2(v: Int): String = if (v < 10) "0$v" else v.toString()

    /** 对应 DateUtils.formatDateTime：刚刚 / N 分钟前 / N 小时前 / N 天前 / yyyy年MM月dd日。 */
    fun formatDateTime(epochMs: Long): String {
        val diff = now() - epochMs
        val inDays = diff / 86_400_000L
        val inHours = diff / 3_600_000L
        val inMinutes = diff / 60_000L
        if (inDays == 0L) {
            if (inHours == 0L) {
                if (inMinutes == 0L) return "just_now".tr
                return "minutes_ago".trParams("minutes" to inMinutes.toString())
            }
            return "hours_ago".trParams("hours" to inHours.toString())
        } else if (inDays < 7) {
            return "days_ago".trParams("days" to inDays.toString())
        }
        return formatDate(epochMs)
    }

    /**
     * 解析 ISO-8601（`2026-01-02T03:04:05.678Z` / `2026-01-02 03:04:05` / `+08:00` 后缀）为 epoch 毫秒。
     * 没有时区后缀时按本地（东八区）时间解释，与 Dart `DateTime.parse` 一致。失败返回 null。
     */
    fun parseIso(text: String?): Long? {
        val s = text?.trim() ?: return null
        if (s.length < 10) return null
        try {
            val year = s.substring(0, 4).toInt()
            if (s[4] != '-' || s[7] != '-') return null
            val month = s.substring(5, 7).toInt()
            val day = s.substring(8, 10).toInt()
            var hour = 0
            var minute = 0
            var second = 0
            var millis = 0
            var offsetMs: Long? = null
            var i = 10
            if (i < s.length && (s[i] == 'T' || s[i] == 't' || s[i] == ' ')) {
                i++
                if (i + 2 > s.length) return null
                hour = s.substring(i, i + 2).toInt(); i += 2
                if (i < s.length && s[i] == ':') i++
                if (i + 2 <= s.length && s[i].isDigit()) {
                    minute = s.substring(i, i + 2).toInt(); i += 2
                    if (i < s.length && s[i] == ':') i++
                    if (i + 2 <= s.length && s[i].isDigit()) {
                        second = s.substring(i, i + 2).toInt(); i += 2
                        if (i < s.length && (s[i] == '.' || s[i] == ',')) {
                            i++
                            val start = i
                            while (i < s.length && s[i].isDigit()) i++
                            val frac = s.substring(start, i)
                            if (frac.isNotEmpty()) millis = (frac + "000").substring(0, 3).toInt()
                        }
                    }
                }
            }
            if (i < s.length) {
                val c = s[i]
                if (c == 'Z' || c == 'z') {
                    offsetMs = 0
                } else if (c == '+' || c == '-') {
                    val sign = if (c == '-') -1 else 1
                    val rest = s.substring(i + 1).replace(":", "")
                    if (rest.length < 2) return null
                    val oh = rest.substring(0, 2).toInt()
                    val om = if (rest.length >= 4) rest.substring(2, 4).toInt() else 0
                    offsetMs = sign * (oh * 3_600_000L + om * 60_000L)
                } else {
                    return null
                }
            }
            val days = daysFromCivil(year, month, day)
            val wall = days * 86_400_000L + hour * 3_600_000L + minute * 60_000L + second * 1000L + millis
            return wall - (offsetMs ?: LOCAL_OFFSET_MS)
        } catch (e: Throwable) {
            return null
        }
    }

    /** JSON 里的时间字段：数字毫秒或 ISO 字符串（纯数字字符串按毫秒）。 */
    fun parseAny(v: Any?): Long? = when (v) {
        is Number -> v.toLong()
        is String -> v.trim().toLongOrNull() ?: parseIso(v)
        else -> null
    }
}
