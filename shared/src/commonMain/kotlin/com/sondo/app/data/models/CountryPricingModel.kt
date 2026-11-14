package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 国家定价（Dart: lib/data/models/country_pricing.dart `CountryPricingModel`）。 */
data class CountryPricingModel(
    /** 用户国家代码 */
    var country: String = "",
    /** 是否使用系统默认分组 */
    var defaultGroup: Boolean = false,
    /** 抽卡高清视频 N积分/秒 */
    var gachaHdVideoPoint: Int = 0,
    /** 抽卡图片 N积分/张 */
    var gachaImagePoint: Int = 0,
    /** 抽卡标清视频 N积分/秒 */
    var gachaSdVideoPoint: Int = 0,
    /** 高清视频 N积分/秒 */
    var hdVideoPoint: Int = 0,
    /** 图片 N积分/张 */
    var imagePoint: Int = 0,
    /** 音乐创作 N积分/次 */
    var musicCreatePoint: Int = 10,
    /** 定价分组编号 */
    var pricingGroupId: Long = 0L,
    /** 定价分组名称 */
    var pricingGroupName: String = "",
    /** 标清视频 N积分/秒 */
    var sdVideoPoint: Int = 0,
    // ------ Seedance mv ------
    /** 创建 MV */
    var mvSeedanceCreatePoint: Int = 0,
    /** 续跑全片 标清（积分/秒） */
    var mvSeedanceSdVideoPoint: Int = 0,
    /** 分镜重生 标清（积分/秒） */
    var mvSeedanceGachaSdVideoPoint: Int = 0,
    /** 续跑全片 高清（积分/秒） */
    var mvSeedanceHdVideoPoint: Int = 0,
    /** 分镜重生 高清（积分/秒） */
    var mvSeedanceGachaHdVideoPoint: Int = 0,
    /** 分镜重生图片（积分/张） */
    var mvSeedanceGachaImagePoint: Int = 0,
    /** 封面重生积分 */
    var mvSeedanceCoverRegenPoint: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("country", country)
        .put("defaultGroup", defaultGroup)
        .put("gachaHdVideoPoint", gachaHdVideoPoint)
        .put("gachaImagePoint", gachaImagePoint)
        .put("gachaSdVideoPoint", gachaSdVideoPoint)
        .put("hdVideoPoint", hdVideoPoint)
        .put("imagePoint", imagePoint)
        .put("musicCreatePoint", musicCreatePoint)
        .put("pricingGroupId", pricingGroupId)
        .put("pricingGroupName", pricingGroupName)
        .put("sdVideoPoint", sdVideoPoint)
        .put("mvSeedanceCreatePoint", mvSeedanceCreatePoint)
        .put("mvSeedanceSdVideoPoint", mvSeedanceSdVideoPoint)
        .put("mvSeedanceGachaSdVideoPoint", mvSeedanceGachaSdVideoPoint)
        .put("mvSeedanceHdVideoPoint", mvSeedanceHdVideoPoint)
        .put("mvSeedanceGachaHdVideoPoint", mvSeedanceGachaHdVideoPoint)
        .put("mvSeedanceGachaImagePoint", mvSeedanceGachaImagePoint)
        .put("mvSeedanceCoverRegenPoint", mvSeedanceCoverRegenPoint)

    companion object {
        /** 宽松解析：缺失字段用构造默认值。 */
        fun fromJson(json: JSONObject): CountryPricingModel = CountryPricingModel(
            country = json.jStr("country"),
            defaultGroup = json.jBool("defaultGroup", false),
            gachaHdVideoPoint = json.jInt("gachaHdVideoPoint", 0),
            gachaImagePoint = json.jInt("gachaImagePoint", 0),
            gachaSdVideoPoint = json.jInt("gachaSdVideoPoint", 0),
            hdVideoPoint = json.jInt("hdVideoPoint", 0),
            imagePoint = json.jInt("imagePoint", 0),
            musicCreatePoint = json.jInt("musicCreatePoint", 10),
            pricingGroupId = json.jLong("pricingGroupId", 0L),
            pricingGroupName = json.jStr("pricingGroupName"),
            sdVideoPoint = json.jInt("sdVideoPoint", 0),
            mvSeedanceCreatePoint = json.jInt("mvSeedanceCreatePoint", 0),
            mvSeedanceSdVideoPoint = json.jInt("mvSeedanceSdVideoPoint", 0),
            mvSeedanceGachaSdVideoPoint = json.jInt("mvSeedanceGachaSdVideoPoint", 0),
            mvSeedanceHdVideoPoint = json.jInt("mvSeedanceHdVideoPoint", 0),
            mvSeedanceGachaHdVideoPoint = json.jInt("mvSeedanceGachaHdVideoPoint", 0),
            mvSeedanceGachaImagePoint = json.jInt("mvSeedanceGachaImagePoint", 0),
            mvSeedanceCoverRegenPoint = json.jInt("mvSeedanceCoverRegenPoint", 0),
        )

        /**
         * Dart 的 fromJson 除 pricingGroupName 外全部必填：任一字段缺失 / 为 null 整个解析抛异常，
         * 调用方拿不到定价、保留旧值。这里返回 null 表达同一结果。
         */
        fun fromJsonOrNull(json: JSONObject?): CountryPricingModel? {
            if (json == null) return null
            return parseOrNull {
                CountryPricingModel(
                    country = json.reqStr("country"),
                    defaultGroup = json.reqBool("defaultGroup"),
                    gachaHdVideoPoint = json.reqInt("gachaHdVideoPoint"),
                    gachaImagePoint = json.reqInt("gachaImagePoint"),
                    gachaSdVideoPoint = json.reqInt("gachaSdVideoPoint"),
                    hdVideoPoint = json.reqInt("hdVideoPoint"),
                    imagePoint = json.reqInt("imagePoint"),
                    musicCreatePoint = json.reqInt("musicCreatePoint"),
                    pricingGroupId = json.reqLong("pricingGroupId"),
                    pricingGroupName = json.jStr("pricingGroupName"),
                    sdVideoPoint = json.reqInt("sdVideoPoint"),
                    mvSeedanceCreatePoint = json.reqInt("mvSeedanceCreatePoint"),
                    mvSeedanceSdVideoPoint = json.reqInt("mvSeedanceSdVideoPoint"),
                    mvSeedanceGachaSdVideoPoint = json.reqInt("mvSeedanceGachaSdVideoPoint"),
                    mvSeedanceHdVideoPoint = json.reqInt("mvSeedanceHdVideoPoint"),
                    mvSeedanceGachaHdVideoPoint = json.reqInt("mvSeedanceGachaHdVideoPoint"),
                    mvSeedanceGachaImagePoint = json.reqInt("mvSeedanceGachaImagePoint"),
                    mvSeedanceCoverRegenPoint = json.reqInt("mvSeedanceCoverRegenPoint"),
                )
            }
        }

        fun listFromJson(arr: JSONArray?): List<CountryPricingModel> = arr.jMap { fromJson(it) }
    }
}
