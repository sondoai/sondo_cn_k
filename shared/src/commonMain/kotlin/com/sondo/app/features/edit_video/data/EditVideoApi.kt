package com.sondo.app.features.edit_video.data

import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiErrorType
import com.sondo.app.core.http.ApiException
import com.sondo.app.core.http.ApiResult
import com.sondo.app.data.api.FileUploadApi
import com.sondo.app.data.api.ModerationApi
import com.sondo.app.data.api.MvActionApi
import com.sondo.app.data.models.CoverStatusModel
import com.sondo.app.data.models.ImageUploadResult
import com.sondo.app.data.models.MergeMv
import com.sondo.app.data.models.MvModel
import com.sondo.app.data.models.MvShot
import com.sondo.app.data.models.PageResult
import com.sondo.app.data.models.ShotHistory
import com.sondo.app.data.models.jsonValueOrNull
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 本 Feature 独有接口：再次合成、封面重生、V2 分镜重生成、信息流失败分镜重试、分镜历史
 * （Dart: lib/features/edit_video/data/datasources/edit_video_remote_datasource.dart `EditVideoRemoteDataSource`）。
 */
object EditVideoRemoteDataSource {

    private fun JSONObject.data(): Any? = jsonValueOrNull(opt("data"))

    private fun bad(body: JSONObject? = null): ApiResult.Failure =
        ApiResult.Failure(ApiException(ApiErrorType.BAD_RESPONSE, body = body))

    /** 编辑后再次合成整支 MV。 */
    suspend fun againMergeMv(data: MergeMv): ApiResult<Unit> =
        Api.post(ApiPaths.mvAgainMerge, data.toJson()).map { }

    /** 按 prompt 重生封面；后端 data 为是否受理。 */
    suspend fun againMergeCover(taskId: Long, prompt: String): ApiResult<Boolean> =
        Api.post(
            ApiPaths.mvAgainMergeCoverWithPrompt,
            JSONObject().put("taskId", taskId).put("image_prompt", prompt),
        ).map { it.data() == true }

    /** 查询封面重生状态；未生成过（data 为空）或请求失败都是 Success(null)（Dart catch 后返回 null）。 */
    suspend fun fetchCoverStatus(id: Long): ApiResult<CoverStatusModel?> {
        val res = Api.get("${ApiPaths.mvAgainMergeCoverStatus}/$id")
        return when (res) {
            is ApiResult.Failure -> ApiResult.Success(null)
            is ApiResult.Success -> {
                val d = res.data.data() as? JSONObject
                ApiResult.Success(d?.let { CoverStatusModel.fromJson(it) })
            }
        }
    }

    private fun shotBody(
        id: Long,
        shortDesc: String,
        description: String,
        mvType: String,
        isUpdt: Int,
        regenImage: Int,
        point: Int,
        referenceUrls: List<String>,
    ): JSONObject {
        val urls = JSONArray()
        referenceUrls.forEach { urls.put(it) }
        return JSONObject()
            .put("id", id)
            .put("shortDesc", shortDesc)
            .put("description", description)
            .put("mvType", mvType)
            .put("isUpdt", isUpdt)
            .put("referenceUrls", urls)
            .put("regenImage", regenImage)
            // 成品编辑页不是信息流续跑
            .put("isFlow", 0)
            .put("userNeedPoint", point)
    }

    private fun ApiResult<JSONObject>.toShot(): ApiResult<MvShot> = when (this) {
        is ApiResult.Failure -> this
        is ApiResult.Success -> {
            val d = data.data() as? JSONObject
            if (d == null) bad(data) else ApiResult.Success(MvShot.fromJson(d))
        }
    }

    /** V2 单分镜重生成。 */
    suspend fun regenCustomShot(
        id: Long,
        shortDesc: String,
        description: String,
        mvType: String,
        isUpdt: Int,
        regenImage: Int,
        point: Int,
        referenceUrls: List<String>,
    ): ApiResult<MvShot> = Api.post(
        ApiPaths.mvRegenCustomShotFlowV2,
        shotBody(id, shortDesc, description, mvType, isUpdt, regenImage, point, referenceUrls),
    ).toShot()

    /** 信息流失败分镜重试；入参与 [regenCustomShot] 相同，路径走 retryFlowShotV2。 */
    suspend fun retryFlowShot(
        id: Long,
        shortDesc: String,
        description: String,
        mvType: String,
        isUpdt: Int,
        regenImage: Int,
        point: Int,
        referenceUrls: List<String>,
    ): ApiResult<MvShot> = Api.post(
        ApiPaths.retryFlowShotV2,
        shotBody(id, shortDesc, description, mvType, isUpdt, regenImage, point, referenceUrls),
    ).toShot()

