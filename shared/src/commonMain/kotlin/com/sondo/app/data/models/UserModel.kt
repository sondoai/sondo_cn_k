package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** Dart: lib/data/models/user_model.dart `UserType`。 */
enum class UserType { google, email, discord, apple, facebook }

/** 用户信息（Dart: lib/data/models/user_model.dart `UserModel`）。 */
data class UserModel(
    var id: Long = 0L,
    var email: String = "",
    var nickname: String = "",
    var avatar: String = "",
    var mobile: String = "",
    var sex: Int = 1,
    var point: Int = 0,
    var experience: Int = 0,
    var expireDate: Long? = null,
    /** 会员类型 1-非会员 2-周会员 3-月会员 4-年会员 */
    var memberType: Int = 1,
    var brokerageEnabled: Boolean = false,
    var deviceVersion: String? = null,
    var uid: String = "",
    /** 是否分享获取积分 */
    var isShareGetPoint: Boolean = false,
    /** 分享积分 */
    var sharePoint: Int? = 10,
    /** 创建歌曲所需积分 */
    var createMusicPoint: Int = 0,
    /** 推荐注册码 */
    var regCode: String = "",
    /** 上级推荐注册码 */
    var superRegCode: String = "",
    /** 剩余试用次数（新版未用=1、已用=0；旧版固定 0） */
    var hasTried: Int = 0,
    /** 是否已使用首次免费特效：1 已用，0 未用 */
    var isUseCapcut: Int = 1,
    /** 订阅页版本：0 旧版，1 新版 */
    var personalCenterVersion: Int = 0,
    /** 设置页「AI生成水印」。不是明确的 false 时按开启。 */
    var aigcWatermarkEnabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("email", email)
        .put("nickname", nickname)
        .put("avatar", avatar)
        .put("mobile", mobile)
        .put("sex", sex)
        .put("point", point)
        .put("experience", experience)
        .put("expireDate", expireDate)
        .put("memberType", memberType)
        .put("brokerageEnabled", brokerageEnabled)
        .put("deviceVersion", deviceVersion)
        .put("uid", uid)
        .put("isShareGetPoint", isShareGetPoint)
        .put("sharePoint", sharePoint)
        .put("createMusicPoint", createMusicPoint)
        .put("regCode", regCode)
        .put("superRegCode", superRegCode)
        .put("hasTried", hasTried)
        .put("isUseCapcut", isUseCapcut)
        .put("personalCenterVersion", personalCenterVersion)
        .put("aigcWatermarkEnabled", aigcWatermarkEnabled)

    companion object {
        /** 宽松解析：缺 id 时为 0。 */
        fun fromJson(json: JSONObject): UserModel = UserModel(
            id = json.jLong("id", 0L),
            email = json.jStr("email"),
            nickname = json.jStr("nickname"),
            avatar = json.jStr("avatar"),
            mobile = json.jStr("mobile"),
            sex = json.jInt("sex", 1),
            point = json.jInt("point", 0),
            experience = json.jInt("experience", 0),
            expireDate = json.jLongOrNull("expireDate"),
            brokerageEnabled = json.jBool("brokerageEnabled", false),
            memberType = json.jInt("memberType", 1),
            deviceVersion = json.jStrOrNull("deviceVersion"),
            uid = json.jStr("uid"),
            isShareGetPoint = json.jBool("isShareGetPoint", false),
            createMusicPoint = json.jInt("createMusicPoint", 0),
            sharePoint = json.jIntOrNull("sharePoint") ?: 10,
            regCode = json.jStr("regCode"),
            superRegCode = json.jStr("superRegCode"),
            hasTried = json.jInt("hasTried", 0),
            isUseCapcut = json.jInt("isUseCapcut", 1),
            personalCenterVersion = json.jInt("personalCenterVersion", 0),
            aigcWatermarkEnabled = json.jBool("aigcWatermarkEnabled", true),
        )

        /** Dart 的 `id` 是必填数字，缺失时 fromJson 抛异常；这里返回 null。 */
        fun fromJsonOrNull(json: JSONObject?): UserModel? {
            if (json == null) return null
            if (jsonValueOrNull(json.opt("id")) !is Number) return null
            return fromJson(json)
        }

        fun listFromJson(arr: JSONArray?): List<UserModel> = arr.jMap { fromJson(it) }
    }
}

/** 积分流水（Dart: lib/data/models/user_model.dart `CreditRecordModel`）。 */
data class CreditRecordModel(
    var title: String = "",
    var id: Long = 0L,
    var description: String = "",
    var createTime: Long = 0L,
    /** 正数表示增加，负数表示减少 */
    var point: Int = 0,
) {
    val formattedTimestamp: String get() = ModelDate.formatMMMdyyyy(createTime)

    val formattedAmount: String get() = if (point > 0) "+$point" else "$point"

    val isSpending: Boolean get() = point < 0

    fun toJson(): JSONObject = JSONObject()
        .put("title", title)
        .put("id", id)
        .put("description", description)
        .put("createTime", createTime)
        .put("point", point)

    companion object {
        fun fromJson(json: JSONObject): CreditRecordModel = CreditRecordModel(
            title = json.jStr("title"),
            id = json.jLong("id"),
            description = json.jStr("description"),
            createTime = json.jLong("createTime"),
            point = json.jInt("point"),
        )

        /** Dart 的五个字段全部必填（`as String` / `as int`），任一缺失抛异常；这里返回 null。 */
        fun fromJsonOrNull(json: JSONObject?): CreditRecordModel? {
            if (json == null) return null
            return parseOrNull {
                CreditRecordModel(
                    title = json.reqStr("title"),
                    id = json.reqLong("id"),
                    description = json.reqStr("description"),
                    createTime = json.reqLong("createTime"),
                    point = json.reqInt("point"),
                )
            }
        }

        fun listFromJson(arr: JSONArray?): List<CreditRecordModel> = arr.jMap { fromJson(it) }
    }
}
