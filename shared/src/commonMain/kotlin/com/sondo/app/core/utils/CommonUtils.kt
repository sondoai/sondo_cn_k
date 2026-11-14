package com.sondo.app.core.utils

import com.sondo.app.base.AppEnv
import com.sondo.app.base.BasePager
import com.sondo.app.base.currentPager
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.i18n.trParams
import com.sondo.app.core.utils.Uuid
import com.sondo.app.nativeapi.SDAppModule
import com.sondo.app.nativeapi.SDCryptoModule
import com.sondo.app.nativeapi.SDFileModule
import com.sondo.app.nativeapi.SDHttpModule
import com.sondo.app.nativeapi.SDMediaPickerModule
import com.sondo.app.nativeapi.SDMediaProcessModule
import com.sondo.app.nativeapi.SDPermissionModule
import com.sondo.app.nativeapi.nativeModule
import com.sondo.app.nativeapi.ok
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** 下载 / 处理进度回调：step = "cache_hit" | "download"，progress 0..1。 */
typealias ProgressCallback = (step: String, progress: Double?) -> Unit

/**
 * 通用工具（对应 Flutter 侧 CommonUtils）。
 * 加密（encryptStr / encrypt）在 data 层的 ApiCrypto，不在这里。
 */
object CommonUtils {
    const val SOURCE_CAMERA = "camera"
    const val SOURCE_GALLERY = "gallery"

    // ------------------------------------------------------------------ 相册保存

    // 相册写入串行，避免图片和视频并发保存互相覆盖。
    private var gallerySaving = false
    private val galleryWaiters = ArrayList<Continuation<Unit>>()

    private suspend fun <T> enqueueGallerySave(operation: suspend () -> T): T {
        if (gallerySaving) suspendCoroutine<Unit> { galleryWaiters.add(it) } else gallerySaving = true
        try {
            return operation()
        } finally {
            // 当前调用无论成败，队尾都继续执行。
            if (galleryWaiters.isNotEmpty()) galleryWaiters.removeAt(0).resume(Unit) else gallerySaving = false
        }
    }

    /** @param albumName 写入相册的文件名（参数名沿用参考工程） */
    suspend fun saveImageToGallery(path: String, albumName: String? = null): Boolean =
        enqueueGallerySave { saveFileToGallery(path, albumName, fallbackExt = "jpg", isVideo = false) }

    suspend fun saveVideoToGallery(path: String, albumName: String? = null): Boolean =
        enqueueGallerySave { saveFileToGallery(path, albumName, fallbackExt = "mp4", isVideo = true) }

    /** 网络地址先落到本地；[name] 是写入相册的文件名，例如 video_123.mp4。 */
    private suspend fun saveFileToGallery(path: String, name: String?, fallbackExt: String, isVideo: Boolean): Boolean {
        if (!ensureGalleryWritePermission(isVideo)) return false
        val localPath = localPathForGallery(path, fallbackExt, isVideo) ?: return false
        val ext = mediaExt(localPath, fallbackExt)
        val fileName = if (!name.isNullOrEmpty()) name else "sondo_${nowMs()}.$ext"
        val picker: SDMediaPickerModule = nativeModule(SDMediaPickerModule.MODULE_NAME)
        return picker.saveToGallery(localPath, if (isVideo) "video" else "image", fileName).ok()
    }

