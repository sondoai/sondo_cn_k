package com.sondo.app.ui.works.cropper

import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.Uuid
import com.sondo.app.nativeapi.SDMediaProcessModule
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.event.layoutFrameDidChange
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Canvas
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

class SDAudioWaveformAttr : ComposeAttr() {
    /** obs：本地音频文件路径；为空时只占位。 */
    var audioPath: String? by observable(null)
    /** 时长（毫秒），用于估算抽取分辨率。 */
    var durationMs: Long? = null
    var waveColor: Color = Color(0xFF797A7AL)
    var strokeWidth: Float = 2f
    var pixelsPerStep: Float = 4f
}

/**
 * Dart `AudioWaveform`：本地音频的静态波形（不播放、不 seek）。
 * 峰值来自 `SDMediaProcessModule.extractWaveform`（每项 0..1），按整段峰值归一化后绕中线对称绘制，每 [pixelsPerStep] 一根圆头竖线。
 * 抽取失败 / `ok:false` / 无数据时画与 Dart 相同的确定性「歌曲形」占位波形（峰值只占 72%）；`approximate:true` 的结果照常绘制。
 * 尺寸写在根节点上（`size(w, h)` 或 flex）。
 */
class SDAudioWaveformView : ComposeView<SDAudioWaveformAttr, ComposeEvent>() {
    private var peaks by observable<List<Float>?>(null)
    private var usingFallback by observable(false)
    private var width by observable(0f)
    private var generation = 0
    private var loadedPath: String? = null
    private var eventRef: CallbackRef? = null
    private var taskId: String? = null
    private var destroyed = false

    override fun createAttr(): SDAudioWaveformAttr = SDAudioWaveformAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun viewDidLoad() {
        super.viewDidLoad()
        // audioPath 变化时重新抽取（Dart didUpdateWidget）；绑定时会先按当前值执行一次
        bindValueChange({ attr.audioPath ?: "" }) { load(attr.audioPath) }
    }

    override fun viewDestroyed() {
        destroyed = true
        unbindAllValueChange()
        cancelExtraction()
        super.viewDestroyed()
    }

    private fun module(): SDMediaProcessModule = getPager().acquireModule(SDMediaProcessModule.MODULE_NAME)

    private fun cancelExtraction() {
        val id = taskId ?: return
        taskId = null
        try {
            module().cancel(id)
        } catch (e: Throwable) {
        }
        eventRef = null
    }

    /** 抽取分辨率：约 2 倍宽度的点数，限制在 8–40 pixels/second；未知时 20。 */
    private fun pixelsPerSecond(): Int {
        val seconds = ((attr.durationMs ?: 0L) / 1000L).toInt()
        val w = width
        if (seconds <= 0 || w <= 0f) return 20
        return ((w * 2f) / seconds).roundToInt().coerceIn(8, 40)
    }

    private fun load(path: String?) {
        if (path == loadedPath) return
        loadedPath = path
        val gen = ++generation
        cancelExtraction()
        peaks = null
        usingFallback = false
        if (path.isNullOrEmpty()) return
        val id = "waveform_" + Uuid.v4()
        taskId = id
        val params = com.tencent.kuikly.core.nvi.serialization.json.JSONObject()
            .put("audioPath", path)
            .put("samplesPerSecond", pixelsPerSecond())
            .put("taskId", id)
        try {
            eventRef = module().extractWaveform(params) { e ->
                if (destroyed || gen != generation) return@extractWaveform
                if (e.optString("event", "") != "done") return@extractWaveform
                taskId = null
                val arr = e.optJSONArray("peaks")
                if (!e.optBoolean("ok", false) || arr == null || arr.length() == 0) {
                    DevLog.logger("AudioWaveform extraction failed: ${e.optString("error", "")}")
                    useFallback()
                    return@extractWaveform
                }
                val list = ArrayList<Float>(arr.length())
                for (i in 0 until arr.length()) list.add(arr.optDouble(i, 0.0).toFloat().coerceIn(0f, 1f))
                usingFallback = false
                peaks = list
            }
        } catch (e: Throwable) {
            DevLog.logger("AudioWaveform load failed: $e")
            useFallback()
        }
    }

