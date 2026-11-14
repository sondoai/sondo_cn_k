package com.sondo.music.video.ai.kuikly.module

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 音频解码 / 归一化 / AAC 编码的拉模式小组件，供混流、裁剪、波形提取共用。
 * 全部是阻塞调用，只在后台线程使用；每个实例只在一个线程里用。
 */

internal const val SD_MIME_AAC = "audio/mp4a-latm"
private const val CODEC_TIMEOUT_US = 5_000L
/** 连续这么多轮（每轮约 10ms）没有任何输入输出进展就认为编解码器卡死。 */
private const val MAX_IDLE_ROUNDS = 1_500

/** 打开媒体文件：本地路径 / file:// / content:// / http(s)。 */
internal fun MediaExtractor.setSource(context: Context?, raw: String) {
    val local = SDMediaKit.localPath(raw)
    when {
        local != null -> setDataSource(local)
        raw.startsWith("content://") && context != null -> setDataSource(context, Uri.parse(raw), null)
        else -> setDataSource(raw)
    }
}

internal fun MediaExtractor.firstTrack(prefix: String): Int {
    for (i in 0 until trackCount) {
        val mime = getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith(prefix)) return i
    }
    return -1
}

internal fun MediaFormat.longOr(key: String, default: Long): Long =
    try {
        if (containsKey(key)) getLong(key) else default
    } catch (e: Throwable) {
        default
    }

internal fun MediaFormat.intOr(key: String, default: Int): Int =
    try {
        if (containsKey(key)) getInteger(key) else default
    } catch (e: Throwable) {
        default
    }

/**
 * 把任意音频文件解码成 16-bit 交错 PCM。
 *
 * @param startUs 起点（微秒），之前的采样被丢弃（精确到采样）
 * @param endUs   终点（微秒），<= 0 表示到文件结束
 */
