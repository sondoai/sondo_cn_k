package com.sondo.app.features.library.data

import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiResult
import com.sondo.app.data.api.MvActionApi
import com.sondo.app.data.api.dataArray
import com.sondo.app.data.api.dataAny
import com.sondo.app.data.api.dataObject
import com.sondo.app.data.api.nowMs
import com.sondo.app.data.api.parse
import com.sondo.app.data.api.queryOf
import com.sondo.app.data.models.DanceFeedback
import com.sondo.app.data.models.MvFeedbackModel
import com.sondo.app.data.models.MvModel
import com.sondo.app.data.models.PageResult
import com.sondo.app.data.models.SongModel
import com.sondo.app.data.models.jMapStrict
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 作品库视频与音乐列表接口（Dart: `LibraryListRemoteDataSource` + `LibraryListRepositoryImpl`）。
 * Dart 出错时返回空页（收藏歌曲 total=-1，其余 total=0）/ 空列表；这里返回 Failure，由页面兜底成空页。
 */
object LibraryListApi {

    /** 收藏歌曲分页。`isCollect: false` 表示不含自己创作的。 */
    suspend fun fetchCollectedSongList(
        pageNo: Int,
        pageSize: Int = 10,
        musicTitle: String? = null,
    ): ApiResult<PageResult<SongModel>> =
        Api.get(
            ApiPaths.musicCollectList,
            queryOf(
                "pageNo" to pageNo,
                "pageSize" to pageSize,
                "musicTitle" to musicTitle?.takeIf { it.isNotEmpty() },
                "isCollect" to false,
            ),
        ).parse { env -> PageResult.fromDataStrict(env.dataObject()) { SongModel.fromJsonOrNull(it) } }

    /** 我的 MV 分页。带 time 防缓存。 */
    suspend fun fetchMyMvList(pageNo: Int, pageSize: Int = 20, title: String? = null): ApiResult<PageResult<MvModel>> =
        Api.get(
            ApiPaths.mvPage,
            queryOf(
                "pageNo" to pageNo,
                "pageSize" to pageSize,
                "time" to nowMs(),
                "title" to title?.takeIf { it.isNotEmpty() },
            ),
        ).parse { env -> PageResult.fromDataStrict(env.dataObject()) { MvModel.fromJsonOrNull(it) } }

    /** 按 ID 批量拉 MV，轮询生成中/失败项用。只含部分字段。data 为空得到空列表。 */
    suspend fun fetchMvListByIds(ids: String): ApiResult<List<MvModel>> =
        Api.get(ApiPaths.mvListByIds, queryOf("ids" to ids)).parse { env ->
            val arr = env.dataArray() ?: return@parse emptyList()
            arr.jMapStrict { MvModel.fromJsonOrNull(it) }
        }

    /** MV 收藏列表。`isCollect: false` 表示不含自己创作的。 */
    suspend fun fetchMvLikeList(pageNo: Int, pageSize: Int = 10): ApiResult<PageResult<MvModel>> =
        Api.get(
            ApiPaths.mvCollectList,
            queryOf("pageNo" to pageNo, "pageSize" to pageSize, "isCollect" to false),
        ).parse { env -> PageResult.fromDataStrict(env.dataObject()) { MvModel.fromJsonOrNull(it) } }
}

/**
 * Library 视频详情页数据（Dart: `LibraryVideoDetailRemoteDataSource` + `LibraryVideoDetailRepositoryImpl`）：
 * 超分合成走本 feature 的接口，详情 / 删除 / 评分反馈委托 shared 层 [MvActionApi]。
 */
object LibraryVideoDetailApi {

    /** 拉取 MV 详情；失败或空数据 Dart 返回 null。 */
    suspend fun fetchMvDetail(id: Long): ApiResult<MvModel> = MvActionApi.fetchMvDetail(id)

    /** 查询 MV 评分/反馈状态。 */
    suspend fun fetchMvFeedback(taskId: Long): ApiResult<MvFeedbackModel> = MvActionApi.fetchMvFeedback(taskId)

    /** 拉取 MV 推荐评价标签（带缓存）。 */
    suspend fun fetchMvRecommendTags(): ApiResult<List<DanceFeedback>> = MvActionApi.fetchMvRecommendTags()

    /** 提交 MV 星级评分。 */
    suspend fun rateMv(taskId: Long, starRating: Int): ApiResult<Boolean> = MvActionApi.rateMv(taskId, starRating)

    /** 提交 MV 文字反馈。 */
    suspend fun submitMvFeedback(taskId: Long, starRating: Int, tags: List<String>, content: String): ApiResult<Boolean> =
        MvActionApi.submitMvFeedback(taskId, starRating, tags, content)

    /** 删除 MV（逻辑删除，进回收站）。 */
    suspend fun deleteMv(id: Long): ApiResult<Unit> = MvActionApi.deleteMv(id)

    /**
     * MV 画质升级（超分合成）。Success 里是后端返回的布尔 data（空为 false）；失败：Dart 返回 false。
     */
    suspend fun superMergeMv(mvId: Long, quality: String, genMode: Int, userNeedPoint: Int): ApiResult<Boolean> =
        Api.post(
            ApiPaths.mvAgainMerge,
            JSONObject()
                .put("mvId", mvId)
                .put("quality", quality)
                .put("mergeBs", 1)
                .put("isupdt", 1)
                .put("genMode", genMode)
                .put("userNeedPoint", userNeedPoint),
        ).map { (it.dataAny() as? Boolean) ?: false }
}
