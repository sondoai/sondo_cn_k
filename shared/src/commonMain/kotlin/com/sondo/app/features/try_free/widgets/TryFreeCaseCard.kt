package com.sondo.app.features.try_free.widgets

import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.constants.whiteA
import com.sondo.app.features.try_free.data.TryFreeCaseEntity
import com.sondo.app.nativeapi.SDVideo
import com.sondo.app.nativeapi.SDVideoState
import com.sondo.app.nativeapi.SDVideoView
import com.sondo.app.service.audio.AppAudioOutputCoordinator
import com.sondo.app.ui.SDFit
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDShimmer
import com.sondo.app.ui.basePager
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/** 案例卡尺寸（Dart: 280 × 480，圆角 16）。 */
internal object TryFreeCaseCardSize {
    const val width = 280f
    const val height = 480f
    const val radius = 16f
}

class TryFreeCaseVideoAttr : ComposeAttr() {
    var url: String = ""
    var coverUrl: String = ""
    /** 当前轮播页且未被全屏挂起：只有它挂载解码器（deferInitUntilActive） */
    var active: Boolean by observable(false)
    /** 登录弹窗期间软暂停：保留解码器与当前帧 */
    var forcePaused: Boolean by observable(false)
    /** 解码器就绪后 seek 一次的位置（毫秒）；null 不 seek */
    var seekOnReadyMs: Long? by observable<Long?>(null)
}

class TryFreeCaseVideoEvent : ComposeEvent() {
    internal var seekApplied: (() -> Unit)? = null
    internal var position: ((Long?) -> Unit)? = null

    /** seek 完成，外层清空 pending */
    fun onSeekApplied(handler: () -> Unit) { seekApplied = handler }

    /** 播放进度（毫秒）；解码器释放时回调 null */
    fun onPosition(handler: (Long?) -> Unit) { position = handler }
}

/**
 * 案例卡视频（Dart `VideoWidget` 的 try_free 用法：isLopper / isFill / volume 1 / needLoading /
 * deferInitUntilActive / bypassPlaybackGate / forcePaused / initCompleted 里 seek）。
 * 出声，所以加入 [AppAudioOutputCoordinator]。
 */
class TryFreeCaseVideoView : ComposeView<TryFreeCaseVideoAttr, TryFreeCaseVideoEvent>() {
    private var videoRef: ViewRef<SDVideoView>? = null
    private var firstFrameShown by observable(false)
    private var buffering by observable(true)
    private var playerState = SDVideoState.IDLE
    private var readyHandled = false
    private var detachAudio: (() -> Unit)? = null
    private var alive = true

    override fun createAttr(): TryFreeCaseVideoAttr = TryFreeCaseVideoAttr()
    override fun createEvent(): TryFreeCaseVideoEvent = TryFreeCaseVideoEvent()

    override fun created() {
        super.created()
        detachAudio = AppAudioOutputCoordinator.attach(
            owner = this,
            isPlaying = { playerState == SDVideoState.PLAYING },
            isAudible = { true },
            pauseForHandoff = { videoRef?.view?.pause() },
        )
        bindValueChange({ attr.active }) {
            if (!attr.active) onUnmounted()
        }
        bindValueChange({ attr.forcePaused }) {
            if (attr.forcePaused) {
                videoRef?.view?.pause()
                AppAudioOutputCoordinator.release(this)
            } else if (attr.active && readyHandled) {
                startPlay()
            }
        }
    }

    override fun viewDestroyed() {
        alive = false
        unbindAllValueChange()
        detachAudio?.invoke()
        detachAudio = null
        super.viewDestroyed()
    }

    private fun onUnmounted() {
        videoRef = null
        readyHandled = false
        playerState = SDVideoState.IDLE
        firstFrameShown = false
        buffering = true
        AppAudioOutputCoordinator.release(this)
        event.position?.invoke(null)
    }

    private fun startPlay() {
        val pager = basePager ?: return
        pager.launch {
            val token = AppAudioOutputCoordinator.claim(this@TryFreeCaseVideoView)
            if (!alive || !attr.active || attr.forcePaused) return@launch
            if (AppAudioOutputCoordinator.isCurrent(this@TryFreeCaseVideoView, token)) videoRef?.view?.play()
        }
    }

    private fun onPlayerState(state: SDVideoState) {
        playerState = state.state
        buffering = state.isBuffering
        if (!readyHandled && (state.state == SDVideoState.READY || state.state == SDVideoState.PLAYING)) {
            readyHandled = true
            val seek = attr.seekOnReadyMs
            if (seek != null) {
                // 全屏关闭后解码器重建：seek 到关闭时进度再续播
                videoRef?.view?.seekTo(seek)
                event.seekApplied?.invoke()
            }
            if (!attr.forcePaused) startPlay()
        }
        if (state.state == SDVideoState.PAUSED || state.state == SDVideoState.COMPLETED) {
            AppAudioOutputCoordinator.release(this)
        }
    }

