package com.sondo.app.ui.video

import com.sondo.app.ui.atoms.SDSeekBar
import com.sondo.app.ui.atoms.SDSeekBarView
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.Translate
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.View

class SDAppVideoProgressBarAttr : ComposeAttr() {
    /** 绑定的控制器（obs） */
    var controller: AppVideoController? by observable(null)
    var allowScrubbing: Boolean = true
    var showScrubTime: Boolean = true
    /** 轨道高度（Dart height，默认 4；播放器内置用 3） */
    var barHeight: Float = 4f
    /** 热区高度 */
    var hitTestHeight: Float = 32f
    var progressLabel: String = "视频进度"
    var playedColor: Color = AppVideoColors.white
    var bufferedColor: Color = Color(0x80FFFFFFL)
    var backgroundColor: Color = Color(0x4DFFFFFFL)
    var bufferingColor: Color = AppVideoColors.accent
    /** 左右内边距 */
    var paddingH: Float = 0f
}

class SDAppVideoProgressBarEvent : ComposeEvent() {
    internal var scrubStart: (() -> Unit)? = null
    internal var scrubEnd: (() -> Unit)? = null
    internal var seekTo: ((Long) -> Unit)? = null

    fun onScrubStart(handler: () -> Unit) { scrubStart = handler }
    fun onScrubEnd(handler: () -> Unit) { scrubEnd = handler }
    /** 替代默认的 controller.seekTo（页面自己排队命令时用） */
    fun onSeekTo(handler: (positionMs: Long) -> Unit) { seekTo = handler }
}

/**
 * Dart `AppVideoProgressBar`：绑定控制器的三层进度条（底 白30% / 已缓冲 白50% / 已播放 白），
 * 点按或横向拖动：按下即暂停（记住之前是否在播），轨道加粗，上方气泡显示 `m:ss / m:ss`，
 * 松手只 seek 一次，之前在播则恢复播放；缓冲中（未拖动）叠一条 1.5 高的强调色不确定进度线。
 *
 * 拖动与 iOS 侧滑 / 外层翻页的手势优先级由 Kuikly pan 手势处理（按下即占用），
 * Android 系统边缘返回的排除区域不移植（原生未提供对应能力）。
 */
class SDAppVideoProgressBarView : ComposeView<SDAppVideoProgressBarAttr, SDAppVideoProgressBarEvent>() {
    private var wasPlaying = false
    private var scrubbing by observable(false)
    private var sweep by observable(false)

    override fun createAttr(): SDAppVideoProgressBarAttr = SDAppVideoProgressBarAttr()
    override fun createEvent(): SDAppVideoProgressBarEvent = SDAppVideoProgressBarEvent()

    override fun viewDidLoad() {
        super.viewDidLoad()
        setTimeout(pagerId, 16) { sweep = true }
    }

    private fun startScrub() {
        val c = attr.controller ?: return
        if (scrubbing) return
        scrubbing = true
        wasPlaying = c.isPlaying || c.playIntent
        if (wasPlaying) c.pause()
        event.scrubStart?.invoke()
    }

    private fun endScrub(ms: Float) {
        val c = attr.controller
        if (c != null) {
            val target = ms.toLong()
            val custom = event.seekTo
            if (custom != null) custom(target) else c.seekTo(target)
            if (wasPlaying) c.play()
        }
        wasPlaying = false
        scrubbing = false
        event.scrubEnd?.invoke()
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val a = attr
        return {
            attr {
                height(maxOf(a.hitTestHeight, a.barHeight + 24f))
                justifyContentCenter()
                touchEnable(a.allowScrubbing)
            }
            SDSeekBar {
                attr {
                    touchHeight = maxOf(a.hitTestHeight, a.barHeight + 24f)
                    paddingH = a.paddingH
                    alignBottom = false
                    trackHeight = a.barHeight
                    activeTrackHeight = a.barHeight * 2f
                    thumbRadius = 0f
                    activeThumbRadius = 0f
                    activeColor = a.playedColor
                    inactiveColor = a.backgroundColor
                    bufferedColor = a.bufferedColor
                    showTooltip = a.showScrubTime
                    appVideoTooltip = true
                    val c = a.controller
                    duration = (c?.durationMs ?: 0L).toFloat()
                    position = (c?.positionMs ?: 0L).toFloat()
                    buffered = (c?.bufferedMs ?: 0L).toFloat()
                }
                event {
                    onSeekStart { ctx.startScrub() }
                    onSeekEnd { ms -> ctx.endScrub(ms) }
                }
            }
            // 缓冲中的不确定进度线
            vif({
                val c = a.controller
                !ctx.scrubbing && c != null && c.playIntent && c.isBuffering && !c.isPlaying
            }) {
                View {
                    attr {
                        absolutePosition(left = a.paddingH, right = a.paddingH)
                        height(1.5f)
                        overflow(true)
                        touchEnable(false)
                    }
                    View {
                        attr {
                            absolutePosition(top = 0f, bottom = 0f, left = 0f)
                            width(80f)
                            backgroundColor(a.bufferingColor)
                            transform(Translate(if (ctx.sweep) 6f else -1f, 0f))
                            animate(Animation.linear(1.2f).repeatForever(true), ctx.sweep)
                        }
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDAppVideoProgressBar(init: SDAppVideoProgressBarView.() -> Unit) {
    addChild(SDAppVideoProgressBarView(), init)
}

/**
 * Dart `VideoProgressWidget`（与控制器无关的可拖动进度条，毫秒值）= UI Kit 的 [SDSeekBar]，这里给个同名入口：
 * `SDVideoProgressWidget { attr { duration = d; position = p; paddingH = 10f }; event { onSeekEnd { … } } }`
 */
fun ViewContainer<*, *>.SDVideoProgressWidget(init: SDSeekBarView.() -> Unit) {
    SDSeekBar(init)
}
