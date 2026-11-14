package com.sondo.app.ui.consent

import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.i18n.tr
import com.sondo.app.ui.SDWeight
import com.sondo.app.ui.atoms.SDLinkText
import com.sondo.app.ui.atoms.splitLinkSpans
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.Scale
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.ActivityIndicator
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

class SDPrivacyConsentAttr : ComposeAttr() {
    /**
     * 同意后的初始化是否进行中（由根页面绑定）。为 true 时主按钮转圈，所有按钮 / 链接不可点。
     */
    var isBusy: Boolean by observable(false)
    /** 是否画启动页同款底图（#131313 + 居中 288 的 logo）。嵌在已有背景上时可关掉 */
    var showSplashBackground: Boolean = true
}

class SDPrivacyConsentEvent : ComposeEvent() {
    internal var agree: (() -> Unit)? = null
    internal var disagree: (() -> Unit)? = null
    internal var openLink: ((String, String) -> Unit)? = null

    /**
     * 点「同意并继续」。根页面在这里：写入同意版本 → 把 `isBusy` 置 true → 启动应用初始化 → 完成后移除本组件。
     * 初始化失败时把 `isBusy` 置回 false 即可让用户重试（同意已写入）。
     */
    fun onAgree(handler: () -> Unit) { agree = handler }

    /**
     * 第二层点「暂不使用」。不写任何同意 / 拒绝标记，根页面在这里退出应用（`SDAppModule.exitApp()`）。
     * 注意：第一层的「暂不同意」只是切到第二层，不会触发这个回调。
     */
    fun onDisagree(handler: () -> Unit) { disagree = handler }

    /**
     * 点正文里的协议链接。`url` 为对应的协议地址，`title` 为链接文案（如「《用户协议》」）。
     * Dart 里是用外部浏览器打开；打开链接不算同意。
     */
    fun onOpenLink(handler: (url: String, title: String) -> Unit) { openLink = handler }
}

/**
 * Dart `PrivacyConsentPage`（`ConsentShell` 的内容）→ `SDPrivacyConsent`：首次启动的两层隐私说明。
 *
 * - 第一层「个人信息保护指引」：正文可滚动（最高为屏高 42%），按钮「同意并继续」/「暂不同意」。
 * - 第二层「使用提示」：按钮「返回查看」/「暂不使用」。
 * - 系统返回键在两层之间切换、永不关闭 Gate：根页面在显示本组件期间让 `interceptBack()` 返回 true，
 *   并在 `onBackPressed()` 里调用 [handleBack]。
 *
 * ```
 * vif({ ctx.needConsent }) {
 *     SDPrivacyConsent {
 *         ref { ctx.consentRef = it }
 *         attr { isBusy = ctx.bootstrapping }
 *         event {
 *             onAgree { ctx.acceptConsent() }
 *             onDisagree { ctx.exitApp() }
 *             onOpenLink { url, title -> ctx.openExternal(url) }
 *         }
 *     }
 * }
 * ```
 * 组件自身铺满父容器（绝对定位），请放在根页面内容的最上层。
 */
class SDPrivacyConsentView : ComposeView<SDPrivacyConsentAttr, SDPrivacyConsentEvent>() {
    private var showReminder by observable(false)
    private var popped by observable(false)
    private var bodyContentHeight by observable(0f)

    override fun createAttr(): SDPrivacyConsentAttr = SDPrivacyConsentAttr()
    override fun createEvent(): SDPrivacyConsentEvent = SDPrivacyConsentEvent()

    override fun viewDidLoad() {
        super.viewDidLoad()
        // 首帧停在透明，和原生启动页重合后再弹出
        setTimeout(pagerId, 32) { popped = true }
    }

    /**
     * 系统返回键：在两层之间切换（与按钮行为一致）。始终返回 true（已消费）。
     */
    fun handleBack(): Boolean {
        setReminder(!showReminder)
        return true
    }

    private fun setReminder(show: Boolean) {
        if (attr.isBusy || showReminder == show) return
        showReminder = show
    }

    private fun accept() {
        if (attr.isBusy) return
        event.agree?.invoke()
    }

    private fun leaveApp() {
        if (attr.isBusy) return
        event.disagree?.invoke()
    }

