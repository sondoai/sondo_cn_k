package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 上传条目的 UI 状态（Dart: lib/data/models/audio_upload_model.dart `AudioItemStatus`）。 */
enum class AudioItemStatus {
    waiting,
    preparing,
    uploading,
    paused,
    failed,
    completed,
    deleting,
    canceled;

    val value: String get() = name

    val isActive: Boolean get() = this == waiting || this == preparing || this == uploading

    companion object {
        fun fromValue(value: String): AudioItemStatus = values().firstOrNull { it.name == value } ?: waiting
    }
}

/** 上传列表中的单条记录（Dart: lib/data/models/audio_upload_model.dart `AudioUploadItem`）。 */
data class AudioUploadItem(
    var id: Long = 0L,
    /** 本地任务唯一标识（本地任务为本地 UUID，服务端记录为服务端 uploadId）。 */
    var uploadId: String = "",
    var fileName: String = "",
    var fileSize: Long = 0L,
    var uploadedBytes: Long = 0L,
    var status: AudioItemStatus = AudioItemStatus.waiting,
    var fileUrl: String? = null,
    var lastError: String? = null,
    /** epoch 毫秒 */
    var createdAt: Long? = null,
    /** 时长（毫秒） */
    var duration: Int? = null,
) {
    val progress: Double
        get() = if (fileSize > 0) (uploadedBytes.toDouble() / fileSize.toDouble()).coerceIn(0.0, 1.0) else 0.0

    /** 用于 UI 播放态匹配的唯一键（服务端记录 uploadId 可能为空）。 */
    val playbackKey: String get() = if (uploadId.isNotEmpty()) uploadId else "id_$id"

    /** 判断是否为同一条记录（本地任务用 uploadId，服务端记录用 id）。 */
    fun isSameRecord(other: AudioUploadItem): Boolean {
        if (id > 0 && other.id > 0) return id == other.id
        if (uploadId.isNotEmpty() && other.uploadId.isNotEmpty()) return uploadId == other.uploadId
        return false
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("uploadId", uploadId)
        .put("fileName", fileName)
        .put("fileSize", fileSize)
        .put("uploadedBytes", uploadedBytes)
        .put("status", status.name)
        .put("fileUrl", fileUrl)
        .put("lastError", lastError)
        .put("createdAt", createdAt?.let { isoLocal(it) })
        .put("duration", duration)

    companion object {
        /** 从服务端列表接口（/infra/file/my-audios/page）的 JSON 构造；有 fileUrl 即视为已完成。 */
        fun fromJson(json: JSONObject): AudioUploadItem {
            val url = json.jStrOrNull("fileUrl")
            val status = if (!url.isNullOrEmpty()) {
                AudioItemStatus.completed
            } else {
                json.jStrOrNull("status")?.let { AudioItemStatus.fromValue(it) } ?: AudioItemStatus.waiting
            }
            return AudioUploadItem(
                id = json.jLong("id", 0L),
                uploadId = json.jStr("uploadId"),
                fileName = json.jStr("fileName"),
                fileSize = json.jLong("fileSize", 0L),
                uploadedBytes = json.jLong("uploadedBytes", 0L),
                status = status,
                fileUrl = url,
                lastError = json.jStrOrNull("lastError"),
                createdAt = ModelDate.parseAny(json.jRaw("createdAt")),
                duration = json.jIntOrNull("duration"),
            )
        }

        fun listFromJson(arr: JSONArray?): List<AudioUploadItem> = arr.jMap { fromJson(it) }

        /** 本地新建上传任务时构造，uploadId 使用本地任务 ID。 */
        fun local(taskId: String, fileName: String, fileSize: Long, durationMs: Int? = null): AudioUploadItem =
            AudioUploadItem(
                id = 0L,
                uploadId = taskId,
                fileName = fileName,
                fileSize = fileSize,
                uploadedBytes = 0L,
                status = AudioItemStatus.waiting,
                duration = durationMs,
                createdAt = ModelDate.now(),
            )

        /** Dart 本地时间的 toIso8601String：`yyyy-MM-ddTHH:mm:ss.SSS`（无时区后缀）。 */
        private fun isoLocal(ms: Long): String {
            val p = ModelDate.localParts(ms)
            val mmm = p.millisecond.toString().padStart(3, '0')
            return "${p.year}-${ModelDate.pad2(p.month)}-${ModelDate.pad2(p.day)}T" +
                "${ModelDate.pad2(p.hour)}:${ModelDate.pad2(p.minute)}:${ModelDate.pad2(p.second)}.$mmm"
        }
    }
}
