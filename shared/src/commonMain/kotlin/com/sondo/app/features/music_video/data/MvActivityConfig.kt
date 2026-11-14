package com.sondo.app.features.music_video.data

import com.sondo.app.data.models.jsonValueOrNull
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.math.roundToInt

/** 活动创作页可排序的表单区块（Dart: domain/entities/mv_activity_config.dart `MvCreateBlock`）。 */
enum class MvCreateBlock { music, role, description, title, options }

/**
 * 活动 MV 落地配置。空字段表示没有默认值，页面保持可编辑的空态
 * （Dart: domain/entities/mv_activity_config.dart `MvActivityConfig`）。
 */
data class MvActivityConfig(
    val navTitle: String = "",
    val copy: String = "",
    val coverUrl: String = "",
    val videoUrl: String = "",
    val order: List<MvCreateBlock> = defaultOrder,
    val audioUrl: String = "",
    /** 歌曲名。空表示没有默认名称，卡片上留空。 */
    val audioName: String = "",
    val audioDurationSeconds: Int = 0,
    val roleImageUrls: List<String> = emptyList(),
    val description: String = "",
    val harmonyTitle: String = "",
    /** 原始画幅。null 表示没值或匹配不上，页面用 9:16。 */
    val aspectRatio: String? = null,
    /** 原始清晰度。null 表示没值或匹配不上，页面用 480。 */
    val quality: String? = null,
    /** 字幕原始字符串，只保留 true / false。 */
    val subtitles: String? = null,
) {
    val hasVideo: Boolean get() = videoUrl.isNotEmpty()
    val hasCover: Boolean get() = coverUrl.isNotEmpty()
    val hasHero: Boolean get() = hasVideo || hasCover

    companion object {
        val defaultOrder: List<MvCreateBlock> = listOf(
            MvCreateBlock.music,
            MvCreateBlock.role,
            MvCreateBlock.description,
            MvCreateBlock.title,
            MvCreateBlock.options,
        )

        private val blockByType = mapOf(
            "music-create" to MvCreateBlock.music,
            "role-select" to MvCreateBlock.role,
            "desc-input" to MvCreateBlock.description,
            "harmory" to MvCreateBlock.title,
            "Options" to MvCreateBlock.options,
        )

        fun fromJson(json: JSONObject): MvActivityConfig {
            val banner = json.optJSONObject("banner")
            val options = optionList(json.opt("optionList"))
            var audioUrl = ""
            var audioName = ""
            var duration = 0
            val roleUrls = ArrayList<String>()
            var description = ""
            var harmony = ""
            var aspectRatio: String? = null
            var quality: String? = null
            var subtitles: String? = null

            val seen = HashSet<String>()
            for (item in options) {
                // 重复 type 只用第一次，后面的同名区块忽略。
                if (!seen.add(item.type)) continue
                when (item.type) {
                    "music-create" -> {
                        audioUrl = text(item.raw.opt("audioUrl"))
                        audioName = text(item.raw.opt("audioName"))
                        duration = intOf(item.raw.opt("duration"))
                    }
                    "role-select" -> roleUrls.addAll(stringList(item.raw.opt("defaultValue")))
                    "desc-input" -> description = text(item.raw.opt("defaultValue"))
                    "harmory" -> harmony = text(item.raw.opt("defaultValue"))
                    "Options" -> {
                        val seenChild = HashSet<String>()
                        for (child in optionList(item.raw.opt("optionList"))) {
                            if (!seenChild.add(child.type)) continue
                            val value = text(child.raw.opt("defaultValue"))
                            when (child.type) {
                                "aspect-ratio" -> aspectRatio = allowed(value, setOf("9:16", "16:9"))
                                "video-parameters" -> quality = allowed(value, setOf("480", "720"))
                                "subtitles" -> subtitles = allowed(value.lowercase(), setOf("true", "false"))
                            }
                        }
                    }
                }
            }

            return MvActivityConfig(
                navTitle = text(banner?.opt("title")),
                copy = text(banner?.opt("desc")),
                coverUrl = text(banner?.opt("cover")),
                videoUrl = text(banner?.opt("video")),
                order = orderOf(options),
                audioUrl = audioUrl,
                audioName = audioName,
                audioDurationSeconds = duration,
                roleImageUrls = roleUrls,
                description = description,
                harmonyTitle = harmony,
                aspectRatio = aspectRatio,
                quality = quality,
                subtitles = subtitles,
            )
        }

        private class OptionItem(val type: String, val raw: JSONObject)

        private fun orderOf(options: List<OptionItem>): List<MvCreateBlock> {
            val seen = HashSet<MvCreateBlock>()
            val order = ArrayList<MvCreateBlock>()
            for (item in options) {
                val block = blockByType[item.type] ?: continue
                if (!seen.add(block)) continue
                order.add(block)
            }
            for (block in defaultOrder) {
                if (seen.add(block)) order.add(block)
            }
            return order
        }

        private fun optionList(raw: Any?): List<OptionItem> {
            val arr = raw as? JSONArray ?: return emptyList()
            val items = ArrayList<OptionItem>()
            for (i in 0 until arr.length()) {
                val map = arr.opt(i) as? JSONObject ?: continue
                val type = text(map.opt("type"))
                if (type.isEmpty()) continue
                items.add(OptionItem(type, map))
            }
            return items
        }

        private fun text(raw: Any?): String {
            val v = jsonValueOrNull(raw) ?: return ""
            return v.toString().trim()
        }

        private fun intOf(raw: Any?): Int = when (raw) {
            is Int -> raw
            is Long -> raw.toInt()
            is Number -> raw.toDouble().roundToInt()
            else -> text(raw).toIntOrNull() ?: 0
        }

        private fun stringList(raw: Any?): List<String> {
            val arr = raw as? JSONArray ?: return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val s = text(arr.opt(i))
                if (s.isNotEmpty()) out.add(s)
            }
            return out
        }

        private fun allowed(value: String, set: Set<String>): String? =
            if (value.isEmpty() || !set.contains(value)) null else value
    }
}
