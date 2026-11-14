package com.sondo.app.data.api

import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiResult
import com.sondo.app.data.models.OssMultipartCompleteUrlResponse
import com.sondo.app.data.models.OssMultipartInitResponse
import com.sondo.app.data.models.OssMultipartPartUrlsResponse
import com.sondo.app.data.models.OssPresignedResponse
import com.sondo.app.data.models.jRaw
import com.sondo.app.data.models.jStr
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** my-audio 上传登记结果（Dart: `OssPresignRemoteDataSource.registerUploadComplete` 的返回记录）。 */
data class AudioRegisterResult(
    var id: Long = 0L,
    var fileUrl: String = "",
)

/**
 * OSS 预签名与分片上传
 * （Dart: lib/shared/data/datasources/oss_presign_remote_datasource.dart `OssPresignRemoteDataSource`）。
 * 所有方法：请求失败、业务码非 0 或 data 为空时 Dart 返回 null，这里是 Failure。
 */
object OssPresignApi {

    /** 初始化 OSS 分片上传会话（大文件）。[fileMd5] 命中服务端记录时可直接秒传完成（isInstantComplete）。 */
    suspend fun initMultipartUpload(
        fileName: String,
        mediaType: String,
        fileMd5: String,
    ): ApiResult<OssMultipartInitResponse> =
        Api.post(
            ApiPaths.uploadMultipartInit,
            JSONObject().put("fileName", fileName).put("mediaType", mediaType).put("fileMd5", fileMd5),
        ).parse { env -> env.dataObject()?.let { OssMultipartInitResponse.fromJson(it) } }

    /** 按分片数量批量获取分片上传的预签名 PUT URL。 */
    suspend fun getPartUrls(
        objectKey: String,
        uploadId: String,
        partCount: Int,
    ): ApiResult<OssMultipartPartUrlsResponse> =
        Api.post(
            ApiPaths.uploadMultipartPartUrls,
            JSONObject().put("objectKey", objectKey).put("uploadId", uploadId).put("partCount", partCount),
        ).parse { OssMultipartPartUrlsResponse.fromJsonOrNull(it.dataObject()) }

    /** 获取分片上传完成后、通知 OSS 合并分片所需的预签名 URL。 */
    suspend fun getCompleteUrl(objectKey: String, uploadId: String): ApiResult<OssMultipartCompleteUrlResponse> =
        Api.post(
            ApiPaths.uploadMultipartCompleteUrl,
            JSONObject().put("objectKey", objectKey).put("uploadId", uploadId),
        ).parse { OssMultipartCompleteUrlResponse.fromJsonOrNull(it.dataObject()) }

    /** 获取单文件直传（小文件）的预签名 PUT URL。[fileMd5] 命中服务端记录时可直接秒传完成。 */
    suspend fun getPresignedUrl(
        fileName: String,
        mediaType: String,
        fileMd5: String = "",
    ): ApiResult<OssPresignedResponse> =
        Api.get(
            ApiPaths.uploadPresigned,
            query = mapOf("fileName" to fileName, "mediaType" to mediaType, "fileMd5" to fileMd5),
        ).parse { env -> env.dataObject()?.let { OssPresignedResponse.fromJson(it) } }

    /** 通知后端 my-audio 上传完成。[duration] 为毫秒。回包缺 id 视为失败。 */
    suspend fun registerUploadComplete(
        objectKey: String,
        fileName: String,
        fileSize: Long,
        mimeType: String,
        duration: Int,
        fileMd5: String,
    ): ApiResult<AudioRegisterResult> =
        Api.post(
            ApiPaths.myAudiosRegister,
            JSONObject()
                .put("objectKey", objectKey)
                .put("fileName", fileName)
                .put("fileSize", fileSize)
                .put("fileMd5", fileMd5)
                .put("mimeType", mimeType)
                .put("duration", duration),
        ).parse { env ->
            val data = env.dataObject() ?: return@parse null
            val id = data.jRaw("id") as? Number ?: return@parse null
            AudioRegisterResult(id.toLong(), data.jStr("fileUrl"))
        }
}
