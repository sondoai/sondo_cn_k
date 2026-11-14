package com.sondo.app.data.api

import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiResult
import com.sondo.app.data.models.DanceFeedback
import com.sondo.app.data.models.MvFeedbackModel
import com.sondo.app.data.models.MvModel
import com.sondo.app.data.models.MvShot
import com.sondo.app.data.models.MvShotRefImg
import com.sondo.app.data.models.RoleModel
import com.sondo.app.data.models.jMapStrict
import com.sondo.app.data.models.toJsonStringArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * MV 收藏 / 发布 / 合并 / 评分反馈等跨 Feature 共用操作
 * （Dart: lib/shared/data/datasources/mv_action_remote_datasource.dart `MvActionRemoteDataSource`）。
 */
object MvActionApi {

    /** 推荐评价标签缓存，避免评分卡每次展示都重复拉取。只缓存成功结果。 */
    private var mvRecommendTags: List<DanceFeedback>? = null

    /** data 为空得到空列表；不是数组视为失败。 */
    private inline fun <T> listOrNull(env: JSONObject, map: (JSONArray) -> List<T>?): List<T>? {
        val data = env.dataAny() ?: return emptyList()
        val arr = data as? JSONArray ?: return null
        return map(arr)
    }

    /** 最近使用过的四视图角色列表。失败：Dart 返回空列表。 */
    suspend fun fetchRecentRoles(): ApiResult<List<RoleModel>> =
        Api.get(ApiPaths.mvRecentRoles).parse { env ->
            listOrNull(env) { arr -> arr.jMapStrict { RoleModel.fromJson(it) } }
        }

    /** 按 MV 任务 ID 拉取各分镜的历史参考图。失败：Dart 返回空列表。 */
    suspend fun fetchShotRefImgs(taskId: Long): ApiResult<List<MvShotRefImg>> =
        Api.get(ApiPaths.mvShotRefImgs, query = mapOf("taskId" to taskId)).parse { env ->
            listOrNull(env) { arr -> arr.jMapStrict { MvShotRefImg.fromJson(it) } }
        }

    /** 收藏/取消收藏 MV，[collectStatus] 1 收藏、0 取消。成功为 Success(1)；失败：Dart 返回 0。 */
    suspend fun collectMv(id: Long, collectStatus: Int): ApiResult<Int> =
        Api.post(ApiPaths.mvCollectOperation, JSONObject().put("mvId", id).put("collectStatus", collectStatus))
            .map { 1 }

    /** 分享成功后累加 MV 转发计数。GET `…/addRepostCount/{id}`。成功为 Success(1)；失败：Dart 返回 0。 */
    suspend fun addMvRepostCount(id: Long): ApiResult<Int> =
        Api.get("${ApiPaths.mvAddRepostCount}/$id").map { 1 }

    /**
     * 更新 MV 发布状态，[mvPublish] 1 发布、0 取消。
     * 成功返回后端下发的最新发布状态码（data 为空时 -1）；失败：Dart 返回 -1。
     */
    suspend fun publishMvOperation(mvId: Long, mvPublish: Int): ApiResult<Int> =
        Api.post(ApiPaths.mvPublishOperation, JSONObject().put("mvId", mvId).put("mvPublish", mvPublish))
            .map { (it.dataAny() as? Number)?.toInt() ?: -1 }

    /**
     * 本地合成视频失败时，把失败状态与错误信息回写到 MV 任务记录
     * （与更新标题/作者复用同一接口，[status] 当前固定传 "4" 表示失败）。
     */
    suspend fun mergeError(id: Long, status: String, errorMsg: String): ApiResult<Unit> =
        Api.post(
            ApiPaths.mvUpdateInfo,
            JSONObject().put("id", id).put("status", status).put("errorMsg", errorMsg),
        ).asUnit()

    /** 本地合成视频成功后，回传最终视频地址与首帧图地址，供后端登记 MV 结果。 */
    suspend fun updateMvShotUrl(id: Long, videoUrl: String, imageUrl: String): ApiResult<Unit> =
        Api.post(
            ApiPaths.mvUpdateShotUrl,
            JSONObject().put("id", id).put("resultUrl", videoUrl).put("firstFrameImg", imageUrl),
        ).asUnit()

    /**
     * 拉取 MV 详情。library / edit_video 共用。
     * 失败、data 为空或缺必填字段（Dart 解析抛异常）：Dart 返回 null。
     */
    suspend fun fetchMvDetail(id: Long): ApiResult<MvModel> =
        Api.get("${ApiPaths.mvDetail}/$id").parse { MvModel.fromJsonOrNull(it.dataObject()) }

