package com.sondo.app.features.my

import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.GlobalData
import com.sondo.app.core.constants.asset
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.i18n.trParams
import com.sondo.app.core.utils.LoadingUtil
import com.sondo.app.data.models.RecommendModel
import com.sondo.app.features.my.data.FaqItem
import com.sondo.app.features.my.data.ReferralApi
import com.sondo.app.nativeapi.SDAppModule
import com.sondo.app.service.UserStore
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.ui.input.SDInput
import com.sondo.app.ui.input.SDKeyboardAwareScroll
import com.sondo.app.ui.input.SDInputView
import com.sondo.app.ui.input.hideKeyboard
import com.sondo.app.ui.list.PagingState
import com.sondo.app.ui.nav.SDNavBar
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 邀请奖励页（Dart: ReferralPage + ReferralController）：邀请链接、绑定上级邀请码、邀请明细（前 10 条）与 FAQ。无参数。
 */
@Page(Routes.referral)
internal class ReferralPage : BasePager() {
    var inviteLink by observable("")
    var myCode by observable("")

    /** 首页展示的邀请明细（取第一页前 10 条） */
    val inviteDetails by observableList<RecommendModel>()

    val recordsPaging = PagingState<RecommendModel>(this, pageSize = 20, itemKey = { it.id }, after = { list ->
        if (pageNoOfPaging() == 1) {
            inviteDetails.clear()
            inviteDetails.addAll(if (list.size <= 10) list.toList() else list.subList(0, 10).toList())
        }
    }) { pageNo, pageSize -> ReferralApi.getRecommendRecords(pageNo, pageSize) }

    private fun pageNoOfPaging(): Int = recordsPaging.pageNo

    val faqList = listOf(
        FaqItem("faq_referral_q1", "faq_referral_a1", mapOf("days" to "3", "bonusPoints" to "200", "percent" to "25")),
        FaqItem("faq_referral_q2", "faq_referral_a2"),
        FaqItem("faq_referral_q3", "faq_referral_a3"),
        FaqItem("faq_referral_q4", "faq_referral_a4", mapOf("days" to "3", "bonusPoints" to "200", "percent" to "25")),
        FaqItem("faq_referral_q5", "faq_referral_a5"),
    )

    var codeInputRef: ViewRef<SDInputView>? = null
    var codeText = ""

    override fun created() {
        super.created()
        recordsPaging.refresh()
        initData()
        on(Events.USER_CHANGED) { initData() }
    }

    private fun initData() {
        myCode = UserStore.userInfo?.regCode ?: ""
        inviteLink = GlobalData.referralShare(myCode)
    }

    private fun copy(text: String) {
        try {
            acquireModule<SDAppModule>(SDAppModule.MODULE_NAME).copyToClipboard(text)
        } catch (e: Throwable) {
        }
        toast("copied".tr)
        UmengUtils.logEventButtonClick("invite_copy")
    }

    fun onCopyLink() = copy(inviteLink)

    fun onCopyCode() = copy(myCode)

    /** 提交邀请码并绑定上级关系。 */
    fun onConfirmCode(rawCode: String) {
        val code = rawCode.trim()
        if (code.isEmpty()) {
            toast("please_enter_referral_code".tr)
            return
        }
        try {
            acquireModule<SDAppModule>(SDAppModule.MODULE_NAME).hideKeyboard()
        } catch (e: Throwable) {
        }
        launch {
            LoadingUtil.show(pager = this@ReferralPage)
            val ok = UserStore.bindSuperRegCode(code)
            LoadingUtil.close()
            toast(if (ok) "code_submitted".tr else "code_submission_failed".tr)
            UmengUtils.logEventButtonClick("invite_code_submit")
        }
    }

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            attr {
                flex(1f)
                backgroundColor(Color(0xFF000002L))
            }
            val root = this
            event { click { root.hideKeyboard() } }
            // 导航栏（Dart NavAppBarWidget(assetBgImage)）：背景图等宽、顶对齐，内容在它下方滚动
            View {
                attr { overflow(true) }
                Image {
                    attr {
                        absolutePosition(top = 0f, left = 0f)
                        size(ctx.screenWidth, ctx.screenWidth * 2560f / 1440f)
                        src(AssetsImage.referralBg.asset())
                        resizeCover()
                    }
                }
                SDNavBar { attr { title = "" } }
            }
            // 键盘弹出时把邀请码输入框滚到键盘上方（Dart Scaffold resizeToAvoidBottomInset）
            SDKeyboardAwareScroll {
                attr { flex(1f) }
                content {
                View {
                    // 背景图接着导航栏的背景往下画（Dart top = -(kToolbarHeight + 状态栏)）
                    Image {
                        attr {
                            absolutePosition(top = -(ctx.statusBarHeight + 56f), left = 0f)
                            size(ctx.screenWidth, ctx.screenWidth * 2560f / 1440f)
                            src(AssetsImage.referralBg.asset())
                            resizeCover()
                        }
                    }
                    referralHeader(ctx)
                    View { attr { height(4f) } }
                    referralInviteLink(ctx)
                    referralCode(ctx)
                    View { attr { height(12f) } }
                    referralDetails(ctx)
                    View { attr { height(12f) } }
                    referralFaq(ctx)
                    View { attr { height(32f + ctx.safeBottom) } }
                }
                }
            }
        }
    }
}

