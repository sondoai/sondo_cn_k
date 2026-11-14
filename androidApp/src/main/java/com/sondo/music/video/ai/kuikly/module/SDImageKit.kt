package com.sondo.music.video.ai.kuikly.module

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/** 图片解码 / 转 JPEG / EXIF 方向修正。全部是阻塞调用，只在后台线程使用。 */
internal object SDImageKit {

    class JpegResult(val path: String, val width: Int, val height: Int, val size: Long) {
        fun toMap(): Map<String, Any> = mapOf("path" to path, "width" to width, "height" to height, "size" to size)
    }

    /** 不限制最长边时的解码上限，防止超大图 OOM。 */
    private const val MAX_DECODE_SIDE = 8192
    private const val MAX_DECODE_PIXELS = 48L * 1000 * 1000

    private val seq = AtomicInteger()

    fun newOutputFile(context: Context, prefix: String, ext: String): File {
        val dir = SDMediaKit.tempDir(context, PICKER_DIR)
        return File(dir, "${prefix}_${System.currentTimeMillis()}_${seq.incrementAndGet()}.$ext")
    }

    const val PICKER_DIR = "sd_picker"

    fun opener(context: Context, uri: Uri): () -> InputStream? = { context.contentResolver.openInputStream(uri) }

    fun opener(file: File): () -> InputStream? = { FileInputStream(file) }

    /**
     * 解码 → 按 EXIF 摆正 → 最长边缩到 [maxSide]（0 = 不缩放）→ 编码为 JPEG（[quality] 1..100）。
     * 带透明通道的图片铺白底。失败抛 [IOException]。
     */
    fun toJpeg(open: () -> InputStream?, quality: Int, maxSide: Int, out: File): JpegResult {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (open() ?: throw IOException("open_failed")).use { BitmapFactory.decodeStream(it, null, bounds) }
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) throw IOException("decode_failed")

        val orientation = readOrientation(open)
        val limit = if (maxSide > 0) maxSide else MAX_DECODE_SIDE
        var sample = 1
        while (maxOf(srcW, srcH) / (sample * 2) >= limit) sample *= 2
        while ((srcW.toLong() / sample) * (srcH.toLong() / sample) > MAX_DECODE_PIXELS) sample *= 2

        var bitmap = decodeWithRetry(open, sample)
        try {
            val matrix = Matrix()
            applyOrientation(matrix, orientation)
            val longest = maxOf(bitmap.width, bitmap.height)
            if (maxSide in 1 until longest) {
                val scale = maxSide.toFloat() / longest
                matrix.postScale(scale, scale)
            }
            if (!matrix.isIdentity) {
                val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (transformed !== bitmap) {
                    bitmap.recycle()
                    bitmap = transformed
                }
            }
            if (bitmap.hasAlpha()) {
                val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(flat)
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                bitmap.recycle()
                bitmap = flat
            }
            SDMediaKit.ensureParent(out)
            val tmp = File(out.parentFile, out.name + ".part")
            val ok = FileOutputStream(tmp).use { os ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), os)
            }
            if (!ok) {
                tmp.delete()
                throw IOException("encode_failed")
            }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) {
                tmp.delete()
                throw IOException("write_failed")
            }
            return JpegResult(out.absolutePath, bitmap.width, bitmap.height, out.length())
        } finally {
            bitmap.recycle()
        }
    }

    /** 读图片尺寸（已按 EXIF 方向换算）。图片损坏返回 null。 */
    fun imageSize(open: () -> InputStream?): Pair<Int, Int>? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            (open() ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            // 只读文件头判断不出截断 / 损坏的图片：再按极小采样真正解一次
            var sample = 1
            while (maxOf(w, h) / (sample * 2) >= 64) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val probe = (open() ?: return null).use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            probe.recycle()
            when (readOrientation(open)) {
                ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_ROTATE_270,
                ExifInterface.ORIENTATION_TRANSPOSE,
                ExifInterface.ORIENTATION_TRANSVERSE -> Pair(h, w)
                else -> Pair(w, h)
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun decodeWithRetry(open: () -> InputStream?, initialSample: Int): Bitmap {
        var sample = initialSample
        var lastError: Throwable? = null
        repeat(3) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bmp = (open() ?: throw IOException("open_failed")).use { BitmapFactory.decodeStream(it, null, opts) }
                return bmp ?: throw IOException("decode_failed")
            } catch (e: OutOfMemoryError) {
                lastError = e
                sample *= 2
            }
        }
        throw IOException("out_of_memory", lastError)
    }

    private fun readOrientation(open: () -> InputStream?): Int {
        return try {
            (open() ?: return ExifInterface.ORIENTATION_NORMAL).use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (e: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun applyOrientation(matrix: Matrix, orientation: Int) {
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> Unit
        }
    }

    // ---------------------------------------------------------------------------------
    // FileProvider
    // ---------------------------------------------------------------------------------

    /**
     * 为 App 私有文件生成 content:// URI。
     *
     * 不写死 authority：工程里可能有多个 FileProvider（分享、三方 SDK），按
     * `<包名>.fileprovider` → 标准 FileProvider → 其它子类 的顺序找第一个能映射该文件的。
     */
    fun contentUriFor(context: Context, file: File): Uri? {
        val candidates = try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS)
            info.providers.orEmpty().filter { isFileProvider(it.name) }
        } catch (e: Throwable) {
            emptyList()
        }
        val preferred = "${context.packageName}.fileprovider"
        val ordered = candidates.sortedBy {
            when {
                it.authority == preferred -> 0
                it.name == FileProvider::class.java.name -> 1
                else -> 2
            }
        }
        for (provider in ordered) {
            val authority = provider.authority?.split(';')?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: continue
            try {
                return FileProvider.getUriForFile(context, authority, file)
            } catch (e: Throwable) {
                // 该 provider 的 paths 不包含这个目录，换下一个
            }
        }
        return null
    }

    private fun isFileProvider(className: String?): Boolean {
        if (className.isNullOrEmpty()) return false
        if (className == FileProvider::class.java.name) return true
        return try {
            FileProvider::class.java.isAssignableFrom(Class.forName(className))
        } catch (e: Throwable) {
            false
        }
    }
}
