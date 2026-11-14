package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 首页数据（Dart: lib/data/models/home_model.dart `HomeModel`）。 */
data class HomeModel(
    var hotList: List<SongModel> = emptyList(),
    var musicCateList: List<MusicCateModel> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("hotList", hotList.toJsonArray { it.toJson() })
        .put("musicCateList", musicCateList.toJsonArray { it.toJson() })

    companion object {
        fun fromJson(json: JSONObject): HomeModel = HomeModel(
            hotList = SongModel.listFromJson(json.jArr("hotList")),
            musicCateList = MusicCateModel.listFromJson(json.jArr("musicCateList")),
        )

        /** 内部歌曲缺 id 时 Dart 整体抛异常；这里返回 null。 */
        fun fromJsonOrNull(json: JSONObject?): HomeModel? {
            if (json == null) return null
            val hot = SongModel.listFromJsonOrNull(json.jArr("hotList")) ?: return null
            val cates = json.jArr("musicCateList").jMapStrict { MusicCateModel.fromJsonOrNull(it) } ?: return null
            return HomeModel(hot, cates)
        }

        fun listFromJson(arr: JSONArray?): List<HomeModel> = arr.jMap { fromJson(it) }
    }
}

/** 首页分类歌单（Dart: lib/data/models/home_model.dart `MusicCateModel`）。 */
data class MusicCateModel(
    var categoryName: String = "",
    var indexTitle: String = "",
    var musicList: List<SongModel> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("categoryName", categoryName)
        .put("indexTitle", indexTitle)
        .put("musicList", musicList.toJsonArray { it.toJson() })

    companion object {
        fun fromJson(json: JSONObject): MusicCateModel = MusicCateModel(
            categoryName = json.jStr("categoryName"),
            indexTitle = json.jStr("indexTitle"),
            musicList = SongModel.listFromJson(json.jArr("musicList")),
        )

        fun fromJsonOrNull(json: JSONObject?): MusicCateModel? {
            if (json == null) return null
            val list = SongModel.listFromJsonOrNull(json.jArr("musicList")) ?: return null
            return MusicCateModel(json.jStr("categoryName"), json.jStr("indexTitle"), list)
        }

        fun listFromJson(arr: JSONArray?): List<MusicCateModel> = arr.jMap { fromJson(it) }
    }
}

/** 首页分类入口（Dart: lib/data/models/home_model.dart `CategoryModel`）。 */
data class CategoryModel(
    var categoryName: String = "",
    var categoryPath: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("categoryName", categoryName)
        .put("categoryPath", categoryPath)

    companion object {
        fun fromJson(json: JSONObject): CategoryModel = CategoryModel(
            categoryName = json.jStr("categoryName"),
            categoryPath = json.jStr("categoryPath"),
        )

        fun listFromJson(arr: JSONArray?): List<CategoryModel> = arr.jMap { fromJson(it) }
    }
}
