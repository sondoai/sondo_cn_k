package com.sondo.app.features.home.data

import com.sondo.app.data.models.CategoryModel
import com.sondo.app.data.models.HomeModel
import com.sondo.app.data.models.SongEntity
import com.sondo.app.data.models.VideoContentEntity
import com.sondo.app.data.models.VideoContentType
import com.sondo.app.data.models.jsonValueOrNull
import com.sondo.app.data.models.toEntity
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 首页音乐分类实体（Dart: features/home/domain/entities/category_entity.dart）。 */
data class CategoryEntity(
    /** 分类展示名称。 */
    val categoryName: String = "",
    /** 分类路由 / 筛选路径。 */
    val categoryPath: String = "",
)

/** 首页热门歌曲聚合实体。 */
data class HomeTrendingEntity(val hotList: List<SongEntity> = emptyList())

/** 首页分页数据实体。 */
data class HomePageDataEntity(val list: List<SongEntity> = emptyList(), val total: Int = 0)

/** CategoryModel ↔ CategoryEntity（Dart CategoryMapper）。 */
object CategoryMapper {
    fun toEntity(model: CategoryModel) = CategoryEntity(model.categoryName, model.categoryPath)
    fun toModel(entity: CategoryEntity) = CategoryModel(entity.categoryName, entity.categoryPath)
    fun toEntityList(models: List<CategoryModel>) = models.map { toEntity(it) }
}

/** HomeModel → HomeTrendingEntity（Dart HomeTrendingMapper）。 */
object HomeTrendingMapper {
    fun toEntity(model: HomeModel) = HomeTrendingEntity(model.hotList.map { it.toEntity() })
}

/**
 * 首页轮播素材（Dart: features/home/domain/entities/home_banner_item.dart）。字段来自 `APP_HOME_BANNER`。
 */
