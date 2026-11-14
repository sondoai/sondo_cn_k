package com.sondo.app.service.pay

import com.sondo.app.core.http.AuthSession
import com.sondo.app.core.utils.nowMs
import com.sondo.app.data.models.GoodsModel
import com.sondo.app.data.models.IosOrder
import com.sondo.app.data.models.jBool
import com.sondo.app.data.models.jLong
import com.sondo.app.data.models.jStr
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 验单结果。status 为 false 时默认保留商店交易，供后续补查（Dart: lib/shared/pay/pay_model.dart）。 */
class PayResult(
    /** 后端是否确认到账。 */
    val status: Boolean,
    /** 失败时给用户看的文案（已翻译），成功可为空。 */
    val message: String? = null,
    finishTransaction: Boolean? = null,
) {
    /** 是否完成商店交易。INELIGIBLE 补单需要 finish 但不算发货成功。 */
    val finishTransaction: Boolean = finishTransaction ?: status
}

/** 支付处理来源。 */
enum class PayFlowType {
    /** 用户主动点击购买。 */
    userPurchase,

    /** 启动/登录后静默补单。 */
    autoRepair,

    /** 用户点击“恢复购买”。 */
    manualRepair;

    /** 支付流程来源字段（仅日志）。 */
    val apiValue: String
        get() = when (this) {
            userPurchase -> "user_purchase"
            autoRepair -> "auto_repair"
            manualRepair -> "manual_repair"
        }

    /** iOS V2 验单 operation：新购 PURCHASE，补单/恢复 RESTORE。 */
    val iosOperation: String get() = if (isRepair) "RESTORE" else "PURCHASE"

    /** 自动或手动补单，都不走主动购买 UI/打点。 */
    val isRepair: Boolean get() = this == autoRepair || this == manualRepair

    val isManualRepair: Boolean get() = this == manualRepair
}

/** 商店交易状态（in_app_purchase 的 PurchaseStatus；取值即 SDPayModule `iapUpdate` 的 status）。 */
enum class PurchaseStatus {
    pending, purchased, error, canceled, restored;

    companion object {
        fun of(raw: String): PurchaseStatus? = when (raw) {
            "pending" -> pending
            "purchased" -> purchased
            "error" -> error
            "canceled", "cancelled" -> canceled
            "restored" -> restored
            else -> null
        }
    }
}

/**
 * 一笔商店交易（对应 in_app_purchase 的 SK2PurchaseDetails）。
 * 由 `iapUpdate` 事件或 `iapUnfinishedTransactions` 的条目构造。
 */
class PurchaseDetails(
    val productID: String,
    /** StoreKit 交易 id。 */
    val purchaseID: String?,
    val status: PurchaseStatus,
    val appAccountToken: String? = null,
    /** 单笔交易 JWS（signedTransactionInfo），验单原样上送。 */
    val serverVerificationData: String = "",
    /** 是否还需要 finish。与参考工程一致：只有 purchased 才可能为 true，restored 不 finish。 */
    val pendingCompletePurchase: Boolean = false,
    val errorMessage: String? = null,
    val transactionDate: Long? = null,
    /** 事件来源：purchase 本次购买的结果 / updates 后台到达（续订、待批准获批等）/ restore 恢复购买 / unfinished 补查。 */
    val source: String = SOURCE_UNFINISHED,
) {
    companion object {
        const val SOURCE_PURCHASE = "purchase"
        const val SOURCE_UPDATES = "updates"
        const val SOURCE_RESTORE = "restore"
        const val SOURCE_UNFINISHED = "unfinished"

        /** `iapUpdate` 事件里的一项；status 不认识的条目丢弃。 */
        fun fromUpdate(json: JSONObject, source: String): PurchaseDetails? {
            val status = PurchaseStatus.of(json.jStr("status")) ?: return null
            return PurchaseDetails(
                productID = json.jStr("productId"),
                purchaseID = json.jStr("transactionId").ifEmpty { null },
                status = status,
                appAccountToken = json.jStr("appAccountToken").ifEmpty { null },
                serverVerificationData = json.jStr("jws"),
                pendingCompletePurchase = status == PurchaseStatus.purchased && json.jBool("pendingFinish", false),
                errorMessage = json.jStr("errorMessage").ifEmpty { null },
                transactionDate = json.jLong("purchaseDateMs", 0L).takeIf { it > 0 },
                source = source,
            )
        }

        fun listFromUpdate(arr: JSONArray?, source: String): List<PurchaseDetails> {
            if (arr == null) return emptyList()
            val out = ArrayList<PurchaseDetails>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                fromUpdate(o, source)?.let { out.add(it) }
            }
            return out
        }

        /** `iapUnfinishedTransactions` 的一项：未完成交易一律按 purchased、待 finish 处理。 */
        fun fromUnfinished(json: JSONObject): PurchaseDetails {
            return PurchaseDetails(
                productID = json.jStr("productId"),
                purchaseID = json.jStr("transactionId").ifEmpty { null },
                status = PurchaseStatus.purchased,
                appAccountToken = json.jStr("appAccountToken").ifEmpty { null },
                serverVerificationData = json.jStr("jws"),
                pendingCompletePurchase = true,
                transactionDate = json.jLong("purchaseDateMs", 0L).takeIf { it > 0 },
            )
        }
    }
}

