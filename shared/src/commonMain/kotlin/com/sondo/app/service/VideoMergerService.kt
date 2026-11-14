package com.sondo.app.service

import com.sondo.app.core.utils.CancelToken
import com.sondo.app.core.utils.CancelledException
import com.sondo.app.core.utils.CdnDownload
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.HostNative
import com.sondo.app.core.utils.ProgressCallback
import com.sondo.app.core.utils.onHost
import com.sondo.app.core.utils.Uuid
import com.sondo.app.nativeapi.errorMsg
import com.sondo.app.nativeapi.ok
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

enum class MergeMode {
    /** 替换视频原声音 */
    replace,

    /** 混音（参考工程没有调用方；原生混流只支持替换，传入会直接失败） */
    mix,
}

class VideoMergeException(message: String) : Exception(message)

/**
 * 端上音视频处理（对应 Flutter 侧 VideoMergerService 的在用路径）：
 * 把歌曲片段混到单个分镜视频上用于预览（[merge]），以及带缓存的媒体下载。
 * 处理过程在宿主页面上执行，调用方页面关闭不影响；失败以异常抛给调用方（页面里必须 try/catch）。
 *
 * 未移植：整片 MV 端上合成 `storyLineMergeVideo`（含归一化 / 黑场 / 字幕烧录 / logo 叠加 / 抽封面）、
 * `compressVideo`、`stopFFmpeg` 以及只服务于它们的进度字段 —— 参考工程里没有调用方，成片由服务端合成。
 */
object VideoMergerService {
    private var inited = false

    fun init() {
        inited = true
    }

    /**
     * 合成网络音视频：下载视频（mp4）与音频（mp3，均走缓存）后，视频流拷贝 + 音频转 AAC 混流（-shortest）。
     *
     * @param progressCallback step 为 video_download / audio_download（progress 0~1）/ merge_start（null）/ merge_complete（1.0）
     * @param outputFileName 输出文件名，落在临时目录；同名会被覆盖
     * @param startMs / endMs 音频截取区间，二者都给才生效；与参考工程一致只保留整秒
     * @return 输出文件路径
     * @throws VideoMergeException 下载 / 合成失败、输出缺失或不足 1KB；[CancelledException] 被取消（合成开始后不可取消）
     */
    suspend fun merge(
        videoUrl: String,
        audioUrl: String,
        outputFileName: String,
        mode: MergeMode = MergeMode.replace,
        startMs: Long? = null,
        endMs: Long? = null,
        progressCallback: ProgressCallback? = null,
        maxRetry: Int = 5,
        cancelToken: CancelToken? = null,
    ): String = onHost {
        mergeImpl(videoUrl, audioUrl, outputFileName, mode, startMs, endMs, progressCallback, maxRetry, cancelToken)
    }

    private suspend fun mergeImpl(
        videoUrl: String,
        audioUrl: String,
        outputFileName: String,
        mode: MergeMode,
        startMs: Long?,
        endMs: Long?,
        progressCallback: ProgressCallback?,
        maxRetry: Int,
        cancelToken: CancelToken?,
    ): String {
        checkCancelled(cancelToken)

        // 1 下载视频
        val videoPath = download(videoUrl, "mp4", maxRetry, cancelToken) { p ->
            progressCallback?.invoke("video_download", p)
        }
        checkCancelled(cancelToken)

        // 2 下载音频
        val audioPath = download(audioUrl, "mp3", maxRetry, cancelToken) { p ->
            progressCallback?.invoke("audio_download", p)
        }
        checkCancelled(cancelToken)

        // 3 输出路径
        val outputPath = "${HostNative.tempDir()}/$outputFileName"

        if (mode == MergeMode.mix) throw VideoMergeException("mix mode is not supported")

        val params = JSONObject()
            .put("videoPath", videoPath)
            .put("audioPath", audioPath)
            .put("dest", outputPath)
            .put("taskId", Uuid.v4())
        if (startMs != null && endMs != null) {
            // `-ss HH:MM:SS -t <整秒>`：毫秒部分丢弃
            val startSeconds = startMs / 1000L
            val durationSeconds = (endMs - startMs) / 1000L
            params.put("audioStartMs", startSeconds * 1000L)
            params.put("audioDurationMs", durationSeconds * 1000L)
        }

        progressCallback?.invoke("merge_start", null)
        checkCancelled(cancelToken)

        val media = HostNative.mediaProcess
        val done: JSONObject = suspendCoroutine { cont ->
            var finished = false
            var ref: String? = null
            ref = media.muxVideoWithAudio(params) { e ->
                if (e.optString("event", "") == "done" && !finished) {
                    finished = true
                    // 常驻回调用完即注销
                    ref?.let { media.removeCallback(it) }
                    cont.resume(e)
                }
            }
        }
        checkCancelled(cancelToken)

        if (!done.ok()) {
            DevLog.logger("merge failed: ${done.errorMsg()}")
            throw VideoMergeException("merge failed: ${done.errorMsg()}")
        }
        val out = done.optString("path", outputPath).ifEmpty { outputPath }
        if (HostNative.file.size(out) <= 1024L) {
            throw VideoMergeException("merge produced empty or invalid output")
        }
        progressCallback?.invoke("merge_complete", 1.0)
        return out
    }

    /** 下载并缓存 mp3，返回本地路径。 */
    suspend fun downloadAudio(url: String, cancelToken: CancelToken? = null): String =
        onHost { download(url, "mp3", 5, cancelToken, null) }

    /** 并行下载并缓存全部 mp4（不限并发，与参考工程一致）；任一失败则整体失败。 */
    suspend fun downloadAllVideos(urls: List<String>): List<String> = onHost {
        val outcomes = Svc.awaitAll(urls.map { url -> suspend { download(url, "mp4", 5, null, null) } })
        outcomes.map { it.getOrThrow() }
    }

    private suspend fun download(
        url: String,
        ext: String,
        maxRetry: Int,
        cancelToken: CancelToken?,
        onProgress: ((Double?) -> Unit)?,
    ): String {
        try {
            return CdnDownload.downloadWithCache(
                url, ext, maxRetry,
                progressCallback = if (onProgress == null) null else { _, p -> onProgress(p) },
                cancelToken = cancelToken,
            )
        } catch (e: CancelledException) {
            throw e
        } catch (e: Throwable) {
            throw VideoMergeException(e.message ?: "download failed")
        }
    }

    private fun checkCancelled(token: CancelToken?) {
        if (token?.isCancelled == true) throw CancelledException("操作已取消")
    }
}
