package com.sondo.app.ui.works.upload

import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.formFieldBorderColor
import com.sondo.app.data.models.RoleModel
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDLoading
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.velseif
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/** Dart `CreatorPhotoUploadColors`。 */
object CreatorPhotoUploadColors {
    val cardFill: Color = Color(255, 255, 255, 0.05f)
    val cardBorder: Color = Color(255, 255, 255, 0.1f)
    val labelText: Color = Color.WHITE
    val secondaryText: Color = Color(255, 255, 255, 0.6f)
    val actionButtonBg: Color = Color(255, 255, 255, 0.1f)
    val previewOverlay: Color = Color(0, 0, 0, 0.6f)
}

class SDCreatorPhotoUploadSectionAttr : ComposeAttr() {
    /** obs：已上传通过的图片 URL。 */
    var imageUrl: String by observable("")
    /** obs：本地待上传 / 检测中的图片路径（Dart `File? imageFile`）。 */
    var imageFile: String? by observable(null)
    /** obs：当前选中的四视图角色。 */
    var role: RoleModel by observable(RoleModel())
    var isImageLoading: Boolean by observable(false)
    var isImageFailed: Boolean by observable(false)
    var isHasSensitive: Boolean by observable(false)
    var errorMessage: String by observable("")
    /** obs：0~100。 */
    var imageProgress: Int by observable(0)
    var showSectionTitle: Boolean = true
    var sectionTitleKey: String = "upload"
    var emptyLabelKey: String = "upload"
    /** obs：校验错误红框。 */
    var hasError: Boolean by observable(false)
    var cardFill: Color? = null
}

class SDCreatorPhotoUploadSectionEvent : ComposeEvent() {
    internal var uploadTap: (() -> Unit)? = null
    internal var clearImage: (() -> Unit)? = null
    internal var retryUpload: (() -> Unit)? = null

    fun onUploadTap(h: () -> Unit) { uploadTap = h }
    fun onClearImage(h: () -> Unit) { clearImage = h }
    fun onRetryUpload(h: () -> Unit) { retryUpload = h }
}

/**
 * Dart `CreatorPhotoUploadSection`：创作页参考图上传区（空态 80 高上传框 / 已选卡片含进度、失败重试、替换 / 删除条、敏感提示）。
 * 选图、上传、检测由调用方完成，这里只展示状态。
 */
class SDCreatorPhotoUploadSectionView : ComposeView<SDCreatorPhotoUploadSectionAttr, SDCreatorPhotoUploadSectionEvent>() {
    override fun createAttr(): SDCreatorPhotoUploadSectionAttr = SDCreatorPhotoUploadSectionAttr()
    override fun createEvent(): SDCreatorPhotoUploadSectionEvent = SDCreatorPhotoUploadSectionEvent()

    private fun hasImage(): Boolean {
        val a = attr
        return a.imageUrl.isNotEmpty() || a.imageFile != null || (a.role.id > 0 && a.role.roleImg.isNotEmpty())
    }

