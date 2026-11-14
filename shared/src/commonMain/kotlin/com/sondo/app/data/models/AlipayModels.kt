package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/*
 * 支付宝下单 / 查单 / 续费管理的结果模型
 * （Dart: lib/shared/data/datasources/pay_remote_datasource.dart 文件内的结果类）。
 * 共同约定：`code` 是业务码；请求失败（网络、超时、回包不是 JSON）时 code 为 null，
 * 调用方不能把 null 当成业务拒绝或未付款。
 */

/** 回包里的业务码：整数或整数字符串；取不到为 null。 */
internal fun alipayBizCode(body: JSONObject?): Int? {
    if (body == null) return null
    return when (val code = body.jRaw("code")) {
        is Number -> {
            val d = code.toDouble()
            if (d == d.toInt().toDouble()) d.toInt() else null
        }
        is String -> code.toIntOrNull()
        else -> null
    }
}

internal fun alipayAmount(value: Any?): Double? = when (value) {
    is Number -> value.toDouble()
    is String -> value.trim().toDoubleOrNull()
    else -> null
}

internal fun alipayCount(value: Any?): Int? = when (value) {
    is Number -> value.toInt()
    is String -> value.trim().toIntOrNull()
    else -> null
}

private fun JSONObject.trimmed(key: String): String = jStr(key).trim()

/** 支付宝积分包订单。`orderStr` 不能改写后再交给 SDK。 */
data class AlipayTradeOrder(
    var code: Int? = null,
    var orderId: String = "",
    var orderStr: String = "",
    var payAmount: Double? = null,
    var expireTime: String? = null,
) {
    val ready: Boolean get() = code == 0 && orderId.isNotEmpty() && orderStr.isNotEmpty()

    companion object {
        fun fromResponse(body: JSONObject?): AlipayTradeOrder {
            val code = alipayBizCode(body)
            val data = body?.jObj("data")
            if (code != 0 || data == null) return AlipayTradeOrder(code = code)
            return AlipayTradeOrder(
                code = 0,
                orderId = data.trimmed("orderId"),
                orderStr = data.trimmed("orderStr"),
                payAmount = alipayAmount(data.jRaw("payAmount")),
                expireTime = data.jStrOrNull("expireTime"),
            )
        }
    }
}

/** 支付宝签约单。code 非 0 时不带链接。 */
data class AlipaySubscriptionOrder(
    var code: Int? = null,
    var orderId: String = "",
    var jumpSchema: String = "",
    var payAmount: Double? = null,
    var expireTime: String? = null,
) {
    val ready: Boolean get() = code == 0 && orderId.isNotEmpty() && jumpSchema.isNotEmpty()

    companion object {
        fun fromResponse(body: JSONObject?): AlipaySubscriptionOrder {
            val code = alipayBizCode(body)
            val data = body?.jObj("data")
            if (code != 0 || data == null) return AlipaySubscriptionOrder(code = code)
            return AlipaySubscriptionOrder(
                code = 0,
                orderId = data.trimmed("orderId"),
                jumpSchema = data.trimmed("alipayJumpSchema"),
                payAmount = alipayAmount(data.jRaw("payAmount")),
                expireTime = data.jStrOrNull("expireTime"),
            )
        }
    }
}

/** 后端确认的付款结果。客户端不拿支付宝回调当到账。 */
data class AlipayOrderResult(
    var code: Int? = null,
    var orderId: String = "",
    var status: String = "",
    var productId: String = "",
    var points: Int? = null,
) {
    val waiting: Boolean get() = status == "WAITING" || status == "PROCESSING"
    val succeeded: Boolean get() = status == "SUCCESS"
    val closed: Boolean get() = status == "CLOSED" || status == "REFUNDED"

    companion object {
        fun fromResponse(body: JSONObject?): AlipayOrderResult {
            val code = alipayBizCode(body)
            val data = body?.jObj("data")
            if (code != 0 || data == null) return AlipayOrderResult(code = code)
            return AlipayOrderResult(
                code = 0,
                orderId = data.trimmed("orderId"),
                status = data.trimmed("status"),
                productId = data.trimmed("productId"),
                points = alipayCount(data.jRaw("points")),
            )
        }
    }
}

/** 取消自动续费的结果。 */
data class AlipayCancelResult(
    var code: Int? = null,
    var autoRenew: Boolean? = null,
    var currentPeriodEnd: String? = null,
) {
    companion object {
        fun fromResponse(body: JSONObject?): AlipayCancelResult {
            val code = alipayBizCode(body)
            val data = body?.jObj("data")
            val end = data?.jStrOrNull("currentPeriodEnd")?.trim()
            return AlipayCancelResult(
                code = code,
                autoRenew = data?.jRaw("autoRenew") as? Boolean,
                currentPeriodEnd = if (end.isNullOrEmpty()) null else end,
            )
        }
    }
}

/** 订阅管理。只有支付宝且允许取消时才弹确认框。 */
data class AlipayRenewState(
    var code: Int? = null,
    var provider: String = "",
    var action: String = "",
    var reason: String = "",
) {
    val canCancelAlipay: Boolean
        get() = code == 0 && provider.uppercase() == "ALIPAY" && action == "CANCEL_ALIPAY_AUTO_RENEW"

    companion object {
        fun fromResponse(body: JSONObject?): AlipayRenewState {
            val code = alipayBizCode(body)
            if (code != 0) return AlipayRenewState(code = code)
            val data = body?.jObj("data")
            val provider = data?.jRaw("provider")
            val action = data?.jRaw("action")
            // 成功响应也须有明确的管理动作，缺字段不能解释成没有自动续费。
            if (provider !is String || provider.trim().isEmpty() || action !is String || action.trim().isEmpty()) {
                return AlipayRenewState()
            }
            return AlipayRenewState(
                code = 0,
                provider = provider.trim(),
                action = action.trim(),
                reason = data.trimmed("reason"),
            )
        }
    }
}
