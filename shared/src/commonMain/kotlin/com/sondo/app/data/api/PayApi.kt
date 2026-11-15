package com.sondo.app.data.api

import com.sondo.app.base.AppEnv
import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiException
import com.sondo.app.core.http.ApiResult
import com.sondo.app.core.http.ReqOptions
import com.sondo.app.data.models.AlipayCancelResult
import com.sondo.app.data.models.AlipayOrderResult
import com.sondo.app.data.models.AlipayRenewState
import com.sondo.app.data.models.AlipaySubscriptionOrder
import com.sondo.app.data.models.AlipayTradeOrder
import com.sondo.app.data.models.AppleReturningPromotion
import com.sondo.app.data.models.AppleReturningPromotionResult
import com.sondo.app.data.models.GoodsModel
import com.sondo.app.data.models.IosOrder
import com.sondo.app.data.models.IosPaymentOptions
import com.sondo.app.data.models.IosVerifyResult
import com.sondo.app.data.models.IosVerifyStatus
import com.sondo.app.data.models.alipayAmount
import com.sondo.app.data.models.alipayBizCode
import com.sondo.app.data.models.alipayCount
import com.sondo.app.data.models.jArr
import com.sondo.app.data.models.jMap
import com.sondo.app.data.models.jRaw
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 内购 / 支付宝下单与验单（Dart: lib/shared/data/datasources/pay_remote_datasource.dart `PayRemoteDataSource`）。
 *
 * 返回约定：Dart 里返回「带可空 code 的结果对象」的方法（验单、促销签名、支付宝五个接口）在这里
 * **永远是 Success**，失败信息在结果对象里（code 为 null = 请求失败，非 0 = 业务拒绝），
 * 与 Dart 逐一对应；其余方法失败时返回 Failure。
 */
object PayApi {
    private const val PAY_TIMEOUT_MS = 30_000

    private val payOptions = ReqOptions(timeoutMs = PAY_TIMEOUT_MS)

    /** 支付宝 / 华为支付接口：业务码留给调用方，不弹全局错误。 */
    private val alipayOptions = ReqOptions(quiet = true, timeoutMs = PAY_TIMEOUT_MS)

    /**
     * 国内支付渠道的接口路径：Android 支付宝，鸿蒙华为支付。两套接口的请求和回包同构，
     * 下面 `*Alipay*` 方法名沿用支付宝，实际按平台选路径。
     */
    private fun cnPath(alipay: String, huawei: String): String = if (AppEnv.isOhos) huawei else alipay

    /** Apple V2 建单。商品 ID 为空、请求失败或回包不完整：Dart 返回 null。 */
    suspend fun createIosOrder(productId: String): ApiResult<IosOrder> {
        if (productId.trim().isEmpty()) return invalidRequest()
        return Api.post(
            ApiPaths.iosCreateOrder,
            JSONObject().put("productId", productId).put("paymentProvider", "APPLE"),
            opts = payOptions,
        ).parse { IosOrder.fromData(it.dataObject()) }
    }

    /**
     * 回归周卡促销签名。只传刚创建的订单号；优惠和商品由服务端决定。
     * 永远 Success：code 为业务码；网络或解析失败时 code 为 null，调用方按加载失败处理，不能改走原价。
     */
    suspend fun signReturningApplePromotion(orderId: String): ApiResult<AppleReturningPromotionResult> {
        val id = orderId.trim()
        if (id.isEmpty()) return ApiResult.Success(AppleReturningPromotionResult())
        val res = Api.post(ApiPaths.applePromotionalOffer, JSONObject().put("orderId", id), opts = payOptions)
        val out = when (res) {
            is ApiResult.Success -> {
                val data = res.data.dataObject()
                if (data == null) AppleReturningPromotionResult(null, 0)
                else AppleReturningPromotionResult(AppleReturningPromotion.fromMap(data), 0)
            }
            is ApiResult.Failure -> AppleReturningPromotionResult(null, intCode(res.error))
        }
        return ApiResult.Success(out)
    }

