package com.sondo.app.features.my

import com.sondo.app.base.AppEnv
import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.constants.asset
import com.sondo.app.core.http.AuthSession
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.storage.Sp
import com.sondo.app.features.my.data.SettingItem
import com.sondo.app.service.UserStore
import com.sondo.app.service.pay.PayService
import com.sondo.app.ui.atoms.SDSwitch
import com.sondo.app.ui.dialog.confirm
import com.sondo.app.ui.dialog.showAiWatermarkStatement
import com.sondo.app.ui.dialog.showConfirmDialog
import com.sondo.app.ui.dialog.showInputDialog
import com.sondo.app.ui.nav.SDNavBar
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.event.layoutFrameDidChange
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.ScrollerView
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 设置页（Dart: SettingPage + SettingController）：条款、隐私、撤回 AI 数据同意、退订、AI 生成水印、注销、登出，
 * 测试包另有「修改 Token / API URL」。
 *
 * 参数：`focusAiWatermark: Boolean`（可选，Dart `SettingRouteArgs`）——从视频标识「去设置」进入时把「AI 生成水印」滚到可见处。
 */
@Page(Routes.setting)
internal class SettingPage : BasePager() {
    /** 下载视频是否烧「AI生成」。默认开，只有后台保存成功才改为关。 */
    var aiWatermarkEnabled by observable(true)

    private var isSavingAiWatermark = false

    var scrollerRef: ViewRef<ScrollerView<*, *>>? = null
    var aiWatermarkY = -1f
    var viewportHeight = 0f
    private var focused = false

    private val focusAiWatermark: Boolean
        get() = pagerData.params.optBoolean("focusAiWatermark", false)

    override fun created() {
        super.created()
        // 设置页不另拉用户资料。没有资料时保持开启，避免把未知状态显示成已关闭。
        aiWatermarkEnabled = UserStore.userInfo?.aigcWatermarkEnabled ?: true
    }

    /** 布局拿到后把水印行滚到视口 20% 处（Scrollable.ensureVisible(alignment: 0.2)）。 */
    fun tryFocusAiWatermark() {
        if (focused || !focusAiWatermark || aiWatermarkY < 0f || viewportHeight <= 0f) return
        focused = true
        val target = (aiWatermarkY - viewportHeight * 0.2f).coerceAtLeast(0f)
        postDelayed(16) { scrollerRef?.view?.setContentOffset(0f, target, true) }
    }

    /** 关闭下载水印：非会员先订阅，每次关闭都要重新声明，保存成功才改开关。 */
    fun onAiWatermarkChanged(enabled: Boolean) {
        if (isSavingAiWatermark || enabled == aiWatermarkEnabled) return
        isSavingAiWatermark = true
        val ctx = this
        launch {
            try {
                if (enabled) {
                    if (UserStore.saveAigcWatermark(true)) {
                        ctx.aiWatermarkEnabled = true
                        return@launch
                    }
                    ctx.toast("aigc_save_failed".tr)
                    return@launch
                }
                var loggedIn = UserStore.isLogin
                if (!loggedIn) loggedIn = UserStore.showLoginDialog(ctx)
                if (!loggedIn) return@launch
                if (!UserStore.isSubscribed) {
                    suspendCoroutine<JSONObject?> { cont -> ctx.nav.push(Routes.subscribe) { cont.resume(it) } }
                    if (!UserStore.isSubscribed) return@launch
                }
                // 曾经同意过也不能跳过，每次从开到关都要重新看声明。
                if (!ctx.showAiWatermarkStatement()) return@launch
                if (UserStore.saveAigcWatermark(false)) {
                    ctx.aiWatermarkEnabled = false
                    return@launch
                }
                ctx.toast("aigc_save_failed".tr)
            } finally {
                ctx.isSavingAiWatermark = false
            }
        }
    }

    /** 处理设置项点击，弹出确认框或跳转外部页面 */
    fun onTap(item: SettingItem) {
        val ctx = this
        when (item) {
            SettingItem.logout -> showConfirmDialog("are_you_sure_you_want_to_log_out".tr) {
                ctx.launch { UserStore.logout() }
            }
            SettingItem.deleteAccount -> UserStore.delAccount(ctx)
            SettingItem.unsubscribe -> launch {
                // iOS 打开订阅管理；Android / 鸿蒙 查询并确认取消支付宝自动续费。
                PayService.openUnSubscribe { ctx.confirm("alipay_cancel_confirm".tr) }
            }
            SettingItem.termsOfUse -> UserStore.openTerms(ctx)
            SettingItem.privacyPolicy -> UserStore.openPrivacyPolicy(ctx)
            SettingItem.withdrawAiDataConsent -> onWithdrawAiDataConsent()
        }
    }

    /** 撤回本机上传同意。未同意只提示；确认后清标记，下次创作再弹声明。 */
    private fun onWithdrawAiDataConsent() {
        // 只看上传同意。发布确认是另一份标记，这里不读也不清。
        if (!Sp.getBool(StorageKey.isAgreeUpload, false)) {
            toast("withdraw_ai_consent_none".tr)
            return
        }
        val ctx = this
        showConfirmDialog(
            title = "withdraw_ai_consent_title".tr,
            content = "withdraw_ai_consent_body".tr,
            cancelText = "cancel".tr,
            confirmText = "withdraw_ai_consent_action".tr,
        ) {
            Sp.remove(StorageKey.isAgreeUpload)
            ctx.toast("withdraw_ai_consent_done".tr)
        }
    }