    private fun imageSrc(): String {
        val a = attr
        if (a.role.id > 0 && a.role.roleImg.isNotEmpty()) return a.role.roleImg
        if (a.imageUrl.isNotEmpty()) return a.imageUrl
        return a.imageFile ?: ""
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val a = ctx.attr
        val fill = a.cardFill ?: CreatorPhotoUploadColors.cardFill
        return {
            if (a.showSectionTitle) {
                Text {
                    attr {
                        margin(0f, 4f, 10f, 4f)
                        text(a.sectionTitleKey.tr)
                        fontSize(14f)
                        fontFamily("Poppins-SemiBold")
                        color(CreatorPhotoUploadColors.labelText)
                    }
                }
            }
            vif({ !ctx.hasImage() }) {
                View {
                    attr {
                        height(80f)
                        backgroundColor(fill)
                        borderRadius(16f)
                        border(Border(if (a.hasError) 1.5f else 1f, BorderStyle.SOLID, formFieldBorderColor(a.hasError, CreatorPhotoUploadColors.cardBorder)))
                        allCenter()
                    }
                    event { click { ctx.event.uploadTap?.invoke() } }
                    Image {
                        attr {
                            size(18f, 18f)
                            src(ImageUri.commonAssets(AssetsImage.uploadIcon))
                            tintColor(SDColors.white80)
                        }
                    }
                    Text {
                        attr {
                            marginTop(8f)
                            text(a.emptyLabelKey.tr)
                            fontSize(10f)
                            fontFamily("Poppins-Medium")
                            color(CreatorPhotoUploadColors.secondaryText)
                        }
                    }
                }
            }
            velse {
                View {
                    View {
                        attr {
                            padding(14f)
                            backgroundColor(fill)
                            borderRadius(16f)
                            border(Border(if (a.hasError) 1.5f else 1f, BorderStyle.SOLID, formFieldBorderColor(a.hasError, CreatorPhotoUploadColors.cardBorder)))
                        }
                        View {
                            attr {
                                height(80f)
                                borderRadius(12f)
                                backgroundColor(CreatorPhotoUploadColors.actionButtonBg)
                                alignItemsCenter()
                                overflow(true)
                            }
                            View {
                                attr { size(80f, 80f) }
                                SDImage {
                                    attr {
                                        absolutePositionAllZero()
                                        url = ctx.imageSrc()
                                    }
                                }
                                vif({ a.isImageLoading }) {
                                    View {
                                        attr {
                                            absolutePositionAllZero()
                                            backgroundColor(Color(0, 0, 0, 0.45f))
                                            allCenter()
                                        }
                                        // Dart CircularProgressIndicator（不确定进度）+ 百分比文字
                                        SDLoading {
                                            attr {
                                                absolutePosition(top = 22f, left = 22f)
                                                size = 36f
                                                color = Color.WHITE
                                            }
                                        }
                                        Text {
                                            attr {
                                                text("${a.imageProgress}%")
                                                fontSize(10f)
                                                color(Color.WHITE)
                                            }
                                        }
                                    }
                                }
                                velseif({ a.isImageFailed }) {
                                    View {
                                        attr {
                                            absolutePositionAllZero()
                                            backgroundColor(Color(0, 0, 0, 0.45f))
                                            allCenter()
                                        }
                                        event { click { ctx.event.retryUpload?.invoke() } }
                                        Text {
                                            attr {
                                                text("retry".tr)
                                                fontSize(12f)
                                                color(Color.WHITE)
                                            }
                                        }
                                    }
                                }
                            }
                            vif({ !a.isImageLoading }) {
                                View {
                                    attr {
                                        absolutePosition(left = 0f, right = 0f, bottom = 0f)
                                        height(20f)
                                        padding(2f, 4f, 2f, 4f)
                                        backgroundColor(CreatorPhotoUploadColors.previewOverlay)
                                        borderRadius(0f, 0f, 12f, 12f)
                                        flexDirectionRow()
                                        alignItemsCenter()
                                    }
                                    View {
                                        attr {
                                            flex(1f)
                                            allCenter()
                                        }
                                        event { click { ctx.event.uploadTap?.invoke() } }
                                        Image {
                                            attr {
                                                size(12f, 12f)
                                                src(ImageUri.commonAssets(AssetsImage.musicVideoGenReload))
                                            }
                                        }
                                    }
                                    View {
                                        attr {
                                            size(1f, 10f)
                                            backgroundColor(SDColors.white20)
                                        }
                                    }
                                    View {
                                        attr {
                                            flex(1f)
                                            allCenter()
                                        }
                                        event { click { ctx.event.clearImage?.invoke() } }
                                        Image {
                                            attr {
                                                size(12f, 12f)
                                                src(ImageUri.commonAssets(AssetsImage.aDelete))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    vif({ a.isHasSensitive && a.errorMessage.isNotEmpty() }) {
                        Text {
                            attr {
                                marginTop(6f)
                                text(a.errorMessage)
                                fontSize(11f)
                                fontFamily("Poppins-Medium")
                                color(Color(0xFFFF5252L))
                                textAlignCenter()
                            }
                        }
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDCreatorPhotoUploadSection(init: SDCreatorPhotoUploadSectionView.() -> Unit) {
    addChild(SDCreatorPhotoUploadSectionView(), init)
}
