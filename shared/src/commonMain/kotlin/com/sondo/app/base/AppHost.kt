package com.sondo.app.base

import com.tencent.kuikly.core.coroutines.CoroutineScope
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuikly.core.manager.PagerManager
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.timer.setTimeout

/**
 * 应用级宿主：根页面（main）在 created() 时把自己注册为宿主，销毁时注销。
 *
 * Kuikly 的 Module 回调、定时器、协程都绑定在发起它的 Pager 上，页面关闭后回调即被丢弃。
 * 轮询 / WebSocket / 后台上传这类「跨页面存活」的工作必须跑在根页面上下文里：
 * 用 [launch] 启动，而不是在业务页自己的 lifecycleScope 里启动。
 */
object AppHost {
    private var hostPager: Pager? = null

    val isReady: Boolean get() = hostPager != null

    fun attach(pager: Pager) {
        hostPager = pager
    }

    fun detach(pager: Pager) {
        if (hostPager === pager) hostPager = null
    }

    /** 宿主页面；尚未就绪（引导流程中）时退回当前页面。 */
    val pager: Pager
        get() = hostPager ?: (PagerManager.getCurrentPager() as Pager)

    val scope: CoroutineScope get() = pager.lifecycleScope

    /**
     * 在宿主页面上下文里启动协程。
     * 先切到宿主的定时器回调，保证协程体内 `PagerManager.getCurrentPager()` 是宿主页面。
     */
    fun launch(block: suspend CoroutineScope.() -> Unit) {
        val host = pager
        setTimeout(host.pagerId, 0) {
            host.lifecycleScope.launch(block = block)
        }
    }

    /** 在宿主页面上下文里执行一段同步代码。 */
    fun run(block: () -> Unit) {
        val host = pager
        setTimeout(host.pagerId, 0, block)
    }

    fun <T : Module> module(name: String): T = pager.acquireModule(name)
}
