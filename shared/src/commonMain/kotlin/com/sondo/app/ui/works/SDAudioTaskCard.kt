package com.sondo.app.ui.works

import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.toFixed
import com.sondo.app.data.models.AudioItemStatus
import com.sondo.app.data.models.AudioUploadItem
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.atoms.SDLoading
import com.sondo.app.ui.atoms.SDProgressBar
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

class SDAudioTaskCardAttr : ComposeAttr() {
    var item: AudioUploadItem by observable(AudioUploadItem())
    /** 当前播放中的条目键（AudioUploadItem.playbackKey） */
    var playingUploadId: String by observable("")
    var index: Int = 0
    var isFirstItem: Boolean by observable(false)
    var isLastItem: Boolean by observable(false)
    var isLoadingAudio: Boolean by observable(false)
    var isPlaying: Boolean by observable(false)
}

class SDAudioTaskCardEvent : ComposeEvent() {
    internal var onSelectHandler: ((AudioUploadItem) -> Unit)? = null
    internal var onPauseHandler: ((String) -> Unit)? = null
    internal var onResumeHandler: ((String) -> Unit)? = null
    internal var onRetryHandler: ((String) -> Unit)? = null
    internal var onRemoveHandler: ((AudioUploadItem) -> Unit)? = null
    internal var onTogglePlayHandler: ((AudioUploadItem) -> Unit)? = null

    fun onSelect(handler: (AudioUploadItem) -> Unit) { onSelectHandler = handler }
    fun onPause(handler: (uploadId: String) -> Unit) { onPauseHandler = handler }
    fun onResume(handler: (uploadId: String) -> Unit) { onResumeHandler = handler }
    fun onRetry(handler: (uploadId: String) -> Unit) { onRetryHandler = handler }
    fun onRemove(handler: (AudioUploadItem) -> Unit) { onRemoveHandler = handler }
    fun onTogglePlay(handler: (AudioUploadItem) -> Unit) { onTogglePlayHandler = handler }
}