internal class SDPcmReader(
    context: Context?,
    path: String,
    private val startUs: Long,
    private val endUs: Long,
) : Closeable {

    class Chunk(val data: ShortArray, val frames: Int, val positionUs: Long)

    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()
    private var rawBuffer: ByteBuffer? = null
    private var inputDone = false
    private var outputDone = false
    private var primed: Chunk? = null
    private var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

    var sampleRate = 0
        private set
    var channels = 0
        private set

    /** 音轨总时长（微秒），未知为 -1。 */
    var durationUs = -1L
        private set

    init {
        try {
            extractor.setSource(context, path)
            val track = extractor.firstTrack("audio/")
            if (track < 0) throw IOException("no_audio_track")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            durationUs = format.longOr(MediaFormat.KEY_DURATION, -1L)
            applyFormat(format)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                // WAV 等裸 PCM 不需要解码器
                rawBuffer = ByteBuffer.allocateDirect(maxOf(format.intOr(MediaFormat.KEY_MAX_INPUT_SIZE, 0), 256 * 1024))
            } else {
                val decoder = MediaCodec.createDecoderByType(mime)
                codec = decoder
                decoder.configure(format, null, null, 0)
                decoder.start()
            }
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    private fun applyFormat(format: MediaFormat) {
        sampleRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
        channels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
        pcmEncoding = format.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
    }

    /** 先解出第一块数据，确保 [sampleRate] / [channels] 是解码器实际输出的格式（HE-AAC 等会与容器声明不同）。 */
    fun prime() {
        if (primed == null && !outputDone) primed = decodeNext()
        if (sampleRate <= 0 || channels <= 0) throw IOException("unknown_audio_format")
    }

    /** 下一块 PCM；结束返回 null。 */
    fun next(): Chunk? {
        primed?.let {
            primed = null
            return it
        }
        return decodeNext()
    }

    private fun decodeNext(): Chunk? {
        val decoder = codec
        if (decoder == null) return readRaw()
        var idle = 0
        while (!outputDone) {
            var progressed = false
            if (!inputDone) {
                val inIdx = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inIdx >= 0) {
                    progressed = true
                    val buf = decoder.getInputBuffer(inIdx) ?: throw IOException("decoder_input_null")
                    val size = extractor.readSampleData(buf, 0)
                    val time = extractor.sampleTime
                    if (size < 0 || (endUs > 0 && time >= endUs)) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, size, time, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                applyFormat(decoder.outputFormat)
                progressed = true
            } else if (outIdx >= 0) {
                progressed = true
                var chunk: Chunk? = null
                if (info.size > 0) {
                    val buf = decoder.getOutputBuffer(outIdx)
                    if (buf != null) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        chunk = convert(buf, info.presentationTimeUs)
                    }
                }
                decoder.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                if (chunk != null) return chunk
            }
            if (progressed) idle = 0 else if (++idle > MAX_IDLE_ROUNDS) throw IOException("decoder_stalled")
        }
        return null
    }

    private fun readRaw(): Chunk? {
        val buf = rawBuffer ?: return null
        while (!outputDone) {
            buf.clear()
            val size = extractor.readSampleData(buf, 0)
            if (size < 0) {
                outputDone = true
                return null
            }
            val time = extractor.sampleTime
            extractor.advance()
            buf.position(0)
            buf.limit(size)
            val chunk = convert(buf, time)
            if (chunk != null) return chunk
        }
        return null
    }

    /** 字节 → 16-bit 采样，并按 [startUs] / [endUs] 精确裁剪。裁完没有数据返回 null。 */
    private fun convert(buf: ByteBuffer, ptsUs: Long): Chunk? {
        val ch = channels
        val sr = sampleRate
        if (ch <= 0 || sr <= 0) return null
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerSample = when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            ENCODING_PCM_24BIT_PACKED -> 3
            ENCODING_PCM_32BIT -> 4
            else -> 2
        }
        var frames = buf.remaining() / (bytesPerSample * ch)
        if (frames <= 0) return null
        var skip = 0
        if (startUs > 0 && ptsUs < startUs) {
            skip = ((startUs - ptsUs) * sr / 1_000_000L).toInt().coerceAtMost(frames)
        }
        if (endUs > 0) {
            if (ptsUs >= endUs) {
                outputDone = true
                return null
            }
            val allowed = ((endUs - ptsUs) * sr / 1_000_000L).toInt()
            if (allowed < frames) {
                frames = allowed.coerceAtLeast(0)
                outputDone = true
            }
        }
        val count = frames - skip
        if (count <= 0) return null
        val out = ShortArray(count * ch)
        val base = buf.position() + skip * ch * bytesPerSample
        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_8BIT -> {
                for (i in out.indices) out[i] = (((buf.get(base + i).toInt() and 0xFF) - 128) shl 8).toShort()
            }
            AudioFormat.ENCODING_PCM_FLOAT -> {
                for (i in out.indices) {
                    val v = (buf.getFloat(base + i * 4) * 32767f).roundToInt()
                    out[i] = v.coerceIn(-32768, 32767).toShort()
                }
            }
            ENCODING_PCM_24BIT_PACKED -> {
                for (i in out.indices) {
                    val p = base + i * 3
                    out[i] = ((buf.get(p + 2).toInt() shl 8) or (buf.get(p + 1).toInt() and 0xFF)).toShort()
                }
            }
            ENCODING_PCM_32BIT -> {
                for (i in out.indices) out[i] = (buf.getInt(base + i * 4) shr 16).toShort()
            }
            else -> {
                buf.position(base)
                buf.asShortBuffer().get(out)
            }
        }
        val position = ptsUs + skip * 1_000_000L / sr
        return Chunk(out, count, position)
    }

    override fun close() {
        try {
            codec?.stop()
        } catch (e: Throwable) {
            // ignore
        }
        try {
            codec?.release()
        } catch (e: Throwable) {
            // ignore
        }
        codec = null
        try {
            extractor.release()
        } catch (e: Throwable) {
            // ignore
        }
    }

    private companion object {
        // AudioFormat.ENCODING_PCM_24BIT_PACKED / ENCODING_PCM_32BIT（API 31）
        const val ENCODING_PCM_24BIT_PACKED = 21
        const val ENCODING_PCM_32BIT = 22
    }
}

/**
 * 把 PCM 规整成 AAC 编码器能接受的格式：最多 2 声道、采样率落在 AAC-LC 支持的档位上
 * （不在档位上的用线性插值重采样）。
 */
internal class SDPcmNormalizer(private val inRate: Int, private val inChannels: Int) {

    val outChannels: Int = if (inChannels >= 2) 2 else 1
    val outRate: Int = pickRate(inRate)

    private val resample = outRate != inRate
    private val step = inRate.toDouble() / outRate
    private var srcPos = 0.0
    private val prev = ShortArray(outChannels)
    private var hasPrev = false

