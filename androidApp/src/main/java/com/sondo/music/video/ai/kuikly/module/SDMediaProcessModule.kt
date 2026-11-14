package com.sondo.music.video.ai.kuikly.module

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.sondo.music.video.ai.kuikly.module.SDMediaKit.optLongOrNull
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * 媒体处理（参考工程用 ffmpeg-kit / just_waveform，这里全部用系统媒体框架，不引入 ffmpeg）。
 *
 * - probe              MediaExtractor + MediaMetadataRetriever；MP3 额外逐帧扫描得到精确时长
 * - remuxMp3WithXing   纯 Kotlin 重写 Xing 头，见 [SDMp3Xing]
 * - muxVideoWithAudio  MediaExtractor + MediaMuxer：视频流拷贝；音频是 AAC 且不需要裁剪起点时直接拷贝，
 *                      否则 MediaCodec 解码 → AAC-LC 重新编码；以较短的一路为准截断（-shortest）
 * - extractWaveform    MediaCodec 解码 → 按时间片取峰值
 * - trimAudio          解码 → AAC-LC → m4a
 * - videoFrame         MediaMetadataRetriever → JPEG
 *
 * 长任务（mux / waveform / trim）跑在后台线程，带 taskId，可 cancel；
 * 进度经常驻回调上报 {event:"progress", taskId, progress}，结束 {event:"done", taskId, ok, …}。
 */
class SDMediaProcessModule : KuiklyRenderBaseModule() {

    /** 本页面发起、尚未结束的任务；页面销毁时一并取消（回调已经送不回去了）。 */
    private val ownedTasks: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        val json = SDMediaKit.parse(params)
        val ctx = (activity ?: context)?.applicationContext
        when (method) {
            "probe" -> runShort(callback) { probe(ctx, json.optString("path", "")) }
            "remuxMp3WithXing" -> runShort(callback) { remuxMp3(ctx, json) }
            "videoFrame" -> runShort(callback) { videoFrame(ctx, json) }
            "muxVideoWithAudio" -> runTask(json, callback) { task -> muxVideoWithAudio(ctx, json, task) }
            "extractWaveform" -> runTask(json, callback) { task -> extractWaveform(ctx, json, task) }
            "trimAudio" -> runTask(json, callback) { task -> trimAudio(ctx, json, task) }
            "cancel" -> json.optString("taskId", "").takeIf { it.isNotEmpty() }?.let { TASKS[it]?.cancelled?.set(true) }
            else -> return super.call(method, params, callback)
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        for (id in ownedTasks) TASKS[id]?.cancelled?.set(true)
        ownedTasks.clear()
    }

    // ---------------------------------------------------------------------------------
    // 任务调度
    // ---------------------------------------------------------------------------------

    private class CancelledException : RuntimeException("cancelled")

    private class Task(val id: String, private val callback: KuiklyRenderCallback?) {
        val cancelled = AtomicBoolean(false)
        private var lastProgress = -1.0
        private var lastEmitAt = 0L

        fun checkCancelled() {
            if (cancelled.get()) throw CancelledException()
        }

        /** 进度上报做节流：变化 ≥1% 或距上次 ≥300ms 才发。 */
        fun progress(value: Double) {
            val p = value.coerceIn(0.0, 1.0)
            val now = SystemClock.elapsedRealtime()
            if (p <= lastProgress) return
            if (p - lastProgress < 0.01 && now - lastEmitAt < 300L && p < 1.0) return
            lastProgress = p
            lastEmitAt = now
            SDMediaKit.reply(callback, mapOf("event" to "progress", "taskId" to id, "progress" to p))
        }
    }

    private fun runShort(callback: KuiklyRenderCallback?, block: () -> Map<String, Any>) {
        SDMediaKit.io.execute {
            val result = try {
                block()
            } catch (e: Throwable) {
                Log.w(TAG, "media process failed", e)
                SDMediaKit.fail(SDMediaKit.errorText(e))
            }
            SDMediaKit.reply(callback, result)
        }
    }

