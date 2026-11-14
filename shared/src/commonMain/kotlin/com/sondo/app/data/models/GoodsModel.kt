package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 商店商品详情（对应 Flutter in_app_purchase 的 `ProductDetails`）。
 * 字段与 SDPayModule.iapQueryProducts 返回的 products[] 一致；[raw] 保留完整原始对象（含 subscription / offers）。
 */
data class ProductDetails(
    var id: String = "",
    var title: String = "",
    var description: String = "",
    /** 本地化价格串 */
    var price: String = "",
    var rawPrice: Double = 0.0,
    var currencyCode: String = "",
    var currencySymbol: String = "",
    var raw: JSONObject? = null,
) {
    fun toJson(): JSONObject = raw ?: JSONObject()
        .put("id", id)
        .put("title", title)
        .put("description", description)
        .put("price", price)
        .put("rawPrice", rawPrice)
        .put("currencyCode", currencyCode)
        .put("currencySymbol", currencySymbol)

    companion object {
        fun fromJson(json: JSONObject): ProductDetails = ProductDetails(
            id = json.jStr("id"),
            title = json.jStr("title"),
            description = json.jStr("description"),
            price = json.jStr("price"),
            rawPrice = json.jDbl("rawPrice"),
            currencyCode = json.jStr("currencyCode"),
            currencySymbol = json.jStr("currencySymbol"),
            raw = json,
        )

        fun listFromJson(arr: JSONArray?): List<ProductDetails> = arr.jMap { fromJson(it) }
    }
}

/** 商品（Dart: lib/data/models/goods_model.dart `GoodsModel`）。 */
data class GoodsModel(
    var createTime: Long? = null,
    /** 商品唯一标识 */
    var productId: String? = null,
    /** 商品名称 */
    var productName: String? = null,
    /** 商品描述 */
    var productDesc: String = "",
    /** 商品价格 */
    var productPrice: Double? = null,
    /** 商品类型 1-一次性 2-订阅 */
    var productType: Int? = null,
    /** 商品订阅周期 1-周 2-月 3-年 */
    var productSubscriptionPeriod: Int? = null,
    /** 商品平台字段由服务端保留 */
    var platform: Int? = null,
    /** 商品是否上架 */
    var productIsActive: Boolean? = null,
    /** 当前商品多少积分 */
    var points: Int? = null,
    /** 当前商品能生成多少首歌 */
    var songNum: Int? = null,
    /** 一美元多少积分 */
    var pointDollar: Int = 0,
    /** Apple 侧附加 SKU；查商店仍用 [productId]。 */
    var appleProductId: String? = null,

    // —— app 添加的属性 —— //
    var productDetails: ProductDetails? = null,
    var selected: Boolean? = false,
    /** 首期订阅价格 */
    var introductoryPrice: String = "",
    /** 首期订阅周期数量 */
    var numberOfPeriods: Int = 0,
    /** 原价 */
    var originalPrice: String = "",
    /** Apple 促销优惠 ID。签名由建单接口返回后再交给 StoreKit。 */
    var appleOfferId: String? = null,
) {
    /** 列表价：有商店详情用商店价，否则用接口 productPrice。 */
    val shopPrice: String
        get() {
            val storePrice = productDetails?.price
            if (!storePrice.isNullOrEmpty()) return storePrice
            val p = productPrice ?: return ""
            return dartDoubleToString(p)
        }

    /** 与 Dart 一致：不含 createTime / productDetails / appleOfferId。 */
    fun toJson(): JSONObject = JSONObject()
        .put("productId", productId)
        .put("productName", productName)
        .put("productDesc", productDesc)
        .put("productPrice", productPrice)
        .put("productType", productType)
        .put("productSubscriptionPeriod", productSubscriptionPeriod)
        .put("platform", platform)
        .put("productIsActive", productIsActive)
        .put("points", points)
        .put("songNum", songNum)
        .put("pointDollar", pointDollar)
        .put("appleProductId", appleProductId)
        .put("selected", selected)
        .put("introductoryPrice", introductoryPrice)
        .put("numberOfPeriods", numberOfPeriods)
        .put("originalPrice", originalPrice)

    companion object {
        fun fromJson(json: JSONObject): GoodsModel = GoodsModel(
            createTime = json.jLongOrNull("createTime"),
            // 两端商店 SKU 都用 productId，不要用 appleProductId 覆盖。非字符串按 null。
            productId = json.jRaw("productId") as? String,
            productName = json.jStrOrNull("productName"),
            productDesc = json.jStr("productDesc"),
            productPrice = json.jDblOrNull("productPrice"),
            productType = json.jIntOrNull("productType"),
            productSubscriptionPeriod = json.jIntOrNull("productSubscriptionPeriod"),
            platform = json.jIntOrNull("platform"),
            productIsActive = json.jBoolOrNull("productIsActive"),
            points = json.jIntOrNull("points"),
            songNum = json.jIntOrNull("songNum"),
            pointDollar = json.jInt("pointDollar", 0),
            appleProductId = json.jStrOrNull("appleProductId"),
        )

        fun listFromJson(arr: JSONArray?): List<GoodsModel> = arr.jMap { fromJson(it) }
    }
}

/** Dart `double.toString()`：整数值也带一位小数（4.0），其余原样。 */
fun dartDoubleToString(d: Double): String {
    if (d == d.toLong().toDouble() && d > -1e15 && d < 1e15) return "${d.toLong()}.0"
    return d.toString()
}
