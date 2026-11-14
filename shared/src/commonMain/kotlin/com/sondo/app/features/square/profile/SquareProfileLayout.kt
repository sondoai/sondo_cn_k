package com.sondo.app.features.square.profile

import com.tencent.kuikly.core.base.Color

/** 个人主页布局常量与滚动联动计算（Dart `SquareProfileLayout`，设计稿基准宽度 360）。 */
internal object SquareProfileLayout {
    const val avatarSize = 80f
    const val horizontalPadding = 16f
    const val nicknameHeight = 22f
    const val statRowHeight = 18f
    const val listItemSpacing = 30f
    const val topBarHeight = 56f
    const val panelCornerRadius = 20f

    /** 毛玻璃模糊强度（Dart sigma 6）。 */
    const val panelBlurSigma = 6f

    /** 毛玻璃区域叠色透明度（Video / Music）。 */
    val panelBlurOverlayAlphas = floatArrayOf(0.32f, 0.38f)

    const val tabBarPillHeight = 32f
    const val tabBarVerticalPadding = 14f
    val tabBarPinnedHeight: Float get() = tabBarVerticalPadding * 2 + tabBarPillHeight

    /** 展开态：面板顶部距屏幕顶部的比例。 */
    const val panelExpandedRatio = 150f / 360f

    /** 顶部 Banner 高宽比。 */
    const val bannerAspectRatio = 240f / 360f

    /** 用户信息淡出速度。 */
    const val profileInfoFadeOutRatio = 0.7f

    /** 完全收起时 Banner 黑色蒙层最大不透明度。 */
    const val bannerOverlayAlpha = 0.7f

    /** 可滚动用户信息区总高度（含顶部 padding）。 */
    const val profileContentExtent = 20f + avatarSize + 20f + nicknameHeight + 14f + statRowHeight

    /** 头部完全收起所需的滚动距离。 */
    const val headerCollapseExtent = 20f + profileContentExtent

    fun bannerHeight(screenWidth: Float) = screenWidth * bannerAspectRatio

    fun panelExpandedTop(screenWidth: Float) = screenWidth * panelExpandedRatio

    fun panelCollapsedTop(topInset: Float) = topInset + topBarHeight

    fun collapseProgress(scrollOffset: Float) = (scrollOffset / headerCollapseExtent).coerceIn(0f, 1f)

    fun profileInfoOpacity(progress: Float) = (1f - progress / profileInfoFadeOutRatio).coerceIn(0f, 1f)

    fun panelTop(progress: Float, screenWidth: Float, topInset: Float): Float {
        val expanded = panelExpandedTop(screenWidth)
        val collapsed = panelCollapsedTop(topInset)
        return expanded + (collapsed - expanded) * progress
    }

    /** 毛玻璃叠色（两个 Tab 都是 #0A0A0A，只插值透明度）。 */
    fun panelBlurTintAt(page: Float): Color {
        val p = page.coerceIn(0f, 1f)
        val alpha = panelBlurOverlayAlphas[0] + (panelBlurOverlayAlphas[1] - panelBlurOverlayAlphas[0]) * p
        return Color(10, 10, 10, alpha)
    }
}
