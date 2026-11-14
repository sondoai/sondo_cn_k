package com.sondo.app.ui.works

import com.sondo.app.ui.SDColors
import com.sondo.app.ui.atoms.SDLoading
import com.sondo.app.ui.atoms.SDLoadingType
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/** Dart `Color.fromARGB(209, 255, 255, 255)`：状态遮罩上的文字色。 */
internal val overlayTextColor = Color(0xD1FFFFFFL)

/**
 * 列表项底部的操作胶囊（Dart `_buildMusicButton` / `_buildImageButton`）：
 * 高 34、左右 12、白 10% 底、图标 18 + 8 + 文字 12 w600 白 80%。
 */
internal fun ViewContainer<*, *>.worksChip(
    icon: String,
    text: String,
    radius: Float,
    tint: Color? = null,
    marginRight: Float = 8f,
    onTap: () -> Unit,
) {
    View {
        attr {
            height(34f)
            padding(0f, 12f, 0f, 12f)
            marginRight(marginRight)
            // Wrap runSpacing 8；所在容器用 marginBottom(-8f) 抵消末行
            marginBottom(8f)
            backgroundColor(SDColors.white10)
            borderRadius(radius)
            flexDirectionRow()
            alignItemsCenter()
            justifyContentCenter()
        }
        event { click { onTap() } }
        Image {
            attr {
                size(18f, 18f)
                src(ImageUri.commonAssets(icon))
                resizeContain()
                if (tint != null) tintColor(tint)
            }
        }
        Text {
            attr {
                marginLeft(8f)
                text(text)
                fontSize(12f)
                color(SDColors.white80)
                fontWeight600()
                lines(1)
            }
        }
    }
}

/** 分享进行中时替代胶囊的转圈占位（高 34、左右 12）。 */
internal fun ViewContainer<*, *>.worksChipLoading(radius: Float, marginRight: Float = 8f) {
    View {
        attr {
            height(34f)
            padding(0f, 12f, 0f, 12f)
            marginRight(marginRight)
            marginBottom(8f)
            backgroundColor(SDColors.white10)
            borderRadius(radius)
            allCenter()
        }
        SDLoading { attr { size = 16f } }
    }
}

/** 统计项：图标 16 + 4 + 文字 12 白 50%。 */
internal fun ViewContainer<*, *>.worksStat(icon: String, count: String, iconSize: Float = 16f, color: Color = SDColors.white50) {
    View {
        attr {
            flexDirectionRow()
            alignItemsCenter()
        }
        Image {
            attr {
                size(iconSize, iconSize)
                src(ImageUri.commonAssets(icon))
                resizeContain()
            }
        }
        Text {
            attr {
                marginLeft(4f)
                text(count)
                fontSize(12f)
                color(color)
                lines(1)
            }
        }
    }
}

/** 统计项之间的竖线：1×12，白 20%，左右 10。 */
internal fun ViewContainer<*, *>.worksStatDivider() {
    View {
        attr {
            size(1f, 12f)
            margin(0f, 10f, 0f, 10f)
            backgroundColor(SDColors.white20)
        }
    }
}

/** 生成中 / 失败遮罩里的「图标 + 文案」列（Dart 36 图标 / lottie + 8 + 12 w600）。 */
internal fun ViewContainer<*, *>.worksStatusColumn(icon: String?, text: String) {
    View {
        attr { allCenter() }
        if (icon == null) {
            SDLoading {
                attr {
                    size = 36f
                    type = SDLoadingType.LOTTIE
                }
            }
        } else {
            Image {
                attr {
                    size(36f, 36f)
                    src(ImageUri.commonAssets(icon))
                    resizeContain()
                }
            }
        }
        Text {
            attr {
                marginTop(8f)
                text(text)
                fontSize(12f)
                color(overlayTextColor)
                fontWeight600()
                textAlignCenter()
            }
        }
    }
}
