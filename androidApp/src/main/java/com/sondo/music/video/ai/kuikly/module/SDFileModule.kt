package com.sondo.music.video.ai.kuikly.module

import android.util.Base64
import com.bumptech.glide.Glide
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * 文件操作（契约：shared/nativeapi/SDAppModule.kt 里的 SDFileModule）。
 * 所有方法都是异步的：调用到达主线程后立刻转到 IO 线程池执行，结果经 callback 回 Kuikly。
 * 路径均为绝对路径，也接受 `file://` 前缀。
 */
class SDFileModule : SDCoreBaseModule() {

    override fun handle(method: String, p: JSONObject, callback: KuiklyRenderCallback?): Any? {
        if (method !in METHODS) return unknown(method, callback)
        async(callback) { run(method, p) }
        return null
    }

    /** IO 线程执行；抛出的异常由 [async] 统一转成 `{ok:false, error}`。 */
    private fun run(method: String, p: JSONObject): JSONObject = when (method) {
        "exists" -> SDCoreKit.ok().put("exists", file(p).exists())
        "size" -> SDCoreKit.ok().put("size", sizeOf(file(p)))
        "delete" -> delete(file(p))
        "copy" -> copy(file(p), dest(p))
        "rename" -> rename(file(p), dest(p))
        "readText" -> SDCoreKit.ok().put("text", requireFile(p).readText(Charsets.UTF_8))
        "writeText" -> writeText(file(p), p.optString("text"))
        "readHead" -> readHead(requireFile(p), p.optInt("length", 0))
        "md5File" -> SDCoreKit.ok().put("md5", md5(requireFile(p)))
        "copyAsset" -> copyAsset(p.optString("asset"), SDCoreKit.localPath(p.optString("destPath")))
        "cacheSize" -> SDCoreKit.ok().put("bytes", cacheRoots().sumOf { sizeOf(it).coerceAtLeast(0L) })
        "clearCache" -> clearCache()
        "list" -> list(file(p))
        else -> SDCoreKit.fail("unknown method: $method")
    }

    // ------------------------------------------------------------------ 参数

    private fun file(p: JSONObject): File {
        val path = SDCoreKit.localPath(p.optString("path"))
        if (path.isEmpty()) throw IllegalArgumentException("path is empty")
        return File(path)
    }

    private fun dest(p: JSONObject): File {
        val path = SDCoreKit.localPath(p.optString("dest"))
        if (path.isEmpty()) throw IllegalArgumentException("dest is empty")
        return File(path)
    }

    private fun requireFile(p: JSONObject): File {
        val f = file(p)
        if (!f.isFile) throw FileNotFoundException("file not found: ${f.path}")
        return f
    }

    // ------------------------------------------------------------------ 操作

    /** 文件 → 字节数；目录 → 递归总和；不存在 → -1。 */
    private fun sizeOf(f: File): Long {
        if (!f.exists()) return -1L
        if (f.isFile) return f.length()
        var total = 0L
        f.walkBottomUp().forEach { if (it.isFile) total += it.length() }
        return total
    }

    /** 删除文件或整个目录。目标本来就不存在也算成功（调用方要的是「它不在了」）。 */
    private fun delete(f: File): JSONObject {
        if (!f.exists()) return SDCoreKit.ok()
        val done = if (f.isDirectory) f.deleteRecursively() else f.delete()
        return if (done || !f.exists()) SDCoreKit.ok() else SDCoreKit.fail("delete failed: ${f.path}")
    }

    private fun copy(src: File, dest: File): JSONObject {
        if (!src.isFile) throw FileNotFoundException("file not found: ${src.path}")
        if (src.canonicalPath == dest.canonicalPath) return SDCoreKit.ok().put("path", dest.absolutePath)
        dest.parentFile?.mkdirs()
        // 先写临时文件再改名：目标要么是旧内容，要么是完整的新内容
        val tmp = File(dest.parentFile, "${dest.name}.${System.nanoTime()}.tmp")
        try {
            FileInputStream(src).use { input -> FileOutputStream(tmp).use { output -> input.copyTo(output, BUFFER) } }
            moveInto(tmp, dest)
        } finally {
            tmp.delete()
        }
        return SDCoreKit.ok().put("path", dest.absolutePath)
    }

    private fun rename(src: File, dest: File): JSONObject {
        if (!src.exists()) throw FileNotFoundException("file not found: ${src.path}")
        if (src.canonicalPath == dest.canonicalPath) return SDCoreKit.ok().put("path", dest.absolutePath)
        dest.parentFile?.mkdirs()
        if (dest.isFile) dest.delete()
        if (!src.renameTo(dest)) {
            // 跨存储卷（内部存储 ↔ 外部存储）不能直接 rename：退化为拷贝 + 删除
            if (src.isDirectory) {
                if (!src.copyRecursively(dest, overwrite = true)) throw IOException("rename failed: ${src.path}")
                src.deleteRecursively()
            } else {
                FileInputStream(src).use { input -> FileOutputStream(dest).use { output -> input.copyTo(output, BUFFER) } }
                src.delete()
            }
        }
        return SDCoreKit.ok().put("path", dest.absolutePath)
    }

