package com.sondo.app.features.guide.data

import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiResult
import com.sondo.app.core.http.ReqOptions
import com.sondo.app.core.http.dataObj
import com.sondo.app.core.storage.Sp
import com.sondo.app.data.models.OnboardingAbAssign
import com.sondo.app.data.models.SplashConfig
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 新手引导数据（Dart `GuideRemoteDataSource` + `GuideRepositoryImpl` + `GuideMapper`）。
 * Dart 的 `SplashConfigEntity` / `OnboardingAbAssignEntity` 与 DTO 字段完全一致，这里直接用 data 层模型。
 */
object GuideApi {
    /**
     * 拉取 onboarding AB 分配，`GET /member/onboarding-ab/assign`。
     * 沿用启动期短超时（2s）、不带登录态、不弹全局错误。Dart 在任何失败时返回空的分配结果（不在白名单）。
     */
    suspend fun getOnboardingAbAssign(deviceId: String): ApiResult<OnboardingAbAssign> =
        Api.get(
            ApiPaths.onboardingAbAssign,
            query = mapOf("deviceId" to deviceId),
            opts = ReqOptions(noToken = true, quiet = true, timeoutMs = Api.TIMEOUT_SHORT),
        ).map { OnboardingAbAssign.fromJson(it.dataObj() ?: JSONObject()) }

    /** 从本地存储读取 Splash 引导配置（`StorageKey.splashGuide`）。 */
    fun getSplashConfig(): SplashConfig = Sp.getJson(StorageKey.splashGuide)?.let { SplashConfig.fromJson(it) } ?: SplashConfig()

    fun saveSplashConfig(config: SplashConfig) = Sp.setJson(StorageKey.splashGuide, config.toJson())
}
