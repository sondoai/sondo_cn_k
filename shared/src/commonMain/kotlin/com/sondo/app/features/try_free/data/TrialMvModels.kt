package com.sondo.app.features.try_free.data

import com.sondo.app.data.models.jArr
import com.sondo.app.data.models.jInt
import com.sondo.app.data.models.jLong
import com.sondo.app.data.models.jStr
import com.sondo.app.data.models.jStrOrNull
import com.sondo.app.service.TrialMvStage
import com.sondo.app.service.TrialMvStatus
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

// 试用 MV 的 DTO / 实体（Dart: features/try_free/data/models + domain/entities + data/mappers）。
// TrialMvStatus / TrialMvStage 已在 service/TryFreeTaskService.kt 里，复用不重复定义。

/** 试用 MV 模板 DTO（API `AppTrialMvTemplateRespVO`）。 */
data class TrialMvTemplateModel(
    /** 模板组 ID。 */
    var templateId: Long = 0L,
    /** 模板风格类型，如 flexrap。 */
    var templateType: String = "",
    var templateName: String = "",
    var templateDesc: String = "",
    var templateAvatarUrl: String = "",
    /** 模板封面 URL，列表展示。 */
    var templateCoverUrl: String = "",
    /** 模板性别，如 male / female。 */
    var templateGender: String = "",
    /** 模板预览视频 URL。 */
    var templateVideoUrl: String = "",
    var audioUrl: String = "",
    /** 画面比例，如 9:16。 */
    var imageComp: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("templateId", templateId)
        .put("templateType", templateType)
        .put("templateName", templateName)
        .put("templateDesc", templateDesc)
        .put("templateAvatarUrl", templateAvatarUrl)
        .put("templateCoverUrl", templateCoverUrl)
        .put("templateGender", templateGender)
        .put("templateVideoUrl", templateVideoUrl)
        .put("audioUrl", audioUrl)
        .put("imageComp", imageComp)

    companion object {
        fun fromJson(json: JSONObject): TrialMvTemplateModel = TrialMvTemplateModel(
            templateId = json.jLong("templateId", 0L),
            templateType = json.jStr("templateType"),
            templateName = json.jStr("templateName"),
            templateDesc = json.jStr("templateDesc"),
            templateAvatarUrl = json.jStr("templateAvatarUrl"),
            templateCoverUrl = json.jStr("templateCoverUrl"),
            templateGender = json.jStr("templateGender"),
            templateVideoUrl = json.jStr("templateVideoUrl"),
            audioUrl = json.jStr("audioUrl"),
            imageComp = json.jStr("imageComp"),
        )

        fun listFromJson(arr: JSONArray?): List<TrialMvTemplateModel> {
            if (arr == null) return emptyList()
            val out = ArrayList<TrialMvTemplateModel>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(fromJson(o))
            }
            return out
        }
    }
}

/** 开始试用请求 DTO（API `AppTrialMvStartReqVO`）。 */
data class TrialMvStartRequestModel(
    var templateId: Long = 0L,
    /** 用户头像 URL，写入任务 cover_image。 */
    var coverImage: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("templateId", templateId).put("coverImage", coverImage)
}

/** 开始试用响应 DTO（API `AppTrialMvStartRespVO`）。 */
data class TrialMvStartResultModel(
    var mvId: Long = 0L,
    /** 1 生成中 / 2 完成 / 3 可重试失败 */
    var status: Int = 1,
    /** 1 四视图 / 2 图片 / 3 视频 / 4 合成 */
    var trialStage: Int = 1,
) {
    companion object {
        fun fromJson(json: JSONObject): TrialMvStartResultModel = TrialMvStartResultModel(
            mvId = json.jLong("mvId", 0L),
            status = json.jInt("status", 1),
            trialStage = json.jInt("trialStage", 1),
        )
    }
}

/** 试用 MV 分镜状态。 */
enum class TrialMvShotStatus(val value: Int) {
    generating(1),
    completed(2),
    failed(3);

    val isGenerating: Boolean get() = this == generating
    val isCompleted: Boolean get() = this == completed
    val isFailed: Boolean get() = this == failed

    companion object {
        fun fromValue(value: Int): TrialMvShotStatus = values().firstOrNull { it.value == value } ?: generating
    }
}

/** 试用 MV 任务分镜 DTO（API `ShotItem`）。 */
data class TrialMvShotModel(
    var shotId: Long = 0L,
    var shotNumber: Int = 0,
    var status: Int = 1,
    var shotImgDesc: String = "",
    var shotVideoDesc: String = "",
    var mvShotImg: String? = null,
    var mvShotVideo: String? = null,
) {
    companion object {
        fun fromJson(json: JSONObject): TrialMvShotModel = TrialMvShotModel(
            shotId = json.jLong("shotId", 0L),
            shotNumber = json.jInt("shotNumber", 0),
            status = json.jInt("status", 1),
            shotImgDesc = json.jStr("shotImgDesc"),
            shotVideoDesc = json.jStr("shotVideoDesc"),
            mvShotImg = json.jStrOrNull("mvShotImg"),
            mvShotVideo = json.jStrOrNull("mvShotVideo"),
        )
    }
}

