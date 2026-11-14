package com.sondo.app.features.think_line_mv.data

import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiResult
import com.sondo.app.data.api.MvActionApi
import com.sondo.app.data.api.dataAny
import com.sondo.app.data.api.dataObject
import com.sondo.app.data.models.DanceFeedback
import com.sondo.app.data.models.MvFeedbackModel
import com.sondo.app.data.models.MvModel
import com.sondo.app.data.models.MvThinkStep
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * MV 信息流接口（Dart: features/think_line_mv/data/datasources/think_line_mv_remote_datasource.dart
 * + repositories/think_line_mv_repository_impl.dart）。
 *
 * 信息流独有接口直接请求；MV 详情、删除、评分反馈、继续生成复用 [MvActionApi]。
 * 无返回数据的操作：Success 表示后端受理，后端返回 false / 空同样转成 Failure（与 Dart `_accepted` 一致）。
 */
object ThinkLineMvApi {

    /** 后端未受理（data 为 false / 空）。 */
    private fun <T> rejected(): ApiResult<T> =
        ApiResult.Failure(com.sondo.app.core.http.ApiException(com.sondo.app.core.http.ApiErrorType.BAD_RESPONSE, message = "request_failed"))

    private fun ApiResult<JSONObject>.accepted(): ApiResult<Unit> = when (this) {
        is ApiResult.Failure -> this
        is ApiResult.Success -> if ((data.dataAny() as? Boolean) == true) ApiResult.Success(Unit) else rejected()
    }

    private fun ApiResult<Boolean>.acceptedBool(): ApiResult<Unit> = when (this) {
        is ApiResult.Failure -> this
        is ApiResult.Success -> if (data) ApiResult.Success(Unit) else rejected()
    }

    /** 拉取 MV 详情；空数据视为失败。 */
    suspend fun fetchMvDetail(id: Long): ApiResult<MvModel> = MvActionApi.fetchMvDetail(id)

    /**
     * 拉取信息流步骤列表（含 isPay）。`GET {mvThinkFlowList}/{taskId}`，读 `data.steps`。
     * 无数据时为空列表；请求失败时 Dart 也返回空列表，这里同样折算成 Success(空)。
     */
    suspend fun fetchFlowSteps(taskId: Long): ApiResult<List<MvThinkStep>> {
        val res = Api.get("${ApiPaths.mvThinkFlowList}/$taskId")
        return when (res) {
            is ApiResult.Failure -> ApiResult.Success(emptyList())
            is ApiResult.Success -> {
                val data = res.data.dataObject() ?: return ApiResult.Success(emptyList())
                val arr = data.optJSONArray("steps") ?: return ApiResult.Success(emptyList())
                val list = try {
                    MvThinkStep.listFromJson(arr)
                } catch (e: Throwable) {
                    emptyList()
                }
                ApiResult.Success(list)
            }
        }
    }

    /** 标记单条信息流已读。`GET {mvThinkFlowRead}/{flowId}`。 */
    suspend fun markFlowRead(flowId: Long): ApiResult<Unit> =
        Api.get("${ApiPaths.mvThinkFlowRead}/$flowId").accepted()

    /** 标记该任务的信息流全部已读。`GET {mvThinkFlowReadAll}/{taskId}`。 */
    suspend fun markAllFlowRead(taskId: Long): ApiResult<Unit> =
        Api.get("${ApiPaths.mvThinkFlowReadAll}/$taskId").accepted()

    /** 重试失败步骤；[stage] 为步骤类型（MvThinkStep.type）。 */
    suspend fun retryStage(mvId: Long, stage: String): ApiResult<Unit> =
        Api.post(ApiPaths.mvThinkFlowRetryStage, JSONObject().put("mvId", mvId).put("stage", stage)).accepted()

    /** 重试单张失败卡片；[uniqueKey] 为卡片唯一标识。 */
    suspend fun retryCard(taskId: Long, uniqueKey: Long): ApiResult<Unit> =
        Api.post(ApiPaths.mvThinkFlowRetryCard, JSONObject().put("taskId", taskId).put("uniqueKey", uniqueKey)).accepted()

    /** 扣 [point] 积分继续生成全片；不回传故事片段，服务端沿用已生成内容。 */
    suspend fun continueGenMv(mvId: Long, point: Int): ApiResult<Unit> =
        MvActionApi.continueGenMv(mvId, point).acceptedBool()

    /** 拉取 1-5 星全部推荐评价标签。 */
    suspend fun fetchRecommendTags(): ApiResult<List<DanceFeedback>> = MvActionApi.fetchMvRecommendTags()

    /** 查询评分/反馈状态。 */
    suspend fun fetchFeedback(taskId: Long): ApiResult<MvFeedbackModel> = MvActionApi.fetchMvFeedback(taskId)

    /** 提交星级评分。 */
    suspend fun rateMv(taskId: Long, starRating: Int): ApiResult<Unit> =
        MvActionApi.rateMv(taskId, starRating).acceptedBool()

    /** 提交文字反馈；[tags] 为推荐标签 code。 */
    suspend fun submitFeedback(taskId: Long, starRating: Int, tags: List<String>, content: String): ApiResult<Unit> =
        MvActionApi.submitMvFeedback(taskId, starRating, tags, content).acceptedBool()

    /** 删除 MV（进回收站）。 */
    suspend fun deleteMv(id: Long): ApiResult<Unit> = MvActionApi.deleteMv(id)
}
