package com.sondo.app.features.think_line_mv

import com.sondo.app.base.AppHost
import com.sondo.app.base.BasePager
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.AigcMediaExport
import com.sondo.app.core.utils.CancelToken
import com.sondo.app.core.utils.CdnDownload
import com.sondo.app.core.utils.CommonUtils
import com.sondo.app.core.utils.DevLog
import com.sondo.app.service.MergeMode
import com.sondo.app.service.VideoMergerService
import com.sondo.app.ui.dialog.ImagePreviewItem
import com.sondo.app.ui.dialog.VideoPreviewDismissResult
import com.sondo.app.ui.dialog.VideoPreviewItem
import com.sondo.app.ui.dialog.showImagePreview
import com.sondo.app.ui.dialog.showVideoPreview
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * 信息流里的全屏预览（Dart `DialogImagePreview` / `DialogVideoPreview` 的业务部分）：
 * 下载（AIGC 导出 + 存相册）、分镜视频按 startSeconds 截取歌曲片段合成后播放、优先播放本地缓存。
 */
internal object ThinkLinePreview {
    private var isDownloading = false

    fun showImages(pager: BasePager, images: List<ImagePreviewItem>, initialIndex: Int) {
        pager.showImagePreview(images, initialIndex, onDownload = { item, _ ->
            if (item.isSuccess) downloadImage(pager, item.url)
        })
    }

    /**
     * @param musicUrl / [startEndScope] 有值时，带 startSeconds 的分镜会先与歌曲对应片段合成再播放
     */
    fun showVideos(
        pager: BasePager,
        videos: List<VideoPreviewItem>,
        initialIndex: Int,
        initialPositionMs: Long = 0L,
        musicUrl: String = "",
        startEndScope: String = "",
        showDownloadButton: Boolean = true,
        onResult: ((VideoPreviewDismissResult?) -> Unit)? = null,
    ) {
        val merged = HashMap<Int, String>()
        var mergeToken: CancelToken? = null
        val hasMusic = musicUrl.trim().isNotEmpty() && startEndScope.trim().isNotEmpty()
        val handle = pager.showVideoPreview(
            videos = videos,
            initialIndex = initialIndex,
            initialPositionMs = initialPositionMs,
            showDownloadButton = showDownloadButton,
            resolveSource = { index, item ->
                resolveSource(index, item, videos, hasMusic, musicUrl, startEndScope, merged) { token ->
                    mergeToken?.cancel()
                    mergeToken = token
                }
            },
            onDownload = { item, _ -> if (item.isSuccess) downloadVideo(pager, item.url) },
            onResult = { result ->
                mergeToken?.cancel()
                onResult?.invoke(result)
            },
        )
        if (handle == null) onResult?.invoke(null)
    }

    // ------------------------------------------------------------------ 合成 / 缓存

    private fun parseTimeMs(value: String): Long? {
        val parts = value.split(":").map { it.trim().toIntOrNull() }
        if (parts.any { it == null }) return null
        return when (parts.size) {
            2 -> (parts[0]!! * 60L + parts[1]!!) * 1000L
            3 -> (parts[0]!! * 3600L + parts[1]!! * 60L + parts[2]!!) * 1000L
            else -> null
        }
    }

    /** (起点 ms, 终点 ms?)。 */
    private fun musicScope(scope: String): Pair<Long, Long?> {
        if (!scope.contains(",")) return 0L to null
        val parts = scope.split(",")
        return (parseTimeMs(parts.first()) ?: 0L) to (if (parts.size > 1) parseTimeMs(parts[1]) else null)
    }

    private fun musicStartFor(item: VideoPreviewItem, scope: String): Long? {
        if (item.startSeconds < 0) return null
        return musicScope(scope).first + (item.startSeconds * 1000).roundToLong()
    }

    private fun musicEndFor(index: Int, start: Long, videos: List<VideoPreviewItem>, scope: String): Long? {
        for (i in index + 1 until videos.size) {
            val nextStart = musicStartFor(videos[i], scope)
            if (nextStart != null && nextStart > start) return nextStart
        }
        val end = musicScope(scope).second
        if (end != null && end > start) return end
        return null
    }

