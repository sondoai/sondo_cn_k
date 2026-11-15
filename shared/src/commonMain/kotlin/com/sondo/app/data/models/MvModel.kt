package com.sondo.app.data.models

import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.storage.Sp
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** MV 任务状态（Dart: lib/data/models/mv_model.dart `MvStatus`）。枚举名即 Dart 的 `name`。 */
enum class MvStatus(val value: Int) {
    /** 排队中 */
    queue(0),

    /** 生成中 */
    generating(1),

    /** 生成成功 */
    success(2),

    /** 合成失败 */
    mergeFailed(3),

    /** 生成失败 */
    failed(4),

    /** 等待确认 */
    waitingConfirm(5),

    /** 图片生成失败 */
    imageFailed(6),

    /** 视频生成失败 */
    videoFailed(7);

    companion object {
        fun getStatus(value: Int): MvStatus = values().firstOrNull { it.value == value } ?: queue
        fun fromValue(value: Int): MvStatus = getStatus(value)
        fun getStatusValue(status: MvStatus): Int = status.value
    }
}

/** MV 发布状态（Dart: lib/data/models/mv_model.dart `MvPublishStatus`）。 */
enum class MvPublishStatus(val value: Int) {
    /** 未发布 */
    unpublish(0),

    /** 已发布 */
    published(1),

    /** 审核中 */
    auditing(2),

    /** 审核失败 */
    auditFailed(3);

    companion object {
        fun getStatus(value: Int): MvPublishStatus = values().firstOrNull { it.value == value } ?: unpublish
        fun fromValue(value: Int): MvPublishStatus = getStatus(value)
        fun getStatusValue(status: MvPublishStatus): Int = status.value
    }
}

/**
 * MV 任务（Dart: lib/data/models/mv_model.dart `MvModel`）。
 *
 * 注意：Dart 的 `copyWith` 不带 chargingModel / useFourViewGen（复制后被重置为 null / 0）；
 * Kotlin 的 `copy()` 会保留这两个字段，[copyWith] 则与 Dart 完全一致。
 */
