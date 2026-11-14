package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 音乐风格标签实体（Dart: lib/shared/domain/entities/music_style_entity.dart `MusicStyleEntity`）。 */
data class MusicStyleEntity(
    var id: Long = 0L,
    /** 展示名称。 */
    var label: String = "",
    /** 提交给后端的值。 */
    var value: String = "",
    var remark: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("value", value)
        .put("remark", remark)

    companion object {
        fun fromJson(json: JSONObject): MusicStyleEntity = MusicStyleEntity(
            id = json.jLong("id", 0L),
            label = json.jStr("label"),
            value = json.jStr("value"),
            remark = json.jStrOrNull("remark"),
        )

        fun listFromJson(arr: JSONArray?): List<MusicStyleEntity> = arr.jMap { fromJson(it) }
    }
}

/** Dart: lib/shared/data/mappers/music_style_mapper.dart。单向映射，丢弃 dictType / cssClass / colorType。 */
fun MusicStyleModel.toEntity(): MusicStyleEntity = MusicStyleEntity(
    id = id,
    label = label,
    value = value,
    remark = remark,
)

fun List<MusicStyleModel>.toEntityList(): List<MusicStyleEntity> = map { it.toEntity() }

/** 与 Dart 同名的静态入口。 */
object MusicStyleMapper {
    fun toEntity(model: MusicStyleModel): MusicStyleEntity = model.toEntity()
    fun toEntityList(models: List<MusicStyleModel>): List<MusicStyleEntity> = models.map { it.toEntity() }
}