    private fun writeText(f: File, text: String): JSONObject {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "${f.name}.${System.nanoTime()}.tmp")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            moveInto(tmp, f)
        } finally {
            tmp.delete()
        }
        return SDCoreKit.ok()
    }

    private fun moveInto(tmp: File, dest: File) {
        if (tmp.renameTo(dest)) return
        // 少数文件系统上 rename 不能覆盖已存在的目标
        if (dest.exists() && !dest.delete()) throw IOException("cannot replace ${dest.path}")
        if (!tmp.renameTo(dest)) throw IOException("cannot write ${dest.path}")
    }

    private fun readHead(f: File, length: Int): JSONObject {
        val n = length.coerceIn(0, MAX_HEAD_BYTES)
        val buf = ByteArray(n)
        var read = 0
        FileInputStream(f).use { input ->
            while (read < n) {
                val r = input.read(buf, read, n - read)
                if (r < 0) break
                read += r
            }
        }
        return SDCoreKit.ok().put("base64", Base64.encodeToString(buf, 0, read, Base64.NO_WRAP))
    }

    private fun md5(f: File): String {
        val digest = MessageDigest.getInstance("MD5")
        val buf = ByteArray(BUFFER)
        FileInputStream(f).use { input ->
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                digest.update(buf, 0, r)
            }
        }
        return SDCoreKit.hex(digest.digest())
    }

    /**
     * shared 模块的资源在 APK 里位于 `assets/common/…`（shared/build.gradle.kts 把 commonMain/assets 整个作为 assets 源目录），
     * 入参是相对 common/ 的路径；带了 `common/`、`assets://common/` 前缀也能识别。
     */
    private fun copyAsset(asset: String, destPath: String): JSONObject {
        if (destPath.isEmpty()) throw IllegalArgumentException("destPath is empty")
        var name = asset.trim().removePrefix("assets://").trimStart('/')
        if (name.isEmpty()) throw IllegalArgumentException("asset is empty")
        if (name.startsWith("common/")) name = name.removePrefix("common/")
        val dest = File(destPath)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, "${dest.name}.${System.nanoTime()}.tmp")
        try {
            openAsset(name).use { input -> FileOutputStream(tmp).use { output -> input.copyTo(output, BUFFER) } }
            moveInto(tmp, dest)
        } finally {
            tmp.delete()
        }
        return SDCoreKit.ok().put("path", dest.absolutePath)
    }

    private fun openAsset(name: String): InputStream {
        val assets = appContext.assets
        return try {
            assets.open("common/$name")
        } catch (e: IOException) {
            // 宿主自带的资源（如 fonts/…）不在 common/ 下
            try {
                assets.open(name)
            } catch (e2: IOException) {
                throw FileNotFoundException("asset not found: $name")
            }
        }
    }

    private fun cacheRoots(): List<File> = listOfNotNull(appContext.cacheDir, appContext.externalCacheDir)

    /**
     * 清空图片 / 视频 / 临时文件缓存：Glide 的磁盘缓存走它自己的清理接口（内部有日志文件，不能直接删目录），
     * 其余 cache 目录下的内容逐个删除。个别文件正被占用删不掉不算失败。
     */
    private fun clearCache(): JSONObject {
        try {
            Glide.get(appContext).clearDiskCache()
        } catch (e: Throwable) {
            SDCoreKit.logE("clear glide disk cache failed", e)
        }
        SDCoreKit.runOnMain {
            try {
                Glide.get(appContext).clearMemory()
            } catch (e: Throwable) {
                // ignore
            }
        }
        val glideDir = File(appContext.cacheDir, GLIDE_DISK_CACHE_DIR)
        for (root in cacheRoots()) {
            root.listFiles()?.forEach { child ->
                if (child != glideDir) {
                    try {
                        child.deleteRecursively()
                    } catch (e: Throwable) {
                        // ignore
                    }
                }
            }
        }
        return SDCoreKit.ok()
    }

    private fun list(dir: File): JSONObject {
        if (!dir.isDirectory) throw FileNotFoundException("directory not found: ${dir.path}")
        val files = JSONArray()
        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            val isDir = f.isDirectory
            files.put(
                JSONObject()
                    .put("name", f.name)
                    .put("path", f.absolutePath)
                    .put("size", if (isDir) 0L else f.length())
                    .put("isDir", isDir)
                    .put("modifiedMs", f.lastModified())
            )
        }
        return SDCoreKit.ok().put("files", files)
    }

    companion object {
        const val MODULE_NAME = "SDFileModule"
        private val METHODS = setOf(
            "exists", "size", "delete", "copy", "rename", "readText", "writeText", "readHead",
            "md5File", "copyAsset", "cacheSize", "clearCache", "list",
        )
        private const val BUFFER = 64 * 1024
        private const val MAX_HEAD_BYTES = 1024 * 1024

        /** Glide 默认磁盘缓存目录名（DiskCache.Factory.DEFAULT_DISK_CACHE_DIR）。 */
        private const val GLIDE_DISK_CACHE_DIR = "image_manager_disk_cache"
    }
}
