package com.sondo.app.service.pay

import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiResult
import com.sondo.app.core.http.AuthSession
import com.sondo.app.core.http.ReqOptions
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.DevLog
import com.sondo.app.data.api.ApiCrypto
import com.sondo.app.data.api.PayApi
import com.sondo.app.data.models.AlipayCancelResult
import com.sondo.app.data.models.AlipayOrderResult
import com.sondo.app.data.models.AlipayRenewState
import com.sondo.app.data.models.AlipaySubscriptionOrder
import com.sondo.app.data.models.AlipayTradeOrder
import com.sondo.app.data.models.ApplePromotionalSignature
import com.sondo.app.data.models.AppleReturningPromotionResult
import com.sondo.app.data.models.GoodsModel
import com.sondo.app.data.models.IosPaymentOptions
import com.sondo.app.data.models.IosOrder
import com.sondo.app.data.models.IosVerifyResult
import com.sondo.app.data.models.IosVerifyStatus
import com.sondo.app.data.models.ProductDetails
import com.sondo.app.nativeapi.SDPayModule
import com.sondo.app.nativeapi.nativeModule
import com.sondo.app.nativeapi.ok
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Apple 商店与后端的支付 I/O（Dart: lib/shared/pay/pay_store.dart `PayStore`）。
 *
 * 只做 StoreKit 2 桥接调用和 Apple 建单 / 验单请求，不持有会话。
 * 购买/补单会话、弹窗、页面跳转和埋点仍由 [PayService] 管理。
 * 原生模块取「当前页面」上的实例：状态机在宿主页面上下文里跑，取到的就是宿主的实例。
 */
internal object PayStore {
    /** 商店调用的超时（Dart 侧 `.timeout(const Duration(seconds: 30))`）。 */
    const val STORE_TIMEOUT_MS = 30_000

    private const val VERIFY_TIMEOUT_MS = 30_000

    private fun iap(): SDPayModule = nativeModule(SDPayModule.MODULE_NAME)

    // ------------------------------------------------------------------ 商店

    suspend fun isAvailable(): Boolean = try {
        iap().iapIsAvailable()
    } catch (e: Throwable) {
        false
    }

    /** 查询商店商品详情；查询失败时 error 非空。 */
    suspend fun queryProductDetails(ids: Set<String>): ProductDetailsResponse {
        val res = try {
            iap().iapQueryProducts(ids.toList())
        } catch (e: Throwable) {
            return ProductDetailsResponse(emptyList(), ids.toList(), error = e.toString())
        }
        if (!res.ok()) {
            return ProductDetailsResponse(emptyList(), ids.toList(), error = res.optString("error", "query failed"))
        }
        val details = ProductDetails.listFromJson(res.optJSONArray("products"))
        val notFound = ArrayList<String>()
        val arr = res.optJSONArray("notFound")
        if (arr != null) {
            for (i in 0 until arr.length()) arr.optString(i)?.let { if (it.isNotEmpty()) notFound.add(it) }
        }
        return ProductDetailsResponse(details, notFound)
    }

    /** SK2Product.isIntroductoryOfferEligible。 */
    suspend fun isIntroductoryOfferEligible(productId: String): Boolean = iap().iapIsIntroEligible(productId)

    /**
     * 拉起 StoreKit 购买。返回 false 表示没有发起（商品查不到、令牌 / 促销参数无效、原生已有一笔购买在进行）。
     *
     * 原生在付款面板弹出**之前**就返回 started=true；之后每笔发起的购买恰好回一个
     * `iapUpdate`（source = purchase，status = purchased / pending / canceled / error）到 [PayService]。
     */
    suspend fun buy(purchaseParam: JSONObject): Boolean {
        val res = try {
            iap().iapPurchase(purchaseParam)
        } catch (e: Throwable) {
            DevLog.logger("Apple 发起购买失败: $e")
            return false
        }
        return res.ok() && res.optBoolean("started", false)
    }

    /**
     * `AppStore.sync` + 把当前权益逐笔以 restored 事件送出（事件先于本调用返回）。
     * 同步失败（断网等）原生仍会送出本机已有的权益，算成功。
     * @return [RESTORE_OK] / [RESTORE_CANCELED]（用户取消了 Apple ID 验证）/ [RESTORE_FAILED]（含超时）
     */
    suspend fun restorePurchases(): String {
        val res = PayHost.withTimeout(STORE_TIMEOUT_MS) { iapRestoreRaw() } ?: return RESTORE_FAILED
        if (res.ok()) return RESTORE_OK
        return if (res.optString("error", "") == "canceled") RESTORE_CANCELED else RESTORE_FAILED
    }

