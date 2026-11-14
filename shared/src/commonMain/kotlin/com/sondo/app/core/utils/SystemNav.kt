package com.sondo.app.core.utils

import com.sondo.app.base.AppEnv
import com.sondo.app.nativeapi.SDAppModule
import com.sondo.app.nativeapi.nativeModule

/**
 * Android 系统导航模式（对应参考工程 app.dart 里的 system_nav 通道 + SystemNavMode）。
 * 三键导航时底栏改黑色，并把页面抬到按钮上方；手势导航保持透明底。其余平台恒为手势样式。
 */
object SystemNavMode {
    /** 是否三键导航；取不到时按手势处理。导航模式可在系统设置里切换，需要时重新读取。 */
    fun isThreeButton(): Boolean {
        if (!AppEnv.isAndroid) return false
        return try {
            nativeModule<SDAppModule>(SDAppModule.MODULE_NAME).getSystemNavMode().optBoolean("threeButton", false)
        } catch (e: Throwable) {
            false
        }
    }

    /** 三键导航栏高度（dp）；手势导航或取不到时为 0。 */
    fun threeButtonInset(): Float {
        if (!AppEnv.isAndroid) return 0f
        return try {
            val mode = nativeModule<SDAppModule>(SDAppModule.MODULE_NAME).getSystemNavMode()
            if (mode.optBoolean("threeButton", false)) mode.optDouble("bottomInset", 0.0).toFloat() else 0f
        } catch (e: Throwable) {
            0f
        }
    }
}
