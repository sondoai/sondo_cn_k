package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** iOS V2 建单结果。orderNum 对应接口 orderId（Dart: lib/data/models/ios_purchase_models.dart `IosOrder`）。 */
data class IosOrder(
    /** 后端订单号，后续 PURCHASE 验单必须原样回传。 */
    var orderNum: String = "",
    /** 服务端绑定本单的 UUID，经 appAccountToken 传给 StoreKit。 */
    var appAccountToken: String = "",
    /** 回归促销签名。由签名接口单独返回；没有签名时不能按原价拉起。 */
    var promotionalSignature: ApplePromotionalSignature? = null,
) {
    fun toJson(): JSONObject {
        val o = JSONObject().put("orderId", orderNum).put("appAccountToken", appAccountToken)
        promotionalSignature?.let { o.put("promotionalOffer", it.toJson()) }
        return o
    }

    companion object {
        private fun isHex(s: String): Boolean = s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

        /** 8-4-4-4-12 的 UUID 文本。 */
        fun validToken(token: String): Boolean {
            val t = token.trim()
            if (t.length != 36) return false
            val parts = t.split("-")
            if (parts.size != 5) return false
            val lens = intArrayOf(8, 4, 4, 4, 12)
            for (i in 0 until 5) if (parts[i].length != lens[i] || !isHex(parts[i])) return false
            return true
        }

        fun sameToken(a: String?, b: String?): Boolean =
            a != null && b != null && validToken(a) && validToken(b) &&
                a.trim().lowercase() == b.trim().lowercase()

        /** data 不是对象、订单号为空或 token 不是 UUID 时返回 null。 */
        fun fromData(data: JSONObject?): IosOrder? {
            if (data == null) return null
            val number = data.jRaw("orderId") ?: data.jRaw("orderNum")
            val token = data.jRaw("appAccountToken")
            if (number !is String || number.trim().isEmpty() || token !is String || !validToken(token)) return null
            return IosOrder(
                orderNum = number.trim(),
                appAccountToken = token.trim().lowercase(),
                promotionalSignature = ApplePromotionalSignature.fromMap(data),
            )
        }

        fun fromJsonOrNull(json: JSONObject?): IosOrder? = fromData(json)
    }
}

/**
 * Apple 促销优惠签名。必须和本单 appAccountToken 一起签，不能在客户端现造
 * （Dart: lib/data/models/ios_purchase_models.dart `ApplePromotionalSignature`）。
 */
data class ApplePromotionalSignature(
    var keyId: String = "",
    var nonce: String = "",
    var timestamp: Long = 0L,
    var signature: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("keyId", keyId)
        .put("nonce", nonce)
        .put("timestamp", timestamp)
        .put("signature", signature)

    companion object {
        /** 兼容嵌套对象与字段别名；任一必要字段缺失返回 null。 */
        fun fromMap(data: JSONObject): ApplePromotionalSignature? {
            val nested = data.jRaw("promotionalOffer") ?: data.jRaw("promotionalOfferSignature")
            val source = nested as? JSONObject ?: data
            val keyId = source.jRaw("keyId") ?: source.jRaw("keyID") ?: source.jRaw("keyIdentifier")
            val nonce = source.jRaw("nonce")
            val rawTimestamp = source.jRaw("timestamp") ?: source.jRaw("signatureTimestamp")
            val signature = source.jRaw("signature") ?: source.jRaw("offerSignature")
            val timestamp = intLike(rawTimestamp)
            if (keyId !is String || keyId.isEmpty() ||
                nonce !is String || nonce.isEmpty() ||
                signature !is String || signature.isEmpty() ||
                timestamp == null
            ) return null
            return ApplePromotionalSignature(keyId, nonce, timestamp, signature)
        }

        fun fromJsonOrNull(json: JSONObject?): ApplePromotionalSignature? = json?.let { fromMap(it) }
    }
}

/** Dart `v is int ? v : int.tryParse('$v')`：整数或整数字符串。 */
private fun intLike(v: Any?): Long? = when (v) {
    is Int -> v.toLong()
    is Long -> v
    is Number -> {
        val d = v.toDouble()
        // JS 等平台数字都是 double；整数值按整数处理
        if (d == d.toLong().toDouble()) d.toLong() else null
    }
    is String -> v.toLongOrNull()
    else -> null
}

/**
 * 回归周卡促销签名。商品和优惠由服务端固定，nonce 与 token 必须原样交给 StoreKit
 * （Dart: lib/data/models/ios_purchase_models.dart `AppleReturningPromotion`）。
 */