    /** `SDPayModule.iapRestore()` 只给布尔值；这里要区分「用户取消」，直接取原生回包 `{ok, error?, synced?, count?}`。 */
    private suspend fun iapRestoreRaw(): JSONObject = suspendCoroutine { cont ->
        iap().asyncToNativeMethod("iapRestore", null) { cont.resume(it ?: JSONObject()) }
    }

    const val RESTORE_OK = "ok"
    const val RESTORE_CANCELED = "canceled"
    const val RESTORE_FAILED = "failed"

    // ------------------------------------------------------------------ 建单

    /**
     * Apple 建单：新购使用 V2 接口；补单不创建订单，由后端凭 JWS 关联。
     * 空结果最多再试 [maxRetry] 次，间隔 2 秒。
     */
    suspend fun createOrder(
        productId: String,
        flowType: PayFlowType,
        logPrefix: String,
        onIosOrder: ((IosOrder) -> Unit)? = null,
        maxRetry: Int = 3,
    ): String {
        if (productId.trim().isEmpty()) {
            DevLog.logger("$logPrefix---商品ID为空，跳过创建订单")
            return ""
        }
        DevLog.logger("$logPrefix---start Apple order:$productId (${flowType.apiValue})")

        suspend fun createOnce(): String {
            val order = PayBackend.createIosOrder(productId) ?: return ""
            onIosOrder?.invoke(order)
            return order.orderNum
        }

        var orderNum = createOnce()
        var retry = 0
        while (orderNum.isEmpty() && retry < maxRetry) {
            PayHost.sleep(2000)
            orderNum = createOnce()
            retry++
        }
        if (orderNum.isEmpty()) {
            DevLog.logger("$logPrefix---Apple 创建订单失败:$productId")
        }
        return orderNum
    }

    // ------------------------------------------------------------------ 验单

    /**
     * Apple StoreKit 2 验单。PURCHASE 带订单号，RESTORE 由服务端凭 JWS 关联。
     *
     * @param authToken 发起这笔购买（或这轮补单）的账号凭证；验单请求只带它，不借用换号后的新凭证
     * @param revision 该凭证所属的账号代次
     */
    suspend fun verify(
        purchase: PurchaseDetails,
        flowType: PayFlowType,
        isNewOrder: Boolean,
        orderNum: String,
        rejectUserPurchase: Boolean,
        authToken: String,
        revision: Int,
        isLoggedIn: () -> Boolean,
    ): PayResult {
        if (!isLoggedIn()) {
            return PayResult(status = false, message = "order_exception".tr)
        }
        val transactionId = purchase.purchaseID
        val signedTransactionInfo = purchase.serverVerificationData
        if (transactionId.isNullOrEmpty() || signedTransactionInfo.isEmpty()) {
            return PayResult(status = false, message = "order_exception".tr)
        }
        if (rejectUserPurchase) {
            DevLog.logger("iOS 验单 rejectUserPurchase: $rejectUserPurchase isNewOrder:$isNewOrder")
            return PayResult(status = false)
        }

        for (attempt in 0 until 3) {
            if (!isLoggedIn()) break
            val result = verifyIosOrder(
                signedTransactionInfo = signedTransactionInfo,
                operation = flowType.iosOperation,
                orderId = if (flowType == PayFlowType.userPurchase) orderNum else null,
                authToken = authToken,
                revision = revision,
            )
            DevLog.logger("iOS 验单 transactionId:$transactionId status:${result.status.name}")
            if (result.succeeded) {
                return PayResult(status = true)
            }
            if (flowType.isRepair && result.status == IosVerifyStatus.ineligible) {
                return PayResult(status = false, finishTransaction = true)
            }
            if (!result.retryable || attempt == 2) break
            PayHost.sleep((attempt + 1) * 1000)
        }
        return PayResult(status = false, message = "check_pay_info_error".tr)
    }

