package com.sondo.app.ui.works

import com.sondo.app.service.AppNav
import com.sondo.app.base.BasePager
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.utils.DevLog
import com.sondo.app.service.PlayerService
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDMarqueeText
import com.sondo.app.ui.atoms.SDProgressBar
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.View

/**
 * Dart `NavPlayerWidget`：迷你播放条。外边距 8/4；条：圆角 8、#4D4D4D 60% 底（参考工程叠 σ4 毛玻璃，这里用同色半透明近似）、
 * 内边距 4；封面 40 + 12 + 跑马灯标题 + 上一首 / 播放暂停 / 下一首；下方 2 高进度线（左右 8，200ms 过渡）。
 */
class SDNavPlayerView : ComposeView<ComposeAttr, ComposeEvent>() {
    override fun createAttr(): ComposeAttr = ComposeAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    private fun navigateToSongDetail() {
        val song = PlayerService.currentSong ?: return
        if (song.musicGenerateStatus != 3) return
        val pager = getPager() as? BasePager ?: return
        val route = AppNav.currentRoute()
        DevLog.logger("songDetail===========$route")
        pager.nav.push(Routes.musicDetail, JSONObject().put("id", song.id).put("type", "nav").put("original", route))
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val ws = worksState
        ws.watchPosition()
        return {
            attr { margin(4f, 8f, 4f, 8f) }
            View {
                attr {
                    borderRadius(8f)
                    backgroundColor(Color(0x994D4D4DL))
                    padding(4f, 4f, 4f, 4f)
                    flexDirectionRow()
                    alignItemsCenter()
                }
                View {
                    attr { size(40f, 40f) }
                    vif({ ws.hasCurrentSong }) {
                        SDImage {
                            attr {
                                size(40f, 40f)
                                borderRadius(4f)
                                url = ws.currentCover
                            }
                            event { click { ctx.navigateToSongDetail() } }
                        }
                    }
                }
                View {
                    attr {
                        flex(1f)
                        marginLeft(12f)
                        height(40f)
                        justifyContentCenter()
                    }
                    event { click { ctx.navigateToSongDetail() } }
                    SDMarqueeText {
                        attr {
                            flex(1f)
                            size = 14f
                            text = ws.currentTitle
                        }
                    }
                }
                ctrlButton({ AssetsImage.navPrev }, 18f) { PlayerService.launch { PlayerService.playPrevious() } }
                ctrlButton({ if (ws.isPlaying) AssetsImage.navPause else AssetsImage.navPlay }, 24f) {
                    PlayerService.launch { PlayerService.playOrPause() }
                }
                ctrlButton({ AssetsImage.navNext }, 18f) { PlayerService.launch { PlayerService.playNext() } }
            }
            View {
                attr { padding(0f, 8f, 0f, 8f) }
                SDProgressBar {
                    attr {
                        barHeight = 2f
                        trackColor = Color(0x4DFFFFFFL)
                        color = Color.WHITE
                        animationS = 0.2f
                        progress = ws.progress
                    }
                }
            }
        }
    }
}

/** Material IconButton(padding: zero, compact)：M3 最小点击区，每个按钮布局占 40×40，图标居中。 */
private fun ViewContainer<*, *>.ctrlButton(icon: () -> String, width: Float, onTap: () -> Unit) {
    View {
        attr {
            size(40f, 40f)
            allCenter()
        }
        event { click { onTap() } }
        Image {
            attr {
                size(width, width)
                resizeContain()
                src(ImageUri.commonAssets(icon()))
            }
        }
    }
}

/** Dart `NavPlayerWidget`：迷你播放条（绑定 PlayerService）。显隐由宿主（主框架）决定。 */
fun ViewContainer<*, *>.SDNavPlayer(init: SDNavPlayerView.() -> Unit = {}) {
    addChild(SDNavPlayerView(), init)
}
