package com.sondo.app.features.try_free.data

import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiResult
import com.sondo.app.data.api.dataAny
import com.sondo.app.data.api.dataArray
import com.sondo.app.data.api.dataObject
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 试用 MV 接口（Dart: TrialMvRemoteDataSource + TrialMvRepositoryImpl）。
 *
 * 任务详情轮询的「原始 JSON」版本在 `WorksApi.getTrialDetail`（TryFreeTaskService 用），这里是带实体的版本。
 */
object TrialMvApi {
    /** 获取试用模板列表；data 不是数组时为空列表（同 Dart）。 */
    suspend fun getTemplates(): ApiResult<List<TrialMvTemplateEntity>> =
        Api.get(ApiPaths.trialMvTemplates).map { TrialMvTemplateModel.listFromJson(it.dataArray()) }

    /** 开始试用生成。失败时 [com.sondo.app.core.http.ApiException.code] 为后端业务码（如 1043000001）。 */
    suspend fun startTrial(templateId: Long, coverImage: String): ApiResult<TrialMvTaskEntity> =
        Api.post(ApiPaths.trialMvStart, TrialMvStartRequestModel(templateId, coverImage).toJson()).map {
            TrialMvMapper.toTaskEntity(TrialMvStartResultModel.fromJson(it.dataObject() ?: JSONObject()))
        }

    /** 获取试用任务详情。 */
    suspend fun getDetail(mvId: Long): ApiResult<TrialMvDetailEntity> =
        Api.get("${ApiPaths.trialMvDetail}/$mvId").map {
            TrialMvMapper.toDetailEntity(TrialMvDetailModel.fromJson(it.dataObject() ?: JSONObject()))
        }

    /** 当前阶段失败重试；data == true 才算成功。 */
    suspend fun retryStage(mvId: Long): ApiResult<Boolean> =
        Api.post("${ApiPaths.trialMvRetryStage}/$mvId").map { it.dataAny() == true }

    /** 合成后重试封面。 */
    suspend fun retryCover(mvId: Long): ApiResult<Boolean> =
        Api.post("${ApiPaths.trialMvRetryCover}/$mvId").map { it.dataAny() == true }

    /** 合成后重试分镜。 */
    suspend fun retryShot(mvId: Long, shotId: Long): ApiResult<Boolean> =
        Api.post("${ApiPaths.trialMvRetryShot}/$mvId", JSONObject().put("shotId", shotId)).map { it.dataAny() == true }
}
