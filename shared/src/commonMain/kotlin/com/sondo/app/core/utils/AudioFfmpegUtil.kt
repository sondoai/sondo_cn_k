package com.sondo.app.core.utils

import com.sondo.app.nativeapi.ok

/** MV 音频预处理：MP3 重封装并写入 Xing 头，其他格式直接放行。 */
object AudioFfmpegUtil {
    /**
     * MP3 重封装失败、源文件无效或不是 MP3 时，均返回原路径。
     * 成功时返回 `<tmp>/mv_audio_<stamp>.mp3`（对应 `ffmpeg -map 0:a:0 -map_metadata 0 -c:a copy -write_xing 1`）。
     */
    suspend fun remuxMp3IfNeeded(sourcePath: String): String = onHost { remuxMp3IfNeededImpl(sourcePath) }

    private suspend fun remuxMp3IfNeededImpl(sourcePath: String): String {
        var outputPath: String? = null
        try {
            val file = HostNative.file
            if (file.size(sourcePath) <= 0L) return sourcePath
            if (AudioSniffer.detectFromFile(sourcePath) != AudioKind.mp3) return sourcePath

            val dest = "${HostNative.tempDir()}/mv_audio_${HostNative.uniqueStamp()}.mp3"
            outputPath = dest
            val res = HostNative.mediaProcess.remuxMp3WithXing(sourcePath, dest)
            val out = res.optString("path", dest).ifEmpty { dest }
            if (res.ok() && file.size(out) > 0L) return out
            DevLog.logger("MP3 重封装失败: ${res.optString("error", "")}")
        } catch (e: Throwable) {
            DevLog.logger("MP3 重封装异常 ${e.message}")
        }
        deleteQuietly(outputPath)
        return sourcePath
    }

    private suspend fun deleteQuietly(path: String?) {
        if (path == null) return
        try {
            if (HostNative.file.exists(path)) HostNative.file.delete(path)
        } catch (e: Throwable) {
            // ignore
        }
    }
}
