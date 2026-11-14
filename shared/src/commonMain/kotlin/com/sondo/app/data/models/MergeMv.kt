package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** MV 再次合成请求体（Dart: lib/data/models/merge_mv.dart `MergeMv`）。 */
data class MergeMv(
    var mvId: Long = 0L,
    var genMode: Int = 0,
    var title: String = "",
    var name: String = "",
    var isTitle: Int = 0,
    var isName: Int = 0,
    var isLyric: Int? = null,
    var isWatermark: Int = 0,
    /** 后端字段名是全小写的 isupdt */
    var isupdt: Int = 0,
    var firstFrameImg: String? = null,
    var quality: String = "",
    var mergeBs: Int = 0,
    var userNeedPoint: Int? = null,
    var capcutEnabled: Boolean? = null,
) {
    /** userNeedPoint / capcutEnabled 为 null 时不带该键。 */
    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("mvId", mvId)
            .put("genMode", genMode)
            .put("title", title)
            .put("name", name)
            .put("isTitle", isTitle)
            .put("isName", isName)
            .put("isLyric", isLyric)
            .put("isWatermark", isWatermark)
            .put("isupdt", isupdt)
            .put("firstFrameImg", firstFrameImg)
            .put("quality", quality)
            .put("mergeBs", mergeBs)
        userNeedPoint?.let { o.put("userNeedPoint", it) }
        capcutEnabled?.let { o.put("capcutEnabled", it) }
        return o
    }

    companion object {
        fun fromJson(json: JSONObject): MergeMv = MergeMv(
            mvId = json.jLong("mvId"),
            genMode = json.jInt("genMode"),
            title = json.jStr("title"),
            name = json.jStr("name"),
            isTitle = json.jInt("isTitle"),
            isName = json.jInt("isName"),
            isLyric = json.jIntOrNull("isLyric"),
            isWatermark = json.jInt("isWatermark"),
            isupdt = json.jInt("isupdt"),
            firstFrameImg = json.jStrOrNull("firstFrameImg"),
            quality = json.jStr("quality"),
            mergeBs = json.jInt("mergeBs"),
            userNeedPoint = json.jIntOrNull("userNeedPoint"),
            capcutEnabled = json.jBoolOrNull("capcutEnabled"),
        )

        fun listFromJson(arr: JSONArray?): List<MergeMv> = arr.jMap { fromJson(it) }
    }
}

/** 封面重生状态（Dart: lib/data/models/merge_mv.dart `CoverStatusModel`）。 */
data class CoverStatusModel(
    var id: Long = 0L,
    var againCoverImgStatus: Int? = null,
    var firstFrameImg: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("againCoverImgStatus", againCoverImgStatus)
        .put("firstFrameImg", firstFrameImg)

    companion object {
        fun fromJson(json: JSONObject): CoverStatusModel = CoverStatusModel(
            id = json.jLong("id", 0L),
            againCoverImgStatus = json.jIntOrNull("againCoverImgStatus"),
            firstFrameImg = json.jStr("firstFrameImg"),
        )

        /** Dart 的 `id` 必填整数，缺失抛异常；这里返回 null。 */
        fun fromJsonOrNull(json: JSONObject?): CoverStatusModel? {
            if (json == null) return null
            if (json.jRaw("id") !is Number) return null
            return fromJson(json)
        }

        fun listFromJson(arr: JSONArray?): List<CoverStatusModel> = arr.jMap { fromJson(it) }
    }
}