    /**
     * Apple V2 验单请求。PURCHASE 必须带 orderId；RESTORE 只传交易 JWS。
     *
     * HTTP 体只有加密信封 data/key/iv，不把 JWS 明文放在外层。请求与当前登录会话隔离
     * （[ReqOptions.isolatedPayment]）：带发起账号自己的凭证，失败不弹全局错、不触发登出。
     * 凭证过期（401）且账号没换时，按普通请求的规则续期后重发一次。
     */
    private suspend fun verifyIosOrder(
        signedTransactionInfo: String,
        operation: String,
        orderId: String?,
        authToken: String,
        revision: Int,
    ): IosVerifyResult {
        if (signedTransactionInfo.trim().isEmpty()) return IosVerifyResult(IosVerifyStatus.unknown)
        if (operation == "PURCHASE" && orderId.isNullOrBlank()) return IosVerifyResult(IosVerifyStatus.unknown)
        val payload = JSONObject()
            .put("operation", operation)
            .put("signedTransactionInfo", signedTransactionInfo)
        if (operation == "PURCHASE") payload.put("orderId", orderId!!.trim())

        val envelope = try {
            ApiCrypto.hybridEncrypt(payload.toString())
        } catch (e: Throwable) {
            DevLog.logger("iOS 验单信封加密失败")
            return IosVerifyResult(IosVerifyStatus.unknown)
        }
        val data = envelope.optString("data", "")
        val key = envelope.optString("key", "")
        val iv = envelope.optString("iv", "")
        if (data.isEmpty() || key.isEmpty() || iv.isEmpty()) return IosVerifyResult(IosVerifyStatus.unknown)
        val body = JSONObject().put("data", data).put("key", key).put("iv", iv)

        var token = authToken.ifEmpty { if (AuthSession.isCurrent(revision)) AuthSession.accessToken else "" }
        if (token.isEmpty()) return IosVerifyResult(IosVerifyStatus.unknown)
        var res = postVerify(body, token)
        if (isUnauthorized(res) && AuthSession.isCurrent(revision)) {
            val current = AuthSession.accessToken
            if (current.isNotEmpty() && current != token) {
                // 同账号的其他请求已经续期过
                token = current
                res = postVerify(body, token)
            } else if (Api.ensureRefreshed(AuthSession.refreshToken, revision)) {
                token = AuthSession.accessToken
                res = postVerify(body, token)
            }
        }
        return when (res) {
            is ApiResult.Success -> IosVerifyResult.fromResponse(res.data)
            // 网络中断无法证明服务端没有处理，留待未完成交易补查。
            is ApiResult.Failure -> {
                DevLog.logger("iOS 验单结果未确认")
                IosVerifyResult(IosVerifyStatus.unknown)
            }
        }
    }

    private suspend fun postVerify(body: JSONObject, token: String): ApiResult<JSONObject> = Api.post(
        ApiPaths.iosVerifyOrder,
        body,
        opts = ReqOptions(isolatedPayment = true, authorization = token, timeoutMs = VERIFY_TIMEOUT_MS),
    )

    private fun isUnauthorized(res: ApiResult<JSONObject>): Boolean = when (res) {
        is ApiResult.Success -> res.data.optInt("code", -1) == 401
        is ApiResult.Failure -> res.error.httpStatus == 401 || res.error.body?.optInt("code", -1) == 401
    }

    // ------------------------------------------------------------------ 完成交易

    /** 完成 Apple 交易。积分商品同样由 StoreKit 统一完成。只在后端验过之后调用。 */
    suspend fun finish(purchase: PurchaseDetails, consumable: Boolean) {
        val id = purchase.purchaseID
        if (id.isNullOrEmpty() || !purchase.pendingCompletePurchase) return
        try {
            val done = iap().iapFinish(id)
            if (!done) DevLog.logger("iOS 完成交易失败 consumable:$consumable")
        } catch (e: Throwable) {
            DevLog.logger("iOS 完成交易失败:$e")
        }
    }

    // ------------------------------------------------------------------ 历史 / 未完成交易

    /**
     * 用到期时间判断这个 Apple 账号是否订阅过。查询失败（含超时）返回 null，由调用方改走促销签名。
     * 原生读 `Transaction.all`，按商品合并成 `{productId, expirationDateMs?, active}`：已过期的订阅也在里面。
     */
    suspend fun appleAccountHasSubscription(): Boolean? {
        val res = PayHost.withTimeout(STORE_TIMEOUT_MS) { iap().iapTransactionHistory() } ?: return null
        if (!res.ok()) return null
        val arr = res.optJSONArray("transactions") ?: return false
        val dates = ArrayList<Any?>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            dates.add(if (item.has("expirationDateMs")) item.opt("expirationDateMs") else item.opt("expirationDate"))
        }
        return appleAccountSubscribedBefore(dates)
    }

    /** 从 StoreKit 拉未完成交易。查询失败（含超时）返回 null。 */
    suspend fun unfinishedIosPurchases(): List<PurchaseDetails>? {
        val res = PayHost.withTimeout(STORE_TIMEOUT_MS) { iap().iapUnfinishedTransactions() } ?: return null
        if (!res.ok()) return null
        val arr = res.optJSONArray("transactions") ?: return emptyList()
        val out = ArrayList<PurchaseDetails>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            out.add(PurchaseDetails.fromUnfinished(item))
        }
        return out
    }
}

