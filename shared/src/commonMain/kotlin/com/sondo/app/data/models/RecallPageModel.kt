package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.math.roundToInt

/** null 当成空串，其余转字符串（Dart `_text`）。 */
private fun JSONObject?.text(key: String): String = this?.jStr(key) ?: ""

/** 回归页后台配置（Dart: lib/data/models/recall_page_model.dart `RecallPageModel`）。 */
data class RecallPageModel(
    /** 商品区开关。关则只藏购买区，不影响上面的内容。内容接口不带这个字段，默认关闭。 */
    var productEnabled: Boolean = false,
    var welcome: RecallMedia = RecallMedia(),
    var sectionTitle: String = "",
    var cards: List<RecallCard> = emptyList(),
    var product: RecallProductContent = RecallProductContent(),
) {
    /** 启用中的卡片，按排序升序。没有则功能区整段隐藏。 */
    val enabledCards: List<RecallCard> get() = cards.filter { it.enabled }.sortedBy { it.sort }

    /** 只替换商品开关和商品文案，欢迎区和功能卡片保持内容接口的结果。 */
    fun copyWithProduct(productEnabled: Boolean, product: RecallProductContent): RecallPageModel = RecallPageModel(
        productEnabled = productEnabled,
        welcome = welcome,
        sectionTitle = sectionTitle,
        cards = cards,
        product = product,
    )

    /** 旧结构（与 [fromJson] 对应）。 */
    fun toJson(): JSONObject = JSONObject()
        .put("productEnabled", productEnabled)
        .put("welcome", welcome.toJson())
        .put("sectionTitle", sectionTitle)
        .put("cards", cards.toJsonArray { it.toJson() })
        .put("product", product.toJson())

    companion object {
        /** 落地页内容：banner 和功能卡片。商品上方的图在页面里写死，不读 promotion。 */
        fun fromContentJson(json: JSONObject): RecallPageModel {
            val banner = json.jObj("banner")
            val section = json.jObj("videoSection")
            val cards = ArrayList<RecallCard>()
            section?.jArr("cards").jMap { item ->
                // sort 用数组下标，避免相同排序值时顺序不稳定。
                cards.add(RecallCard.fromContentJson(item, sort = cards.size))
            }
            return RecallPageModel(
                welcome = RecallMedia.fromBanner(banner),
                sectionTitle = section.text("title"),
                cards = cards,
            )
        }

        /** 商品配置。首页入口和商品页仍用这份旧结构。 */
        fun fromJson(json: JSONObject): RecallPageModel {
            val product = json.jObj("product")
            return RecallPageModel(
                productEnabled = json.jRaw("productEnabled") == true,
                welcome = RecallMedia.fromJson(json.jObj("welcome")),
                sectionTitle = json.text("sectionTitle"),
                cards = json.jArr("cards").jMap { RecallCard.fromJson(it) },
                product = if (product != null) RecallProductContent.fromJson(product) else RecallProductContent(),
            )
        }

        fun listFromJson(arr: JSONArray?): List<RecallPageModel> = arr.jMap { fromJson(it) }
    }
}

/** 回归页媒体块（Dart: lib/data/models/recall_page_model.dart `RecallMedia`）。 */
data class RecallMedia(
    /** `video` 或 `image`。 */
    var mediaType: String = "",
    var mediaUrl: String = "",
    var coverUrl: String = "",
    var title: String = "",
    var description: String = "",
) {
    val isVideo: Boolean get() = mediaType == "video" && mediaUrl.isNotEmpty()

    val hasImage: Boolean get() = !isVideo && mediaUrl.isNotEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("mediaType", mediaType)
        .put("mediaUrl", mediaUrl)
        .put("coverUrl", coverUrl)
        .put("title", title)
        .put("description", description)

    companion object {
        fun fromJson(json: JSONObject?): RecallMedia {
            if (json == null) return RecallMedia()
            return RecallMedia(
                mediaType = json.text("mediaType"),
                mediaUrl = json.text("mediaUrl"),
                coverUrl = json.text("coverUrl"),
                title = json.text("title"),
                description = json.text("description"),
            )
        }

        /** 有 backgroundUrl 就播视频，海报作封面。空或空串不播；这时有海报就当静态头图。 */
        fun fromBanner(json: JSONObject?): RecallMedia {
            if (json == null) return RecallMedia()
            val backgroundUrl = json.text("backgroundUrl").trim()
            val posterUrl = json.text("posterUrl").trim()
            val title = json.text("title")
            val description = json.text("description")
            if (backgroundUrl.isNotEmpty()) {
                return RecallMedia(
                    mediaType = "video",
                    mediaUrl = backgroundUrl,
                    coverUrl = posterUrl,
                    title = title,
                    description = description,
                )
            }
            if (posterUrl.isNotEmpty()) {
                return RecallMedia(mediaType = "image", mediaUrl = posterUrl, title = title, description = description)
            }
            return RecallMedia(title = title, description = description)
        }

        fun listFromJson(arr: JSONArray?): List<RecallMedia> = arr.jMap { fromJson(it) }
    }
}