/** 白 → 粉的竖向渐变字（Dart ShaderMask，Carter 字体）。 */
internal fun ViewContainer<*, *>.referralShaderText(title: String, size: Float) {
    Text {
        attr {
            text(title)
            fontSize(size)
            fontFamily("Carter")
            textAlignCenter()
            backgroundLinearGradient(
                Direction.TO_BOTTOM,
                ColorStop(Color.WHITE, 0f),
                ColorStop(Color(0xFFE661F6L), 1f),
            )
        }
    }
}

private fun ViewContainer<*, *>.referralHeader(ctx: ReferralPage) {
    View {
        attr {
            paddingLeft(16f)
            paddingRight(16f)
            alignItemsCenter()
        }
        // 标题是斗鱼渐变字，工程没有该字体，用设计稿 4x 透明图。
        Image {
            attr {
                size(250f, 60f)
                src(AssetsImage.referralTitle.asset())
                resizeContain()
            }
        }
        Text {
            attr {
                marginTop(4f)
                text("referral_subtitle".tr)
                fontSize(16f)
                lineHeight(16f * 1.4f)
                lines(2)
                fontFamily("Poppins-Medium")
                textAlignCenter()
                color(Color.WHITE)
            }
        }
        Image {
            attr {
                marginTop(10f)
                size(250f, 250f)
                src(AssetsImage.referralCover.asset())
                resizeContain()
            }
        }
        View { attr { height(16f) } }
    }
}

/** 带背景图的卡片 + 右上浮出的图标（Dart `_card`）。 */
private fun ViewContainer<*, *>.referralCard(
    ctx: ReferralPage,
    image: String,
    icon: String,
    child: ViewContainer<*, *>.() -> Unit,
) {
    View {
        View {
            attr {
                margin(16f, 16f, 16f, 16f)
                padding(16f)
                borderRadius(16f)
            }
            Image {
                attr {
                    absolutePosition(top = 0f, left = 0f, right = 0f, bottom = 0f)
                    src(image.asset())
                    resizeCover()
                }
            }
            child()
        }
        Image {
            attr {
                absolutePosition(top = 8f, right = (ctx.screenWidth / 2.5f) / 2f - 35f)
                size(70f, 70f)
                src(icon.asset())
            }
        }
    }
}

private fun ViewContainer<*, *>.referralCardTitle(title: String, subtitle: String) {
    Text {
        attr {
            text(title)
            fontSize(16f)
            fontFamily("Poppins-SemiBold")
            color(Color.WHITE)
        }
    }
    Text {
        attr {
            marginTop(4f)
            text(subtitle)
            fontSize(12f)
            fontFamily("Poppins")
            color(Color(0x80FFFFFFL))
            lines(1)
        }
    }
}

