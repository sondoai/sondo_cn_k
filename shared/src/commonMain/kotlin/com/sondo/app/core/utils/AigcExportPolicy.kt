package com.sondo.app.core.utils

/** 下载水印开关与是否烧录可见水印的纯规则。 */
class AigcWatermarkToggle(var enabled: Boolean = true) {

    /** 从开到关必须重新声明。已经是关时不会再走关闭流程。 */
    val mustDeclareBeforeOff: Boolean get() = enabled

    fun turnOn() {
        enabled = true
    }

    /** 取消声明或保存失败都保持开启。只有同意并且保存成功才关闭。 */
    fun finishTurnOff(accepted: Boolean, saved: Boolean): Boolean {
        if (!accepted || !saved) return enabled
        enabled = false
        return enabled
    }

    companion object {
        /** 非会员不能直接关闭，要先走订阅。 */
        fun needsSubscribe(turningOff: Boolean, isMember: Boolean): Boolean = turningOff && !isMember
    }
}

object AigcExportPolicy {
    /** 图片始终烧可见水印。视频只在有效会员、开关已关、且本次无水印导出记录成功时跳过。 */
    fun burnVisible(
        isVideo: Boolean,
        isMember: Boolean,
        watermarkEnabled: Boolean,
        unmarkedExportAllowed: Boolean,
    ): Boolean {
        if (!isVideo) return true
        if (!isMember || watermarkEnabled || !unmarkedExportAllowed) return true
        return false
    }
}
