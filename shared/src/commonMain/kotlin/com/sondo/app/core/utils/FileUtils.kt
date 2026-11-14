package com.sondo.app.core.utils

import com.sondo.app.nativeapi.SDAppModule
import com.sondo.app.nativeapi.SDFileModule
import com.sondo.app.nativeapi.SDMediaPickerModule
import com.sondo.app.nativeapi.nativeModule
import com.sondo.app.nativeapi.ok

/** 文件工具（对应 Flutter 侧 FileUtils；路径均为绝对路径）。 */
object FileUtils {
    private var tempDirCache = ""
    private var documentsDirCache = ""

    private val file: SDFileModule get() = nativeModule(SDFileModule.MODULE_NAME)

    /** 临时目录（不带结尾斜杠）。 */
    fun tempDir(): String {
        if (tempDirCache.isEmpty()) loadPaths()
        return tempDirCache
    }

    /** 文档目录（不带结尾斜杠）。 */
    fun documentsDir(): String {
        if (documentsDirCache.isEmpty()) loadPaths()
        return documentsDirCache
    }

    private fun loadPaths() {
        val p = nativeModule<SDAppModule>(SDAppModule.MODULE_NAME).getPaths()
        tempDirCache = p.optString("tempDir", "").trimEnd('/')
        if (tempDirCache.isEmpty()) tempDirCache = p.optString("cacheDir", "").trimEnd('/')
        documentsDirCache = p.optString("documentsDir", "").trimEnd('/')
    }

    /** 文件大小（字节）；不存在为 -1。 */
    suspend fun getFileSize(path: String): Long = file.size(path)

    /** 路径里的扩展名（小写，不带点）；没有返回空串。 */
    fun extensionOf(path: String): String {
        val clean = path.substringBefore('?').substringBefore('#')
        val name = clean.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    /**
     * 获取文件扩展名：先看文件名，再按文件头嗅探（文件名无扩展名或不在 [exts] 内时）。
     * 嗅探结果也不在 [exts] 内时返回嗅探到的扩展名（可能为空串），与参考工程一致。
     */
    suspend fun getFileExtension(path: String, exts: List<String>): String {
        var ext = extensionOf(path)
        if (exts.contains(ext)) return ext
        try {
            val head = Base64.decode(file.readHead(path, 12))
            ext = when (AudioSniffer.detectFromBytes(head)) {
                AudioKind.mp3 -> "mp3"
                AudioKind.m4a -> "m4a"
                AudioKind.aac -> "aac"
                AudioKind.wav -> "wav"
                AudioKind.flac -> "flac"
                else -> ""
            }
            if (exts.contains(ext)) return ext
        } catch (e: Throwable) {
            DevLog.logger("getFileExtension3 error: $e")
        }
        return ext
    }

    /** 文件名（不包含扩展名）。 */
    fun getFileName(filePath: String): String {
        val name = filePath.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    /** 临时目录下的文件路径（不创建文件）。 */
    fun createTempFile(fileName: String): String = "${tempDir()}/$fileName"

    /** 压缩图片为 JPEG；失败返回 null。 */
    suspend fun compressImage(path: String, maxWidth: Int = 1920, maxHeight: Int = 1080, quality: Int = 85): String? {
        val picker: SDMediaPickerModule = nativeModule(SDMediaPickerModule.MODULE_NAME)
        val res = picker.compressImage(path, quality, maxOf(maxWidth, maxHeight))
        if (!res.ok()) {
            DevLog.logger("压缩图片失败: ${res.optString("error")}")
            return null
        }
        return res.optString("path", "").ifEmpty { null }
    }

    suspend fun exists(path: String): Boolean = file.exists(path)

    suspend fun deleteFile(path: String) {
        if (file.exists(path)) file.delete(path)
    }

    /** 将源文件拷贝到 upload 专用临时路径，返回新路径；失败返回 null。 */
    suspend fun copyToUploadTemp(source: String, ext: String): String? {
        val dest = "${tempDir()}/audio_upload_${nowMs() * 1000 + (0..999).random()}.$ext"
        return if (file.copy(source, dest)) dest else null
    }
}
