package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 提示词条目（Dart: lib/data/models/song_model.dart `PromptItemModel`）。 */
data class PromptItemModel(
    var id: Long = -1L,
    var label: String = "",
    var value: String = "",
    var dictType: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("value", value)
        .put("dictType", dictType)

    companion object {
        fun fromJson(json: JSONObject): PromptItemModel = PromptItemModel(
            id = json.jLong("id", -1L),
            label = json.jStr("label"),
            value = json.jStr("value"),
            dictType = json.jStr("dictType"),
        )

        fun listFromJson(arr: JSONArray?): List<PromptItemModel> = arr.jMap { fromJson(it) }
    }
}

/** 按曲风分组的提示词（Dart: lib/data/models/song_model.dart `PromptModel`）。 */
data class PromptModel(
    var genre: String = "",
    var genreId: String = "",
    var list: List<PromptItemModel> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("genre", genre)
        .put("genreId", genreId)
        .put("list", list.toJsonArray { it.toJson() })

    companion object {
        fun fromJson(json: JSONObject): PromptModel = PromptModel(
            genre = json.jStr("genre"),
            genreId = json.jStr("genreId"),
            list = PromptItemModel.listFromJson(json.jArr("list")),
        )

        fun listFromJson(arr: JSONArray?): List<PromptModel> = arr.jMap { fromJson(it) }
    }
}

/** 歌曲模型（Dart: lib/data/models/song_model.dart `SongModel`）。 */
data class SongModel(
    var id: Long = 0L,
    /** 歌曲标题 */
    var musicTitle: String? = "",
    /** 音乐提示词 */
    var musicPrompt: String? = null,
    /** 歌词 */
    var musicLyric: String? = null,
    /** 转换后的歌词 */
    var musicConvertLyric: String? = "",
    /** 音频文件URL */
    var musicAudioUrl: String? = null,
    /** 封面图片URL */
    var musicCoverUrl: String? = null,
    /** 音乐时长字符串 */
    var musicDurationStr: String? = null,
    /** 音乐UID */
    var musicUid: Long = 0L,
    /** 音乐类型 */
    var musicType: String? = null,
    /** 声音性别 */
    var musicVocalGender: String? = null,
    /** 生成状态 1.生成中 2.生成中 3.生成成功(mp3) 4.生成失败 5.生成成功(流) */
    var musicGenerateStatus: Int = 0,
    /** 模型类型 3.M 4.A */
    var musicModelType: Int = 0,
    /** 生成任务ID */
    var musicGenerateTaskId: String? = "",
    /** 发布状态 */
    var musicPublish: Int = 0,
    /** 是否收藏 */
    var isCollect: Boolean = false,
    /** 收藏数量 */
    var isCollectNum: Int = 0,
    /** 点击数 */
    var musicHitNum: Int = 0,
    /** 转发数 */
    var repostCount: Int = 0,
    /** 创建时间 */
    var createTime: Long? = null,
    /** 更新时间 */
    var updateTime: Long? = null,
    /** 音乐类别 */
    var musicCategory: String? = null,
    /** 音乐类别ID */
    var musicCategoryId: Long? = null,
    /** 创作模式 1 简单 2 高级 */
    var musicCreativeMode: Int? = null,
    /** 审核失败错误信息 */
    var publishErrorMsg: String? = null,
    /** 是否购买证书 */
    var isBuyCertificate: Boolean = false,
    /** 错误信息 */
    var errorMsg: String? = "",
    /** 是否纯音乐 0 否 1 是 */
    var instrumental: Int = 0,
    /** 用户信息（原始对象） */
    var userInfo: JSONObject? = null,
) {
    val nicknameStr: String
        get() {
            val nickname = userInfo?.jStrOrNull("nickname")
            if (!nickname.isNullOrEmpty() && nickname.contains("@")) {
                return "${nickname.split("@").first()}@***"
            }
            return nickname ?: ""
        }

    val createTimeStr: String get() = ModelDate.formatDate(createTime ?: 0L)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("musicTitle", musicTitle)
        .put("musicPrompt", musicPrompt)
        .put("musicLyric", musicLyric)
        .put("musicConvertLyric", musicConvertLyric)
        .put("musicAudioUrl", musicAudioUrl)
        .put("musicCoverUrl", musicCoverUrl)
        .put("musicDurationStr", musicDurationStr)
        .put("musicUid", musicUid)
        .put("musicType", musicType)
        .put("musicVocalGender", musicVocalGender)
        .put("musicGenerateStatus", musicGenerateStatus)
        .put("musicModelType", musicModelType)
        .put("musicGenerateTaskId", musicGenerateTaskId)
        .put("musicPublish", musicPublish)
        .put("isCollect", isCollect)
        .put("isCollectNum", isCollectNum)
        .put("musicHitNum", musicHitNum)
        .put("repostCount", repostCount)
        .put("createTime", createTime)
        .put("updateTime", updateTime)
        .put("musicCategory", musicCategory)
        .put("musicCategoryId", musicCategoryId)
        .put("musicCreativeMode", musicCreativeMode)
        .put("publishErrorMsg", publishErrorMsg)
        .put("isBuyCertificate", isBuyCertificate)
        .put("errorMsg", errorMsg)
        .put("instrumental", instrumental)
        .put("userInfo", userInfo)

    companion object {
        /** 宽松解析：缺 id 时为 0。 */
        fun fromJson(json: JSONObject): SongModel = SongModel(
            id = json.jLong("id", 0L),
            musicTitle = json.jStr("musicTitle"),
            musicPrompt = json.jStrOrNull("musicPrompt"),
            musicLyric = json.jStrOrNull("musicLyric"),
            musicConvertLyric = json.jStr("musicConvertLyric"),
            musicAudioUrl = json.jStrOrNull("musicAudioUrl"),
            musicCoverUrl = json.jStrOrNull("musicCoverUrl"),
            musicDurationStr = json.jStrOrNull("musicDurationStr"),
            musicUid = json.jLong("musicUid", 0L),
            musicType = json.jStrOrNull("musicType"),
            musicVocalGender = json.jStrOrNull("musicVocalGender"),
            musicGenerateStatus = json.jInt("musicGenerateStatus", 0),
            musicModelType = json.jInt("musicModelType", 0),
            musicGenerateTaskId = json.jStr("musicGenerateTaskId"),
            musicPublish = json.jInt("musicPublish", 0),
            isCollect = json.jBool("isCollect", false),
            isCollectNum = json.jInt("isCollectNum", 0),
            createTime = json.jLongOrNull("createTime"),
            updateTime = json.jLongOrNull("updateTime"),
            musicHitNum = json.jInt("musicHitNum", 0),
            musicCategory = json.jStrOrNull("musicCategory"),
            musicCategoryId = json.jLongOrNull("musicCategoryId"),
            musicCreativeMode = json.jIntOrNull("musicCreativeMode"),
            publishErrorMsg = json.jStrOrNull("publishErrorMsg"),
            isBuyCertificate = json.jBool("isBuyCertificate", false),
            instrumental = json.jInt("instrumental", 0),
            userInfo = json.jObj("userInfo"),
            errorMsg = json.jStr("errorMsg"),
            repostCount = json.jInt("repostCount", 0),
        )

        /** Dart 的 `id` 必填，缺失时 fromJson 抛异常；这里返回 null。 */
        fun fromJsonOrNull(json: JSONObject?): SongModel? {
            if (json == null) return null
            if (jsonValueOrNull(json.opt("id")) !is Number) return null
            return fromJson(json)
        }

        fun listFromJson(arr: JSONArray?): List<SongModel> = arr.jMap { fromJson(it) }

        /** 严格列表：任一条缺 id 则整体为 null（Dart 整个列表解析抛异常）。 */
        fun listFromJsonOrNull(arr: JSONArray?): List<SongModel>? = arr.jMapStrict { fromJsonOrNull(it) }
    }
}

