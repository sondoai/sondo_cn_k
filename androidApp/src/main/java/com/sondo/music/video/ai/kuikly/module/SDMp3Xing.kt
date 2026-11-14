package com.sondo.music.video.ai.kuikly.module

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * 纯 Kotlin 的 MP3 帧扫描与 Xing 头重写，对应参考工程的
 * `ffmpeg -i in -map 0:a:0 -map_metadata 0 -c:a copy -write_xing 1 out.mp3`。
 *
 * 用途：VBR MP3 没有（或带着错误的）Xing/Info 头时，播放器只能按首帧码率估算时长，
 * 上传前重写一遍头，时长就是精确的。音频帧原样拷贝，不重新编码。
 *
 * 处理流程：
 * 1. 跳过并保留文件开头的 ID3v2 标签；
 * 2. 逐帧解析（要求连续帧的版本 / 层 / 采样率一致），统计帧数、字节数、每帧偏移；
 * 3. 首帧若是 Xing / Info / VBRI 头帧则剔除；
 * 4. 写入新的头帧：帧数、字节数、100 项 TOC。原头帧带完整 LAME 扩展时在原帧上改写
 *    （保留 encoder delay / padding 等无缝播放信息，并重算标签 CRC），否则新建一个静音帧；
 * 5. 原样拷贝音频帧（帧间夹杂的垃圾数据丢弃），以及文件尾部的 ID3v1 / APE / Lyrics3 标签。
 *
 * 只支持 Layer III；解析不出合法帧序列时抛 [IOException]，调用方保留原文件。
 * 本文件只依赖 java.io，可脱离 Android 运行。
 */
internal object SDMp3Xing {

    class Info(
        val frames: Int,
        val durationMs: Long,
        val sampleRate: Int,
        val channels: Int,
        val vbr: Boolean,
        val hadHeaderFrame: Boolean,
    )

    private const val WINDOW = 256 * 1024
    private const val FIRST_SYNC_SCAN_LIMIT = 1024L * 1024
    private const val RESYNC_SCAN_LIMIT = 128L * 1024
    private const val LAME_EXT_SIZE = 36

    private class Header(
        val raw: Int,
        val version: Int,      // 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5
        val layer: Int,        // 1 / 2 / 3
        val bitrate: Int,      // bps
        val sampleRate: Int,
        val channels: Int,
        val frameLength: Int,
        val samplesPerFrame: Int,
    ) {
        /** 帧头 4 字节之后到 Xing 标识的距离（side info 长度）。 */
        val sideInfoSize: Int
            get() = if (version == 3) (if (channels == 1) 17 else 32) else (if (channels == 1) 9 else 17)

        fun compatibleWith(other: Header): Boolean =
            version == other.version && layer == other.layer && sampleRate == other.sampleRate
    }

    private val BITRATES_V1_L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
    private val BITRATES_V1_L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
    private val BITRATES_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val BITRATES_V2_L1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
    private val BITRATES_V2_L23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private val SAMPLE_RATES_V1 = intArrayOf(44100, 48000, 32000)
    private val SAMPLE_RATES_V2 = intArrayOf(22050, 24000, 16000)
    private val SAMPLE_RATES_V25 = intArrayOf(11025, 12000, 8000)

