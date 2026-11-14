package com.sondo.app.ui.works

import com.sondo.app.data.models.SongModel
import com.sondo.app.ui.atoms.SDAudioVisualizer
import com.sondo.app.ui.atoms.SDImage
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

class SDSongCardAttr : ComposeAttr() {
    var song: SongModel by observable(SongModel())
}

class SDSongCardEvent : ComposeEvent() {
    internal var addPlayListHandler: ((SongModel) -> Unit)? = null
    internal var onDetailHandler: ((SongModel) -> Unit)? = null
    fun addPlayList(handler: (SongModel) -> Unit) { addPlayListHandler = handler }
    fun onDetail(handler: (SongModel) -> Unit) { onDetailHandler = handler }
}

/** Dart `SongCardWidget`：首页横向歌曲卡片（宽 140 + 左右各 6 外边距）。 */
class SDSongCardView : ComposeView<SDSongCardAttr, SDSongCardEvent>() {
    override fun createAttr(): SDSongCardAttr = SDSongCardAttr()
    override fun createEvent(): SDSongCardEvent = SDSongCardEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        val ws = worksState
        return {
            attr {
                width(140f)
                margin(0f, 6f, 0f, 6f)
            }
            event { click { ctx.event.onDetailHandler?.invoke(ctx.attr.song) } }
            View {
                attr { size(140f, 140f) }
                SDImage {
                    attr {
                        absolutePositionAllZero()
                        borderRadius(12f)
                        url = ctx.attr.song.musicCoverUrl ?: ""
                    }
                }
                View {
                    attr { absolutePosition(right = 10f, bottom = 10f) }
                    event { click { ctx.event.addPlayListHandler?.invoke(ctx.attr.song) } }
                    SDAudioVisualizer {
                        attr {
                            color = Color.WHITE
                            barCount = 3
                            size = 34f
                            imageSize = 16f
                            status = ws.playIcon(ctx.attr.song.id)
                        }
                    }
                }
            }
            View { attr { height(8f) } }
            View {
                attr {
                    height(20f)
                    justifyContentCenter()
                }
                Text {
                    attr {
                        text(ctx.attr.song.musicTitle ?: "")
                        fontSize(14f)
                        fontFamily("Poppins")
                        lines(1)
                        textOverFlowTail()
                        color(Color.WHITE)
                    }
                }
            }
            View { attr { height(8f) } }
        }
    }
}

/** Dart `SongCardWidget`：首页横向歌曲卡片。 */
fun ViewContainer<*, *>.SDSongCard(init: SDSongCardView.() -> Unit) {
    addChild(SDSongCardView(), init)
}
