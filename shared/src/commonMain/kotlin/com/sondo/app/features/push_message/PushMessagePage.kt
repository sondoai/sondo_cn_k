package com.sondo.app.features.push_message

import com.sondo.app.base.BasePager
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.asset
import com.sondo.app.core.constants.c
import com.sondo.app.core.i18n.tr
import com.sondo.app.data.models.PushMessageModel
import com.sondo.app.features.push_message.data.PushMessageApi
import com.sondo.app.nativeapi.SDVideo
import com.sondo.app.nativeapi.SDVideoView
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.service.audio.AppAudioOutputCoordinator
import com.sondo.app.ui.SDFit
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.dialog.showImagePreview
import com.sondo.app.ui.overlay.DialogEntry
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

private val pageBg get() = c(0xFF030301L)

/**
 * 推送落地页（Dart `PushMessagePage` + `PushMessageController`）。
 *
 * 页面参数：`{id: Long|String}` —— 落地页 id（推送 payload 的 landingPageId）；缺失或不是数字时直接回主框架。
 */
@Page(Routes.pushMessage)
internal class PushMessagePage : BasePager() {
    internal var detail by observable(PushMessageModel())
    internal var isLoading by observable(false)
    internal var bullets by observableList<String>()

    /** 页面内嵌静音视频（全屏返回时续播位置）。 */
    internal var inlineVideoRef: ViewRef<SDVideoView>? = null
    internal var inlinePositionMs = 0L
    internal var inlineReady = false

    override fun pageBackgroundColor(): Color = pageBg

    override fun pageDidAppear() {
        super.pageDidAppear()
        if (!started) {
            started = true
            initData()
        }
    }

    private var started = false

    private fun idArg(): Long? {
        val raw = pagerData.params.opt("id") ?: return null
        return when (raw) {
            is Number -> raw.toLong()
            else -> raw.toString().trim().toLongOrNull()
        }
    }

    private fun initData() {
        // 参数缺失或类型不对时没有可展示的内容，直接回首页
        val id = idArg()
        if (id == null) {
            nav.popToRoot()
            return
        }
        isLoading = true
        showLoading()
        launch {
            val result = PushMessageApi.fetchPushMessage(id)
            hideLoading()
            val message = result.getOrNull()
            if (message == null) {
                isLoading = false
                nav.popToRoot()
                return@launch
            }
            detail = message
            bullets.clear()
            bullets.addAll(parseBullets(message.bodyContent))
            isLoading = false
        }
    }

    private fun parseBullets(content: String): List<String> {
        if (content.isEmpty()) return emptyList()
        return content.split("\n").filter { it.trim().isNotEmpty() }
    }

    internal fun authorText(author: String, date: String): String = when {
        author.isNotEmpty() && date.isNotEmpty() -> "By $author  |  $date"
        author.isNotEmpty() -> "By $author"
        else -> date
    }

    /** 底部按钮：后端下发的 Flutter 路由映射为应用内跳转。 */
    internal fun onTap() {
        var buttonUrl = detail.buttonUrl
        // 旧信息流按钮仍可能下发 /think_line_v2：参考工程改写成 /think_line_mv，
        // 它不在可打开的路由表里（需要 id），最终回到根页面
        if (buttonUrl == "/think_line_v2") buttonUrl = "/" + Routes.thinkLineMv
        if (buttonUrl.isEmpty()) {
            nav.popToRoot()
            return
        }
        when (buttonUrl) {
            // 旧舞蹈创作按钮不再打开页面。
            "/create_dance", "/think_line_dance" -> nav.popToRoot()
            // 主框架 Tab：回到根页面再切换（MV / 音乐创作由根页面打开）
            "/create_mv" -> nav.popToRoot(1)
            "/create_v2" -> nav.popToRoot(3)
            "/library" -> nav.popToRoot(4)
            "/", "/home" -> nav.popToRoot()
            else -> {
                val page = allPages.firstOrNull { UmengUtils.routePath(it) == buttonUrl }
                if (page != null) nav.push(page) else nav.popToRoot()
            }
        }
    }

    internal fun openMedia() {
        val message = detail
        if (message.mediaUrl.isEmpty()) return
        if (message.mediaType == 2) openFullscreenVideo(message.mediaUrl) else showImagePreview(listOf(message.mediaUrl))
    }