    private fun parseHeader(h: Int): Header? {
        if ((h ushr 21) and 0x7FF != 0x7FF) return null
        val version = (h ushr 19) and 3
        if (version == 1) return null
        val layerBits = (h ushr 17) and 3
        if (layerBits == 0) return null
        val layer = 4 - layerBits
        val bitrateIdx = (h ushr 12) and 0xF
        if (bitrateIdx == 0 || bitrateIdx == 15) return null   // free format 不支持
        val srIdx = (h ushr 10) and 3
        if (srIdx == 3) return null
        if (h and 3 == 2) return null                          // emphasis 保留值
        val padding = (h ushr 9) and 1
        val channels = if ((h ushr 6) and 3 == 3) 1 else 2
        val table = if (version == 3) {
            when (layer) {
                1 -> BITRATES_V1_L1
                2 -> BITRATES_V1_L2
                else -> BITRATES_V1_L3
            }
        } else {
            if (layer == 1) BITRATES_V2_L1 else BITRATES_V2_L23
        }
        val bitrate = table[bitrateIdx] * 1000
        val sampleRate = when (version) {
            3 -> SAMPLE_RATES_V1[srIdx]
            2 -> SAMPLE_RATES_V2[srIdx]
            else -> SAMPLE_RATES_V25[srIdx]
        }
        val frameLength: Int
        val samples: Int
        when (layer) {
            1 -> {
                frameLength = (12 * bitrate / sampleRate + padding) * 4
                samples = 384
            }
            2 -> {
                frameLength = 144 * bitrate / sampleRate + padding
                samples = 1152
            }
            else -> {
                if (version == 3) {
                    frameLength = 144 * bitrate / sampleRate + padding
                    samples = 1152
                } else {
                    frameLength = 72 * bitrate / sampleRate + padding
                    samples = 576
                }
            }
        }
        if (frameLength < 24) return null
        return Header(h, version, layer, bitrate, sampleRate, channels, frameLength, samples)
    }

    /** 带滑动窗口的随机读取，顺序扫描时基本不产生额外 IO。 */
    private class Reader(private val raf: RandomAccessFile) {
        val length: Long = raf.length()
        private val buf = ByteArray(WINDOW)
        private var bufStart = 0L
        private var bufLen = 0

        fun u8(pos: Long): Int {
            if (pos < 0 || pos >= length) return -1
            if (pos < bufStart || pos >= bufStart + bufLen) fill(pos)
            if (pos >= bufStart + bufLen) return -1
            return buf[(pos - bufStart).toInt()].toInt() and 0xFF
        }

        fun be32(pos: Long): Int {
            val a = u8(pos)
            val b = u8(pos + 1)
            val c = u8(pos + 2)
            val d = u8(pos + 3)
            if (a < 0 || b < 0 || c < 0 || d < 0) return 0
            return (a shl 24) or (b shl 16) or (c shl 8) or d
        }

        fun startsWith(pos: Long, text: String): Boolean {
            for (i in text.indices) if (u8(pos + i) != text[i].code) return false
            return true
        }

        fun read(pos: Long, out: ByteArray, len: Int) {
            raf.seek(pos)
            raf.readFully(out, 0, len)
        }

        private fun fill(pos: Long) {
            bufStart = pos
            raf.seek(pos)
            var total = 0
            while (total < buf.size) {
                val n = raf.read(buf, total, buf.size - total)
                if (n <= 0) break
                total += n
            }
            bufLen = total
        }

        fun copy(from: Long, to: Long, out: OutputStream) {
            if (to <= from) return
            val chunk = ByteArray(64 * 1024)
            raf.seek(from)
            var remaining = to - from
            while (remaining > 0) {
                val n = raf.read(chunk, 0, minOf(chunk.size.toLong(), remaining).toInt())
                if (n <= 0) throw IOException("unexpected_eof")
                out.write(chunk, 0, n)
                remaining -= n
            }
            // 拷贝移动了文件指针，窗口缓存作废
            bufLen = 0
        }
    }

    private class Scan(
        val id3End: Long,
        /** 连续的合法帧区间：[start0, end0, start1, end1, …]（源文件偏移）。 */
        val segments: LongArray,
        val segmentCount: Int,
        /** 全部音频帧的总字节数（不含头帧、不含帧间垃圾）。 */
        val audioBytes: Long,
        val trailerStart: Long,
        val first: Header,
        val frames: Int,
        /** 每帧在「输出音频流」里的偏移（相对首个音频帧）。 */
        val offsets: LongArray?,
        val vbr: Boolean,
        val headerFrameStart: Long,
        val headerFrame: Header?,
    )

    private fun headerAt(r: Reader, pos: Long): Header? {
        if (pos + 4 > r.length) return null
        if (r.u8(pos) != 0xFF) return null
        return parseHeader(r.be32(pos))
    }

