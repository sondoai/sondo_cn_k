package com.sondo.app.ui.nav

import com.sondo.app.ui.SDColors
import com.sondo.app.ui.SDWeight
import com.sondo.app.ui.weight
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.event.layoutFrameDidChange
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.ScrollerView
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/** 指示器样式。 */
enum class SDTabIndicator {
    /** 无指示器（作品库顶部大字 Tab） */
    NONE,
    /** 文字下方短横线 */
    UNDERLINE,
    /** 与 Tab 等大的圆角底（Dart `indicator: BoxDecoration(color:, borderRadius:)`） */
    PILL,
}

class SDTabBarAttr : ComposeAttr() {
    /** Tab 文案（构建时确定；数量变化请用 `vbind` 重建组件） */
    var tabs: List<String> = emptyList()
    /** 当前选中下标（受控：父级绑定；点击只回调 onChanged） */
    var currentIndex: Int by observable(0)
    /** 可横向滚动、从左排起（Dart isScrollable + TabAlignment.start）；false 时等分宽度 */
    var isScrollable: Boolean = true
    /** Tab 高度 */
    var tabHeight: Float = 32f
    /** 单个 Tab 最小宽度（Dart 作品库筛选 70） */
    var tabMinWidth: Float = 0f
    /** Tab 内左右内边距 */
    var tabPaddingH: Float = 16f
    /** Tab 之间的间距（Dart labelPadding 水平值 × 2） */
    var gap: Float = 8f
    /** 整体左右内边距 */
    var paddingH: Float = 0f
    var labelColor: Color by observable(Color.WHITE)
    var unselectedLabelColor: Color by observable(SDColors.white40)
    var labelSize: Float = 12f
    var unselectedLabelSize: Float = -1f
    var labelWeight: SDWeight? = SDWeight.W600
    var unselectedLabelWeight: SDWeight? = SDWeight.W600
    var fontFamily: String? = null
    /**
     * 选中 / 未选中各自的字族（如 "Poppins-Bold" / "Poppins-SemiBold"），null 时用 [fontFamily]。
     * 给了字族的状态不再叠 [labelWeight] / [unselectedLabelWeight]，避免 Android 上假粗体。
     */
    var selectedFontFamily: String? = null
    var unselectedFontFamily: String? = null
    var indicator: SDTabIndicator = SDTabIndicator.PILL
    /** 指示器颜色（PILL：选中底色，Dart 白 15%；UNDERLINE：线色） */
    var indicatorColor: Color by observable(Color(0x26FFFFFFL))
    /** PILL 圆角 */
    var indicatorRadius: Float = 29f
    /** UNDERLINE：线宽 / 线高 */
    var underlineWidth: Float = 16f
    var underlineHeight: Float = 3f
    /** 每个 Tab 自身的底色（未选中也有，Dart 白 5%）；透明则没有 */
    var tabBgColor: Color = Color.TRANSPARENT
}

class SDTabBarEvent : ComposeEvent() {
    internal var changed: ((Int) -> Unit)? = null

    /** 点击某个 Tab（包括点当前 Tab）。 */
    fun onChanged(handler: (index: Int) -> Unit) { changed = handler }
}

/**
 * Tab 栏（Dart `TabBar`，带滑动指示器）。与内容联动：把 `currentIndex` 和 PageList 的页码绑到同一个字段。
 *
 * ```
 * SDTabBar {
 *     attr { tabs = listOf("my".tr, "like".tr); currentIndex = ctx.tab }
 *     event { onChanged { ctx.tab = it } }
 * }
 * ```
 */
class SDTabBarView : ComposeView<SDTabBarAttr, SDTabBarEvent>() {
    // 每个 Tab 的 x / 宽度（布局后回填）
    private var frameVersion by observable(0)
    private var xs = FloatArray(0)
    private var ws = FloatArray(0)
    private var scrollerRef: ViewRef<ScrollerView<*, *>>? = null
    private var viewportWidth = 0f
    private var bound = false

    override fun createAttr(): SDTabBarAttr = SDTabBarAttr()
    override fun createEvent(): SDTabBarEvent = SDTabBarEvent()

