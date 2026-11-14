package com.sondo.app.features.square.data

import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiErrorType
import com.sondo.app.core.http.ApiException
import com.sondo.app.core.http.ApiResult
import com.sondo.app.core.utils.DevLog
import com.sondo.app.data.api.PublishListApi
import com.sondo.app.data.models.MvModel
import com.sondo.app.data.models.PageResult
import com.sondo.app.data.models.SongModel
import com.sondo.app.data.models.VideoContentMapper
import com.sondo.app.data.models.VideoContentType
import com.sondo.app.data.models.jIntOrNull
import com.sondo.app.data.models.jStrOrNull
import com.sondo.app.data.models.jsonValueOrNull
import com.sondo.app.data.models.toEntity
import com.tencent.kuikly.core.datetime.DateTime
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 广场数据（Dart: SquareRemoteDataSource + SquareRepositoryImpl 合并）。 */
object SquareApi {
    private const val DEFAULT_FIRST_FRAME_IMG =
        "https://cdn.sondo.ai/4f56d178531bf1f10d3161adf540d07c26639e76fb8f970dcb2c7d17bdca8939.jpg"
    private const val DEFAULT_AVATAR = "https://cdn.sondo.ai/default_head.png"

    private fun fail(msg: String = "request_failed"): ApiResult.Failure =
        ApiResult.Failure(ApiException(ApiErrorType.BAD_RESPONSE, message = msg))

    private fun userListQuery(pageNo: Int, pageSize: Int): Map<String, Any?> =
        mapOf("pageNo" to pageNo, "pageSize" to pageSize, "time" to DateTime.currentTimestamp())

    private fun dataObj(env: JSONObject): JSONObject? = jsonValueOrNull(env.opt("data")) as? JSONObject

    /** 分页拉取已发布 MV 广场列表。 */
    suspend fun getPublishedList(
        type: VideoContentType = VideoContentType.mv,
        pageNo: Int,
        pageSize: Int = 10,
        imageComp: String? = null,
        isFindHot: Int = 0,
        isIndex: Int = 0,
    ): ApiResult<SquarePageEntity> =
        PublishListApi.fetchMvPublishList(pageNo, pageSize, imageComp, isFindHot, isIndex)
            .map { SquarePageEntity(VideoContentMapper.fromMvModelList(it.list), it.total) }

    /** 用户资料（昵称、头像、浏览 / 点赞统计）。 */
    suspend fun getUserInfo(userId: Long): ApiResult<SquareProfileUserEntity> =
        when (val r = Api.get("${ApiPaths.userInfo}/$userId")) {
            is ApiResult.Failure -> r
            is ApiResult.Success -> {
                val d = dataObj(r.data)
                if (d == null) fail("user info is null") else {
                    val raw = d.jStrOrNull("nickname") ?: "User$userId"
                    ApiResult.Success(
                        SquareProfileUserEntity(
                            nickname = maskNickname(raw, userId),
                            avatar = d.jStrOrNull("avatar") ?: DEFAULT_AVATAR,
                            totalViews = d.jIntOrNull("hitNum") ?: 0,
                            totalLikes = d.jIntOrNull("collectNum") ?: 0,
                        )
                    )
                }
            }
        }

    /** 用户已发布 MV（补全缺失首帧图）。 */
    suspend fun getUserVideoList(userId: Long, pageNo: Int, pageSize: Int = 20): ApiResult<SquarePageEntity> =
        when (val r = Api.get("${ApiPaths.userMvPublishList}/$userId", query = userListQuery(pageNo, pageSize))) {
            is ApiResult.Failure -> r
            is ApiResult.Success -> {
                val page = PageResult.fromDataStrict(dataObj(r.data)) { json ->
                    ensureFirstFrameImg(json)
                    MvModel.fromJsonOrNull(json)
                }
                if (page == null) fail() else ApiResult.Success(SquarePageEntity(VideoContentMapper.fromMvModelList(page.list), page.total))
            }
        }

    /** 用户已发布音乐；每首补上作者信息。 */
    suspend fun getUserMusicList(
        userId: Long,
        pageNo: Int,
        pageSize: Int = 20,
        nickname: String = "",
        avatar: String = "",
    ): ApiResult<SquareProfileMusicPageEntity> =
        when (val r = Api.get("${ApiPaths.userMusicPublishList}/$userId", query = userListQuery(pageNo, pageSize))) {
            is ApiResult.Failure -> r
            is ApiResult.Success -> {
                val page = PageResult.fromDataStrict(dataObj(r.data)) { SongModel.fromJsonOrNull(it) }
                if (page == null) fail() else {
                    val list = page.list.map { song ->
                        val info = JSONObject().put("nickname", nickname).put("avatar", avatar)
                        if (userId != 0L) info.put("id", userId)
                        song.copy(userInfo = info).toEntity()
                    }
                    ApiResult.Success(SquareProfileMusicPageEntity(list, page.total))
                }
            }
        }

    /** 上报 MV 播放次数。 */
    suspend fun reportMvHit(id: Long): ApiResult<Unit> =
        when (val r = Api.get("${ApiPaths.mvHit}/$id")) {
            is ApiResult.Failure -> {
                DevLog.logger("reportMvHit: ${r.error.message}")
                r
            }
            is ApiResult.Success -> ApiResult.Success(Unit)
        }

    /** 邮箱昵称脱敏：baishi@gmail.com → baishi@*** */
    private fun maskNickname(nickname: String, userId: Long): String {
        val name = nickname.ifEmpty { "User$userId" }
        if (!name.contains("@")) return name
        return "${name.split("@").first()}@***"
    }

    private fun ensureFirstFrameImg(json: JSONObject) {
        val first = jsonValueOrNull(json.opt("firstFrameImg"))
        if (first == null || (first is String && first.isEmpty())) {
            val cover = jsonValueOrNull(json.opt("coverImage"))
            if (cover == null || (cover is String && cover.isEmpty())) {
                json.put("firstFrameImg", DEFAULT_FIRST_FRAME_IMG)
            } else {
                json.put("firstFrameImg", cover)
            }
        }
    }
}
