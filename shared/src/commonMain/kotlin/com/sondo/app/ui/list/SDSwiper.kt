package com.sondo.app.ui.list

import com.sondo.app.ui.SDColors
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.compose.SliderPage
import com.tencent.kuikly.core.views.compose.SliderPageView

class SDSwiperAttr : ComposeAttr() {
    /** 页数（构建时确定；数据变化用 `vbind({ ctx.banners })` 包一层重建） */
    var itemCount: Int = 0
    /** 单页构建：`index` 为页下标。根节点会被放进一个 `pageWidth × pageHeight` 的页面里 */
    var itemBuilder: (ViewContainer<*, *>.(index: Int) -> Unit)? = null
    /** 页宽 / 页高（必须给，Dart viewportFraction = 1 的整页轮播） */
    var pageWidth: Float = 0f
    var pageHeight: Float = 0f
    /** 初始页 */
    var initialIndex: Int = 0
    /** 自动轮播间隔（毫秒）；0 = 不自动轮播。可随时改（如离开首页 Tab 时置 0） */
    var autoPlayIntervalMs: Int by observable(0)
    /** 是否允许手势滑动 */
    var scrollEnable: Boolean by observable(true)
    /** 是否显示圆点指示器（只有 1 页时不显示） */
    var showIndicator: Boolean = true
    /** 指示器：距底部距离、圆点大小、间距、颜色 */
    var indicatorBottom: Float = 10f
    var dotSize: Float = 6f
    var dotGap: Float = 4f
    /** 当前页圆点的宽度（> dotSize 即为长条样式） */
    var activeDotWidth: Float = 6f
    var dotColor: Color = SDColors.white40
    var activeDotColor: Color = Color.WHITE
    /** 自定义指示器（替换默认圆点；闭包里通过 `SDSwiperView.currentIndex` 读当前页） */
    var indicatorBuilder: ViewBuilder? = null
}

class SDSwiperEvent : ComposeEvent() {
    internal var indexChanged: ((Int) -> Unit)? = null

    /** 当前页变化（手势或自动轮播）。 */
    fun onIndexChanged(handler: (index: Int) -> Unit) { indexChanged = handler }
}

/**
 * 轮播图（Dart `card_swiper` 的 `Swiper` / `carousel_slider`）：整页横向轮播 + 循环 + 自动播放 + 圆点指示器。
 *
 * ```
 * SDSwiper {
 *     attr {
 *         pageWidth = ctx.screenWidth; pageHeight = 180f
 *         itemCount = banners.size
 *         itemBuilder = { index -> SDImage { attr { flex(1f); url = banners[index].image } } }
 *         autoPlayIntervalMs = 5000
 *     }
 *     event { onIndexChanged { ctx.bannerIndex = it } }
 * }
 * ```
 * 多于 1 页时自动首尾循环（Dart `loop: banners.length > 1`）。
 */
class SDSwiperView : ComposeView<SDSwiperAttr, SDSwiperEvent>() {
    private var current by observable(0)
    private var sliderRef: ViewRef<SliderPageView>? = null

    /** 当前页下标（响应式，可在 indicatorBuilder 里读）。 */
    val currentIndex: Int get() = current

    override fun createAttr(): SDSwiperAttr = SDSwiperAttr()
    override fun createEvent(): SDSwiperEvent = SDSwiperEvent()

    override fun created() {
        super.created()
        current = attr.initialIndex.coerceIn(0, (attr.itemCount - 1).coerceAtLeast(0))
    }

    /** 跳到某一页。 */
    fun scrollToPage(index: Int, animated: Boolean = true) {
        sliderRef?.view?.scrollToPage(index, animated)
    }

    /** 下一页（循环）。 */
    fun next(animated: Boolean = true) {
        if (attr.itemCount <= 1) return
        scrollToPage(current + 1, animated)
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val a = ctx.attr
        val indices = (0 until a.itemCount).toList()
        return {
            attr { size(a.pageWidth, a.pageHeight) }
            if (a.itemCount > 0) {
                SliderPage {
                    ref { ctx.sliderRef = it }
                    attr {
                        pageItemWidth = a.pageWidth
                        pageItemHeight = a.pageHeight
                        isHorizontal = true
                        defaultPageIndex = ctx.attr.initialIndex.coerceIn(0, a.itemCount - 1)
                        loopPlayIntervalTimeMs = a.autoPlayIntervalMs
                        scrollEnable = a.scrollEnable
                        initSliderItems(indices) { index ->
                            View {
                                attr { size(a.pageWidth, a.pageHeight) }
                                a.itemBuilder?.invoke(this, index)
                            }
                        }
                    }
                    event {
                        pageIndexDidChanged {
                            val index = (it as? JSONObject)?.optInt("index", 0) ?: 0
                            if (index != ctx.current) {
                                ctx.current = index
                                ctx.event.indexChanged?.invoke(index)
                            }
                        }
                    }
                }
            }
            if (a.indicatorBuilder != null) {
                a.indicatorBuilder?.invoke(this)
            } else if (a.showIndicator && a.itemCount > 1) {
                View {
                    attr {
                        absolutePosition(left = 0f, right = 0f, bottom = a.indicatorBottom)
                        flexDirectionRow()
                        allCenter()
                        touchEnable(false)
                    }
                    for (i in 0 until a.itemCount) {
                        View {
                            attr {
                                val on = ctx.current == i
                                size(if (on) a.activeDotWidth else a.dotSize, a.dotSize)
                                borderRadius(a.dotSize / 2f)
                                if (i > 0) marginLeft(a.dotGap)
                                backgroundColor(if (on) a.activeDotColor else a.dotColor)
                                animate(Animation.easeOut(0.2f), ctx.current)
                            }
                        }
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDSwiper(init: SDSwiperView.() -> Unit) {
    addChild(SDSwiperView(), init)
}
