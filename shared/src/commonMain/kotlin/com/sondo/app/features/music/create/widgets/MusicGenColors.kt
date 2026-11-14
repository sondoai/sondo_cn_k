package com.sondo.app.features.music.create.widgets

import com.sondo.app.core.constants.whiteA
import com.tencent.kuikly.core.base.Color

/** 音乐创作页配色（Dart: create/widgets/colors.dart `MusicGenColors`）。 */
internal object MusicGenColors {
    val bgPrimary = Color(0xFF0A0A0AL)
    val btnPrimary = Color(0xFFF3EFF7L)
    val btnPrimaryText = Color(0xFF0A0A0AL)
    val chipSelectedText = Color(0xFF020202L)
    val tabContainer = Color(0xCC222222L)

    val cardFill = whiteA(0.05f)
    val cardBorder = whiteA(0.1f)
    val tabSelected = whiteA(0.1f)
    val labelText = Color.WHITE
    val labelText2 = whiteA(0.8f)
    val hintText = whiteA(0.4f)
    val secondaryText = whiteA(0.6f)
    val counterText = whiteA(0.4f)
    val chipUnselected = whiteA(0.1f)
    val actionButtonBg = whiteA(0.1f)
}

/** Dart `FontFamily.poppins` 的各字重（Android 上不能再叠 fontWeight）。 */
internal object PoppinsFace {
    const val regular = "Poppins"
    const val medium = "Poppins-Medium"
    const val semiBold = "Poppins-SemiBold"
    const val bold = "Poppins-Bold"
}
