package com.sondo.app.ui

import com.sondo.app.base.BasePager
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.views.TextAttr

/**
 * UI Kit 的设计常量（颜色 / 字体 / 尺寸）。
 *
 * 与参考工程一致：全局深色，常用色大多是「白色按透明度」叠在黑底上；
 * 这里只收敛「在多个组件里重复出现」的值，页面私有的颜色照抄 `c(0x…)` 即可。
 */
object SDColors {
    /** 弹层 / 弹窗底色（Dart: CommonDialogColors.sheetBg） */
    val sheetBg = Color(0xFF1C1C1CL)
    /** 弹层遮罩（Dart: CommonDialogColors.barrier，黑 70%） */
    val barrier = Color(0xB3000000L)
    /** 普通弹窗遮罩（SmartDialog / Get.dialog 默认黑 50%） */
    val mask50 = Color(0x80000000L)
    /** 主按钮底色 / 文字色 */
    val primaryBtn = Color(0xFFF3EFF7L)
    val primaryBtnText = Color(0xFF0A0A0AL)
    /** 次按钮底色（白 20%） */
    val secondaryBtn = Color(0x33FFFFFFL)
    /** 强调紫 */
    val accentPurple = Color(0xFF9B64FFL)
    val brandPurple = Color(0xFF7F58F7L)
    val tagPurple = Color(0xFF9C70FFL)
    val progressPurple = Color(0xFFC685FFL)
    val linkPurple = Color(0xFFB59CFFL)
    /** 品牌渐变 #E661F6 → #7F58F7 */
    val gradientStart = Color(0xFFE661F6L)
    val gradientEnd = Color(0xFF7F58F7L)
    val errorRed = Color(0xFFFF5451L)

    val white100 = Color(0xFFFFFFFFL)
    val white80 = Color(0xCCFFFFFFL)
    val white60 = Color(0x99FFFFFFL)
    val white50 = Color(0x80FFFFFFL)
    val white40 = Color(0x66FFFFFFL)
    val white30 = Color(0x4DFFFFFFL)
    val white20 = Color(0x33FFFFFFL)
    val white10 = Color(0x1AFFFFFFL)
    val white5 = Color(0x0DFFFFFFL)

    /** 品牌渐变的两个色标（横向）。 */
    fun brandStops(): Array<ColorStop> = arrayOf(ColorStop(gradientStart, 0f), ColorStop(gradientEnd, 1f))
}

/** 字体族（Dart: FontFamily.poppins 等；字体文件由宿主工程注册）。 */
object SDFont {
    const val poppins = "Poppins"
    const val anton = "Anton"
    const val bangers = "Bangers"
    const val montserrat = "Montserrat"
}

/** 字重（Dart FontWeight.wXXX / bold / normal）。 */
enum class SDWeight { W300, W400, W500, W600, W700, W800, W900 }

/** 对齐（Dart TextAlign）。 */
enum class SDTextAlign { START, CENTER, END }

/** 图片填充（Dart BoxFit）。 */
enum class SDFit { COVER, CONTAIN, FILL }

fun TextAttr.weight(w: SDWeight?): TextAttr {
    when (w) {
        SDWeight.W300 -> fontWeightLight()
        SDWeight.W400 -> fontWeight400()
        SDWeight.W500 -> fontWeight500()
        SDWeight.W600 -> fontWeight600()
        SDWeight.W700 -> fontWeight700()
        SDWeight.W800 -> fontWeight800()
        SDWeight.W900 -> fontWeight900()
        null -> {}
    }
    return this
}

fun TextAttr.align(a: SDTextAlign): TextAttr {
    when (a) {
        SDTextAlign.START -> textAlignLeft()
        SDTextAlign.CENTER -> textAlignCenter()
        SDTextAlign.END -> textAlignRight()
    }
    return this
}

/**
 * 一次性按 Dart `TextStyle` / `TextWidget` 的参数名设置文字样式（写在 `attr { }` 里，随响应式重跑）。
 *
 * @param height 行高「倍数」（Dart TextStyle.height），内部换算成 `lineHeight(size * height)`
 * @param maxLines 0 表示不限行数
 */
fun TextAttr.textStyle(
    size: Float = 14f,
    color: Color = Color.WHITE,
    weight: SDWeight? = null,
    height: Float? = null,
    maxLines: Int? = null,
    fontFamily: String? = null,
    textAlign: SDTextAlign? = null,
    letterSpacing: Float? = null,
): TextAttr {
    fontSize(size)
    color(color)
    weight(weight)
    if (height != null) lineHeight(size * height)
    if (maxLines != null) lines(maxLines)
    if (fontFamily != null) fontFamily(fontFamily)
    if (textAlign != null) align(textAlign)
    if (letterSpacing != null) letterSpacing(letterSpacing)
    return this
}

/** 组件所在页面（组件内取状态栏高度 / 弹 toast 等）。 */
val DeclarativeBaseView<*, *>.basePager: BasePager?
    get() = getPager() as? BasePager
