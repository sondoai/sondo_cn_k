package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 邀请奖励记录（Dart: lib/data/models/recommend_model.dart `RecommendModel`）。 */
data class RecommendModel(
    var id: Long = 0L,
    /** 得到积分的用户 id */
    var userId: Long = 0L,
    /** 得到积分的用户昵称 */
    var userNickName: String = "",
    /** 上级/下级用户 id */
    var recommdUserId: Long = 0L,
    /** 上级/下级用户昵称 */
    var recommdUserName: String = "",
    /** 获取积分 */
    var point: Int = 0,
    /** 推荐描述 */
    var remark: String = "",
    /** 创建时间 */
    var createTime: Long = 0L,
) {
    val createTimeStr: String get() = ModelDate.formatMMMdyyyy(createTime)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("userId", userId)
        .put("userNickName", userNickName)
        .put("recommdUserId", recommdUserId)
        .put("recommdUserName", recommdUserName)
        .put("point", point)
        .put("remark", remark)
        .put("createTime", createTime)

    companion object {
        fun fromJson(json: JSONObject): RecommendModel = RecommendModel(
            id = json.jLong("id", 0L),
            userId = json.jLong("userId", 0L),
            userNickName = json.jStr("userNickName"),
            recommdUserId = json.jLong("recommdUserId", 0L),
            recommdUserName = json.jStr("recommdUserName"),
            point = json.jInt("point", 0),
            remark = json.jStr("remark"),
            createTime = json.jLong("createTime", 0L),
        )

        fun listFromJson(arr: JSONArray?): List<RecommendModel> = arr.jMap { fromJson(it) }
    }
}