    /**
     * Apple V2 验单。PURCHASE 必须带 orderId；RESTORE 只传交易 JWS。
     * HTTP 体只有加密信封 data/key/iv（[ApiCrypto.hybridEncrypt]），不把 JWS 明文放在外层。
     * 永远 Success：参数无效、加密失败、请求失败都得到 status=unknown
     * （网络中断无法证明服务端没有处理，留待未完成交易补查）。
     */
    suspend fun verifyIosOrder(
        signedTransactionInfo: String,
        operation: String,
        orderId: String? = null,
    ): ApiResult<IosVerifyResult> {
        val unknown = ApiResult.Success(IosVerifyResult(IosVerifyStatus.unknown))
        if (signedTransactionInfo.trim().isEmpty()) return unknown
        if (operation == "PURCHASE" && orderId.isNullOrBlank()) return unknown
        val payload = JSONObject()
            .put("operation", operation)
            .put("signedTransactionInfo", signedTransactionInfo)
        if (operation == "PURCHASE") payload.put("orderId", orderId!!.trim())
        val envelope = ApiCrypto.hybridEncrypt(payload.toString())
        val data = envelope.optString("data", "")
        val key = envelope.optString("key", "")
        val iv = envelope.optString("iv", "")
        if (data.isEmpty() || key.isEmpty() || iv.isEmpty()) return unknown
        val res = Api.post(
            ApiPaths.iosVerifyOrder,
            JSONObject().put("data", data).put("key", key).put("iv", iv),
            opts = payOptions,
        )
        return when (res) {
            is ApiResult.Success -> ApiResult.Success(IosVerifyResult.fromResponse(res.data))
            is ApiResult.Failure -> unknown
        }
    }

    /** iOS 支付选项。商品数组在 data.products。失败或 data 不是对象：Dart 返回 null。 */
    suspend fun getIosPaymentOptions(
        productType: Int,
        applePlatform: Int,
        isRate: Boolean = false,
    ): ApiResult<IosPaymentOptions> =
        Api.get(
            ApiPaths.iosPaymentOptions,
            query = mapOf("productType" to productType, "applePlatform" to applePlatform, "isRate" to isRate),
        ).parse { env -> env.dataObject()?.let { IosPaymentOptions.fromData(it) } }

    /** 国内会员商品。`platform` 固定 12，不走 Apple 商品接口。失败或列表形状不对：Dart 返回 null。 */
    suspend fun getCnSubscriptionProducts(productType: Int, isRate: Boolean = false): ApiResult<List<GoodsModel>> =
        Api.get(
            ApiPaths.cnProductList,
            query = mapOf("platform" to 12, "productType" to productType, "isRate" to isRate),
        ).parse { cnGoodsFromBody(it, productType) }

    /** 创建积分包订单（支付宝 / 华为支付）。`orderStr` 原样交给 SDK，业务码留给调用方。永远 Success。 */
    suspend fun createAlipayTrade(productId: String): ApiResult<AlipayTradeOrder> {
        if (productId.trim().isEmpty()) return ApiResult.Success(AlipayTradeOrder())
        val res = Api.post(cnPath(ApiPaths.alipayTradeCreate, ApiPaths.huaweiTradeCreate), JSONObject().put("productId", productId), opts = alipayOptions)
        return ApiResult.Success(
            when (res) {
                is ApiResult.Success -> AlipayTradeOrder.fromResponse(res.data)
                is ApiResult.Failure -> AlipayTradeOrder(code = paymentFailureCode(res.error))
            },
        )
    }

    /** 创建自动续费签约单（支付宝给签约链接，华为支付给 contractStr）。业务码留给调用方，不在这里弹全局错误。永远 Success。 */
    suspend fun createAlipaySubscription(productId: String): ApiResult<AlipaySubscriptionOrder> {
        if (productId.trim().isEmpty()) return ApiResult.Success(AlipaySubscriptionOrder())
        val res = Api.post(
            cnPath(ApiPaths.alipaySubscriptionCreate, ApiPaths.huaweiSubscriptionCreate),
            JSONObject().put("productId", productId),
            opts = alipayOptions,
        )
        return ApiResult.Success(
            when (res) {
                is ApiResult.Success -> AlipaySubscriptionOrder.fromResponse(res.data)
                is ApiResult.Failure -> AlipaySubscriptionOrder(code = paymentFailureCode(res.error))
            },
        )
    }

    /** 查询本人订单。网络失败时 code 为 null，不能当成未付款。永远 Success。 */
    suspend fun queryAlipayOrder(orderId: String): ApiResult<AlipayOrderResult> {
        val id = orderId.trim()
        if (id.isEmpty()) return ApiResult.Success(AlipayOrderResult())
        val res = Api.get("${cnPath(ApiPaths.alipayOrder, ApiPaths.huaweiOrder)}/$id", opts = alipayOptions)
        return ApiResult.Success(
            when (res) {
                is ApiResult.Success -> AlipayOrderResult.fromResponse(res.data)
                is ApiResult.Failure -> AlipayOrderResult(code = paymentFailureCode(res.error))
            },
        )
    }