    private suspend fun resolveSource(
        index: Int,
        item: VideoPreviewItem,
        videos: List<VideoPreviewItem>,
        hasMusic: Boolean,
        musicUrl: String,
        scope: String,
        merged: HashMap<Int, String>,
        onToken: (CancelToken) -> Unit,
    ): String? {
        merged[index]?.let { return it }
        if (hasMusic && item.startSeconds >= 0) {
            val start = musicStartFor(item, scope)
            val end = if (start != null) musicEndFor(index, start, videos, scope) else null
            if (start != null && end != null) {
                val token = CancelToken()
                onToken(token)
                try {
                    val stamp = item.startSeconds.toString().replace('.', '_')
                    val path = VideoMergerService.merge(
                        videoUrl = item.url,
                        audioUrl = musicUrl,
                        outputFileName = "dialog_preview_${index}_${stamp}_merged.mp4",
                        mode = MergeMode.replace,
                        startMs = start,
                        endMs = end,
                        cancelToken = token,
                    )
                    if (!token.isCancelled) {
                        merged[index] = path
                        return path
                    }
                    return null
                } catch (e: Throwable) {
                    if (!token.isCancelled) DevLog.logger("DialogVideoPreview merge music failed: ${e.message}")
                }
            }
        }
        val url = item.url
        val isNetwork = url.startsWith("http://") || url.startsWith("https://")
        if (!isNetwork) return if (url.startsWith("file://")) url.removePrefix("file://") else url
        val cached = try {
            CdnDownload.cachedFilePathIfExists(url, "mp4")
        } catch (e: Throwable) {
            null
        }
        if (cached != null) return cached
        // 无缓存时先走网络流，后台补缓存
        AppHost.launch {
            try {
                CdnDownload.downloadWithCache(url, "mp4")
            } catch (e: Throwable) {
                DevLog.logger("DialogVideoPreview background cache failed: ${e.message}")
            }
        }
        return null
    }

    // ------------------------------------------------------------------ 下载

    private fun downloadImage(pager: BasePager, imageUrl: String) {
        if (isDownloading || imageUrl.isEmpty()) return
        isDownloading = true
        pager.launch {
            try {
                pager.showLoading()
                val marked = AigcMediaExport.prepareImage(imageUrl)
                if (marked == null) {
                    pager.toast("aigc_export_failed".tr)
                    pager.hideLoading()
                    return@launch
                }
                val ok = CommonUtils.saveImageToGallery(marked, albumName = "image_${Random.nextInt(1000000)}.png")
                pager.toast(if (ok) "download_success".tr else "download_failed".tr)
                pager.hideLoading()
            } catch (e: Throwable) {
                pager.hideLoading()
                DevLog.logger("downloadImage error: ${e.message}")
                pager.toast("download_failed".tr)
            } finally {
                isDownloading = false
            }
        }
    }

    private fun downloadVideo(pager: BasePager, videoUrl: String) {
        if (isDownloading || videoUrl.isEmpty()) return
        if (videoUrl.startsWith("assets")) {
            pager.toast("download_failed".tr)
            return
        }
        isDownloading = true
        pager.launch {
            try {
                pager.showLoading()
                val videoPath = when {
                    videoUrl.startsWith("http://") || videoUrl.startsWith("https://") -> CdnDownload.downloadWithCache(videoUrl, "mp4")
                    videoUrl.startsWith("file://") -> videoUrl.removePrefix("file://")
                    else -> videoUrl
                }
                val marked = AigcMediaExport.prepareVideo(localPath = videoPath, source = videoUrl)
                if (marked == null) {
                    pager.toast("aigc_export_failed".tr)
                    pager.hideLoading()
                    return@launch
                }
                val ok = CommonUtils.saveVideoToGallery(marked, albumName = "video_${Random.nextInt(1000000)}.mp4")
                pager.toast(if (ok) "download_success".tr else "download_failed".tr)
                pager.hideLoading()
            } catch (e: Throwable) {
                pager.hideLoading()
                DevLog.logger("downloadVideo error: ${e.message}")
                pager.toast("download_failed".tr)
            } finally {
                isDownloading = false
            }
        }
    }
}