    /**
     * 拉取分镜列表。edit_video / story_line 共用。
     * [calculateRetryPoints] 为 true 时后端才返回各镜 retryNeedPoint。
     * 失败或任一条缺必填字段：Dart 返回空列表。
     */
    suspend fun fetchShotList(id: Long, calculateRetryPoints: Boolean = false): ApiResult<List<MvShot>> =
        Api.get(
            "${ApiPaths.mvShotList}/$id",
            query = if (calculateRetryPoints) mapOf("calculateRetryPoints" to true) else null,
        ).parse { env -> listOrNull(env) { MvShot.listFromJsonOrNull(it) } }

    /** 取消分镜重生成。成功返回后端新 status（data 为空时 -1）；失败：Dart 返回 -1。 */
    suspend fun cancelRecreate(shotId: Long, genMode: Int): ApiResult<Int> =
        Api.post(ApiPaths.mvCancelShot, JSONObject().put("shotId", shotId).put("genMode", genMode))
            .map { (it.dataAny() as? Number)?.toInt() ?: -1 }

    /** 删除指定 MV（移入回收站）。注意是 GET `/member/musicMvTask/del/{id}`。 */
    suspend fun deleteMv(id: Long): ApiResult<Unit> = Api.get("${ApiPaths.mvDelete}/$id").asUnit()

    /**
     * 触发 MV 分镜合成（GET）。[genMode] 为 2 时走重新合成分支
     * `…/regenerateSynthesisMv/{id}/{isUpdt}`（[isUpdt] 标记是否为编辑后重新生成），其余走 `…/mergeMvShot/{id}`。
     */
    suspend fun mergeMvShot(id: Long, genMode: Int, isUpdt: Int): ApiResult<Unit> {
        val url = if (genMode == 2) "${ApiPaths.mvRegenerateSynthesis}/$id/$isUpdt" else "${ApiPaths.mvMergeShot}/$id"
        return Api.get(url).asUnit()
    }

    /**
     * 扣 [point] 积分继续生成全片（试看解锁复用同一接口）。continueModel 固定 0：不回传故事片段。
     * Success(true) = 后端受理；Success(false) = 后端未受理（data 为 false / 空）；失败：Dart 返回 false。
     */
    suspend fun continueGenMv(mvId: Long, point: Int): ApiResult<Boolean> =
        Api.post(
            ApiPaths.mvContinueGen,
            JSONObject().put("id", mvId).put("continueModel", 0).put("userNeedPoint", point),
        ).map { (it.dataAny() as? Boolean) ?: false }

    /** 查询 MV 评分/反馈状态。library 详情页 / think_line_mv 共用。失败或 data 为空：Dart 返回 null。 */
    suspend fun fetchMvFeedback(taskId: Long): ApiResult<MvFeedbackModel> =
        Api.get("${ApiPaths.mvFeedback}/$taskId")
            .parse { env -> env.dataObject()?.let { MvFeedbackModel.fromJson(it) } }

    /**
     * 一次性获取 MV 1-5 星全部推荐评价标签；成功后进程内只请求一次。
     * data 为空或不是数组得到空列表；失败：Dart 返回空列表。
     */
    suspend fun fetchMvRecommendTags(): ApiResult<List<DanceFeedback>> {
        mvRecommendTags?.let { return ApiResult.Success(it) }
        return Api.get(ApiPaths.mvFeedbackRecommendTags).parse { env ->
            val arr = env.dataArray() ?: return@parse emptyList()
            arr.jMapStrict { DanceFeedback.fromJson(it) }
        }.onSuccess { if (it.isNotEmpty()) mvRecommendTags = it }
    }

    /** 提交 MV 星级评分。Success 里是后端返回的布尔 data（空为 false）；失败：Dart 返回 false。 */
    suspend fun rateMv(taskId: Long, starRating: Int): ApiResult<Boolean> =
        Api.post(ApiPaths.mvFeedbackRate, JSONObject().put("taskId", taskId).put("starRating", starRating))
            .map { (it.dataAny() as? Boolean) ?: false }

    /** 提交 MV 文字反馈；[tags] 为推荐标签 code。成功为 Success(true)；失败：Dart 返回 false。 */
    suspend fun submitMvFeedback(
        taskId: Long,
        starRating: Int,
        tags: List<String>,
        content: String,
    ): ApiResult<Boolean> =
        Api.post(
            ApiPaths.mvFeedbackSubmit,
            JSONObject()
                .put("taskId", taskId)
                .put("starRating", starRating)
                .put("recommendTagCodes", tags.toJsonStringArray())
                .put("content", content),
        ).asOk()
}