    /**
     * 相册写入权限统一入口。
     * iOS / 鸿蒙只申请 photosAddOnly（不读取已有照片）；Android 仅 32 及以下申请存储权限，
     * 新版本通过 MediaStore 写入无需额外权限。
     */
    private suspend fun ensureGalleryWritePermission(isVideo: Boolean): Boolean {
        val permission: String
        val purpose: String
        if (AppEnv.isAndroid) {
            val sdkInt = nativeModule<SDAppModule>(SDAppModule.MODULE_NAME).getDeviceInfo().optInt("sdkInt", 0)
            if (sdkInt > 32) return true
            permission = PermissionUtil.STORAGE
            purpose = if (isVideo) "将你主动下载的音乐视频保存到设备存储。仅在下载时使用" else "将你主动下载的图片保存到设备存储。仅在下载时使用"
        } else {
            permission = PermissionUtil.PHOTOS_ADD_ONLY
            purpose = if (isVideo) "将你主动下载的音乐视频保存到系统相册，不读取相册内容" else "将你主动下载的图片保存到系统相册，不读取相册内容"
        }

        val current = PermissionUtil.checkPermission(permission)
        val alreadyUsable = PermissionUtil.isUsable(current)
        // 下载时 loading 还开着就弹说明，遮罩会挡住「拒绝/同意」。已授权不弹窗，不动 loading。
        var pausedLoading = false
        if (!alreadyUsable && current != SDPermissionModule.RESTRICTED && LoadingUtil.isOpenLoading()) {
            LoadingUtil.close()
            pausedLoading = true
        }

        val status = PermissionUtil.requestPermissionStatus(permission, purpose = purpose)
        if (PermissionUtil.isUsable(status)) {
            // 说明结束且已授权，把调用方的 loading 拉回来，写入相册仍有反馈。
            if (pausedLoading) LoadingUtil.show()
            return true
        }
        if (status == SDPermissionModule.PERMANENTLY_DENIED) {
            val pager = currentPager()
            if (pager != null) {
                ConfirmDialogLite.show(
                    pager,
                    title = "permission_tips".tr,
                    content = (
                        if (permission == PermissionUtil.STORAGE) {
                            "storage_permission_is_required_to_continue_the_operation_do_you_want_to_set_it_up"
                        } else {
                            "album_permission_is_required_to_continue_the_operation_do_you_want_to_set_it_up"
                        }
                        ).tr,
                    cancelText = "permission_deny".tr,
                    confirmText = "go_to_settings".tr,
                    onConfirm = { PermissionUtil.openSettings() },
                )
            }
        }
        return false
    }

    private suspend fun localPathForGallery(path: String, fallbackExt: String, isVideo: Boolean): String? {
        if (!isHttpUrl(path)) return path
        val ext = mediaExt(path, fallbackExt)
        // 静态图没有时长，不能走 downloadWithCache 的媒体校验。
        return if (isVideo) downloadWithCache(path, ext) else downloadStillFile(path, ext)
    }

    private fun isHttpUrl(path: String): Boolean = path.startsWith("http://") || path.startsWith("https://")

    private fun mediaExt(path: String, fallback: String): String = FileUtils.extensionOf(path).ifEmpty { fallback }

    // ------------------------------------------------------------------ 下载

    private fun crypto(): SDCryptoModule = nativeModule(SDCryptoModule.MODULE_NAME)

    fun getMd5(str: String): String = crypto().md5(str)

    /** 单次下载到 [savePath]；返回是否成功。[onStart] 回传请求 id（可用 SDHttpModule.cancel 取消）。 */
    private suspend fun downloadOnce(
        url: String,
        savePath: String,
        timeoutMs: Int,
        onProgress: ((Double) -> Unit)? = null,
        onStart: ((String) -> Unit)? = null,
    ): Boolean {
        val http: SDHttpModule = nativeModule(SDHttpModule.MODULE_NAME)
        val id = Uuid.v4()
        onStart?.invoke(id)
        val params = JSONObject().put("id", id).put("url", url).put("savePath", savePath).put("timeoutMs", timeoutMs)
        return suspendCoroutine { cont ->
            var done = false
            var ref: String? = null
            ref = http.download(params) { ev ->
                when (ev.optString("event", "")) {
                    "progress" -> {
                        val total = ev.optLong("total", 0L)
                        if (total > 0) onProgress?.invoke(ev.optLong("received", 0L).toDouble() / total)
                    }
                    "done" -> if (!done) {
                        done = true
                        ref?.let { http.removeCallback(it) }
                        cont.resume(ev.optBoolean("ok", false) && ev.optInt("status", 200) in 200..299)
                    }
                }
            }
        }
    }

    /** 下载静态图到临时目录（不走视频时长校验）；失败返回 null。 */
    suspend fun downloadStillFile(url: String, ext: String): String? {
        val file: SDFileModule = nativeModule(SDFileModule.MODULE_NAME)
        val savePath = "${FileUtils.tempDir()}/${getMd5(url)}.$ext"
        if (file.exists(savePath) && file.size(savePath) > 0) return savePath
        // 先写入临时文件，避免中断后把不完整内容当成缓存。
        val tempPath = "$savePath.tmp"
        if (file.exists(tempPath)) file.delete(tempPath)
        val ok = downloadOnce(url, tempPath, timeoutMs = 5 * 60 * 1000)
        if (!ok || !file.exists(tempPath) || file.size(tempPath) <= 0) {
            if (file.exists(tempPath)) file.delete(tempPath)
            return null
        }
        if (file.exists(savePath)) file.delete(savePath)
        if (!file.rename(tempPath, savePath)) return null
        return savePath
    }

