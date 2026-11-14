package com.sondo.app.features.music_video.data

import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiResult
import com.sondo.app.data.api.DictDataApi
import com.sondo.app.data.api.FileUploadApi
import com.sondo.app.data.api.ModerationApi
import com.sondo.app.data.api.PolishStreamApi
import com.sondo.app.data.api.StreamHandle
import com.sondo.app.data.api.badResponse
import com.sondo.app.data.api.dataObject
import com.sondo.app.data.models.ImageUploadResult
import com.sondo.app.data.models.MusicStyleEntity
import com.sondo.app.data.models.MvModel
import com.sondo.app.data.models.VideoGenParams
import com.sondo.app.data.models.parseJsonObjectOrNull
import com.sondo.app.data.models.toEntityList
import com.sondo.app.data.models.toJsonArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * MV 生成：数据源 + 仓库合并（Dart: features/music_video/data/datasources/music_video_gen_remote_datasource.dart、
 * data/repositories/music_video_gen_repository_impl.dart）。
 */
object MusicVideoGenApi {
    private const val mvStyleType = "music_mv_style_v2"

    /**
     * 提交 MV 自定义流程 V2（`POST /member/musicMvTask/genMvCustomTaskFlowV2`）。
     * 封面只传 coverImages 对象数组（空则不传），不再带 fourViewIds。
     */
    suspend fun createMvTask(params: VideoGenParams): ApiResult<MvGenTaskEntity> {
        val body = JSONObject()
            .put("musicId", params.musicId)
            .put("title", params.title)
            .put("artist", params.artist)
        if (params.coverImages.isNotEmpty()) body.put("coverImages", params.coverImages.toJsonArray { it.toJson() })
        body.put("characterCount", params.characterCount)
            .put("subDurationScope", params.subDurationScope)
            .put("audioUrl", params.audioUrl)
            .put("imageComp", params.imageComp)
            .put("taskType", params.taskType ?: 0)
            .put("chargingModel", "0")
            .put("isWatermark", params.isWatermark)
            .put("customStoryPlot", params.customStoryPlot)
            .put("storyRequest", params.storyRequest)
            .put("isLyric", params.isLyric)
            .put("capcutEnabled", params.capcutEnabled)
            .put("quality", params.quality.toString())
            .put("informationFlowVersion", 2)
            .put("mvVersion", (params.mvVersion ?: 0).toString())
        return when (val r = Api.post(ApiPaths.genMvCustomTaskFlowV2, body)) {
            is ApiResult.Failure -> r
            is ApiResult.Success -> {
                val data = r.data.dataObject() ?: return badResponse(body = r.data)
                ApiResult.Success(MvGenTaskEntity(MvModel.fromJson(data).id))
            }
        }
    }

    /** MV 风格模板：只保留 value 是合法 JSON、label 是 http 预览、remark 非空的条目。 */
    suspend fun getStyleTemplates(): ApiResult<List<MvStyleTemplateEntity>> =
        DictDataApi.getDictByLanguage(mvStyleType).map { raw ->
            MvStyleTemplateMapper.toEntityList(
                raw.filter { e ->
                    parseJsonObjectOrNull(e.value) != null &&
                        e.label.isNotEmpty() && e.label.startsWith("http") && !e.remark.isNullOrEmpty()
                },
            )
        }

    /** 上传图片并做敏感检测；[onProgress] 0–100。永远 Success（结果状态在 [ImageUploadResult.status]）。 */
    suspend fun uploadImageWithCheck(filePath: String, onProgress: ((Int) -> Unit)? = null): ApiResult<ImageUploadResult> =
        FileUploadApi.uploadImageWithCheck(filePath, onProgress)

    /** 图片敏感检测。非空 = 违规原因；请求失败按通过（Dart 返回空串）。 */
    suspend fun checkImageV2(imageUrl: String, type: Int): String =
        FileUploadApi.checkImageV2(imageUrl, type).getOrNull() ?: ""

    /** 文本内容审核，非空返回值表示违规提示。 */
    suspend fun checkText(text: String): ApiResult<String> = ModerationApi.checkText(text)

    /** 默认歌手头像预设列表。 */
    suspend fun getDefaultHeadImages(): ApiResult<List<MusicStyleEntity>> =
        DictDataApi.getDefaultHeadImages().map { it.toEntityList() }

    /** 随机推荐描述文案。 */
    suspend fun getRandomDescription(): ApiResult<String> = DictDataApi.getString(ApiPaths.dictDataMvRecRemark)

    /** 流式润色描述（回调语义见 [PolishStreamApi.polishStream]）。 */
    fun polishDescriptionStream(
        description: String,
        onDelta: (String) -> Unit,
        onDone: (ApiResult<String>) -> Unit,
        onMeta: ((JSONObject) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        expectedRevision: Int? = null,
    ): StreamHandle = PolishStreamApi.polishMvDescStream(description, onDelta, onDone, onMeta, onError, expectedRevision)
}
