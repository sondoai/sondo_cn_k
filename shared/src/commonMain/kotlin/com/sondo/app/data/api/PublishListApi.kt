package com.sondo.app.data.api

import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiResult
import com.sondo.app.data.models.MvModel
import com.sondo.app.data.models.PageResult
import com.sondo.app.data.models.SongModel

/**
 * 跨 Feature 共用的 MV/音乐发布列表
 * （Dart: lib/shared/data/datasources/publish_list_remote_datasource.dart `PublishListRemoteDataSource`）。
 */
object PublishListApi {

    /**
     * 分页拉取 MV 发布列表。任一条缺必填字段时整页失败（与 Dart 解析抛异常一致）。
     * 失败：Dart 返回 `PageData(list: [], total: -1)`。
     */
    suspend fun fetchMvPublishList(
        pageNo: Int,
        pageSize: Int = 10,
        imageComp: String? = null,
        isFindHot: Int = 0,
        isIndex: Int = 0,
    ): ApiResult<PageResult<MvModel>> =
        Api.get(
            ApiPaths.mvPublishList,
            query = queryOf(
                "pageNo" to pageNo,
                "pageSize" to pageSize,
                "imageComp" to imageComp?.takeIf { it.isNotEmpty() },
                "isFindHot" to isFindHot,
                "isIndex" to isIndex,
            ),
        ).parse { env -> PageResult.fromDataStrict(env.dataObject()) { MvModel.fromJsonOrNull(it) } }

    /** 分页获取新歌列表。失败：Dart 直接抛异常。 */
    suspend fun fetchNewSongs(
        pageNo: Int,
        pageSize: Int = 10,
        musicTitle: String? = null,
        musicCategory: String? = null,
    ): ApiResult<PageResult<SongModel>> =
        Api.get(
            ApiPaths.musicNewPublishList,
            query = queryOf(
                "pageNo" to pageNo,
                "pageSize" to pageSize,
                "musicCategory" to musicCategory,
                "musicTitle" to musicTitle,
            ),
        ).parse { env -> PageResult.fromDataStrict(env.dataObject()) { SongModel.fromJsonOrNull(it) } }
}
