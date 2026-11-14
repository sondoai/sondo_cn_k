package com.sondo.app.data.models

import com.sondo.app.core.i18n.tr
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** MV 标签（Dart: lib/data/models/music_style.dart `MvTagLabelModel`）。 */
data class MvTagLabelModel(
    var id: String = "",
    var label: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("label", label)

    companion object {
        fun fromJson(json: JSONObject): MvTagLabelModel = MvTagLabelModel(json.jStr("id"), json.jStr("label"))

        /** Dart 两个字段都是必填字符串，缺失抛异常；这里返回 null。 */
        fun fromJsonOrNull(json: JSONObject?): MvTagLabelModel? {
            if (json == null) return null
            return parseOrNull { MvTagLabelModel(json.reqStr("id"), json.reqStr("label")) }
        }

        fun listFromJson(arr: JSONArray?): List<MvTagLabelModel> = arr.jMap { fromJson(it) }
    }
}

/** 字典数据条目（Dart: lib/data/models/music_style.dart `MusicStyleModel`）。 */
data class MusicStyleModel(
    var id: Long = 0L,
    var label: String = "",
    var value: String = "",
    var dictType: String = "",
    var remark: String? = "",
    var cssClass: String? = "",
    var colorType: String = "",
) {
    /** 首页推荐视频 webp 图片 */
    val webpUrls: String
        get() {
            val r = remark
            if (r != null && r.contains(",")) return r.split(",").first()
            return ""
        }

    /** 首页推荐视频图片 */
    val imageUrl: String
        get() {
            val r = remark
            if (r != null && r.contains(",")) return r.split(",").last()
            return ""
        }

    /** 模板所需积分 */
    val point: Int
        get() {
            if (colorType.contains(",")) {
                return colorType.split(",").last().trim().toIntOrNull() ?: 0
            }
            return 0
        }

    val typeStr: String
        get() {
            if (colorType.contains(",")) return colorType.split(",").first().trim()
            return colorType.trim()
        }

    /** value 是模板 JSON 字符串；解析失败返回 id=0 的空模板。 */
    val template: MvTemplateModel
        get() {
            val json = parseJsonObjectOrNull(value) ?: return MvTemplateModel(id = 0L)
            val t = MvTemplateModel.fromJsonOrNull(json) ?: return MvTemplateModel(id = 0L)
            return t.copy(id = id)
        }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("value", value)
        .put("dictType", dictType)
        .put("remark", remark)
        .put("cssClass", cssClass)
        .put("colorType", colorType)

    companion object {
        fun fromJson(json: JSONObject): MusicStyleModel = MusicStyleModel(
            id = json.jLong("id", 0L),
            label = json.jStr("label"),
            value = json.jStr("value"),
            dictType = json.jStr("dictType"),
            remark = json.jStr("remark"),
            cssClass = json.jStr("cssClass"),
            colorType = json.jStr("colorType"),
        )

        /** Dart 的 id / label / value / dictType 必填，缺失抛异常；这里返回 null。 */
        fun fromJsonOrNull(json: JSONObject?): MusicStyleModel? {
            if (json == null) return null
            return parseOrNull {
                MusicStyleModel(
                    id = json.reqLong("id"),
                    label = json.reqStr("label"),
                    value = json.reqStr("value"),
                    dictType = json.reqStr("dictType"),
                    remark = json.jStr("remark"),
                    cssClass = json.jStr("cssClass"),
                    colorType = json.jStr("colorType"),
                )
            }
        }

        fun listFromJson(arr: JSONArray?): List<MusicStyleModel> = arr.jMap { fromJson(it) }

        /** 严格列表：任一条解析失败整体为 null（Dart 整个列表解析抛异常）。 */
        fun listFromJsonOrNull(arr: JSONArray?): List<MusicStyleModel>? = arr.jMapStrict { fromJsonOrNull(it) }
    }
}

/** MV 模板镜头风格（Dart: lib/data/models/music_style.dart `MvTempType`）。 */
enum class MvTempType(val value: String, val label: String) {
    none("", ""),
    defaul("default", "default"),
    vocal("lip_sync", "sing"),
    scenic("scene", "scenic");

    companion object {
        fun getEnum(value: String): MvTempType = values().firstOrNull { it.value == value } ?: none
        fun fromValue(value: String): MvTempType = getEnum(value)
        fun getValue(type: MvTempType): String = type.value
        fun getLabel(type: MvTempType): String = type.label.tr
    }
}

/** camera_pace 视频速度（Dart: lib/data/models/music_style.dart `MvTempPace`）。 */
enum class MvTempPace(val value: String, val label: String) {
    none("", ""),
    defaul("normal", "normal"),
    vocal("slow", "slow"),
    scenic("fast", "fast");

    companion object {
        fun getEnum(value: String): MvTempPace = values().firstOrNull { it.value == value } ?: none
        fun fromValue(value: String): MvTempPace = getEnum(value)
        fun getValue(type: MvTempPace): String = type.value
        fun getLabel(type: MvTempPace): String = type.label.tr
    }
}

/**
 * MV 模板（Dart: lib/data/models/music_style.dart `MvTemplateModel`）。
 * 字段名沿用后端的 snake_case（从字典 value 的 JSON 字符串里解析）。
 */
data class MvTemplateModel(
    var id: Long = -1L,
    var template_type: String = "",
    var shot_style: MvTempType = MvTempType.none,
    var camera_pace: MvTempPace = MvTempPace.none,
    var emotional_arc: String = "",
    var emotion: String = "",
    var description: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("template_type", template_type)
        .put("shot_style", MvTempType.getValue(shot_style))
        .put("camera_pace", MvTempPace.getValue(camera_pace))
        .put("emotional_arc", emotional_arc)
        .put("emotion", emotion)
        .put("description", description)

    companion object {
        fun fromJson(json: JSONObject): MvTemplateModel = MvTemplateModel(
            id = json.jLong("id", -1L),
            template_type = json.jStr("template_type"),
            shot_style = MvTempType.getEnum(json.jStr("shot_style")),
            camera_pace = MvTempPace.getEnum(json.jStr("camera_pace")),
            emotional_arc = json.jStr("emotional_arc"),
            emotion = json.jStr("emotion"),
            description = json.jStr("description"),
        )

        /** Dart 对字符串字段用 `as String?` 强转，类型不对时抛异常；这里返回 null。 */
        fun fromJsonOrNull(json: JSONObject?): MvTemplateModel? {
            if (json == null) return null
            for (k in arrayOf("template_type", "shot_style", "camera_pace", "emotional_arc", "emotion", "description")) {
                val v = json.jRaw(k)
                if (v != null && v !is String) return null
            }
            val idRaw = json.jRaw("id")
            if (idRaw != null && idRaw !is Number) return null
            return fromJson(json)
        }

        fun listFromJson(arr: JSONArray?): List<MvTemplateModel> = arr.jMap { fromJson(it) }
    }
}
