package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 分片上传初始化结果（Dart: lib/data/models/oss_presign_model.dart `OssMultipartInitResponse`）。 */
data class OssMultipartInitResponse(
    /** 为 true 表示秒传，无需再走分片上传。 */
    var instantComplete: Boolean = false,
    /** 秒传时可为空。 */
    var initUploadUrl: String? = null,
    var objectKey: String = "",
    var fileUrl: String = "",
    var fileName: String = "",
    var id: Long? = null,
    var fileMd5: String? = null,
    var partSize: Long = 0L,
    var maxSize: Long = 0L,
) {
    val isInstantComplete: Boolean get() = instantComplete

    fun toJson(): JSONObject = JSONObject()
        .put("instantComplete", instantComplete)
        .put("initUploadUrl", initUploadUrl)
        .put("objectKey", objectKey)
        .put("fileUrl", fileUrl)
        .put("fileName", fileName)
        .put("id", id)
        .put("fileMd5", fileMd5)
        .put("partSize", partSize)
        .put("maxSize", maxSize)

    companion object {
        fun fromJson(json: JSONObject): OssMultipartInitResponse = OssMultipartInitResponse(
            instantComplete = json.jBool("instantComplete", false),
            initUploadUrl = json.jStrOrNull("initUploadUrl"),
            objectKey = json.jStr("objectKey"),
            fileUrl = json.jStr("fileUrl"),
            fileName = json.jStr("fileName"),
            id = json.jLongOrNull("id"),
            fileMd5 = json.jStrOrNull("fileMd5"),
            partSize = json.jLong("partSize", 0L),
            maxSize = json.jLong("maxSize", 0L),
        )

        fun listFromJson(arr: JSONArray?): List<OssMultipartInitResponse> = arr.jMap { fromJson(it) }
    }
}

/** 分片上传地址（Dart: lib/data/models/oss_presign_model.dart `OssPartUrlItem`）。 */
data class OssPartUrlItem(
    var partNumber: Int = 0,
    var uploadUrl: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("partNumber", partNumber).put("uploadUrl", uploadUrl)

    companion object {
        fun fromJson(json: JSONObject): OssPartUrlItem = OssPartUrlItem(json.jInt("partNumber"), json.jStr("uploadUrl"))

        /** Dart 两个字段必填，缺失抛异常；这里返回 null。 */
        fun fromJsonOrNull(json: JSONObject?): OssPartUrlItem? {
            if (json == null) return null
            return parseOrNull { OssPartUrlItem(json.reqInt("partNumber"), json.reqStr("uploadUrl")) }
        }

        fun listFromJson(arr: JSONArray?): List<OssPartUrlItem> = arr.jMap { fromJson(it) }
    }
}

/** 分片上传地址列表（Dart: lib/data/models/oss_presign_model.dart `OssMultipartPartUrlsResponse`）。 */
data class OssMultipartPartUrlsResponse(
    var parts: List<OssPartUrlItem> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().put("parts", parts.toJsonArray { it.toJson() })

    companion object {
        fun fromJson(json: JSONObject): OssMultipartPartUrlsResponse =
            OssMultipartPartUrlsResponse(OssPartUrlItem.listFromJson(json.jArr("parts")))

        /** Dart 的 `parts` 必填且每项必须完整，否则抛异常；这里返回 null。 */
        fun fromJsonOrNull(json: JSONObject?): OssMultipartPartUrlsResponse? {
            val arr = json?.jArr("parts") ?: return null
            val parts = arr.jMapStrict { OssPartUrlItem.fromJsonOrNull(it) } ?: return null
            return OssMultipartPartUrlsResponse(parts)
        }

        fun listFromJson(arr: JSONArray?): List<OssMultipartPartUrlsResponse> = arr.jMap { fromJson(it) }
    }
}

/** 分片合并地址（Dart: lib/data/models/oss_presign_model.dart `OssMultipartCompleteUrlResponse`）。 */
data class OssMultipartCompleteUrlResponse(
    var completeUrl: String = "",
    var fileUrl: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("completeUrl", completeUrl).put("fileUrl", fileUrl)

    companion object {
        fun fromJson(json: JSONObject): OssMultipartCompleteUrlResponse =
            OssMultipartCompleteUrlResponse(json.jStr("completeUrl"), json.jStr("fileUrl"))

        /** Dart 两个字段必填，缺失抛异常；这里返回 null。 */
        fun fromJsonOrNull(json: JSONObject?): OssMultipartCompleteUrlResponse? {
            if (json == null) return null
            return parseOrNull { OssMultipartCompleteUrlResponse(json.reqStr("completeUrl"), json.reqStr("fileUrl")) }
        }

        fun listFromJson(arr: JSONArray?): List<OssMultipartCompleteUrlResponse> = arr.jMap { fromJson(it) }
    }
}

/** 单文件预签名上传结果（Dart: lib/data/models/oss_presign_model.dart `OssPresignedResponse`）。 */
data class OssPresignedResponse(
    /** 为 true 表示秒传，无需再 PUT。 */
    var instantComplete: Boolean = false,
    /** 秒传时可为空。 */
    var uploadUrl: String? = null,
    var fileUrl: String = "",
    var fileName: String = "",
    var objectKey: String = "",
    var maxSize: Long = 0L,
    var expireSeconds: Int = 600,
) {
    val isInstantComplete: Boolean get() = instantComplete

    fun toJson(): JSONObject = JSONObject()
        .put("instantComplete", instantComplete)
        .put("uploadUrl", uploadUrl)
        .put("fileUrl", fileUrl)
        .put("fileName", fileName)
        .put("objectKey", objectKey)
        .put("maxSize", maxSize)
        .put("expireSeconds", expireSeconds)

    companion object {
        fun fromJson(json: JSONObject): OssPresignedResponse = OssPresignedResponse(
            instantComplete = json.jBool("instantComplete", false),
            uploadUrl = json.jStrOrNull("uploadUrl"),
            fileUrl = json.jStr("fileUrl"),
            fileName = json.jStr("fileName"),
            objectKey = json.jStr("objectKey"),
            maxSize = json.jLong("maxSize", 0L),
            expireSeconds = json.jInt("expireSeconds", 600),
        )

        fun listFromJson(arr: JSONArray?): List<OssPresignedResponse> = arr.jMap { fromJson(it) }
    }
}
