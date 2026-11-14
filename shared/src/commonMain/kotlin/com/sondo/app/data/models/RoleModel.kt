package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 四视图角色状态（Dart: lib/data/models/role_model.dart `RoleStatus`）。 */
enum class RoleStatus(val value: Int) {
    /** 排队中 */
    queue(0),

    /** 生成中 */
    generating(1),

    /** 生成成功 */
    success(2),

    /** 生成失败 */
    failed(3);

    companion object {
        fun fromValue(value: Int): RoleStatus = values().firstOrNull { it.value == value } ?: queue
    }
}

/** 四视图角色（Dart: lib/data/models/role_model.dart `RoleModel`）。 */
data class RoleModel(
    var id: Long = 0L,
    var roleImg: String = "",
    var status: RoleStatus = RoleStatus.queue,
    var roleImgDesc: String = "",
    var coverImage: String = "",
    var createTime: Long = 0L,
    var updateTime: Long = 0L,
) {
    val isQueue: Boolean get() = status == RoleStatus.queue
    val isGenerating: Boolean get() = status == RoleStatus.generating
    val isFailed: Boolean get() = status == RoleStatus.failed
    val isSuccess: Boolean get() = status == RoleStatus.success

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("roleImg", roleImg)
        .put("roleImgDesc", roleImgDesc)
        .put("coverImage", coverImage)
        .put("createTime", createTime)
        .put("updateTime", updateTime)
        .put("status", status.value)

    companion object {
        fun fromJson(json: JSONObject): RoleModel = RoleModel(
            id = json.jLong("id", 0L),
            roleImg = json.jStr("roleImg"),
            roleImgDesc = json.jStr("roleImgDesc"),
            coverImage = json.jStr("coverImage"),
            createTime = json.jLong("createTime", 0L),
            updateTime = json.jLong("updateTime", 0L),
            status = RoleStatus.fromValue(json.jInt("status", 0)),
        )

        fun listFromJson(arr: JSONArray?): List<RoleModel> = arr.jMap { fromJson(it) }
    }
}
