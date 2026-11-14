package com.sondo.app.ui.overlay

import com.sondo.app.base.BasePager
import com.sondo.app.core.utils.nowMs
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.timer.clearTimeout
import com.tencent.kuikly.core.timer.setTimeout

/** 一个正在展示的弹窗。 */
class DialogEntry(
    val id: Int,
    val align: Int,
    val dismissOnMask: Boolean,
    val dismissOnBack: Boolean,
    val maskColor: Color,
    val onDismiss: (() -> Unit)?,
    val builder: ViewContainer<*, *>.(DialogHandle) -> Unit,
) {
    lateinit var handle: DialogHandle
    /** 已关闭、正在播退场动画（仍在 dialogs 里，但不再算作打开的弹窗） */
    internal var leaving = false
    /** 由弹窗视图注入：播放退场动画，播完调用 done 从列表移除 */
    internal var leave: ((done: () -> Unit) -> Unit)? = null

    companion object {
        const val ALIGN_CENTER = 0
        const val ALIGN_BOTTOM = 1
        const val ALIGN_TOP = 2
        /** 内容自行铺满（全屏弹层），不加入场位移 */
        const val ALIGN_FILL = 3
    }
}

/** 弹窗句柄：内容里调用 [dismiss] 关闭自己。 */
class DialogHandle internal constructor(private val state: OverlayState, val id: Int) {
    var isShowing: Boolean = true
        internal set

    fun dismiss() = state.dismissDialog(id)
}

/** 一条顶部横幅（非模态：不挡住页面其它区域的触摸）。 */
class BannerEntry(
    val id: Int,
    val builder: ViewContainer<*, *>.(BannerHandle) -> Unit,
) {
    lateinit var handle: BannerHandle
    /** 由横幅视图注入：播放退场动画，播完后再从列表移除 */
    internal var leave: (() -> Unit)? = null
}

/** 横幅句柄。 */
class BannerHandle internal constructor(private val state: OverlayState, val id: Int) {
    var isShowing: Boolean = true
        internal set

    /** 关闭（带退场动画）。 */
    fun dismiss() = state.dismissBanner(id)
}

/** 页面级浮层状态：Toast、顶部错误条、Loading、弹窗栈、顶部横幅。 */
class OverlayState(private val pager: BasePager) {
    // Toast
    var toastText by pager.observable("")
    var toastVisible by pager.observable(false)
    private var toastTimer: String? = null
    private var lastToastAt = 0L

    // 顶部错误条
    var topErrorText by pager.observable("")
    var topErrorVisible by pager.observable(false)
    /** 视图是否挂着（含淡出中）；淡入淡出由 [topErrorVisible] 驱动 */
    var topErrorMounted by pager.observable(false)
    private var topErrorTimer: String? = null

    // Loading
    var loadingVisible by pager.observable(false)
    var loadingText by pager.observable("")
    /** 点遮罩是否关闭 Loading（Dart LoadingUtil.show(clickMaskDismiss:)） */
    var loadingMaskDismiss = false
    /** 返回键是否被 Loading 吞掉而不关闭（Dart LoadingUtil.show(isBackTypeBlock:)）；默认返回键先关 Loading */
    var loadingBlockBack = false
    private var loadingCount = 0

    // 顶部横幅
    val banners by pager.observableList<BannerEntry>()
    private var bannerSeq = 0

    // 弹窗栈
    val dialogs by pager.observableList<DialogEntry>()
    private var dialogSeq = 0

    /**
     * 轻提示。与参考工程的 SmartDialog 配置一致：正在显示时只刷新文案（不排队），
     * 且距上一次提示不足 1200ms 的调用被丢弃（debounce）。
     */
    fun showToast(msg: String, durationMs: Int = 2000) {
        if (msg.isEmpty()) return
        val now = nowMs()
        if (lastToastAt != 0L && now - lastToastAt < TOAST_DEBOUNCE_MS) return
        lastToastAt = now
        toastTimer?.let { clearTimeout(it) }
        toastText = msg
        toastVisible = true
        toastTimer = setTimeout(pager.pagerId, durationMs) {
            toastVisible = false
            toastTimer = null
        }
    }

    /** 顶部错误提示：展示 1.7s，淡入淡出；展示期间的新错误直接丢弃（Dart `_isShowingError`）。 */
    fun showTopError(msg: String) {
        if (msg.isEmpty()) return
        if (topErrorMounted) return
        topErrorText = msg
        topErrorMounted = true
        setTimeout(pager.pagerId, 16) { topErrorVisible = true }
        topErrorTimer = setTimeout(pager.pagerId, 1700) {
            topErrorVisible = false
            topErrorTimer = setTimeout(pager.pagerId, 220) {
                topErrorMounted = false
                topErrorTimer = null
            }
        }
    }

