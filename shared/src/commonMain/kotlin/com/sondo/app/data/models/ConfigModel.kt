package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 应用配置（Dart: lib/data/models/config_model.dart `ConfigModel`）。
 *
 * Dart 侧 `secondPoint` 声明为 double，后端回整数时整个解析会抛类型错误；这里按数字宽松解析。
 */
data class ConfigModel(
    /** ios 是否显示引导页 0 不显示 大于1 显示 */
    var ios: Int = 1,
    /** ios 是否开启facebook 埋点上报 */
    var isFacebookOpenIos: Int = 0,
    /** ios 是否检查更新 */
    var isCheckUpdateIos: Int = 0,
    /** ios 是否强制更新 */
    var isMandatoryUpdateIos: Int = 0,
    /** ios 是否开启评分 */
    var commentOpenIos: Int = 0,
    /** ios 首页是否开启评分 */
    var homeCommentOpenIos: Int = 0,
    /** ios 是否开启通知页面 */
    var sondoSubDisplayIos: Int = 0,
    /** ios 评分版本号 */
    var guideScoreControlBuildNoIos: Int = 0,
    /** android 是否显示引导页 0 不显示 大于1 显示 */
    var android: Int = 1,
    /** android 是否开启facebook 埋点上报 */
    var isFacebookOpen: Int = 0,
    /** android 是否检查更新 */
    var isCheckUpdateAndroid: Int = 0,
    /** android 是否强制更新 */
    var isMandatoryUpdateAndroid: Int = 0,
    /** android 是否开启评分 */
    var commentOpenAndroid: Int = 0,
    /** android 首页是否开启评分 */
    var homeCommentOpenAndroid: Int = 0,
    /** android 是否开启通知页面 */
    var sondoSubDisplay: Int = 0,
    /** android 评分版本号 */
    var guideScoreControlBuildNoAndroid: Int = 0,
    /** 新用户登录赠送的积分 */
    var newUserPoint: Int = 0,
    /** 自定义mv收费模式 */
    var customMvChargingModel: Int = 0,
    /** 自定义mv 每秒积分 */
    var secondPoint: Double = 7.0,
    /** 对口型mv每秒积分 */
    var dkxSecondMvPoint: Int = 10,
    /** 重新生成封面所需积分 */
    var againMergePicPoint: Int = 5,
    /** 重新生成封面所需积分 v1 */
    var againMergePicPointV1: Int = 10,
    /** （编辑后）重新合成视频所需积分 */
    var againMergePoint: Int = 10,
    /** 是否显示使用说明 */
    var displayUseExplain: Int = 0,
    /** 自定义mv描述是否必填 */
    var mvDescIsNeed: Int = 0,
    /** mv(手动、自动)创建开关 0 关闭 1 开启 */
    var customMvZdSdOpen: Int = 0,
    /** 生成四视图图片所需积分 */
    var genFourViewImgPoint: Int = 50,
    /** 每日优化描述次数限制 */
    var dailyLimit: Int = 30,
    /** 重新生成图片积分 */
    var regenerateCustomShotPicPoint: Int = 5,
    /** 重新生成视频积分 */
    var regenerateCustomShotVideoPerSec: Int = 8,
    /** 续跑全片积分随机：1=创建时 base±20 随机；0 或其它=仅 base */
    var mvRandomJsPoint: Int = 0,
    /** 是否开启证明功能(播放列表页、歌曲详情页) */
    var certificateExposureOpen: Int = 0,
    /** 是否开启证明功能(所有入口) */
    var certificateOpen: Int = 1,
    /** mv故事线智能选曲多少秒 */
    var mvFlowV2MusicChoseSecond: Int = 0,
    /** mv故事线智能选曲多少秒(Ultra) */
    var mvFlowV2AiDanceMusicChoseSecond: Int = 0,
    /** 是否开启旗舰版(MV) */
    var mvFlagshipOpen: Int = 0,
    /** 是否显示特效开关：1开启，0关闭 */
    var mvSpecialEffectsOpen: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("ios", ios)
        .put("isFacebookOpenIos", isFacebookOpenIos)
        .put("isCheckUpdateIos", isCheckUpdateIos)
        .put("isMandatoryUpdateIos", isMandatoryUpdateIos)
        .put("commentOpenIos", commentOpenIos)
        .put("homeCommentOpenIos", homeCommentOpenIos)
        .put("sondoSubDisplayIos", sondoSubDisplayIos)
        .put("guideScoreControlBuildNoIos", guideScoreControlBuildNoIos)
        .put("android", android)
        .put("isFacebookOpen", isFacebookOpen)
        .put("isCheckUpdateAndroid", isCheckUpdateAndroid)
        .put("isMandatoryUpdateAndroid", isMandatoryUpdateAndroid)
        .put("commentOpenAndroid", commentOpenAndroid)
        .put("homeCommentOpenAndroid", homeCommentOpenAndroid)
        .put("sondoSubDisplay", sondoSubDisplay)
        .put("guideScoreControlBuildNoAndroid", guideScoreControlBuildNoAndroid)
        .put("newUserPoint", newUserPoint)
        .put("customMvChargingModel", customMvChargingModel)
        .put("secondPoint", secondPoint)
        .put("dkxSecondMvPoint", dkxSecondMvPoint)
        .put("againMergePicPoint", againMergePicPoint)
        .put("againMergePicPointV1", againMergePicPointV1)
        .put("againMergePoint", againMergePoint)
        .put("displayUseExplain", displayUseExplain)
        .put("mvDescIsNeed", mvDescIsNeed)
        .put("customMvZdSdOpen", customMvZdSdOpen)
        .put("genFourViewImgPoint", genFourViewImgPoint)
        .put("dailyLimit", dailyLimit)
        .put("regenerateCustomShotPicPoint", regenerateCustomShotPicPoint)
        .put("regenerateCustomShotVideoPerSec", regenerateCustomShotVideoPerSec)
        .put("mvRandomJsPoint", mvRandomJsPoint)
        .put("certificateExposureOpen", certificateExposureOpen)
        .put("certificateOpen", certificateOpen)
        .put("mvFlowV2MusicChoseSecond", mvFlowV2MusicChoseSecond)
        .put("mvFlowV2AiDanceMusicChoseSecond", mvFlowV2AiDanceMusicChoseSecond)
        .put("mvFlagshipOpen", mvFlagshipOpen)
        .put("mvSpecialEffectsOpen", mvSpecialEffectsOpen)

    companion object {
        fun fromJson(json: JSONObject): ConfigModel = ConfigModel(
            ios = json.jInt("ios", 1),
            isFacebookOpenIos = json.jInt("isFacebookOpenIos", 0),
            isCheckUpdateIos = json.jInt("isCheckUpdateIos", 0),
            isMandatoryUpdateIos = json.jInt("isMandatoryUpdateIos", 0),
            commentOpenIos = json.jInt("commentOpenIos", 0),
            homeCommentOpenIos = json.jInt("homeCommentOpenIos", 0),
            sondoSubDisplayIos = json.jInt("sondoSubDisplayIos", 0),
            guideScoreControlBuildNoIos = json.jInt("guideScoreControlBuildNoIos", 0),
            android = json.jInt("android", 1),
            isFacebookOpen = json.jInt("isFacebookOpen", 0),
            isCheckUpdateAndroid = json.jInt("isCheckUpdateAndroid", 0),
            isMandatoryUpdateAndroid = json.jInt("isMandatoryUpdateAndroid", 0),
            commentOpenAndroid = json.jInt("commentOpenAndroid", 0),
            homeCommentOpenAndroid = json.jInt("homeCommentOpenAndroid", 0),
            sondoSubDisplay = json.jInt("sondoSubDisplay", 0),
            guideScoreControlBuildNoAndroid = json.jInt("guideScoreControlBuildNoAndroid", 0),
            newUserPoint = json.jInt("newUserPoint", 0),
            customMvChargingModel = json.jInt("customMvChargingModel", 0),
            secondPoint = json.jDbl("secondPoint", 5.5),
            dkxSecondMvPoint = json.jInt("dkxSecondMvPoint", 10),
            againMergePicPoint = json.jInt("againMergePicPoint", 5),
            againMergePicPointV1 = json.jInt("againMergePicPointV1", 10),
            againMergePoint = json.jInt("againMergePoint", 10),
            displayUseExplain = json.jInt("displayUseExplain", 0),
            mvDescIsNeed = json.jInt("mvDescIsNeed", 0),
            customMvZdSdOpen = json.jInt("customMvZdSdOpen", 0),
            genFourViewImgPoint = json.jInt("genFourViewImgPoint", 50),
            dailyLimit = json.jInt("dailyLimit", 30),
            regenerateCustomShotPicPoint = json.jInt("regenerateCustomShotPicPoint", 5),
            regenerateCustomShotVideoPerSec = json.jInt("regenerateCustomShotVideoPerSec", 8),
            mvRandomJsPoint = json.jInt("mvRandomJsPoint", 0),
            certificateExposureOpen = json.jInt("certificateExposureOpen", 0),
            certificateOpen = json.jInt("certificateOpen", 1),
            mvFlowV2MusicChoseSecond = json.jInt("mvFlowV2MusicChoseSecond", 0),
            mvFlowV2AiDanceMusicChoseSecond = json.jInt("mvFlowV2AiDanceMusicChoseSecond", 0),
            mvFlagshipOpen = json.jInt("mvFlagshipOpen", 0),
            mvSpecialEffectsOpen = json.jInt("mvSpecialEffectsOpen", 0),
        )

        fun listFromJson(arr: JSONArray?): List<ConfigModel> = arr.jMap { fromJson(it) }
    }
}
