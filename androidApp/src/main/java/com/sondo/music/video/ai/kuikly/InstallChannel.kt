package com.sondo.music.video.ai.kuikly

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 安装渠道号（美团 Walle）。
 *
 * 参考工程里是 `WalleChannelReader.getChannel(applicationContext).orEmpty()`。Walle 的读取库只发布在
 * jcenter / 阿里云镜像，本工程的仓库里解析不到，而读取逻辑本身很小，所以这里按 Walle 的格式直接实现：
 * 渠道信息写在 APK Signing Block（v2 签名块）里 ID 为 0x71777777 的条目中，内容是 UTF-8 JSON：
 * `{"channel":"huawei", ...}`。打包流程（scripts/build_channels.sh：加固 → 签名 → walle put）不变。
 *
 * 未写渠道块（调试包 / 母包）时返回空串，由业务决定不带 App-Channel。
 */
object InstallChannel {

    private const val TAG = "InstallChannel"
    private const val WALLE_CHANNEL_BLOCK_ID = 0x71777777
    private const val CHANNEL_KEY = "channel"

    private const val APK_SIG_BLOCK_MAGIC_LO = 0x20676953204b5041L // "APK Sig "
    private const val APK_SIG_BLOCK_MAGIC_HI = 0x3234206b636f6c42L // "Block 42"
    private const val EOCD_SIGNATURE = 0x06054b50
    private const val EOCD_MIN_SIZE = 22
    private const val MAX_COMMENT_SIZE = 0xFFFF
    private const val MAX_SIG_BLOCK_SIZE = 64L * 1024 * 1024

    @Volatile
    private var cached: String? = null

    /** 渠道号；首次调用读一次 APK 尾部（几次随机读，毫秒级），之后走内存。 */
    fun get(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val value = try {
                read(context.applicationContext.applicationInfo.sourceDir)
            } catch (e: Throwable) {
                Log.w(TAG, "read channel failed", e)
                ""
            }
            cached = value
            return value
        }
    }

    private fun read(apkPath: String?): String {
        if (apkPath.isNullOrEmpty()) return ""
        RandomAccessFile(apkPath, "r").use { apk ->
            val centralDirOffset = findCentralDirOffset(apk)
            if (centralDirOffset < 32) return ""

            // 签名块尾部：uint64 blockSize + 16 字节 magic，紧贴在 Central Directory 之前
            val footer = readBuffer(apk, centralDirOffset - 24, 24)
            val blockSize = footer.getLong(0)
            if (footer.getLong(8) != APK_SIG_BLOCK_MAGIC_LO || footer.getLong(16) != APK_SIG_BLOCK_MAGIC_HI) return ""
            if (blockSize < 24 || blockSize > MAX_SIG_BLOCK_SIZE) return ""

            val blockStart = centralDirOffset - blockSize - 8
            if (blockStart < 0) return ""
            val block = readBuffer(apk, blockStart, (blockSize + 8).toInt())
            if (block.getLong(0) != blockSize) return ""

            // ID-value 序列：uint64 len | uint32 id | (len - 4) 字节 value
            var pos = 8
            val end = block.capacity() - 24
            while (pos + 12 <= end) {
                val len = block.getLong(pos)
                if (len < 4 || len > end - pos - 8) return ""
                val id = block.getInt(pos + 8)
                if (id == WALLE_CHANNEL_BLOCK_ID) {
                    val value = ByteArray((len - 4).toInt())
                    block.position(pos + 12)
                    block.get(value)
                    return parseChannel(String(value, Charsets.UTF_8))
                }
                pos += 8 + len.toInt()
            }
        }
        return ""
    }

    private fun parseChannel(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        return try {
            JSONObject(text).optString(CHANNEL_KEY, "")
        } catch (e: Throwable) {
            ""
        }
    }

    /** 从文件尾部找 End Of Central Directory，取出 Central Directory 的起始偏移。 */
    private fun findCentralDirOffset(apk: RandomAccessFile): Long {
        val fileSize = apk.length()
        if (fileSize < EOCD_MIN_SIZE) return -1
        val scanSize = minOf(fileSize, (EOCD_MIN_SIZE + MAX_COMMENT_SIZE).toLong()).toInt()
        val tail = readBuffer(apk, fileSize - scanSize, scanSize)
        var i = scanSize - EOCD_MIN_SIZE
        while (i >= 0) {
            if (tail.getInt(i) == EOCD_SIGNATURE) {
                val commentLength = tail.getShort(i + 20).toInt() and 0xFFFF
                if (commentLength == scanSize - i - EOCD_MIN_SIZE) {
                    return tail.getInt(i + 16).toLong() and 0xFFFFFFFFL
                }
            }
            i--
        }
        return -1
    }

    private fun readBuffer(file: RandomAccessFile, offset: Long, size: Int): ByteBuffer {
        val bytes = ByteArray(size)
        file.seek(offset)
        file.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    }
}
