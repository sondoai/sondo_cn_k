package com.sondo.app.data.api

import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiResult
import com.sondo.app.core.utils.FakeProgressController
import com.sondo.app.core.utils.HostNative
import com.sondo.app.core.utils.OssUploader
import com.sondo.app.data.models.ImageUploadResult
import com.sondo.app.data.models.ImageUploadStatus
import com.sondo.app.data.models.OssPresignedResponse
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 跨 Feature 共用的 OSS 图片上传与敏感检测
 * （Dart: lib/shared/data/datasources/file_upload_remote_datasource.dart `FileUploadRemoteDataSource`，
 * 并入 lib/shared/data/repositories/file_upload_repository_impl.dart）。
 */
object FileUploadApi {
    /** 20 MB */
    const val uploadFileSizeLimit = 20 * 1024 * 1024

    /**
     * OSS 直传 + 图片敏感检测。[filePath] 是本地文件路径；[onProgress] 回调 1~99 的假进度。
     * 与 Dart 一致**永远 Success**：上传/检测结果在 [ImageUploadResult.status] 里
     * （success / failure（size 为超限时的上限 MB）/ check（审核拦截原因））。
     */
    suspend fun uploadImageWithCheck(
        filePath: String,
        onProgress: ((progress: Int) -> Unit)? = null,
    ): ApiResult<ImageUploadResult> {
        val fakeProgress = FakeProgressController()
        val result: ImageUploadResult = try {
            fakeProgress.start { onProgress?.invoke(it) }
            val upload = uploadFile(filePath, "image")
            val url = upload.url
            if (upload.status != ImageUploadStatus.success || url == null) {
                upload
            } else {
                when (val check = checkImageV2(url, 1)) {
                    is ApiResult.Failure -> ImageUploadResult.failure()
                    is ApiResult.Success -> if (check.data.isEmpty()) {
                        fakeProgress.complete { onProgress?.invoke(it) }
                        ImageUploadResult.success(url)
                    } else {
                        ImageUploadResult.check(check.data)
                    }
                }
            }
        } catch (e: Throwable) {
            ImageUploadResult.failure()
        } finally {
            fakeProgress.dispose()
        }
        return ApiResult.Success(result)
    }

    private suspend fun uploadFile(filePath: String, mediaType: String): ImageUploadResult {
        return try {
            val fileName = filePath.substringAfterLast('/')
            val presign = getPresignedUrl(fileName, mediaType) ?: return ImageUploadResult.failure()
            val fileSize = HostNative.file.size(filePath)
            if (fileSize < 0) return ImageUploadResult.failure()
            val effectiveMax = if (presign.maxSize > 0) presign.maxSize else uploadFileSizeLimit.toLong()
            if (fileSize > effectiveMax) {
                // 向上取整到 MB，至少 1
                val mb = ((effectiveMax + 1024 * 1024 - 1) / (1024 * 1024)).toInt()
                return ImageUploadResult.failure(size = if (mb < 1) 1 else mb)
            }
            val uploadUrl = presign.uploadUrl
            // 图片流程不看 instantComplete：没有上传地址即失败
            if (uploadUrl.isNullOrEmpty()) return ImageUploadResult.failure()
            OssUploader.putFile(uploadUrl, filePath)
            ImageUploadResult.success(presign.fileUrl)
        } catch (e: Throwable) {
            ImageUploadResult.failure()
        }
    }

    private suspend fun getPresignedUrl(fileName: String, mediaType: String): OssPresignedResponse? {
        val res = Api.get(
            ApiPaths.uploadPresigned,
            query = mapOf("fileName" to fileName, "mediaType" to mediaType, "fileMd5" to ""),
        )
        val data = res.getOrNull()?.dataObject() ?: return null
        return OssPresignedResponse.fromJson(data)
    }

    /**
     * 图片敏感检测。[type] 0=原逻辑，1=素材库检测。
     * Success 里非空字符串是拦截原因，空字符串表示通过；请求失败为 Failure（Dart 抛异常）。
     */
    suspend fun checkImageV2(imageUrl: String, type: Int): ApiResult<String> =
        Api.post(ApiPaths.uploadFileCheckV2, JSONObject().put("imageUrl", imageUrl).put("type", type))
            .map { (it.dataAny() as? String) ?: "" }
}