    override fun created() {
        super.created()
        xs = FloatArray(attr.tabs.size)
        ws = FloatArray(attr.tabs.size)
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        bindValueChange({ attr.currentIndex }) {
            if (!bound) bound = true else scrollToCurrent()
        }
    }

    override fun viewDestroyed() {
        unbindAllValueChange()
        super.viewDestroyed()
    }

    /** 选中项滚到可视区中间。 */
    private fun scrollToCurrent() {
        if (!attr.isScrollable) return
        val i = attr.currentIndex
        if (i !in xs.indices || viewportWidth <= 0f) return
        val target = (xs[i] + ws[i] / 2f - viewportWidth / 2f).coerceAtLeast(0f)
        scrollerRef?.view?.setContentOffset(target, 0f, true)
    }

    private fun indicatorLeft(): Float {
        frameVersion
        val i = attr.currentIndex
        if (i !in xs.indices) return 0f
        return if (attr.indicator == SDTabIndicator.UNDERLINE) xs[i] + (ws[i] - attr.underlineWidth) / 2f else xs[i]
    }

    private fun indicatorWidth(): Float {
        frameVersion
        val i = attr.currentIndex
        if (i !in ws.indices) return 0f
        return if (attr.indicator == SDTabIndicator.UNDERLINE) attr.underlineWidth else ws[i]
    }

