package com.sondo.app.features.square.data

import com.sondo.app.data.models.SongEntity
import com.sondo.app.data.models.VideoContentEntity
import com.sondo.app.data.models.jInt
import com.sondo.app.data.models.jRaw
import com.sondo.app.data.models.jStr
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 广场分页列表数据。total = -1 表示请求失败。 */
data class SquarePageEntity(val list: List<VideoContentEntity>, val total: Int)

/** 个人主页 Music Tab 分页数据。 */
data class SquareProfileMusicPageEntity(val list: List<SongEntity>, val total: Int)

/** 个人主页用户资料。 */
data class SquareProfileUserEntity(
    /** 展示昵称（已脱敏）。 */
    val nickname: String,
    val avatar: String,
    /** 总浏览量。 */
    val totalViews: Int,
    /** 总点赞数。 */
    val totalLikes: Int,
)

/**
 * 广场列表页参数（`Routes.square`）：`{videoType: Int = 0, type: "trending"|"new"|"", isFindHot: Int = 0}`。
 */
data class SquareListArgs(val videoType: Int = 0, val type: String = "", val isFindHot: Int = 0) {
    fun toJson(): JSONObject = JSONObject().put("videoType", videoType).put("type", type).put("isFindHot", isFindHot)

    companion object {
        fun fromJson(json: JSONObject?): SquareListArgs {
            if (json == null) return SquareListArgs()
            return SquareListArgs(json.jInt("videoType", 0), json.jStr("type"), json.jInt("isFindHot", 0))
        }
    }
}

/**
 * 个人主页参数（`Routes.profile`）：`{userId: Long（也接受字符串）, type: "mv"|"music"|"dance" = "mv"}`。
 */
data class SquareProfileArgs(val userId: Long, val type: String = "mv") {
    fun toJson(): JSONObject = JSONObject().put("userId", userId).put("type", type)

    companion object {
        fun fromJson(json: JSONObject?): SquareProfileArgs {
            if (json == null) return SquareProfileArgs(0L)
            // 推送 / 旧参数的 userId 可能是字符串
            val raw = json.jRaw("userId")
            val id = when (raw) {
                is Number -> raw.toLong()
                is String -> raw.trim().toLongOrNull() ?: 0L
                else -> 0L
            }
            return SquareProfileArgs(id, json.jStr("type", "mv").ifEmpty { "mv" })
        }
    }
}

/**
 * 广场全屏播放页参数（`Routes.squarePlay` / `Routes.squarePlayDetail`，Dart `SquarePlayArgs`）。
 *
 * 页面参数 JSON：
 * `{videoType: Int = 0, listKey?: String, videoList?: [VideoContentEntity json], initialIndex: Int = 0, pageNo: Int = 1,
 *   isFindHot: Int = 0, imageComp: String = "", isIndex: Int = 0, isLoadMore: Boolean = true, showAuthorActions: Boolean = true}`。
 * 列表优先用 `listKey`（[SquarePlayListHolder.put] 存的进程内列表，取后即清），没有再读 `videoList`。
 */
data class SquarePlayArgs(
    /** 0=MV，1=舞蹈（已下线）。 */
    val videoType: Int = 0,
    val videoList: List<VideoContentEntity> = emptyList(),
    val initialIndex: Int = 0,
    val pageNo: Int = 1,
    /** 是否热门：0 否，1 是。 */
    val isFindHot: Int = 0,
    /** 画幅比例，如 16:9、9:16。 */
    val imageComp: String = "",
    /** 是否首页入口：0 否，1 是。 */
    val isIndex: Int = 0,
    /** 是否支持加载更多。 */
    val isLoadMore: Boolean = true,
    /** 是否展示右侧头像和收藏。 */
    val showAuthorActions: Boolean = true,
) {
    /** 生成页面参数：列表放进 [SquarePlayListHolder]，参数里只带 listKey。 */
    fun toArgs(): JSONObject = JSONObject()
        .put("videoType", videoType)
        .put("listKey", SquarePlayListHolder.put(videoList))
        .put("initialIndex", initialIndex)
        .put("pageNo", pageNo)
        .put("isFindHot", isFindHot)
        .put("imageComp", imageComp)
        .put("isIndex", isIndex)
        .put("isLoadMore", isLoadMore)
        .put("showAuthorActions", showAuthorActions)

    companion object {
        fun fromJson(json: JSONObject?): SquarePlayArgs {
            if (json == null) return SquarePlayArgs()
            val key = json.jStr("listKey")
            var list = if (key.isNotEmpty()) SquarePlayListHolder.take(key) else emptyList()
            if (list.isEmpty()) list = VideoContentEntity.listFromJson(json.jRaw("videoList") as? JSONArray)
            return SquarePlayArgs(
                videoType = json.jInt("videoType", 0),
                videoList = list,
                initialIndex = json.jInt("initialIndex", 0),
                pageNo = json.jInt("pageNo", 1),
                isFindHot = json.jInt("isFindHot", 0),
                imageComp = json.jStr("imageComp"),
                isIndex = json.jInt("isIndex", 0),
                isLoadMore = (json.jRaw("isLoadMore") as? Boolean) ?: true,
                showAuthorActions = (json.jRaw("showAuthorActions") as? Boolean) ?: true,
            )
        }
    }
}

/** 广场播放页的进程内列表交接（大列表不走页面参数）。 */
object SquarePlayListHolder {
    private var seq = 0
    private val lists = LinkedHashMap<String, List<VideoContentEntity>>()

    fun put(list: List<VideoContentEntity>): String {
        val key = "square_play_${++seq}"
        // 只留最近几份，避免未打开的页面堆积
        while (lists.size >= 4) lists.remove(lists.keys.first())
        lists[key] = list
        return key
    }

    fun take(key: String): List<VideoContentEntity> = lists.remove(key) ?: emptyList()
}