    /** 全屏播放（Dart 全高底部弹层）：暂停内嵌视频，从同一位置播放，下滑 / 返回关闭后内嵌视频接着播。 */
    private fun openFullscreenVideo(url: String) {
        val ctx = this
        val startMs = if (inlineReady) inlinePositionMs else 0L
        inlineVideoRef?.view?.pause()
        val owner = Any()
        var exitPosition = startMs
        var playing = false
        var fullRef: ViewRef<SDVideoView>? = null
        val detach = AppAudioOutputCoordinator.attach(
            owner,
            isPlaying = { playing },
            isAudible = { true },
            pauseForHandoff = { fullRef?.view?.pause() },
        )
        var claimed = false
        var ready = false
        fun tryPlay() {
            if (claimed && ready) fullRef?.view?.play()
        }
        launch {
            claimed = AppAudioOutputCoordinator.claim(owner) >= 0
            tryPlay()
        }
        var panStartY = 0f
        showDialog(
            align = DialogEntry.ALIGN_FILL,
            dismissOnMask = false,
            maskColor = Color.BLACK,
            onDismiss = {
                detach()
                if (ctx.inlineReady) {
                    ctx.inlineVideoRef?.view?.seekTo(exitPosition)
                    ctx.inlineVideoRef?.view?.play()
                }
            },
        ) { handle ->
            View {
                attr {
                    width(ctx.screenWidth)
                    height(ctx.screenHeight)
                    backgroundColor(Color.BLACK)
                    paddingTop(ctx.statusBarHeight)
                    justifyContentCenter()
                    alignItemsCenter()
                }
                event {
                    pan {
                        if (it.state == "start") panStartY = it.pageY
                        if (it.state == "end" && it.pageY - panStartY > 80f) handle.dismiss()
                    }
                }
                SDVideo {
                    ref { fullRef = it }
                    attr {
                        width(ctx.screenWidth)
                        // 成片比例未知：给出全宽、留出底部提示的高度，contain 自适应
                        height(ctx.screenHeight - ctx.statusBarHeight - 80f)
                        src(url)
                        loop(true)
                        autoPlay(false)
                        fitContain()
                    }
                    event {
                        onState { st ->
                            playing = st.isPlaying
                            if (st.state == "ready" && !ready) {
                                ready = true
                                if (startMs > 0) fullRef?.view?.seekTo(startMs)
                                tryPlay()
                            }
                        }
                        onProgress { p -> exitPosition = p.positionMs }
                    }
                }
                Text {
                    attr {
                        marginTop(20f)
                        text("exit_full_screen".tr)
                        fontSize(12f)
                        fontWeightMedium()
                        color(c(0x99FFFFFFL))
                    }
                }
            }
        }
    }

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            attr {
                flex(1f)
                paddingTop(ctx.statusBarHeight)
                paddingBottom(ctx.safeBottom)
            }
            vif({ ctx.detail.mediaUrl.isNotEmpty() }) {
                View {
                    attr { flex(1f) }
                    Scroller {
                        attr { flex(1f) }
                        mediaFrame(ctx)
                        messageContent(ctx)
                    }
                    pushAppBar(ctx)
                }
            }
            vif({ ctx.detail.buttonUrl.isNotEmpty() }) {
                View {
                    attr { alignItemsCenter() }
                    viewButton(ctx)
                }
            }
        }
    }

    companion object {
        /** 可由落地页按钮打开的页面：参考工程 `RouteStackObserver.allRoutes` 中已有 Kuikly 页面的部分，其余回根页面。 */
        private val allPages = listOf(
            Routes.musicCreate, Routes.musicVideoGen,
            Routes.mvActivity, Routes.mvStyleTemplateSelect, Routes.musicDetail, Routes.mine,
            Routes.subscribe, Routes.creditsRecord, Routes.creditsPurchase, Routes.storyLine,
            Routes.editVideo, Routes.retryShots, Routes.shotHistory, Routes.profile, Routes.square, Routes.squarePlay,
            Routes.squarePlayDetail, Routes.referral, Routes.referralRecord, Routes.fourView, Routes.fourViewForm,
            Routes.setting, Routes.trash, Routes.discount, Routes.recall, Routes.recallProduct, Routes.myAudio,
            Routes.mvListDetail, Routes.mvPreviewPlay,
        )
    }
}