/** 回归页功能卡片（Dart: lib/data/models/recall_page_model.dart `RecallCard`）。 */
data class RecallCard(
    var media: RecallMedia = RecallMedia(),
    var sort: Int = 0,
    var enabled: Boolean = false,
) {
    fun toJson(): JSONObject = media.toJson().put("sort", sort).put("enabled", enabled)

    companion object {
        fun fromJson(json: JSONObject): RecallCard = RecallCard(
            media = RecallMedia.fromJson(json),
            sort = json.jStrOrNull("sort")?.trim()?.toIntOrNull() ?: 0,
            enabled = json.jRaw("enabled") == true,
        )

        /** 按数组顺序展示。有视频地址就播视频，只有封面时显示图片，都没有则不留媒体区。 */
        fun fromContentJson(json: JSONObject, sort: Int = 0): RecallCard {
            val videoUrl = json.text("videoUrl")
            val coverUrl = json.text("coverUrl")
            val title = json.text("title")
            val description = json.text("description")
            val media = when {
                videoUrl.isNotEmpty() -> RecallMedia(
                    mediaType = "video",
                    mediaUrl = videoUrl,
                    coverUrl = coverUrl,
                    title = title,
                    description = description,
                )
                coverUrl.isNotEmpty() ->
                    RecallMedia(mediaType = "image", mediaUrl = coverUrl, title = title, description = description)
                else -> RecallMedia(title = title, description = description)
            }
            return RecallCard(media = media, sort = sort, enabled = true)
        }

        fun listFromJson(arr: JSONArray?): List<RecallCard> = arr.jMap { fromJson(it) }
    }
}

/** 回归页商品文案（Dart: lib/data/models/recall_page_model.dart `RecallProductContent`）。 */
data class RecallProductContent(
    var media: RecallMedia = RecallMedia(),
    var tags: List<String> = emptyList(),
    var buttonText: String = "",
    var iosProductId: String = "",
) {
    val productId: String get() = iosProductId

    fun toJson(): JSONObject = media.toJson()
        .put("tags", tags.toJsonStringArray())
        .put("buttonText", buttonText)
        .put("iosProductId", iosProductId)

    companion object {
        fun fromJson(json: JSONObject): RecallProductContent = RecallProductContent(
            media = RecallMedia.fromJson(json),
            tags = json.jArr("tags").jStrList().filter { it.isNotEmpty() },
            buttonText = json.text("buttonText"),
            iosProductId = json.text("iosProductId"),
        )

        fun listFromJson(arr: JSONArray?): List<RecallProductContent> = arr.jMap { fromJson(it) }
    }
}

