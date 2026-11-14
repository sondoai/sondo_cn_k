package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 分镜重生历史（Dart: lib/data/models/shot_history.dart `ShotHistory`）。 */
data class ShotHistory(
    var id: Long = 0L,
    var shotCustomId: Long = 0L,
    var historySeq: Int = 0,
    var mvId: Long = 0L,
    var shotNumber: Int = 0,
    var shotType: Int = 0,
    var timestamp: String = "",
    var shotImgDesc: String? = null,
    var mvShotImg: String = "",
    var status: Int = 0,
    var mvShotVideo: String = "",
    var firstFrameImg: String? = null,
    var duration: Int = 0,
    var durationV1: Double = 0.0,
    /** 参考图列表 逗号分隔 */
    var referenceImgs: String? = "",
    var shotVideoDesc: String? = null,
    var taskId: String? = null,
    var shotId: String? = null,
    var quality: Int = 0,
    var createTime: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("shotCustomId", shotCustomId)
        .put("historySeq", historySeq)
        .put("mvId", mvId)
        .put("shotNumber", shotNumber)
        .put("shotType", shotType)
        .put("timestamp", timestamp)
        .put("shotImgDesc", shotImgDesc)
        .put("mvShotImg", mvShotImg)
        .put("status", status)
        .put("mvShotVideo", mvShotVideo)
        .put("firstFrameImg", firstFrameImg)
        .put("duration", duration)
        .put("durationV1", durationV1)
        .put("referenceImgs", referenceImgs)
        .put("shotVideoDesc", shotVideoDesc)
        .put("taskId", taskId)
        .put("shotId", shotId)
        .put("quality", quality)
        .put("createTime", createTime)

    companion object {
        fun fromJson(json: JSONObject): ShotHistory = ShotHistory(
            id = json.jLong("id"),
            shotCustomId = json.jLong("shotCustomId"),
            historySeq = json.jInt("historySeq"),
            mvId = json.jLong("mvId"),
            shotNumber = json.jInt("shotNumber"),
            shotType = json.jInt("shotType"),
            timestamp = json.jStr("timestamp"),
            shotImgDesc = json.jStr("shotImgDesc"),
            mvShotImg = json.jStr("mvShotImg"),
            status = json.jInt("status"),
            mvShotVideo = json.jStr("mvShotVideo"),
            quality = json.jInt("quality"),
            createTime = json.jLong("createTime"),
            firstFrameImg = json.jStrOrNull("firstFrameImg"),
            duration = json.jInt("duration"),
            durationV1 = json.jDbl("durationV1"),
            shotVideoDesc = json.jStrOrNull("shotVideoDesc"),
            taskId = json.jStrOrNull("taskId"),
            shotId = json.jStrOrNull("shotId"),
            referenceImgs = json.jStrOrNull("referenceImgs"),
        )

        /**
         * Dart 的 fromJson 没有空值兜底：id / shotCustomId / historySeq / mvId / shotNumber / shotType /
         * status / quality / createTime / duration（整数）、durationV1（数字）、timestamp（字符串）任一缺失，
         * 或 taskId / shotId 不是字符串，都会抛异常，整页历史变成空列表。这里返回 null。
         */
        fun fromJsonOrNull(json: JSONObject?): ShotHistory? {
            if (json == null) return null
            for (k in arrayOf(
                "id", "shotCustomId", "historySeq", "mvId", "shotNumber", "shotType",
                "status", "quality", "createTime", "duration", "durationV1",
            )) {
                if (json.jRaw(k) !is Number) return null
            }
            if (json.jRaw("timestamp") !is String) return null
            for (k in arrayOf("firstFrameImg", "shotVideoDesc", "taskId", "shotId", "referenceImgs")) {
                val v = json.jRaw(k)
                if (v != null && v !is String) return null
            }
            return fromJson(json)
        }

        fun listFromJson(arr: JSONArray?): List<ShotHistory> = arr.jMap { fromJson(it) }

        /** 严格列表：任一条解析失败整体为 null。 */
        fun listFromJsonOrNull(arr: JSONArray?): List<ShotHistory>? = arr.jMapStrict { fromJsonOrNull(it) }
    }
}
