package com.sondo.app.ui.list

import com.tencent.kuikly.core.base.ContainerAttr
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.views.DivView
import com.tencent.kuikly.core.views.List
import com.tencent.kuikly.core.views.ListView
import com.tencent.kuikly.core.views.ScrollerAttr
import com.tencent.kuikly.core.views.View

/**
 * 等宽网格的尺寸计算（Dart `GridView.count` / `Wrap` + 手算宽度）。
 *
 * @param columns 列数
 * @param totalWidth 网格总宽度（通常 `screenWidth - 左右内边距`）
 * @param spacing 列间距（Dart crossAxisSpacing）
 * @param runSpacing 行间距（Dart mainAxisSpacing）
 */
class SDGridSpec(
    val columns: Int,
    val totalWidth: Float,
    val spacing: Float = 0f,
    val runSpacing: Float = spacing,
) {
    /** 单格宽度（向下取到 0.01，避免浮点误差导致一行放不下而换行）。 */
    val itemWidth: Float = (((totalWidth - spacing * (columns - 1)) / columns) * 100f).toInt() / 100f
}

/** 网格容器的属性：横排 + 自动换行 + 固定总宽。写在容器的 `attr { }` 里。 */
fun ContainerAttr.gridContainer(spec: SDGridSpec) {
    flexDirectionRow()
    flexWrapWrap()
    width(spec.totalWidth)
}

/**
 * 网格单元的属性：宽度 + 与前一格 / 上一行的间距。写在每个格子根节点的 `attr { }` 里。
 *
 * 响应式网格的标准写法（配合 `vforIndex`）：
 * ```
 * val grid = SDGridSpec(columns = 3, totalWidth = ctx.screenWidth - 40f, spacing = 10f)
 * View {
 *     attr { gridContainer(grid) }
 *     vforIndex({ ctx.tags }) { item, index, _ ->
 *         View { attr { gridCell(grid, index); height(grid.itemWidth) } … }
 *     }
 * }
 * ```
 */
fun ContainerAttr.gridCell(spec: SDGridSpec, index: Int) {
    width(spec.itemWidth)
    if (index % spec.columns != 0) marginLeft(spec.spacing)
    if (index >= spec.columns) marginTop(spec.runSpacing)
}

/**
 * 静态网格：把 [items] 按 [SDGridSpec.columns] 列排成若干行（构建时确定，数据变化请用 `vbind` 包一层重建）。
 * 每个格子的宽度已设为 `spec.itemWidth`，[itemBuilder] 里只管内容和高度。
 */
fun <T> ViewContainer<*, *>.SDGrid(
    items: List<T>,
    spec: SDGridSpec,
    itemBuilder: DivView.(item: T, index: Int) -> Unit,
) {
    View {
        attr { gridContainer(spec) }
        items.forEachIndexed { index, item ->
            View {
                attr { gridCell(spec, index) }
                itemBuilder(item, index)
            }
        }
    }
}

/**
 * Dart `ScrollOverWidget` 的等价物：关闭越界回弹 / 发光效果。写在 Scroller / List 的 `attr { }` 里。
 * @param onlyAndroid 只在 Android 关闭（iOS 保留回弹），与 Dart 默认一致
 */
fun ScrollerAttr.scrollOver(onlyAndroid: Boolean = true) {
    if (!onlyAndroid || pagerData.isAndroid) bouncesEnable(false)
}

/**
 * 横向列表（Dart `ListView(scrollDirection: Axis.horizontal)` / `SingleChildScrollView(horizontal)`）。
 *
 * ```
 * SDHorizontalList(height = 68f, paddingH = 20f) {
 *     vfor({ ctx.records }) { url -> SDImage { attr { size(68f, 68f); marginRight(10f); url = url } } }
 * }
 * ```
 * @param height 列表高度（必须）
 * @param paddingH 首尾留白
 * @param bounces 是否回弹
 */
fun ViewContainer<*, *>.SDHorizontalList(
    height: Float,
    paddingH: Float = 0f,
    bounces: Boolean = true,
    init: ListView<*, *>.() -> Unit,
) {
    List {
        attr {
            height(height)
            flexDirectionRow()
            showScrollerIndicator(false)
            bouncesEnable(bounces)
        }
        if (paddingH > 0f) View { attr { width(paddingH) } }
        init()
        if (paddingH > 0f) View { attr { width(paddingH) } }
    }
}

/**
 * 瀑布流 / 错位网格（Dart `flutter_staggered_grid_view` 的 MasonryGridView）：不带刷新和加载更多的 [SDRefreshList]。
 * 需要分页时直接用 `SDRefreshList { attr { columnCount = 2; listWidth = … } }`。
 *
 * ```
 * SDStaggeredGrid {
 *     attr { flex(1f); columnCount = 2; listWidth = ctx.screenWidth; itemSpacing = 8f; lineSpacing = 8f; contentPaddingH = 12f }
 *     items { vfor({ ctx.mvList }) { mv -> MvCard { … } } }      // 每项高度自适应
 * }
 * ```
 */
fun ViewContainer<*, *>.SDStaggeredGrid(init: SDRefreshListView.() -> Unit) {
    addChild(SDRefreshListView()) {
        attr {
            columnCount = 2
            enableRefresh = false
            enableLoadMore = false
        }
        init()
    }
}
