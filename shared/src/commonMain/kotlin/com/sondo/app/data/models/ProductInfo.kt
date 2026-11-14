package com.sondo.app.data.models

import com.sondo.app.core.constants.AppConstants

/** Apple StoreKit 商品的本地兜底信息（Dart: lib/core/constants/product_info.dart `ProductInfo`）。 */
object ProductInfo {
    private val iosAddCredit200 = GoodsModel(
        productId = "ios_add_credit_200", productName = "Add Credit 200", productPrice = 4.99, productType = 1,
    )
    private val iosAddCredit800 = GoodsModel(
        productId = "ios_add_credit_800", productName = "Add Credit 800", productPrice = 9.99, productType = 1,
    )
    private val iosAddCredit3200 = GoodsModel(
        productId = "ios_add_credit_3200", productName = "Add Credit 3200", productPrice = 29.99, productType = 1,
    )
    private val iosAddCredit8000 = GoodsModel(
        productId = "ios_add_credit_8000", productName = "Add Credit 8000", productPrice = 59.99, productType = 1,
    )

    private val iosSuperProWeeklyDiscount260422 = GoodsModel(
        productId = "ios_super_pro_weekly_discount_260422", productName = "Sub Vip Plan Weekly",
        productPrice = 29.99, productType = 2,
    )
    private val iosSuperProWeekly260330 = GoodsModel(
        productId = "ios_super_pro_weekly_260330", productName = "Sub Vip Plan Weekly",
        productPrice = 29.99, productType = 2,
    )
    private val iosSuperProWeekly = GoodsModel(
        productId = "ios_super_pro_weekly", productName = "Sub Vip Plan Weekly", productPrice = 29.99, productType = 2,
    )
    private val iosSubVipPlanWeekly250915 = GoodsModel(
        productId = "ios_sub_vip_plan_weekly_250915", productName = "Sub Vip Plan Weekly",
        productPrice = 9.99, productType = 2,
    )
    private val iosSubVipPlanMonthly250915 = GoodsModel(
        productId = "ios_sub_vip_plan_monthly_250915", productName = "Sub Vip Plan Monthly",
        productPrice = 29.99, productType = 2,
    )
    private val iosSubVipPlanYearly = GoodsModel(
        productId = "ios_sub_vip_plan_yearly", productName = "Sub Vip Plan Yearly", productPrice = 59.99, productType = 2,
    )
    private val iosSubVipPlanMonthly = GoodsModel(
        productId = "ios_sub_vip_plan_monthly", productName = "Sub Vip Plan Monthly", productPrice = 9.99, productType = 2,
    )
    private val iosSubVipPlanWeekly = GoodsModel(
        productId = "ios_sub_vip_plan_weekly", productName = "Sub Vip Plan Weekly", productPrice = 4.99, productType = 2,
    )
    private val iosRecallWeekly = GoodsModel(
        productId = AppConstants.recallWeeklyIos,
        productName = "Welcome Back Weekly VIP",
        productPrice = 4.99,
        productType = 2,
        productSubscriptionPeriod = 1,
        points = 888,
    )

    val creditProductList: List<GoodsModel> = listOf(
        iosAddCredit200,
        iosAddCredit800,
        iosAddCredit3200,
        iosAddCredit8000,
    )

    val productInfoMap: Map<String, GoodsModel> = linkedMapOf(
        AppConstants.recallWeeklyIos to iosRecallWeekly,
        "ios_super_pro_weekly" to iosSuperProWeekly,
        "ios_super_pro_weekly_discount_260422" to iosSuperProWeeklyDiscount260422,
        "ios_super_pro_weekly_260330" to iosSuperProWeekly260330,
        "ios_sub_vip_plan_weekly_250915" to iosSubVipPlanWeekly250915,
        "ios_sub_vip_plan_monthly_250915" to iosSubVipPlanMonthly250915,
        "ios_sub_vip_plan_yearly" to iosSubVipPlanYearly,
        "ios_sub_vip_plan_monthly" to iosSubVipPlanMonthly,
        "ios_sub_vip_plan_weekly" to iosSubVipPlanWeekly,
        "ios_add_credit_200" to iosAddCredit200,
        "ios_add_credit_800" to iosAddCredit800,
        "ios_add_credit_3200" to iosAddCredit3200,
        "ios_add_credit_8000" to iosAddCredit8000,
    )
}