    /** 校验本地媒体文件是否可用（存在、非空、可读且时长 > 0）。 */
    suspend fun isValidCachedMediaFile(path: String): Boolean {
        val file: SDFileModule = nativeModule(SDFileModule.MODULE_NAME)
        if (!file.exists(path) || file.size(path) <= 0) return false
        val info = nativeModule<SDMediaProcessModule>(SDMediaProcessModule.MODULE_NAME).probe(path)
        if (!info.ok()) return false
        return info.optLong("durationMs", 0L) > 0
    }

    /** 返回 [url] 对应的本地缓存路径（文件存在且有效时），否则 null。 */
    suspend fun cachedFilePathIfExists(url: String, ext: String): String? {
        val savePath = "${FileUtils.tempDir()}/${getMd5(url)}.$ext"
        return if (isValidCachedMediaFile(savePath)) savePath else null
    }

    /**
     * 下载带缓存和重试（最多 [maxRetry] 次，间隔 2 秒）。
     * 失败或被取消返回 null（参考工程抛异常；Kuikly 协程里未捕获异常会崩溃，改为空值）。
     * @param isCancelled 调用方的取消标记；[onRequestId] 回传当前请求 id 供 SDHttpModule.cancel 使用
     */
    suspend fun downloadWithCache(
        url: String,
        ext: String,
        maxRetry: Int = 5,
        progressCallback: ProgressCallback? = null,
        isCancelled: (() -> Boolean)? = null,
        onRequestId: ((String) -> Unit)? = null,
    ): String? {
        val file: SDFileModule = nativeModule(SDFileModule.MODULE_NAME)
        val savePath = "${FileUtils.tempDir()}/${getMd5(url)}.$ext"
        val tempPath = "$savePath.tmp"
        if (file.exists(savePath)) {
            if (isValidCachedMediaFile(savePath)) {
                progressCallback?.invoke("cache_hit", 1.0)
                return savePath
            }
            DevLog.logger("downloadWithCache invalid cache, re-download: $url")
            file.delete(savePath)
        }
        var attempt = 0
        while (attempt < maxRetry) {
            if (isCancelled?.invoke() == true) return null
            // 每次重试前清理临时文件
            if (file.exists(tempPath)) file.delete(tempPath)
            val ok = downloadOnce(
                url, tempPath, timeoutMs = 5 * 60 * 1000,
                onProgress = { p -> if (isCancelled?.invoke() != true) progressCallback?.invoke("download", p) },
                onStart = onRequestId,
            )
            if (isCancelled?.invoke() == true) {
                if (file.exists(tempPath)) file.delete(tempPath)
                return null
            }
            if (ok && file.exists(tempPath) && file.size(tempPath) > 0 && isValidCachedMediaFile(tempPath)) {
                // 下载完整后，再替换正式缓存文件
                if (file.exists(savePath)) file.delete(savePath)
                if (file.rename(tempPath, savePath)) return savePath
            }
            // 下载失败时必须删除临时文件，避免下次误用
            if (file.exists(tempPath)) file.delete(tempPath)
            if (isCancelled?.invoke() == true) return null
            attempt++
            if (attempt < maxRetry) delayMs(2000) else DevLog.logger("下载失败超过 $attempt 次: $url")
        }
        return null
    }

    // ------------------------------------------------------------------ 选图

    /** 统一转成 JPEG（quality 100）；失败返回 null。 */
    suspend fun compressToJpg(path: String): String? {
        val res = nativeModule<SDMediaPickerModule>(SDMediaPickerModule.MODULE_NAME).compressImage(path, 100)
        return if (res.ok()) res.optString("path", "").ifEmpty { null } else null
    }

    /** 图片可解码则返回宽高，否则 null。 */
    suspend fun isImageValid(path: String): Pair<Int, Int>? {
        val res = nativeModule<SDMediaPickerModule>(SDMediaPickerModule.MODULE_NAME).getImageSize(path)
        if (!res.ok()) return null
        val w = res.optInt("width", 0)
        val h = res.optInt("height", 0)
        return if (w > 0 && h > 0) w to h else null
    }

