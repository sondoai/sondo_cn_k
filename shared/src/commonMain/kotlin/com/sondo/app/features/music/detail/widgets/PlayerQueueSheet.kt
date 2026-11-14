package com.sondo.app.features.music.detail.widgets

import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.i18n.tr
import com.sondo.app.features.music.detail.MusicDetailPage
import com.sondo.app.ui.atoms.SDAudioVisualizer
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.dialog.showAppBottomSheet
import com.sondo.app.ui.overlay.DialogHandle
import com.tencent.kuikly.core.base.Anchor
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.Rotate
import com.tencent.kuikly.core.base.Scale
import com.tencent.kuikly.core.base.Skew
import com.tencent.kuikly.core.base.Translate
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vforIndex
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.List
import com.tencent.kuikly.core.views.ListView
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import kotlin.math.roundToInt

/** 打开播放队列弹层（Dart `PlayerQueueWidget`：60% 屏高、#1C1C1C、顶部圆角 20）。 */
internal fun MusicDetailPage.showPlayerQueueSheet(): DialogHandle {
    val page = this
    val height = screenHeight * 0.6f
    // Get.bottomSheet 默认 enableDrag: true：整块可下拉关闭（列表 / 长按排序的条目会优先消费手势）
    return showAppBottomSheet(
        enableDrag = true,
        showHandle = false,
        topRadius = 20f,
        maxHeight = height,
        onDismiss = { page.queueSheet = null },
    ) { _ ->
        addChild(PlayerQueueView()) {
            attr {
                this.page = page
                height(height - page.safeBottom)
            }
        }
    }
}

internal class PlayerQueueAttr : ComposeAttr() {
    lateinit var page: MusicDetailPage
}

/** 播放队列：长按拖动排序、删除（正在播放的不可删）、点击切歌。 */
internal class PlayerQueueView : ComposeView<PlayerQueueAttr, ComposeEvent>() {
    private var dragIndex by observable(-1)
    private var dragDy by observable(0f)
    private var dragStartY = 0f
    private var listRef: ViewRef<ListView<*, *>>? = null
    private var lastSyncedIndex = -1

    override fun createAttr(): PlayerQueueAttr = PlayerQueueAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    private fun offsetForIndex(index: Int): Float = if (index > 2) (index - 2) * ITEM_EXTENT else 0f

    override fun viewDidLoad() {
        super.viewDidLoad()
        // 打开时当前歌曲在第 3 行附近
        lastSyncedIndex = attr.page.currentIndex
        attr.page.postDelayed(16) { listRef?.view?.setContentOffset(0f, offsetForIndex(lastSyncedIndex), false) }
        // 切歌后对齐可视区
        bindValueChange({ attr.page.currentIndex }) { v ->
            val index = v as Int
            if (index == lastSyncedIndex) return@bindValueChange
            lastSyncedIndex = index
            attr.page.postDelayed(16) {
                val list = listRef?.view ?: return@postDelayed
                val contentH = list.contentView?.frame?.height ?: 0f
                val max = (contentH - list.frame.height).coerceAtLeast(0f)
                list.setContentOffset(0f, offsetForIndex(index).coerceIn(0f, max), false)
            }
        }
    }

    override fun viewDestroyed() {
        unbindAllValueChange()
        super.viewDestroyed()
    }

    /** 当前歌曲的播放指示状态（AudioVisualizer status）。 */
    private fun visualizerStatus(): String {
        val p = attr.page
        val playing = p.isPlaying
        return when (p.processingState) {
            "ready" -> if (playing) "pause" else "player"
            "loading" -> "loading"
            "buffering" -> if (playing) "loading" else "player"
            "idle" -> "player"
            "completed" -> if (playing) "pause" else "player"
            else -> "loading"
        }
    }

