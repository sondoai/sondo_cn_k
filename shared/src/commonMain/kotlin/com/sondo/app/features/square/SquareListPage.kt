package com.sondo.app.features.square

import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.ui.works.WorksState
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.asset
import com.sondo.app.core.http.ApiResult
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.DevLog
import com.sondo.app.data.models.MvModel
import com.sondo.app.data.models.PageResult
import com.sondo.app.data.models.VideoContentEntity
import com.sondo.app.data.models.toEntity
import com.sondo.app.features.square.data.SquareApi
import com.sondo.app.features.square.data.SquareListArgs
import com.sondo.app.features.square.data.SquarePlayArgs
import com.sondo.app.features.square.data.SquareProfileArgs
import com.sondo.app.features.square.widgets.squareCard
import com.sondo.app.features.square.widgets.squareCardSkeleton
import com.sondo.app.ui.list.PagingState
import com.sondo.app.ui.list.SDRefreshList
import com.sondo.app.ui.nav.SDNavBar
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 广场 MV 瀑布流（Dart `SquareListPage` + `SquareListController`）。
 *
 * 页面参数（[SquareListArgs]）：`{videoType: Int = 0, type: "trending"|"new"|"", isFindHot: Int = 0}`；
 * type 决定标题（trending → `trending_video`，new → `new_mvs`）。
 */
@Page(Routes.square)
internal class SquareListPage : BasePager() {
    /** 0=MV。 */
    private var videoType = 0
    private var title by observable("")
    /** 是否热门列表：0 否，1 是。 */
    private var isFindHot = 0

    // Dart loadData 失败返回 []：这里同样把失败当成空页（首屏显示空态，翻页即没有更多）
    private val paging = PagingState<VideoContentEntity>(this, pageSize = 10, itemKey = { it.id }) { pageNo, pageSize ->
        when (val r = SquareApi.getPublishedList(pageNo = pageNo, pageSize = pageSize, isFindHot = isFindHot)) {
            is ApiResult.Success -> ApiResult.Success(PageResult(r.data.list, r.data.total))
            is ApiResult.Failure -> {
                DevLog.logger("loadData exception: ${r.error.message}")
                ApiResult.Success(PageResult(emptyList(), 0))
            }
        }
    }

    internal val columnWidth: Float get() = (screenWidth - 32f - 12f) / 2f

    override fun created() {
        super.created()
        val args = SquareListArgs.fromJson(pagerData.params)
        videoType = args.videoType
        if (args.type == "trending") {
            title = "trending_video".tr
        } else if (args.type == "new") {
            title = "new_mvs".tr
        }
        isFindHot = args.isFindHot
        paging.refresh()
    }

    /** 点击卡片跳转全屏播放，传入当前列表与索引。 */
    internal fun onTapVideo(video: VideoContentEntity) {
        val index = paging.list.indexOfFirst { it.id == video.id }
        if (index == -1) return
        SquareNav.openPlay(
            this,
            SquarePlayArgs(
                videoType = videoType,
                videoList = ArrayList(paging.list.map { WorksState.of(this).latestVideo(it) }),
                initialIndex = index,
                pageNo = paging.pageNo,
                isFindHot = isFindHot,
            ),
        )
    }

    internal fun onTapAuthor(video: VideoContentEntity) {
        SquareNav.openProfile(this, SquareProfileArgs(userId = video.userId))
    }

    // 收藏 / 分享 / 播放数由 WorksState 按 id 覆盖，卡片局部刷新，不替换列表元素（替换会重建卡片、封面重新加载）。

    /** 空态点击重试。 */
    internal fun retry() = paging.refresh()

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            SDNavBar {
                attr {
                    title = ctx.title
                    titleSize = 18f
                }
            }
            SDRefreshList {
                attr {
                    flex(1f)
                    marginBottom(ctx.safeBottom)
                    state = ctx.paging
                    columnCount = 2
                    listWidth = ctx.screenWidth
                    itemSpacing = 12f
                    lineSpacing = 12f
                    contentPaddingH = 16f
                    placeholderHeight = ctx.screenHeight * 0.7f
                    loadingBuilder = { squareListSkeleton(ctx) }
                    emptyBuilder = { squareListEmpty(ctx) }
                }
                items {
                    vfor({ ctx.paging.list }) { video ->
                        View {
                            squareCard(
                                video,
                                ctx.columnWidth,
                                onTap = { ctx.onTapVideo(video) },
                                onTapAuthor = { ctx.onTapAuthor(video) },
                                onTapLike = {},
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.squareListSkeleton(ctx: SquareListPage) {
    View {
        attr {
            absolutePosition(top = 0f, left = 16f, right = 16f)
            flexDirectionRow()
            justifyContentSpaceBetween()
        }
        // 参考是 MasonryGridView：10 个骨架（高 200/140 交替）依次放进当前较矮的一列
        val columns = listOf(mutableListOf<Int>(), mutableListOf<Int>())
        val heights = floatArrayOf(0f, 0f)
        for (i in 0 until 10) {
            val col = if (heights[1] < heights[0]) 1 else 0
            columns[col].add(i)
            heights[col] += (if (i % 2 == 0) 200f else 140f) + 12f
        }
        for (col in 0 until 2) {
            View {
                columns[col].forEachIndexed { row, index ->
                    View {
                        attr { if (row > 0) marginTop(12f) }
                        squareCardSkeleton(ctx.columnWidth, index)
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.squareListEmpty(ctx: SquareListPage) {
    View {
        attr {
            flex(1f)
            allCenter()
        }
        event { click { ctx.retry() } }
        Image {
            attr {
                size(80f, 80f)
                src(AssetsImage.mvEmpty.asset())
            }
        }
        Text {
            attr {
                marginTop(24f)
                text("no_mvs_yet".tr)
                fontSize(16f)
                color(Color(255, 255, 255, 0.5f))
                fontWeight600()
            }
        }
        Text {
            attr {
                marginTop(8f)
                text("pull_down_to_refresh".tr)
                fontSize(14f)
                color(Color(255, 255, 255, 0.3f))
            }
        }
    }
}