private fun ViewContainer<*, *>.pushAppBar(ctx: PushMessagePage) {
    View {
        attr {
            absolutePosition(top = 0f, left = 0f, right = 0f)
            height(56f)
            flexDirectionRow()
            alignItemsCenter()
            paddingLeft(4f)
            backgroundLinearGradient(Direction.TO_BOTTOM, ColorStop(pageBg, 0f), ColorStop(Color(3, 3, 1, 0f), 1f))
        }
        View {
            attr {
                size(48f, 48f)
                allCenter()
            }
            event { click { ctx.nav.pop() } }
            Image {
                attr {
                    size(28f, 28f)
                    resizeContain()
                    // Dart Icons.chevron_left 28（由 MaterialIcons 字形导出）
                    src("images/common/chevron_left.png".asset())
                    tintColor(Color.WHITE)
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.mediaFrame(ctx: PushMessagePage) {
    val w = ctx.screenWidth
    // 背景 1440×1564、前景 1440×1824，宽度撑满按原比例
    val bgHeight = w * 1564f / 1440f
    val iconHeight = w * 1824f / 1440f
    val frameWidth = w / 1.5f
    val frameHeight = frameWidth * 3f / 4f
    View {
        attr {
            width(w)
            height(maxOf(bgHeight, iconHeight))
        }
        Image {
            attr {
                absolutePosition(top = 0f, left = 0f)
                size(w, bgHeight)
                resizeStretch()
                src(AssetsImage.pushBg1.asset())
            }
        }
        View {
            attr {
                absolutePositionAllZero()
                allCenter()
            }
            View {
                attr {
                    size(frameWidth, frameHeight)
                    borderRadius(4f)
                    backgroundColor(Color(0, 0, 0, 0x42 / 255f))
                }
                View {
                    attr {
                        absolutePositionAllZero()
                        padding(10f, 10f, 10f, 10f)
                    }
                    vif({ ctx.detail.mediaUrl.isNotEmpty() && ctx.detail.mediaType == 2 }) {
                        SDVideo {
                            ref { ctx.inlineVideoRef = it }
                            attr {
                                flex(1f)
                                src(ctx.detail.mediaUrl)
                                loop(true)
                                muted(true)
                                volume(0f)
                                mixWithOthers(true)
                                autoPlay(true)
                                fitCover()
                            }
                            event {
                                onState { if (it.state == "ready" || it.isPlaying) ctx.inlineReady = true }
                                onProgress { ctx.inlinePositionMs = it.positionMs }
                            }
                        }
                    }
                    vif({ ctx.detail.mediaUrl.isNotEmpty() && ctx.detail.mediaType != 2 }) {
                        SDImage {
                            attr {
                                flex(1f)
                                url = ctx.detail.mediaUrl
                                fit = SDFit.COVER
                            }
                        }
                    }
                }
                Image {
                    attr {
                        absolutePositionAllZero()
                        resizeStretch()
                        src(AssetsImage.pushBorder.asset())
                    }
                }
            }
        }
        Image {
            attr {
                absolutePosition(top = 0f, left = 0f)
                size(w, iconHeight)
                resizeStretch()
                src(AssetsImage.pushIcon.asset())
            }
            event { click { ctx.openMedia() } }
        }
        vif({ ctx.detail.createTimeStr.isNotEmpty() }) {
            Text {
                attr {
                    absolutePosition(top = 50f, left = 0f, right = 0f)
                    text(ctx.authorText(ctx.detail.author, ctx.detail.createTimeStr))
                    fontSize(9.6f)
                    fontWeightMedium()
                    color(c(0xCCFFFFFFL))
                    textAlignCenter()
                }
            }
        }
        vif({ ctx.detail.contentTitle.isNotEmpty() }) {
            Text {
                attr {
                    absolutePosition(left = 0f, right = 0f, bottom = 10f)
                    text(ctx.detail.contentTitle)
                    fontSize(24f)
                    fontWeightBold()
                    lineHeight(24f * 1.3f)
                    color(c(0xFFD9D9D9L))
                    textAlignCenter()
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.messageContent(ctx: PushMessagePage) {
    View {
        attr { padding(10f, 20f, 20f, 20f) }
        vif({ ctx.bullets.isNotEmpty() }) {
            View {
                vfor({ ctx.bullets }) { line ->
                    View {
                        attr {
                            flexDirectionRow()
                            alignItemsFlexStart()
                            paddingBottom(6f)
                        }
                        View {
                            attr {
                                marginTop(5f)
                                marginRight(8f)
                                size(6f, 6f)
                                borderRadius(3f)
                                backgroundColor(c(0x99FFFFFFL))
                            }
                        }
                        Text {
                            attr {
                                flex(1f)
                                text(line.replaceFirst(Regex("^[•\\-*]\\s*"), ""))
                                fontSize(12f)
                                lineHeight(12f * 1.1f)
                                color(c(0x99FFFFFFL))
                            }
                        }
                    }
                }
                View { attr { height(24f) } }
            }
        }
        vif({ ctx.bullets.isEmpty() && ctx.detail.bodyContent.isNotEmpty() }) {
            View {
                Text {
                    attr {
                        text(ctx.detail.bodyContent)
                        fontSize(12f)
                        lineHeight(12f * 1.88f)
                        color(c(0x99FFFFFFL))
                    }
                }
                View { attr { height(24f) } }
            }
        }
    }
}

private fun ViewContainer<*, *>.viewButton(ctx: PushMessagePage) {
    View {
        attr {
            height(42f)
            marginBottom(10f)
            padding(0f, 24f, 0f, 7f)
            flexDirectionRow()
            alignItemsCenter()
            borderRadius(183f)
            backgroundLinearGradient(Direction.TO_RIGHT, ColorStop(c(0xFFB1DAFFL), 0f), ColorStop(c(0xFFF5B8FFL), 1f))
        }
        event { click { ctx.onTap() } }
        Text {
            attr {
                text("View")
                fontSize(17f)
                fontWeightBold()
                color(c(0xFF0C0C0CL))
            }
        }
        View { attr { width(31f) } }
        View {
            attr {
                size(36f, 28f)
                borderRadius(35f)
                backgroundColor(Color.BLACK)
                allCenter()
            }
            Image {
                attr {
                    size(18f, 18f)
                    resizeContain()
                    // Dart Icons.arrow_forward（带杆的右箭头，由 MaterialIcons 字形导出）
                    src("images/common/arrow_forward.png".asset())
                    tintColor(Color.WHITE)
                }
            }
        }
    }
}
