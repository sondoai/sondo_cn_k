package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 音视频裁剪区间，起止时间均以音频绝对位置表示（Dart: lib/shared/domain/entities/video_gen_params.dart
 * `VideoDurationSelection`）。Dart 的 Duration 在这里是毫秒。
 */
data class VideoDurationSelection(
    /** 起点（毫秒） */
    var start: Long = 0L,
    /** 终点（毫秒） */
    var end: Long = 0L,
) {
    val durationInSeconds: Int get() = ((end - start) / 1000L).toInt()
}

/** MV 封面提交项；四视图与上传图互斥，按槽位带角色标识（Dart: `VideoCoverImage`）。 */
data class VideoCoverImage(
    /** 上传图 URL；四视图时必须为空串。 */
    var url: String = "",
    /** 四视图角色 id；上传图必须为 0。 */
    var fourViewId: Long = 0L,
    /** 1 主角 / 2 配角，与当前槽位对齐。 */
    var roleType: Int = roleTypeLead,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("url", url)
        .put("fourViewId", fourViewId)
        .put("roleType", roleType)

    companion object {
        /** 1 主角（第一槽）。 */
        const val roleTypeLead = 1

        /** 2 配角（第二槽）。 */
        const val roleTypeSupporting = 2

        fun fromJson(json: JSONObject): VideoCoverImage = VideoCoverImage(
            url = json.jStr("url"),
            fourViewId = json.jLong("fourViewId", 0L),
            roleType = json.jInt("roleType", roleTypeLead),
        )

        fun listFromJson(arr: JSONArray?): List<VideoCoverImage> = arr.jMap { fromJson(it) }
    }
}

