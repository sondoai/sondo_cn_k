package com.sondo.app.features.member.credits_record

import com.sondo.app.base.AppEnv
import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.constants.asset
import com.sondo.app.core.http.ApiResult
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.DevLog
import com.sondo.app.data.models.CreditRecordModel
import com.sondo.app.data.models.PageResult
import com.sondo.app.features.member.data.MemberApi
import com.sondo.app.service.UserStore
import com.sondo.app.service.pay.PayService
import com.sondo.app.ui.SDWeight
import com.sondo.app.ui.atoms.HGap
import com.sondo.app.ui.atoms.SDCircularIndicator
import com.sondo.app.ui.atoms.SDText
import com.sondo.app.ui.atoms.VGap
import com.sondo.app.ui.list.PagingState
import com.sondo.app.ui.list.SDRefreshList
import com.sondo.app.ui.nav.SDNavBar
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/** 筛选类型：全部 / 消耗 / 获得（Dart `RecordType`，值与 typeList 的 value 一致）。 */
private class RecordFilter(val name: String, val value: String)

/**
 * 积分记录页（Dart: features/member/presentation/pages/credits_record/credits_record_page.dart
 * + CreditsRecordController）。积分流水分页 + 全部 / 消耗 / 获得筛选。
 *
 * 参数：无。「更多」入口（去积分购买 / 订阅）受 `AppConstants.enableMemberPageEntry` 控制。
 */
@Page(Routes.creditsRecord)
internal class CreditsRecordPage : BasePager() {

    /** 当前积分余额（展示用，取自 userInfo） */
    private var currentCredits by observable(UserStore.userInfo?.point ?: 0)

    /** 当前选中的筛选类型值 */
    private var selectedFilter by observable("All")

    private val typeList = listOf(
        RecordFilter("all".tr, "All"),
        RecordFilter("spend".tr, "Spend"),
        RecordFilter("earn".tr, "Earn"),
    )

    private val paging = PagingState<CreditRecordModel>(this, pageSize = 10) { pageNo, pageSize -> loadData(pageNo, pageSize) }

    override fun created() {
        super.created()
        on(Events.USER_CHANGED) { currentCredits = UserStore.userInfo?.point ?: 0 }
        on(Events.POINTS_CHANGED) { currentCredits = UserStore.userInfo?.point ?: it.optInt("points", currentCredits) }
        if (AppEnv.isIOS) PayService.resumePurchase(showRestoreTips = false)
        paging.refresh()
        launch {
            UserStore.getUserInfo()
            currentCredits = UserStore.userInfo?.point ?: 0
        }
    }

    private suspend fun loadData(pageNo: Int, pageSize: Int): ApiResult<PageResult<CreditRecordModel>> {
        val addStatus = when (selectedFilter) {
            "Earn" -> true
            "Spend" -> false
            else -> null
        }
        return when (val res = MemberApi.getCreditRecords(pageNo = pageNo, pageSize = pageSize, addStatus = addStatus)) {
            is ApiResult.Success -> {
                DevLog.logger("$pageNo === $pageSize pageData.list.length: ${res.data.list.size}")
                res
            }
            // Dart 失败返回空列表（显示空态 / 没有更多），不展示错误态。
            is ApiResult.Failure -> ApiResult.Success(PageResult(emptyList(), 0))
        }
    }

    /** 切换筛选类型并重新加载列表 */
    fun selectFilterType(type: String) {
        if (paging.loading) return
        if (selectedFilter != type) {
            selectedFilter = type
            paging.clearData()
            paging.refresh()
        }
    }