    /**
     * 选图（相机 / 相册）。返回 JPEG 本地路径；取消、被拒或校验不过返回 null（已提示）。
     *
     * 选择器会在需要时触发系统权限框：先在 App 内用当前功能的具体用途说明，用户拒绝后不进入系统授权流程。
     * @param source [SOURCE_CAMERA] / [SOURCE_GALLERY]
     * @param callback 选中图片后回调 "pick_image_success"
     * @param isCheckImageHW 校验宽高在 300–6000
     * @param isCheckImageAspectRatio 校验宽÷高在 0.4–2.5
     */
    suspend fun pickImage(
        source: String,
        pager: BasePager? = null,
        callback: ((String) -> Unit)? = null,
        isCheckImageSize: Boolean = true,
        isCheckImageAspectRatio: Boolean = false,
        isCheckImageHW: Boolean = false,
    ): String? {
        val host = pager ?: currentPager() ?: return null
        val isCamera = source == SOURCE_CAMERA
        val permission = if (isCamera) PermissionUtil.CAMERA else PermissionUtil.PHOTOS
        val shouldOpenPicker = PermissionUtil.confirmBeforeSystemPermission(
            permission,
            purpose = if (isCamera) "拍摄你主动选择上传的创作素材" else "选择你主动上传的照片作为头像或音乐视频创作素材",
            pager = host,
        )
        if (!shouldOpenPicker) return null

        // 选择器由 [host] 弹出（Android 上 activity-result 只投给前台页面）
        val res = onPage(host, { JSONObject().put("ok", false).put("cancelled", true) }) {
            nativeModule<SDMediaPickerModule>(SDMediaPickerModule.MODULE_NAME).pickImage(source, quality = 100)
        }
        if (!res.ok()) {
            if (res.optBoolean("cancelled", false)) return null
            val error = res.optString("error", "")
            if (error == "photo_access_denied" || error == "camera_access_denied") {
                val permissionSourceText = (if (isCamera) "camera" else "album").tr
                ConfirmDialogLite.show(
                    host,
                    title = "permission_tips".tr,
                    content = "permission_required_to_continue_operation".trParams("source" to permissionSourceText),
                    cancelText = "cancel".tr,
                    confirmText = "go_to_settings".tr,
                    onConfirm = { PermissionUtil.openSettings() },
                )
                return null
            }
            LogUtils.logRed("pickImage error: $error")
            val errorMessage = when {
                error.contains("permission") -> "permission_settings_hint"
                error.contains("camera") -> "camera_unavailable"
                else -> "image_pick_failed"
            }
            host.toast(errorMessage.tr)
            return null
        }
        val imagePath = res.optString("path", "")
        if (imagePath.isEmpty()) return null
        callback?.invoke("pick_image_success")

        val imageInfo = isImageValid(imagePath)
        if (imageInfo == null) {
            nativeModule<SDFileModule>(SDFileModule.MODULE_NAME).delete(imagePath)
            host.toast("image_damage_or_unsupported_format".tr)
            return null
        }
        val (imgWidth, imgHeight) = imageInfo
        val isImageHW = imgWidth < 300 || imgWidth > 6000 || imgHeight < 300 || imgHeight > 6000
        if (isCheckImageHW && isImageHW) {
            host.toast("the_image_hw_not_in_range".trParams("min" to "300", "max" to "6000"))
            return null
        }
        // 模型要求宽÷高在 0.4–2.5，超限过滤不上传、不补边
        val aspectRatio = imgWidth.toDouble() / imgHeight
        val isImageAspectRatio = aspectRatio < 0.4 || aspectRatio > 2.5
        if (isCheckImageAspectRatio && isImageAspectRatio) {
            host.toast("the_image_aspect_ratio_not_in_range".trParams("min" to "0.4", "max" to "2.5"))
            return null
        }
        return imagePath
    }

    // ------------------------------------------------------------------ 杂项

    /** 秒 → `m:ss` */
    fun getMusicDuration(duration: Int): String {
        val minute = duration / 60
        val second = duration % 60
        return "$minute:${if (second < 10) "0$second" else second}"
    }

    fun hideKeyboard() {
        try {
            nativeModule<SDAppModule>(SDAppModule.MODULE_NAME).hideKeyboard()
        } catch (e: Throwable) {
            DevLog.logger("hideKeyboard error: $e")
        }
    }