    private fun buildTabs(container: ViewContainer<*, *>) {
        val ctx = this
        val a = attr
        // 下划线指示器（在文字下面一层）。
        // 药丸样式不用滑块：Android 上 Scroller 内绝对定位的滑块改 left 不生效，会停在初始 Tab；改由每个 Tab 自己铺选中底色。
        if (a.indicator == SDTabIndicator.UNDERLINE) {
            container.View {
                attr {
                    positionAbsolute()
                    bottom(0f)
                    height(a.underlineHeight)
                    borderRadius(a.underlineHeight / 2f)
                    left(ctx.indicatorLeft())
                    width(ctx.indicatorWidth())
                    backgroundColor(a.indicatorColor)
                    animate(Animation.easeOut(0.25f), a.currentIndex)
                }
            }
        }
        a.tabs.forEachIndexed { index, label ->
            container.View {
                attr {
                    height(a.tabHeight)
                    if (a.isScrollable) {
                        if (a.tabMinWidth > 0f) minWidth(a.tabMinWidth)
                        padding(0f, a.tabPaddingH, 0f, a.tabPaddingH)
                        if (index == 0) marginLeft(a.paddingH) else marginLeft(a.gap)
                        if (index == a.tabs.size - 1) marginRight(a.paddingH)
                    } else {
                        flex(1f)
                    }
                    allCenter()
                    if (a.tabBgColor != Color.TRANSPARENT) {
                        backgroundColor(a.tabBgColor)
                        borderRadius(a.indicatorRadius)
                    }
                }
                event {
                    click { ctx.event.changed?.invoke(index) }
                    layoutFrameDidChange {
                        if (index in ctx.xs.indices && (ctx.xs[index] != it.x || ctx.ws[index] != it.width)) {
                            ctx.xs[index] = it.x
                            ctx.ws[index] = it.width
                            ctx.frameVersion++
                        }
                    }
                }
                if (a.indicator == SDTabIndicator.PILL) {
                    View {
                        attr {
                            absolutePositionAllZero()
                            borderRadius(a.indicatorRadius)
                            backgroundColor(if (a.currentIndex == index) a.indicatorColor else Color.TRANSPARENT)
                        }
                    }
                }
                Text {
                    attr {
                        text(label)
                        val selected = a.currentIndex == index
                        val unSize = if (a.unselectedLabelSize > 0f) a.unselectedLabelSize else a.labelSize
                        fontSize(if (selected) a.labelSize else unSize)
                        val family = if (selected) a.selectedFontFamily else a.unselectedFontFamily
                        if (family != null) {
                            fontFamily(family)
                        } else {
                            weight(if (selected) a.labelWeight else a.unselectedLabelWeight)
                            a.fontFamily?.let { fontFamily(it) }
                        }
                        color(if (selected) a.labelColor else a.unselectedLabelColor)
                        lines(1)
                    }
                }
            }
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            if (ctx.attr.isScrollable) {
                Scroller {
                    ref { ctx.scrollerRef = it }
                    attr {
                        height(ctx.attr.tabHeight)
                        flexDirectionRow()
                        showScrollerIndicator(false)
                    }
                    event {
                        layoutFrameDidChange { ctx.viewportWidth = it.width }
                    }
                    ctx.buildTabs(this)
                }
            } else {
                View {
                    attr {
                        height(ctx.attr.tabHeight)
                        flexDirectionRow()
                        padding(0f, ctx.attr.paddingH, 0f, ctx.attr.paddingH)
                    }
                    ctx.buildTabs(this)
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDTabBar(init: SDTabBarView.() -> Unit) {
    addChild(SDTabBarView(), init)
}

// ============================================================================
// 分段控件
// ============================================================================

class SDSegmentedAttr : ComposeAttr() {
    var segments: List<String> = emptyList()
    /** 当前选中下标（受控） */
    var currentIndex: Int by observable(0)
    /** 高度（Dart 音乐创作 simple/custom 切换 42） */
    var segmentHeight: Float = 42f
    /** 容器底色 / 选中滑块色 */
    var containerColor: Color by observable(SDColors.white10)
    var selectedColor: Color by observable(SDColors.white20)
    var radius: Float = 100f
    /** 滑块与容器的内缩 */
    var inset: Float = 0f
    var labelColor: Color by observable(Color.WHITE)
    var unselectedLabelColor: Color by observable(SDColors.white60)
    var labelSize: Float = 14f
    var labelWeight: SDWeight? = SDWeight.W600
    var unselectedLabelWeight: SDWeight? = SDWeight.W500
    var fontFamily: String? = null
}

class SDSegmentedEvent : ComposeEvent() {
    internal var changed: ((Int) -> Unit)? = null
    fun onChanged(handler: (index: Int) -> Unit) { changed = handler }
}

/**
 * 分段控件：等宽分段 + 滑动的圆角选中块（Dart `MusicModeSegment` 那种 `TabBar(indicatorSize: tab)`）。
 * 宽度由调用方给（`flex(1f)` / `margin(0f, 16f, 0f, 16f)`）。
 */
class SDSegmentedView : ComposeView<SDSegmentedAttr, SDSegmentedEvent>() {
    private var totalWidth by observable(0f)

    override fun createAttr(): SDSegmentedAttr = SDSegmentedAttr()
    override fun createEvent(): SDSegmentedEvent = SDSegmentedEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        val a = ctx.attr
        val n = if (a.segments.isEmpty()) 1 else a.segments.size
        return {
            attr {
                height(a.segmentHeight)
                borderRadius(a.radius)
                backgroundColor(a.containerColor)
                flexDirectionRow()
            }
            event {
                layoutFrameDidChange { ctx.totalWidth = it.width }
            }
            View {
                attr {
                    val segW = ctx.totalWidth / n
                    absolutePosition(top = a.inset, bottom = a.inset)
                    left(segW * a.currentIndex + a.inset)
                    width((segW - a.inset * 2f).coerceAtLeast(0f))
                    borderRadius(a.radius)
                    backgroundColor(a.selectedColor)
                    animate(Animation.easeOut(0.25f), a.currentIndex)
                }
            }
            a.segments.forEachIndexed { index, label ->
                View {
                    attr {
                        flex(1f)
                        allCenter()
                    }
                    event { click { ctx.event.changed?.invoke(index) } }
                    Text {
                        attr {
                            text(label)
                            val selected = a.currentIndex == index
                            fontSize(a.labelSize)
                            weight(if (selected) a.labelWeight else a.unselectedLabelWeight)
                            a.fontFamily?.let { fontFamily(it) }
                            color(if (selected) a.labelColor else a.unselectedLabelColor)
                            lines(1)
                        }
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDSegmented(init: SDSegmentedView.() -> Unit) {
    addChild(SDSegmentedView(), init)
}
