package com.sondo.app.service.pay

import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.utils.DevLog
import com.sondo.app.data.models.GoodsModel
import com.sondo.app.service.analytics.AdEvent
import com.sondo.app.service.analytics.UmengUtils

/** 主动购买验单成功后的打点；补单不得调用（Dart: lib/shared/pay/pay_analytics.dart）。 */
object PayAnalytics {
    /**
     * 主动购买验单成功后上报购买/订阅事件。
     *
     * [product] 优先用本地商品表，找不到再退到 session 快照。
     * [productId] 仅在商品缺失时写入 product_failed，避免空 ID。
     */
    fun sendPurchaseEvent(product: GoodsModel?, orderNum: String, purchaseID: String, productId: String? = null) {
        if (product != null) {
            val productType = product.productType ?: -1
            val resolvedProductId = product.productId ?: ""
            if (productType == 1) {
                DevLog.logger("logPurchaseEvent product: ${product.productId}")
                AdEvent.logPurchaseEvent(
                    price = product.productPrice ?: 0.0,
                    productId = resolvedProductId,
                    productName = product.productName ?: "",
                    orderId = orderNum,
                    purchaseId = purchaseID,
                )
            } else if (productType == 2) {
                // 回归周卡用固定标价，避免商店本地化价格污染归因。
                val price = if (resolvedProductId == AppConstants.recallWeeklyIos) 4.99 else product.productPrice ?: 0.0
                DevLog.logger("logSubscribeEvent product: ${product.productId}")
                AdEvent.logSubscribeEvent(price = price, productId = resolvedProductId, orderId = orderNum)
            }
        } else {
            UmengUtils.logEvent("product_failed", mapOf("product_id" to (productId ?: "")))
        }
    }
}