    private fun useFallback() {
        val step = if (attr.pixelsPerStep < 1f) 1f else attr.pixelsPerStep
        val count = if (width <= 0f) 96 else max(96, ceil(width / step).toInt())
        peaks = List(count) { songAmplitude(it, count) }
        usingFallback = true
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            Canvas({
                attr { absolutePositionAllZero() }
                event { layoutFrameDidChange { f -> ctx.width = f.width } }
            }) { context, w, h ->
                val data = ctx.peaks ?: return@Canvas
                if (data.isEmpty() || w <= 0f || h <= 0f) return@Canvas
                val stroke = ctx.attr.strokeWidth
                if (stroke <= 0f) return@Canvas
                var peak = 0f
                for (v in data) if (v > peak) peak = v
                if (peak <= 0f) peak = 1f
                val peakFill = if (ctx.usingFallback) 0.72f else 1f
                val centerY = h / 2f
                val halfHeight = max(0f, h - stroke) / 2f
                val step = max(1f, ctx.attr.pixelsPerStep)
                context.beginPath()
                context.strokeStyle(ctx.attr.waveColor)
                context.lineWidth(stroke)
                context.lineCapRound()
                var x = 0f
                while (x <= w) {
                    val index = ((x / w) * (data.size - 1)).toInt().coerceIn(0, data.size - 1)
                    val extent = halfHeight * peakFill * data[index] / peak
                    context.moveTo(x, centerY - extent)
                    context.lineTo(x, centerY + extent)
                    x += step
                }
                context.stroke()
            }
        }
    }

    companion object {
        /** 段落能量（前奏、主歌、副歌、间断、结尾）。 */
        private val knots = arrayOf(
            floatArrayOf(0.00f, 0.14f), floatArrayOf(0.08f, 0.26f), floatArrayOf(0.18f, 0.36f), floatArrayOf(0.28f, 0.28f),
            floatArrayOf(0.36f, 0.44f), floatArrayOf(0.44f, 0.58f), floatArrayOf(0.52f, 0.48f), floatArrayOf(0.58f, 0.16f),
            floatArrayOf(0.64f, 0.11f), floatArrayOf(0.72f, 0.40f), floatArrayOf(0.80f, 0.62f), floatArrayOf(0.88f, 0.42f),
            floatArrayOf(0.94f, 0.22f), floatArrayOf(1.00f, 0.09f),
        )

        private fun songEnergy(t: Float): Float {
            val x = t.coerceIn(0f, 1f)
            var i = 1
            while (i < knots.size - 1 && knots[i][0] < x) i++
            val left = knots[i - 1]
            val right = knots[i]
            val span = right[0] - left[0]
            val u = if (span == 0f) 1f else ((x - left[0]) / span).coerceIn(0f, 1f)
            val smooth = u * u * (3 - 2 * u)
            return left[1] + (right[1] - left[1]) * smooth
        }

        private fun unitHash(n: Int): Float {
            // 与 Dart 64 位 int 运算一致（乘法 64 位回绕、>> 为算术右移）
            var x = (n + 1).toLong() * 0x9E3779B1L
            x = (x xor (x shr 16)) * 0x85EBCA6BL
            x = (x xor (x shr 13)) * 0xC2B2AE35L
            x = x xor (x shr 16)
            return (x and 0xFFFFFFL).toFloat() / 0xFFFFFF.toFloat()
        }

        /** Dart `_songAmplitude`（归一化到 0..1）。 */
        internal fun songAmplitude(index: Int, count: Int): Float {
            val t = if (count <= 1) 0f else index.toFloat() / (count - 1)
            val energy = songEnergy(t)
            val slow = unitHash(index / 4)
            val grain = 0.55f + 0.3f * slow + 0.15f * unitHash(index)
            val hit = unitHash(index * 13 + 7)
            val bump = if (hit > 0.94f) 1.08f else 1f
            return (energy * grain * bump).coerceIn(0.04f, 0.72f)
        }
    }
}

fun ViewContainer<*, *>.SDAudioWaveform(init: SDAudioWaveformView.() -> Unit) {
    addChild(SDAudioWaveformView(), init)
}
