package com.sondo.app.ui.atoms

import com.sondo.app.ui.SDColors
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.Translate
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.View

class SDShimmerAttr : ComposeAttr() {
    /** 底色（Dart Shimmer baseColor） */
    var baseColor: Color by observable(SDColors.white20)
    /** 扫光峰值颜色（叠在底色上） */
    var highlightColor: Color by observable(SDColors.white10)
    /** 圆角 */
    var radius: Float by observable(0f)
    /** 是否播放扫光动画；false 时只是一个静态色块 */
    var animated: Boolean by observable(true)
    /** 一次扫光时长（秒），Dart shimmer 默认 1.5s */
    var periodS: Float = 1.5f
}

/**
 * 骨架屏色块（Dart `Shimmer.fromColors(child: Container(...))`）。
 * 尺寸由调用方在 attr 里给（`size(w, h)` / `flex(1f)` / `absolutePositionAllZero()`）。
 */
class SDShimmerView : ComposeView<SDShimmerAttr, ComposeEvent>() {
    private var running by observable(false)

    override fun createAttr(): SDShimmerAttr = SDShimmerAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun viewDidLoad() {
        super.viewDidLoad()
        // 首帧之后再切状态，声明式动画才有「起点 → 终点」
        setTimeout(pagerId, 16) { running = true }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                backgroundColor(ctx.attr.baseColor)
                borderRadius(ctx.attr.radius)
                overflow(true)
            }
            View {
                attr {
                    absolutePositionAllZero()
                    visibility(ctx.attr.animated)
                    backgroundLinearGradient(
                        Direction.TO_RIGHT,
                        ColorStop(Color.TRANSPARENT_WHITE, 0f),
                        ColorStop(ctx.attr.highlightColor, 0.5f),
                        ColorStop(Color.TRANSPARENT_WHITE, 1f),
                    )
                    transform(Translate(if (ctx.running) 1f else -1f, 0f))
                    animate(Animation.linear(ctx.attr.periodS).repeatForever(true), ctx.running)
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDShimmer(init: SDShimmerView.() -> Unit) {
    addChild(SDShimmerView(), init)
}

// ---------------------------------------------------------------------------

class SDSongSkeletonAttr : ComposeAttr() {
    /** 'card' | 'item' | 'more_type'（Dart SongSkeleton.type） */
    var type: String = TYPE_ITEM

    companion object {
        const val TYPE_CARD = "card"
        const val TYPE_ITEM = "item"
        const val TYPE_MORE = "more_type"
    }
}

// 注意：构建辅助函数必须是顶层扩展，不能写成组件的成员（DSL 作用域限制，成员扩展在嵌套闭包里调不到）
private fun ViewContainer<*, *>.skeletonBlock(w: Float?, h: Float, r: Float, mt: Float = 0f, ml: Float = 0f, grow: Boolean = false) {
    SDShimmer {
        attr {
            if (w != null) width(w)
            height(h)
            if (grow) flex(1f)
            radius = r
            highlightColor = SDColors.white10
            if (mt > 0f) marginTop(mt)
            if (ml > 0f) marginLeft(ml)
        }
    }
}

/** Dart `SongSkeleton`：歌曲卡片 / 列表项 / 更多类型的骨架屏（底色白 20%，高亮白 30%）。 */
class SDSongSkeletonView : ComposeView<SDSongSkeletonAttr, ComposeEvent>() {
    override fun createAttr(): SDSongSkeletonAttr = SDSongSkeletonAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            when (ctx.attr.type) {
                SDSongSkeletonAttr.TYPE_CARD -> {
                    attr {
                        width(140f)
                        margin(0f, 6f, 0f, 6f)
                        paddingBottom(4f)
                    }
                    skeletonBlock(140f, 140f, 8f)
                    skeletonBlock(140f, 20f, 2f, mt = 12f)
                }
                SDSongSkeletonAttr.TYPE_ITEM -> {
                    attr { flexDirectionRow() }
                    skeletonBlock(60f, 60f, 6f)
                    View {
                        attr {
                            flex(1f)
                            height(60f)
                            marginLeft(8f)
                            justifyContentSpaceBetween()
                        }
                        skeletonBlock(null, 18f, 2f)
                        View {
                            attr {
                                flexDirectionRow()
                                alignItemsCenter()
                            }
                            skeletonBlock(20f, 20f, 10f)
                            skeletonBlock(null, 14f, 2f, ml = 4f, grow = true)
                        }
                        skeletonBlock(null, 14f, 2f)
                    }
                }
                SDSongSkeletonAttr.TYPE_MORE -> {
                    skeletonBlock(130f, 80f, 12f)
                }
                else -> {}
            }
        }
    }
}

fun ViewContainer<*, *>.SDSongSkeleton(init: SDSongSkeletonView.() -> Unit) {
    addChild(SDSongSkeletonView(), init)
}
