package com.sondo.app.core.utils

import com.sondo.app.base.BasePager
import com.sondo.app.base.currentPager

/**
 * 全局 Loading（对应 Flutter 侧 LoadingUtil）。
 * Kuikly 的浮层属于单个页面：记住打开 Loading 的页面，关闭时关同一个，避免跨页面后关不掉。
 */
object LoadingUtil {
    private var owner: BasePager? = null
    private var count = 0

    fun show(text: String? = null, pager: BasePager? = null) {
        val target = pager ?: owner ?: currentPager() ?: return
        if (owner != null && owner !== target) closeAll()
        owner = target
        count++
        target.showLoading(text ?: "")
    }

    fun isOpenLoading(): Boolean = count > 0 && owner != null

    /** 关闭全部 Loading（参考工程的 close 一次关掉整个 loading 层）。 */
    fun close() = closeAll()

    private fun closeAll() {
        val target = owner
        owner = null
        count = 0
        if (target != null && isPagerAlive(target.pagerId)) target.overlay.hideLoading(force = true)
    }
}
