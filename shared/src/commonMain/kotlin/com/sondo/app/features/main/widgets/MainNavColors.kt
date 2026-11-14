package com.sondo.app.features.main.widgets

import com.sondo.app.core.constants.c
import com.sondo.app.core.constants.whiteA
import com.tencent.kuikly.core.base.Color

/** 底部浮动导航的颜色与尺寸 token（Dart `MainNavColors`）。 */
internal object MainNavColors {
    val containerFill: Color get() = c(0x33333333L)
    val containerBorder: Color get() = c(0x1AFFFFFFL)
    val playerZoneFill: Color get() = c(0x994D4D4DL)
    val zoneDivider: Color get() = c(0x33FFFFFFL)

    const val blurSigma = 15f
    const val barHeight = 54f
    const val horizontalPadding = 20f
    const val verticalPadding = 10f
    const val innerPadding = 6f
    const val itemGap = 8f
    const val sideItemHeight = 42f
    const val iconSize = 24f
    const val orbSizeCollapsed = barHeight
    const val orbSizeExpanded = 44f
    const val navOrbSize = 42f
    const val cardGap = 8f
    const val playerCardCollapsedWidth = barHeight
    const val navOrbExpandedWidth = 168f
    const val squeezePillRadius = 100f
    const val squeezeCircleRadius = barHeight / 2f
    const val squeezeContentFadeThreshold = 0.5f

    /** 控件最小宽度 + 标题与控件之间的一个 [itemGap]（与 MainPlayerControls.minLayoutWidth 同步）。 */
    const val playerExpandedMinContentWidth = 76f + itemGap

    /** 收起态进度环宽度。 */
    const val progressStrokeWidth = 2f

    /** 展开态进度环宽度。 */
    const val progressStrokeWidthExpanded = 1.5f
    val progressColor: Color get() = Color.WHITE

    /** 白色 5% 轨道。 */
    val progressTrackColor: Color get() = c(0x0DFFFFFFL)

    /** 底部导航选中底，白色 5%。 */
    val selectedFill: Color get() = c(0x0DFFFFFFL)
    const val swipeThreshold = 48f
    const val swipeVelocityThreshold = 300f
    const val maxDragOffset = 12f

    const val orbSlideDurationMs = 280
    const val navExpandDurationMs = 200

    val iconActive: Color get() = Color.WHITE
    val iconInactive: Color get() = whiteA(0.4f)

    /** 浮动导航相对页面底边的总占位（含底部安全区），Dart `MainFloatingNavBar.totalHeight`。 */
    fun totalHeight(bottomInset: Float): Float = bottomInset + verticalPadding * 2f + barHeight
}

/** Curves.easeInOutCubic */
internal fun easeInOutCubic(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return if (x < 0.5f) 4f * x * x * x else {
        val f = -2f * x + 2f
        1f - f * f * f / 2f
    }
}

internal fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t