/** Dart `AudioTaskCard`：上传音频行（分组列表：只有首尾两条带外侧圆角）。 */
class SDAudioTaskCardView : ComposeView<SDAudioTaskCardAttr, SDAudioTaskCardEvent>() {
    override fun createAttr(): SDAudioTaskCardAttr = SDAudioTaskCardAttr()
    override fun createEvent(): SDAudioTaskCardEvent = SDAudioTaskCardEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                padding(14f, 14f, 14f, 14f)
                backgroundColor(Color(0x1A94ADFFL))
                val top = if (ctx.attr.isFirstItem) 12f else 0f
                val bottom = if (ctx.attr.isLastItem) 12f else 0f
                borderRadius(top, top, bottom, bottom)
                flexDirectionRow()
                alignItemsCenter()
            }
            vbind({ listOf(ctx.attr.item, ctx.attr.playingUploadId, ctx.attr.isLoadingAudio, ctx.attr.isPlaying) }) {
                val item = ctx.attr.item
                val isCompleted = item.status == AudioItemStatus.completed
                val isFailed = item.status == AudioItemStatus.failed
                val isPaused = item.status == AudioItemStatus.paused
                val isActive = item.status.isActive
                val isThisItem = ctx.attr.playingUploadId == item.playbackKey
                val isLoading = isThisItem && ctx.attr.isLoadingAudio
                val isThisPlaying = isThisItem && ctx.attr.isPlaying
                // 左侧图标：已完成的可点播放 / 暂停
                View {
                    attr {
                        size(40f, 40f)
                        allCenter()
                    }
                    if (isCompleted) {
                        event { click { if (!isLoading) ctx.event.onTogglePlayHandler?.invoke(item) } }
                        if (isLoading) {
                            SDLoading {
                                attr {
                                    size = 22f
                                    color = SDColors.gradientStart
                                }
                            }
                        } else {
                            assetIcon(if (isThisPlaying) AssetsImage.uploadAudioPause else AssetsImage.uploadAudioPlay, 40f)
                        }
                    } else {
                        assetIcon(AssetsImage.uploadAudio, 40f)
                    }
                }
                // 中间：文件名 + 进度 / 大小
                View {
                    attr {
                        flex(1f)
                        marginLeft(12f)
                    }
                    event { click { if (item.status == AudioItemStatus.completed) ctx.event.onSelectHandler?.invoke(item) } }
                    Text {
                        attr {
                            text(item.fileName)
                            fontSize(14f)
                            fontFamily("Poppins-Medium")
                            lines(1)
                            textOverFlowTail()
                            color(if (isFailed) SDColors.white60 else Color(0xE6FFFFFFL))
                        }
                    }
                    View { attr { height(2f) } }
                    if (isFailed) {
                        Text {
                            attr {
                                text("upload_failed".tr)
                                fontSize(10f)
                                fontFamily("Poppins-Medium")
                                color(SDColors.errorRed)
                            }
                        }
                    } else if (isActive || isPaused) {
                        SDProgressBar {
                            attr {
                                barHeight = 4f
                                radius = 3f
                                trackColor = Color(0x26FFFFFFL)
                                gradientColors = listOf(SDColors.gradientStart, SDColors.gradientEnd)
                                progress = item.progress.toFloat().coerceIn(0f, 1f)
                            }
                        }
                        View { attr { height(6f) } }
                        fileSizeRow(item)
                    } else if (isCompleted) {
                        fileSizeRow(item)
                    }
                }
                View { attr { width(10f) } }
                when {
                    isActive -> actionCircle(
                        if (item.status == AudioItemStatus.waiting) AssetsImage.uploadAwait else AssetsImage.uploadStop,
                    ) { ctx.event.onPauseHandler?.invoke(item.uploadId) }
                    isPaused -> actionCircle(AssetsImage.uploadUp) { ctx.event.onResumeHandler?.invoke(item.uploadId) }
                    isFailed -> actionCircle(AssetsImage.uploadRetry) { ctx.event.onRetryHandler?.invoke(item.uploadId) }
                }
                View { attr { width(10f) } }
                if (item.status == AudioItemStatus.deleting) {
                    View {
                        attr {
                            size(28f, 28f)
                            borderRadius(14f)
                            backgroundColor(SDColors.white10)
                            allCenter()
                        }
                        SDLoading {
                            attr {
                                size = 18f
                                color = Color.WHITE
                            }
                        }
                    }
                } else {
                    actionCircle(AssetsImage.uploadDel) { ctx.event.onRemoveHandler?.invoke(item) }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.assetIcon(icon: String, size: Float) {
    Image {
        attr {
            size(size, size)
            src(ImageUri.commonAssets(icon))
        }
    }
}

private fun ViewContainer<*, *>.actionCircle(icon: String, onTap: () -> Unit) {
    View {
        attr { size(28f, 28f) }
        event { click { onTap() } }
        assetIcon(icon, 28f)
    }
}

/** Dart `_FileSizeRow.formatFileSize`。 */
internal fun formatUploadFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return "${(bytes / 1024.0).toFixed(1)}KB"
    if (bytes < 1024L * 1024 * 1024) return "${(bytes / (1024.0 * 1024)).toFixed(1)}MB"
    return "${(bytes / (1024.0 * 1024 * 1024)).toFixed(1)}GB"
}

/** Dart `_FileSizeRow.formatDuration`：毫秒 → 00:00。 */
internal fun formatUploadDuration(duration: Int?): String {
    if (duration == null) return "00:00"
    val total = duration / 1000
    return "${(total / 60).toString().padStart(2, '0')}:${(total % 60).toString().padStart(2, '0')}"
}

private fun ViewContainer<*, *>.fileSizeRow(item: AudioUploadItem) {
    View {
        attr {
            flexDirectionRow()
            alignItemsCenter()
        }
        sizeText(formatUploadFileSize(item.fileSize), 12f)
        View {
            attr {
                width(10f)
                allCenter()
            }
            sizeText("•", 10f)
        }
        sizeText(formatUploadDuration(item.duration), 12f)
    }
}

private fun ViewContainer<*, *>.sizeText(s: String, size: Float) {
    Text {
        attr {
            text(s)
            fontSize(size)
            fontFamily("Poppins-Medium")
            color(SDColors.white40)
        }
    }
}

/** Dart `AudioTaskCard`：上传音频行（MV 创作页音乐区 / 我的音频页）。 */
fun ViewContainer<*, *>.SDAudioTaskCard(init: SDAudioTaskCardView.() -> Unit) {
    addChild(SDAudioTaskCardView(), init)
}
