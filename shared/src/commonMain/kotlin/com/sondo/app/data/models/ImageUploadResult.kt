package com.sondo.app.data.models

/** 图片上传+检测结果状态（Dart: lib/shared/domain/entities/image_upload_result.dart `ImageUploadStatus`）。 */
enum class ImageUploadStatus {
    /** 上传并通过敏感检测。 */
    success,

    /** 上传失败（含超限）。 */
    failure,

    /** 敏感/版权拦截。 */
    check,
}

/** 图片上传+检测统一结果（Dart: lib/shared/domain/entities/image_upload_result.dart `ImageUploadResult`）。 */
data class ImageUploadResult(
    var status: ImageUploadStatus = ImageUploadStatus.failure,
    /** [ImageUploadStatus.success] 时的资源 URL。 */
    var url: String? = null,
    /** [ImageUploadStatus.failure] 且因超限时的上限 MB；其它失败为 null。 */
    var size: Int? = null,
    /** [ImageUploadStatus.check] 时的审核拦截原因。 */
    var check: String? = null,
) {
    companion object {
        fun success(url: String): ImageUploadResult = ImageUploadResult(ImageUploadStatus.success, url = url)

        fun failure(size: Int? = null): ImageUploadResult = ImageUploadResult(ImageUploadStatus.failure, size = size)

        fun check(check: String): ImageUploadResult = ImageUploadResult(ImageUploadStatus.check, check = check)
    }
}
