package com.sondo.app.base

import com.sondo.app.core.http.Api
import com.sondo.app.core.utils.isPagerAlive
import com.sondo.app.core.utils.nowMs
import com.sondo.app.ui.overlay.DialogHandle
import com.tencent.kuikly.core.manager.PagerManager
import com.tencent.kuikly.core.timer.setTimeout

/** 把 core 层的回调钩子接到 UI / 业务层（进程内只装一次）。 */
object AppHooks {
    private var installed = false

    /** 会话失效时的处理（由 UserStore 注入）。 */
    var sessionInvalidHandler: (() -> Unit)? = null
    /** 价格不一致时的处理（由 ConfigService 注入）。 */
    var priceMismatchHandler: (() -> Unit)? = null

    /** 接口错误提示展示到何时（Dart _isShowingError：展示期间的新错误直接丢弃，保留第一条）。 */
    private var apiErrorUntil = 0L

    fun install() {
        if (installed) return
        installed = true
        Api.onErrorToast = { msg ->
            val now = nowMs()
            if (now >= apiErrorUntil) {
                apiErrorUntil = now + 1700
                VisiblePage.run { it.toastError(msg) }
            }
        }
        Api.onSessionInvalid = { sessionInvalidHandler?.invoke() }
        Api.onPriceMismatch = { priceMismatchHandler?.invoke() }
    }
}

/**
 * 用户正在看的页面（最近一次 pageDidAppear 且仍存活的页面）。
 *
 * 每个页面是独立的原生容器：宿主页面上发起的请求 / 服务里的弹窗如果画在「当前 pager」（发起方）上，
 * 会被盖在上层页面下面。全局提示、应用级弹窗统一落到这里。
 */
object VisiblePage {
    private val pages = ArrayList<BasePager>()
    private val followers = ArrayList<Follower>()

    internal fun appeared(pager: BasePager) {
        pages.remove(pager)
        pages.add(pager)
        followers.toList().forEach { it.moveTo(pager) }
    }

    internal fun destroyed(pager: BasePager) {
        pages.remove(pager)
        val next = top()
        followers.toList().forEach { if (it.owner === pager) it.ownerDestroyed(next) }
    }

    /** 可见页面；没有时退回宿主页面。 */
    fun top(): BasePager? {
        val it = pages.iterator()
        while (it.hasNext()) {
            if (!isPagerAlive(it.next().pagerId)) it.remove()
        }
        pages.lastOrNull()?.let { return it }
        return if (AppHost.isReady) AppHost.pager as? BasePager else null
    }

    /** 在可见页面的上下文里执行（目标就是当前页面时同步执行）。 */
    fun run(block: (BasePager) -> Unit) {
        val current = try {
            PagerManager.getCurrentPager()
        } catch (e: Throwable) {
            null
        }
        val target = top() ?: (current as? BasePager) ?: return
        if (current === target) {
            block(target)
        } else {
            setTimeout(target.pagerId, 0) { if (isPagerAlive(target.pagerId)) block(target) }
        }
    }

    /**
     * 在可见页面上展示一个应用级弹窗（应用火爆 / 版本更新）；弹窗未被关掉前，
     * 新页面盖上来（如首启引导页）或所在页面被销毁时，挪到新的可见页面上重新展示。
     */
    fun showFollowing(show: (BasePager) -> DialogHandle) {
        val f = Follower(show)
        followers.add(f)
        run { f.showOn(it) }
    }

    private class Follower(val show: (BasePager) -> DialogHandle) {
        var owner: BasePager? = null
        var handle: DialogHandle? = null

        fun showOn(pager: BasePager) {
            owner = pager
            handle = show(pager)
        }

        fun moveTo(pager: BasePager) {
            val old = owner ?: return
            val h = handle ?: return
            if (pager === old) return
            if (!h.isShowing) {
                followers.remove(this)
                return
            }
            handle = null
            owner = pager
            setTimeout(old.pagerId, 0) { h.dismiss() }
            setTimeout(pager.pagerId, 0) { if (isPagerAlive(pager.pagerId)) showOn(pager) }
        }

        fun ownerDestroyed(next: BasePager?) {
            val h = handle
            if (h == null || !h.isShowing || next == null) {
                followers.remove(this)
                return
            }
            handle = null
            owner = next
            setTimeout(next.pagerId, 0) { if (isPagerAlive(next.pagerId)) showOn(next) }
        }
    }
}

/** 当前页面（在页面事件 / 回调 / 协程上下文中调用）。 */
fun currentPager(): BasePager? = PagerManager.getCurrentPager() as? BasePager

/** 任意位置弹轻提示（落到用户正在看的页面）。 */
fun toast(msg: String) {
    if (msg.isEmpty()) return
    VisiblePage.run { it.toast(msg) }
}