    private fun isTrailerTag(r: Reader, pos: Long): Boolean =
        r.startsWith(pos, "TAG") || r.startsWith(pos, "APETAGEX") || r.startsWith(pos, "LYRICSBEGIN")

    /** 从 [from] 起找一个「后面还跟着同规格帧」的帧头。 */
    private fun findSync(r: Reader, from: Long, limit: Long, ref: Header?): Long {
        var p = from
        val end = minOf(limit, r.length - 4)
        while (p <= end) {
            if (r.u8(p) == 0xFF && (r.u8(p + 1) and 0xE0) == 0xE0) {
                val h = headerAt(r, p)
                if (h != null && (ref == null || ref.compatibleWith(h)) && chainOk(r, p, h)) return p
            }
            p++
        }
        return -1L
    }

    /** 连续校验后续两帧（或恰好到达文件尾 / 尾部标签）。 */
    private fun chainOk(r: Reader, pos: Long, h: Header): Boolean {
        var p = pos + h.frameLength
        var prev = h
        repeat(2) {
            if (p >= r.length || isTrailerTag(r, p)) return true
            val next = headerAt(r, p) ?: return false
            if (!prev.compatibleWith(next)) return false
            prev = next
            p += next.frameLength
        }
        return true
    }

    private fun isHeaderFrame(r: Reader, pos: Long, h: Header): Boolean {
        if (h.layer != 3) return false
        val off = pos + 4 + h.sideInfoSize
        for (delta in intArrayOf(0, 2)) {
            if (r.startsWith(off + delta, "Xing") || r.startsWith(off + delta, "Info")) return true
        }
        return r.startsWith(pos + 4 + 32, "VBRI")
    }

    private fun scan(r: Reader, collectOffsets: Boolean): Scan {
        val len = r.length
        var pos = 0L
        // 开头的 ID3v2（可能连续多个）
        while (pos + 10 <= len && r.startsWith(pos, "ID3")) {
            val s0 = r.u8(pos + 6)
            val s1 = r.u8(pos + 7)
            val s2 = r.u8(pos + 8)
            val s3 = r.u8(pos + 9)
            if (s0 >= 0x80 || s1 >= 0x80 || s2 >= 0x80 || s3 >= 0x80) break
            val size = (s0 shl 21) or (s1 shl 14) or (s2 shl 7) or s3
            val footer = if (r.u8(pos + 5) and 0x10 != 0) 10 else 0
            pos += 10L + size + footer
        }
        if (pos >= len) throw IOException("mp3_no_audio")
        val id3End = pos

        val firstPos = findSync(r, pos, pos + FIRST_SYNC_SCAN_LIMIT, null)
        if (firstPos < 0) throw IOException("mp3_sync_not_found")
        var ref = headerAt(r, firstPos) ?: throw IOException("mp3_sync_not_found")
        if (ref.layer != 3) throw IOException("mp3_unsupported_layer")

        var p = firstPos
        var headerFrameStart = -1L
        var headerFrame: Header? = null
        if (isHeaderFrame(r, firstPos, ref)) {
            headerFrameStart = firstPos
            headerFrame = ref
            p = firstPos + ref.frameLength
            ref = headerAt(r, p) ?: throw IOException("mp3_no_audio")
            if (ref.layer != 3) throw IOException("mp3_unsupported_layer")
        }

        var offsets = if (collectOffsets) LongArray(16 * 1024) else null
        var frames = 0
        var firstBitrate = -1
        var vbr = false
        var segments = LongArray(16)
        var segmentCount = 0
        var segStart = -1L
        var segEnd = -1L
        var audioBytes = 0L
        var trailerStart = -1L
        while (p + 4 <= len) {
            val h = headerAt(r, p)
            if (h == null || !ref.compatibleWith(h)) {
                if (isTrailerTag(r, p)) {
                    trailerStart = p
                    break
                }
                // 帧间夹了垃圾数据：向后重新同步，找不到就到此为止
                val next = findSync(r, p + 1, p + RESYNC_SCAN_LIMIT, ref)
                if (next < 0) break
                p = next
                continue
            }
            if (p + h.frameLength > len) break   // 末尾被截断的半帧
            if (offsets != null) {
                if (frames == offsets.size) offsets = offsets.copyOf(offsets.size * 2)
                offsets[frames] = audioBytes
            }
            if (p != segEnd) {
                // 新的连续区间（开头，或刚跳过一段垃圾数据）
                if (segStart >= 0) {
                    if (segmentCount * 2 == segments.size) segments = segments.copyOf(segments.size * 2)
                    segments[segmentCount * 2] = segStart
                    segments[segmentCount * 2 + 1] = segEnd
                    segmentCount++
                }
                segStart = p
            }
            frames++
            if (firstBitrate < 0) firstBitrate = h.bitrate else if (h.bitrate != firstBitrate) vbr = true
            p += h.frameLength
            segEnd = p
            audioBytes += h.frameLength
        }
        if (segStart >= 0) {
            if (segmentCount * 2 == segments.size) segments = segments.copyOf(segments.size * 2)
            segments[segmentCount * 2] = segStart
            segments[segmentCount * 2 + 1] = segEnd
            segmentCount++
        }
        if (trailerStart < 0 && segEnd in 0 until len && isTrailerTag(r, segEnd)) trailerStart = segEnd
        if (frames < 2) throw IOException("mp3_too_few_frames")
        return Scan(
            id3End, segments, segmentCount, audioBytes, trailerStart, ref, frames, offsets, vbr,
            headerFrameStart, headerFrame,
        )
    }