/** 试用 MV 任务详情 DTO（API `AppTrialMvDetailRespVO`）。 */
data class TrialMvDetailModel(
    var mvId: Long = 0L,
    var status: Int = 1,
    var trialStage: Int = 1,
    var trialTemplateId: Long = 0L,
    var coverImage: String = "",
    var fourViewImg: String? = null,
    var firstFrameImg: String? = null,
    var resultUrl: String? = null,
    var audioUrl: String? = null,
    var errorMsg: String? = null,
    var shots: List<TrialMvShotModel> = emptyList(),
) {
    companion object {
        fun fromJson(json: JSONObject): TrialMvDetailModel {
            val arr = json.jArr("shots")
            val shots = ArrayList<TrialMvShotModel>()
            if (arr != null) {
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { shots.add(TrialMvShotModel.fromJson(it)) }
            }
            return TrialMvDetailModel(
                mvId = json.jLong("mvId", 0L),
                status = json.jInt("status", 1),
                trialStage = json.jInt("trialStage", 1),
                trialTemplateId = json.jLong("trialTemplateId", 0L),
                coverImage = json.jStr("coverImage"),
                fourViewImg = json.jStrOrNull("fourViewImg"),
                firstFrameImg = json.jStrOrNull("firstFrameImg"),
                resultUrl = json.jStrOrNull("resultUrl"),
                audioUrl = json.jStrOrNull("audioUrl"),
                errorMsg = json.jStrOrNull("errorMsg"),
                shots = shots,
            )
        }
    }
}

// ------------------------------------------------------------------ 领域实体

/** 试用任务提交结果实体。 */
data class TrialMvTaskEntity(
    val mvId: Long,
    val status: TrialMvStatus,
    val stage: TrialMvStage,
)

/** 试用任务分镜实体。 */
data class TrialMvShotEntity(
    val shotId: Long,
    val shotNumber: Int,
    val status: TrialMvShotStatus,
    val shotImgDesc: String,
    val shotVideoDesc: String,
    val mvShotImg: String? = null,
    val mvShotVideo: String? = null,
)

/** 试用任务详情实体。 */
data class TrialMvDetailEntity(
    val mvId: Long,
    val status: TrialMvStatus,
    val stage: TrialMvStage,
    val trialTemplateId: Long,
    val coverImage: String,
    val fourViewImg: String? = null,
    val firstFrameImg: String? = null,
    val resultUrl: String? = null,
    val audioUrl: String? = null,
    val errorMsg: String? = null,
    val shots: List<TrialMvShotEntity> = emptyList(),
)

/** 模板实体与 DTO 字段一致，直接复用。 */
typealias TrialMvTemplateEntity = TrialMvTemplateModel

/** Try Free 案例实体：轮播中的示例 MV。 */
data class TryFreeCaseEntity(
    val id: String,
    val title: String,
    val description: String,
    /** 预览视频地址（asset 或 http） */
    val videoUrl: String,
    val coverUrl: String,
    /** 卡片左下角头像 */
    val avatarUrl: String,
) {
    companion object {
        fun fromTrialTemplate(template: TrialMvTemplateEntity): TryFreeCaseEntity = TryFreeCaseEntity(
            id = template.templateId.toString(),
            title = template.templateName,
            description = template.templateDesc,
            videoUrl = template.templateVideoUrl,
            coverUrl = template.templateCoverUrl,
            avatarUrl = template.templateAvatarUrl,
        )
    }
}

/** 生成等待页展示状态。 */
enum class TryFreeGenWaitingPageState { loading, failed }

/** DTO → 实体（Dart `TrialMvMapper`）。 */
object TrialMvMapper {
    fun toTaskEntity(model: TrialMvStartResultModel) = TrialMvTaskEntity(
        mvId = model.mvId,
        status = TrialMvStatus.fromValue(model.status),
        stage = TrialMvStage.fromValue(model.trialStage),
    )

    fun toShotEntity(model: TrialMvShotModel) = TrialMvShotEntity(
        shotId = model.shotId,
        shotNumber = model.shotNumber,
        status = TrialMvShotStatus.fromValue(model.status),
        shotImgDesc = model.shotImgDesc,
        shotVideoDesc = model.shotVideoDesc,
        mvShotImg = model.mvShotImg,
        mvShotVideo = model.mvShotVideo,
    )

    fun toDetailEntity(model: TrialMvDetailModel) = TrialMvDetailEntity(
        mvId = model.mvId,
        status = TrialMvStatus.fromValue(model.status),
        stage = TrialMvStage.fromValue(model.trialStage),
        trialTemplateId = model.trialTemplateId,
        coverImage = model.coverImage,
        fourViewImg = model.fourViewImg,
        firstFrameImg = model.firstFrameImg,
        resultUrl = model.resultUrl,
        audioUrl = model.audioUrl,
        errorMsg = model.errorMsg,
        shots = model.shots.map { toShotEntity(it) },
    )

    fun toTryFreeCaseEntityList(templates: List<TrialMvTemplateEntity>): List<TryFreeCaseEntity> =
        templates.map { TryFreeCaseEntity.fromTrialTemplate(it) }
}

/** 本地兜底案例（Dart `TryFreeMockCases`）：模板接口失败或为空时展示。 */
object TryFreeMockCases {
    private const val coverOne =
        "https://cdn1.sondo.ai/3cd4ab7373cc88746760a81d0ce3b478ab410c84438aba9db2e22a16d9e757b4.jpg"
    private const val avatarOne = coverOne
    private const val coverTwo = coverOne

    val all: List<TryFreeCaseEntity> = listOf(
        TryFreeCaseEntity(
            id = "case_1",
            title = "说唱巨星",
            description = "我想要一支嘻哈风格的音乐视频",
            videoUrl = "assets/guide/2.mp4",
            coverUrl = coverOne,
            avatarUrl = avatarOne,
        ),
        TryFreeCaseEntity(
            id = "case_2",
            title = "流行偶像",
            description = "我想要一支流行风格的音乐视频",
            videoUrl = "assets/guide/1.mp4",
            coverUrl = coverTwo,
            avatarUrl = avatarOne,
        ),
    )
}
