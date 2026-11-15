package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 广场作品类型（Dart: lib/data/models/square_model.dart `MvType`）。枚举名即 Dart 的 `name`。 */
enum class MvType(val value: Int) {
    mv(0);

    companion object {
        /** 1 曾是舞蹈。舞蹈已下线，不能当成 MV。 */
        fun isRetiredDanceValue(value: Int): Boolean = value == 1

        fun fromValue(value: Int): MvType = values().firstOrNull { it.value == value } ?: mv

        fun toValue(type: MvType): Int = type.value

        fun getName(type: MvType): String = type.name

        fun parse(value: Any?): MvType {
            if (value is MvType) return value
            if (value is Number) return fromValue(value.toInt())
            if (value is String) {
                val key = value.trim()
                if (key.isEmpty()) return mv
                val intVal = key.toIntOrNull()
                if (intVal != null) return fromValue(intVal)
                val lower = key.lowercase()
                return values().firstOrNull { it.name.lowercase() == lower } ?: mv
            }
            return mv
        }
    }
}

/** 广场 / 发布列表里的视频条目（Dart: lib/data/models/square_model.dart `SquareModel`）。 */
data class SquareModel(
    var id: Long = 0L,
    var userId: Long = 0L,
    var mvType: MvType = MvType.mv,
    var title: String = "",
    var artist: String = "",
    var coverImage: String = "",
    var firstFrameImg: String = "",
    var resultUrl: String = "",
    var imageComp: String = "16:9",
    var duration: Int = 0,
    var mvHitNum: Int = 0,
    var repostCount: Int = 0,
    var collectNum: Int = 0,
    var isCollect: Boolean = false,
    var subDurationScope: String = "",
    var mvPublish: VideoPublishStatus = VideoPublishStatus.unpublish,
    var isBuyCertificate: Boolean = false,
    var quality: Int = 480,
    var genMode: Int = 0,
    var nickname: String = "",
    var avatar: String = "",
    var createTime: Long = 0L,
    var updateTime: Long = 0L,
    var informationFlowVersion: Int = 1,
    var mvVersion: Int = 1,
    /** 是否试用 MV，仅 MV 类型有效。 */
    var trialMvFlag: Boolean = false,
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
            val totalMinutes = (diff / 60) % 60
            val totalSeconds = diff % 60
            return "${totalMinutes.toString().padStart(2, '0')}:${totalSeconds.toString().padStart(2, '0')}"
        }

    val cover: String
        get() {
            val img = if (firstFrameImg.isNotEmpty()) firstFrameImg else coverImage
            // 本地文件路径无法作为网络图片加载，降级到 coverImage 或置空
            if (img.startsWith("file://")) {
                return if (coverImage.startsWith("file://")) "" else coverImage
            }
            return img
        }

    val nicknameStr: String
        get() {
            if (nickname.isNotEmpty() && nickname.contains("@")) {
                return "${nickname.split("@").first()}@***"
            }
            return nickname
        }

    val timeStr: String
        get() {
            if (createTime == 0L) return ""
            return ModelDate.formatDate(createTime)
        }

    val isVertical: Boolean get() = imageComp == "9:16"

    /**
     * 转回 MvModel。与 Dart 一致：audioUrl=""、status=success，不带 resultUrl / mvVersion /
     * unlock / 试看字段（取 MvModel 默认值）。
     */
    val mvModel: MvModel
        get() = MvModel(
            id = id,
            userId = userId,
            title = title,
            artist = artist,
            audioUrl = "",
            coverImage = coverImage,
            status = MvStatus.success,
            subDurationScope = subDurationScope,
            firstFrameImg = firstFrameImg,
            imageComp = imageComp,
            quality = quality,
            genMode = genMode,
            mvHitNum = mvHitNum,
            repostCount = repostCount,
            collectNum = collectNum,
            isCollect = isCollect,
            mvPublish = mvPublish,
            isBuyCertificate = isBuyCertificate,
            nickname = nickname,
            avatar = avatar,
            createTime = createTime,
            informationFlowVersion = informationFlowVersion,
            trialMvFlag = trialMvFlag,
        )

    /** 与 Dart 一致：不含 isBuyCertificate / quality / genMode。 */
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("userId", userId)
        .put("mvType", MvType.toValue(mvType))
        .put("title", title)
        .put("artist", artist)
        .put("coverImage", coverImage)
        .put("firstFrameImg", firstFrameImg)
        .put("subDurationScope", subDurationScope)
        .put("resultUrl", resultUrl)
        .put("imageComp", imageComp)
        .put("duration", duration)
        .put("mvHitNum", mvHitNum)
        .put("repostCount", repostCount)
        .put("collectNum", collectNum)
        .put("isCollect", isCollect)
        .put("mvPublish", VideoPublishStatus.toValue(mvPublish))
        .put("nickname", nickname)
        .put("avatar", avatar)
        .put("createTime", createTime)
        .put("updateTime", updateTime)
        .put("informationFlowVersion", informationFlowVersion)
        .put("mvVersion", mvVersion)
        .put("trialMvFlag", trialMvFlag)

    companion object {
        fun fromMv(mv: MvModel): SquareModel = SquareModel(
            id = mv.id,
            userId = mv.userId,
            mvType = MvType.mv,
            title = mv.title,
            artist = mv.artist,
            coverImage = mv.coverImage ?: "",
            firstFrameImg = mv.firstFrameImg ?: "",
            subDurationScope = mv.subDurationScope,
            resultUrl = mv.resultUrl ?: "",
            imageComp = mv.imageComp,
            quality = mv.quality,
            genMode = mv.genMode,
            mvHitNum = mv.mvHitNum,
            repostCount = mv.repostCount,
            collectNum = mv.collectNum,
            isCollect = mv.isCollect,
            mvPublish = mv.mvPublish,
            isBuyCertificate = mv.isBuyCertificate ?: false,
            nickname = mv.nickname,
            avatar = mv.avatar,
            createTime = mv.createTime,
            informationFlowVersion = mv.informationFlowVersion,
            mvVersion = mv.mvVersion,
            trialMvFlag = mv.trialMvFlag,
        )

        /**
         * 注意与 Dart 一致的细节：informationFlowVersion / mvVersion 缺失时为 0（不是构造默认的 1），
         * isBuyCertificate / quality / genMode 不从 JSON 读。
         */
        fun fromJson(json: JSONObject, mvType: MvType = MvType.mv): SquareModel = SquareModel(
            id = json.jLong("id", 0L),
            userId = json.jLong("userId", 0L),
            mvType = mvType,
            title = json.jStr("title"),
            artist = json.jStr("artist"),
            coverImage = json.jStr("coverImage"),
            firstFrameImg = json.jStr("firstFrameImg"),
            subDurationScope = json.jStr("subDurationScope"),
            resultUrl = json.jStr("resultUrl"),
            imageComp = json.jStr("imageComp", "16:9"),
            duration = json.jInt("duration", 0),
            mvHitNum = json.jInt("mvHitNum", 0),
            repostCount = json.jInt("repostCount", 0),
            collectNum = json.jInt("collectNum", 0),
            isCollect = json.jBool("isCollect", false),
            mvPublish = VideoPublishStatus.parse(json.jInt("mvPublish", 0)),
            nickname = json.jStr("nickname"),
            avatar = json.jStr("avatar"),
            createTime = timeFromJson(json.jRaw("createTime")),
            updateTime = timeFromJson(json.jRaw("updateTime") ?: json.jRaw("updatedAt")),
            informationFlowVersion = json.jInt("informationFlowVersion", 0),
            mvVersion = json.jInt("mvVersion", 0),
            trialMvFlag = json.jBool("trialMvFlag", false),
        )

        fun listFromJson(arr: JSONArray?): List<SquareModel> = arr.jMap { fromJson(it) }

        fun listFromJson(arr: JSONArray?, mvType: MvType): List<SquareModel> = arr.jMap { fromJson(it, mvType) }

        /** 数字毫秒 / 数字字符串 / ISO 时间串 → 毫秒；其余为 0。 */
        private fun timeFromJson(value: Any?): Long {
            if (value == null) return 0L
            if (value is Number) return value.toLong()
            if (value is String) {
                if (value.isEmpty()) return 0L
                value.toLongOrNull()?.let { return it }
                ModelDate.parseIso(value)?.let { return it }
            }
            return 0L
        }
    }
}