    /** 跳转积分购买；未订阅用户先跳转订阅页 */
    fun goToCreditsPurchase() {
        // 主入口关闭时不进积分购买或订阅页。
        if (!AppConstants.enableMemberPageEntry) return
        if (UserStore.isSubscribed) {
            nav.push(Routes.creditsPurchase) { result ->
                DevLog.logger("result: $result")
                if (result != null) paging.refresh()
            }
        } else {
            nav.push(Routes.subscribe)
        }
    }

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            attr { backgroundColor(UIColor.pageBg) }
            SDNavBar {
                attr {
                    title = "credits_details".tr
                    titleSize = 18f
                    // Dart：TextWidget(size: 18, w500)，系统字体
                    titleWeight = SDWeight.W500
                    titleFontFamily = null
                }
            }
            View {
                attr {
                    flex(1f)
                    paddingBottom(ctx.safeBottom)
                }
                recordHeader(ctx)
                // 筛选按钮区域
                View {
                    attr {
                        flexDirectionRow()
                        padding(10f, 16f, 10f, 16f)
                    }
                    for (type in ctx.typeList) {
                        View {
                            attr {
                                marginRight(12f)
                                minWidth(70f)
                                paddingTop(10f)
                                paddingBottom(10f)
                                allCenter()
                                borderRadius(29f)
                                backgroundColor(if (ctx.selectedFilter == type.value) Color(0x33FFFFFFL) else Color(0x0DFFFFFFL))
                            }
                            event { click { ctx.selectFilterType(type.value) } }
                            SDText(type.name, size = 12f, height = 1.2f) { attr { fontWeightBold() } }
                        }
                    }
                }
                SDRefreshList {
                    attr {
                        flex(1f)
                        state = ctx.paging
                        contentPaddingH = 16f
                        placeholderFill = true
                        emptyBuilder = {
                            View {
                                attr {
                                    flex(1f)
                                    allCenter()
                                }
                                Image {
                                    attr {
                                        size(80f, 80f)
                                        src(AssetsImage.noIntergal.asset())
                                        resizeContain()
                                    }
                                }
                                VGap(24f)
                                SDText("no_records_found".tr, color = Color(0x8AFFFFFFL))
                            }
                        }
                        loadingBuilder = {
                            View {
                                attr {
                                    flex(1f)
                                    allCenter()
                                }
                                // Dart CircularProgressIndicator(color: white, backgroundColor: #7F58F7)
                                SDCircularIndicator(color = Color.WHITE, trackColor = Color(0xFF7F58F7L))
                            }
                        }
                    }
                    items {
                        vfor({ ctx.paging.list }) { record ->
                            View {
                                recordItem(record)
                            }
                        }
                    }
                }
            }
        }
    }

    internal val points: Int get() = currentCredits
}

/** 头部积分显示区域 */
private fun ViewContainer<*, *>.recordHeader(ctx: CreditsRecordPage) {
    View {
        attr {
            margin(10f, 16f, 10f, 16f)
            padding(12f, 20f, 12f, 20f)
            backgroundColor(Color(0x21CD8CFFL))
            borderRadius(12f)
        }
        SDText("my_credits".tr, size = 12f, color = Color(0xB3FFFFFFL))
        VGap(10f)
        View {
            attr {
                flexDirectionRow()
                justifyContentSpaceBetween()
                alignItemsCenter()
            }
            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                }
                Image {
                    attr {
                        size(24f, 24f)
                        src((if (ctx.points > 0) AssetsImage.credits else AssetsImage.unMember).asset())
                        resizeContain()
                    }
                }
                HGap(10f)
                Text {
                    attr {
                        text("${ctx.points}")
                        fontSize(22f)
                        fontWeight500()
                        color(Color.WHITE)
                        lines(1)
                    }
                }
            }
            // 主入口关闭时不展示「更多」，避免从积分详情进入积分购买或订阅页。
            if (AppConstants.enableMemberPageEntry) {
                View {
                    attr {
                        flexDirectionRow()
                        alignItemsCenter()
                        padding(4f, 10f, 4f, 10f)
                        borderRadius(30f)
                        backgroundLinearGradient(
                            Direction.TO_RIGHT,
                            ColorStop(Color(0xFFE661F6L), 0f),
                            ColorStop(Color(0xFF7F58F7L), 1f),
                        )
                    }
                    event { click { ctx.goToCreditsPurchase() } }
                    SDText("more".tr, size = 12f, height = 1.4f) { attr { fontWeightBold() } }
                    Image {
                        attr {
                            size(20f, 20f)
                            src(AssetsImage.creditsAdd.asset())
                            resizeContain()
                        }
                    }
                }
            }
        }
    }
}

/** 单个记录项：花费为白色，获得为紫色。 */
private fun ViewContainer<*, *>.recordItem(record: CreditRecordModel) {
    View {
        attr {
            marginBottom(10f)
            padding(14f, 14f, 14f, 14f)
            backgroundColor(Color(0x1AFFFFFFL))
            borderRadius(10f)
            flexDirectionRow()
            justifyContentSpaceBetween()
            alignItemsCenter()
        }
        View {
            attr { flex(1f) }
            SDText(record.title, size = 12f) { attr { fontWeight500() } }
            VGap(5f)
            SDText(record.formattedTimestamp, size = 10f, color = Color(0x66FFFFFFL))
        }
        SDText(record.formattedAmount, size = 14f, color = if (record.isSpending) Color.WHITE else Color(0xFFB983FFL)) {
            attr {
                fontWeightBold()
                marginLeft(8f)
            }
        }
    }
}