    private fun runTask(json: JSONObject, callback: KuiklyRenderCallback?, block: (Task) -> Map<String, Any>) {
        val taskId = json.optString("taskId", "").ifEmpty { "sd_task_${SEQ.incrementAndGet()}" }
        val task = Task(taskId, callback)
        // 同步登记，保证紧跟着的 cancel 一定能找到任务
        TASKS[taskId] = task
        ownedTasks.add(taskId)
        SDMediaKit.codec.execute {
            val result = try {
                task.checkCancelled()
                block(task)
            } catch (e: CancelledException) {
                SDMediaKit.cancelled()
            } catch (e: Throwable) {
                if (task.cancelled.get()) {
                    SDMediaKit.cancelled()
                } else {
                    Log.w(TAG, "task $taskId failed", e)
                    SDMediaKit.fail(SDMediaKit.errorText(e))
                }
            }
            TASKS.remove(taskId, task)
            ownedTasks.remove(taskId)
            val done = LinkedHashMap<String, Any>(result)
            done["event"] = "done"
            done["taskId"] = taskId
            SDMediaKit.reply(callback, done)
        }
    }

    // ---------------------------------------------------------------------------------
    // probe
    // ---------------------------------------------------------------------------------

    private fun probe(ctx: Context?, rawPath: String): Map<String, Any> {
        val raw = rawPath.trim()
        if (raw.isEmpty()) return SDMediaKit.fail("invalid_path")
        val local = SDMediaKit.localPath(raw)
        if (local != null) {
            val file = File(local)
            if (!file.isFile || file.length() == 0L) return SDMediaKit.fail("file_not_found")
        }

        var durationMs = 0L
        var hasVideo = false
        var hasAudio = false
        var width = 0
        var height = 0
        var fps = 0.0
        var audioMime = ""
        var videoMime = ""

        val extractor = MediaExtractor()
        try {
            extractor.setSource(ctx, raw)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                val trackMs = format.longOr(MediaFormat.KEY_DURATION, 0L) / 1000L
                if (mime.startsWith("video/")) {
                    if (!hasVideo) {
                        hasVideo = true
                        videoMime = mime
                        width = format.intOr(MediaFormat.KEY_WIDTH, 0)
                        height = format.intOr(MediaFormat.KEY_HEIGHT, 0)
                        val rotation = format.intOr(KEY_ROTATION, 0)
                        if (rotation == 90 || rotation == 270) {
                            val t = width
                            width = height
                            height = t
                        }
                        fps = format.numberOr(MediaFormat.KEY_FRAME_RATE, 0.0)
                    }
                    durationMs = maxOf(durationMs, trackMs)
                } else if (mime.startsWith("audio/")) {
                    if (!hasAudio) audioMime = mime
                    hasAudio = true
                    durationMs = maxOf(durationMs, trackMs)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "probe: extractor failed: ${e.message}")
        } finally {
            try {
                extractor.release()
            } catch (ignored: Throwable) {
            }
        }

        var containerMime = ""
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setSource(ctx, raw)
            containerMime = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
            if (durationMs <= 0L) {
                durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }
            if (!hasVideo && mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") {
                hasVideo = true
                width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rotation == 90 || rotation == 270) {
                    val t = width
                    width = height
                    height = t
                }
            }
            if (!hasAudio && mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes") hasAudio = true
            if (hasVideo && fps <= 0.0 && durationMs > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val frames = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() ?: 0L
                if (frames > 0) fps = frames * 1000.0 / durationMs
            }
        } catch (e: Throwable) {
            Log.w(TAG, "probe: retriever failed: ${e.message}")
        } finally {
            try {
                mmr.release()
            } catch (ignored: Throwable) {
            }
        }

        // MP3：没有 Xing 头的 VBR 文件系统只能估算时长，这里逐帧数出精确值
        val ext = SDMediaKit.extensionOf(raw)
        if (!hasVideo && local != null && (audioMime == "audio/mpeg" || containerMime == "audio/mpeg" || ext == "mp3")) {
            SDMp3Xing.inspect(File(local))?.let {
                durationMs = it.durationMs
                hasAudio = true
                if (audioMime.isEmpty()) audioMime = "audio/mpeg"
            }
        }