/** MV / 舞蹈视频统一生成请求参数（Dart: lib/shared/domain/entities/video_gen_params.dart `VideoGenParams`）。 */
data class VideoGenParams(
    var type: VideoContentType = VideoContentType.mv,
    var title: String = "",
    var artist: String = "",
    /** 曲库歌曲 ID，本地上传时为空字符串。 */
    var musicId: String = "",
    /** 本地上传音频 URL，曲库歌曲时为空。 */
    var audioUrl: String = "",
    /** 歌手/舞者封面图 URL。 */
    var coverImage: String = "",
    /** 四视图角色 ID，未选角色时为 null。 */
    var fourViewId: Long? = null,
    /** MV 封面对象数组（含 url / fourViewId / roleType）；空则不传。 */
    var coverImages: List<VideoCoverImage> = emptyList(),
    /** MV 人物数量 `'1'` / `'2'`。 */
    var characterCount: String = "1",
    /** 订阅时长档位标识。 */
    var subDurationScope: String = "",
    /** 画幅比例，如 9:16、16:9。 */
    var imageComp: String = "",
    /** 视频清晰度。 */
    var quality: Int = 0,
    /** 是否去除水印。国内版固定 [keptWatermark]（0=不去除），下载水印不在这里控制。 */
    var isWatermark: Int = keptWatermark,
    /** 字幕开关，0=显示字幕（UI 开启），1=隐藏字幕。 */
    var isLyric: Int = 0,
    /** 自定义故事描述（无模板时使用）。 */
    var customStoryPlot: String = "",
    /** 风格模板 JSON（选中模板时使用）。 */
    var storyRequest: String = "",
    /** 任务类型，仅 MV 有效，曲库歌曲为 0。 */
    var taskType: Int? = null,
    /** MV 版本，2 表示 Ultra（Seedance）模式，仅 MV 有效。 */
    var mvVersion: Int? = null,
    /** 是否开启剪映特效加工（true 开启，false/不传关闭）；仅 MV Premium 有效。 */
    var capcutEnabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", type.name)
        .put("title", title)
        .put("artist", artist)
        .put("musicId", musicId)
        .put("audioUrl", audioUrl)
        .put("coverImage", coverImage)
        .put("fourViewId", fourViewId)
        .put("coverImages", coverImages.toJsonArray { it.toJson() })
        .put("characterCount", characterCount)
        .put("subDurationScope", subDurationScope)
        .put("imageComp", imageComp)
        .put("quality", quality)
        .put("taskType", taskType)
        .put("mvVersion", mvVersion)
        .put("isWatermark", isWatermark)
        .put("isLyric", isLyric)
        .put("capcutEnabled", capcutEnabled)
        .put("customStoryPlot", customStoryPlot)
        .put("storyRequest", storyRequest)

    companion object {
        /** 国内版不再按任务去掉水印。生成请求固定传 0（不去除）。 */
        const val keptWatermark = 0

        /** 与 [toJson] 对应（页面间传参用）。 */
        fun fromJson(json: JSONObject): VideoGenParams = VideoGenParams(
            type = VideoContentType.mv,
            title = json.jStr("title"),
            artist = json.jStr("artist"),
            musicId = json.jStr("musicId"),
            audioUrl = json.jStr("audioUrl"),
            coverImage = json.jStr("coverImage"),
            fourViewId = json.jLongOrNull("fourViewId"),
            coverImages = VideoCoverImage.listFromJson(json.jArr("coverImages")),
            characterCount = json.jStr("characterCount", "1"),
            subDurationScope = json.jStr("subDurationScope"),
            imageComp = json.jStr("imageComp"),
            quality = json.jInt("quality", 0),
            isWatermark = json.jInt("isWatermark", keptWatermark),
            isLyric = json.jInt("isLyric", 0),
            customStoryPlot = json.jStr("customStoryPlot"),
            storyRequest = json.jStr("storyRequest"),
            taskType = json.jIntOrNull("taskType"),
            mvVersion = json.jIntOrNull("mvVersion"),
            capcutEnabled = json.jBool("capcutEnabled", true),
        )

        /** UI 字幕开启 → API isLyric 传 0（显示字幕）。 */
        fun isLyricFromSubtitlesEnabled(subtitlesEnabled: Boolean): Int = if (subtitlesEnabled) 0 else 1

        /** 解析 API 使用的 `起点,终点` 裁剪区间；格式非法或终点不晚于起点时返回 null。 */
        fun tryParseSubDurationScope(scope: String): VideoDurationSelection? {
            val range = scope.split(",")
            if (range.size != 2) return null
            val start = tryParseTimestamp(range[0])
            val end = tryParseTimestamp(range[1])
            if (start == null || end == null || end <= start) return null
            return VideoDurationSelection(start = start, end = end)
        }

        /** `mm:ss` 或 `hh:mm:ss` → 毫秒。 */
        private fun tryParseTimestamp(value: String): Long? {
            val parts = value.trim().split(":")
            if (parts.size != 2 && parts.size != 3) return null
            if (parts.any { p -> p.isEmpty() || !p.all { it in '0'..'9' } }) return null
            val values = ArrayList<Long>()
            for (part in parts) values.add(part.toLongOrNull() ?: return null)
            val seconds = values.last()
            val minutes = values[values.size - 2]
            if (seconds >= 60 || (parts.size == 3 && minutes >= 60)) return null
            val hours = if (parts.size == 3) values.first() else 0L
            return (hours * 3600L + minutes * 60L + seconds) * 1000L
        }

        /** 构造 MV 生成参数；封面只走对象数组 coverImages，不再传 fourViewIds。 */
        fun forMv(
            title: String,
            artist: String,
            musicId: String,
            audioUrl: String,
            taskType: Int,
            coverImages: List<VideoCoverImage>,
            characterCount: String,
            subDurationScope: String,
            imageComp: String,
            quality: Int,
            mvVersion: Int,
            isWatermark: Int,
            isLyric: Int,
            capcutEnabled: Boolean,
            customStoryPlot: String,
            storyRequest: String,
        ): VideoGenParams = VideoGenParams(
            type = VideoContentType.mv,
            title = title,
            artist = artist,
            musicId = musicId,
            audioUrl = audioUrl,
            coverImage = "",
            fourViewId = null,
            coverImages = coverImages,
            characterCount = characterCount,
            subDurationScope = subDurationScope,
            imageComp = imageComp,
            quality = quality,
            taskType = taskType,
            mvVersion = mvVersion,
            isWatermark = isWatermark,
            isLyric = isLyric,
            capcutEnabled = capcutEnabled,
            customStoryPlot = customStoryPlot,
            storyRequest = storyRequest,
        )
    }
}
