package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 信息流步骤状态（Dart: lib/data/models/mv_think_model.dart `MvThinkStepStatus`）。 */
enum class MvThinkStepStatus {
    generating,
    success,
    failed;

    companion object {
        /** 数字 1=success、2=failed、其余 generating；字符串按枚举名（不区分大小写）。 */
        fun parse(value: Any?): MvThinkStepStatus {
            if (value is MvThinkStepStatus) return value
            if (value is Number) {
                return when (value.toInt()) {
                    1 -> success
                    2 -> failed
                    else -> generating
                }
            }
            if (value is String) {
                val key = value.trim().lowercase()
                return values().firstOrNull { it.name.lowercase() == key } ?: generating
            }
            return generating
        }
    }
}

/** 信息流卡片状态（Dart: lib/data/models/mv_think_model.dart `MvThinkCardStatus`）。 */
enum class MvThinkCardStatus {
    generating,
    success,
    locked,
    failed;

    companion object {
        /** 数字 1=success、2=failed、3=locked、其余 generating；纯数字字符串同数字；未知值按 success。 */
        fun parse(value: Any?): MvThinkCardStatus {
            if (value is MvThinkCardStatus) return value
            if (value is Number) {
                return when (value.toInt()) {
                    1 -> success
                    2 -> failed
                    3 -> locked
                    else -> generating
                }
            }
            if (value is String) {
                val key = value.trim().lowercase()
                if (key.isNotEmpty() && key.all { it in '0'..'9' }) {
                    return parse(key.toLongOrNull()?.toInt() ?: 0)
                }
                return values().firstOrNull { it.name.lowercase() == key } ?: success
            }
            return success
        }
    }
}

/** 信息流卡片类型（Dart: lib/data/models/mv_think_model.dart `MvThinkCardType`）。 */
enum class MvThinkCardType {
    none,
    text,
    actionRow,
    lyrics,
    imageList,
    videoList,
    singerImage,
    image,
    mvPreview,
    star,
    feedback,

    /** 后端下发的失败文案卡片，正文在 bodyText */
    error;

    companion object {
        fun parse(value: Any?): MvThinkCardType {
            if (value is MvThinkCardType) return value
            val key = jsonValueOrNull(value)?.toString()?.trim()?.lowercase() ?: ""
            if (key.isEmpty()) return none
            return values().firstOrNull { it.name.lowercase() == key } ?: none
        }
    }
}

/** 信息流里的单个生成结果（Dart: lib/data/models/mv_think_model.dart `MvThinkGeneratedCard`）。 */
data class MvThinkGeneratedCard(
    var id: Long = 0L,
    var imageUrl: String? = null,
    var videoUrl: String? = null,
    var decoration: String = "",
    var status: MvThinkCardStatus = MvThinkCardStatus.success,
    var startSeconds: Double = -1.0,
) {
    val previewUrl: String get() = imageUrl ?: videoUrl ?: ""

    val displayStatus: MvThinkCardStatus
        get() {
            if (status == MvThinkCardStatus.failed) return MvThinkCardStatus.failed
            if (status == MvThinkCardStatus.generating) return MvThinkCardStatus.generating
            if (previewUrl.isEmpty()) return MvThinkCardStatus.generating
            return MvThinkCardStatus.success
        }

    val isComplete: Boolean get() = displayStatus == MvThinkCardStatus.success

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("imageUrl", imageUrl)
        .put("videoUrl", videoUrl)
        .put("decoration", decoration)
        .put("status", status.name)
        .put("startSeconds", startSeconds)

    companion object {
        fun fromJson(json: JSONObject): MvThinkGeneratedCard = MvThinkGeneratedCard(
            id = json.jLong("id", 0L),
            imageUrl = json.jStrOrNull("imageUrl"),
            videoUrl = json.jStrOrNull("videoUrl"),
            decoration = json.jStr("decoration"),
            status = MvThinkCardStatus.parse(json.jRaw("status")),
            startSeconds = json.jDbl("startSeconds", -1.0),
        )

        fun listFromJson(arr: JSONArray?): List<MvThinkGeneratedCard> = arr.jMap { fromJson(it) }
    }
}