        if (!hasVideo && !hasAudio) return SDMediaKit.fail("unsupported_media")
        val format = formatName(containerMime, if (hasVideo) videoMime else audioMime, ext)
        return SDMediaKit.ok(
            "durationMs" to durationMs,
            "hasVideo" to hasVideo,
            "hasAudio" to hasAudio,
            "width" to width.takeIf { hasVideo && it > 0 },
            "height" to height.takeIf { hasVideo && it > 0 },
            "fps" to fps.takeIf { hasVideo && it > 0.0 }?.let { (it * 100).roundToInt() / 100.0 },
            "format" to format.takeIf { it.isNotEmpty() },
        )
    }

    private fun formatName(containerMime: String, trackMime: String, ext: String): String {
        val mime = containerMime.ifEmpty { trackMime }.lowercase()
        return when {
            mime == "audio/mpeg" || mime == "audio/mp3" -> "mp3"
            mime == "video/mp4" -> if (ext == "mov") "mov" else "mp4"
            mime == "audio/mp4" || mime == "audio/x-m4a" || mime == "audio/mp4a-latm" -> if (ext == "aac") "aac" else "m4a"
            mime == "audio/aac" || mime == "audio/aac-adts" -> "aac"
            mime == "audio/x-wav" || mime == "audio/wav" || mime == "audio/raw" -> "wav"
            mime == "audio/flac" || mime == "audio/x-flac" -> "flac"
            mime == "audio/ogg" || mime == "application/ogg" -> "ogg"
            mime == "video/quicktime" -> "mov"
            mime == "video/webm" || mime == "audio/webm" -> "webm"
            mime == "video/x-matroska" -> "mkv"
            mime == "video/3gpp" -> "3gp"
            mime.isNotEmpty() -> mime
            else -> ext
        }
    }

    // ---------------------------------------------------------------------------------
    // remuxMp3WithXing / videoFrame
    // ---------------------------------------------------------------------------------

    private fun remuxMp3(ctx: Context?, json: JSONObject): Map<String, Any> {
        val src = SDMediaKit.localPath(json.optString("src", "")) ?: return SDMediaKit.fail("invalid_src")
        var dest = SDMediaKit.localPath(json.optString("dest", ""))
        if (dest == null) {
            if (ctx == null) return SDMediaKit.fail("invalid_dest")
            dest = File(SDMediaKit.tempDir(ctx, WORK_DIR), "mv_audio_${System.nanoTime() / 1000}.mp3").absolutePath
        }
        return try {
            val info = SDMp3Xing.remux(File(src), File(dest))
            SDMediaKit.ok("path" to dest, "durationMs" to info.durationMs, "frames" to info.frames, "vbr" to info.vbr)
        } catch (e: Throwable) {
            // 解析失败：调用方保留原文件
            File(dest).delete()
            SDMediaKit.fail(SDMediaKit.errorText(e))
        }
    }

    private fun videoFrame(ctx: Context?, json: JSONObject): Map<String, Any> {
        val video = json.optString("videoPath", "").trim()
        if (video.isEmpty()) return SDMediaKit.fail("invalid_video")
        SDMediaKit.localPath(video)?.let { if (!File(it).isFile) return SDMediaKit.fail("file_not_found") }
        var destPath = SDMediaKit.localPath(json.optString("dest", ""))
        if (destPath == null) {
            if (ctx == null) return SDMediaKit.fail("invalid_dest")
            destPath = File(SDMediaKit.tempDir(ctx, WORK_DIR), "frame_${System.nanoTime() / 1000}.jpg").absolutePath
        }
        val dest = File(destPath)
        val positionMs = (json.optLongOrNull("positionMs") ?: 0L).coerceAtLeast(0L)
        val mmr = MediaMetadataRetriever()
        var bitmap: Bitmap? = null
        try {
            mmr.setSource(ctx, video)
            val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val targetUs = (if (durationMs > 0) positionMs.coerceAtMost((durationMs - 50L).coerceAtLeast(0L)) else positionMs) * 1000L
            bitmap = mmr.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: mmr.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: mmr.frameAtTime
            val frame = bitmap ?: return SDMediaKit.fail("frame_not_available")
            SDMediaKit.ensureParent(dest)
            val tmp = File(dest.parentFile, dest.name + ".part")
            val ok = FileOutputStream(tmp).use { frame.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            if (!ok) {
                tmp.delete()
                return SDMediaKit.fail("encode_failed")
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return SDMediaKit.fail("write_failed")
            }
            return SDMediaKit.ok("path" to dest.absolutePath, "width" to frame.width, "height" to frame.height)
        } finally {
            bitmap?.recycle()
            try {
                mmr.release()
            } catch (ignored: Throwable) {
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // muxVideoWithAudio
    // ---------------------------------------------------------------------------------

    private fun muxVideoWithAudio(ctx: Context?, json: JSONObject, task: Task): Map<String, Any> {
        val videoPath = json.optString("videoPath", "").trim()
        val audioPath = json.optString("audioPath", "").trim()
        val destPath = SDMediaKit.localPath(json.optString("dest", "")) ?: return SDMediaKit.fail("invalid_dest")
        if (videoPath.isEmpty() || audioPath.isEmpty()) return SDMediaKit.fail("invalid_input")
        val startUs = (json.optLongOrNull("audioStartMs") ?: 0L).coerceAtLeast(0L) * 1000L
        val wantUs = (json.optLongOrNull("audioDurationMs") ?: 0L).coerceAtLeast(0L) * 1000L

        val dest = File(destPath)
        SDMediaKit.ensureParent(dest)
        val tmp = File(dest.parentFile, dest.name + ".part")
        tmp.delete()

        val videoEx = MediaExtractor()
        var audioEx: MediaExtractor? = null
        var reader: SDPcmReader? = null
        var transcoder: SDAudioTranscoder? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var success = false
        try {
            // ---- 视频轨 ----
            videoEx.setSource(ctx, videoPath)
            val vTrack = videoEx.firstTrack("video/")
            if (vTrack < 0) return SDMediaKit.fail("no_video_track")
            val vFormat = videoEx.getTrackFormat(vTrack)
            videoEx.selectTrack(vTrack)
            val videoDurUs = vFormat.longOr(MediaFormat.KEY_DURATION, 0L)

            // ---- 音频轨信息 ----
            val probeEx = MediaExtractor()
            val aMime: String
            val audioDurUs: Long
            try {
                probeEx.setSource(ctx, audioPath)
                val aTrack = probeEx.firstTrack("audio/")
                if (aTrack < 0) return SDMediaKit.fail("no_audio_track")
                val aFormat = probeEx.getTrackFormat(aTrack)
                aMime = aFormat.getString(MediaFormat.KEY_MIME).orEmpty()
                audioDurUs = aFormat.longOr(MediaFormat.KEY_DURATION, 0L)
            } finally {
                probeEx.release()
            }
            if (audioDurUs > 0 && startUs >= audioDurUs) return SDMediaKit.fail("audio_start_out_of_range")

            // ---- 输出时长：视频 / 可用音频 / 指定音频时长 三者取最短（-shortest）----
            var limitUs = Long.MAX_VALUE
            if (videoDurUs > 0) limitUs = minOf(limitUs, videoDurUs)
            if (audioDurUs > 0) limitUs = minOf(limitUs, audioDurUs - startUs)
            if (wantUs > 0) limitUs = minOf(limitUs, wantUs)
            val progressTotalUs = if (limitUs == Long.MAX_VALUE) 0L else limitUs

            val mux = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            val rotation = vFormat.intOr(KEY_ROTATION, 0)
            if (rotation != 0) mux.setOrientationHint(rotation)

            // 源音频已是 AAC 且从头开始：直接拷贝码流；否则解码后重新编码为 AAC-LC
            val passthrough = aMime == SD_MIME_AAC && startUs == 0L
            val vOut: Int
            val aOut: Int
            if (passthrough) {
                val ex = MediaExtractor()
                audioEx = ex
                ex.setSource(ctx, audioPath)
                val aTrack = ex.firstTrack("audio/")
                ex.selectTrack(aTrack)
                vOut = mux.addTrack(vFormat)
                aOut = mux.addTrack(ex.getTrackFormat(aTrack))
            } else {
                val endUs = if (limitUs == Long.MAX_VALUE) -1L else startUs + limitUs
                val r = SDPcmReader(ctx, audioPath, startUs, endUs)
                reader = r
                val t = SDAudioTranscoder(r)
                transcoder = t
                // 复用器启动前必须拿到编码器的输出格式（csd-0）
                while (t.outputFormat == null && !t.finished) {
                    task.checkCancelled()
                    t.step(null)
                }
                val aFormat = t.outputFormat ?: return SDMediaKit.fail("audio_encode_failed")
                vOut = mux.addTrack(vFormat)
                aOut = mux.addTrack(aFormat)
            }
            mux.start()
            muxerStarted = true

            // API 28 起可以按实际采样大小扩容；更低版本读不到大小，直接给足
            val minVideoBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) 1024 * 1024 else 8 * 1024 * 1024
            var vBuf = ByteBuffer.allocateDirect(maxOf(vFormat.intOr(MediaFormat.KEY_MAX_INPUT_SIZE, 0), minVideoBuffer))
            var aBuf: ByteBuffer? = if (passthrough) ByteBuffer.allocateDirect(256 * 1024) else null
            val info = MediaCodec.BufferInfo()
            var videoDone = false
            var audioDone = false
            var videoPtsUs = 0L
            var audioPtsUs = 0L
            var videoSamples = 0
            var audioSamples = 0
            val audioSink: (ByteBuffer, MediaCodec.BufferInfo) -> Unit = { buf, bi ->
                if (bi.presentationTimeUs < limitUs) {
                    mux.writeSampleData(aOut, buf, bi)
                    audioSamples++
                }
                audioPtsUs = bi.presentationTimeUs
            }

            while (!videoDone || !audioDone) {
                task.checkCancelled()
                // 交替写入：谁的时间戳落后就先写谁，保证文件内音视频交错
                val audioTurn = !audioDone && (videoDone || audioPtsUs <= videoPtsUs)
                if (audioTurn) {
                    if (passthrough) {
                        val ex = audioEx!!
                        var buf = aBuf!!
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && ex.sampleSize > buf.capacity()) {
                            buf = ByteBuffer.allocateDirect(ex.sampleSize.toInt() + 1024)
                            aBuf = buf
                        }
                        buf.clear()
                        val size = ex.readSampleData(buf, 0)
                        val time = ex.sampleTime
                        if (size < 0 || time >= limitUs) {
                            audioDone = true
                        } else {
                            info.set(0, size, time, sampleFlags(ex))
                            mux.writeSampleData(aOut, buf, info)
                            audioSamples++
                            audioPtsUs = time
                            ex.advance()
                        }
                    } else {
                        val t = transcoder!!
                        t.step(audioSink)
                        if (t.finished) audioDone = true
                    }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && videoEx.sampleSize > vBuf.capacity()) {
                        vBuf = ByteBuffer.allocateDirect(videoEx.sampleSize.toInt() + 1024)
                    }
                    vBuf.clear()
                    val size = videoEx.readSampleData(vBuf, 0)
                    val time = videoEx.sampleTime
                    if (size < 0 || time >= limitUs) {
                        videoDone = true
                    } else {
                        info.set(0, size, time, sampleFlags(videoEx))
                        mux.writeSampleData(vOut, vBuf, info)
                        videoSamples++
                        videoPtsUs = time
                        videoEx.advance()
                    }
                }
                if (progressTotalUs > 0) {
                    val done = when {
                        videoDone && audioDone -> progressTotalUs
                        videoDone -> audioPtsUs
                        audioDone -> videoPtsUs
                        else -> minOf(videoPtsUs, audioPtsUs)
                    }
                    task.progress((done.toDouble() / progressTotalUs).coerceAtMost(0.99))
                }
            }
            if (videoSamples == 0) return SDMediaKit.fail("no_video_samples")
            if (audioSamples == 0) return SDMediaKit.fail("no_audio_samples")

            mux.stop()
            muxerStarted = false
            mux.release()
            muxer = null
            // 与参考工程一致：输出必须大于 1KB 才算成功
            if (tmp.length() <= 1024L) return SDMediaKit.fail("output_too_small")
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) return SDMediaKit.fail("write_failed")
            success = true
            task.progress(1.0)
            return SDMediaKit.ok("path" to dest.absolutePath, "size" to dest.length())
        } finally {
            try {
                if (muxerStarted) muxer?.stop()
            } catch (ignored: Throwable) {
            }
            try {
                muxer?.release()
            } catch (ignored: Throwable) {
            }
            try {
                transcoder?.close()
            } catch (ignored: Throwable) {
            }
            try {
                reader?.close()
            } catch (ignored: Throwable) {
            }
            try {
                audioEx?.release()
            } catch (ignored: Throwable) {
            }
            try {
                videoEx.release()
            } catch (ignored: Throwable) {
            }
            if (!success) tmp.delete()
        }
    }

    private fun sampleFlags(extractor: MediaExtractor): Int =
        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0

    // ---------------------------------------------------------------------------------
    // trimAudio
    // ---------------------------------------------------------------------------------

    private fun trimAudio(ctx: Context?, json: JSONObject, task: Task): Map<String, Any> {
        val src = json.optString("src", "").trim()
        val destPath = SDMediaKit.localPath(json.optString("dest", "")) ?: return SDMediaKit.fail("invalid_dest")
        if (src.isEmpty()) return SDMediaKit.fail("invalid_src")
        val startUs = (json.optLongOrNull("startMs") ?: 0L).coerceAtLeast(0L) * 1000L
        val endMs = json.optLongOrNull("endMs") ?: 0L
        val endUs = if (endMs > 0) endMs * 1000L else -1L
        if (endUs in 1..startUs) return SDMediaKit.fail("invalid_range")

        val dest = File(destPath)
        SDMediaKit.ensureParent(dest)
        val tmp = File(dest.parentFile, dest.name + ".part")
        tmp.delete()

        var reader: SDPcmReader? = null
        var transcoder: SDAudioTranscoder? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var success = false
        try {
            val r = SDPcmReader(ctx, src, startUs, endUs)
            reader = r
            if (r.durationUs in 1..startUs) return SDMediaKit.fail("invalid_range")
            val totalUs = when {
                endUs > 0 && r.durationUs > 0 -> minOf(endUs, r.durationUs) - startUs
                endUs > 0 -> endUs - startUs
                r.durationUs > 0 -> r.durationUs - startUs
                else -> 0L
            }
            val t = SDAudioTranscoder(r)
            transcoder = t
            while (t.outputFormat == null && !t.finished) {
                task.checkCancelled()
                t.step(null)
            }
            val format = t.outputFormat ?: return SDMediaKit.fail("audio_encode_failed")
            val mux = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            val track = mux.addTrack(format)
            mux.start()
            muxerStarted = true
            var samples = 0
            val sink: (ByteBuffer, MediaCodec.BufferInfo) -> Unit = { buf, bi ->
                mux.writeSampleData(track, buf, bi)
                samples++
            }
            while (!t.finished) {
                task.checkCancelled()
                t.step(sink)
                if (totalUs > 0) task.progress((t.lastPtsUs.toDouble() / totalUs).coerceAtMost(0.99))
            }
            if (samples == 0) return SDMediaKit.fail("no_audio_samples")
            mux.stop()
            muxerStarted = false
            mux.release()
            muxer = null
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) return SDMediaKit.fail("write_failed")
            success = true
            task.progress(1.0)
            return SDMediaKit.ok("path" to dest.absolutePath, "durationMs" to t.lastPtsUs / 1000L, "size" to dest.length())
        } finally {
            try {
                if (muxerStarted) muxer?.stop()
            } catch (ignored: Throwable) {
            }
            try {
                muxer?.release()
            } catch (ignored: Throwable) {
            }
            try {
                transcoder?.close()
            } catch (ignored: Throwable) {
            }
            try {
                reader?.close()
            } catch (ignored: Throwable) {
            }
            if (!success) tmp.delete()
        }
    }

    // ---------------------------------------------------------------------------------
    // extractWaveform
    // ---------------------------------------------------------------------------------

    private fun extractWaveform(ctx: Context?, json: JSONObject, task: Task): Map<String, Any> {
        val audioPath = json.optString("audioPath", "").trim()
        if (audioPath.isEmpty()) return SDMediaKit.fail("invalid_input")
        val samplesPerSecond = json.optInt("samplesPerSecond", 20).coerceIn(1, 200)

        val reader = SDPcmReader(ctx, audioPath, 0L, -1L)
        try {
            reader.prime()
            val sampleRate = reader.sampleRate
            val channels = reader.channels
            val totalUs = reader.durationUs
            // 每个时间片的帧数用浮点累计，避免采样率不能整除时越走越偏
            val framesPerSlice = sampleRate.toDouble() / samplesPerSecond
            var peaks = FloatArray(4096)
            var count = 0
            var sliceIndex = 0L
            var nextBoundary = framesPerSlice
            var framesSeen = 0L
            var current = 0

            while (true) {
                task.checkCancelled()
                val chunk = reader.next() ?: break
                val data = chunk.data
                var i = 0
                for (f in 0 until chunk.frames) {
                    for (c in 0 until channels) {
                        val v = data[i++].toInt()
                        val abs = if (v < 0) -v else v
                        if (abs > current) current = abs
                    }
                    framesSeen++
                    if (framesSeen >= nextBoundary) {
                        if (count == peaks.size) peaks = peaks.copyOf(peaks.size * 2)
                        peaks[count++] = (current / 32768f).coerceAtMost(1f)
                        current = 0
                        sliceIndex++
                        nextBoundary = (sliceIndex + 1) * framesPerSlice
                    }
                }
                if (totalUs > 0) task.progress((chunk.positionUs.toDouble() / totalUs).coerceAtMost(0.99))
            }
            // 末尾不足一个时间片的部分也算一项
            if (framesSeen > sliceIndex * framesPerSlice + 0.5) {
                if (count == peaks.size) peaks = peaks.copyOf(peaks.size + 1)
                peaks[count++] = (current / 32768f).coerceAtMost(1f)
            }
            if (framesSeen == 0L) return SDMediaKit.fail("no_audio_samples")

            val list = ArrayList<Double>(count)
            for (k in 0 until count) list.add((peaks[k] * 10000f).roundToInt() / 10000.0)
            task.progress(1.0)
            return SDMediaKit.ok(
                "durationMs" to framesSeen * 1000L / sampleRate,
                "peaks" to list,
                "samplesPerSecond" to samplesPerSecond,
            )
        } finally {
            reader.close()
        }
    }

    // ---------------------------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------------------------

    private fun MediaMetadataRetriever.setSource(ctx: Context?, raw: String) {
        val local = SDMediaKit.localPath(raw)
        when {
            local != null -> setDataSource(local)
            raw.startsWith("content://") && ctx != null -> setDataSource(ctx, Uri.parse(raw))
            SDMediaKit.isHttp(raw) -> setDataSource(raw, HashMap<String, String>())
            else -> setDataSource(raw)
        }
    }

    /** MediaFormat 里的数值可能是 Int 也可能是 Float（帧率）。 */
    private fun MediaFormat.numberOr(key: String, default: Double): Double {
        if (!containsKey(key)) return default
        return try {
            getInteger(key).toDouble()
        } catch (e: Throwable) {
            try {
                getFloat(key).toDouble()
            } catch (e2: Throwable) {
                default
            }
        }
    }

    companion object {
        private const val TAG = "SDMediaProcess"
        private const val WORK_DIR = "sd_media"
        private const val KEY_ROTATION = "rotation-degrees"
        private val SEQ = AtomicInteger()

        /** 进程内全部进行中的任务：cancel 可以来自任意页面的模块实例。 */
        private val TASKS = ConcurrentHashMap<String, Task>()
    }
}
