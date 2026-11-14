package com.sondo.app.core.utils

import kotlin.math.max
import kotlin.math.min

enum class AudioKind { m4a, aac, mp3, wav, flac, ogg, unknown, mp4VideoOrOther }

/**
 * 按文件头魔数识别音频格式（不解码）：wav / flac / ogg / aac / mp3 / ISO-BMFF（纯音频 m4a）。
 *
 * 与参考工程的差异：原生文件模块只能读文件头，ISO-BMFF 的 moov 在文件尾部时无法确认，
 * 按「不确定即拒绝」返回 [AudioKind.mp4VideoOrOther]（参考工程会再读尾部 256KB）。
 */
object AudioSniffer {
    val defaultAllowedKinds: Set<AudioKind> = setOf(
        AudioKind.wav, AudioKind.mp3, AudioKind.m4a, AudioKind.aac, AudioKind.flac, AudioKind.ogg,
    )

    private const val INITIAL_READ = 16 * 1024 // 16KB：绝大多数格式足够
    private const val MP3_SCAN_MAX = 64 * 1024 // MP3 扩大扫描上限
    private const val BMFF_HEAD_READ = 256 * 1024 // ISO-BMFF 头部解析上限
    private const val ID3_EXTRA_SLACK = 4 * 1024 // ID3 tag 后额外读取一点余量

    suspend fun detectFromFile(path: String): AudioKind = onHost { detectFromFileImpl(path) }

    private suspend fun detectFromFileImpl(path: String): AudioKind {
        val file = HostNative.file
        val len = file.size(path)
        if (len < 12L) return AudioKind.unknown

        // 1) 小读 16KB 快速判定
        var head = readHead(path, min(len, INITIAL_READ.toLong()).toInt())
        if (head.size < 12) return AudioKind.unknown

        val quick = detectNonBmffAndNonMp3Quick(head)
        if (quick != AudioKind.unknown) return quick

        // 2) MP3：可能需要扩大读取（ID3 大 / 帧头不在 16KB 内）
        if (isLikelyMp3(head)) return AudioKind.mp3
        val id3End = id3EndOffsetIfPresent(head)
        if (id3End != null) {
            val need = min(len, max(MP3_SCAN_MAX.toLong(), min(len, (id3End + ID3_EXTRA_SLACK).toLong()))).toInt()
            if (need > head.size) {
                val expanded = readHead(path, need)
                if (isLikelyMp3(expanded)) return AudioKind.mp3
            }
            // ID3 很大但仍没找到帧头：大概率不是 MP3 或文件损坏，不再扩读
        } else {
            val need = min(len, MP3_SCAN_MAX.toLong()).toInt()
            if (need > head.size) {
                val expanded = readHead(path, need)
                if (isLikelyMp3(expanded)) return AudioKind.mp3
            }
        }

        // 3) ISO-BMFF（m4a / mp4）
        if (looksLikeIsoBmff(head)) {
            val want = min(len, BMFF_HEAD_READ.toLong()).toInt()
            if (head.size < want) head = readHead(path, want)
            return classifyIsoBmffAudioOnlyFromChunk(head) ?: AudioKind.mp4VideoOrOther
        }
        return AudioKind.unknown
    }

    fun detectFromBytes(bytes: ByteArray): AudioKind {
        if (bytes.size < 12) return AudioKind.unknown
        val quick = detectNonBmffAndNonMp3Quick(bytes)
        if (quick != AudioKind.unknown) return quick
        if (isLikelyMp3(bytes)) return AudioKind.mp3
        if (looksLikeIsoBmff(bytes)) return classifyIsoBmffAudioOnlyFromChunk(bytes) ?: AudioKind.mp4VideoOrOther
        return AudioKind.unknown
    }

    fun parseAllowedFormats(formats: Iterable<String>?, fallbackToDefaultIfEmpty: Boolean = true): Set<AudioKind> {
        if (formats == null) return defaultAllowedKinds
        val out = LinkedHashSet<AudioKind>()
        for (raw in formats) {
            when (raw.trim().lowercase()) {
                "m4a" -> out.add(AudioKind.m4a)
                "aac" -> out.add(AudioKind.aac)
                "mp3" -> out.add(AudioKind.mp3)
                "wav" -> out.add(AudioKind.wav)
                "flac" -> out.add(AudioKind.flac)
                "ogg" -> out.add(AudioKind.ogg)
            }
        }
        if (out.isEmpty() && fallbackToDefaultIfEmpty) return defaultAllowedKinds
        return out
    }

