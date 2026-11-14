package com.sondo.app.core.utils

import com.sondo.app.core.i18n.tr
import com.sondo.app.core.i18n.trParams
import com.sondo.app.data.models.ModelDate

/**
 * 日期展示（对应 Flutter 侧 DateUtils）。
 * 注意：参考工程的绝对日期固定用英文格式 `MMM d, yyyy`（如 `Nov 3, 2026`），这里保持一致。
 */
object DateUtils {
    /**
     * @param time epoch 毫秒（Long / Int / 数字字符串）或 ISO-8601 字符串；无法解析返回空串
     * 当天内：刚刚 / N 分钟前 / N 小时前；7 天内：N 天前；更早：`MMM d, yyyy`
     */
    fun formatDateTime(time: Any?): String {
        val ms = toEpochMs(time) ?: return ""
        val diff = nowMs() - ms
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
        return ModelDate.formatMMMdyyyy(ms)
    }

    fun toEpochMs(time: Any?): Long? = when (time) {
        null -> null
        is Long -> time
        is Int -> time.toLong()
        is Number -> time.toLong()
        is String -> ModelDate.parseIso(time)
        else -> null
    }

    /** 当前时间的 UTC ISO-8601 串（`2026-01-02T03:04:05.678Z`，对应 Dart `toUtc().toIso8601String()`）。 */
    fun utcIso8601(epochMs: Long = nowMs()): String {
        val p = ModelDate.utcParts(epochMs)
        return "${p.year.toString().padStart(4, '0')}-${pad2(p.month)}-${pad2(p.day)}T" +
            "${pad2(p.hour)}:${pad2(p.minute)}:${pad2(p.second)}.${p.millisecond.toString().padStart(3, '0')}Z"
    }

    private fun pad2(v: Int): String = if (v < 10) "0$v" else v.toString()
}
