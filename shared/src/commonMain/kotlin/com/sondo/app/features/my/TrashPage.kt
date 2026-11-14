package com.sondo.app.features.my

import com.sondo.app.base.BasePager
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.constants.asset
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.i18n.trParams
import com.sondo.app.core.utils.LoadingUtil
import com.sondo.app.data.models.TrashItemModel
import com.sondo.app.features.my.data.TrashApi
import com.sondo.app.service.LibraryTabRefreshService
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.atoms.SDShimmer
import com.sondo.app.ui.dialog.showConfirmDialog
import com.sondo.app.ui.list.PagingState
import com.sondo.app.ui.list.SDRefreshList
import com.sondo.app.ui.nav.SDNavBar
import com.sondo.app.ui.nav.SDTabBar
import com.sondo.app.ui.nav.SDTabIndicator
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.event.layoutFrameDidChange
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.PageList
import com.tencent.kuikly.core.views.PageListView
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 回收站页（Dart: TrashPage + TrashController）：按 MV / 音乐分类展示已删除内容（保留 7 天），可恢复。无参数。
 */
@Page(Routes.trash)
internal class TrashPage : BasePager() {
    /** 0 = MV，1 = 音乐 */
    var tabIndex by observable(0)
    var pageWidth by observable(0f)
    var pageHeight by observable(0f)
    var pageListRef: ViewRef<PageListView<*, *>>? = null

    val mvTrashPaging = PagingState<TrashItemModel>(this, pageSize = 20, itemKey = { it.id }) { pageNo, pageSize ->
        TrashApi.getTrashList(pageNo, pageSize, 2)
    }
    val musicTrashPaging = PagingState<TrashItemModel>(this, pageSize = 20, itemKey = { it.id }) { pageNo, pageSize ->
        TrashApi.getTrashList(pageNo, pageSize, 1)
    }

    private var musicFirstEnter = true

    override fun created() {
        super.created()
        mvTrashPaging.refresh()
    }

    /** Tab 首次切到音乐时懒加载 */
    fun onTabChange(index: Int) {
        if (tabIndex == index) return
        tabIndex = index
        if (index == 1 && musicFirstEnter) {
            musicFirstEnter = false
            if (musicTrashPaging.list.isEmpty() && !musicTrashPaging.loading) musicTrashPaging.refresh()
        }
    }

    fun selectTab(index: Int) {
        onTabChange(index)
        pageListRef?.view?.scrollToPageIndex(index, true)
    }

    /** 按业务类型重试加载（type：1 = MV 页，3 = 音乐页，同 Dart）。 */
    fun onRetry(type: Int) {
        if (type == 1) mvTrashPaging.refresh() else musicTrashPaging.refresh()
    }

    /** 恢复已删除条目，成功后同步刷新作品库对应 Tab */
    fun onRecover(item: TrashItemModel) {
        launch {
            LoadingUtil.show(pager = this@TrashPage)
            val ok = TrashApi.recover(item.id, item.bizType).getOrNull() == true
            LoadingUtil.close()
            if (!ok) {
                toast("trash_recover_failed".tr)
                return@launch
            }
            toast("trash_recover_success".tr)
            if (item.bizType == 1) {
                musicTrashPaging.removeWhere { it.id == item.id }
                // 作品库 Tab 仍注册回调时才同步刷新
                if (LibraryTabRefreshService.isBound) LibraryTabRefreshService.refreshSong()
            } else if (item.bizType == 2) {
                mvTrashPaging.removeWhere { it.id == item.id }
                if (LibraryTabRefreshService.isBound) LibraryTabRefreshService.refreshMv()
            }
        }
    }

    fun openHelp() {
        showConfirmDialog(
            content = "trash_delete_content_tip".tr,
            isShowCancel = false,
            confirmText = "i_know".tr,
        ) { }
    }

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            attr { flex(1f) }
            SDNavBar {
                attr {
                    title = "trash".tr
                    // 帮助图标自带左右 16（Dart pdSymmetric(h: 16)），不再额外留右边距
                    actionsPaddingRight = 0f
                    actions = {
                        View {
                            attr { padding(0f, 16f, 0f, 16f) }
                            event { click { ctx.openHelp() } }
                            Image {
                                attr {
                                    size(18f, 18f)
                                    src(AssetsImage.help.asset())
                                    tintColor(Color.WHITE)
                                }
                            }
                        }
                    }
                }
            }
            SDTabBar {
                attr {
                    marginBottom(20f)
                    tabs = listOf("trash_tab_mv".tr, "trash_tab_music".tr)
                    currentIndex = ctx.tabIndex
                    isScrollable = true
                    // Dart TabBar padding h12 + labelPadding h4：首尾药丸离屏边 16，药丸之间 8
                    paddingH = 16f
                    gap = 8f
                    tabHeight = 32f
                    tabMinWidth = 70f
                    tabPaddingH = 16f
                    labelSize = 12f
                    indicator = SDTabIndicator.PILL
                    indicatorColor = Color(0x26FFFFFFL)
                    indicatorRadius = 29f
                    tabBgColor = Color(0x0DFFFFFFL)
                }
                event { onChanged { ctx.selectTab(it) } }
            }
            View {
                attr {
                    flex(1f)
                    marginBottom(ctx.safeBottom)
                }
                event {
                    layoutFrameDidChange {
                        ctx.pageWidth = it.width
                        ctx.pageHeight = it.height
                    }
                }
                PageList {
                    ref { ctx.pageListRef = it }
                    attr {
                        absolutePositionAllZero()
                        pageDirection(true)
                        pageItemWidth(ctx.pageWidth)
                        pageItemHeight(ctx.pageHeight)
                        showScrollerIndicator(false)
                        keepItemAlive(true)
                    }
                    event {
                        pageIndexDidChanged {
                            val index = (it as? JSONObject)?.optInt("index", 0) ?: 0
                            ctx.onTabChange(index)
                        }
                    }
                    trashList(ctx, 1)
                    trashList(ctx, 3)
                }
            }
        }
    }
}

