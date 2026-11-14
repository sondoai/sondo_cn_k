package com.sondo.app.features.home.data

import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiErrorType
import com.sondo.app.core.http.ApiException
import com.sondo.app.core.http.ApiResult
import com.sondo.app.core.http.ReqOptions
import com.sondo.app.data.api.AppConfigApi
import com.sondo.app.data.api.PublishListApi
import com.sondo.app.data.models.CategoryModel
import com.sondo.app.data.models.HomeModel
import com.sondo.app.data.models.VideoContentEntity
import com.sondo.app.data.models.VideoContentMapper
import com.sondo.app.data.models.jsonValueOrNull
import com.sondo.app.data.models.toEntity
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 首页数据（Dart: HomeRemoteDataSource + HomeRepositoryImpl 合并）。
 * 返回值即 Dart 仓库层的实体；失败统一 [ApiResult.Failure]（Dart `AppException('request_failed')`）。
 */
object HomeApi {
    private fun bad(): ApiResult.Failure = ApiResult.Failure(ApiException(ApiErrorType.BAD_RESPONSE, message = "request_failed"))

    private fun data(env: JSONObject): Any? = jsonValueOrNull(env.opt("data"))

    /** 热门歌曲，`GET /member/music/list`。 */
    suspend fun getTrending(): ApiResult<HomeTrendingEntity> =
        when (val r = Api.get(ApiPaths.musicList)) {
            is ApiResult.Failure -> r
            is ApiResult.Success -> {
                val model = HomeModel.fromJsonOrNull(data(r.data) as? JSONObject)
                if (model == null) bad() else ApiResult.Success(HomeTrendingMapper.toEntity(model))
            }
        }

    /** 音乐分类，`GET /member/music/indexCategoryPicInfo`（不带 token）。 */
    suspend fun getCategories(): ApiResult<List<CategoryEntity>> =
        when (val r = Api.get(ApiPaths.musicIndexCategoryPicInfo, opts = ReqOptions(noToken = true))) {
            is ApiResult.Failure -> r
            is ApiResult.Success -> {
                val arr = data(r.data) as? JSONArray
                if (arr == null) bad() else {
                    val list = ArrayList<CategoryModel>()
                    for (i in 0 until arr.length()) (jsonValueOrNull(arr.opt(i)) as? JSONObject)?.let { list.add(CategoryModel.fromJson(it)) }
                    ApiResult.Success(CategoryMapper.toEntityList(list))
                }
            }
        }

    /** 分页拉新歌。 */
    suspend fun getNewSongs(pageNo: Int, pageSize: Int = 10): ApiResult<HomePageDataEntity> =
        PublishListApi.fetchNewSongs(pageNo = pageNo, pageSize = pageSize).map { page ->
            HomePageDataEntity(page.list.map { it.toEntity() }, page.total)
        }

    /** 首页轮播素材，`code=APP_HOME_BANNER`。空列表表示没有可展示项。 */
    // 与 Dart 一致：素材接口失败时数据源返回 null，这里当成没有可展示的轮播（成功 + 空列表）。
    suspend fun getHomeBanners(): ApiResult<List<HomeBannerItem>> =
        when (val r = AppConfigApi.fetchMaterialPayload("APP_HOME_BANNER")) {
            is ApiResult.Failure -> ApiResult.Success(emptyList())
            is ApiResult.Success -> ApiResult.Success(HomeBannerItem.parseList(r.data))
        }

    /** 热门 MV 流（9:16 竖版画幅）。 */
    suspend fun getTrendingMvList(): ApiResult<List<VideoContentEntity>> =
        PublishListApi.fetchMvPublishList(pageNo = 1, imageComp = "9:16", pageSize = 10, isFindHot = 1, isIndex = 1)
            .map { VideoContentMapper.fromMvModelList(it.list) }
}
