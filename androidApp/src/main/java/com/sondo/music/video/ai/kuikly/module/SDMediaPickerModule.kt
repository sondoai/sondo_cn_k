package com.sondo.music.video.ai.kuikly.module

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ext.SdkExtensions
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.sondo.music.video.ai.kuikly.module.SDMediaKit.optStringOrNull
import com.sondo.music.video.ai.kuikly.module.SDMediaKit.toStringList
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * 相册 / 相机 / 文件选择与保存。
 *
 * - pickImage / pickImages：系统照片选择器（Android 13+ 或带扩展的 11+）→ ACTION_PICK → 文件选择器逐级回退；
 *   相机走 ACTION_IMAGE_CAPTURE + FileProvider。结果统一转成 JPEG（EXIF 摆正、可限制最长边）。
 * - pickAudioFile / pickVideo：选中后拷贝到 App 缓存目录，返回真实文件路径。
 * - saveFileAs：系统「存储为」（ACTION_CREATE_DOCUMENT）。
 * - saveToGallery：Android 10+ 用 MediaStore（无需权限），以下写公共目录（需要 WRITE_EXTERNAL_STORAGE）。
 *
 * Activity 结果通过 ActivityResultRegistry 以唯一 key 临时注册，不依赖宿主 Activity 的 onActivityResult 分发。
 */
class SDMediaPickerModule : KuiklyRenderBaseModule() {

    private val launchers = HashSet<ActivityResultLauncher<*>>()

