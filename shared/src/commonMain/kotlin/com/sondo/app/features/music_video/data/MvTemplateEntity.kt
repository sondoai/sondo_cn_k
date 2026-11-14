package com.sondo.app.features.music_video.data

import com.sondo.app.data.models.MusicStyleModel
import com.sondo.app.data.models.MvTempPace
import com.sondo.app.data.models.MvTempType
import com.sondo.app.data.models.MvTemplateModel
import com.sondo.app.data.models.jInt
import com.sondo.app.data.models.jLong
import com.sondo.app.data.models.jStr
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * MV 风格模板配置实体（Dart: features/music_video/domain/entities/mv_template_entity.dart `MvTemplateEntity`）。
 * 镜头风格 / 节奏枚举与数据层共用 [MvTempType] / [MvTempPace]（取值、标签完全一致）。
 */
data class MvTemplateEntity(
    var id: Long = -1L,
    var templateType: String = "",
    /** 选中模板封面 URL（来自列表 label，供创作页展示）。 */
    var templateCover: String = "",
    var shotStyle: MvTempType = MvTempType.none,
    var cameraPace: MvTempPace = MvTempPace.none,
    var emotionalArc: String = "",
    var emotion: String = "",
    var description: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("template_type", templateType)
        .put("template_cover", templateCover)
        .put("shot_style", MvTempType.getValue(shotStyle))
        .put("camera_pace", MvTempPace.getValue(cameraPace))
        .put("emotional_arc", emotionalArc)
        .put("emotion", emotion)
        .put("description", description)

    companion object {
        /** 草稿里的模板 JSON（[toJson] 的逆过程）。 */
        fun fromDraftJson(t: JSONObject): MvTemplateEntity = MvTemplateEntity(
            id = t.jLong("id", -1L),
            templateType = t.jStr("template_type"),
            templateCover = t.jStr("template_cover"),
            shotStyle = MvTempType.getEnum(t.jStr("shot_style")),
            cameraPace = MvTempPace.getEnum(t.jStr("camera_pace")),
            emotionalArc = t.jStr("emotional_arc"),
            emotion = t.jStr("emotion"),
            description = t.jStr("description"),
        )
    }
}

/** MV 模板 DTO ↔ Entity（Dart: data/mappers/mv_template_mapper.dart `MvTemplateMapper`）。 */
object MvTemplateMapper {
    fun toEntity(model: MvTemplateModel): MvTemplateEntity = MvTemplateEntity(
        id = model.id,
        templateType = model.template_type,
        shotStyle = MvTempType.getEnum(model.shot_style.value),
        cameraPace = MvTempPace.getEnum(model.camera_pace.value),
        emotionalArc = model.emotional_arc,
        emotion = model.emotion,
        description = model.description,
    )

    fun toModel(entity: MvTemplateEntity): MvTemplateModel = MvTemplateModel(
        id = entity.id,
        template_type = entity.templateType,
        shot_style = MvTempType.getEnum(entity.shotStyle.value),
        camera_pace = MvTempPace.getEnum(entity.cameraPace.value),
        emotional_arc = entity.emotionalArc,
        emotion = entity.emotion,
        description = entity.description,
    )
}

/** MV 风格模板实体（Dart: domain/entities/mv_style_template_entity.dart `MvStyleTemplateEntity`）。 */
data class MvStyleTemplateEntity(
    var id: Long = 0L,
    /** 模板预览视频 / 图 URL（字典 label）。 */
    var previewUrl: String = "",
    /** 模板展示备注。 */
    var remark: String = "",
    /** 模板配置（含 template_type、description 等）。 */
    var template: MvTemplateEntity = MvTemplateEntity(),
)

/** Dart: data/mappers/mv_style_template_mapper.dart `MvStyleTemplateMapper`。 */
object MvStyleTemplateMapper {
    fun toEntity(model: MusicStyleModel): MvStyleTemplateEntity = MvStyleTemplateEntity(
        id = model.id,
        previewUrl = model.label,
        remark = model.remark ?: "",
        template = MvTemplateMapper.toEntity(model.template),
    )

    fun toEntityList(models: List<MusicStyleModel>): List<MvStyleTemplateEntity> = models.map { toEntity(it) }
}

/** MV 生成任务提交结果（Dart: domain/entities/mv_gen_task_entity.dart `MvGenTaskEntity`）。 */
data class MvGenTaskEntity(val id: Long)
