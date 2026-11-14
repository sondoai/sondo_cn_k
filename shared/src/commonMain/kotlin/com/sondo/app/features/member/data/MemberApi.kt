package com.sondo.app.features.member.data

import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiResult
import com.sondo.app.core.utils.DevLog
import com.sondo.app.data.api.PayApi
import com.sondo.app.data.models.CreditRecordModel
import com.sondo.app.data.models.GoodsModel
import com.sondo.app.data.models.PageResult

/**
 * 会员域接口（Dart: features/member/data/datasources/member_remote_datasource.dart
 * + repositories/member_repository_impl.dart）。
 *
 * Dart 的 Entity（CreditRecordEntity / GoodsEntity）与 Model 字段一一相同，Mapper 只做搬运；
 * 这里直接返回 [CreditRecordModel] / [GoodsModel]（`formattedTimestamp` / `formattedAmount` / `isSpending` 已在 Model 上）。
 */
object MemberApi {

    /** 积分流水分页。[addStatus]：true 只看获得，false 只看消耗，null 全部。失败时 Dart 返回 `request_failed`。 */
    suspend fun getCreditRecords(
        pageNo: Int,
        pageSize: Int = 20,
        addStatus: Boolean? = null,
    ): ApiResult<PageResult<CreditRecordModel>> {
        val query = LinkedHashMap<String, Any?>()
        query["pageNo"] = pageNo
        query["pageSize"] = pageSize
        if (addStatus != null) query["addStatus"] = addStatus
        return when (val res = Api.get(ApiPaths.pointRecordPage, query = query)) {
            is ApiResult.Failure -> {
                DevLog.logger("fetchCreditRecords: ${res.error}")
                res
            }
            is ApiResult.Success -> {
                val data = res.data.optJSONObject("data")
                ApiResult.Success(PageResult.fromData(data) { CreditRecordModel.fromJson(it) })
            }
        }
    }

    /**
     * 商品列表（纯后端数据，不含商店价格）。[platform] 为 12 时走国内支付宝商品，其余为 Apple 的 applePlatform。
     */
    suspend fun getProductList(productType: Int, platform: Int, isRate: Boolean = false): ApiResult<List<GoodsModel>> {
        // 国内 Android 会员不查 Apple 商品。
        if (platform == 12) {
            return PayApi.getCnSubscriptionProducts(productType = productType, isRate = isRate)
                .onFailure { DevLog.logger("fetchProductList: $it") }
        }
        return when (
            val res = PayApi.getIosPaymentOptions(productType = productType, applePlatform = platform, isRate = isRate)
        ) {
            is ApiResult.Success -> ApiResult.Success(res.data.products)
            is ApiResult.Failure -> {
                // Dart：getIosPaymentOptions 出错也返回 null → 空列表成功；
                // 由 PayController.getShopList 再取一次，仍为空时提示 product_not_found
                DevLog.logger("fetchProductList: ${res.error}")
                ApiResult.Success(emptyList())
            }
        }
    }
}
