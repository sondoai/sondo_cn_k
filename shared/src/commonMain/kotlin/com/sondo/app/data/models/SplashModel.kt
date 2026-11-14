package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 开屏实验配置（Dart: lib/data/models/splash_model.dart `SplashConfig`）。 */
data class SplashConfig(
    var exposureId: String = "",
    var experimentId: Long = -1L,
    var variantCode: String = "",
    var splashConfig: String = "",
    var assignReason: String = "",
    /** 使用 ab 分流分配记录 ID */
    var assignmentId: Long = -1L,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("exposureId", exposureId)
        .put("experimentId", experimentId)
        .put("variantCode", variantCode)
        .put("splashConfig", splashConfig)
        .put("assignReason", assignReason)
        .put("assignmentId", assignmentId)

    companion object {
        fun fromJson(json: JSONObject): SplashConfig = SplashConfig(
            exposureId = json.jStr("exposureId"),
            experimentId = json.jLong("experimentId", -1L),
            variantCode = json.jStr("variantCode"),
            splashConfig = json.jStr("splashConfig"),
            assignReason = json.jStr("assignReason"),
            assignmentId = json.jLong("assignmentId", -1L),
        )

        fun listFromJson(arr: JSONArray?): List<SplashConfig> = arr.jMap { fromJson(it) }
    }
}

/** 冷启动分配引导/试用版本（Dart: lib/data/models/splash_model.dart `OnboardingAbAssign`）。 */
data class OnboardingAbAssign(
    /** 是否在国家白名单；false 时不进分流、不落库 */
    var inWhitelist: Boolean = false,
    /** 分配记录 ID，本地应持久化 */
    var assignmentId: Long = -1L,
    /** 分配版本（归因锁定） */
    var version: String = "",
    var deviceId: String = "",
    /** 服务端判定国家码 */
    var countryCode: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("inWhitelist", inWhitelist)
        .put("assignmentId", assignmentId)
        .put("version", version)
        .put("deviceId", deviceId)
        .put("countryCode", countryCode)

    companion object {
        fun fromJson(json: JSONObject): OnboardingAbAssign = OnboardingAbAssign(
            inWhitelist = json.jBool("inWhitelist", false),
            assignmentId = json.jLong("assignmentId", -1L),
            version = json.jStr("version"),
            deviceId = json.jStr("deviceId"),
            countryCode = json.jStr("countryCode"),
        )

        fun listFromJson(arr: JSONArray?): List<OnboardingAbAssign> = arr.jMap { fromJson(it) }
    }
}