/** 一个 Tab 的列表（type：1 = MV，3 = 音乐，同 Dart `_TrashList`）。 */
private fun ViewContainer<*, *>.trashList(ctx: TrashPage, type: Int) {
    val paging = if (type == 1) ctx.mvTrashPaging else ctx.musicTrashPaging
    View {
        attr { size(ctx.pageWidth, ctx.pageHeight) }
        SDRefreshList {
            attr {
                flex(1f)
                state = paging
                // 首屏骨架 5 × (72 + 12) = 420，占位块要放得下（空 / 失败态自身仍是 320 高、从顶部排）
                placeholderHeight = 420f
                loadingBuilder = {
                    View {
                        attr { padding(0f, 16f, 0f, 16f) }
                        for (i in 0 until 5) {
                            trashItemSkeleton()
                        }
                    }
                }
                emptyBuilder = {
                    View {
                        attr {
                            height(320f)
                            allCenter()
                        }
                        Image {
                            attr {
                                size(80f, 80f)
                                src((if (type == 1) AssetsImage.mvEmpty else AssetsImage.libraryEmpty).asset())
                            }
                        }
                        Text {
                            attr {
                                marginTop(16f)
                                text("trash_no_items".tr)
                                fontSize(14f)
                                color(Color(0x8AFFFFFFL))
                            }
                        }
                    }
                }
                errorBuilder = {
                    View {
                        attr {
                            height(320f)
                            padding(0f, 64f, 0f, 64f)
                            allCenter()
                        }
                        Image {
                            attr {
                                size(48f, 48f)
                                // Dart Icons.wifi_off_rounded（由 MaterialIcons 字形导出），white38
                                src("images/v3/wifi_off.png".asset())
                                tintColor(Color(0x61FFFFFFL))
                            }
                        }
                        Text {
                            attr {
                                marginTop(12f)
                                text("trash_load_failed".tr)
                                fontSize(14f)
                                color(Color(0xB3FFFFFFL))
                                textAlignCenter()
                                lines(0)
                            }
                        }
                        View {
                            attr {
                                marginTop(20f)
                                height(36f)
                                minWidth(100f)
                                padding(0f, 20f, 0f, 20f)
                                allCenter()
                                backgroundColor(UIColor.cF3EFF7)
                                borderRadius(53f)
                            }
                            event { click { ctx.onRetry(type) } }
                            Text {
                                attr {
                                    text("retry".tr)
                                    fontSize(14f)
                                    color(UIColor.c0A0A0A)
                                }
                            }
                        }
                    }
                }
            }
            items {
                vfor({ paging.list }) { item ->
                    View {
                        attr { padding(0f, 16f, 12f, 16f) }
                        trashItemCard(ctx, item)
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.trashItemCard(ctx: TrashPage, item: TrashItemModel) {
    View {
        attr {
            padding(12f, 16f, 12f, 16f)
            backgroundColor(Color(0x14FFFFFFL))
            borderRadius(14f)
            flexDirectionRow()
            alignItemsCenter()
        }
        View {
            attr { flex(1f) }
            Text {
                attr {
                    text(item.title)
                    fontSize(15f)
                    fontWeight600()
                    color(Color.WHITE)
                    lines(1)
                    textOverFlowTail()
                }
            }
            Text {
                attr {
                    marginTop(4f)
                    text("trash_n_days_remaining".trParams("n" to item.remainingDays.toString()))
                    fontSize(12f)
                    color(Color(0x8AFFFFFFL))
                    lines(1)
                }
            }
        }
        View { attr { width(8f) } }
        View {
            attr {
                padding(8f, 16f, 8f, 16f)
                backgroundColor(UIColor.cF3EFF7)
                borderRadius(20f)
            }
            event { click { ctx.onRecover(item) } }
            Text {
                attr {
                    text("trash_recover".tr)
                    fontSize(12f)
                    fontWeight600()
                    color(UIColor.c0A0A0A)
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.trashItemSkeleton() {
    View {
        attr {
            marginBottom(12f)
            height(72f)
            padding(12f)
            flexDirectionRow()
            alignItemsCenter()
        }
        SDShimmer {
            attr {
                absolutePositionAllZero()
                radius = 14f
                baseColor = SDColors.white20
                highlightColor = Color(0x59FFFFFFL)
            }
        }
    }
}
