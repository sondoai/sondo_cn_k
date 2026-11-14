package com.sondo.app.ui.atoms

import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.SDFit
import com.sondo.app.ui.SDFont
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Canvas
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/** Dart `UploadImageStatus`（null = 无状态）。 */
enum class SDUploadImageStatus { NONE, UPLOADING, FAILED }

class SDUploadImageAttr : ComposeAttr() {
    /** 图片地址；以 https 开头视为上传成功（与 Dart 一致） */
    var url: String by observable("")
    /** 上传进度 0..100；负数不显示百分比 */
    var progress: Float by observable(-1f)
    var status: SDUploadImageStatus by observable(SDUploadImageStatus.NONE)
    var fit: SDFit = SDFit.COVER
    /** 圆角（Dart 默认 10） */
    var radius: Float = 10f
    /** 是否显示右上角清除按钮（Dart: `onClear != null`） */
    var clearable: Boolean = true
    /** 失败时是否可重试（Dart: `onRetry != null`） */
    var retryable: Boolean = true
}

class SDUploadImageEvent : ComposeEvent() {
    internal var clear: (() -> Unit)? = null
    internal var emptyTap: (() -> Unit)? = null
    internal var retry: (() -> Unit)? = null
    internal var imageTap: (() -> Unit)? = null

    fun onClear(handler: () -> Unit) { clear = handler }
    fun onEmptyTap(handler: () -> Unit) { emptyTap = handler }
    fun onRetry(handler: () -> Unit) { retry = handler }
    fun onImageTap(handler: () -> Unit) { imageTap = handler }
}

/**
 * Dart `UploadImageWidget` → `SDUploadImage`：图片上传格（空 / 上传中 / 失败 / 成功四态）。
 * 尺寸用 `size(w, h)`。点击优先级：失败且可重试 → onRetry；空 → onEmptyTap；成功 → onImageTap。
 */
class SDUploadImageView : ComposeView<SDUploadImageAttr, SDUploadImageEvent>() {
    override fun createAttr(): SDUploadImageAttr = SDUploadImageAttr()
    override fun createEvent(): SDUploadImageEvent = SDUploadImageEvent()

    private val isEmpty get() = attr.url.isEmpty()
    private val isUploading get() = attr.status == SDUploadImageStatus.UPLOADING
    private val isFailed get() = attr.status == SDUploadImageStatus.FAILED
    private val isSuccess get() = attr.url.startsWith("https")
    private val showClear get() = attr.clearable && (isFailed || isSuccess || (attr.url.isNotEmpty() && !isUploading))

    private fun onTap() {
        if (isFailed && attr.retryable) {
            event.retry?.invoke()
            return
        }
        if (isEmpty) {
            event.emptyTap?.invoke()
            return
        }
        if (isSuccess) event.imageTap?.invoke()
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                backgroundColor(SDColors.white10)
                borderRadius(ctx.attr.radius)
                allCenter()
            }
            event {
                click { ctx.onTap() }
            }

            vif({ ctx.isEmpty && !ctx.isUploading }) {
                Image {
                    attr {
                        size(24f, 24f)
                        src(ImageUri.commonAssets(AssetsImage.uploadPhoto))
                        resizeContain()
                    }
                }
            }
            vif({ ctx.attr.url.isNotEmpty() }) {
                SDImage {
                    attr {
                        absolutePositionAllZero()
                        borderRadius(ctx.attr.radius)
                        url = ctx.attr.url
                        fit = ctx.attr.fit
                    }
                }
            }
            vif({ ctx.isUploading }) {
                View {
                    attr {
                        absolutePositionAllZero()
                        borderRadius(ctx.attr.radius)
                        backgroundColor(Color(0, 0, 0, 0.35f))
                        allCenter()
                    }
                    SDLoading {
                        attr {
                            val w = ctx.flexNode.styleWidth
                            size = if (w.isNaN()) 24f else (w * 0.4f).coerceIn(24f, 36f)
                        }
                    }
                    vif({ ctx.attr.progress >= 0f }) {
                        View {
                            attr {
                                absolutePositionAllZero()
                                allCenter()
                            }
                            Text {
                                attr {
                                    text("${ctx.attr.progress.toInt()}%")
                                    fontSize(10f)
                                    fontWeight500()
                                    fontFamily(SDFont.poppins)
                                    color(Color.WHITE)
                                }
                            }
                        }
                    }
                }
            }
            vif({ ctx.isFailed && ctx.attr.retryable }) {
                View {
                    attr {
                        size(28f, 28f)
                        borderRadius(14f)
                        backgroundColor(Color(0, 0, 0, 0.6f))
                    }
                    Image {
                        attr {
                            size(28f, 28f)
                            src(ImageUri.commonAssets(AssetsImage.uploadRetry))
                            resizeContain()
                        }
                    }
                }
            }
            vif({ ctx.showClear }) {
                // 右上角清除：14 圆、黑底 60% 透明度、白色叉
                View {
                    attr {
                        absolutePosition(top = 0f, right = 0f)
                        padding(4f, 5f, 5f, 4f)
                    }
                    event { click { ctx.event.clear?.invoke() } }
                    View {
                        attr {
                            size(14f, 14f)
                            borderRadius(7f)
                            backgroundColor(Color.BLACK)
                            opacity(0.6f)
                            allCenter()
                        }
                        Canvas({ attr { size(10f, 10f) } }) { context, w, h ->
                            context.beginPath()
                            context.strokeStyle(Color.WHITE)
                            context.lineWidth(1.2f)
                            context.lineCapRound()
                            context.moveTo(w * 0.25f, h * 0.25f)
                            context.lineTo(w * 0.75f, h * 0.75f)
                            context.moveTo(w * 0.75f, h * 0.25f)
                            context.lineTo(w * 0.25f, h * 0.75f)
                            context.stroke()
                        }
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDUploadImage(init: SDUploadImageView.() -> Unit) {
    addChild(SDUploadImageView(), init)
}