private fun ViewContainer<*, *>.referralInviteLink(ctx: ReferralPage) {
    referralCard(ctx, AssetsImage.subtract, AssetsImage.referralLink) {
        referralCardTitle("invitation_link".tr, "share_link_with_friends".tr)
        View {
            attr {
                marginTop(12f)
                height(50f)
                padding(10f, 12f, 10f, 12f)
                backgroundColor(Color(0x1AFFFFFFL))
                borderRadius(10f)
                flexDirectionRow()
                alignItemsCenter()
            }
            Text {
                attr {
                    flex(1f)
                    text(ctx.inviteLink)
                    fontSize(13f)
                    fontFamily("Poppins")
                    color(Color(0xCCFFFFFFL))
                    lines(1)
                    textOverFlowClip()
                }
            }
            View { attr { width(8f) } }
            Image {
                attr {
                    size(18f, 18f)
                    src(AssetsImage.referralCopy.asset())
                }
                event { click { ctx.onCopyLink() } }
            }
        }
    }
}

private fun ViewContainer<*, *>.referralCode(ctx: ReferralPage) {
    referralCard(ctx, AssetsImage.subtract2, AssetsImage.referralKey) {
        referralCardTitle("referral_code".tr, "bind_activation_code".tr)
        View {
            attr {
                marginTop(14f)
                padding(10f, 12f, 10f, 12f)
                backgroundColor(Color(0x1AFFFFFFL))
                borderRadius(10f)
                flexDirectionRow()
                alignItemsCenter()
                justifyContentSpaceBetween()
            }
            View {
                Text {
                    attr {
                        text("my_code".tr)
                        fontSize(12f)
                        fontFamily("Poppins")
                        color(Color(0x80FFFFFFL))
                    }
                }
                Text {
                    attr {
                        text(ctx.myCode)
                        fontSize(14f)
                        fontFamily("Poppins-Medium")
                        color(Color(0xFFB55AFFL))
                    }
                }
            }
            View {
                attr { paddingLeft(20f) }
                event { click { ctx.onCopyCode() } }
                Image {
                    attr {
                        size(18f, 18f)
                        src(AssetsImage.referralCopy.asset())
                    }
                }
            }
        }
        // 邀请码输入 + 确认
        View {
            attr {
                marginTop(10f)
                flexDirectionRow()
                alignItemsCenter()
            }
            View {
                attr {
                    flex(1f)
                    height(50f)
                    paddingLeft(12f)
                    paddingRight(12f)
                    backgroundColor(Color(0x1AFFFFFFL))
                    borderRadius(10f)
                    justifyContentCenter()
                }
                SDInput {
                    ref { ctx.codeInputRef = it }
                    attr {
                        height(48f)
                        contentPaddingH = 0f
                        fontSize = 13f
                        color = Color.WHITE
                        hintColor = Color(0x4DFFFFFFL)
                        cursorColor = Color.WHITE
                        placeholder = "enter_referral_code".tr
                    }
                    event { onChanged { ctx.codeText = it } }
                }
            }
            View { attr { width(10f) } }
            View {
                attr {
                    height(50f)
                    padding(0f, 18f, 0f, 18f)
                    borderRadius(10f)
                    allCenter()
                    backgroundLinearGradient(
                        Direction.TO_RIGHT,
                        ColorStop(Color(0xFFE661F6L), 0f),
                        ColorStop(Color(0xFF7F58F7L), 1f),
                    )
                }
                event { click { ctx.onConfirmCode(ctx.codeText) } }
                Text {
                    attr {
                        text("confirm".tr)
                        fontSize(13f)
                        fontFamily("Poppins-SemiBold")
                        color(Color.WHITE)
                    }
                }
            }
        }
    }
}

/** 邀请明细表头（Dart `_tableHeader`）。 */
internal fun ViewContainer<*, *>.referralTableHeader(fontSize: Float) {
    View {
        attr {
            flexDirectionRow()
            alignItemsCenter()
        }
        Text {
            attr {
                flex(1f)
                text("user".tr)
                fontSize(fontSize)
                fontFamily("Poppins-Medium")
                color(Color.WHITE)
            }
        }
        Text {
            attr {
                flex(1f)
                text("date".tr)
                fontSize(fontSize)
                fontFamily("Poppins-Medium")
                color(Color.WHITE)
                textAlignCenter()
            }
        }
        Text {
            attr {
                width(70f)
                text("earnings".tr)
                fontSize(fontSize)
                fontFamily("Poppins-Medium")
                color(Color.WHITE)
                textAlignRight()
            }
        }
    }
}