/** 单次主动购买上下文，仅 submit 创建和清理，补单不得读写。 */
class BuySession(
    /** 发起购买的页面（Routes.xxx），成功后决定是否关页。 */
    val currentPageName: String,
) {
    /** 商品 ID → 后端订单号。 */
    val orderMap = HashMap<String, String>()

    /** 商品 ID → 下单时的商品快照，供成功打点使用。 */
    val productMap = HashMap<String, GoodsModel>()

    /** Apple 下单返回的账户绑定。 */
    var iosOrder: IosOrder? = null

    /** 商店回了 pending（家长审批/SCA），结果可能很久才到，兜底计时不能按无结果释放。 */
    var storePending = false

    /** 发起购买时的账号代次与凭证：验单请求携带它，换号后不借用新账号的 token。 */
    val revision: Int = AuthSession.revision
    val authToken: String = AuthSession.accessToken

    /** 本次购买的结果回执；任何路径只回执一次。 */
    internal var attempt: PayAttempt? = null

    /** 本次购买的商店商品 ID 与类型，回执里用。 */
    var productId = ""
    var subscription = false

    fun setProduct(productId: String, product: GoodsModel) {
        productMap[productId] = product
    }

    fun getProduct(productId: String): GoodsModel? = productMap[productId]

    fun removeProduct(productId: String) {
        productMap.remove(productId)
    }

    fun clearProduct() = productMap.clear()

    fun setOrder(orderId: String, orderNum: String) {
        orderMap[orderId] = orderNum
    }

    fun getOrder(orderId: String): String? = orderMap[orderId]

    fun removeOrder(orderId: String) {
        orderMap.remove(orderId)
    }

    fun clearOrder() = orderMap.clear()

    fun clearAll() {
        clearProduct()
        clearOrder()
        iosOrder = null
        storePending = false
    }
}

/** 自动补单和手动补单共用，不复用 [BuySession]。 */
class RepairSession(
    /** 本轮是自动补单还是手动恢复，决定是否 toast。 */
    val flowType: PayFlowType,
    /** true 为手动恢复，需要 loading/toast；静默补单为 false。 */
    val showTips: Boolean,
) {
    /** 本轮开始时间，便于排查卡住的补单。 */
    val startedAt: Long = nowMs()

    /** 验单成功笔数，>0 才刷新用户权益。 */
    var successCount = 0

    /** 验单失败笔数，手动恢复时优先展示验单错误而不是“无可恢复内容”。 */
    var failureCount = 0

    /** 本轮是否验到订阅商品，用于订阅页自动返回。 */
    var hasSubscribe = false

    /** 本轮开始时的账号代次与凭证。 */
    val revision: Int = AuthSession.revision
    val authToken: String = AuthSession.accessToken

    /** 手动恢复时用户取消了 Apple ID 验证：这次恢复不算数，也不算失败。 */
    var restoreCanceled = false
}

/** 一笔支付宝操作的账号归属与轮询状态；旧会话不能修改新操作。 */
internal class AlipaySession(
    val userId: String,
    val pageName: String = "",
    /** 积分包为 true，自动续费为 false。两套本地订单不能混读。 */
    val trade: Boolean = false,
    /** 用户本次点的商品，只留在内存里给到账后的购买上报用。 */
    val goods: GoodsModel? = null,
) {
    val revision: Int = AuthSession.revision

    val userInitiated: Boolean get() = pageName.isNotEmpty()

    /** 续费靠签约链接，积分包靠 orderStr。有凭证才能再次打开收银台。 */
    val canReopenPayment: Boolean get() = if (trade) orderStr.isNotEmpty() else jumpSchema.isNotEmpty()

    var orderId = ""
    var jumpSchema = ""
    var orderStr = ""
    var productId = ""

    /** 建单返回的实付金额，只用于购买上报。 */
    var payAmount: Double? = null
    var pollTimer: PayTimer? = null
    var pollTicks = 0
    var lastNoticeStatus: String? = null
    var submitting = false
    var completing = false
    var querying = false

    /** 本次点击的结果回执；静默补查为 null。 */
    var attempt: PayAttempt? = null
}

/** 商店商品查询结果（in_app_purchase 的 ProductDetailsResponse）。 */
class ProductDetailsResponse(
    val productDetails: List<com.sondo.app.data.models.ProductDetails>,
    val notFoundIDs: List<String>,
    /** 平台不可用或查询失败（参考工程此处抛异常）。 */
    val error: String? = null,
)