    private fun openLink(label: String) {
        if (attr.isBusy) return
        val url = when (label) {
            LABEL_TERMS -> AppConstants.termsUrl
            LABEL_PRIVACY -> AppConstants.privacyUrl
            LABEL_AI -> AppConstants.aiContentStatementUrl
            else -> return
        }
        event.openLink?.invoke(url, label)
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val pd = ctx.pagerData
        val maxBodyHeight = pd.pageViewHeight * 0.42f
        val cardWidth = (pd.pageViewWidth - 48f).coerceAtMost(520f)
        return {
            attr {
                absolutePositionAllZero()
                allCenter()
                if (ctx.attr.showSplashBackground) backgroundColor(Color(0xFF131313L))
            }
            // 吞掉空白处的点击，避免穿透到下面的页面
            event { click { } }

            if (ctx.attr.showSplashBackground) {
                Image {
                    attr {
                        positionAbsolute()
                        top((pd.pageViewHeight - 288f) / 2f)
                        left((pd.pageViewWidth - 288f) / 2f)
                        size(288f, 288f)
                        src(ImageUri.commonAssets("splash/icon-1.png"))
                        resizeContain()
                    }
                }
            }

            View {
                attr {
                    width(cardWidth)
                    padding(28f, 24f, 16f, 24f)
                    borderRadius(20f)
                    backgroundColor(UIColor.c1C1C1C)
                    opacity(if (ctx.popped) 1f else 0f)
                    transform(Scale(if (ctx.popped) 1f else 0.92f, if (ctx.popped) 1f else 0.92f))
                    animate(Animation.easeOut(0.2f), ctx.popped)
                }
                Text {
                    attr {
                        text(if (ctx.showReminder) "privacy_consent_reminder_title".tr else "privacy_consent_title".tr)
                        fontSize(20f)
                        fontWeight700()
                        lineHeight(20f * 1.3f)
                        color(Color.WHITE)
                    }
                }

                vif({ ctx.showReminder }) {
                    // —— 第二层：使用提示 —— //
                    View {
                        attr { marginTop(16f) }
                        ctx.linkedText(this, "privacy_consent_reminder_body")
                    }
                    View {
                        attr { marginTop(18f) }
                        ctx.primaryButton(this, "privacy_consent_back".tr, showProgress = false) { ctx.setReminder(false) }
                        ctx.secondaryButton(this, "privacy_consent_exit".tr) { ctx.leaveApp() }
                    }
                }
                velse {
                    // —— 第一层：个人信息保护指引（只滚动正文，标题和按钮固定）—— //
                    Scroller {
                        attr {
                            marginTop(16f)
                            val h = ctx.bodyContentHeight
                            height(if (h > 0f && h < maxBodyHeight) h else maxBodyHeight)
                            showScrollerIndicator(false)
                        }
                        event {
                            contentSizeChanged { _, h -> ctx.bodyContentHeight = h }
                        }
                        ctx.linkedText(this, "privacy_consent_welcome")
                        View { attr { height(12f) } }
                        ctx.plainText(this, "privacy_consent_point_account")
                        View { attr { height(8f) } }
                        ctx.plainText(this, "privacy_consent_point_permission")
                        View { attr { height(8f) } }
                        ctx.plainText(this, "privacy_consent_point_content")
                        View { attr { height(12f) } }
                        ctx.linkedText(this, "privacy_consent_footer")
                    }
                    View {
                        attr { marginTop(18f) }
                        ctx.primaryButton(this, "privacy_consent_agree".tr, showProgress = true) { ctx.accept() }
                        ctx.secondaryButton(this, "privacy_consent_decline".tr) { ctx.setReminder(true) }
                    }
                }
            }
        }
    }

    private fun plainText(container: ViewContainer<*, *>, key: String) {
        container.Text {
            attr {
                text(key.tr)
                fontSize(BODY_SIZE)
                lineHeight(BODY_SIZE * BODY_HEIGHT)
                color(BODY_COLOR)
            }
        }
    }

    /** 正文里按字面量匹配三个协议名，渲染成白色加粗带下划线的链接。 */
    private fun linkedText(container: ViewContainer<*, *>, key: String) {
        val ctx = this
        container.SDLinkText(
            spans = splitLinkSpans(key.tr, LINK_LABELS) { label -> ctx.openLink(label) },
            size = BODY_SIZE,
            color = BODY_COLOR,
            height = BODY_HEIGHT,
            linkColor = Color.WHITE,
            linkWeight = SDWeight.W600,
            linkUnderline = true,
        )
    }

    private fun primaryButton(container: ViewContainer<*, *>, label: String, showProgress: Boolean, onPressed: () -> Unit) {
        val ctx = this
        container.View {
            attr {
                height(48f)
                borderRadius(24f)
                allCenter()
                backgroundColor(if (ctx.attr.isBusy) Color(255, 255, 255, 0.4f) else Color.WHITE)
            }
            event {
                click { if (!ctx.attr.isBusy) onPressed() }
            }
            vif({ showProgress && ctx.attr.isBusy }) {
                ActivityIndicator {
                    attr {
                        size(20f, 20f)
                        isGrayStyle(true)
                    }
                }
            }
            vif({ !(showProgress && ctx.attr.isBusy) }) {
                Text {
                    attr {
                        text(label)
                        fontSize(16f)
                        fontWeight600()
                        color(UIColor.c0A0A0A)
                    }
                }
            }
        }
    }

    private fun secondaryButton(container: ViewContainer<*, *>, label: String, onPressed: () -> Unit) {
        val ctx = this
        container.View {
            attr {
                marginTop(6f)
                height(48f)
                allCenter()
            }
            event {
                click { if (!ctx.attr.isBusy) onPressed() }
            }
            Text {
                attr {
                    text(label)
                    fontSize(15f)
                    color(Color(255, 255, 255, 0.6f))
                }
            }
        }
    }

    companion object {
        private const val LABEL_AI = "《AI 生成内容使用声明》"
        private const val LABEL_TERMS = "《用户协议》"
        private const val LABEL_PRIVACY = "《隐私政策》"

        /** 链接文案靠字面量匹配（与参考工程一致；改文案时要同步这里）。 */
        private val LINK_LABELS = listOf(LABEL_AI, LABEL_TERMS, LABEL_PRIVACY)

        private const val BODY_SIZE = 15f
        private const val BODY_HEIGHT = 1.55f
        private val BODY_COLOR = Color(255, 255, 255, 0.85f)
    }
}

fun ViewContainer<*, *>.SDPrivacyConsent(init: SDPrivacyConsentView.() -> Unit) {
    addChild(SDPrivacyConsentView(), init)
}
