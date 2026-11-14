package com.sondo.app.features.web

import com.sondo.app.base.BasePager
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.c
import com.sondo.app.nativeapi.SDWeb
import com.sondo.app.nativeapi.SDWebView
import com.sondo.app.ui.atoms.SDErrorState
import com.sondo.app.ui.atoms.SDProgressBar
import com.sondo.app.ui.nav.SDNavBar
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.View

/**
 * 内置网页（隐私政策 / 用户协议 / AI 声明 / 活动页）。
 *
 * 页面参数：`{url: String, title?: String}`。标题优先用网页自己的 `<title>`，取不到时用参数里的 title。
 * 返回（导航栏 / 系统返回键）先退网页历史，没有历史再关页。
 */
@Page(Routes.webview)
internal class WebViewPage : BasePager() {
    internal var pageTitle by observable("")
    internal var progress by observable(0f)
    internal var loading by observable(true)
    internal var failed by observable(false)
    internal var webRef: ViewRef<SDWebView>? = null

    /** 网页内前进过的层数（SDWebView 契约里没有 canGoBack，按加载事件自己计数）。 */
    private var historyDepth = 0
    private var lastState = ""
    private var pendingBack = false

    internal val argUrl: String get() = pagerData.params.optString("url", "")
    private val argTitle: String get() = pagerData.params.optString("title", "")

    override fun created() {
        super.created()
        pageTitle = argTitle
        if (argUrl.isEmpty()) {
            loading = false
            failed = true
        }
    }

    override fun interceptBack(): Boolean = true

    override fun onBackPressed(): Boolean {
        goBack()
        return true
    }

    internal fun goBack() {
        if (historyDepth > 0 && !failed) {
            historyDepth--
            pendingBack = true
            webRef?.view?.goBack()
        } else {
            nav.pop()
        }
    }

    internal fun onTitle(title: String) {
        if (title.isNotEmpty()) pageTitle = title
    }

    internal fun onLoad(state: String, value: Float) {
        when (state) {
            "start" -> {
                // 上一次已加载完成后又开始加载 = 网页内跳转了一层（自己发起的后退除外）
                if (lastState == "finish") {
                    if (pendingBack) pendingBack = false else historyDepth++
                }
                failed = false
                loading = true
                progress = value.coerceIn(0f, 1f)
            }
            "finish" -> {
                pendingBack = false
                progress = 1f
                loading = false
            }
            "error" -> {
                pendingBack = false
                loading = false
                failed = true
            }
        }
        lastState = state
    }

    internal fun retry() {
        if (argUrl.isEmpty()) return
        failed = false
        loading = true
        progress = 0f
        lastState = ""
        webRef?.view?.reload()
    }

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            attr { flex(1f) }
            SDNavBar {
                attr {
                    title = ctx.pageTitle
                }
                event { onBack { ctx.goBack() } }
            }
            View {
                attr { flex(1f) }
                vif({ ctx.argUrl.isNotEmpty() }) {
                    SDWeb {
                        ref { ctx.webRef = it }
                        attr {
                            absolutePositionAllZero()
                            url(ctx.argUrl)
                        }
                        event {
                            onTitle { ctx.onTitle(it) }
                            onLoad { state, p -> ctx.onLoad(state, p) }
                        }
                    }
                }
                vif({ ctx.loading && !ctx.failed }) {
                    SDProgressBar {
                        attr {
                            absolutePosition(top = 0f, left = 0f, right = 0f)
                            progress = ctx.progress
                            barHeight = 2f
                            trackColor = Color.TRANSPARENT
                            color = c(0xFF7F58F7L)
                        }
                    }
                }
                vif({ ctx.failed }) {
                    View {
                        attr {
                            absolutePositionAllZero()
                            backgroundColor(ctx.pageBackgroundColor())
                        }
                        SDErrorState(onRetry = if (ctx.argUrl.isNotEmpty()) ({ ctx.retry() }) else null)
                    }
                }
            }
        }
    }
}
