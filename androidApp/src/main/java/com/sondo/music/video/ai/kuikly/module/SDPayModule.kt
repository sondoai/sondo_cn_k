package com.sondo.music.video.ai.kuikly.module

import com.sondo.music.video.ai.SondoApplication
import com.sondo.music.video.ai.sdk.AppForeground
import com.sondo.music.video.ai.sdk.OnceCallback
import com.sondo.music.video.ai.sdk.SdkSupport
import com.sondo.music.video.ai.sdk.pay.AlipayManager
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback

/**
 * 支付（契约见 shared 的 `SDPayModule`）。
 *
 * Android 只有支付宝：`alipayIsInstalled`（同步）和 `alipayPay`（华为支付 `huaweiPay` / `huaweiContract` 是鸿蒙的，这里回 unsupported）。自动续费签约走 SDAppModule.openUrl
 * 拉起 `alipays://`，不经过这里。`iap*` 是 iOS 内购，这里立即回 unsupported，页面逻辑不用分平台判空。
 */
class SDPayModule : KuiklyRenderBaseModule() {

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        AppForeground.ensureInstalled(SondoApplication.application, activity)
        return when (method) {
            "alipayIsInstalled" ->
                if (AlipayManager.isInstalled(SondoApplication.application)) "1" else "0"

            "alipayPay" -> {
                val orderStr = SdkSupport.parseParams(params).optString("orderStr", "")
                AlipayManager.pay(activity, orderStr, OnceCallback(callback))
                null
            }

            // 华为支付只在鸿蒙端实现。
            "huaweiPay", "huaweiContract" -> reply(callback, SdkSupport.unsupported())

            "iapIsAvailable" -> reply(callback, SdkSupport.unsupported("available" to false))
            "iapQueryProducts" -> {
                val ids = SdkSupport.parseParams(params).optJSONArray("ids")
                val notFound = ArrayList<Any>()
                if (ids != null) {
                    for (i in 0 until ids.length()) notFound.add(ids.optString(i))
                }
                reply(callback, SdkSupport.unsupported("products" to emptyList<Any>(), "notFound" to notFound))
            }
            "iapIsIntroEligible" -> reply(callback, SdkSupport.unsupported("eligible" to false))
            "iapPurchase" -> reply(callback, SdkSupport.unsupported("started" to false))
            "iapFinish", "iapRestore" -> reply(callback, SdkSupport.unsupported())
            "iapUnfinishedTransactions", "iapTransactionHistory" ->
                reply(callback, SdkSupport.unsupported("transactions" to emptyList<Any>()))

            // 支付宝自动续费的管理（查询 / 解约）走后端接口，没有系统级订阅管理页可开。
            "openSubscriptionManagement" -> reply(callback, SdkSupport.unsupported())

            // 事件只有 iOS 的 iapUpdate，Android 不会发，回调不用留。
            "addEventListener" -> null

            else -> reply(callback, SdkSupport.fail("unknown method: $method"))
        }
    }

    private fun reply(callback: KuiklyRenderCallback?, result: Map<String, Any>): Any? {
        callback?.invoke(result)
        return null
    }
}