    private fun onLongPress(index: Int, state: String, pageY: Float) {
        when (state) {
            "start" -> {
                dragIndex = index
                dragStartY = pageY
                dragDy = 0f
            }
            "move" -> if (dragIndex >= 0) dragDy = pageY - dragStartY
            else -> {
                val from = dragIndex
                if (from < 0) return
                val size = attr.page.queue.size
                val to = (from + (dragDy / ITEM_EXTENT).roundToInt()).coerceIn(0, (size - 1).coerceAtLeast(0))
                dragIndex = -1
                dragDy = 0f
                if (to != from) attr.page.reorderQueue(from, to)
            }
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val page = ctx.attr.page
        return {
            View {
                attr {
                    padding(16f, 20f, 8f, 12f)
                    flexDirectionRow()
                }
                Text {
                    attr {
                        text("${"play_queue".tr} (${page.queue.size})")
                        fontSize(16f)
                        fontWeightBold()
                        color(Color.WHITE)
                    }
                }
            }
            vif({ page.queue.isEmpty() }) {
                View {
                    attr {
                        flex(1f)
                        allCenter()
                    }
                    Text {
                        attr {
                            text("queue_empty".tr)
                            fontSize(14f)
                            color(Color(0x8AFFFFFFL))
                        }
                    }
                }
            }
            velse {
                List {
                    ref { ctx.listRef = it }
                    attr {
                        flex(1f)
                        showScrollerIndicator(false)
                        scrollEnable(ctx.dragIndex < 0)
                    }
                    vforIndex({ page.queue }) { song, index, _ ->
                        View {
                            attr {
                                height(ITEM_EXTENT)
                                flexDirectionRow()
                                alignItemsCenter()
                                val isCurrent = index == page.currentIndex
                                backgroundColor(if (isCurrent) Color(255, 255, 255, 0.06f) else Color.TRANSPARENT)
                                val dragging = ctx.dragIndex == index
                                zIndex(if (dragging) 10 else 0)
                                transform(
                                    Rotate.DEFAULT,
                                    if (dragging) Scale(1.05f, 1.05f) else Scale.DEFAULT,
                                    Translate(0f, 0f, 0f, if (dragging) ctx.dragDy else 0f),
                                    Anchor.DEFAULT,
                                    Skew.DEFAULT,
                                )
                            }
                            event {
                                click { page.playSongFromQueue(index) }
                                longPress { p -> ctx.onLongPress(index, p.state, p.pageY) }
                            }
                            View { attr { width(16f) } }
                            SDImage {
                                attr {
                                    size(44f, 44f)
                                    borderRadius(6f)
                                    url = song.musicCoverUrl ?: ""
                                }
                            }
                            View {
                                attr {
                                    flex(1f)
                                    marginLeft(12f)
                                    justifyContentCenter()
                                }
                                Text {
                                    attr {
                                        val isCurrent = index == page.currentIndex
                                        text(song.musicTitle ?: "")
                                        fontSize(14f)
                                        lines(1)
                                        textOverFlowTail()
                                        // 当前歌曲：参考工程是 #3EA2FF→#E548FF 渐变字，这里取渐变中段色
                                        if (isCurrent) fontWeightBold() else fontWeight600()
                                        color(if (isCurrent) Color(0xFF9175FFL) else Color.WHITE)
                                    }
                                }
                                Text {
                                    attr {
                                        marginTop(2f)
                                        text(song.nicknameStr)
                                        fontSize(12f)
                                        lines(1)
                                        textOverFlowTail()
                                        color(Color(0x8AFFFFFFL))
                                    }
                                }
                            }
                            vif({ index == page.currentIndex }) {
                                View {
                                    attr { marginRight(8f) }
                                    SDAudioVisualizer {
                                        attr {
                                            status = ctx.visualizerStatus()
                                            color = Color(0xFF7F58F7L)
                                            barCount = 3
                                            size = 32f
                                        }
                                    }
                                }
                            }
                            velse {
                                // 删除（正在播放的歌曲不可删除）
                                View {
                                    attr {
                                        size(48f, 48f)
                                        allCenter()
                                    }
                                    event { click { page.removeFromQueue(index) } }
                                    Image {
                                        attr {
                                            size(22f, 22f)
                                            src(ImageUri.commonAssets(AssetsImage.close))
                                            resizeContain()
                                        }
                                    }
                                }
                            }
                            View { attr { width(8f) } }
                        }
                    }
                    View { attr { height(20f) } }
                }
            }
        }
    }

    companion object {
        const val ITEM_EXTENT = 64f
    }
}