    /** 取消自动续费。成功只表示不再续扣，当前周期权益仍在。永远 Success。 */
    suspend fun cancelAlipaySubscription(): ApiResult<AlipayCancelResult> {
        val res = Api.post(cnPath(ApiPaths.alipaySubscriptionCancel, ApiPaths.huaweiSubscriptionCancel), opts = alipayOptions)
        return ApiResult.Success(
            when (res) {
                is ApiResult.Success -> AlipayCancelResult.fromResponse(res.data)
                is ApiResult.Failure -> AlipayCancelResult(code = paymentFailureCode(res.error))
            },
        )
    }

    /** 设置页点击后再查。不满足取消条件时不弹确认框。永远 Success。 */
    suspend fun fetchAlipayRenewState(): ApiResult<AlipayRenewState> {
        val res = Api.get(ApiPaths.subscriptionManagement, opts = alipayOptions)
        return ApiResult.Success(
            when (res) {
                is ApiResult.Success -> AlipayRenewState.fromResponse(res.data)
                is ApiResult.Failure -> AlipayRenewState(code = paymentFailureCode(res.error))
            },
        )
    }

    /** 失败回包里的整数业务码（Dart `code is int ? code : null`）。 */
    private fun intCode(error: ApiException): Int? {
        val code = error.body?.jRaw("code") as? Number ?: return null
        val d = code.toDouble()
        return if (d == d.toInt().toDouble()) d.toInt() else null
    }

    /** 网络异常不能代表业务成功；仅保留失败响应中的非零业务码。 */
    private fun paymentFailureCode(error: ApiException): Int? {
        val code = alipayBizCode(error.body)
        return if (code == 0) null else code
    }

    /**
     * 国内商品列表（Dart 顶层函数 `cnGoodsFromBody`）。[body] 是完整信封；
     * `data` 可能是数组，也可能包在 list / products / records 里。形状不对返回 null。
     */
    fun cnGoodsFromBody(body: JSONObject?, productType: Int? = null): List<GoodsModel>? {
        if (body == null || alipayBizCode(body) != 0) return null
        val raw: JSONArray = when (val data = body.jRaw("data")) {
            is JSONArray -> data
            is JSONObject -> (data.jRaw("list") ?: data.jRaw("products") ?: data.jRaw("records")) as? JSONArray
            else -> null
        } ?: return null
        return raw.jMap { cnGoods(it, productType) }
    }

    private fun cnGoods(json: JSONObject, requestedType: Int?): GoodsModel {
        val price = alipayAmount(json.jRaw("productPrice"))
        val first = alipayAmount(json.jRaw("firstPrice"))
        val renewal = alipayAmount(json.jRaw("renewalPrice"))
        val points = alipayCount(json.jRaw("points"))
        // 数字字符串形式的价格 / 积分先归一成数字再交给 GoodsModel
        var goods = GoodsModel.fromJson(json)
        if (price != null) goods = goods.copy(productPrice = price)
        if (points != null) goods = goods.copy(points = points)
        val type = goods.productType ?: requestedType
        // 积分包只发积分，不按订阅周期改名或改成月/年。
        if (type == 1) {
            return if (goods.productType == 1) goods else goods.copy(productType = 1)
        }
        val id = goods.productId ?: ""
        // 国内没有周订阅；旧周码和缺省周期统一按月，年卡保留实际周期。
        val period =
            if (goods.productSubscriptionPeriod == 3 ||
                (goods.productSubscriptionPeriod == null && id.contains("yearly"))
            ) 3 else 2
        val name = goods.productName
            ?.replace("周卡", "月卡")
            ?.replace("周订阅", "月订阅")
            ?.replace("每周", "每月")
            ?.replace(Regex("\\bWeekly\\b", RegexOption.IGNORE_CASE), "Monthly")
            ?.replace(Regex("\\bWeek\\b", RegexOption.IGNORE_CASE), "Month")
        val intro = if (first != null && renewal != null && first != renewal) money(first) else ""
        return goods.copy(
            productName = name,
            productPrice = price ?: renewal ?: first ?: goods.productPrice,
            productSubscriptionPeriod = period,
            introductoryPrice = intro,
            numberOfPeriods = if (intro.isEmpty()) 0 else 1,
            originalPrice = if (renewal == null) "" else money(renewal),
        )
    }

    /** 整数金额不带小数，其余原样（Dart `_money`）。 */
    private fun money(value: Double): String {
        if (value == value.toLong().toDouble() && value > -1e15 && value < 1e15) return value.toLong().toString()
        return value.toString()
    }
}