data class AppleReturningPromotion(
    var orderId: String = "",
    var productId: String = "",
    var offerId: String = "",
    var keyId: String = "",
    var appAccountToken: String = "",
    var nonce: String = "",
    var timestamp: Long = 0L,
    var signature: String = "",
) {
    val promotionalSignature: ApplePromotionalSignature
        get() = ApplePromotionalSignature(keyId = keyId, nonce = nonce, timestamp = timestamp, signature = signature)

    /** 订单、商品、账户令牌必须和建单结果一致，否则不能换一笔新订单再付。 */
    fun matchesOrder(order: IosOrder, productId: String): Boolean =
        orderId == order.orderNum &&
            this.productId == productId &&
            IosOrder.sameToken(appAccountToken, order.appAccountToken)

    fun toJson(): JSONObject = JSONObject()
        .put("orderId", orderId)
        .put("productId", productId)
        .put("offerId", offerId)
        .put("keyId", keyId)
        .put("appAccountToken", appAccountToken)
        .put("nonce", nonce)
        .put("timestamp", timestamp)
        .put("signature", signature)

    companion object {
        fun fromMap(data: JSONObject): AppleReturningPromotion? {
            val orderId = data.jRaw("orderId")
            val productId = data.jRaw("productId")
            val offerId = data.jRaw("offerId")
            val keyId = data.jRaw("keyId")
            val token = data.jRaw("appAccountToken")
            val nonce = data.jRaw("nonce")
            val signature = data.jRaw("signature")
            val timestamp = intLike(data.jRaw("timestamp"))
            if (orderId !is String || orderId.trim().isEmpty() ||
                productId !is String || productId.trim().isEmpty() ||
                offerId !is String || offerId.trim().isEmpty() ||
                keyId !is String || keyId.isEmpty() ||
                token !is String || !IosOrder.validToken(token) ||
                nonce !is String || !IosOrder.validToken(nonce) ||
                signature !is String || signature.isEmpty() ||
                timestamp == null || timestamp <= 0
            ) return null
            return AppleReturningPromotion(
                orderId = orderId.trim(),
                productId = productId.trim(),
                offerId = offerId.trim(),
                keyId = keyId,
                appAccountToken = token.trim(),
                nonce = nonce.trim(),
                timestamp = timestamp,
                signature = signature,
            )
        }

        fun fromJsonOrNull(json: JSONObject?): AppleReturningPromotion? = json?.let { fromMap(it) }
    }
}

/** V2 验单 data.status。FULFILLED / ALREADY_PROCESSED 视为成功（Dart: `IosVerifyStatus`）。 */
enum class IosVerifyStatus {
    fulfilled,
    alreadyProcessed,
    processing,
    failed,
    ineligible,
    unknown,
}

/** V2 验单结果（Dart: lib/data/models/ios_purchase_models.dart `IosVerifyResult`）。 */
data class IosVerifyResult(
    var status: IosVerifyStatus = IosVerifyStatus.unknown,
    /** PROCESSING / FAILED 且服务端允许时，客户端按 1s、2s 再试相同请求。 */
    var retryable: Boolean = false,
) {
    val succeeded: Boolean
        get() = status == IosVerifyStatus.fulfilled || status == IosVerifyStatus.alreadyProcessed

    companion object {
        /** response 是完整信封 `{code, data:{status, retryable}}`；code 不为 0 或 data 不是对象时为 unknown。 */
        fun fromResponse(response: JSONObject?): IosVerifyResult {
            if (response == null) return IosVerifyResult(IosVerifyStatus.unknown)
            val code = response.jRaw("code")
            val data = response.jObj("data")
            if (code !is Number || code.toDouble() != 0.0 || data == null) {
                return IosVerifyResult(IosVerifyStatus.unknown)
            }
            val retryable = data.jRaw("retryable") == true
            val status = when (data.jRaw("status")) {
                "FULFILLED" -> IosVerifyStatus.fulfilled
                "ALREADY_PROCESSED" -> IosVerifyStatus.alreadyProcessed
                "PROCESSING" -> IosVerifyStatus.processing
                "FAILED" -> IosVerifyStatus.failed
                "INELIGIBLE" -> IosVerifyStatus.ineligible
                else -> IosVerifyStatus.unknown
            }
            return IosVerifyResult(status, retryable)
        }
    }
}

/** `iosPaymentOptions` 响应，只取 Apple 商品列表（Dart: lib/shared/pay/ios_payment_options.dart `IosPaymentOptions`）。 */
data class IosPaymentOptions(
    var products: List<GoodsModel> = emptyList(),
) {
    companion object {
        /** data.products 里的对象逐个转成 GoodsModel；缺失时为空列表。 */
        fun fromData(data: JSONObject): IosPaymentOptions =
            IosPaymentOptions(GoodsModel.listFromJson(data.jArr("products")))
    }
}

/** 回归周卡促销签名结果（Dart: `PayRemoteDataSource.signReturningApplePromotion` 的返回记录）。 */
data class AppleReturningPromotionResult(
    var promotion: AppleReturningPromotion? = null,
    /** 业务码。网络或解析失败时为 null，调用方按加载失败处理，不能改走原价。 */
    var code: Int? = null,
)