/** 歌曲详情（Dart: lib/data/models/song_model.dart `SongDetailModel`）。 */
data class SongDetailModel(
    var lyricDetail: JSONArray? = null,
    var musicDetail: SongModel = SongModel(),
    var user: JSONObject? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("lyricDetail", lyricDetail)
        .put("musicDetail", musicDetail.toJson())
        .put("user", user)

    companion object {
        fun fromJson(json: JSONObject): SongDetailModel = SongDetailModel(
            lyricDetail = json.jArr("lyricDetail"),
            musicDetail = json.jObj("musicDetail")?.let { SongModel.fromJson(it) } ?: SongModel(),
            user = json.jObj("user"),
        )

        /** Dart 的 `musicDetail`（及其 id）必填，缺失时抛异常；这里返回 null。 */
        fun fromJsonOrNull(json: JSONObject?): SongDetailModel? {
            if (json == null) return null
            val detail = SongModel.fromJsonOrNull(json.jObj("musicDetail")) ?: return null
            return SongDetailModel(json.jArr("lyricDetail"), detail, json.jObj("user"))
        }

        fun listFromJson(arr: JSONArray?): List<SongDetailModel> = arr.jMap { fromJson(it) }
    }
}

/** 引导页示例歌曲（Dart: lib/data/models/song_model.dart `GuideMusicModel`）。 */
data class GuideMusicModel(
    var mood: String? = null,
    var category: String? = null,
    var musicName: String = "",
    var musicUrl: String = "",
    var coverUrl: String = "",
    var lyric: String? = null,
    var startTime: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("mood", mood)
        .put("category", category)
        .put("musicName", musicName)
        .put("musicUrl", musicUrl)
        .put("coverUrl", coverUrl)
        .put("lyric", lyric)
        .put("startTime", startTime)

    companion object {
        fun fromJson(json: JSONObject): GuideMusicModel = GuideMusicModel(
            mood = json.jStrOrNull("mood"),
            category = json.jStrOrNull("category"),
            musicName = json.jStr("musicName"),
            musicUrl = json.jStr("musicUrl"),
            coverUrl = json.jStr("coverUrl"),
            lyric = json.jStrOrNull("lyric"),
            startTime = json.jStr("startTime"),
        )

        /** Dart 的 musicName / musicUrl / coverUrl / startTime 必填字符串，缺失抛异常；这里返回 null。 */
        fun fromJsonOrNull(json: JSONObject?): GuideMusicModel? {
            if (json == null) return null
            return parseOrNull {
                GuideMusicModel(
                    mood = json.jStrOrNull("mood"),
                    category = json.jStrOrNull("category"),
                    musicName = json.reqStr("musicName"),
                    musicUrl = json.reqStr("musicUrl"),
                    coverUrl = json.reqStr("coverUrl"),
                    lyric = json.jStrOrNull("lyric"),
                    startTime = json.reqStr("startTime"),
                )
            }
        }

        fun listFromJson(arr: JSONArray?): List<GuideMusicModel> = arr.jMap { fromJson(it) }
    }
}