data class HomeBannerItem(
    /** 封面。 */
    val firstFrameImg: String = "",
    /** 视频地址。空表示这条只展示封面。 */
    val resultUrl: String = "",
    /** 左下标题。空则不展示标题。 */
    val title: String = "",
    /** 做同款剧情。null 表示没有可复用描述。 */
    val customStoryPlot: String? = null,
    /** 播放页是否盖 AI 生成标识。0 否，1 是。 */
    val isWatermark: Int = 0,
    /** 画幅，如 1:1。 */
    val imageComp: String = "1:1",
    /** `create_mv`、`create_music`，或应用内路由。空则进 MV 创作。 */
    val router: String = "",
    /** 跳转参数。接口字段名是 `parmas`，内容是 JSON 字符串。 */
    val params: JSONObject = JSONObject(),
    /** `parmas` 不是合法 JSON 对象。为 true 时跳转只进 MV 创作，不带这份参数。 */
    val paramsInvalid: Boolean = false,
    /** 按钮文案，键为语言或 `default`。 */
    val btnText: Map<String, String> = emptyMap(),
) {
    val hasVideo: Boolean get() = resultUrl.trim().isNotEmpty()

    /** 高度相对屏宽的倍数。画幅解析失败时按 1:1。 */
    val heightFactor: Float
        get() {
            val parts = imageComp.split(":")
            if (parts.size == 2) {
                val w = parts[0].trim().toFloatOrNull()
                val h = parts[1].trim().toFloatOrNull()
                if (w != null && h != null && w > 0f && h > 0f) return h / w
            }
            return 1f
        }

    /** 当前语言文案。先语言-地区，再语言码，最后 `default`。都没有返回空。国内版固定 zh-CN。 */
    fun buttonTextFor(languageCode: String = "zh", countryCode: String = "CN"): String {
        val lang = languageCode.lowercase()
        val country = countryCode.lowercase()
        val candidates = ArrayList<String>()
        if (lang.isNotEmpty() && country.isNotEmpty()) candidates.add("$lang-$country")
        if (lang.isNotEmpty()) candidates.add(lang)
        candidates.add("default")
        for (key in candidates) {
            val text = btnText[key] ?: ""
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    /** 打开创作页的参数。MV 且有剧情时写入 `customStoryPlot`。参数为空返回 null。 */
    fun routeArguments(includePlot: Boolean = false): JSONObject? {
        val args = JSONObject(params.toString())
        if (includePlot) {
            val plot = customStoryPlot?.trim() ?: ""
            if (plot.isNotEmpty()) args.put("customStoryPlot", plot)
        }
        if (args.length() == 0) return null
        return args
    }

    /** 广场播放页只认视频实体。用序号做成负数 id，避免和真实 MV 撞键。 */
    fun toVideo(index: Int): VideoContentEntity = VideoContentEntity(
        type = VideoContentType.mv,
        id = -(index + 1).toLong(),
        userId = 0L,
        title = title,
        firstFrameImg = firstFrameImg,
        resultUrl = resultUrl,
        imageComp = imageComp.ifEmpty { "1:1" },
        isWatermark = isWatermark,
        customStoryPlot = customStoryPlot,
    )

    companion object {
        fun fromJson(json: JSONObject): HomeBannerItem {
            val plot = text(json.opt("customStoryPlot"))
            val parsed = parseParams(json.opt("parmas"))
            val comp = text(json.opt("imageComp"))
            return HomeBannerItem(
                firstFrameImg = text(json.opt("firstFrameImg")),
                resultUrl = text(json.opt("resultUrl")),
                title = text(json.opt("title")),
                customStoryPlot = plot.ifEmpty { null },
                isWatermark = toInt(json.opt("isWatermark")),
                imageComp = comp.ifEmpty { "1:1" },
                router = text(json.opt("router")),
                params = parsed.first,
                paramsInvalid = parsed.second,
                btnText = parseBtnText(json.opt("btn_text")),
            )
        }

        /** 从素材 data 里取出轮播列表。data 本身是列表，或某一层列表里的项带这些字段。 */
        fun parseList(raw: Any?): List<HomeBannerItem> =
            itemMaps(raw).filter { looksLikeItem(it) }.map { fromJson(it) }
                .filter { it.firstFrameImg.isNotEmpty() || it.hasVideo }

        private fun arrMaps(arr: JSONArray): List<JSONObject> {
            val out = ArrayList<JSONObject>()
            for (i in 0 until arr.length()) (jsonValueOrNull(arr.opt(i)) as? JSONObject)?.let { out.add(it) }
            return out
        }

        private fun itemMaps(raw: Any?): List<JSONObject> {
            val v = jsonValueOrNull(raw)
            if (v is JSONArray) return arrMaps(v)
            val map = v as? JSONObject ?: return emptyList()
            if (looksLikeItem(map)) return listOf(map)
            val keys = map.keys()
            while (keys.hasNext()) {
                val value = jsonValueOrNull(map.opt(keys.next()))
                if (value !is JSONArray) continue
                val items = arrMaps(value).filter { looksLikeItem(it) }
                if (items.isNotEmpty()) return items
            }
            return emptyList()
        }

        private fun looksLikeItem(map: JSONObject): Boolean =
            map.has("firstFrameImg") || map.has("resultUrl") || map.has("router") || map.has("btn_text")

        private fun text(raw: Any?): String {
            val v = jsonValueOrNull(raw) ?: return ""
            if (v is Double && v == kotlin.math.floor(v) && !v.isInfinite()) return v.toLong().toString()
            return v.toString().trim()
        }

        private fun toInt(raw: Any?): Int {
            val v = jsonValueOrNull(raw)
            if (v is Number) return kotlin.math.round(v.toDouble()).toInt()
            return text(v).toIntOrNull() ?: 0
        }

        /** 返回 (参数, 是否非法)。 */
        private fun parseParams(raw: Any?): Pair<JSONObject, Boolean> {
            val v = jsonValueOrNull(raw) ?: return JSONObject() to false
            if (v is JSONObject) return v to false
            if (v !is String) return JSONObject() to true
            val t = v.trim()
            if (t.isEmpty()) return JSONObject() to false
            return try {
                if (!t.startsWith("{")) JSONObject() to true else JSONObject(t) to false
            } catch (e: Throwable) {
                JSONObject() to true
            }
        }

        private fun parseBtnText(raw: Any?): Map<String, String> {
            var v = jsonValueOrNull(raw)
            if (v is String) {
                val t = v.trim()
                if (t.startsWith("{")) v = try { JSONObject(t) } catch (e: Throwable) { v }
            }
            val map = v as? JSONObject ?: return emptyMap()
            val out = LinkedHashMap<String, String>()
            val keys = map.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val t = text(map.opt(key))
                if (t.isEmpty()) continue
                out[key.trim().lowercase()] = t
            }
            return out
        }
    }
}