    /**
     * 文件头 → 扩展名（对应 FileUtils.getFileExtension 里 `mime` 包的魔数表，只覆盖音频）：
     * `ID3` / `FF FB` → mp3，`FF F1` / `FF F9` → aac。识别不了返回空串。
     */
    fun extensionFromHeader(header: ByteArray): String {
        if (header.size >= 3 && u(header, 0) == 0x49 && u(header, 1) == 0x44 && u(header, 2) == 0x33) return "mp3"
        if (header.size >= 2 && u(header, 0) == 0xFF) {
            when (u(header, 1)) {
                0xFB -> return "mp3"
                0xF1, 0xF9 -> return "aac"
            }
        }
        return ""
    }

    /** [AudioKind] 对应的上传扩展名；不可上传的类型返回空串。 */
    fun extensionOf(kind: AudioKind): String = when (kind) {
        AudioKind.mp3 -> "mp3"
        AudioKind.m4a -> "m4a"
        AudioKind.aac -> "aac"
        AudioKind.wav -> "wav"
        AudioKind.flac -> "flac"
        else -> ""
    }

    suspend fun readHead(path: String, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        return Base64.decode(HostNative.file.readHead(path, length))
    }

    // -------------------- 魔数：WAV / FLAC / OGG / AAC / MP3 --------------------

