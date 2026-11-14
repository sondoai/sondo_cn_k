package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 通知消息跳转类型（Dart: lib/shared/services/message_action.dart `MessageNoticeAction`）。
 * 纯数据枚举，放在 models 里供 [MessageModel] 使用；跳转处理在 service 层。
 */
enum class MessageNoticeAction(val key: String) {
    /** 音乐成片 */
    openMusicPage("musicDetail"),

    /** mv 成片 */
    openMvPage("mvDetail"),

    /** mv 信息流 */
    openThinkLineV2("thinkLineV2"),

    /** 创建失败 */
    createError("createError"),

    /** 推送消息 */
    openPushMessage("pushLandingPage"),

    /** 不处理 */
    none("none");

    companion object {
        /** 解析从后台传来的值 */
        fun parse(v: String?): MessageNoticeAction {
            if (v == null) return none
            return values().firstOrNull { it.key == v } ?: none
        }

        fun fromValue(v: String?): MessageNoticeAction = parse(v)
    }
}

/** 推送消息的跳转信息（Dart: lib/data/models/message_model.dart `MessageModel`）。 */
data class MessageModel(
    var params: JSONObject? = null,
    var action: MessageNoticeAction = MessageNoticeAction.none,
    /** jumpParams.isTrial 为 true 时进试看播放页。缺失或 false 走原详情页。 */
    var isTrial: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("jumpParams", params?.toString())
        .put("jumpAddress", action.key)

    companion object {
        /** jumpParams 是 JSON 字符串；jumpAddress 是跳转类型。 */
        fun fromJson(json: JSONObject?): MessageModel {
            val raw = json?.jRaw("jumpParams")
            val params: JSONObject? = when (raw) {
                is JSONObject -> raw
                is String -> parseJsonObjectOrNull(raw)
                else -> null
            }
            return MessageModel(
                params = params,
                action = MessageNoticeAction.parse(json?.jRaw("jumpAddress") as? String),
                isTrial = pushTrial(params?.jRaw("isTrial")),
            )
        }

        /**
         * Dart 对 jumpParams 直接 jsonDecode：不是合法 JSON（或不是字符串）时抛异常，调用方不处理这条消息。
         * 这里返回 null。
         */
        fun fromJsonOrNull(json: JSONObject?): MessageModel? {
            val raw = json?.jRaw("jumpParams")
            if (raw != null) {
                if (raw !is String) return null
                val t = raw.trim()
                if (t.isEmpty()) return null
                if (t.startsWith("{")) {
                    if (parseJsonObjectOrNull(t) == null) return null
                } else {
                    // 合法 JSON 但不是对象（数组 / 数字 / 字符串 / 字面量）时 Dart 得到 params=null，不抛
                    val c = t[0]
                    val literal = t == "true" || t == "false" || t == "null"
                    if (!(c == '[' || c == '"' || c == '-' || c.isDigit() || literal)) return null
                }
            }
            val addr = json?.jRaw("jumpAddress")
            if (addr != null && addr !is String) return null
            return fromJson(json)
        }

        /** jumpParams 里的 isTrial。true / 1 才进试看页。 */
        private fun pushTrial(value: Any?): Boolean {
            if (value == true) return true
            if (value is Number) return value.toDouble() == 1.0
            if (value is String) {
                val text = value.trim().lowercase()
                return text == "true" || text == "1"
            }
            return false
        }

        fun listFromJson(arr: JSONArray?): List<MessageModel> = arr.jMap { fromJson(it) }
    }
}

/** 推送落地页内容（Dart: lib/data/models/message_model.dart `PushMessageModel`）。 */
data class PushMessageModel(
    var contentTitle: String = "",
    var bodyContent: String = "",
    var mediaUrl: String = "",
    /** 1 image / 2 video */
    var mediaType: Int = 1,
    var author: String = "",
    var buttonUrl: String = "",
    var buttonText: String = "",
    var createTime: Long = 0L,
) {
    val createTimeStr: String get() = ModelDate.formatMMMdyyyy(createTime)

    fun toJson(): JSONObject = JSONObject()
        .put("contentTitle", contentTitle)
        .put("bodyContent", bodyContent)
        .put("mediaUrl", mediaUrl)
        .put("mediaType", mediaType)
        .put("author", author)
        .put("buttonUrl", buttonUrl)
        .put("buttonText", buttonText)
        .put("createTime", createTime)

    companion object {
        fun fromJson(json: JSONObject): PushMessageModel = PushMessageModel(
            contentTitle = json.jStr("contentTitle"),
            bodyContent = json.jStr("bodyContent"),
            mediaUrl = json.jStr("mediaUrl"),
            mediaType = json.jInt("mediaType", 1),
            author = json.jStr("author"),
            buttonUrl = json.jStr("buttonUrl"),
            buttonText = json.jStr("buttonText"),
            createTime = json.jLong("createTime", 0L),
        )

        fun listFromJson(arr: JSONArray?): List<PushMessageModel> = arr.jMap { fromJson(it) }
    }
}
