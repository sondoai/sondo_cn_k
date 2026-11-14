package com.sondo.app.features.music.data

import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiResult
import com.sondo.app.data.api.DictDataApi
import com.sondo.app.data.api.ModerationApi
import com.sondo.app.data.api.PolishStreamApi
import com.sondo.app.data.api.PublishListApi
import com.sondo.app.data.api.StreamHandle
import com.sondo.app.data.api.dataAny
import com.sondo.app.data.api.parse
import com.sondo.app.data.models.MusicStyleEntity
import com.sondo.app.data.models.PageResult
import com.sondo.app.data.models.SongDetailModel
import com.sondo.app.data.models.SongEntity
import com.sondo.app.data.models.toEntity
import com.sondo.app.data.models.toEntityList
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 音乐生成 Feature 的数据层
 * （Dart: features/music/data/datasources/music_gen_remote_datasource.dart + repositories/music_gen_repository_impl.dart）。
 * Dart 仓库把任何异常包成 AppException('request_failed')；这里统一返回 ApiResult.Failure，调用方按需提示。
 */
object MusicGenApi {
    /** 提交音乐生成任务，`POST /member/music/generate`。 */
    suspend fun generateMusic(params: MusicGenParams): ApiResult<Unit> =
        Api.post(ApiPaths.musicGenerate, buildMusicGenerateBody(params)).map { }

    /** 获取随机简单模式描述灵感（data 为字符串；null 得空串）。 */
    suspend fun getSimplePrompt(): ApiResult<String> = getDictString(ApiPaths.dictDataMusicSimplePromptList)

    /** 获取随机歌词（含标题分隔符 `qinhongyang`）。 */
    suspend fun getRandomLyric(): ApiResult<String> = getDictString(ApiPaths.dictDataMusicRandomLyric)

    /** 获取随机风格提示词。 */
    suspend fun getStylePrompt(): ApiResult<String> = getDictString(ApiPaths.dictDataMusicStylePromptList)

    private suspend fun getDictString(path: String): ApiResult<String> =
        Api.get(path).map { it.dataAny()?.toString() ?: "" }

    /** 获取风格标签字典列表。 */
    suspend fun getStyleList(): ApiResult<List<MusicStyleEntity>> =
        DictDataApi.getMusicStyleTags().map { it.toEntityList() }

    /** 文本内容审核，非空返回值表示违规提示。 */
    suspend fun checkText(text: String): ApiResult<String> = ModerationApi.checkText(text)

    /** 流式润色歌词（body `{data, type: 1}`）。回调语义见 [PolishStreamApi.polishStream]。 */
    fun polishLyricsStream(
        lyrics: String,
        onDelta: (String) -> Unit,
        onDone: (ApiResult<String>) -> Unit,
        onMeta: ((JSONObject) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        expectedRevision: Int? = null,
    ): StreamHandle = PolishStreamApi.polishLyricsStream(lyrics, onDelta, onDone, onMeta, onError, expectedRevision)
}

/** 音乐详情（Dart: music_detail_remote_datasource.dart + music_detail_repository_impl.dart）。 */
object MusicDetailApi {
    /** 获取歌曲详情，`GET /member/music/detail/{musicId}`。 */
    suspend fun getMusicDetail(musicId: Long): ApiResult<SongDetailEntity> =
        Api.get("${ApiPaths.musicDetail}/$musicId").parse { env ->
            val data = env.dataAny() as? JSONObject ?: return@parse null
            val dto = SongDetailModel.fromJson(data)
            SongDetailEntity(
                lyricDetail = dto.lyricDetail,
                musicDetail = dto.musicDetail.toEntity(),
                user = dto.user,
            )
        }
}

/** 音乐歌单 / 搜索列表（Dart: music_genre_remote_datasource.dart + music_genre_repository_impl.dart）。 */
object MusicGenreApi {
    /** 分页获取歌曲列表。 */
    suspend fun getMusicListPage(
        pageNo: Int,
        pageSize: Int = 10,
        musicTitle: String? = null,
        musicCategory: String? = null,
    ): ApiResult<PageResult<SongEntity>> =
        PublishListApi.fetchNewSongs(pageNo, pageSize, musicTitle, musicCategory)
            .map { PageResult(it.list.map { s -> s.toEntity() }, it.total) }
}