    fun process(input: ShortArray, frames: Int): ShortArray {
        if (frames <= 0) return EMPTY
        val mixed = if (inChannels > 2) downmix(input, frames) else input
        if (!resample) {
            return if (mixed.size == frames * outChannels) mixed else mixed.copyOf(frames * outChannels)
        }
        val ch = outChannels
        val estimate = ((frames + 1) / step).toInt() + 2
        var out = ShortArray(estimate * ch)
        var n = 0
        while (true) {
            val i = floor(srcPos).toInt()
            if (i + 1 >= frames) break
            if (i < 0 && !hasPrev) {
                srcPos = 0.0
                continue
            }
            val frac = srcPos - i
            if ((n + 1) * ch > out.size) out = out.copyOf(out.size * 2)
            for (c in 0 until ch) {
                val a = if (i < 0) prev[c].toInt() else mixed[i * ch + c].toInt()
                val b = mixed[(i + 1) * ch + c].toInt()
                out[n * ch + c] = (a + (b - a) * frac).roundToInt().coerceIn(-32768, 32767).toShort()
            }
            n++
            srcPos += step
        }
        srcPos -= frames
        for (c in 0 until ch) prev[c] = mixed[(frames - 1) * ch + c]
        hasPrev = true
        return if (n * ch == out.size) out else out.copyOf(n * ch)
    }

    /** 多声道 → 立体声：前置左右 + 中置 / 环绕各按 0.707 混入，整体衰减防止削波；LFE 丢弃。 */
    private fun downmix(input: ShortArray, frames: Int): ShortArray {
        val out = ShortArray(frames * 2)
        val n = inChannels
        var weight = 1.0
        if (n >= 3) weight += 0.707
        if (n >= 5) weight += 0.707
        val gain = 1.0 / weight
        for (f in 0 until frames) {
            val base = f * n
            var l = input[base].toDouble()
            var r = input[base + 1].toDouble()
            if (n >= 3) {
                val c = input[base + 2] * 0.707
                l += c
                r += c
            }
            var ch = 4
            while (ch < n) {
                if (ch % 2 == 0) l += input[base + ch] * 0.707 else r += input[base + ch] * 0.707
                ch++
            }
            out[f * 2] = (l * gain).roundToInt().coerceIn(-32768, 32767).toShort()
            out[f * 2 + 1] = (r * gain).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private companion object {
        val EMPTY = ShortArray(0)
        val AAC_RATES = intArrayOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)

        fun pickRate(rate: Int): Int {
            if (AAC_RATES.contains(rate)) return rate
            if (rate > 48000) return 48000
            var best = AAC_RATES[0]
            for (r in AAC_RATES) if (kotlin.math.abs(r - rate) < kotlin.math.abs(best - rate)) best = r
            return best
        }
    }
}

/** AAC-LC 编码器（MediaCodec）。时间戳按已送入的采样数计算，从 0 开始。 */
internal class SDAacEncoder(private val sampleRate: Int, private val channels: Int, bitRate: Int) : Closeable {

    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var framesIn = 0L
    private var eosQueued = false

    val endSignalled: Boolean get() = eosQueued

    /** INFO_OUTPUT_FORMAT_CHANGED 之后可用（带 csd-0），加到 MediaMuxer 用它。 */
    var outputFormat: MediaFormat? = null
        private set
    var finished = false
        private set
    var lastPtsUs = 0L
        private set

    private class Pending(val data: ByteArray, val ptsUs: Long, val flags: Int)

    private val pendingOutputs = ArrayList<Pending>()

    init {
        val format = MediaFormat.createAudioFormat(SD_MIME_AAC, sampleRate, channels)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        val encoder = MediaCodec.createEncoderByType(SD_MIME_AAC)
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
        } catch (e: Throwable) {
            try {
                encoder.release()
            } catch (ignored: Throwable) {
            }
            throw e
        }
        codec = encoder
    }

