package com.sondo.app.core.constants

import com.tencent.kuikly.core.base.Color

/** 通用色板（与 Flutter 侧 UIColor 一一对应，ARGB）。 */
object UIColor {
    val transparent10 = Color(0x1AFFFFFFL)
    val transparent20 = Color(0x33FFFFFFL)
    val transparent30 = Color(0x4DFFFFFFL)
    val transparent40 = Color(0x66FFFFFFL)
    val transparent50 = Color(0x80FFFFFFL)
    val transparent60 = Color(0x99FFFFFFL)
    val transparent70 = Color(0xB3FFFFFFL)
    val transparent80 = Color(0xCCFFFFFFL)
    val transparent90 = Color(0xE6FFFFFFL)
    val cFFFFFF = Color(0xFFFFFFFFL)
    val black6 = Color(0x0F000000L)
    val black10 = Color(0x1A000000L)
    val black20 = Color(0x33000000L)
    val black30 = Color(0x4D000000L)
    val black40 = Color(0x66000000L)
    val black50 = Color(0x80000000L)
    val black60 = Color(0x99000000L)
    val black70 = Color(0xB3000000L)
    val black80 = Color(0xCC000000L)
    val black90 = Color(0xE6000000L)
    val black100 = Color(0xFF000000L)
    val cF3EFF7 = Color(0xFFF3EFF7L)
    val c0A0A0A = Color(0xFF0A0A0AL)
    val c1C1C1C = Color(0xFF1C1C1CL)
    val cFFF80 = Color(0xCCFFFFFFL)
    val cFFF75 = Color(0xBFFFFFFFL)
    val cFFF60 = Color(0x99FFFFFFL)
    val cFFF50 = Color(0x80FFFFFFL)
    val cFFF40 = Color(0x66FFFFFFL)
    val cFFF20 = Color(0x33FFFFFFL)
    val cFFF10 = Color(0x1AFFFFFFL)

    // —— 语义色 —— //
    val white = cFFFFFF
    val black = black100
    val transparent = Color.TRANSPARENT
    /** 页面底色 */
    val pageBg = c0A0A0A
    /** 卡片底色 */
    val cardBg = c1C1C1C
}

/** `0xAARRGGBB` → Color，方便照抄 Flutter 的 Color(0x…)。 */
fun c(argb: Long): Color = Color(argb)

/** 白色按透明度。 */
fun whiteA(alpha: Float): Color = Color(255, 255, 255, alpha)

/** 黑色按透明度。 */
fun blackA(alpha: Float): Color = Color(0, 0, 0, alpha)