    /** 分镜历史分页。固定第一页 30 条，与旧列表页一致。 */
    suspend fun fetchShotHistory(shotCustomId: Long): ApiResult<List<ShotHistory>> =
        when (
            val res = Api.get(
                ApiPaths.getShotCustomHistoryPage,
                query = mapOf("shotCustomId" to shotCustomId, "pageNo" to 1, "pageSize" to 30),
            )
        ) {
            is ApiResult.Failure -> res
            is ApiResult.Success -> {
                val d = res.data.data() as? JSONObject
                if (d == null) {
                    ApiResult.Success(emptyList())
                } else {
                    val page = PageResult.fromDataStrict(d) { ShotHistory.fromJsonOrNull(it) }
                    if (page == null) bad(res.data) else ApiResult.Success(page.list)
                }
            }
        }

    /** 把历史版本还原为当前分镜。data 为空表示后端未返回分镜（按失败处理）。 */
    suspend fun applyShotHistory(historyId: Long): ApiResult<MvShot> =
        Api.post(ApiPaths.applyShotCustomHistory, JSONObject().put("historyId", historyId)).toShot()
}

/**
 * 组合 shared 共用端点与本 Feature 独有接口
 * （Dart: `EditVideoRepository` / `EditVideoRepositoryImpl`）。失败一律是 Failure（Dart 的 AppException('request_failed')）。
 */
object EditVideoRepository {
    suspend fun fetchMvDetail(id: Long): ApiResult<MvModel> = MvActionApi.fetchMvDetail(id)

    /** [calculateRetryPoints] 仅 retry_shots 传 true。 */
    suspend fun fetchShotList(id: Long, calculateRetryPoints: Boolean = false): ApiResult<List<MvShot>> =
        MvActionApi.fetchShotList(id, calculateRetryPoints)

    suspend fun deleteMv(id: Long): ApiResult<Unit> = MvActionApi.deleteMv(id)

    /** 取消分镜重生成，成功返回后端新 status；-1 视为失败。 */
    suspend fun cancelRecreate(shotId: Long, genMode: Int): ApiResult<Int> =
        when (val r = MvActionApi.cancelRecreate(shotId, genMode)) {
            is ApiResult.Failure -> r
            is ApiResult.Success -> if (r.data == -1) {
                ApiResult.Failure(ApiException(ApiErrorType.BAD_RESPONSE))
            } else {
                r
            }
        }

    /** 文本审核，非空为违规提示。 */
    suspend fun checkText(text: String): ApiResult<String> = ModerationApi.checkText(text)

    suspend fun uploadImageWithCheck(filePath: String, onProgress: ((Int) -> Unit)? = null): ApiResult<ImageUploadResult> =
        FileUploadApi.uploadImageWithCheck(filePath, onProgress)

    suspend fun againMergeMv(data: MergeMv): ApiResult<Unit> = EditVideoRemoteDataSource.againMergeMv(data)

    suspend fun againMergeCover(taskId: Long, prompt: String): ApiResult<Boolean> =
        EditVideoRemoteDataSource.againMergeCover(taskId, prompt)

    suspend fun fetchCoverStatus(id: Long): ApiResult<CoverStatusModel?> = EditVideoRemoteDataSource.fetchCoverStatus(id)

    suspend fun regenCustomShot(
        id: Long, shortDesc: String, description: String, mvType: String,
        isUpdt: Int, regenImage: Int, point: Int, referenceUrls: List<String>,
    ): ApiResult<MvShot> = EditVideoRemoteDataSource.regenCustomShot(
        id, shortDesc, description, mvType, isUpdt, regenImage, point, referenceUrls,
    )

    suspend fun retryFlowShot(
        id: Long, shortDesc: String, description: String, mvType: String,
        isUpdt: Int, regenImage: Int, point: Int, referenceUrls: List<String>,
    ): ApiResult<MvShot> = EditVideoRemoteDataSource.retryFlowShot(
        id, shortDesc, description, mvType, isUpdt, regenImage, point, referenceUrls,
    )

    suspend fun fetchShotHistory(shotCustomId: Long): ApiResult<List<ShotHistory>> =
        EditVideoRemoteDataSource.fetchShotHistory(shotCustomId)

    suspend fun applyShotHistory(historyId: Long): ApiResult<MvShot> = EditVideoRemoteDataSource.applyShotHistory(historyId)
}
