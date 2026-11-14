package com.sondo.app.data.models

/** 作品发布状态（Dart: lib/data/models/common_model.dart `VideoPublishStatus`）。 */
enum class VideoPublishStatus(val value: Int, val label: String) {
    /** 未发布 */
    unpublish(0, "unpublish"),

    /** 已发布 */
    published(1, "published"),

    /** 审核中 */
    auditing(2, "auditing"),

    /** 审核失败 */
    auditFailed(3, "auditFailed");

    companion object {
        fun fromValue(value: Int): VideoPublishStatus = values().firstOrNull { it.value == value } ?: unpublish

        fun toValue(status: VideoPublishStatus): Int = status.value

        /** 兼容数字、数字字符串、枚举名。 */
        fun parse(value: Any?): VideoPublishStatus {
            if (value is VideoPublishStatus) return value
            if (value is Number) return fromValue(value.toInt())
            if (value is String) {
                val key = value.trim()
                if (key.isEmpty()) return unpublish
                val intVal = key.toIntOrNull()
                if (intVal != null) return fromValue(intVal)
                val lower = key.lowercase()
                return values().firstOrNull { it.label.lowercase() == lower } ?: unpublish
            }
            return unpublish
        }
    }
}
