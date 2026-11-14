package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** MV 分镜（Dart: lib/data/models/mv_shot.dart `MvShot`）。 */
data class MvShot(
    var id: Long = 0L,
    var taskId: Long = 0L,
    var shotNumber: Int = 0,
    /** 0 环境镜头 1 对口型 2 人物镜头 3 环境冲击镜头 4 人物特写镜头 */
    var shotType: Int = 0,
    var timestamp: String = "",
    /** 文生图 */
    var klingImg: String? = "",
    /** 0: 未开始 1: 生成中 2: 已完成 3: 失败 */
    var status: Int = 0,
    var videoUrl: String? = "",
    var errorMsg: String? = "",
    /** 第一帧图片 */
    var firstFrameImg: String? = "",
    /** 动态图 */
    var dynamicGraph: String? = "",
    /** 时长 */
    var duration: Int = 0,
    /** 时长 浮点数 */
    var durationV1: Double = 0.0,
    /** 长描述 */
    var description: String = "",
    /** 短描述 */
    var shortDesc: String = "",
    /** 0: 文生视频, 1: 文生图->图生视频 */
    var mvType: Int? = 0,
    /** 文生图的描述 */
    var scene: String? = "",
    /** 参考图 */
    var coverImg: String = "",
    /** 参考图列表 逗号分隔 */
    var referenceImgs: String? = "",
    /** 失败分镜重试所需积分；列表未带 calculateRetryPoints 时为 0。 */
    var retryNeedPoint: Int = 0,
) {
    val videoPrompt: String
        get() = when {
            shortDesc.isNotEmpty() -> shortDesc
            description.isNotEmpty() -> description
            else -> "-"
        }

    val referenceImgsList: List<ShotImageModel>
        get() {
            val r = referenceImgs
            if (!r.isNullOrEmpty()) return r.split(",").map { ShotImageModel(imageUrl = it) }
            return emptyList()
        }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("taskId", taskId)
        .put("shotNumber", shotNumber)
        .put("shotType", shotType)
        .put("timestamp", timestamp)
        .put("klingImg", klingImg)
        .put("status", status)
        .put("videoUrl", videoUrl)
        .put("errorMsg", errorMsg)
        .put("firstFrameImg", firstFrameImg)
        .put("dynamicGraph", dynamicGraph)
        .put("duration", duration)
        .put("durationV1", durationV1)
        .put("description", description)
        .put("shortDesc", shortDesc)
        .put("mvType", mvType)
        .put("scene", scene)
        .put("coverImg", coverImg)
        .put("referenceImgs", referenceImgs)
        .put("retryNeedPoint", retryNeedPoint)

    companion object {
        fun fromJson(json: JSONObject): MvShot = MvShot(
            id = json.jLong("id", 0L),
            taskId = json.jLong("taskId", 0L),
            shotNumber = json.jInt("shotNumber", 0),
            shotType = json.jInt("shotType", 0),
            timestamp = json.jStr("timestamp"),
            description = json.jStr("description"),
            klingImg = json.jStr("klingImg"),
            status = json.jInt("status", 0),
            videoUrl = json.jStr("videoUrl"),
            errorMsg = json.jStr("errorMsg"),
            firstFrameImg = json.jStr("firstFrameImg"),
            dynamicGraph = json.jStr("dynamicGraph"),
            duration = json.jInt("duration", 0),
            durationV1 = json.jDbl("durationV1", 0.0),
            shortDesc = json.jStr("shortDesc"),
            mvType = json.jInt("mvType", 0),
            scene = json.jStr("scene"),
            coverImg = json.jStr("coverImg"),
            referenceImgs = json.jStr("referenceImgs"),
            retryNeedPoint = json.jInt("retryNeedPoint", 0),
        )

        /**
         * Dart 的 id / taskId / shotNumber / shotType / status（数字）与 timestamp（字符串）必填，
         * 缺失时 fromJson 抛异常；这里返回 null。
         */
        fun fromJsonOrNull(json: JSONObject?): MvShot? {
            if (json == null) return null
            for (k in arrayOf("id", "taskId", "shotNumber", "shotType", "status")) {
                if (json.jRaw(k) !is Number) return null
            }
            if (json.jRaw("timestamp") !is String) return null
            return fromJson(json)
        }

        fun listFromJson(arr: JSONArray?): List<MvShot> = arr.jMap { fromJson(it) }

        /** 严格列表：任一条解析失败整体为 null（Dart 整个列表解析抛异常）。 */
        fun listFromJsonOrNull(arr: JSONArray?): List<MvShot>? = arr.jMapStrict { fromJsonOrNull(it) }
    }
}

/** 分镜参考图（Dart: lib/data/models/mv_shot.dart `MvShotRefImg`）。 */
data class MvShotRefImg(
    var id: Long = 0L,
    var createTime: Long = 0L,
    var taskId: Long = 0L,
    var genMode: Int = 0,
    var shotId: Long = 0L,
    var firstFrameImg: String = "",
    var status: Int = 0,
    var coverImage: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("createTime", createTime)
        .put("taskId", taskId)
        .put("genMode", genMode)
        .put("shotId", shotId)
        .put("firstFrameImg", firstFrameImg)
        .put("status", status)
        .put("coverImage", coverImage)

    companion object {
        fun fromJson(json: JSONObject): MvShotRefImg = MvShotRefImg(
            id = json.jLong("id", 0L),
            createTime = json.jLong("createTime", 0L),
            taskId = json.jLong("taskId", 0L),
            genMode = json.jInt("genMode", 0),
            shotId = json.jLong("shotId", 0L),
            firstFrameImg = json.jStr("firstFrameImg"),
            status = json.jInt("status", 0),
            coverImage = json.jStr("coverImage"),
        )

        fun listFromJson(arr: JSONArray?): List<MvShotRefImg> = arr.jMap { fromJson(it) }
    }
}

/**
 * 分镜参考图的本地编辑态（Dart: lib/data/models/mv_shot.dart `ShotImageModel`）。
 * Dart 的 `File? imageFile` 在这里是本地文件路径。
 */
data class ShotImageModel(
    var isHasSensitive: Boolean = false,
    var imageUrl: String = "",
    var imageLoading: Boolean = false,
    var isImageFailed: Boolean = false,
    var imageFile: String? = null,
    var errorMsg: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("isHasSensitive", isHasSensitive)
        .put("imageUrl", imageUrl)
        .put("imageLoading", imageLoading)
        .put("isImageFailed", isImageFailed)
        .put("imageFile", imageFile)
        .put("errorMsg", errorMsg)

    companion object {
        fun fromJson(json: JSONObject): ShotImageModel = ShotImageModel(
            isHasSensitive = json.jBool("isHasSensitive", false),
            imageUrl = json.jStr("imageUrl"),
            imageLoading = json.jBool("imageLoading", false),
            isImageFailed = json.jBool("isImageFailed", false),
            imageFile = json.jStrOrNull("imageFile"),
            errorMsg = json.jStr("errorMsg"),
        )

        fun listFromJson(arr: JSONArray?): List<ShotImageModel> = arr.jMap { fromJson(it) }
    }
}
