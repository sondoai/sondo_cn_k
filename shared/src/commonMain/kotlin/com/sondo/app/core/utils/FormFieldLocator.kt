package com.sondo.app.core.utils

import com.tencent.kuikly.core.base.Color

/** 根据校验态返回边框颜色（Colors.redAccent = 0xFFFF5252）。 */
fun formFieldBorderColor(hasError: Boolean, normalColor: Color): Color =
    if (hasError) Color(0xFFFF5252L) else normalColor