/**
 * 购买参数（`SDPayModule.iapPurchase` 的入参）。回归周卡优惠：Apple 促销签名缺失时回退到普通购买参数。
 * [applicationUserName] 是建单返回的 appAccountToken，原样交给 StoreKit。
 */
fun purchaseParamWithStoreOffer(
    details: ProductDetails,
    applicationUserName: String,
    consumable: Boolean,
    appleOfferId: String? = null,
    appleSignature: ApplePromotionalSignature? = null,
): JSONObject {
    val param = JSONObject()
        .put("productId", details.id)
        .put("appAccountToken", applicationUserName)
        .put("consumable", consumable)
    if (!appleOfferId.isNullOrEmpty() && appleSignature != null) {
        param.put(
            "promotionalOffer",
            JSONObject()
                .put("offerId", appleOfferId)
                .put("keyId", appleSignature.keyId)
                .put("nonce", appleSignature.nonce)
                .put("timestamp", appleSignature.timestamp)
                .put("signature", appleSignature.signature),
        )
    }
    return param
}

/** 非空到期时间才算订阅过。消耗型没有到期时间。 */
fun appleAccountSubscribedBefore(expirationDates: Iterable<Any?>): Boolean = expirationDates.any { date ->
    when (date) {
        null -> false
        is Number -> date.toDouble() > 0
        is String -> date.isNotEmpty() && date != "null"
        else -> false
    }
}

/**
 * 后端支付接口的薄封装：把 [PayApi] 的 ApiResult 还原成参考工程数据源的返回形状
 * （失败 = null / 只带业务码的结果对象），状态机里不用到处判 Success / Failure。
 * Apple 验单不在这里：它要带发起账号的凭证并与当前会话隔离，见 [PayStore.verify]。
 */
internal object PayBackend {
    /** Apple V2 建单。失败返回 null。 */
    suspend fun createIosOrder(productId: String): IosOrder? = PayApi.createIosOrder(productId).getOrNull()

    /** 回归周卡促销签名。code 为业务码；网络或解析失败时 code 为 null。 */
    suspend fun signReturningApplePromotion(orderId: String): AppleReturningPromotionResult =
        PayApi.signReturningApplePromotion(orderId).getOrNull() ?: AppleReturningPromotionResult()

    suspend fun getIosPaymentOptions(productType: Int, applePlatform: Int, isRate: Boolean): IosPaymentOptions? =
        PayApi.getIosPaymentOptions(productType, applePlatform, isRate).getOrNull()

    /** 国内商品（platform=12）；会员商品的周卡已在数据层统一改写成月卡。 */
    suspend fun getCnSubscriptionProducts(productType: Int, isRate: Boolean): List<GoodsModel>? =
        PayApi.getCnSubscriptionProducts(productType, isRate).getOrNull()

    suspend fun createAlipayTrade(productId: String): AlipayTradeOrder =
        PayApi.createAlipayTrade(productId).getOrNull() ?: AlipayTradeOrder()

    suspend fun createAlipaySubscription(productId: String): AlipaySubscriptionOrder =
        PayApi.createAlipaySubscription(productId).getOrNull() ?: AlipaySubscriptionOrder()

    /** 查询本人订单。网络失败时 code 为 null，不能当成未付款。 */
    suspend fun queryAlipayOrder(orderId: String): AlipayOrderResult =
        PayApi.queryAlipayOrder(orderId).getOrNull() ?: AlipayOrderResult()

    suspend fun cancelAlipaySubscription(): AlipayCancelResult =
        PayApi.cancelAlipaySubscription().getOrNull() ?: AlipayCancelResult()

    suspend fun fetchAlipayRenewState(): AlipayRenewState =
        PayApi.fetchAlipayRenewState().getOrNull() ?: AlipayRenewState()
}