    @Volatile
    private var destroyed = false

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        val json = SDMediaKit.parse(params)
        when (method) {
            "pickImage" -> onMain(callback) { pickImage(json, callback) }
            "pickImages" -> onMain(callback) { pickImages(json, callback) }
            "pickAudioFile" -> onMain(callback) { pickAudioFile(json, callback) }
            "pickVideo" -> onMain(callback) { pickVideo(callback) }
            "saveFileAs" -> onMain(callback) { saveFileAs(json, callback) }
            "saveToGallery" -> onMain(callback) { saveToGallery(json, callback) }
            "getImageSize" -> onIo(callback) { getImageSize(json) }
            "compressImage" -> onIo(callback) { compressImage(json) }
            else -> return super.call(method, params, callback)
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyed = true
        val pending = synchronized(launchers) { ArrayList(launchers).also { launchers.clear() } }
        for (l in pending) {
            try {
                l.unregister()
            } catch (e: Throwable) {
                // ignore
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // 图片
    // ---------------------------------------------------------------------------------

    private fun pickImage(json: JSONObject, callback: KuiklyRenderCallback?) {
        val host = hostActivity() ?: return SDMediaKit.reply(callback, SDMediaKit.fail("no_activity"))
        val quality = json.optInt("quality", 100).coerceIn(1, 100)
        val maxSide = json.optInt("maxSide", 0).coerceAtLeast(0)
        if (json.optString("source", "gallery") == "camera") {
            pickFromCamera(host, quality, maxSide, callback)
            return
        }
        val launched = launchFirst(host, galleryIntents(host, "image/*", 1)) { result ->
            val uri = resultUris(result).firstOrNull()
            if (uri == null) {
                SDMediaKit.reply(callback, SDMediaKit.cancelled())
                return@launchFirst
            }
            SDMediaKit.io.execute {
                val converted = convertPicked(host, uri, quality, maxSide)
                SDMediaKit.reply(callback, converted.result?.let { okWith(it.toMap()) } ?: converted.error)
            }
        }
        if (!launched) SDMediaKit.reply(callback, SDMediaKit.fail("picker_unavailable"))
    }

    private fun pickImages(json: JSONObject, callback: KuiklyRenderCallback?) {
        val host = hostActivity() ?: return SDMediaKit.reply(callback, SDMediaKit.fail("no_activity"))
        val maxCount = json.optInt("maxCount", 9).coerceAtLeast(1)
        val quality = json.optInt("quality", 100).coerceIn(1, 100)
        val maxSide = json.optInt("maxSide", 0).coerceAtLeast(0)
        val launched = launchFirst(host, galleryIntents(host, "image/*", maxCount)) { result ->
            val uris = resultUris(result).take(maxCount)
            if (uris.isEmpty()) {
                SDMediaKit.reply(callback, SDMediaKit.cancelled())
                return@launchFirst
            }
            SDMediaKit.io.execute {
                val items = ArrayList<Map<String, Any>>()
                var firstError: Map<String, Any>? = null
                for (uri in uris) {
                    val converted = convertPicked(host, uri, quality, maxSide)
                    val r = converted.result
                    if (r != null) items.add(r.toMap()) else if (firstError == null) firstError = converted.error
                }
                if (items.isEmpty()) {
                    SDMediaKit.reply(callback, firstError ?: SDMediaKit.fail("decode_failed"))
                } else {
                    SDMediaKit.reply(callback, SDMediaKit.ok("items" to items))
                }
            }
        }
        if (!launched) SDMediaKit.reply(callback, SDMediaKit.fail("picker_unavailable"))
    }

    private class Converted(val result: SDImageKit.JpegResult?, val error: Map<String, Any>)

    private fun okWith(fields: Map<String, Any>): Map<String, Any> {
        val map = LinkedHashMap<String, Any>()
        map["ok"] = true
        map.putAll(fields)
        return map
    }

    /** 选中的图片 → JPEG（阻塞，IO 线程）。 */
    private fun convertPicked(context: Context, uri: Uri, quality: Int, maxSide: Int): Converted {
        return try {
            val out = SDImageKit.newOutputFile(context, "img", "jpg")
            Converted(SDImageKit.toJpeg(SDImageKit.opener(context, uri), quality, maxSide, out), emptyMap())
        } catch (e: SecurityException) {
            Converted(null, SDMediaKit.fail(ERR_PHOTO_DENIED))
        } catch (e: Throwable) {
            Log.w(TAG, "convert image failed", e)
            Converted(null, SDMediaKit.fail(SDMediaKit.errorText(e)))
        }
    }

    private fun pickFromCamera(host: ComponentActivity, quality: Int, maxSide: Int, callback: KuiklyRenderCallback?) {
        // 清单里声明了 CAMERA 时（三方 SDK 可能带入），拍照 Intent 也要求已授权
        if (declaresPermission(host, Manifest.permission.CAMERA) && !isGranted(host, Manifest.permission.CAMERA)) {
            val ok = launch(host, ActivityResultContracts.RequestPermission(), Manifest.permission.CAMERA) { granted ->
                if (granted) openCamera(host, quality, maxSide, callback)
                else SDMediaKit.reply(callback, SDMediaKit.fail(ERR_CAMERA_DENIED))
            }
            if (!ok) SDMediaKit.reply(callback, SDMediaKit.fail(ERR_CAMERA_DENIED))
            return
        }
        openCamera(host, quality, maxSide, callback)
    }

    private fun openCamera(host: ComponentActivity, quality: Int, maxSide: Int, callback: KuiklyRenderCallback?) {
        val raw = SDImageKit.newOutputFile(host, "camera", "jpg")
        val uri = SDImageKit.contentUriFor(host, raw)
        if (uri == null) {
            SDMediaKit.reply(callback, SDMediaKit.fail("file_provider_missing"))
            return
        }
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("", uri)
            // 后置摄像头（各厂商相机识别的 extra 不同，都带上）
            putExtra("android.intent.extras.CAMERA_FACING", 0)
            putExtra("android.intent.extras.LENS_FACING_BACK", 1)
            putExtra("android.intent.extra.USE_FRONT_CAMERA", false)
        }
        val launched = try {
            launch(host, ActivityResultContracts.StartActivityForResult(), intent) { result ->
                if (result.resultCode != Activity.RESULT_OK || !raw.exists() || raw.length() == 0L) {
                    raw.delete()
                    SDMediaKit.reply(callback, SDMediaKit.cancelled())
                    return@launch
                }
                SDMediaKit.io.execute {
                    try {
                        val out = SDImageKit.newOutputFile(host, "img", "jpg")
                        val r = SDImageKit.toJpeg(SDImageKit.opener(raw), quality, maxSide, out)
                        SDMediaKit.reply(callback, okWith(r.toMap()))
                    } catch (e: Throwable) {
                        Log.w(TAG, "process camera image failed", e)
                        SDMediaKit.reply(callback, SDMediaKit.fail(SDMediaKit.errorText(e)))
                    } finally {
                        raw.delete()
                    }
                }
            }
        } catch (e: SecurityException) {
            raw.delete()
            SDMediaKit.reply(callback, SDMediaKit.fail(ERR_CAMERA_DENIED))
            return
        }
        if (!launched) {
            raw.delete()
            SDMediaKit.reply(callback, SDMediaKit.fail("camera_unavailable"))
        }
    }

    private fun getImageSize(json: JSONObject): Map<String, Any> {
        val ctx = appContext() ?: return SDMediaKit.fail("no_context")
        val open = openerFor(ctx, json.optString("path", "")) ?: return SDMediaKit.fail("file_not_found")
        val size = SDImageKit.imageSize(open) ?: return SDMediaKit.fail("decode_failed")
        return SDMediaKit.ok("width" to size.first, "height" to size.second)
    }

    private fun compressImage(json: JSONObject): Map<String, Any> {
        val ctx = appContext() ?: return SDMediaKit.fail("no_context")
        val open = openerFor(ctx, json.optString("path", "")) ?: return SDMediaKit.fail("file_not_found")
        val quality = json.optInt("quality", 90).coerceIn(1, 100)
        val maxSide = json.optInt("maxSide", 0).coerceAtLeast(0)
        return try {
            val r = SDImageKit.toJpeg(open, quality, maxSide, SDImageKit.newOutputFile(ctx, "img", "jpg"))
            okWith(r.toMap())
        } catch (e: Throwable) {
            SDMediaKit.fail(SDMediaKit.errorText(e))
        }
    }

    private fun openerFor(context: Context, raw: String): (() -> InputStream?)? {
        val local = SDMediaKit.localPath(raw)
        if (local != null) {
            val file = File(local)
            return if (file.isFile && file.length() > 0) SDImageKit.opener(file) else null
        }
        if (raw.startsWith("content://")) return SDImageKit.opener(context, Uri.parse(raw))
        return null
    }

    // ---------------------------------------------------------------------------------
    // 音频文件 / 视频
    // ---------------------------------------------------------------------------------

    private fun pickAudioFile(json: JSONObject, callback: KuiklyRenderCallback?) {
        val host = hostActivity() ?: return SDMediaKit.reply(callback, SDMediaKit.fail("no_activity"))
        val extensions = json.optJSONArray("extensions").toStringList()
            .map { it.trim().trimStart('.').lowercase() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf("mp3", "m4a", "aac", "wav", "flac") }
        val mimes = LinkedHashSet<String>()
        for (ext in extensions) mimes.addAll(mimeTypesFor(ext))
        val baseType = if (mimes.isNotEmpty() && mimes.all { it.startsWith("audio/") }) "audio/*" else "*/*"

        fun build(action: String) = Intent(action).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = baseType
            if (mimes.isNotEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        }

        val intents = listOf(build(Intent.ACTION_OPEN_DOCUMENT), build(Intent.ACTION_GET_CONTENT))
        val launched = launchFirst(host, intents) { result ->
            val uri = resultUris(result).firstOrNull()
            if (uri == null) {
                SDMediaKit.reply(callback, SDMediaKit.cancelled())
                return@launchFirst
            }
            SDMediaKit.io.execute {
                try {
                    val name = displayName(host, uri)
                    var ext = SDMediaKit.extensionOf(name)
                    if (ext.isEmpty()) ext = extensionFromMime(host.contentResolver.getType(uri)).orEmpty()
                    val out = SDImageKit.newOutputFile(host, "audio", ext.ifEmpty { "bin" })
                    val size = copyToFile(host, uri, out)
                    if (size <= 0L) {
                        out.delete()
                        SDMediaKit.reply(callback, SDMediaKit.fail("empty_file"))
                    } else {
                        SDMediaKit.reply(
                            callback,
                            SDMediaKit.ok(
                                "path" to out.absolutePath,
                                "name" to name.ifEmpty { out.name },
                                "size" to size,
                                "extension" to ext,
                            )
                        )
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "copy audio failed", e)
                    SDMediaKit.reply(callback, SDMediaKit.fail(SDMediaKit.errorText(e)))
                }
            }
        }
        if (!launched) SDMediaKit.reply(callback, SDMediaKit.fail("picker_unavailable"))
    }

    private fun pickVideo(callback: KuiklyRenderCallback?) {
        val host = hostActivity() ?: return SDMediaKit.reply(callback, SDMediaKit.fail("no_activity"))
        val launched = launchFirst(host, galleryIntents(host, "video/*", 1)) { result ->
            val uri = resultUris(result).firstOrNull()
            if (uri == null) {
                SDMediaKit.reply(callback, SDMediaKit.cancelled())
                return@launchFirst
            }
            SDMediaKit.io.execute {
                try {
                    var ext = SDMediaKit.extensionOf(displayName(host, uri))
                    if (ext.isEmpty()) ext = extensionFromMime(host.contentResolver.getType(uri)) ?: "mp4"
                    val out = SDImageKit.newOutputFile(host, "video", ext)
                    val size = copyToFile(host, uri, out)
                    if (size <= 0L) {
                        out.delete()
                        SDMediaKit.reply(callback, SDMediaKit.fail("empty_file"))
                        return@execute
                    }
                    var durationMs = 0L
                    var width = 0
                    var height = 0
                    val mmr = MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(out.absolutePath)
                        durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                        if (rotation == 90 || rotation == 270) {
                            val t = width
                            width = height
                            height = t
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "read video metadata failed", e)
                    } finally {
                        try {
                            mmr.release()
                        } catch (e: Throwable) {
                            // ignore
                        }
                    }
                    SDMediaKit.reply(
                        callback,
                        SDMediaKit.ok(
                            "path" to out.absolutePath,
                            "durationMs" to durationMs,
                            "width" to width,
                            "height" to height,
                            "size" to size,
                        )
                    )
                } catch (e: SecurityException) {
                    SDMediaKit.reply(callback, SDMediaKit.fail(ERR_PHOTO_DENIED))
                } catch (e: Throwable) {
                    Log.w(TAG, "copy video failed", e)
                    SDMediaKit.reply(callback, SDMediaKit.fail(SDMediaKit.errorText(e)))
                }
            }
        }
        if (!launched) SDMediaKit.reply(callback, SDMediaKit.fail("picker_unavailable"))
    }

    // ---------------------------------------------------------------------------------
    // 保存
    // ---------------------------------------------------------------------------------

    private fun saveFileAs(json: JSONObject, callback: KuiklyRenderCallback?) {
        val host = hostActivity() ?: return SDMediaKit.reply(callback, SDMediaKit.fail("no_activity"))
        val src = SDMediaKit.localPath(json.optString("srcPath", ""))?.let { File(it) }
        if (src == null || !src.isFile) {
            SDMediaKit.reply(callback, SDMediaKit.fail("file_not_found"))
            return
        }
        val fileName = json.optStringOrNull("fileName") ?: src.name
        val mime = json.optStringOrNull("mime")
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(SDMediaKit.extensionOf(fileName))
            ?: "application/octet-stream"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        val launched = launchFirst(host, listOf(intent)) { result ->
            val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
            if (uri == null) {
                SDMediaKit.reply(callback, SDMediaKit.cancelled())
                return@launchFirst
            }
            SDMediaKit.io.execute {
                try {
                    val os = host.contentResolver.openOutputStream(uri, "w") ?: throw IOException("open_output_failed")
                    os.use { FileInputStream(src).use { input -> input.copyTo(it, COPY_BUFFER) } }
                    SDMediaKit.reply(callback, SDMediaKit.ok("savedPath" to uri.toString(), "name" to displayName(host, uri)))
                } catch (e: Throwable) {
                    Log.w(TAG, "saveFileAs failed", e)
                    // 写失败时删掉系统已经创建的空文档
                    try {
                        DocumentsContract.deleteDocument(host.contentResolver, uri)
                    } catch (ignored: Throwable) {
                    }
                    SDMediaKit.reply(callback, SDMediaKit.fail(SDMediaKit.errorText(e)))
                }
            }
        }
        if (!launched) SDMediaKit.reply(callback, SDMediaKit.fail("picker_unavailable"))
    }

    private fun saveToGallery(json: JSONObject, callback: KuiklyRenderCallback?) {
        val ctx = appContext() ?: return SDMediaKit.reply(callback, SDMediaKit.fail("no_context"))
        val src = SDMediaKit.localPath(json.optString("path", ""))?.let { File(it) }
        if (src == null || !src.isFile || src.length() == 0L) {
            SDMediaKit.reply(callback, SDMediaKit.fail("file_not_found"))
            return
        }
        val isVideo = json.optString("type", "image") == "video"
        val fileName = json.optStringOrNull("fileName")

        val write = Runnable {
            SDMediaKit.io.execute {
                val result = try {
                    writeToGallery(ctx, src, isVideo, fileName)
                    SDMediaKit.ok()
                } catch (e: SecurityException) {
                    SDMediaKit.fail(ERR_STORAGE_DENIED)
                } catch (e: Throwable) {
                    Log.w(TAG, "saveToGallery failed", e)
                    SDMediaKit.fail(SDMediaKit.errorText(e))
                }
                SDMediaKit.reply(callback, result)
            }
        }

        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || isGranted(ctx, permission)) {
            write.run()
            return
        }
        // Android 9 及以下写公共目录需要存储权限（用途说明弹窗由 Kuikly 侧在调用前完成）
        val host = hostActivity()
        val ok = host != null && launch(host, ActivityResultContracts.RequestPermission(), permission) { granted ->
            if (granted) write.run() else SDMediaKit.reply(callback, SDMediaKit.fail(ERR_STORAGE_DENIED))
        }
        if (!ok) SDMediaKit.reply(callback, SDMediaKit.fail(ERR_STORAGE_DENIED))
    }

