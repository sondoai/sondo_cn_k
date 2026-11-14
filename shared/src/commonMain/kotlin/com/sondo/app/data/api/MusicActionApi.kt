package com.sondo.app.data.api

import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiResult
import com.sondo.app.data.models.PageResult
import com.sondo.app.data.models.SongModel
import com.sondo.app.data.models.jInt
import com.sondo.app.data.models.jRaw
import com.sondo.app.data.models.jsonValueOrNull
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 歌曲列表与收藏 / 发布 / 转发等操作
 * （Dart: lib/shared/data/datasources/music_action_remote_datasource.dart `MusicActionRemoteDataSource`）。
 *
 * Dart 里返回 1/0 的操作在这里是 `ApiResult<Int>`：成功为 Success(1)，失败为 Failure（Dart 返回 0）。
 */
object MusicActionApi {

    /**
     * 按 ids（逗号分隔）查询当前用户的音乐生成进度。
     * 只取进度字段：标题/音频/封面/错误信息缺省保持 null，避免空字符串冲掉列表已有值；缺 id 的条目跳过。
     * ids 为空或 data 不是数组得到空列表；失败：Dart 返回空列表，轮询下个周期再试。
     */
    suspend fun getMusicListByIds(ids: String): ApiResult<List<SongModel>> {
        if (ids.isEmpty()) return ApiResult.Success(emptyList())
        return Api.get(ApiPaths.musicListByIds, query = mapOf("ids" to ids)).map { env ->
            val data = env.dataArray() ?: return@map emptyList()
            val songs = ArrayList<SongModel>()
            for (i in 0 until data.length()) {
                val json = jsonValueOrNull(data.opt(i)) as? JSONObject ?: continue
                val id = json.jRaw("id") as? Number ?: continue
                songs.add(
                    SongModel(
                        id = id.toLong(),
                        musicTitle = json.jRaw("musicTitle") as? String,
                        musicAudioUrl = json.jRaw("musicAudioUrl") as? String,
                        musicCoverUrl = json.jRaw("musicCoverUrl") as? String,
                        musicGenerateStatus = json.jInt("musicGenerateStatus", 0),
                        errorMsg = json.jRaw("errorMsg") as? String,
                    ),
                )
            }
            songs
        }
    }

    /**
     * 我的歌曲分页（按 [status] 过滤生成状态，供音乐选择弹窗等场景使用），已过滤本地模型歌曲
     * （musicModelType == 4）。total 是过滤前的后端总数。
     */
    suspend fun mySongList(
        pageNo: Int,
        pageSize: Int = 10,
        musicTitle: String? = null,
        status: Int? = null,
    ): ApiResult<PageResult<SongModel>> =
        Api.get(
            ApiPaths.musicPage,
            query = queryOf(
                "pageNo" to pageNo,
                "pageSize" to pageSize,
                "time" to nowMs(),
                "musicTitle" to musicTitle?.takeIf { it.isNotEmpty() },
                "musicGenerateStatus" to status,
            ),
        ).parse { env ->
            val page = PageResult.fromDataStrict(env.dataObject()) { SongModel.fromJsonOrNull(it) }
                ?: return@parse null
            PageResult(page.list.filter { it.musicModelType != 4 }, page.total)
        }

    /** 删除指定歌曲（移入回收站）。注意是 GET `/member/music/del/{id}`。 */
    suspend fun deleteSong(musicId: Long): ApiResult<Int> =
        Api.get("${ApiPaths.musicDelete}/$musicId").map { 1 }

    /** 收藏/取消收藏歌曲，[collectStatus] 1 收藏、0 取消。 */
    suspend fun collectSong(musicId: Long, collectStatus: Int): ApiResult<Int> =
        Api.post(
            ApiPaths.musicCollectOperation,
            JSONObject().put("musicId", musicId).put("collectStatus", collectStatus),
        ).map { 1 }

    /** 更新歌曲发布状态，可一并提交标题/封面/分类等编辑字段（均可选，按需部分更新）。 */
    suspend fun publishOperation(
        musicId: Long? = null,
        musicPublish: Int? = null,
        musicTitle: String? = null,
        musicCoverUrl: String? = null,
        categoryId: String? = null,
        categoryName: String? = null,
    ): ApiResult<Int> =
        Api.post(
            ApiPaths.musicPublishOperation,
            bodyOf(
                "musicId" to musicId,
                "musicPublish" to musicPublish,
                "musicTitle" to musicTitle,
                "musicCoverUrl" to musicCoverUrl,
                "categoryId" to categoryId,
                "categoryName" to categoryName,
            ),
        ).map { 1 }

    /** 分享成功后累加歌曲转发计数。GET `/member/music/addRepostCount/{id}`。 */
    suspend fun addMusicRepostCount(id: Long): ApiResult<Int> =
        Api.get("${ApiPaths.musicAddRepostCount}/$id").map { 1 }
}
