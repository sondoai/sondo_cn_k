package com.sondo.app.core.utils

/** 回归周卡首期价（美元标价，仅 iOS 本地兜底展示用）。 */
const val recallIntroUsd: Double = 4.99

/** 回归周卡窗口：10 分钟。 */
const val recallOfferWindowMs: Long = 10 * 60 * 1000L

/** 看到商品时的到期时间：已有记录不重置；没看到商品不开窗。 */
fun recallExpireWhenSeen(existingExpireMs: Long, sawProduct: Boolean, nowMs: Long): Long {
    if (!sawProduct || existingExpireMs > 0) return existingExpireMs
    return nowMs + recallOfferWindowMs
}

fun isRecallOfferActive(expireMs: Long, nowMs: Long): Boolean = expireMs > nowMs

/** 离开商品页时是否结束倒计时：已订阅结束；明确不可买结束；未登录 / 结果未知不动。 */
fun shouldEndRecallCountdownOnProductLeave(loggedIn: Boolean, subscribed: Boolean, purchasable: Boolean?): Boolean {
    if (!loggedIn) return false
    if (subscribed) return true
    if (purchasable == null) return false
    return !purchasable
}

/** 登录后这份优惠不能买时停表。未登录、只差登录、或还没开过窗口时不写过期记录。 */
fun shouldStopRecallCountdown(loggedIn: Boolean, blockedAfterLogin: Boolean, expireMs: Long, onRecallPage: Boolean): Boolean {
    if (!loggedIn || !blockedAfterLogin) return false
    return expireMs > 0 || onRecallPage
}
