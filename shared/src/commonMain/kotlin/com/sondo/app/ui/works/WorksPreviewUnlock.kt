package com.sondo.app.ui.works

import com.sondo.app.base.BasePager
import com.sondo.app.base.Routes
import com.sondo.app.core.utils.DevLog
import com.sondo.app.data.api.MvServiceApi
import com.sondo.app.data.models.UnlockPayDecision
import com.sondo.app.data.models.decideUnlockPay
import com.sondo.app.service.UserStore
import com.sondo.app.ui.dialog.showMvPreviewUnlock
import com.sondo.app.ui.overlay.DialogHandle

/**
 * Dart `MvPreviewUnlockFlow`（lib/shared/services/mv_preview_unlock_flow.dart）：高潮试看解锁。
 * 积分够直接扣（继续生成接口），不够再按是否订阅去积分页或订阅页。
 * 扣费成功后刷新余额并回调 onPaid（标记生成中、轮询、关页由调用方处理）。
 */
object WorksPreviewUnlock {
    private val paying = HashSet<Long>()

    /** 弹解锁窗，点「解锁」后走 [unlock]。 */
    fun show(pager: BasePager, mvId: Long, needPoint: Int, onPaid: (suspend () -> Unit)? = null): DialogHandle =
        pager.showMvPreviewUnlock(needPoint) {
            pager.launch { unlock(pager, mvId, needPoint, onPaid) }
        }

    /** 不弹窗，直接走「解锁」按钮同一套逻辑（登录 / 会员 / 扣积分）。 */
    suspend fun unlock(pager: BasePager, mvId: Long, needPoint: Int, onPaid: (suspend () -> Unit)? = null) {
        if (!UserStore.isLogin) {
            if (!UserStore.showLoginDialog(pager)) return
        }
        when (decideUnlockPay(isMember = UserStore.isSubscribed, points = UserStore.points, needPoints = needPoint)) {
            UnlockPayDecision.subscribe -> {
                pager.nav.push(Routes.subscribe)
                return
            }
            UnlockPayDecision.buyCredits -> {
                pager.nav.push(Routes.creditsPurchase)
                return
            }
            UnlockPayDecision.deductPoints -> {}
        }
        // 解锁复用继续生成接口；请求未返回前同一 MV 不重复扣费，积分不足等错误由拦截器提示
        if (!paying.add(mvId)) return
        try {
            val paid = MvServiceApi.continueGenMvApi(mvId = mvId, continueModel = 0, point = needPoint).getOrNull() ?: false
            if (!paid) return
            // 扣费成功后刷新余额，播放页和资源库没有自己的刷新
            pager.launch { UserStore.getUserInfo() }
            onPaid?.invoke()
        } catch (e: Throwable) {
            DevLog.logger("MvPreviewUnlockFlow error: ${e.message}")
        } finally {
            paying.remove(mvId)
        }
    }
}
