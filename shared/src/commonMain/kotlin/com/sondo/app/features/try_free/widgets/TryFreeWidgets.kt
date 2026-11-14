package com.sondo.app.features.try_free.widgets

import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.c
import com.sondo.app.core.constants.whiteA
import com.sondo.app.ui.anim.SDRotating
import com.sondo.app.ui.atoms.SDAssetImage
import com.sondo.app.ui.atoms.SDRingProgress
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.views.View

/** Try Free 页面颜色 token（Dart `TryFreeColors`）。 */
internal object TryFreeColors {
    val bgPrimary = c(0xFF0A0A0AL)
    val bgWaiting = c(0xFF000000L)
    val btnPrimary = c(0xFFF3EFF7L)
    val btnPrimaryText = c(0xFF0A0A0AL)

    val closeButtonBg get() = whiteA(0.1f)
    val uploadBorder get() = whiteA(0.1f)
    val uploadFill get() = whiteA(0.05f)
    val secondaryText get() = whiteA(0.6f)
}

/** 圆形关闭按钮（32 圆 + 20 close 图标），创建页与等待页共用。 */
internal fun ViewContainer<*, *>.TryFreeCloseButton(onTap: () -> Unit) {
    View {
        attr {
            size(32f, 32f)
            borderRadius(16f)
            backgroundColor(TryFreeColors.closeButtonBg)
            allCenter()
        }
        event { click { onTap() } }
        SDAssetImage(AssetsImage.close, 20f)
    }
}

/** Material `CircularProgressIndicator` 的近似：旋转的 1/4 弧。 */
internal fun ViewContainer<*, *>.TryFreeSpinner(diameter: Float, stroke: Float, color: Color) {
    SDRotating {
        attr {
            size(diameter, diameter)
            periodS = 1f
        }
        content {
            SDRingProgress {
                attr {
                    size(diameter, diameter)
                    strokeWidth = stroke
                    progressColor = color
                    trackColor = Color(0, 0, 0, 0f)
                    progress = 0.25f
                }
            }
        }
    }
}