    /** 测试入口：覆盖本地 accessToken，便于切换账号调试。 */
    fun onModifyToken() {
        // 正式包无 TOKEN 开关，禁止改本地会话。
        if (!AppEnv.showTokenEditor) return
        val ctx = this
        showDebugInputDialog("修改 Token", Sp.getString(StorageKey.token)) { token ->
            ctx.launch {
                // 测试换号同样递增会话代次，旧请求和旧刷新不能覆盖新凭证。
                UserStore.logout(isAuto = true)
                AuthSession.replace(token.trim(), "")
                // 等弹窗退场后再拉用户信息
                ctx.postDelayed(300) { ctx.launch { UserStore.getUserInfo() } }
            }
        }
    }

    /** 测试入口：覆盖主接口地址；清空输入则回落编译期环境。 */
    fun onModifyApiUrl() {
        if (!AppEnv.showTokenEditor) return
        showDebugInputDialog("修改 API URL", AppConstants.apiBaseUrl) { url ->
            if (url.isEmpty()) {
                Sp.remove(StorageKey.debugApiBaseUrl)
            } else {
                Sp.setString(StorageKey.debugApiBaseUrl, url)
            }
            // Api 每次请求都读 AppConstants.apiBaseUrl，无需另行更新客户端
            AppConstants.applyDebugApiBaseUrl(url)
        }
    }

    /** 确定回调 trim 后的输入；取消或点遮罩关闭不回调。 */
    private fun showDebugInputDialog(title: String, initialText: String, onOk: (String) -> Unit) {
        showInputDialog(
            title = title,
            initialText = initialText,
            cancelText = "cancel".tr,
            confirmText = "ok".tr,
        ) { text, handle ->
            handle.dismiss()
            onOk(text.trim())
        }
    }

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            attr { flex(1f) }
            SDNavBar {
                attr { title = "settings".tr }
            }
            Scroller {
                ref { ctx.scrollerRef = it }
                attr {
                    flex(1f)
                    showScrollerIndicator(false)
                    bouncesEnable(false)
                    paddingBottom(ctx.safeBottom)
                }
                event {
                    layoutFrameDidChange {
                        ctx.viewportHeight = it.height
                        ctx.tryFocusAiWatermark()
                    }
                }
                settingItem("terms_of_use".tr, AssetsImage.termsOfUse) { ctx.onTap(SettingItem.termsOfUse) }
                settingItem("privacy_policy".tr, AssetsImage.privacyPolicy) { ctx.onTap(SettingItem.privacyPolicy) }
                settingItem("withdraw_ai_data_consent".tr, AssetsImage.withdrawAiConsent) {
                    ctx.onTap(SettingItem.withdrawAiDataConsent)
                }
                settingItem("unsubscribe".tr, AssetsImage.unsubscribe1) { ctx.onTap(SettingItem.unsubscribe) }
                View {
                    attr {
                        marginBottom(8f)
                        padding(8f, 16f, 8f, 16f)
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    event {
                        layoutFrameDidChange {
                            ctx.aiWatermarkY = it.y
                            ctx.tryFocusAiWatermark()
                        }
                    }
                    Image {
                        attr {
                            size(22f, 22f)
                            src(AssetsImage.aiWatermark.asset())
                        }
                    }
                    View { attr { width(15f) } }
                    Text {
                        attr {
                            flex(1f)
                            text("ai_watermark_title".tr)
                            fontSize(14f)
                            color(Color.WHITE)
                            lines(1)
                        }
                    }
                    SDSwitch {
                        attr { value = ctx.aiWatermarkEnabled }
                        event { onChanged { ctx.onAiWatermarkChanged(it) } }
                    }
                }
                settingItem("delete_account_1".tr, AssetsImage.deleteAccount) { ctx.onTap(SettingItem.deleteAccount) }
                settingItem("log_out".tr, AssetsImage.logout) { ctx.onTap(SettingItem.logout) }
                // 仅测试包（showTokenEditor）展示，正式包不出现。
                if (AppEnv.showTokenEditor) {
                    settingItem("修改 Token", AssetsImage.termsOfUse) { ctx.onModifyToken() }
                    settingItem("修改 API URL", AssetsImage.termsOfUse) { ctx.onModifyApiUrl() }
                }
            }
        }
    }
}

/** 设置行：左图标 + 标题（最多两行），右箭头。 */
private fun ViewContainer<*, *>.settingItem(title: String, icon: String, onTap: () -> Unit) {
    View {
        attr {
            marginBottom(8f)
            padding(12f, 16f, 12f, 16f)
            flexDirectionRow()
            alignItemsCenter()
        }
        event { click { onTap() } }
        Image {
            attr {
                size(22f, 22f)
                src(icon.asset())
            }
        }
        View { attr { width(15f) } }
        Text {
            attr {
                flex(1f)
                // 部分语言文案很长，最多两行，避免把右侧箭头挤出屏幕
                text(title)
                fontSize(14f)
                color(Color.WHITE)
                lines(2)
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
                    size(24f, 24f)
                    src(AssetsImage.arrowRight.asset())
                    tintColor(Color(0x8AFFFFFFL))
                }
            }
        }
    }
}
