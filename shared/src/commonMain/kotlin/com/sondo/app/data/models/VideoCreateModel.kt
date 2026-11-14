package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 分享视频生成任务（Dart: lib/data/models/video_create.dart `VideoCreateModel`）。 */
data class VideoCreateModel(
    /** 分享视频 id */
    var id: Long = 0L,
    /** 分享音乐 id */
    var musicId: Long = 0L,
    /** 分享视频生成状态：1 排队中 2 生成中 3 生成完成 4 生成失败 */
    var shareStatus: Int = 0,
    /** 分享视频音乐生成地址 */
    var shareMusicUrl: String? = null,
    /** 背景视频地址 */
    var videoBackUrl: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("musicId", musicId)
        .put("shareStatus", shareStatus)
        .put("shareMusicUrl", shareMusicUrl)
        .put("videoBackUrl", videoBackUrl)

    companion object {
        fun fromJson(json: JSONObject): VideoCreateModel = VideoCreateModel(
            id = json.jLong("id", 0L),
            musicId = json.jLong("musicId", 0L),
            shareStatus = json.jInt("shareStatus", 0),
            videoBackUrl = json.jStr("videoBackUrl"),
            shareMusicUrl = json.jStrOrNull("shareMusicUrl"),
        )

        /** Dart 的 id / musicId / shareStatus / videoBackUrl 必填，缺失抛异常；这里返回 null。 */
        fun fromJsonOrNull(json: JSONObject?): VideoCreateModel? {
            if (json == null) return null
            for (k in arrayOf("id", "musicId", "shareStatus")) if (json.jRaw(k) !is Number) return null
            if (json.jRaw("videoBackUrl") !is String) return null
            return fromJson(json)
        }

        fun listFromJson(arr: JSONArray?): List<VideoCreateModel> = arr.jMap { fromJson(it) }
    }
}