    /** 文档目录下以时间戳命名的副本路径；拷贝失败返回 null。 */
    suspend fun copyWithTimestampName(origin: String): String? {
        val file: SDFileModule = nativeModule(SDFileModule.MODULE_NAME)
        val ext = FileUtils.extensionOf(origin)
        var stamp = nowMs()
        var newPath = "${FileUtils.documentsDir()}/$stamp${if (ext.isEmpty()) "" else ".$ext"}"
        while (file.exists(newPath)) {
            stamp++
            newPath = "${FileUtils.documentsDir()}/$stamp${if (ext.isEmpty()) "" else ".$ext"}"
        }
        return if (file.copy(origin, newPath)) newPath else null
    }

    /** 是否含表情 / 符号（U+1F600–1F64F、1F300–1F5FF、1F680–1F6FF、2600–26FF、2700–27BF）。 */
    fun containsEmoji(text: String): Boolean {
        for (cp in codePoints(text)) {
            if (cp in 0x1F600..0x1F64F || cp in 0x1F300..0x1F5FF || cp in 0x1F680..0x1F6FF ||
                cp in 0x2600..0x26FF || cp in 0x2700..0x27BF
            ) return true
        }
        return false
    }
}

// ------------------------------------------------------------------ 字素

internal fun codePoints(text: String): List<Int> {
    val out = ArrayList<Int>(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            out.add(0x10000 + ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00))
            i += 2
        } else {
            out.add(c.code)
            i++
        }
    }
    return out
}

private fun appendCodePoint(sb: StringBuilder, cp: Int) {
    if (cp >= 0x10000) {
        val v = cp - 0x10000
        sb.append((0xD800 + (v shr 10)).toChar()).append((0xDC00 + (v and 0x3FF)).toChar())
    } else {
        sb.append(cp.toChar())
    }
}

/** 丢掉孤立代理项。按码元截断 emoji 后的非法 UTF-16 会让文本排版出错。 */
fun stripUnpairedSurrogates(text: String): String {
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.isHighSurrogate()) {
            if (i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                sb.append(c).append(text[i + 1])
                i++
            }
        } else if (!c.isLowSurrogate()) {
            sb.append(c)
        }
        i++
    }
    return sb.toString()
}

/** 该码点是否接在前一个字素后面（组合符、变体选择符、肤色修饰、标签字符、ZWJ）。 */
private fun isExtender(cp: Int): Boolean =
    cp == 0x200D || cp in 0xFE00..0xFE0F || cp in 0x0300..0x036F || cp in 0x20D0..0x20FF ||
        cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F || cp == 0x20E3

private fun isRegionalIndicator(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF

/** 按字素簇切分（覆盖 emoji ZWJ 序列、国旗、肤色、组合符；足够用于输入框长度限制与打字机效果）。 */
private fun graphemes(text: String): List<String> {
    val cps = codePoints(text)
    val out = ArrayList<String>(cps.size)
    var sb = StringBuilder()
    var prev = -1
    var riCount = 0
    for (cp in cps) {
        val joinToPrev = prev >= 0 && (
            isExtender(cp) || prev == 0x200D ||
                (isRegionalIndicator(cp) && isRegionalIndicator(prev) && riCount % 2 == 1) ||
                (prev == 0x0D && cp == 0x0A)
            )
        if (!joinToPrev && sb.isNotEmpty()) {
            out.add(sb.toString())
            sb = StringBuilder()
            riCount = 0
        }
        if (isRegionalIndicator(cp)) riCount++
        appendCodePoint(sb, cp)
        prev = cp
    }
    if (sb.isNotEmpty()) out.add(sb.toString())
    return out
}

/** 按字素截断。短于上限时仍会去掉孤立代理项。 */
fun truncateGraphemes(text: String, maxGraphemes: Int): String {
    val cleaned = stripUnpairedSurrogates(text)
    if (maxGraphemes <= 0 || cleaned.isEmpty()) return ""
    val list = graphemes(cleaned)
    if (list.size <= maxGraphemes) return cleaned
    return list.subList(0, maxGraphemes).joinToString("")
}

/** 打字机按字素前进，避免停在 emoji 的半个码元上。 */
fun graphemeLength(text: String): Int = graphemes(stripUnpairedSurrogates(text)).size
