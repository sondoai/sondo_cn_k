package com.sondo.app.ui.atoms

import com.sondo.app.core.constants.AssetsImage
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.View
import kotlin.random.Random

class SDAudioVisualizerAttr : ComposeAttr() {
    /** 'pause'（= 正在播放，显示跳动柱）| 'loading' | 其它（调用方传 'player'，显示播放图标） */
    var status: String by observable("player")
    var color: Color = Color.WHITE
    /** 柱子数量（Dart 默认 27，调用方都传 3） */
    var barCount: Int = 3
    /** 圆形直径 */
    var size: Float = 30f
    /** 播放图标边长 */
    var imageSize: Float = 22f
    /** 底色；默认黑 40% */
    var bg: Color = Color(0, 0, 0, 0.4f)
}

/**
 * Dart `AudioVisualizer` → `SDAudioVisualizer`：歌曲封面上的圆形播放状态指示。
 * 柱高是随机数（约每 70ms 刷新一次），不做真实频谱分析，和参考工程一致。
 */
class SDAudioVisualizerView : ComposeView<SDAudioVisualizerAttr, ComposeEvent>() {
    private val barHeights by observableList<Float>()
    private var alive = true
    private var ticking = false

    override fun createAttr(): SDAudioVisualizerAttr = SDAudioVisualizerAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun created() {
        super.created()
        for (i in 0 until attr.barCount) barHeights.add(0.2f)
        // 状态切到 'pause' 时启动定时器，离开时自然停下
        bindValueChange({ attr.status }) { tick() }
    }

    override fun viewDestroyed() {
        alive = false
        unbindAllValueChange()
        super.viewDestroyed()
    }

    private fun tick() {
        if (!alive || ticking || attr.status != "pause") return
        ticking = true
        for (i in 0 until barHeights.size) barHeights[i] = Random.nextFloat() * 0.8f + 0.2f
        setTimeout(pagerId, 70) {
            ticking = false
            tick()
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                size(ctx.attr.size, ctx.attr.size)
                borderRadius(ctx.attr.size / 2f)
                backgroundColor(ctx.attr.bg)
                allCenter()
                overflow(true)
            }
            vif({ ctx.attr.status == "pause" }) {
                View {
                    attr {
                        flexDirectionRow()
                        allCenter()
                    }
                    for (i in 0 until ctx.attr.barCount) {
                        View {
                            attr {
                                width(4f)
                                height(20f * ctx.barHeights[i])
                                borderRadius(5f)
                                backgroundColor(ctx.attr.color)
                                if (i > 0) marginLeft(3f)
                            }
                        }
                    }
                }
            }
            vif({ ctx.attr.status == "loading" }) {
                SDLoading {
                    attr { size = ctx.attr.size * 0.6f }
                }
            }
            vif({ ctx.attr.status != "pause" && ctx.attr.status != "loading" }) {
                Image {
                    attr {
                        size(ctx.attr.imageSize, ctx.attr.imageSize)
                        src(ImageUri.commonAssets(AssetsImage.play2))
                        resizeContain()
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDAudioVisualizer(init: SDAudioVisualizerView.() -> Unit) {
    addChild(SDAudioVisualizerView(), init)
}
