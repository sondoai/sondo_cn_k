package com.sondo.app.features.my

import com.sondo.app.base.AppEnv
import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.asset
import com.sondo.app.core.i18n.tr
import com.sondo.app.data.models.ModelDate
import com.sondo.app.data.models.UserModel
import com.sondo.app.features.my.data.MineItem
import com.sondo.app.service.UserStore
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.dialog.showInputDialog
import com.sondo.app.ui.input.hideKeyboard
import com.sondo.app.ui.works.showDialogEditUser
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 个人中心页（Dart: MinePage + MineController）：用户信息、订阅状态与功能入口。无参数。
 */
@Page(Routes.mine)
internal class MinePage : BasePager() {
    var user by observable<UserModel?>(UserStore.userInfo)
    var isSubscribed by observable(UserStore.isSubscribed)

    override fun created() {
        super.created()
        on(Events.USER_CHANGED) {
            user = UserStore.userInfo
            isSubscribed = UserStore.isSubscribed
        }
    }

    private var readyDone = false

    override fun pageDidAppear() {
        super.pageDidAppear()
        if (readyDone) return
        readyDone = true
        launch { UserStore.getUserInfo() }
    }

    /** 处理菜单项点击：用户操作委托给 UserStore，子页面走路由跳转 */
    fun onTap(item: MineItem) {
        val ctx = this
        when (item) {
            MineItem.updateUser -> updateUserInfo()
            MineItem.copyId -> UserStore.copyId()
            MineItem.feedback, MineItem.sendEmail -> launch { UserStore.sendEmail() }
            MineItem.contactUs -> UserStore.openContactUs(ctx)
            MineItem.activationCode -> openActivationCode()
            MineItem.referral -> nav.push(Routes.referral)
            MineItem.trash -> nav.push(Routes.trash)
            MineItem.settings -> nav.push(Routes.setting)
        }
    }

    /** 编辑资料弹层：返回 {title, image?}；头像没换时沿用当前头像。 */
    private fun updateUserInfo() {
        val ctx = this
        launch {
            val info = UserStore.userInfo
            val result = ctx.showDialogEditUser(info?.nickname ?: "", info?.avatar ?: "") ?: return@launch
            val name = result.optString("title", "")
            val image = result.optString("image", "").takeIf { it != "null" } ?: ""
            UserStore.updateUserInfo(name, image)
        }
    }

    /** 激活码输入弹窗：成功后关闭弹窗、提示并刷新资料。 */
    private fun openActivationCode() {
        val ctx = this
        showInputDialog(
            title = "CDkey".tr,
            hintText = "please_enter_the_CDkey".tr,
            confirmText = "confirm".tr,
            cancelText = "cancel".tr,
        ) { text, handle ->
            ctx.hideKeyboardSafe()
            if (text.isNotEmpty()) {
                ctx.launch { if (UserStore.redeemCode(text)) handle.dismiss() }
            } else {
                ctx.toast("please_enter_the_CDkey".tr)
            }
        }
    }