    /** 送入 PCM（交错 16-bit）。返回本次消耗的采样个数（不是帧数），编码器暂时没有空闲缓冲时为 0。 */
    fun feed(pcm: ShortArray, offset: Int, count: Int): Int {
        if (eosQueued || count <= 0) return 0
        val idx = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
        if (idx < 0) return 0
        val buf = codec.getInputBuffer(idx) ?: throw IOException("encoder_input_null")
        buf.clear()
        var n = minOf(count, buf.capacity() / 2)
        n -= n % channels
        if (n <= 0) {
            codec.queueInputBuffer(idx, 0, 0, ptsUs(), 0)
            return 0
        }
        buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, offset, n)
        codec.queueInputBuffer(idx, 0, n * 2, ptsUs(), 0)
        framesIn += n / channels
        return n
    }

    /** 通知输入结束。返回本次是否成功排入 EOS（编码器暂时没有空闲缓冲时为 false，稍后重试）。 */
    fun signalEnd(): Boolean {
        if (eosQueued) return false
        val idx = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
        if (idx < 0) return false
        codec.queueInputBuffer(idx, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        eosQueued = true
        return true
    }

    /**
     * 取编码输出。[sink] 为 null 表示复用器还没启动：此时只等输出格式，已出来的数据暂存，
     * 等下次带 sink 调用时先补发。返回本次是否有进展。
     */
    fun drain(sink: ((ByteBuffer, MediaCodec.BufferInfo) -> Unit)?): Boolean {
        var progressed = false
        if (sink != null && pendingOutputs.isNotEmpty()) {
            for (p in pendingOutputs) {
                val bi = MediaCodec.BufferInfo()
                bi.set(0, p.data.size, p.ptsUs, p.flags)
                sink(ByteBuffer.wrap(p.data), bi)
                lastPtsUs = p.ptsUs
            }
            pendingOutputs.clear()
            progressed = true
        }
        if (sink == null && outputFormat != null) return progressed
        while (!finished) {
            val idx = codec.dequeueOutputBuffer(info, if (progressed) 0L else CODEC_TIMEOUT_US)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outputFormat = codec.outputFormat
                progressed = true
                if (sink == null) break
                continue
            }
            if (idx < 0) break
            progressed = true
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (info.size > 0 && !isConfig) {
                val buf = codec.getOutputBuffer(idx)
                if (buf != null) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val flags = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
                    if (sink != null) {
                        val bi = MediaCodec.BufferInfo()
                        bi.set(info.offset, info.size, info.presentationTimeUs, flags)
                        sink(buf, bi)
                        lastPtsUs = info.presentationTimeUs
                    } else {
                        val copy = ByteArray(info.size)
                        buf.get(copy)
                        pendingOutputs.add(Pending(copy, info.presentationTimeUs, flags))
                    }
                }
            }
            codec.releaseOutputBuffer(idx, false)
            if (eos) finished = true
        }
        return progressed
    }

    private fun ptsUs(): Long = framesIn * 1_000_000L / sampleRate

    override fun close() {
        try {
            codec.stop()
        } catch (e: Throwable) {
            // ignore
        }
        try {
            codec.release()
        } catch (e: Throwable) {
            // ignore
        }
    }
}

/**
 * 解码 → 规整 → AAC 编码 的一条流水线。调用方反复 [step] 推进，直到 [finished]。
 * 编码输出的时间戳从 0 起算（即已经减去了裁剪起点）。
 */
internal class SDAudioTranscoder(private val reader: SDPcmReader) : Closeable {

    private val normalizer: SDPcmNormalizer
    private val encoder: SDAacEncoder
    private var pending: ShortArray? = null
    private var pendingOffset = 0
    private var readerDone = false
    private var idle = 0

    val outputFormat: MediaFormat? get() = encoder.outputFormat
    val finished: Boolean get() = encoder.finished
    val lastPtsUs: Long get() = encoder.lastPtsUs

    init {
        reader.prime()
        normalizer = SDPcmNormalizer(reader.sampleRate, reader.channels)
        // 与 ffmpeg 内置 aac 编码器的默认码率（128k）一致，单声道减半略留余量
        val bitRate = if (normalizer.outChannels >= 2) 128_000 else 96_000
        encoder = SDAacEncoder(normalizer.outRate, normalizer.outChannels, bitRate)
    }

    fun step(sink: ((ByteBuffer, MediaCodec.BufferInfo) -> Unit)?) {
        var progressed = false
        if (pending == null && !readerDone) {
            val chunk = reader.next()
            if (chunk == null) {
                readerDone = true
            } else {
                val data = normalizer.process(chunk.data, chunk.frames)
                if (data.isNotEmpty()) {
                    pending = data
                    pendingOffset = 0
                }
            }
            progressed = true
        }
        val data = pending
        if (data != null) {
            val consumed = encoder.feed(data, pendingOffset, data.size - pendingOffset)
            if (consumed > 0) {
                progressed = true
                pendingOffset += consumed
                if (pendingOffset >= data.size) pending = null
            }
        } else if (readerDone && !encoder.endSignalled) {
            if (encoder.signalEnd()) progressed = true
        }
        if (encoder.drain(sink)) progressed = true
        if (progressed) idle = 0 else if (++idle > MAX_IDLE_ROUNDS) throw IOException("encoder_stalled")
    }

    override fun close() {
        encoder.close()
    }
}