    private fun u(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    private fun detectNonBmffAndNonMp3Quick(b: ByteArray): AudioKind {
        if (isWav(b)) return AudioKind.wav
        if (isFlac(b)) return AudioKind.flac
        if (isOgg(b)) return AudioKind.ogg
        if (isAdtsAac(b)) return AudioKind.aac
        return AudioKind.unknown
    }

    private fun isWav(b: ByteArray): Boolean =
        b.size >= 12 && u(b, 0) == 0x52 && u(b, 1) == 0x49 && u(b, 2) == 0x46 && u(b, 3) == 0x46 && // RIFF
            u(b, 8) == 0x57 && u(b, 9) == 0x41 && u(b, 10) == 0x56 && u(b, 11) == 0x45 // WAVE

    private fun isFlac(b: ByteArray): Boolean =
        b.size >= 4 && u(b, 0) == 0x66 && u(b, 1) == 0x4C && u(b, 2) == 0x61 && u(b, 3) == 0x43 // fLaC

    private fun isOgg(b: ByteArray): Boolean =
        b.size >= 4 && u(b, 0) == 0x4F && u(b, 1) == 0x67 && u(b, 2) == 0x67 && u(b, 3) == 0x53 // OggS

    private fun isAdtsAac(b: ByteArray): Boolean {
        if (b.size < 2) return false
        if (u(b, 0) != 0xFF) return false
        val b1 = u(b, 1)
        if ((b1 and 0xF0) != 0xF0) return false
        val layer = (b1 shr 1) and 0x03
        return layer == 0x00
    }

    private fun id3EndOffsetIfPresent(bytes: ByteArray): Int? {
        if (bytes.size < 10) return null
        if (!(u(bytes, 0) == 0x49 && u(bytes, 1) == 0x44 && u(bytes, 2) == 0x33)) return null // "ID3"
        val b6 = u(bytes, 6)
        val b7 = u(bytes, 7)
        val b8 = u(bytes, 8)
        val b9 = u(bytes, 9)
        val isSynchsafe = ((b6 or b7 or b8 or b9) and 0x80) == 0
        if (!isSynchsafe) return null
        val tagSize = (b6 shl 21) or (b7 shl 14) or (b8 shl 7) or b9
        return 10 + tagSize
    }

    private fun isLikelyMp3(bytes: ByteArray): Boolean {
        if (bytes.size < 10) return false
        var start = 0
        val id3End = id3EndOffsetIfPresent(bytes)
        if (id3End != null && id3End < bytes.size - 4) start = id3End

        var i = start
        while (i < bytes.size - 4) {
            if (u(bytes, i) == 0xFF) {
                val b1 = u(bytes, i + 1)
                if ((b1 and 0xE0) == 0xE0) {
                    val b2 = u(bytes, i + 2)
                    val versionBits = (b1 shr 3) and 0x03 // 01 reserved
                    val layerBits = (b1 shr 1) and 0x03 // 01 LayerIII
                    val bitrateIndex = (b2 shr 4) and 0x0F // 1111 bad
                    val sampleIndex = (b2 shr 2) and 0x03 // 11 reserved
                    // 只认 Layer III = MP3
                    if (versionBits != 0x01 && layerBits == 0x01 && bitrateIndex != 0x0F && sampleIndex != 0x03) {
                        return true
                    }
                }
            }
            i++
        }
        return false
    }

    // -------------------- ISO-BMFF（m4a vs mp4 视频） --------------------

    private fun looksLikeIsoBmff(b: ByteArray): Boolean =
        b.size >= 12 && u(b, 4) == 0x66 && u(b, 5) == 0x74 && u(b, 6) == 0x79 && u(b, 7) == 0x70 // ftyp

    private class BoxRange(val type: String, val boxEnd: Int, val payloadStart: Int, val payloadEnd: Int)

    private fun classifyIsoBmffAudioOnlyFromChunk(bytes: ByteArray): AudioKind? {
        val moov = findBoxRange(bytes, 0, bytes.size, "moov") ?: return null
        var hasAudio = false
        var hasVideo = false
        var cursor = moov.payloadStart
        while (true) {
            val trak = findBoxRange(bytes, cursor, moov.payloadEnd, "trak") ?: break
            val mdia = findBoxRange(bytes, trak.payloadStart, trak.payloadEnd, "mdia")
            if (mdia != null) {
                val hdlr = findBoxRange(bytes, mdia.payloadStart, mdia.payloadEnd, "hdlr")
                // hdlr payload: version/flags(4) + pre_defined(4) + handler_type(4) + ...
                if (hdlr != null && hdlr.payloadEnd - hdlr.payloadStart >= 16) {
                    val handler = readAscii(bytes, hdlr.payloadStart + 8, 4)
                    if (handler == "soun") hasAudio = true
                    if (handler == "vide") hasVideo = true
                }
            }
            if (hasVideo) return AudioKind.mp4VideoOrOther
            cursor = trak.boxEnd
            if (cursor >= moov.payloadEnd) break
        }
        return if (hasAudio) AudioKind.m4a else null
    }

    private fun findBoxRange(bytes: ByteArray, start: Int, end: Int, type: String): BoxRange? {
        var off = start
        while (off + 8 <= end) {
            val box = readBoxRange(bytes, off, end) ?: return null
            if (box.type == type) return box
            off = box.boxEnd
        }
        return null
    }

    private fun readBoxRange(b: ByteArray, off: Int, end: Int): BoxRange? {
        if (off + 8 > end) return null
        val size = readUint32(b, off)
        val type = readAscii(b, off + 4, 4)
        var headerSize = 8
        var boxSize = size
        if (boxSize == 1L) {
            // 64-bit size
            if (off + 16 > end) return null
            boxSize = readUint32(b, off + 8) * 4294967296L + readUint32(b, off + 12)
            headerSize = 16
        } else if (boxSize == 0L) {
            boxSize = (end - off).toLong()
        }
        if (boxSize < headerSize) return null
        val boxEnd = off + boxSize
        if (boxEnd > end) return null
        return BoxRange(type, boxEnd.toInt(), off + headerSize, boxEnd.toInt())
    }

    private fun readUint32(b: ByteArray, off: Int): Long =
        (u(b, off).toLong() shl 24) or (u(b, off + 1).toLong() shl 16) or (u(b, off + 2).toLong() shl 8) or u(b, off + 3).toLong()

    private fun readAscii(b: ByteArray, off: Int, len: Int): String {
        val sb = StringBuilder(len)
        for (i in 0 until len) sb.append(u(b, off + i).toChar())
        return sb.toString()
    }
}
