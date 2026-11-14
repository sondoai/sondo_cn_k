package com.sondo.app.features.member.recall.widgets

import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.constants.asset
import com.sondo.app.data.models.RecallMedia
import com.sondo.app.nativeapi.SDVideo
import com.sondo.app.nativeapi.SDVideoState
import com.sondo.app.ui.SDFit
import com.sondo.app.ui.atoms.SDImage
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.View

class RecallMediaAttr : ComposeAttr() {
    var media: RecallMedia = RecallMedia()
    var mediaHeight: Float = 180f
    var videoId: String = ""

    /** 头图：静音、不画播放按钮。 */
    var isHero: Boolean = false

    /** 当前允许播放的就是本条（页面的 activeVideoId == videoId）。 */
    var active: Boolean by observable(false)
}

class RecallMediaEvent : ComposeEvent() {
    internal var playHandler: (() -> Unit)? = null
    internal var pauseHandler: (() -> Unit)? = null
    internal var videoFailedHandler: (() -> Unit)? = null

    fun onPlay(handler: () -> Unit) { playHandler = handler }
    fun onPause(handler: () -> Unit) { pauseHandler = handler }

    /** 视频出错：页面清掉这条的播放权。 */
    fun onVideoFailed(handler: () -> Unit) { videoFailedHandler = handler }
}

/**
 * 回归页媒体块（Dart: recall_page.dart `_RecallMedia`）。
 * 图片失败收起，避免大块空白；视频失败只留封面，不再提供重试。
 * 视频只在被允许播放时挂载原生播放器（`deferInitUntilActive`），其余时间显示封面。
 */
class RecallMediaView : ComposeView<RecallMediaAttr, RecallMediaEvent>() {
    private var imageFailed by observable(false)
    private var videoFailed by observable(false)

    override fun createAttr(): RecallMediaAttr = RecallMediaAttr()
    override fun createEvent(): RecallMediaEvent = RecallMediaEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        val media = ctx.attr.media
        val h = ctx.attr.mediaHeight
        return {
            if (media.isVideo) {
                View {
                    attr {
                        height(h)
                        alignSelfStretch()
                    }
                    // 封面一直垫在下面；没有封面时是卡片底色
                    if (media.coverUrl.isNotEmpty()) {
                        SDImage {
                            attr {
                                absolutePositionAllZero()
                                url = media.coverUrl
                                fit = SDFit.COVER
                            }
                        }
                    } else {
                        View {
                            attr {
                                absolutePositionAllZero()
                                backgroundColor(UIColor.c1C1C1C)
                            }
                        }
                    }
                    vif({ !ctx.videoFailed && ctx.attr.active }) {
                        SDVideo {
                            attr {
                                absolutePositionAllZero()
                                src(media.mediaUrl)
                                autoPlay(true)
                                loop(true)
                                muted(ctx.attr.isHero)
                                volume(if (ctx.attr.isHero) 0f else 1f)
                                fitCover()
                            }
                            event {
                                onState { state ->
                                    if (state.state == SDVideoState.ERROR && !ctx.videoFailed) {
                                        ctx.videoFailed = true
                                        ctx.event.videoFailedHandler?.invoke()
                                    }
                                }
                            }
                        }
                    }
                    if (!ctx.attr.isHero) {
                        View {
                            attr {
                                absolutePositionAllZero()
                                allCenter()
                            }
                            vif({ !ctx.videoFailed && ctx.attr.active }) {
                                // 正在播：点任意处停，不显示暂停按钮
                                View {
                                    attr { absolutePositionAllZero() }
                                    event { click { ctx.event.pauseHandler?.invoke() } }
                                }
                            }
                            velse {
                                vif({ !ctx.videoFailed }) {
                                    View {
                                        attr {
                                            padding(4f, 4f, 4f, 4f)
                                            backgroundColor(Color(0, 0, 0, 0.35f))
                                            borderRadius(50f)
                                        }
                                        event { click { ctx.event.playHandler?.invoke() } }
                                        Image {
                                            attr {
                                                size(46f, 46f)
                                                src(AssetsImage.play2.asset())
                                                tintColor(Color.WHITE)
                                                resizeContain()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (media.hasImage) {
                vif({ !ctx.imageFailed }) {
                    SDImage {
                        attr {
                            height(h)
                            alignSelfStretch()
                            url = media.mediaUrl
                            fit = SDFit.COVER
                        }
                        // 图片失败后收起媒体区，卡片标题仍保留。
                        event { loadFailure { ctx.imageFailed = true } }
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.RecallMediaBlock(init: RecallMediaView.() -> Unit) {
    addChild(RecallMediaView(), init)
}
