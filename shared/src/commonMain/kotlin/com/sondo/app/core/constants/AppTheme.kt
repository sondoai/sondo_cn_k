package com.sondo.app.core.constants

import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.views.TextAttr

/**
 * 应用主题常量（对应 Flutter 侧 AppTheme）。
 *
 * 参考工程亮 / 暗两套 ThemeData 取值完全相同（scaffold 都是黑底），这里只保留一套；
 * ThemeController（明暗切换）在参考工程里没有调用方，不移植。
 */
object AppTheme {
    /** colorScheme.primary */
    val primary: Color = Color(0xFF000000L)
    /** colorScheme.secondary（Colors.white60） */
    val secondary: Color = Color(0x99FFFFFFL)
    /** colorScheme.surface */
    val surface: Color = Color(0xFFFFFFFFL)
    /** colorScheme.onSurface */
    val onSurface: Color = Color(0xFF000000L)
    /** colorScheme.error（Colors.red） */
    val error: Color = Color(0xFFF44336L)
    /** colorScheme.inversePrimary */
    val inversePrimary: Color = Color(0xFFFFFFFFL)
    /** colorScheme.surfaceContainer */
    val surfaceContainer: Color = Color(0xFF131313L)

    /** scaffoldBackgroundColor */
    val scaffoldBackground: Color = Color(0xFF000000L)
    /** AppBar：透明底、白色图标、标题居中 17 号白字 */
    val appBarBackground: Color = Color.TRANSPARENT
    val appBarIcon: Color = Color(0xFFFFFFFFL)
    const val appBarTitleSize = 17f
    /** 卡片圆角 */
    const val cardRadius = 8f

    /** 输入框光标 / 选择手柄颜色 */
    val cursorColor: Color = Color(0xFFFFFFFFL)
    /** 选中文本背景色（白 20%） */
    val selectionColor: Color = Color(0x33FFFFFFL)

    /** 状态栏样式：深色页面用浅色图标（SDAppModule.setStatusBarStyle 的取值）。 */
    const val statusBarStyle = "light"
    /** 三键导航时底栏底色（手势导航为透明）。 */
    val threeButtonNavBarColor: Color = Color(0xFF000000L)

    /** AppBar 标题文字样式。 */
    fun TextAttr.appBarTitle(): TextAttr = fontSize(appBarTitleSize).color(appBarIcon).lines(1).textOverFlowTail()

    /** 全局 Toast 文字样式（黑字，行高 1.3，最多 3 行，居中）。 */
    fun TextAttr.toastText(): TextAttr = fontSize(14f).color(Color(0xFF000000L)).lineHeight(14f * 1.3f).lines(3).textAlignCenter()

    /** 全局 Toast 底色（白 80%，圆角 34，水平内边距 20、垂直 6，最大宽度 80%）。 */
    val toastBackground: Color = Color(0xCCFFFFFFL)
    const val toastRadius = 34f
}
