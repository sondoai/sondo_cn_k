package com.sondo.app.service

import com.sondo.app.base.AppHost
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.http.AuthSession
import com.sondo.app.core.storage.Sp
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.shouldStopRecallCountdown
import com.sondo.app.data.api.ApiCrypto
import com.sondo.app.data.api.AppConfigApi
import com.sondo.app.data.api.AuthApi
import com.sondo.app.data.models.ReturningOffer
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 回归页入口（对应 Flutter 侧 features/member/presentation/recall_flow.dart）。
 * 自动展示看本地加密 userId 的资格接口；深链不等该接口，只在后台查来清上一轮倒计时。
 *
 * 回归页参数：`{source: "deeplink" | "auto"}`。
 */
object RecallFlow {
    const val SOURCE_DEEPLINK = "deeplink"
    const val SOURCE_AUTO = "auto"

    var deepLinkPending = false
    private var opening = false
    private var upgradeToDeepLink = false

    /** 本会话已经问过资格或已经自动打开，避免登录往返和前后台再弹。 */
    private var autoHandled = false

    /** 登录成功后把 userId 做 RSA，留给下次进入时查询。 */
    suspend fun storeEncryptedUserId(userId: Long, isCurrent: (() -> Boolean)? = null) {
        try {
            val encrypted = ApiCrypto.rsaEncrypt("$userId")
            if (encrypted.isEmpty() || isCurrent?.invoke() == false) return
            Sp.setString(StorageKey.recallEncryptedUserId, encrypted)
        } catch (e: Throwable) {
            DevLog.logger("recall encrypt userId: $e")
        }
    }

    fun clearEncryptedUserId() {
        Sp.setString(StorageKey.recallEncryptedUserId, "")
    }

    suspend fun tryAuto(isCurrent: (() -> Boolean)? = null) {
        val revision = AuthSession.revision
        val current = isCurrent ?: { AuthSession.isCurrent(revision) }
        // 资格接口不要求登录，本地有密文即可查。
        if (autoHandled || deepLinkPending || opening) return
        if (!DeepLinkRouter.homeReady) return
        // 密文还没落盘时先返回，避免占住 opening 把随后的查询丢掉。
        if (Sp.getString(StorageKey.recallEncryptedUserId).isEmpty()) return
        open(fromDeepLink = false, isCurrent = current)
    }

    suspend fun openFromDeepLink() {
        deepLinkPending = true
        upgradeToDeepLink = true
        if (opening) return
        open(fromDeepLink = true)
    }

    private fun onRecallPage(): Boolean {
        val route = AppNav.currentRoute()
        return route == Routes.recall || route == Routes.recallProduct
    }

    private suspend fun open(fromDeepLink: Boolean, isCurrent: (() -> Boolean)? = null) {
        if (opening) {
            DevLog.logger("recall open skipped: opening fromDeepLink=$fromDeepLink")
            return
        }
        opening = true
        val revision = AuthSession.revision
        val encryptedUserId = Sp.getString(StorageKey.recallEncryptedUserId)
        try {
            if (onRecallPage()) {
                DevLog.logger("recall open skipped: already on recall page")
                return
            }
            var asDeepLink = fromDeepLink || upgradeToDeepLink
            if (!asDeepLink) {
                val eligible = returningEligible()
                // 退出或换号后，旧资格结果不能弹页或修改当前账号的优惠窗口。
                if (isCurrent?.invoke() == false) return
                // 问过且有明确结果就不再问。查询途中改走深链时，页仍按深链打开。
                if (eligible != null) autoHandled = true
                asDeepLink = upgradeToDeepLink
                if (!asDeepLink && eligible != true) return
                prepareCycleIfEligible(eligible)
            }
            asDeepLink = fromDeepLink || upgradeToDeepLink
            if (fromDeepLink) {
                // 深链总是立刻打开，资格在后台查：满足新一轮就清上一轮的过期记录，看到商品后才会出倒计时。
                AppHost.launch {
                    val eligible = returningEligible()
                    if (AuthSession.isCurrent(revision) && Sp.getString(StorageKey.recallEncryptedUserId) == encryptedUserId) {
                        // 本次运行已经展示过落地页，有明确结果后不再自动弹。
                        if (eligible != null) autoHandled = true
                        prepareCycleIfEligible(eligible)
                    }
                }
            }
            // 内容等页面出现后再请求，失败留在页内重试。不等页面关闭。
            AppNav.push(Routes.recall, JSONObject().put("source", if (asDeepLink) SOURCE_DEEPLINK else SOURCE_AUTO))
        } finally {
            opening = false
            upgradeToDeepLink = false
            deepLinkPending = false
        }
    }

    /** 以资格接口为准：有资格就清掉上一轮已过期的记录，看到商品后才能重新开 10 分钟；进行中的不动。 */
    private fun prepareCycleIfEligible(eligible: Boolean?) {
        if (eligible != true) return
        ConfigService.countdown.prepareNewRecallCycle()
    }

    /** true 有资格，false 明确没有，null 还不能判断。 */
    private suspend fun returningEligible(): Boolean? {
        val encryptedUserId = Sp.getString(StorageKey.recallEncryptedUserId)
        if (encryptedUserId.isEmpty()) return null
        return AuthApi.queryReturningUser(encryptedUserId).getOrNull()
    }

    /** 刚登录时重查回归优惠。明确没资格就停掉倒计时，查询失败保持原样。 */
    suspend fun refreshCountdownEligibility(isCurrent: (() -> Boolean)? = null) {
        val offer = AppConfigApi.fetchReturningOffer().getOrNull()
        if (isCurrent?.invoke() == false) return
        stopCountdownWhenBlocked(offer)
    }

    /** 登录后这份优惠不能买时停表。未登录、只差登录、或还没开过窗口时不写过期记录。 */
    fun stopCountdownWhenBlocked(offer: ReturningOffer?) {
        val countdown = ConfigService.countdown
        if (!shouldStopRecallCountdown(
                loggedIn = UserStore.isLogin,
                blockedAfterLogin = offer?.blockedAfterLogin ?: false,
                expireMs = countdown.recallExpireMs,
                onRecallPage = onRecallPage(),
            )
        ) {
            return
        }
        DevLog.logger(
            "recall countdown cleared visible=${offer?.visible} canPurchase=${offer?.canPurchase} reason=${offer?.blockReason}",
        )
        countdown.endRecallOffer()
    }

    /**
     * 10 分钟内首页订阅入口打开回归周卡商品页。商品等进页后再查，这里不验价。
     * @return 是否已打开商品页
     */
    fun openProductFromHome(): Boolean {
        if (!ConfigService.countdown.recallOfferActive) return false
        // 会员或身份不明都不进回归周卡。
        if (!UserStore.isLogin || UserStore.userInfo == null || UserStore.isSubscribed) return false
        AppNav.push(Routes.recallProduct)
        return true
    }
}
