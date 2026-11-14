package com.sondo.app.data.api

import com.sondo.app.base.AppEnv
import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiResult
import com.sondo.app.core.http.ReqOptions
import com.sondo.app.data.models.ConfigModel
import com.sondo.app.data.models.CountryPricingModel
import com.sondo.app.data.models.RecallPageModel
import com.sondo.app.data.models.ReturningOffer
import com.sondo.app.data.models.SplashConfig
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 应用启动配置、远端开关与极光 Registration ID 绑定
 * （Dart: lib/shared/data/datasources/app_config_remote_datasource.dart `AppConfigRemoteDataSource`）。
 */
object AppConfigApi {

    /**
     * 启动页配置。启动期请求：2s 超时、不带登录态、不弹全局错误（Dart 走无拦截器的短超时 Dio）。
     * 失败：Dart 返回空的 `SplashConfig()`。
     */
    suspend fun fetchSplashConfig(): ApiResult<SplashConfig> =
        Api.get(
            ApiPaths.splashConfig,
            opts = ReqOptions(noToken = true, quiet = true, timeoutMs = Api.TIMEOUT_SHORT),
        ).map { SplashConfig.fromJson(it.dataObject() ?: JSONObject()) }

    /**
     * 是否需弹出应用火爆弹窗（data == 1）。走 [AppConstants.apiOtherUrl]，30s 超时、不带登录态、不弹全局错误。
     * 失败：Dart 返回 false。
     */
    suspend fun isAppHotOpen(): ApiResult<Boolean> =
        Api.get(
            ApiPaths.appHotOpen,
            opts = ReqOptions(
                noToken = true,
                quiet = true,
                timeoutMs = Api.TIMEOUT_DEFAULT,
                baseUrl = AppConstants.apiOtherUrl,
            ),
        ).map { ((it.dataAny() as? Number)?.toInt() ?: 0) == 1 }

    /**
     * 素材配置原始 data：可能是 JSONObject，也可能是 JSONArray（首页轮播），也可能为 null。
     * 失败：Dart 返回 null。
     */
    suspend fun fetchMaterialPayload(code: String): ApiResult<Any?> =
        Api.get(ApiPaths.oldPeopleContent, query = mapOf("code" to code)).map { it.dataAny() }

    /**
     * 素材配置。回归页和活动 MV 共用这条路径，只是 code 不同。
     * 失败或 data 不是对象：Dart 返回 null。不在这里弹 toast（路径在静默名单内）。
     */
    suspend fun fetchMaterialData(code: String): ApiResult<JSONObject> =
        when (val r = fetchMaterialPayload(code)) {
            is ApiResult.Failure -> r
            is ApiResult.Success -> (r.data as? JSONObject)?.let { ApiResult.Success(it) } ?: badResponse()
        }

    /**
     * 回归落地页欢迎区和功能卡片。素材码固定 OLD_PEOPLE。backgroundUrl 为空不算失败。
     * 失败：Dart 返回 null，由页面重试。
     */
    suspend fun fetchOldPeopleContent(): ApiResult<RecallPageModel> =
        fetchMaterialData("OLD_PEOPLE").map { RecallPageModel.fromContentJson(it) }

    /**
     * 回归商品。Android / 鸿蒙用国内支付宝平台价（platform=12），iOS 为 2。
     * 失败或 data 不是对象：Dart 返回 null。
     */
    suspend fun fetchReturningOffer(): ApiResult<ReturningOffer> {
        val platform = if (AppEnv.isIOS) 2 else 12
        return Api.get(ApiPaths.returningOffer, query = mapOf("platform" to platform))
            .parse { env -> env.dataObject()?.let { ReturningOffer.fromJson(it) } }
    }

    /** 当前用户是否命中「周留存折扣」活动资格。失败：Dart 按未命中返回 false。 */
    suspend fun getDiscountConfig(): ApiResult<Boolean> =
        Api.get(ApiPaths.luckyWeeklyRetention).map { (it.dataAny() as? Boolean) ?: false }

    /**
     * 应用级展示与功能开关（评分弹窗、强制更新、证明功能等）。
     * 失败或 data 为空：Dart 返回 null。
     */
    suspend fun getDisplayGuide(): ApiResult<ConfigModel> =
        Api.get(ApiPaths.displayGuidePage).parse { env -> env.dataObject()?.let { ConfigModel.fromJson(it) } }

    /**
     * 按国家/地区差异化的积分计费单价。
     * 失败、data 为空或字段不全（Dart 解析抛异常）：Dart 返回 null，调用方保留旧值并重试。
     */
    suspend fun getCountryPricing(): ApiResult<CountryPricingModel> =
        Api.get(ApiPaths.countryPricing).parse { CountryPricingModel.fromJsonOrNull(it.dataObject()) }

    /** 自定义 MV 计费配置（data 对象，如 `customOnePoint`）。失败：调用方按约定做有限次重试。 */
    suspend fun getMvConfigs(): ApiResult<JSONObject> = Api.get(ApiPaths.mvConfigs).asDataObject()

    /**
     * 把极光 Registration ID 绑到当前登录用户（字段名仍是 fcmToken）。
     * 成功返回后端的 data 字符串（正常为 "success"）；失败或 data 为空：Dart 返回 "error"。
     */
    suspend fun bindUserJPushToken(registrationId: String): ApiResult<String> =
        Api.post(ApiPaths.bindUserJPushToken, JSONObject().put("fcmToken", registrationId))
            .map { (it.dataAny() as? String) ?: "error" }
}