    fun showLoading(text: String = "", maskDismiss: Boolean = false, blockBack: Boolean = false) {
        loadingCount++
        loadingText = text
        loadingMaskDismiss = maskDismiss
        loadingBlockBack = blockBack
        loadingVisible = true
        // Loading 显示期间返回键先作用于它（SmartDialog backType normal），不能直接退出页面
        pager.refreshBackIntercept()
    }

    fun hideLoading(force: Boolean = false) {
        loadingCount = if (force) 0 else (loadingCount - 1).coerceAtLeast(0)
        if (loadingCount == 0 && loadingVisible) {
            loadingVisible = false
            pager.refreshBackIntercept()
        }
    }

    /** 是否有 Loading 在显示（Dart LoadingUtil.isOpenLoading()）。 */
    fun isLoadingShowing(): Boolean = loadingVisible

    // —— 顶部横幅 —— //

    /**
     * 展示一条顶部横幅。同一时刻只保留一条：新的会替换旧的。
     * @param durationMs 自动关闭时间；<= 0 不自动关闭
     */
    fun showBanner(durationMs: Int = 3000, builder: ViewContainer<*, *>.(BannerHandle) -> Unit): BannerHandle {
        // 旧横幅直接移除（不播退场，避免两条叠在一起）
        while (banners.isNotEmpty()) removeBanner(banners.last().id)
        val id = ++bannerSeq
        val entry = BannerEntry(id, builder)
        val handle = BannerHandle(this, id)
        entry.handle = handle
        banners.add(entry)
        if (durationMs > 0) {
            setTimeout(pager.pagerId, durationMs) { dismissBanner(id) }
        }
        return handle
    }

    /** 关闭横幅：有视图时先播退场动画。 */
    fun dismissBanner(id: Int) {
        val entry = banners.firstOrNull { it.id == id } ?: return
        val leave = entry.leave
        if (leave != null) leave() else removeBanner(id)
    }

    internal fun removeBanner(id: Int) {
        val index = banners.indexOfFirst { it.id == id }
        if (index < 0) return
        banners[index].handle.isShowing = false
        banners.removeAt(index)
    }

    fun hasDialog(): Boolean = dialogs.any { !it.leaving }

    fun showDialog(
        align: Int,
        dismissOnMask: Boolean,
        dismissOnBack: Boolean,
        maskColor: Color,
        onDismiss: (() -> Unit)?,
        builder: ViewContainer<*, *>.(DialogHandle) -> Unit,
    ): DialogHandle {
        val id = ++dialogSeq
        val entry = DialogEntry(id, align, dismissOnMask, dismissOnBack, maskColor, onDismiss, builder)
        val handle = DialogHandle(this, id)
        entry.handle = handle
        dialogs.add(entry)
        pager.refreshBackIntercept()
        return handle
    }

    /** 关闭弹窗：状态立即更新、onDismiss 立即回调，视图播完退场动画（淡出 / 下滑）后再移除。 */
    fun dismissDialog(id: Int) {
        val entry = dialogs.firstOrNull { it.id == id && !it.leaving } ?: return
        entry.handle.isShowing = false
        entry.leaving = true
        pager.refreshBackIntercept()
        entry.onDismiss?.invoke()
        val leave = entry.leave
        if (leave != null) leave { removeDialog(id) } else removeDialog(id)
    }

    private fun removeDialog(id: Int) {
        val index = dialogs.indexOfFirst { it.id == id }
        if (index >= 0) dialogs.removeAt(index)
    }

    fun dismissAllDialogs() {
        while (true) {
            val top = dialogs.lastOrNull { !it.leaving } ?: break
            dismissDialog(top.id)
        }
    }

    companion object {
        private const val TOAST_DEBOUNCE_MS = 1200L
    }

    /** 返回键：关闭 Loading / 最上层弹窗。返回是否消费了本次返回。 */
    fun dismissTopDialogByBack(): Boolean {
        // Loading 在弹窗栈之上：先关它（blockBack 时只消费不关）
        if (loadingVisible) {
            if (!loadingBlockBack) hideLoading(force = true)
            return true
        }
        val top = dialogs.lastOrNull { !it.leaving } ?: return false
        if (top.dismissOnBack) dismissDialog(top.id)
        return true
    }
}
