package com.sondo.app.service

import com.sondo.app.base.AppHost
import com.tencent.kuikly.core.manager.PagerManager
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager

/**
 * 非页面代码（全局单例 Service）广播跨页面事件的入口。
 * 页面内直接用 BasePager.emit；这里借当前页面（没有则宿主页面）的 NotifyModule 发出，所有存活页面都会收到。
 */
object EventBus {
    fun post(event: String, data: JSONObject = JSONObject()) {
        val pager: Pager = try {
            PagerManager.getCurrentPager() as Pager
        } catch (e: Throwable) {
            if (!AppHost.isReady) return
            AppHost.pager
        }
        pager.acquireModule<NotifyModule>(NotifyModule.MODULE_NAME).postNotify(event, data)
    }
}