    private fun hideKeyboardSafe() {
        try {
            acquireModule<com.sondo.app.nativeapi.SDAppModule>(com.sondo.app.nativeapi.SDAppModule.MODULE_NAME).hideKeyboard()
        } catch (e: Throwable) {
        }
    }

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            attr { flex(1f) }
            View { attr { height(ctx.statusBarHeight) } }
            // AppBar：左侧关闭
            View {
                attr {
                    height(56f)
                    flexDirectionRow()
                    alignItemsCenter()
                }
                View {
                    attr {
                        // AppBar leading 占 56×56，图标居中
                        size(56f, 56f)
                        allCenter()
                    }
                    event { click { ctx.nav.pop() } }
                    Image {
                        attr {
                            size(24f, 24f)
                            src(AssetsImage.close.asset())
                        }
                    }
                }
            }
            mineUserInfo(ctx)
            View { attr { height(5f) } }
            mineSubscribe(ctx)
            View { attr { height(20f) } }
            Scroller {
                attr {
                    flex(1f)
                    showScrollerIndicator(false)
                    bouncesEnable(false)
                }
                mineItem(ctx, MineItem.feedback, "feedback".tr, AssetsImage.mail)
                mineItem(ctx, MineItem.referral, "referral_rewards".tr, AssetsImage.referralPresent)
                if (AppEnv.isAndroid) {
                    mineItem(ctx, MineItem.activationCode, "activation_code".tr, AssetsImage.key)
                }
                // 国内版暂时不展示激活码、社交媒体；改为 true 即恢复，跳转逻辑未删
                if (SHOW_ACTIVATION_AND_SOCIAL) {
                    mineItem(ctx, MineItem.contactUs, "social_media".tr, AssetsImage.socialMedia)
                }
                mineItem(ctx, MineItem.trash, "trash".tr, AssetsImage.trash)
                mineItem(ctx, MineItem.settings, "settings".tr, AssetsImage.settings)
                View { attr { height(12f) } }
                View {
                    attr {
                        paddingLeft(16f)
                        paddingRight(16f)
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    Image {
                        attr {
                            size(22f, 22f)
                            src(AssetsImage.version.asset())
                        }
                    }
                    View { attr { width(15f) } }
                    Text {
                        attr {
                            text("version".tr)
                            fontSize(14f)
                            color(Color.WHITE)
                        }
                    }
                    View { attr { flex(1f) } }
                    Text {
                        attr {
                            text("V${UserStore.currentVersion}")
                            fontSize(14f)
                            color(Color(0x8AFFFFFFL))
                        }
                    }
                    View { attr { width(8f) } }
                }
                View { attr { height(20f) } }
            }
            View {
                attr {
                    flexDirectionRow()
                    justifyContentCenter()
                    alignItemsCenter()
                }
                mineFooterLink("terms_of_use".tr) { UserStore.openTerms(ctx) }
                Text {
                    attr {
                        text("|")
                        fontSize(14f)
                        color(Color(0xFFB3B3B3L))
                    }
                }
                mineFooterLink("privacy_policy".tr) { UserStore.openPrivacyPolicy(ctx) }
            }
            View { attr { height(10f + ctx.safeBottom) } }
        }
    }

    companion object {
        /** 国内版暂时不展示激活码、社交媒体（Dart `_showActivationAndSocial`）。 */
        const val SHOW_ACTIVATION_AND_SOCIAL = false
    }
}