/** 回归优惠接口数据；iOS 使用 Apple，Android 使用支付宝（Dart: lib/data/models/recall_page_model.dart `ReturningOffer`）。 */
data class ReturningOffer(
    var visible: Boolean = false,
    var canPurchase: Boolean = false,
    var loginRequired: Boolean = false,
    /** 接口可不返回。空字符串同样视为没有。 */
    var blockReason: String? = null,
    var returnCycleId: String? = null,
    var hasProduct: Boolean = false,
    var productId: String = "",
    var paymentProvider: String = "",
    var displayName: String = "",
    var offerId: String = "",
    var points: Int = 0,
    var billingPeriod: String = "",
    var firstPrice: Double? = null,
    var renewalPrice: Double? = null,
    var currency: String = "",
) {
    /** 接口明确允许展示并且允许购买。价格和商店核验另做。 */
    val purchasable: Boolean get() = visible && canPurchase

    /** 未登录时接口会把 canPurchase 设为 false。商品信息齐就可以先查商店。 */
    val canLoadStoreProduct: Boolean
        get() {
            if (canPurchase) return true
            if (loginRequired) return true
            return (blockReason ?: "").uppercase() == "LOGIN_REQUIRED"
        }

    /** 登录门槛已经过了，接口仍不允许买。只差登录时不算没资格。 */
    val blockedAfterLogin: Boolean
        get() {
            if (!visible) return true
            if (canPurchase) return false
            return !canLoadStoreProduct
        }

    /** 支付宝旧周周期按月展示，Apple 保留商店周周期；月卡和年卡沿用接口。 */
    val subscriptionPeriod: Int?
        get() = when (billingPeriod.uppercase()) {
            "P1W" -> if (paymentProvider.uppercase() == "ALIPAY") 2 else 1
            "P1M" -> 2
            "P1Y" -> 3
            else -> null
        }

    /** 首期确有优惠才展示折扣，不能把后端任意价格差都写成五折。 */
    val discountPercentage: Int?
        get() {
            val first = firstPrice
            val renewal = renewalPrice
            if (first == null || renewal == null || !first.isFinite() || !renewal.isFinite() ||
                first < 0 || renewal <= first
            ) return null
            val percentage = ((renewal - first) / renewal * 100).roundToInt()
            return if (percentage > 0) percentage else null
        }

    /** Apple 需要促销 ID；支付宝直接使用完整的后端价格与周期。 */
    fun isComplete(ios: Boolean): Boolean {
        if (!hasProduct || productId.isEmpty()) return false
        val provider = paymentProvider.uppercase()
        if (ios) return provider == "APPLE" && offerId.isNotEmpty()
        val first = firstPrice
        val renewal = renewalPrice
        return provider == "ALIPAY" &&
            first != null && first.isFinite() && first >= 0 &&
            renewal != null && renewal.isFinite() && renewal > 0 &&
            currency.trim().isNotEmpty() &&
            subscriptionPeriod != null
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("visible", visible)
            .put("canPurchase", canPurchase)
            .put("loginRequired", loginRequired)
            .put("blockReason", blockReason)
            .put("returnCycleId", returnCycleId)
        if (hasProduct) {
            o.put(
                "product",
                JSONObject()
                    .put("productId", productId)
                    .put("paymentProvider", paymentProvider)
                    .put("displayName", displayName)
                    .put("offerId", offerId)
                    .put("points", points)
                    .put("billingPeriod", billingPeriod)
                    .put("firstPrice", firstPrice)
                    .put("renewalPrice", renewalPrice)
                    .put("currency", currency),
            )
        }
        return o
    }

    companion object {
        fun fromJson(json: JSONObject): ReturningOffer {
            val product = json.jObj("product")
            return ReturningOffer(
                visible = json.jRaw("visible") == true,
                canPurchase = json.jRaw("canPurchase") == true,
                loginRequired = json.jRaw("loginRequired") == true,
                blockReason = optionalText(json.jStrOrNull("blockReason")),
                returnCycleId = optionalText(json.jStrOrNull("returnCycleId")),
                hasProduct = product != null,
                productId = product.text("productId"),
                paymentProvider = product.text("paymentProvider"),
                displayName = product.text("displayName"),
                offerId = product.text("offerId"),
                points = product?.jInt("points", 0) ?: 0,
                billingPeriod = product.text("billingPeriod"),
                firstPrice = product?.jDblOrNull("firstPrice"),
                renewalPrice = product?.jDblOrNull("renewalPrice"),
                currency = product.text("currency"),
            )
        }

        /** 缺字段或空字符串都当成没有。 */
        private fun optionalText(value: String?): String? {
            val text = value?.trim() ?: return null
            return if (text.isEmpty()) null else text
        }

        fun listFromJson(arr: JSONArray?): List<ReturningOffer> = arr.jMap { fromJson(it) }
    }
}
