package com.sondo.app.core.utils

/**
 * 下载和分享共用的导出步骤。可见水印在成片生成时由后端决定（MergeMv.isWatermark）；
 * 客户端不烧「AI生成」，也不核对或写入隐式标识，文件按下载结果原样交付。
 *
 * 写入相册（含权限说明、永久拒绝后的去设置引导、串行保存）在 [CommonUtils.saveVideoToGallery] /
 * [CommonUtils.saveImageToGallery]；水印开关的保存接口在账号层（UserStore.saveAigcWatermark）。
 * 「无水印导出登记」接口（confirmUnmarkedExport）在参考工程里没有调用方，这里不接入。
 */
object AigcMediaExport {
    /**
     * 导出前处理视频。当前直接返回 [localPath]；调用方把 null 当失败（提示 aigc_export_failed）。
     * 来源和内容编号仍由调用方传入，落盘不再据此改文件。
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun prepareVideo(
        localPath: String,
        source: String,
        contentId: String? = null,
        contentType: String = "video",
    ): String? = localPath

    /**
     * 导出前处理图片：本地路径原样返回；http(s) 地址下载到 `<tmp>/<md5(url)>.<ext>`
     * （扩展名取自地址路径，默认 png）。下载失败返回 null。
     */
    suspend fun prepareImage(source: String): String? {
        if (!CdnDownload.isHttpUrl(source)) return source
        return try {
            CdnDownload.downloadStillFile(source, CdnDownload.mediaExt(source, "png"))
        } catch (e: Throwable) {
            DevLog.logger("prepareImage error: ${e.message}")
            null
        }
    }
}