private fun ViewContainer<*, *>.mineUserInfo(ctx: MinePage) {
    View {
        attr {
            padding(0f, 16f, 16f, 16f)
            flexDirectionRow()
            alignItemsCenter()
        }
        SDImage {
            attr {
                size(80f, 80f)
                borderRadius(40f)
                url = ctx.user?.avatar ?: ""
            }
            event { click { ctx.onTap(MineItem.updateUser) } }
        }
        View { attr { width(16f) } }
        View {
            attr {
                flex(1f)
                justifyContentSpaceBetween()
            }
            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                }
                event { click { ctx.onTap(MineItem.updateUser) } }
                Text {
                    attr {
                        maxWidth(ctx.screenWidth - 32f - 16f - 80f - 10f - 36f)
                        text(ctx.user?.nickname ?: "")
                        fontSize(18f)
                        fontWeightBold()
                        color(Color.WHITE)
                        lines(1)
                        textOverFlowTail()
                    }
                }
                View {
                    attr {
                        paddingLeft(8f)
                        paddingRight(8f)
                    }
                    Image {
                        attr {
                            size(18f, 18f)
                            src(AssetsImage.edit.asset())
                        }
                    }
                }
            }
            View { attr { height(6f) } }
            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                }
                event { click { ctx.onTap(MineItem.copyId) } }
                Text {
                    attr {
                        text("ID: ${ctx.user?.id}")
                        fontSize(12f)
                        lineHeight(12f)
                        color(Color(0x8AFFFFFFL))
                        lines(1)
                        textOverFlowTail()
                    }
                }
                View { attr { width(4f) } }
                Image {
                    attr {
                        size(14f, 14f)
                        src(AssetsImage.copy.asset())
                    }
                }
            }
            View { attr { height(6f) } }
            View {
                attr { flexDirectionRow() }
                View {
                    attr {
                        padding(4f, 8f, 4f, 8f)
                        backgroundColor(Color(0x33FFFFFFL))
                        borderRadius(30f)
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    event { click { ctx.nav.push(Routes.creditsRecord) } }
                    Image {
                        attr {
                            size(16f, 16f)
                            val point = ctx.user?.point ?: 0
                            src((if (point > 0) AssetsImage.credits else AssetsImage.unMember).asset())
                        }
                    }
                    View { attr { width(6f) } }
                    Text {
                        attr {
                            text("${ctx.user?.point ?: 0}")
                            fontSize(12f)
                            lineHeight(12f * 1.2f)
                            color(Color(0xCCFFFFFFL))
                        }
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.mineSubscribe(ctx: MinePage) {
    View {
        attr {
            marginLeft(16f)
            marginRight(16f)
            borderRadius(12f)
            backgroundLinearGradient(
                Direction.TO_BOTTOM_RIGHT,
                ColorStop(Color(0x0026578BL), 0f),
                ColorStop(Color(0xFF641F5DL), 1f),
            )
            flexDirectionRow()
            alignItemsCenter()
            justifyContentSpaceBetween()
        }
        event {
            click {
                if (ctx.isSubscribed) return@click
                ctx.nav.push(Routes.subscribe)
            }
        }
        View {
            attr {
                flexDirectionRow()
                alignItemsCenter()
            }
            Image {
                attr {
                    size(50f, 50f)
                    src((if (ctx.isSubscribed) AssetsImage.subscribe else AssetsImage.unsubscribe).asset())
                }
            }
            View { attr { width(10f) } }
            Text {
                attr {
                    val expire = ctx.user?.expireDate
                    if (ctx.isSubscribed && expire != null) {
                        val p = ModelDate.localParts(expire)
                        text("${"validity".tr}：${p.year}年${ModelDate.pad2(p.month)}月${ModelDate.pad2(p.day)}日")
                    } else {
                        text("pro_member".tr)
                    }
                    fontSize(14f)
                    fontWeightBold()
                    color(Color.WHITE)
                }
            }
        }
        vif({ !ctx.isSubscribed }) {
            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                }
                Text {
                    attr {
                        text("subscribe".tr)
                        fontSize(12f)
                        color(Color.WHITE)
                    }
                }
                View { attr { width(8f) } }
                View {
                    attr { padding(8f) }
                    Image {
                        attr {
                            size(24f, 24f)
                            src(AssetsImage.arrowRight.asset())
                            tintColor(Color.WHITE)
                        }
                    }
                }
            }
        }
    }
}

/** 菜单行：左图标 + 标题，右箭头。 */
internal fun ViewContainer<*, *>.mineItem(ctx: MinePage, item: MineItem, title: String, icon: String) {
    View {
        attr {
            marginBottom(8f)
            padding(12f, 16f, 12f, 16f)
            flexDirectionRow()
            alignItemsCenter()
            justifyContentSpaceBetween()
        }
        event { click { ctx.onTap(item) } }
        View {
            attr {
                flexDirectionRow()
                alignItemsCenter()
            }
            Image {
                attr {
                    size(22f, 22f)
                    src(icon.asset())
                }
            }
            View { attr { width(15f) } }
            Text {
                attr {
                    text(title)
                    fontSize(14f)
                    color(Color.WHITE)
                }
            }
        }
        View {
            attr {
                paddingLeft(8f)
                paddingRight(8f)
            }
            Image {
                attr {
                    size(24f, 24f)
                    src(AssetsImage.arrowRight.asset())
                    tintColor(Color(0x8AFFFFFFL))
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.mineFooterLink(title: String, onTap: () -> Unit) {
    View {
        attr {
            padding(12f, 8f, 12f, 8f)
        }
        event { click { onTap() } }
        Text {
            attr {
                text(title)
                fontSize(12f)
                color(Color(0xFFB3B3B3L))
                textDecorationUnderLine()
            }
        }
    }
}
