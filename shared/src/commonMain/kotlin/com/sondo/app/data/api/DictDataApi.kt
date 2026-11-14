package com.sondo.app.data.api

import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiResult
import com.sondo.app.data.models.MusicStyleModel
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 跨 Feature 共用的字典数据
 * （Dart: lib/shared/data/datasources/dict_data_remote_datasource.dart `DictDataRemoteDataSource`，
 * 并入 lib/shared/data/repositories/dict_data_repository_impl.dart）。
 */
object DictDataApi {
    /** 默认头像字典 type 常量。 */
    const val defaultHeadType = "app_mv_default_head"

    /** 音乐风格标签字典 type 常量。 */
    const val musicStyleTagsType = "music_style_tags"

    /**
     * 按 type 获取字典列表，`GET /system/dict-data/type`（带 time 防缓存）。
     * data 为空得到空列表；失败或任一条字段不全（Dart 解析抛异常）：Dart 返回空列表。
     */
    suspend fun getDictByType(type: String): ApiResult<List<MusicStyleModel>> =
        Api.get(ApiPaths.dictDataType, query = mapOf("type" to type, "time" to nowMs())).parse { dictList(it) }

    /** 按语言获取字典列表，`GET /system/dict-data/typeByLanguage`。失败语义同 [getDictByType]。 */
    suspend fun getDictByLanguage(type: String): ApiResult<List<MusicStyleModel>> =
        Api.get(ApiPaths.dictDataTypeByLanguage, query = mapOf("type" to type, "time" to nowMs())).parse { dictList(it) }

    private fun dictList(env: JSONObject): List<MusicStyleModel>? {
        val data = env.dataAny() ?: return emptyList()
        val arr = data as? JSONArray ?: return null
        return MusicStyleModel.listFromJsonOrNull(arr)
    }

    /** 获取默认舞者/歌手头像预设（字典条目）。 */
    suspend fun getDefaultHeadImages(): ApiResult<List<MusicStyleModel>> = getDictByType(defaultHeadType)

    /**
     * 默认推荐头像 URL 列表：取字典 value 里以 http 开头的
     * （Dart: `DictDataRepositoryImpl.getDefaultHeadImages`，与数据源同名方法返回类型不同，这里改名区分）。
     * 与 Dart 一致：请求失败时得到空列表而不是错误。
     */
    suspend fun getDefaultHeadImageUrls(): ApiResult<List<String>> {
        val list = getDefaultHeadImages().getOrNull() ?: emptyList()
        return ApiResult.Success(list.map { it.value }.filter { it.startsWith("http") })
    }

    /** 获取音乐风格标签列表。 */
    suspend fun getMusicStyleTags(): ApiResult<List<MusicStyleModel>> = getDictByType(musicStyleTagsType)

    /** 按路径获取字符串字典值（如 ApiPaths.dictDataMvRecRemark）。失败：Dart 返回空字符串。 */
    suspend fun getString(path: String): ApiResult<String> =
        Api.get(path).map { (it.dataAny() as? String) ?: "" }

    /**
     * 流式润色描述文案（[path] 通常是 ApiPaths.mvDescPolish，body `{prompt}`）。
     * 回调语义见 [PolishStreamApi.polishStream]。
     */
    fun polishDescriptionStream(
        path: String,
        description: String,
        onDelta: (String) -> Unit,
        onDone: (ApiResult<String>) -> Unit,
        onMeta: ((JSONObject) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        expectedRevision: Int? = null,
    ): StreamHandle = PolishStreamApi.polishStream(
        path = path,
        body = JSONObject().put("prompt", description),
        onDelta = onDelta,
        onDone = onDone,
        onMeta = onMeta,
        onError = onError,
        expectedRevision = expectedRevision,
    )
}