    private fun writeToGallery(context: Context, src: File, isVideo: Boolean, fileName: String?) {
        var ext = SDMediaKit.extensionOf(fileName)
        if (ext.isEmpty()) ext = SDMediaKit.extensionOf(src.name).takeIf { it.isNotEmpty() && it != "exo" && it != "part" }.orEmpty()
        if (ext.isEmpty()) ext = sniffExtension(src, isVideo)
        val base = when {
            fileName.isNullOrBlank() -> "sondo_${System.currentTimeMillis()}"
            SDMediaKit.extensionOf(fileName).isNotEmpty() -> fileName.substringBeforeLast('.')
            else -> fileName
        }
        val displayName = "${sanitizeFileName(base)}.$ext"
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: if (isVideo) "video/mp4" else "image/jpeg"
        val publicDir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = if (isVideo) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, publicDir)
                put(MediaStore.MediaColumns.DATE_ADDED, now / 1000)
                put(MediaStore.MediaColumns.DATE_MODIFIED, now / 1000)
                put(MediaStore.MediaColumns.DATE_TAKEN, now)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: throw IOException("media_store_insert_failed")
            try {
                val os: OutputStream = resolver.openOutputStream(uri, "w") ?: throw IOException("open_output_failed")
                os.use { FileInputStream(src).use { input -> input.copyTo(it, COPY_BUFFER) } }
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            } catch (e: Throwable) {
                try {
                    resolver.delete(uri, null, null)
                } catch (ignored: Throwable) {
                }
                throw e
            }
            return
        }

        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(publicDir)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("create_dir_failed")
        var target = File(dir, displayName)
        var index = 1
        while (target.exists()) {
            target = File(dir, "${sanitizeFileName(base)}_${index++}.$ext")
        }
        try {
            FileInputStream(src).use { input -> FileOutputStream(target).use { input.copyTo(it, COPY_BUFFER) } }
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
    }

    /** 没有扩展名时按文件头判断。 */
    private fun sniffExtension(file: File, isVideo: Boolean): String {
        val head = ByteArray(12)
        val n = try {
            FileInputStream(file).use { it.read(head) }
        } catch (e: Throwable) {
            -1
        }
        if (n >= 12) {
            val u = IntArray(12) { head[it].toInt() and 0xFF }
            if (u[0] == 0xFF && u[1] == 0xD8) return "jpg"
            if (u[0] == 0x89 && u[1] == 0x50 && u[2] == 0x4E && u[3] == 0x47) return "png"
            if (u[0] == 0x47 && u[1] == 0x49 && u[2] == 0x46) return "gif"
            if (u[0] == 0x52 && u[1] == 0x49 && u[2] == 0x46 && u[3] == 0x46 &&
                u[8] == 0x57 && u[9] == 0x45 && u[10] == 0x42 && u[11] == 0x50
            ) return "webp"
            if (u[4] == 0x66 && u[5] == 0x74 && u[6] == 0x79 && u[7] == 0x70) return if (isVideo) "mp4" else "heic"
            if (u[0] == 0x1A && u[1] == 0x45 && u[2] == 0xDF && u[3] == 0xA3) return "webm"
        }
        return if (isVideo) "mp4" else "jpg"
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.map { c -> if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|' || c.code < 0x20) '_' else c }
            .joinToString("")
            .trim()
        return cleaned.ifEmpty { "sondo_${System.currentTimeMillis()}" }.take(120)
    }

    // ---------------------------------------------------------------------------------
    // Intent / Activity Result
    // ---------------------------------------------------------------------------------

    /** 相册类选择的候选 Intent，按优先级排列。 */
    private fun galleryIntents(context: Context, mime: String, maxCount: Int): List<Intent> {
        val out = ArrayList<Intent>()
        val isVideo = mime.startsWith("video/")
        if (isPhotoPickerAvailable()) {
            out.add(Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                type = mime
                if (maxCount > 1) {
                    val limit = try {
                        MediaStore.getPickImagesMaxLimit()
                    } catch (e: Throwable) {
                        maxCount
                    }
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, maxCount.coerceAtMost(limit).coerceAtLeast(2))
                }
            })
        }
        if (maxCount <= 1) {
            // 传统相册选择（不支持多选）
            val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            out.add(Intent(Intent.ACTION_PICK).apply { setDataAndType(collection, mime) })
        }
        for (action in listOf(Intent.ACTION_GET_CONTENT, Intent.ACTION_OPEN_DOCUMENT)) {
            out.add(Intent(action).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mime
                if (maxCount > 1) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            })
        }
        return out
    }

    private fun isPhotoPickerAvailable(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return try {
                SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2
            } catch (e: Throwable) {
                false
            }
        }
        return false
    }

    private fun resultUris(result: ActivityResult): List<Uri> {
        if (result.resultCode != Activity.RESULT_OK) return emptyList()
        val data = result.data ?: return emptyList()
        val out = LinkedHashSet<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i)?.uri?.let { out.add(it) }
        }
        data.data?.let { out.add(it) }
        return out.toList()
    }

    /** 依次尝试候选 Intent，直到有一个能被系统处理。全部失败返回 false。 */
    private fun launchFirst(host: ComponentActivity, intents: List<Intent>, onResult: (ActivityResult) -> Unit): Boolean {
        for (intent in intents) {
            try {
                if (launch(host, ActivityResultContracts.StartActivityForResult(), intent, onResult)) return true
            } catch (e: SecurityException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "launch ${intent.action} failed", e)
            }
        }
        return false
    }

    /**
     * 以唯一 key 临时注册并发起一次 Activity Result 调用。
     * 没有可处理的 Activity 时返回 false；[SecurityException] 向上抛。
     */
    private fun <I, O> launch(
        host: ComponentActivity,
        contract: ActivityResultContract<I, O>,
        input: I,
        onResult: (O) -> Unit,
    ): Boolean {
        if (destroyed) return false
        val key = "sd_media_picker_${SEQ.incrementAndGet()}"
        var launcher: ActivityResultLauncher<I>? = null
        val unregister = {
            launcher?.let { l ->
                synchronized(launchers) { launchers.remove(l) }
                try {
                    l.unregister()
                } catch (e: Throwable) {
                    // ignore
                }
            }
            launcher = null
        }
        launcher = host.activityResultRegistry.register(key, contract) { result ->
            unregister()
            try {
                onResult(result)
            } catch (e: Throwable) {
                Log.w(TAG, "handle activity result failed", e)
            }
        }
        synchronized(launchers) { launcher?.let { launchers.add(it) } }
        return try {
            launcher?.launch(input)
            true
        } catch (e: ActivityNotFoundException) {
            unregister()
            false
        } catch (e: SecurityException) {
            unregister()
            throw e
        } catch (e: Throwable) {
            unregister()
            Log.w(TAG, "launch failed", e)
            false
        }
    }

    // ---------------------------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------------------------

    private fun onMain(callback: KuiklyRenderCallback?, block: () -> Unit) {
        SDMediaKit.runOnMain {
            try {
                block()
            } catch (e: SecurityException) {
                SDMediaKit.reply(callback, SDMediaKit.fail(ERR_PHOTO_DENIED))
            } catch (e: Throwable) {
                Log.w(TAG, "picker call failed", e)
                SDMediaKit.reply(callback, SDMediaKit.fail(SDMediaKit.errorText(e)))
            }
        }
    }

    private fun onIo(callback: KuiklyRenderCallback?, block: () -> Map<String, Any>) {
        SDMediaKit.io.execute {
            val result = try {
                block()
            } catch (e: Throwable) {
                SDMediaKit.fail(SDMediaKit.errorText(e))
            }
            SDMediaKit.reply(callback, result)
        }
    }

    private fun hostActivity(): ComponentActivity? = activity as? ComponentActivity

    private fun appContext(): Context? = (activity ?: context)?.applicationContext

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun declaresPermission(context: Context, permission: String): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            info.requestedPermissions?.contains(permission) == true
        } catch (e: Throwable) {
            false
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment.orEmpty()
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.takeIf { it.isNotEmpty() }?.let { return it }
                }
            }
        } catch (e: Throwable) {
            // 部分 provider 不支持查询
        }
        return uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    }

    private fun copyToFile(context: Context, uri: Uri, out: File): Long {
        SDMediaKit.ensureParent(out)
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("open_failed")
        try {
            input.use { ins -> FileOutputStream(out).use { ins.copyTo(it, COPY_BUFFER) } }
        } catch (e: Throwable) {
            out.delete()
            throw e
        }
        return out.length()
    }

    private fun extensionFromMime(mime: String?): String? {
        if (mime.isNullOrEmpty()) return null
        return when (mime.lowercase()) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/mp4a-latm" -> "m4a"
            "audio/aac", "audio/aac-adts", "audio/x-aac" -> "aac"
            "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "wav"
            "audio/flac", "audio/x-flac" -> "flac"
            "video/mp4" -> "mp4"
            "video/quicktime" -> "mov"
            else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        }
    }

    private fun mimeTypesFor(ext: String): List<String> = when (ext) {
        "mp3" -> listOf("audio/mpeg", "audio/mp3")
        "m4a" -> listOf("audio/mp4", "audio/x-m4a", "audio/m4a", "audio/mp4a-latm")
        "aac" -> listOf("audio/aac", "audio/aac-adts", "audio/x-aac")
        "wav" -> listOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")
        "flac" -> listOf("audio/flac", "audio/x-flac")
        "ogg" -> listOf("audio/ogg", "application/ogg")
        else -> listOfNotNull(MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext))
    }

    companion object {
        private const val TAG = "SDMediaPicker"
        private const val ERR_PHOTO_DENIED = "photo_access_denied"
        private const val ERR_CAMERA_DENIED = "camera_access_denied"
        private const val ERR_STORAGE_DENIED = "storage_access_denied"
        private const val COPY_BUFFER = 64 * 1024
        private val SEQ = AtomicInteger()
    }
}
