package com.sondo.app.ui.atoms

import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.i18n.tr
import com.sondo.app.nativeapi.SDLottie
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.SDFont
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.Rotate
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/** Dart `LoadingType`。 */
enum class SDLoadingType { LOTTIE, IMAGE }

class SDLoadingAttr : ComposeAttr() {
    /** 边长（Dart LoadingWidget.size，默认 40） */
    var size: Float = 40f
    var type: SDLoadingType = SDLoadingType.IMAGE
    /** lottie 资源（相对 assets/common/） */
    var lottieAsset: String = "lottie/HBlJYYr2Zn.json"
    /** 图片转圈时的着色 */
    var color: Color? = null
}

/**
 * Dart `LoadingWidget` → `SDLoading`：居中的转圈。
 * - IMAGE（默认）：`loading_icon.png` 每秒匀速转一圈
 * - LOTTIE：播放 [SDLoadingAttr.lottieAsset]
 */
class SDLoadingView : ComposeView<SDLoadingAttr, ComposeEvent>() {
    private var spinning by observable(false)

    override fun createAttr(): SDLoadingAttr = SDLoadingAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun viewDidLoad() {
        super.viewDidLoad()
        setTimeout(pagerId, 16) { spinning = true }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { allCenter() }
            if (ctx.attr.type == SDLoadingType.LOTTIE) {
                SDLottie {
                    attr {
                        size(ctx.attr.size, ctx.attr.size)
                        src(ctx.attr.lottieAsset)
                        loop(true)
                        autoPlay(true)
                    }
                }
            } else {
                Image {
                    attr {
                        size(ctx.attr.size, ctx.attr.size)
                        src(ImageUri.commonAssets(AssetsImage.loadingIcon))
                        resizeContain()
                        tintColor(ctx.attr.color)
                        transform(Rotate(if (ctx.spinning) 360f else 0f))
                        animate(Animation.linear(1f).repeatForever(true), ctx.spinning)
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDLoading(init: SDLoadingView.() -> Unit) {
    addChild(SDLoadingView(), init)
}

/**
 * Dart `LoadingUtil.musicLoading(text:)`：80×80 的 music_loading 动画 + 可选文字（12 白，间距 10）。
 * 全局 Loading（`showLoading()`）和列表首屏加载都用它。
 */
fun ViewContainer<*, *>.SDMusicLoading(text: String = "", size: Float = 80f) {
    View {
        attr { allCenter() }
        SDLottie {
            attr {
                size(size, size)
                src("lottie/music_loading.json")
                loop(true)
                autoPlay(true)
            }
        }
        if (text.isNotEmpty()) {
            Text {
                attr {
                    marginTop(10f)
                    text(text)
                    fontSize(12f)
                    color(Color.WHITE)
                }
            }
        }
    }
}

// ============================================================================
// 空态 / 错误态 / 加载态
// ============================================================================

class SDEmptyAttr : ComposeAttr() {
    /** 图片（AssetsImage.xxx）；空串不显示。参考工程空态图统一 80×80 */
    var image: String by observable("")
    var imageSize: Float = 80f
    /** 文案；默认 `no_data` */
    var text: String by observable("")
    /** 文案颜色 / 字号（Dart：white54，列表页 14、作品库 12） */
    var textColor: Color = Color(0x8AFFFFFFL)
    var fontSize: Float = 14f
    /** 按钮文案；空串不显示按钮 */
    var buttonText: String by observable("")
    /** 图片与文案间距 */
    var gap: Float = 16f
}

class SDEmptyEvent : ComposeEvent() {
    internal var buttonHandler: (() -> Unit)? = null
    fun onButtonTap(handler: () -> Unit) { buttonHandler = handler }
}

/**
 * 空态视图：图 + 文案 + 可选胶囊按钮（Dart 各列表页的 `Image.asset(xxxEmpty, 80) + TextWidget('no_data'.tr)`，
 * 作品库的「去创作 / 登录」按钮：高 32、最小宽 100、#F3EFF7、字 12 w600）。
 * 默认 `flex(1f)` 居中；固定高度时自己在 attr 里 `height(500f)`。
 */
class SDEmptyStateView : ComposeView<SDEmptyAttr, SDEmptyEvent>() {
    override fun createAttr(): SDEmptyAttr = SDEmptyAttr()
    override fun createEvent(): SDEmptyEvent = SDEmptyEvent()

    override fun willInit() {
        super.willInit()
        attr.flex(1f)
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { allCenter() }
            vif({ ctx.attr.image.isNotEmpty() }) {
                Image {
                    attr {
                        size(ctx.attr.imageSize, ctx.attr.imageSize)
                        marginBottom(ctx.attr.gap)
                        src(ImageUri.commonAssets(ctx.attr.image))
                        resizeContain()
                    }
                }
            }
            Text {
                attr {
                    text(ctx.attr.text.ifEmpty { "no_data".tr })
                    fontSize(ctx.attr.fontSize)
                    color(ctx.attr.textColor)
                    textAlignCenter()
                    margin(0f, 24f, 0f, 24f)
                }
            }
            vif({ ctx.attr.buttonText.isNotEmpty() }) {
                View {
                    attr {
                        marginTop(16f)
                        height(32f)
                        minWidth(100f)
                        padding(0f, 16f, 0f, 16f)
                        borderRadius(53f)
                        backgroundColor(SDColors.primaryBtn)
                        allCenter()
                    }
                    event {
                        click { ctx.event.buttonHandler?.invoke() }
                    }
                    Text {
                        attr {
                            text(ctx.attr.buttonText)
                            fontSize(12f)
                            fontWeight600()
                            fontFamily(SDFont.poppins)
                            color(SDColors.primaryBtnText)
                        }
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDEmptyState(init: SDEmptyStateView.() -> Unit) {
    addChild(SDEmptyStateView(), init)
}

/**
 * 错误态：文案（默认 `request_failed`）+「重试」胶囊（Dart 标签弹层的失败态：`'no_data'` + `'retry'`）。
 */
fun ViewContainer<*, *>.SDErrorState(
    message: String = "",
    image: String = "",
    onRetry: (() -> Unit)? = null,
) {
    SDEmptyState {
        attr {
            this.image = image
            text = message.ifEmpty { "request_failed".tr }
            buttonText = if (onRetry != null) "retry".tr else ""
        }
        event {
            onButtonTap { onRetry?.invoke() }
        }
    }
}

/** 加载态：居中的 music_loading（Dart 列表首屏 `LoadingUtil.musicLoading()` / `LoadingWidget`）。 */
fun ViewContainer<*, *>.SDLoadingState(text: String = "", lottie: Boolean = true) {
    View {
        attr {
            flex(1f)
            allCenter()
        }
        if (lottie) {
            SDMusicLoading(text)
        } else {
            SDLoading { }
            if (text.isNotEmpty()) {
                Text {
                    attr {
                        marginTop(10f)
                        text(text)
                        fontSize(12f)
                        color(Color.WHITE)
                    }
                }
            }
        }
    }
}