data class MvModel(
    var id: Long = 0L,
    var musicId: Long? = null,
    var userId: Long = 0L,
    var title: String = "",
    var artist: String = "",
    var audioUrl: String = "",
    var coverImage: String? = null,
    /** 四视图图片 */
    var fourViewImg: String = "",
    var status: MvStatus = MvStatus.queue,
    /** 0: 文生视频, 1: 文生图->图生视频 */
    var mvType: Int? = 0,
    /** 0：图片生成阶段 1：视频生成阶段 */
    var taskPhase: Int? = 0,
    /** 故事板分镜两步提示词是否完成 0：未完成 1：完成 */
    var storyboardStatus: Int? = 0,
    var resultUrl: String? = null,
    var errorMsg: String? = null,
    var mvStyle: String? = null,
    /** "00:00,02:36" */
    var subDurationScope: String = "",
    /** 第一帧图片 */
    var firstFrameImg: String? = null,
    /** 动态图 */
    var dynamicGraph: String? = null,
    /** 是否购买证书 */
    var isBuyCertificate: Boolean? = false,
    /** 是否更新 */
    var isUpdt: Int? = 0,
    /** 视频比例 */
    var imageComp: String = "16:9",
    /** 生成模式 0：旧模式 1：模板 2: 自定义MV模式 */
    var genMode: Int = 0,
    /** 信息流版本：1=V1 information_flow_custom，2=V2 动态卡片 */
    var informationFlowVersion: Int = 1,
    /** 自定义MV收费模式 0：两步收费 1：提前收费 null 其他模式 */
    var chargingModel: Int? = null,
    /** 创建时间 */
    var createTime: Long = 0L,
    /** 视频质量 480 720 */
    var quality: Int = 480,
    /** 0 否 1 是 是否展示标题 */
    var isTitle: Int = 0,
    /** 0 否 1 是 是否展示作者名称 */
    var isName: Int = 0,
    /** 0 否 1 是 是否展示歌词(null 不展示选项) */
    var isLyric: Int? = null,
    /** 0 否 1 是 是否去除水印 */
    var isWatermark: Int = 1,
    /** 是否收藏 */
    var isCollect: Boolean = false,
    /** 收藏数量 */
    var collectNum: Int = 0,
    /** 转发数量 */
    var repostCount: Int = 0,
    /** 高级版 mv类型 0 常规类型，1 对口型类型 */
    var modelType: Int = 0,
    /** mv版本 0 家豪版本，1 译峰版本 */
    var mvVersion: Int = 0,
    /** 点击数 */
    var mvHitNum: Int = 0,
    /** 艺术家头像 */
    var avatar: String = "",
    /** 艺术家昵称 */
    var nickname: String = "",
    /** 封面提示词 */
    var coverPrompt: String = "",
    /** 连续生成完整mv需要积分 */
    var continueFullMvNeedPoint: Int = 0,
    /** 高潮试看锁定。0 锁定，1 已解锁。缺失按未锁定，不展示解锁入口。 */
    var unlock: Int? = null,
    /** 试看起点（秒）。原音频上的绝对秒，和 [subDurationScope] 同一条时间轴。仅 unlock=0 时有值。 */
    var trialStartSeconds: Double = 0.0,
    /** 试看终点（秒），口径同 [trialStartSeconds]。 */
    var trialEndSeconds: Double = 0.0,
    /** 0 否 1 是 是否使用四视图生成 */
    var useFourViewGen: Int = 0,
    /** 是否试用MV */
    var trialMvFlag: Boolean = false,
    /** 试用阶段：1四视图 2图片 3视频 4合成 */
    var trialStage: Int = 0,
    /** 试用模板组ID */
    var trialTemplateId: Long = 0L,
    /** 是否展示特效开关 */
    var capcutSwitchVisible: Boolean = false,
    /** 是否启用特效 */
    var capcutEnabled: Boolean = false,
    /** 做同款剧情。null 或空白表示没有可复用描述。 */
    var customStoryPlot: String? = null,
    var mvPublish: VideoPublishStatus = VideoPublishStatus.unpublish,
) {
    /** "01:32,02:36"（开始，结束）→ 总时长 mm:ss */
    val durationStr: String
        get() {
            val parts = subDurationScope.split(",")
            if (parts.size != 2) return "00:00"
            fun parseTime(time: String): Int {
                val t = time.split(":")
                val minutes = t.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
                val seconds = t.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                return minutes * 60 + seconds
            }
            val diff = parseTime(parts[1]) - parseTime(parts[0])
            // Dart 的 remainder 向零取整，负数保持负号
            val totalMinutes = (diff / 60) % 60
            val totalSeconds = diff % 60
            return "${totalMinutes.toString().padStart(2, '0')}:${totalSeconds.toString().padStart(2, '0')}"
        }

    /** createTime 相对时间 */
    val timeStr: String get() = ModelDate.formatDateTime(createTime)

    val createTimeStr: String get() = ModelDate.formatDate(createTime)

    /** taskPhase 等于 0：以前的数据并且是生图阶段才能进入以前的故事版 */
    val isImagePhase: Boolean get() = taskPhase == 0

    /** genMode 等于 0：以前的数据 */
    val isOldData: Boolean get() = genMode == 0

    /** firstFrameImg 为空时用 coverImage */
    val cover: String
        get() {
            if (firstFrameImg.isNullOrEmpty()) return coverImage ?: ""
            return firstFrameImg ?: ""
        }

    val isFourViewGen: Boolean
        get() {
            if (firstFrameImg.isNullOrEmpty()) return useFourViewGen == 1
            return false
        }

    val nicknameStr: String
        get() {
            if (nickname.isNotEmpty() && nickname.contains("@")) {
                return "${nickname.split("@").first()}@***"
            }
            return nickname
        }

    /** 0：待生成 1：生成中 5：等待确认 6：图片生成失败 7：视频生成失败 可以进入信息流 */
    val isFlowData: Boolean
        get() = status == MvStatus.queue ||
            status == MvStatus.generating ||
            status == MvStatus.waitingConfirm ||
            status == MvStatus.imageFailed ||
            status == MvStatus.videoFailed

    /** 收费模式 0：两步收费(开始收10积分) 1：提前收费(全部收) */
    val chargModel: Int get() = chargingModel ?: 0

    /**
     * 与 Dart `MvModel.copyWith` 逐字段一致：参数为 null 表示沿用原值；
     * **不带 chargingModel / useFourViewGen**，复制结果里这两个字段被重置为 null / 0（参考工程的现有行为）。
     * 需要保留全部字段时用 data class 的 `copy()`。
     */
    fun copyWith(
        id: Long? = null,
        musicId: Long? = null,
        userId: Long? = null,
        title: String? = null,
        artist: String? = null,
        audioUrl: String? = null,
        coverImage: String? = null,
        status: MvStatus? = null,
        resultUrl: String? = null,
        errorMsg: String? = null,
        mvStyle: String? = null,
        subDurationScope: String? = null,
        firstFrameImg: String? = null,
        dynamicGraph: String? = null,
        isUpdt: Int? = null,
        mvType: Int? = null,
        taskPhase: Int? = null,
        storyboardStatus: Int? = null,
        isBuyCertificate: Boolean? = null,
        imageComp: String? = null,
        genMode: Int? = null,
        informationFlowVersion: Int? = null,
        createTime: Long? = null,
        isTitle: Int? = null,
        isName: Int? = null,
        isLyric: Int? = null,
        isWatermark: Int? = null,
        isCollect: Boolean? = null,
        collectNum: Int? = null,
        repostCount: Int? = null,
        mvHitNum: Int? = null,
        mvPublish: VideoPublishStatus? = null,
        avatar: String? = null,
        nickname: String? = null,
        modelType: Int? = null,
        mvVersion: Int? = null,
        coverPrompt: String? = null,
        quality: Int? = null,
        fourViewImg: String? = null,
        continueFullMvNeedPoint: Int? = null,
        unlock: Int? = null,
        trialStartSeconds: Double? = null,
        trialEndSeconds: Double? = null,
        trialMvFlag: Boolean? = null,
        trialStage: Int? = null,
        trialTemplateId: Long? = null,
        capcutSwitchVisible: Boolean? = null,
        capcutEnabled: Boolean? = null,
        customStoryPlot: String? = null,
    ): MvModel = MvModel(
        id = id ?: this.id,
        musicId = musicId ?: this.musicId,
        userId = userId ?: this.userId,
        title = title ?: this.title,
        artist = artist ?: this.artist,
        audioUrl = audioUrl ?: this.audioUrl,
        coverImage = coverImage ?: this.coverImage,
        status = status ?: this.status,
        resultUrl = resultUrl ?: this.resultUrl,
        errorMsg = errorMsg ?: this.errorMsg,
        mvStyle = mvStyle ?: this.mvStyle,
        subDurationScope = subDurationScope ?: this.subDurationScope,
        firstFrameImg = firstFrameImg ?: this.firstFrameImg,
        dynamicGraph = dynamicGraph ?: this.dynamicGraph,
        isUpdt = isUpdt ?: this.isUpdt,
        mvType = mvType ?: this.mvType,
        taskPhase = taskPhase ?: this.taskPhase,
        storyboardStatus = storyboardStatus ?: this.storyboardStatus,
        isBuyCertificate = isBuyCertificate ?: this.isBuyCertificate,
        imageComp = imageComp ?: this.imageComp,
        genMode = genMode ?: this.genMode,
        informationFlowVersion = informationFlowVersion ?: this.informationFlowVersion,
        createTime = createTime ?: this.createTime,
        isTitle = isTitle ?: this.isTitle,
        isName = isName ?: this.isName,
        isLyric = isLyric ?: this.isLyric,
        isWatermark = isWatermark ?: this.isWatermark,
        isCollect = isCollect ?: this.isCollect,
        collectNum = collectNum ?: this.collectNum,
        repostCount = repostCount ?: this.repostCount,
        mvHitNum = mvHitNum ?: this.mvHitNum,
        mvPublish = mvPublish ?: this.mvPublish,
        avatar = avatar ?: this.avatar,
        nickname = nickname ?: this.nickname,
        modelType = modelType ?: this.modelType,
        mvVersion = mvVersion ?: this.mvVersion,
        coverPrompt = coverPrompt ?: this.coverPrompt,
        quality = quality ?: this.quality,
        fourViewImg = fourViewImg ?: this.fourViewImg,
        continueFullMvNeedPoint = continueFullMvNeedPoint ?: this.continueFullMvNeedPoint,
        unlock = unlock ?: this.unlock,
        trialStartSeconds = trialStartSeconds ?: this.trialStartSeconds,
        trialEndSeconds = trialEndSeconds ?: this.trialEndSeconds,
        trialMvFlag = trialMvFlag ?: this.trialMvFlag,
        trialStage = trialStage ?: this.trialStage,
        trialTemplateId = trialTemplateId ?: this.trialTemplateId,
        capcutSwitchVisible = capcutSwitchVisible ?: this.capcutSwitchVisible,
        capcutEnabled = capcutEnabled ?: this.capcutEnabled,
        customStoryPlot = customStoryPlot ?: this.customStoryPlot,
    )

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("musicId", musicId)
        .put("userId", userId)
        .put("title", title)
        .put("artist", artist)
        .put("audioUrl", audioUrl)
        .put("coverImage", coverImage)
        .put("fourViewImg", fourViewImg)
        .put("status", MvStatus.getStatusValue(status))
        .put("mvType", mvType)
        .put("taskPhase", taskPhase)
        .put("storyboardStatus", storyboardStatus)
        .put("resultUrl", resultUrl)
        .put("errorMsg", errorMsg)
        .put("mvStyle", mvStyle)
        .put("subDurationScope", subDurationScope)
        .put("firstFrameImg", firstFrameImg)
        .put("dynamicGraph", dynamicGraph)
        .put("isBuyCertificate", isBuyCertificate)
        .put("isUpdt", isUpdt)
        .put("imageComp", imageComp)
        .put("genMode", genMode)
        .put("informationFlowVersion", informationFlowVersion)
        .put("chargingModel", chargingModel)
        .put("createTime", createTime)
        .put("quality", quality)
        .put("isTitle", isTitle)
        .put("isName", isName)
        .put("isLyric", isLyric)
        .put("isWatermark", isWatermark)
        .put("isCollect", isCollect)
        .put("collectNum", collectNum)
        .put("repostCount", repostCount)
        .put("modelType", modelType)
        .put("mvVersion", mvVersion)
        .put("mvHitNum", mvHitNum)
        .put("avatar", avatar)
        .put("nickname", nickname)
        .put("coverPrompt", coverPrompt)
        .put("continueFullMvNeedPoint", continueFullMvNeedPoint)
        .put("unlock", unlock)
        .put("trialStartSeconds", trialStartSeconds)
        .put("trialEndSeconds", trialEndSeconds)
        .put("useFourViewGen", useFourViewGen)
        .put("trialMvFlag", trialMvFlag)
        .put("trialStage", trialStage)
        .put("trialTemplateId", trialTemplateId)
        .put("capcutSwitchVisible", capcutSwitchVisible)
        .put("capcutEnabled", capcutEnabled)
        .put("customStoryPlot", customStoryPlot)
        .put("mvPublish", VideoPublishStatus.toValue(mvPublish))

    companion object {
        /** 宽松解析：必填字段缺失时用空值（id=0、title=""、status=queue …）。 */
        fun fromJson(json: JSONObject): MvModel = MvModel(
            id = json.jLong("id", 0L),
            musicId = json.jLongOrNull("musicId"),
            userId = json.jLong("userId", 0L),
            title = json.jStr("title"),
            artist = json.jStr("artist"),
            audioUrl = json.jStr("audioUrl"),
            coverImage = json.jStrOrNull("coverImage"),
            status = MvStatus.getStatus(json.jInt("status", 0)),
            resultUrl = json.jStrOrNull("resultUrl"),
            errorMsg = json.jStrOrNull("errorMsg"),
            mvStyle = json.jStrOrNull("mvStyle"),
            subDurationScope = json.jStr("subDurationScope"),
            firstFrameImg = json.jStrOrNull("firstFrameImg"),
            dynamicGraph = json.jStrOrNull("dynamicGraph"),
            isUpdt = json.jInt("isUpdt", 0),
            mvType = json.jInt("mvType", 0),
            taskPhase = json.jInt("taskPhase", 0),
            storyboardStatus = json.jInt("storyboardStatus", 0),
            isBuyCertificate = json.jBool("isBuyCertificate", false),
            imageComp = json.jStr("imageComp", "16:9"),
            genMode = json.jInt("genMode", 0),
            informationFlowVersion = json.jInt("informationFlowVersion", 1),
            createTime = json.jLong("createTime", 0L),
            chargingModel = json.jIntOrNull("chargingModel"),
            isTitle = json.jInt("isTitle", 0),
            isName = json.jInt("isName", 0),
            isLyric = json.jIntOrNull("isLyric"),
            isWatermark = json.jInt("isWatermark", 1),
            isCollect = json.jBool("isCollect", false),
            collectNum = json.jInt("collectNum", 0),
            repostCount = json.jInt("repostCount", 0),
            mvHitNum = json.jInt("mvHitNum", 0),
            mvPublish = VideoPublishStatus.parse(json.jRaw("mvPublish")),
            avatar = json.jStr("avatar"),
            nickname = json.jStr("nickname"),
            modelType = json.jInt("modelType", 0),
            mvVersion = json.jInt("mvVersion", 0),
            coverPrompt = json.jStr("coverPrompt"),
            quality = json.jInt("quality", 480),
            fourViewImg = json.jStr("fourViewImg"),
            continueFullMvNeedPoint = json.jInt("continueFullMvNeedPoint", 0),
            unlock = json.jIntOrNull("unlock"),
            trialStartSeconds = json.jDblOrNull("trialStartSeconds") ?: json.jDblOrNull("previewStartSec") ?: 0.0,
            trialEndSeconds = json.jDblOrNull("trialEndSeconds") ?: json.jDblOrNull("previewEndSec") ?: 0.0,
            useFourViewGen = json.jInt("useFourViewGen", 0),
            trialMvFlag = json.jBool("trialMvFlag", false),
            trialStage = json.jInt("trialStage", 0),
            trialTemplateId = json.jLong("trialTemplateId", 0L),
            capcutSwitchVisible = json.jBool("capcutSwitchVisible", false),
            capcutEnabled = json.jBool("capcutEnabled", false),
            customStoryPlot = json.jStrOrNull("customStoryPlot"),
        )

        /**
         * Dart 的 id / userId / title / audioUrl / status / subDurationScope 必填（`as num` / `as String`），
         * 任一缺失或为 null 时 fromJson 抛异常，调用方得到 null / 空列表。这里返回 null。
         */
        fun fromJsonOrNull(json: JSONObject?): MvModel? {
            if (json == null) return null
            for (k in arrayOf("id", "userId", "status")) if (json.jRaw(k) !is Number) return null
            for (k in arrayOf("title", "audioUrl", "subDurationScope")) if (json.jRaw(k) !is String) return null
            return fromJson(json)
        }

        fun listFromJson(arr: JSONArray?): List<MvModel> = arr.jMap { fromJson(it) }

        /** 严格列表：任一条解析失败整体为 null（Dart 整个列表解析抛异常）。 */
        fun listFromJsonOrNull(arr: JSONArray?): List<MvModel>? = arr.jMapStrict { fromJsonOrNull(it) }
    }
}