    /** Dart 资源路径 `assets/xxx` → 原生公共资源相对路径 `xxx`。 */
    private fun playSrc(url: String): String = if (url.startsWith("assets/")) url.removePrefix("assets/") else url

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                borderRadius(TryFreeCaseCardSize.radius)
                backgroundColor(UIColor.pageBg)
            }
            SDImage {
                attr {
                    absolutePositionAllZero()
                    url = ctx.attr.coverUrl
                    fit = SDFit.COVER
                }
            }
            vif({ ctx.attr.active }) {
                SDVideo {
                    ref { ctx.videoRef = it }
                    attr {
                        absolutePositionAllZero()
                        src(ctx.playSrc(ctx.attr.url))
                        autoPlay(false)
                        loop(true)
                        volume(1f)
                        fitCover()
                        opacity(if (ctx.firstFrameShown) 1f else 0f)
                    }
                    event {
                        onState { ctx.onPlayerState(it) }
                        onFirstFrame { ctx.firstFrameShown = true }
                        onProgress { ctx.event.position?.invoke(it.positionMs) }
                    }
                }
            }
            // needLoading：激活且首帧未出 / 缓冲中
            vif({ ctx.attr.active && (!ctx.firstFrameShown || ctx.buffering) && !ctx.attr.forcePaused }) {
                View {
                    attr {
                        absolutePositionAllZero()
                        allCenter()
                    }
                    TryFreeSpinner(28f, 2.5f, Color.WHITE)
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.TryFreeCaseVideo(init: TryFreeCaseVideoView.() -> Unit) {
    addChild(TryFreeCaseVideoView(), init)
}

/**
 * 单个案例卡片（Dart `TryFreeCaseCard`）：视频预览 + 底部渐变信息层。
 */
internal fun ViewContainer<*, *>.TryFreeCaseCard(
    caseItem: TryFreeCaseEntity,
    isActiveProvider: () -> Boolean,
    isPausedProvider: () -> Boolean,
    seekOnReadyProvider: () -> Long?,
    onSeekApplied: () -> Unit,
    onPosition: (Long?) -> Unit,
) {
    View {
        attr {
            size(TryFreeCaseCardSize.width, TryFreeCaseCardSize.height)
            borderRadius(TryFreeCaseCardSize.radius)
        }
        TryFreeCaseVideo {
            attr {
                absolutePositionAllZero()
                url = caseItem.videoUrl
                coverUrl = caseItem.coverUrl
                active = isActiveProvider()
                forcePaused = isPausedProvider()
                seekOnReadyMs = seekOnReady(isActiveProvider, seekOnReadyProvider)
            }
            event {
                onSeekApplied { onSeekApplied() }
                onPosition { onPosition(it) }
            }
        }
        View {
            attr {
                absolutePosition(left = 0f, right = 0f, bottom = 0f)
                height(160f)
                backgroundLinearGradient(
                    Direction.TO_BOTTOM,
                    ColorStop(Color(0, 0, 0, 0f), 0.66f),
                    ColorStop(Color(10, 10, 10, 0.6f), 0.96f),
                )
            }
        }
        View {
            attr { absolutePosition(left = 12f, right = 12f, bottom = 14f) }
            SDImage {
                attr {
                    size(54f, 54f)
                    borderRadius(27f)
                    url = caseItem.avatarUrl
                }
            }
            View {
                attr { marginTop(8f); padding(0f, 4f, 0f, 4f) }
                Text {
                    attr {
                        text(caseItem.title)
                        lines(1)
                        textOverFlowTail()
                        fontSize(14f)
                        fontFamily("Poppins-SemiBold")
                        color(Color.WHITE)
                    }
                }
                Text {
                    attr {
                        marginTop(6f)
                        text(caseItem.description)
                        lines(10)
                        textOverFlowTail()
                        fontSize(10f)
                        fontFamily("Poppins-Medium")
                        color(TryFreeColors.secondaryText)
                    }
                }
            }
        }
    }
}

private fun seekOnReady(isActive: () -> Boolean, seek: () -> Long?): Long? = if (isActive()) seek() else null

/** 模板加载中的骨架（与案例卡同尺寸，Dart `_buildSkeleton`）。 */
internal fun ViewContainer<*, *>.TryFreeCaseSkeleton() {
    val base = whiteA(0.28f)
    val highlight = whiteA(0.45f)
    val bar = whiteA(0.7f)
    View {
        attr {
            height(TryFreeCaseCardSize.height)
            allCenter()
        }
        View {
            attr {
                size(TryFreeCaseCardSize.width, TryFreeCaseCardSize.height)
                borderRadius(TryFreeCaseCardSize.radius)
            }
            SDShimmer {
                attr {
                    absolutePositionAllZero()
                    baseColor = base
                    highlightColor = highlight
                    radius = TryFreeCaseCardSize.radius
                }
            }
            View {
                attr { absolutePosition(left = 12f, right = 12f, bottom = 14f) }
                View { attr { size(54f, 54f); borderRadius(27f); backgroundColor(bar) } }
                View {
                    attr { marginTop(8f); padding(0f, 4f, 0f, 4f) }
                    View { attr { size(120f, 14f); borderRadius(4f); backgroundColor(bar) } }
                    View { attr { marginTop(6f); height(10f); borderRadius(4f); backgroundColor(bar) } }
                    View { attr { marginTop(4f); size(180f, 10f); borderRadius(4f); backgroundColor(bar) } }
                }
            }
        }
    }
}