    /** 只扫描不改写：给出精确帧数与时长。不是合法 MP3 时返回 null。 */
    fun inspect(file: File): Info? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val s = scan(Reader(raf), false)
                toInfo(s)
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun toInfo(s: Scan): Info = Info(
        frames = s.frames,
        durationMs = s.frames.toLong() * s.first.samplesPerFrame * 1000L / s.first.sampleRate,
        sampleRate = s.first.sampleRate,
        channels = s.first.channels,
        vbr = s.vbr,
        hadHeaderFrame = s.headerFrame != null,
    )

    /** 重写 Xing 头并输出到 [dest]（先写临时文件再改名）。失败抛 [IOException]。 */
    fun remux(src: File, dest: File): Info {
        if (!src.isFile || src.length() < 128) throw IOException("mp3_file_not_found")
        if (src.canonicalPath == dest.canonicalPath) throw IOException("mp3_same_file")
        dest.parentFile?.let { if (!it.exists()) it.mkdirs() }
        val tmp = File(dest.parentFile, dest.name + ".part")
        try {
            val info = RandomAccessFile(src, "r").use { raf ->
                val r = Reader(raf)
                val s = scan(r, true)
                val offsets = s.offsets ?: throw IOException("mp3_internal")
                val headerBytes = buildHeaderFrame(r, s, offsets)
                FileOutputStream(tmp).use { fos ->
                    val out = fos.buffered(64 * 1024)
                    r.copy(0L, s.id3End, out)
                    out.write(headerBytes)
                    for (i in 0 until s.segmentCount) r.copy(s.segments[i * 2], s.segments[i * 2 + 1], out)
                    if (s.trailerStart >= 0) r.copy(s.trailerStart, r.length, out)
                    out.flush()
                }
                toInfo(s)
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("mp3_rename_failed")
            return info
        } catch (e: Throwable) {
            tmp.delete()
            if (e is IOException) throw e
            throw IOException(e.message ?: e.javaClass.simpleName, e)
        }
    }

    private fun buildHeaderFrame(r: Reader, s: Scan, offsets: LongArray): ByteArray {
        val audioBytes = s.audioBytes
        val frame = reuseOriginalHeaderFrame(r, s) ?: newHeaderFrame(s)
        val tagOffset = frame.tagOffset
        val bytes = frame.data
        val total = bytes.size + audioBytes
        if (total > 0xFFFFFFFFL) throw IOException("mp3_too_large")

        // "Xing" 用于 VBR，"Info" 用于 CBR（与 LAME / ffmpeg 一致）；复用原帧时保留原标识
        if (!frame.reused) putAscii(bytes, tagOffset, if (s.vbr) "Xing" else "Info")
        putBe32(bytes, tagOffset + 4, 0x0F)                 // frames | bytes | TOC | quality
        putBe32(bytes, tagOffset + 8, s.frames)
        putBe32(bytes, tagOffset + 12, total.toInt())
        for (i in 0 until 100) {
            val idx = (i.toLong() * s.frames / 100).toInt().coerceAtMost(s.frames - 1)
            val bytePos = bytes.size + offsets[idx]
            bytes[tagOffset + 16 + i] = (bytePos * 256 / total).coerceIn(0L, 255L).toByte()
        }
        if (frame.reused && frame.hasLameExt) {
            // LAME 扩展里的「音乐长度」与标签 CRC 需要跟着更新
            val ext = tagOffset + 120
            putBe32(bytes, ext + 28, total.toInt())
            val crc = crc16(bytes, ext + 34)
            bytes[ext + 34] = (crc ushr 8).toByte()
            bytes[ext + 35] = crc.toByte()
        }
        return bytes
    }

    private class HeaderFrame(val data: ByteArray, val tagOffset: Int, val reused: Boolean, val hasLameExt: Boolean)

    /**
     * 原文件的 Xing/Info 头帧带齐四个字段且留有 LAME 扩展区时，在原帧上改写，
     * 这样 encoder delay / padding、ReplayGain 等信息不会丢。
     */
    private fun reuseOriginalHeaderFrame(r: Reader, s: Scan): HeaderFrame? {
        val h = s.headerFrame ?: return null
        if (!h.compatibleWith(s.first) || h.channels != s.first.channels) return null
        val start = s.headerFrameStart
        val tagOffset = 4 + h.sideInfoSize
        if (!r.startsWith(start + tagOffset, "Xing") && !r.startsWith(start + tagOffset, "Info")) return null
        if (r.be32(start + tagOffset + 4) and 0x0F != 0x0F) return null
        if (h.frameLength < tagOffset + 120 + LAME_EXT_SIZE) return null
        val data = ByteArray(h.frameLength)
        r.read(start, data, data.size)
        val ext = tagOffset + 120
        val encoder = String(data, ext, 4, Charsets.ISO_8859_1)
        val hasLameExt = encoder == "LAME" || encoder == "Lavf" || encoder == "Lavc" || encoder.startsWith("L3.9")
        return HeaderFrame(data, tagOffset, true, hasLameExt)
    }

    /** 新建头帧：沿用首个音频帧的版本 / 采样率 / 声道模式，选能放下标签的最小码率，side info 全 0（静音帧）。 */
    private fun newHeaderFrame(s: Scan): HeaderFrame {
        val first = s.first
        val tagOffset = 4 + first.sideInfoSize
        val needed = tagOffset + 120 + LAME_EXT_SIZE
        // 保留版本 / 层 / 采样率 / 声道模式；去掉 CRC（保护位=1）、padding、private 位
        val base = (first.raw and 0xFFFE0CC0.toInt()) or 0x00010000
        for (idx in 1..14) {
            val candidate = parseHeader(base or (idx shl 12)) ?: continue
            if (candidate.frameLength >= needed) {
                val data = ByteArray(candidate.frameLength)
                putBe32(data, 0, candidate.raw)
                return HeaderFrame(data, tagOffset, false, false)
            }
        }
        throw IOException("mp3_header_frame_too_small")
    }

    private fun putBe32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    private fun putAscii(b: ByteArray, off: Int, text: String) {
        for (i in text.indices) b[off + i] = text[i].code.toByte()
    }

    /** CRC-16/ARC（多项式 0xA001，初值 0），LAME 标签校验用。 */
    private fun crc16(data: ByteArray, length: Int): Int {
        var crc = 0
        for (i in 0 until length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }
}