/** 邀请明细行（Dart `_tableRow`）：昵称 / 日期 / 积分（无积分显示 -）。 */
internal fun ViewContainer<*, *>.referralTableRow(item: RecommendModel, nameLines: Int) {
    View {
        attr {
            flexDirectionRow()
            alignItemsCenter()
        }
        Text {
            attr {
                flex(1f)
                text(item.userNickName)
                fontSize(12f)
                fontFamily("Poppins")
                color(Color(0xCCFFFFFFL))
                lines(nameLines)
                textOverFlowTail()
            }
        }
        View { attr { width(8f) } }
        Text {
            attr {
                flex(1f)
                text(item.createTimeStr)
                fontSize(12f)
                fontFamily("Poppins")
                color(Color(0xCCFFFFFFL))
                textAlignCenter()
                lines(1)
            }
        }
        View { attr { width(8f) } }
        Text {
            attr {
                width(70f)
                text(if (item.point > 0) "${item.point}" else "-")
                fontSize(12f)
                fontFamily("Poppins-SemiBold")
                color(if (item.point > 0) Color(0xFFB55AFFL) else Color(0xCCFFFFFFL))
                textAlignRight()
                lines(1)
            }
        }
    }
}

private fun ViewContainer<*, *>.referralDetails(ctx: ReferralPage) {
    View {
        attr { alignItemsCenter() }
        Image {
            attr {
                size(30f, 30f)
                src(AssetsImage.referralStar.asset())
            }
        }
        referralShaderText("invitation_details".tr, 18f)
        View {
            attr {
                alignSelfStretch()
                margin(8f, 16f, 8f, 16f)
                backgroundColor(Color(0xB32F1F4BL))
                borderRadius(16f)
            }
            View {
                attr {
                    height(50f)
                    paddingLeft(16f)
                    paddingRight(16f)
                    justifyContentCenter()
                    backgroundColor(Color(0xE62F1F4BL))
                    borderRadius(16f, 16f, 0f, 0f)
                }
                referralTableHeader(12f)
            }
            View { attr { height(8f) } }
            View {
                attr {
                    paddingLeft(16f)
                    paddingRight(16f)
                }
                vif({ ctx.recordsPaging.list.isEmpty() && !ctx.recordsPaging.loading }) {
                    View {
                        attr {
                            padding(30f)
                            allCenter()
                        }
                        Text {
                            attr {
                                text("no_data".tr)
                                fontSize(12f)
                                color(Color(0x8AFFFFFFL))
                            }
                        }
                    }
                }
                vfor({ ctx.inviteDetails }) { item ->
                    View {
                        attr { padding(10f, 0f, 10f, 0f) }
                        referralTableRow(item, 1)
                    }
                }
            }
            View { attr { height(8f) } }
        }
        vif({ ctx.recordsPaging.list.isNotEmpty() }) {
            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                    justifyContentCenter()
                }
                event { click { ctx.nav.push(Routes.referralRecord) } }
                Text {
                    attr {
                        text("see_all".tr)
                        fontSize(12f)
                        fontFamily("Poppins-Medium")
                        color(Color.WHITE)
                    }
                }
                Image {
                    attr {
                        size(12f, 12f)
                        src(AssetsImage.arrowRight.asset())
                        tintColor(Color.WHITE)
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.referralFaq(ctx: ReferralPage) {
    View {
        attr { alignItemsCenter() }
        Image {
            attr {
                size(30f, 30f)
                src(AssetsImage.referralStar.asset())
            }
        }
        referralShaderText("common_questions".tr, 18f)
        View { attr { height(10f) } }
        for (faq in ctx.faqList) {
            View {
                attr {
                    alignSelfStretch()
                    margin(8f, 16f, 8f, 16f)
                    padding(16f)
                    backgroundColor(Color(0xE62F1F4BL))
                    borderRadius(16f)
                }
                Text {
                    attr {
                        text(faq.question.tr)
                        fontSize(14f)
                        fontFamily("Poppins-SemiBold")
                        color(Color.WHITE)
                        lines(10)
                    }
                }
                Text {
                    attr {
                        marginTop(10f)
                        text(faq.answer.trParams(faq.params))
                        fontSize(12f)
                        fontFamily("Poppins")
                        lineHeight(12f * 1.6f)
                        color(Color(0xCCFFFFFFL))
                        lines(30)
                    }
                }
            }
        }
    }
}