/** 折扣页本地状态（Dart: lib/data/models/mv_model.dart `MvDiscountModel`）。 */
data class MvDiscountModel(
    /** 是否新注册的用户 */
    var isNewUser: Boolean = false,
    /** 是否进入过折扣页 */
    var isEnteredDiscountPage: Boolean = false,
    /** 进入折扣页的到期时间(毫秒级时间戳) */
    var enteredDiscountPageExpireTime: Long = 0L,
    /** 是否进入过订阅页 */
    var isEnteredSubscriptionPage: Boolean = false,
) {
    /** 到期时间小于等于当前时间则超时；0 表示未开始计时。 */
    val isTimeOut: Boolean
        get() {
            if (enteredDiscountPageExpireTime == 0L) return false
            return enteredDiscountPageExpireTime <= ModelDate.now()
        }

    fun saveToStorage() {
        try {
            Sp.setBool(StorageKey.isNewUser, isNewUser)
            Sp.setBool(StorageKey.isEnteredDiscountPage, isEnteredDiscountPage)
            Sp.setLong(StorageKey.enteredDiscountPageExpireTime, enteredDiscountPageExpireTime)
            Sp.setBool(StorageKey.isEnteredSubscriptionPage, isEnteredSubscriptionPage)
        } catch (e: Throwable) {
            // 写失败不影响流程
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("isNewUser", isNewUser)
        .put("isEnteredDiscountPage", isEnteredDiscountPage)
        .put("enteredDiscountPageExpireTime", enteredDiscountPageExpireTime)
        .put("isEnteredSubscriptionPage", isEnteredSubscriptionPage)

    companion object {
        fun getFromStorage(): MvDiscountModel = MvDiscountModel(
            isNewUser = Sp.getBool(StorageKey.isNewUser, false),
            isEnteredDiscountPage = Sp.getBool(StorageKey.isEnteredDiscountPage, false),
            enteredDiscountPageExpireTime = Sp.getLong(StorageKey.enteredDiscountPageExpireTime, 0L),
            isEnteredSubscriptionPage = Sp.getBool(StorageKey.isEnteredSubscriptionPage, false),
        )

        fun fromJson(json: JSONObject): MvDiscountModel = MvDiscountModel(
            isNewUser = json.jBool("isNewUser", false),
            isEnteredDiscountPage = json.jBool("isEnteredDiscountPage", false),
            enteredDiscountPageExpireTime = json.jLong("enteredDiscountPageExpireTime", 0L),
            isEnteredSubscriptionPage = json.jBool("isEnteredSubscriptionPage", false),
        )

        fun listFromJson(arr: JSONArray?): List<MvDiscountModel> = arr.jMap { fromJson(it) }
    }
}

/** MV 评分 / 反馈状态（Dart: lib/data/models/mv_model.dart `MvFeedbackModel`）。 */
data class MvFeedbackModel(
    var taskId: Long = 0L,
    var storyRatingEnabled: Boolean = false,
    var feedbackEnabled: Boolean = false,
    var hasRated: Boolean = false,
    var hasFeedback: Boolean = false,
    var starRating: Int? = null,
    var recommendTagCodes: List<String> = emptyList(),
    var content: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("taskId", taskId)
        .put("storyRatingEnabled", storyRatingEnabled)
        .put("feedbackEnabled", feedbackEnabled)
        .put("hasRated", hasRated)
        .put("hasFeedback", hasFeedback)
        .put("starRating", starRating)
        .put("recommendTagCodes", recommendTagCodes.toJsonStringArray())
        .put("content", content)

    companion object {
        fun fromJson(json: JSONObject): MvFeedbackModel = MvFeedbackModel(
            taskId = json.jLong("taskId", 0L),
            storyRatingEnabled = json.jBool("storyRatingEnabled", false),
            feedbackEnabled = json.jBool("feedbackEnabled", false),
            hasRated = json.jBool("hasRated", false),
            hasFeedback = json.jBool("hasFeedback", false),
            starRating = json.jInt("starRating", 0),
            recommendTagCodes = json.jArr("recommendTagCodes").jStrList(),
            content = json.jStr("content"),
        )

        fun listFromJson(arr: JSONArray?): List<MvFeedbackModel> = arr.jMap { fromJson(it) }
    }
}

/** MV 评分弹层的推荐标签（Dart: lib/data/models/mv_model.dart `DanceFeedbackTag`）。类名沿用历史接口字段。 */
data class DanceFeedbackTag(
    var code: String = "",
    var label: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("code", code).put("label", label)

    companion object {
        fun fromJson(json: JSONObject): DanceFeedbackTag = DanceFeedbackTag(json.jStr("code"), json.jStr("label"))

        fun listFromJson(arr: JSONArray?): List<DanceFeedbackTag> = arr.jMap { fromJson(it) }
    }
}

/** 某一星级下的推荐标签列表（Dart: lib/data/models/mv_model.dart `DanceFeedback`）。 */
data class DanceFeedback(
    var starRating: Int = 0,
    var tags: List<DanceFeedbackTag> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("starRating", starRating)
        .put("tags", tags.toJsonArray { it.toJson() })

    companion object {
        fun fromJson(json: JSONObject): DanceFeedback = DanceFeedback(
            starRating = json.jInt("starRating", 0),
            tags = DanceFeedbackTag.listFromJson(json.jArr("tags")),
        )

        fun listFromJson(arr: JSONArray?): List<DanceFeedback> = arr.jMap { fromJson(it) }
    }
}