/** 信息流卡片（Dart: lib/data/models/mv_think_model.dart `MvThinkCard`）。 */
data class MvThinkCard(
    var uniqueKey: Long = 0L,
    var type: MvThinkCardType = MvThinkCardType.none,
    var status: MvThinkCardStatus = MvThinkCardStatus.success,
    var label: String = "",
    var bodyText: String? = null,
    var filledStars: Int? = null,
    var totalStars: Int? = null,
    var isImageComp: Boolean? = null,
    var imageUrl: String? = null,
    var isRetryable: Boolean? = false,
    var items: List<MvThinkGeneratedCard>? = null,
    var videoUrl: String? = null,
    var firstFrameImg: String? = null,
    /** 跳转路由 */
    var route: String? = "",
) {
    val isTextCard: Boolean get() = type == MvThinkCardType.text
    val isActionRowCard: Boolean get() = type == MvThinkCardType.actionRow
    val isLyricsCard: Boolean get() = type == MvThinkCardType.lyrics
    val isImageListCard: Boolean get() = type == MvThinkCardType.imageList
    val isVideoListCard: Boolean get() = type == MvThinkCardType.videoList
    val isSingerImageCard: Boolean get() = type == MvThinkCardType.singerImage
    val isImageCard: Boolean get() = type == MvThinkCardType.image
    val isMvPreviewCard: Boolean get() = type == MvThinkCardType.mvPreview
    val isStarCard: Boolean get() = type == MvThinkCardType.star
    val isFeedbackCard: Boolean get() = type == MvThinkCardType.feedback
    val isErrorCard: Boolean get() = type == MvThinkCardType.error

    /** 可空字段为 null 时不带该键。 */
    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("uniqueKey", uniqueKey)
            .put("type", type.name)
            .put("status", status.name)
            .put("label", label)
        bodyText?.let { o.put("bodyText", it) }
        filledStars?.let { o.put("filledStars", it) }
        totalStars?.let { o.put("totalStars", it) }
        isImageComp?.let { o.put("isImageComp", it) }
        imageUrl?.let { o.put("imageUrl", it) }
        isRetryable?.let { o.put("isRetryable", it) }
        items?.let { list -> o.put("items", list.toJsonArray { it.toJson() }) }
        videoUrl?.let { o.put("videoUrl", it) }
        firstFrameImg?.let { o.put("firstFrameImg", it) }
        route?.let { o.put("route", it) }
        return o
    }

    companion object {
        fun fromJson(json: JSONObject): MvThinkCard = MvThinkCard(
            uniqueKey = json.jLong("uniqueKey", 0L),
            type = MvThinkCardType.parse(json.jRaw("type")),
            status = MvThinkCardStatus.parse(json.jRaw("status")),
            label = json.jStr("label"),
            bodyText = json.jStrOrNull("bodyText"),
            filledStars = json.jIntOrNull("filledStars"),
            totalStars = json.jIntOrNull("totalStars"),
            isImageComp = json.jBoolOrNull("isImageComp"),
            imageUrl = json.jStrOrNull("imageUrl"),
            isRetryable = json.jBool("isRetryable", false),
            items = json.jArr("items")?.let { MvThinkGeneratedCard.listFromJson(it) },
            videoUrl = json.jStrOrNull("videoUrl"),
            firstFrameImg = json.jStrOrNull("firstFrameImg"),
            route = json.jStrOrNull("route"),
        )

        fun listFromJson(arr: JSONArray?): List<MvThinkCard> = arr.jMap { fromJson(it) }
    }
}

/** 信息流步骤（Dart: lib/data/models/mv_think_model.dart `MvThinkStep`）。 */
data class MvThinkStep(
    var id: Long = 0L,
    var flowId: Long = 0L,
    var title: String = "",
    var type: String = "",
    var description: String = "",
    var isRead: Boolean = false,
    var status: MvThinkStepStatus = MvThinkStepStatus.success,
    var isRetryable: Boolean = false,
    var durationSec: Int = 0,
    var cards: List<MvThinkCard> = emptyList(),
    var isFinalStep: Boolean = false,
    /** 0=无需付费 1=需要付费 */
    var isPay: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("flowId", flowId)
        .put("title", title)
        .put("type", type)
        .put("description", description)
        .put("isRead", isRead)
        .put("status", status.name)
        .put("isRetryable", isRetryable)
        .put("isFinalStep", isFinalStep)
        .put("isPay", isPay)
        .put("needMinutes", durationSec)
        .put("durationSec", durationSec)
        .put("cards", cards.toJsonArray { it.toJson() })

    companion object {
        fun fromJson(json: JSONObject): MvThinkStep = MvThinkStep(
            id = json.jLong("id", 0L),
            flowId = json.jLong("flowId", 0L),
            title = json.jStr("title"),
            type = json.jStr("type"),
            description = json.jStr("description"),
            isRead = json.jBool("isRead", false),
            status = MvThinkStepStatus.parse(json.jRaw("status")),
            isRetryable = json.jBool("isRetryable", false),
            isFinalStep = json.jBool("isFinalStep", false),
            isPay = json.jInt("isPay", 0),
            durationSec = json.jIntOrNull("durationSec") ?: json.jIntOrNull("needMinutes") ?: 0,
            cards = MvThinkCard.listFromJson(json.jArr("cards")),
        )

        fun listFromJson(arr: JSONArray?): List<MvThinkStep> = arr.jMap { fromJson(it) }
    }
}

/** 信息流页面的展示态缓存（Dart: lib/data/models/mv_think_model.dart `ThinkLineMvDisplayCache`）。 */
data class ThinkLineMvDisplayCache(
    var steps: List<MvThinkStep> = emptyList(),
    var stepStatuses: List<MvThinkStepStatus> = emptyList(),
    var displayedTitles: List<String> = emptyList(),
    var displayedDescriptions: List<String> = emptyList(),
    var isStepTyping: List<Boolean> = emptyList(),
    var stepReadFlags: List<Boolean> = emptyList(),
    var maxVisibleStep: Int = 0,
    var visibleCardCounts: List<Int> = emptyList(),
    var stepCardsUseAnimatedReveal: List<Boolean> = emptyList(),
    var litStars: Int = 0,
    var stepAdvanced: List<Boolean> = emptyList(),
    var cardRevealInProgress: List<Boolean> = emptyList(),
    var cardRevealDone: List<Boolean> = emptyList(),
    var activeStepIndex: Int = 0,
    var flowCompleted: Boolean = false,
    var pollingPaused: Boolean = false,
    var payCompleted: Boolean = false,
    var needPoint: Int = 0,
    var imageComp: String = "",
    var isDeletable: Boolean = false,
) {
    companion object {
        /** 深拷贝（经 JSON 往返，与 Dart 一致）。 */
        fun copySteps(source: List<MvThinkStep>): List<MvThinkStep> =
            source.map { MvThinkStep.fromJson(it.toJson()) }
    }
}
