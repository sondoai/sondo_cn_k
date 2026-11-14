package com.sondo.app.features.member.recall

import com.sondo.app.base.AppEnv
import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.constants.asset
import com.sondo.app.core.i18n.tr
import com.sondo.app.data.api.AppConfigApi
import com.sondo.app.data.models.GoodsModel
import com.sondo.app.data.models.RecallCard
import com.sondo.app.data.models.RecallPageModel
import com.sondo.app.data.models.ReturningOffer
import com.sondo.app.features.member.memberPayResult
import com.sondo.app.features.member.recall.widgets.RecallMediaBlock
import com.sondo.app.features.member.widgets.OfferLegalLinks
import com.sondo.app.service.ConfigService
import com.sondo.app.service.RecallFlow
import com.sondo.app.service.UserStore
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.service.audio.AppAudioOutputCoordinator
import com.sondo.app.service.pay.PayController
import com.sondo.app.service.pay.PayService
import com.sondo.app.service.pay.alipayRenewNoticeFor
import com.sondo.app.service.pay.loadRecallGoods
import com.sondo.app.service.pay.recallPeriodLabel
import com.sondo.app.ui.SDTextAlign
import com.sondo.app.ui.atoms.HGap
import com.sondo.app.ui.atoms.SDCircularIndicator
import com.sondo.app.ui.atoms.SDShimmer
import com.sondo.app.ui.atoms.SDText
import com.sondo.app.ui.atoms.Spacer
import com.sondo.app.ui.atoms.VGap
import com.sondo.app.ui.nav.SDNavBack
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.Rotate
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.event.layoutFrameDidChange
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.velseif
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.RichText
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Span
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

private val recallPurple = Color(0xFF7F58F7L)

/**
 * 老用户回归落地页（Dart: features/member/presentation/pages/recall/recall_page.dart + RecallController，
 * 流程见 docs/recall_page_flow.md）。文案和媒体都来自后台（`ApiPaths.oldPeopleContent`），商品区按回归优惠接口单独隐藏。
 *
 * 参数：`{source: "deeplink" | "auto"}`（由 `RecallFlow` 打开；缺省按 auto）。
 * 结果：购买成功由 PayService 关页并带回 `{"result": true}`。
 */
@Page(Routes.recall)
internal class RecallPage : BasePager() {

    // --- 页面内容 ---

    /** 后台配置。为空且 [loadFailed] 时展示重试。 */
    private var page by observable<RecallPageModel?>(null)

    /** 内容请求失败，页内可重试。 */
    private var loadFailed by observable(false)

    /** 进入页面后先出骨架，内容回来再换成正式布局。 */
    private var loading by observable(true)

    private var source = RecallFlow.SOURCE_AUTO

    // --- 商品 ---

    /** 当前平台校验通过的回归商品。Android 使用后端商品，iOS 另带商店详情。 */
    private var goods by observable<GoodsModel?>(null)

    /** 回归优惠接口的 visible。商品区是否出现只看这个字段。 */
    private var offerVisible by observable(false)

    /** 最近一次回归优惠接口结果，下单前看 canPurchase。 */
    private var offer: ReturningOffer? = null

    private var goodsLoading by observable(false)

    private var inRequest = false
    private var pageViewLogged = false
    private var leaving = false

    /** 当前允许播放的视频。有点中的卡片就播它，否则头图够八成就播欢迎视频。 */
    private var activeVideoId by observable<String?>(null)

    /** 用户点过播放的卡片。再点一次或露出不超过 20% 才停。 */
    private var playingCardId: String? = null

    private var heroVisible = false
    private var pageHidden = false
    private var appBackground = false

    /** 导航底色透明度，0 透出头图，1 为页面底色。 */
    private var navOpacity by observable(0f)

    private var scrollY = 0f
    private var viewportHeight = 0f
    private var columnTop = 0f
    private val cardTops = HashMap<String, Float>()

    /** 卡片视频出声时参与应用内出声仲裁（与音乐播放器互斥）。 */
    private val cardAudioOwner = Any()
    private var detachAudio: (() -> Unit)? = null

    private val heroVisibleHeight: Float get() = screenWidth * 260f / 360f

    val enabledCards: List<RecallCard> get() = page?.enabledCards ?: emptyList()

    /** 接口允许展示，且当前平台商品完整时展示；购买资格另行确认。 */
    val showProduct: Boolean get() = offerVisible && goods != null

    val discountPercentage: Int? get() = if (AppEnv.isIOS) 50 else offer?.discountPercentage

    /** 登录前也允许点击；登录完成后重新确认购买资格。 */
    val canSubscribe: Boolean get() = showProduct && (!AppEnv.isIOS || goods?.productDetails != null)

    val showRestore: Boolean get() = AppEnv.isIOS && showProduct

    val renewalNotice: String
        get() {
            val g = goods
            return if (AppEnv.isIOS || g == null) "cancel_anytime_auto_renewable".tr else alipayRenewNoticeFor(g)
        }

    override fun created() {
        super.created()
        source = pagerData.params.optString("source", "").ifEmpty { RecallFlow.SOURCE_AUTO }
        on(Events.PAY_RESULT) { json -> memberPayResult(json, Routes.recall) { inRequest = false } }
        // 欢迎区视频离开页面或进后台会暂停
        on(Events.APP_LIFECYCLE) { json ->
            appBackground = !json.optBoolean("foreground", true)
            syncActiveVideo()
        }
        detachAudio = AppAudioOutputCoordinator.attach(
            cardAudioOwner,
            isPlaying = { playingCardId != null },
            isAudible = { true },
            pauseForHandoff = { pauseCard() },
        )
        if (AppEnv.isIOS) PayService.resumePurchase(showRestoreTips = false)
        // 跳转前不带内容，进入后再请求。
        if (page == null) load()
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        pageHidden = false
        syncActiveVideo()
    }

    override fun pageDidDisappear() {
        super.pageDidDisappear()
        pageHidden = true
        syncActiveVideo()
    }

    override fun pageWillDestroy() {
        detachAudio?.invoke()
        detachAudio = null
        super.pageWillDestroy()
    }

    // ------------------------------------------------------------------ 视频播放权

    /** 头图按裁切区域算八成；正在播的卡片露出不超过 20% 视为暂停。卡片不会因为靠近中间而自动播。 */
    private fun reportVideoVisibility(id: String, fraction: Float) {
        if (id == HERO_VIDEO_ID) {
            heroVisible = fraction >= HERO_PLAY_FRACTION
        } else if (id == playingCardId && fraction <= 0.2f) {
            playingCardId = null
            AppAudioOutputCoordinator.release(cardAudioOwner)
        }
        syncActiveVideo()
    }

    /** 点卡片播放。同一时间只播这一张，顶部和其它卡片都停。 */
    fun playCard(id: String) {
        if (id == HERO_VIDEO_ID) return
        playingCardId = id
        syncActiveVideo()
        launch {
            val token = AppAudioOutputCoordinator.claim(cardAudioOwner)
            // 交接期间用户又停了 / 换了卡：不再出声
            if (!AppAudioOutputCoordinator.isCurrent(cardAudioOwner, token) && playingCardId == id) pauseCard()
        }
    }

    /** 再点正在播的卡片就停。没有卡片在播时，头图仍够八成就恢复静音自动播。 */
    fun pauseCard() {
        playingCardId = null
        AppAudioOutputCoordinator.release(cardAudioOwner)
        syncActiveVideo()
    }

    private fun syncActiveVideo() {
        val next = if (pageHidden || appBackground) null else playingCardId ?: (if (heroVisible) HERO_VIDEO_ID else null)
        if (activeVideoId != next) activeVideoId = next
    }

    fun onVideoFailed(id: String) = reportVideoVisibility(id, 0f)

    fun isActive(id: String): Boolean = activeVideoId == id

    /** 向上滑动时导航底色逐渐变深，回到顶部再透出头图；同时按滚动位置重算视频露出比例。 */
    private fun onContentScroll(offsetY: Float, viewHeight: Float) {
        scrollY = offsetY
        if (viewHeight > 0f) viewportHeight = viewHeight
        val opacity = (offsetY / NAV_FADE_DISTANCE).coerceIn(0f, 1f)
        // 两端必须落到 0 和 1，否则纯色标题会对不齐。
        if (!(opacity != 0f && opacity != 1f && kotlin.math.abs(navOpacity - opacity) < 0.01f) && navOpacity != opacity) {
            navOpacity = opacity
        }
        updateVisibility()
    }

    private fun updateVisibility() {
        val p = page ?: return
        if (p.welcome.isVideo) {
            val h = heroVisibleHeight
            val fraction = ((h - scrollY) / h).coerceIn(0f, 1f)
            val visible = fraction >= HERO_PLAY_FRACTION
            if (visible != heroVisible) reportVideoVisibility(HERO_VIDEO_ID, fraction)
        }
        val playing = playingCardId ?: return
        val top = cardTops[playing] ?: return
        if (viewportHeight <= 0f) return
        val cardTop = columnTop + top
        val cardBottom = cardTop + CARD_MEDIA_HEIGHT
        val visibleTop = maxOf(cardTop, scrollY)
        val visibleBottom = minOf(cardBottom, scrollY + viewportHeight)
        val fraction = ((visibleBottom - visibleTop) / CARD_MEDIA_HEIGHT).coerceIn(0f, 1f)
        reportVideoVisibility(playing, fraction)
    }

    fun onColumnTop(y: Float) {
        columnTop = y
    }

    fun onCardTop(id: String, y: Float) {
        cardTops[id] = y
    }

    // ------------------------------------------------------------------ 数据

    /** 拉欢迎区和功能卡片。失败留在页内重试，不把用户弹走。 */
    fun load() {
        loading = true
        loadFailed = false
        navOpacity = 0f
        launch {
            val config = AppConfigApi.fetchOldPeopleContent().getOrNull()
            loading = false
            if (config == null) {
                loadFailed = true
                page = null
                goods = null
                offerVisible = false
                return@launch
            }
            // 内容先出来时还不知道有没有商品，先按加载中，避免创作按钮闪一下再换成价格卡。
            goodsLoading = true
            page = config
            loadFailed = false
            scrollY = 0f
            heroVisible = config.welcome.isVideo
            syncActiveVideo()
            logPageView()
            loadGoods()
        }
    }

    private suspend fun loadGoods() {
        if (page == null) {
            goods = null
            offerVisible = false
            goodsLoading = false
            return
        }
        val result = AppConfigApi.fetchReturningOffer().getOrNull()
        offer = result
        // 登录后重查如果没资格，停掉倒计时。已过期的记录之后再看到商品也不会开新窗口。
        RecallFlow.stopCountdownWhenBlocked(result)
        // 是否展示只看接口 visible。为 false 时不查商店，也不保留上一次的价格。
        offerVisible = result?.visible ?: false
        if (result == null || !result.visible) {
            goods = null
            goodsLoading = false
            return
        }
        goodsLoading = true
        // iOS 必须核验商店促销，Android 必须有完整的支付宝价格与周期。
        val loaded = loadRecallGoods(result)
        goods = loaded
        goodsLoading = false
        if (showProduct) {
            // 商品区展示出来就计时，人还在本页时窗口已经在走。已有到期记录不会被重置。
            ConfigService.countdown.startRecallCountdownOnce()
        }
    }

    private fun logPageView() {
        if (pageViewLogged || page == null) return
        pageViewLogged = true
        // 内容可见即报，不等视频和登录。
        UmengUtils.logEventPageView(Routes.recall, source, null)
    }

    /** 未登录先登录，回来后重查当前平台商品与购买资格。 */
    fun subscribe() {
        if (!canSubscribe || inRequest) return
        launch {
            val justLoggedIn = !UserStore.isLogin
            if (justLoggedIn) {
                val loggedIn = UserStore.showLoginDialog(this@RecallPage)
                if (!loggedIn) return@launch
            }
            // 登录或换号后重查回归商品，不覆盖已经展示的欢迎区和功能卡片。
            loadGoods()
            // visible=false 即没资格：只有在本页刚登录才提示。
            if (!offerVisible) {
                if (justLoggedIn) toast("recall_offer_unavailable".tr)
                return@launch
            }
            // 平台商品核验失败时不提交，不把数据缺失误报成用户没资格。
            if (!canSubscribe) return@launch
            // 登录后接口仍不允许买（已买过、没资格等）时只提示，不下单。
            if (offer?.canPurchase != true) {
                toast("recall_offer_unavailable".tr)
                return@launch
            }
            val item = goods ?: return@launch
            inRequest = true
            PayController.subscript(item, Routes.recall) { inRequest = false }
        }
    }

    fun restore() {
        if (AppEnv.isIOS) PayService.resumePurchase()
    }

    /** 没商品时去创作：直接换成 MV 创作页，避免创作页压在落地页上面。 */
    fun openCreateMv() {
        if (leaving) return
        leaving = true
        UmengUtils.logEventButtonClick("recall_to_mv", pageName = Routes.recall)
        UmengUtils.logEvent("root_page_change", mapOf("pageName" to "mv"))
        if (nav.contains(Routes.musicVideoGen)) {
            // 创作页已在栈内：回到它，不再建一份
            nav.popTo(Routes.musicVideoGen)
        } else {
            nav.replace(Routes.musicVideoGen)
        }
    }

    fun closePage() {
        if (leaving) return
        leaving = true
        nav.pop()
    }

    // ------------------------------------------------------------------ UI

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            attr { backgroundColor(UIColor.c0A0A0A) }
            vif({ ctx.loading }) {
                recallSkeleton(ctx)
            }
            velseif({ ctx.loadFailed || ctx.page == null }) {
                recallFailure { ctx.load() }
            }
            velse {
                Scroller {
                    attr {
                        flex(1f)
                        showScrollerIndicator(false)
                        // Dart _NoTopStretchScrollPhysics：只禁顶部拉伸，底部照常回弹
                        bouncesEnable(true, limitHeaderBounces = true)
                    }
                    event {
                        scroll { ctx.onContentScroll(it.offsetY, it.viewHeight) }
                        layoutFrameDidChange { ctx.viewportHeight = it.height }
                    }
                    vbind({ ctx.page }) {
                        val p = ctx.page
                        if (p != null) recallContent(ctx, p)
                    }
                }
            }
            recallNavBar(ctx)
        }
    }

    // —— 供顶层构建函数读取的状态 —— //
    fun pageModel(): RecallPageModel? = page
    fun goodsModel(): GoodsModel? = goods
    fun isGoodsLoading(): Boolean = goodsLoading
    fun opacity(): Float = navOpacity
    fun heroHeight(): Float = heroVisibleHeight

    companion object {
        const val HERO_VIDEO_ID = "welcome"
        const val HERO_PLAY_FRACTION = 0.8f
        const val NAV_FADE_DISTANCE = 80f
        const val CARD_MEDIA_HEIGHT = 180f
    }
}

// ============================================================================ 导航栏

private fun ViewContainer<*, *>.recallNavBar(ctx: RecallPage) {
    View {
        attr {
            absolutePosition(top = 0f, left = 0f, right = 0f)
            paddingTop(ctx.statusBarHeight)
            backgroundColor(Color(0x0A, 0x0A, 0x0A, ctx.opacity()))
        }
        View {
            attr {
                height(56f)
                flexDirectionRow()
                alignItemsCenter()
            }
            SDNavBack { ctx.closePage() }
            Spacer()
            vif({ ctx.showRestore }) {
                View {
                    attr {
                        height(32f)
                        marginRight(12f)
                        padding(0f, 14f, 0f, 14f)
                        allCenter()
                        backgroundColor(Color(255, 255, 255, 0.2f))
                        borderRadius(43f)
                    }
                    event { click { ctx.restore() } }
                    SDText("restore".tr, size = 12f, color = Color(255, 255, 255, 0.8f), fontFamily = "Poppins-Medium")
                }
            }
            // 底色铺实后才把标题收到导航中间，避免和头图上的大标题叠在一起。
            vif({ ctx.opacity() >= 1f && (ctx.pageModel()?.welcome?.title ?: "").isNotEmpty() }) {
                View {
                    attr {
                        absolutePosition(top = 0f, left = 96f, bottom = 0f, right = 96f)
                        allCenter()
                        touchEnable(false)
                    }
                    Text {
                        attr {
                            text(ctx.pageModel()?.welcome?.title ?: "")
                            fontSize(16f)
                            fontFamily("Poppins-SemiBold")
                            color(Color.WHITE)
                            textAlignCenter()
                            lines(1)
                            textOverFlowTail()
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================ 骨架 / 失败

private fun ViewContainer<*, *>.skeletonBlock(width: Float?, height: Float, radius: Float = 0f) {
    SDShimmer {
        attr {
            if (width != null) width(width) else alignSelfStretch()
            height(height)
            this.radius = radius
            baseColor = UIColor.c1C1C1C
            highlightColor = Color(0xFF2E2E2EL)
        }
    }
}

/** 内容返回前的占位，高度和间距跟正式布局一致。 */
private fun ViewContainer<*, *>.recallSkeleton(ctx: RecallPage) {
    val width = ctx.screenWidth
    View {
        attr {
            flex(1f)
            overflow(true)
        }
        skeletonBlock(width, ctx.heroHeight())
        View {
            attr { padding(0f, 20f, 24f, 20f) }
            skeletonBlock(220f, 36f, 4f)
            VGap(8f)
            skeletonBlock(null, 18f, 4f)
            VGap(4f)
            skeletonBlock(width * 0.55f, 18f, 4f)
            VGap(24f)
            skeletonBlock(120f, 14f, 4f)
            VGap(16f)
            skeletonCard()
            VGap(16f)
            skeletonCard()
            VGap(24f)
            skeletonBlock(null, (width - 40f) * 230f / 320f)
        }
    }
}

private fun ViewContainer<*, *>.skeletonCard() {
    View {
        attr {
            borderRadius(24f)
            overflow(true)
        }
        skeletonBlock(null, 180f)
        View {
            attr { padding(12f, 12f, 14f, 12f) }
            skeletonBlock(140f, 20f)
            VGap(4f)
            skeletonBlock(null, 18f)
        }
    }
}

private fun ViewContainer<*, *>.recallFailure(onRetry: () -> Unit) {
    View {
        attr {
            flex(1f)
            allCenter()
            padding(0f, 32f, 0f, 32f)
        }
        Image {
            attr {
                size(48f, 48f)
                // Dart Icons.wifi_off_rounded（由 MaterialIcons 字体导出的白色图）
                src(AssetsImage.wifiOff.asset())
                tintColor(Color(0x61FFFFFFL))
                resizeContain()
            }
        }
        VGap(12f)
        SDText(
            "trash_load_failed".tr,
            size = 14f,
            color = Color(0xB3FFFFFFL),
            fontFamily = "Poppins",
            textAlign = SDTextAlign.CENTER,
        )
        VGap(20f)
        View {
            attr {
                height(36f)
                minWidth(100f)
                padding(0f, 20f, 0f, 20f)
                allCenter()
                backgroundColor(UIColor.cF3EFF7)
                borderRadius(53f)
            }
            event { click { onRetry() } }
            SDText("retry".tr, size = 14f, color = UIColor.c0A0A0A, fontFamily = "Poppins-SemiBold")
        }
    }
}

// ============================================================================ 正文

private fun ViewContainer<*, *>.recallContent(ctx: RecallPage, page: RecallPageModel) {
    val width = ctx.screenWidth
    val welcome = page.welcome
    // 没有媒体地址时不留正方形空白，文案直接从顶部开始。
    val hasHero = welcome.isVideo || welcome.hasImage
    View {
        if (hasHero) {
            View {
                attr { height(ctx.heroHeight()) }
                // 头图是正方形，底部按 100/360 裁掉，标题从裁切线下面开始。
                View {
                    attr {
                        absolutePositionAllZero()
                        overflow(true)
                    }
                    View {
                        attr {
                            absolutePosition(top = 0f, left = 0f)
                            size(width, width)
                        }
                        RecallMediaBlock {
                            attr {
                                media = welcome
                                mediaHeight = width
                                videoId = RecallPage.HERO_VIDEO_ID
                                isHero = true
                                active = ctx.isActive(RecallPage.HERO_VIDEO_ID)
                            }
                            event { onVideoFailed { ctx.onVideoFailed(RecallPage.HERO_VIDEO_ID) } }
                        }
                        View {
                            attr {
                                absolutePositionAllZero()
                                touchEnable(false)
                                backgroundLinearGradient(
                                    Direction.TO_BOTTOM,
                                    ColorStop(Color(0xCC000000L), 0f),
                                    ColorStop(Color(0x00000000L), 0.37f),
                                )
                            }
                        }
                        View {
                            attr {
                                absolutePositionAllZero()
                                touchEnable(false)
                                backgroundLinearGradient(
                                    Direction.TO_BOTTOM,
                                    ColorStop(Color(0x000A0A0AL), 0.54f),
                                    ColorStop(UIColor.c0A0A0A, 1f),
                                )
                            }
                        }
                    }
                }
                // 画在裁切后露出的底边。整段素材的最底下会被裁掉，用户看不到。
                if (welcome.isVideo) {
                    View {
                        attr {
                            absolutePosition(left = 0f, right = 0f, bottom = -1f)
                            height(2f)
                            backgroundColor(Color.BLACK)
                        }
                    }
                }
            }
        }
        View {
            attr { padding(0f, 20f, 24f, 20f) }
            event { layoutFrameDidChange { ctx.onColumnTop(it.y) } }
            if (!hasHero) VGap(ctx.statusBarHeight + 56f)
            if (welcome.title.isNotEmpty()) {
                SDText(welcome.title, size = 24f, fontFamily = "Poppins-SemiBold", height = 1.5f)
            }
            if (welcome.description.isNotEmpty()) {
                VGap(8f)
                SDText(
                    welcome.description,
                    size = 12f,
                    fontFamily = "Poppins-Medium",
                    height = 1.5f,
                    color = UIColor.cFFF40,
                    maxLines = 6,
                )
            }
            val cards = page.enabledCards
            if (cards.isNotEmpty()) {
                VGap(24f)
                sectionTitle(page.sectionTitle)
                VGap(16f)
                cards.forEachIndexed { i, card ->
                    if (i > 0) VGap(16f)
                    featureCard(ctx, card, "card-$i")
                }
            }
            VGap(24f)
            vbind({ Triple(ctx.isGoodsLoading(), ctx.goodsModel(), ctx.showProduct) }) {
                if (ctx.isGoodsLoading() || ctx.showProduct) {
                    // 商品上方插画按设计稿写死，320:230，透明底，不裁成圆角卡片。
                    Image {
                        attr {
                            size(width - 40f, (width - 40f) * 230f / 320f)
                            src(AssetsImage.recallOfferArt.asset())
                            resizeContain()
                        }
                    }
                    VGap(8f)
                    productBlock(ctx, page)
                } else {
                    // 加载中不放按钮，避免先闪一下再换成价格卡。
                    VGap(24f)
                    View {
                        attr { alignItemsCenter() }
                        View {
                            attr {
                                height(48f)
                                padding(0f, 24f, 0f, 24f)
                                allCenter()
                                backgroundColor(Color.WHITE)
                                borderRadius(48f)
                            }
                            event { click { ctx.openCreateMv() } }
                            SDText(
                                "recall_try_new_features".tr,
                                size = 16f,
                                fontFamily = "Poppins-SemiBold",
                                color = Color.BLACK,
                            )
                        }
                    }
                }
            }
            // 法律入口固定在内容最底部，商品区隐藏时也要能点到。
            VGap(12f)
            OfferLegalLinks({ UserStore.openTerms(ctx) }, { UserStore.openPrivacyPolicy(ctx) })
            VGap(ctx.safeBottom)
        }
    }
}

private fun ViewContainer<*, *>.sectionTitle(title: String) {
    if (title.isEmpty()) return
    View {
        attr {
            flexDirectionRow()
            alignItemsCenter()
        }
        View {
            attr {
                size(8f, 8f)
                backgroundColor(recallPurple)
                transform(Rotate(45f))
            }
        }
        HGap(8f)
        View {
            attr { flex(1f) }
            SDText(title, size = 14f, fontFamily = "Poppins-SemiBold", color = recallPurple)
        }
    }
}

private fun ViewContainer<*, *>.featureCard(ctx: RecallPage, card: RecallCard, videoId: String) {
    View {
        attr {
            backgroundColor(UIColor.c1C1C1C)
            borderRadius(24f)
            overflow(true)
        }
        event { layoutFrameDidChange { ctx.onCardTop(videoId, it.y) } }
        RecallMediaBlock {
            attr {
                media = card.media
                mediaHeight = RecallPage.CARD_MEDIA_HEIGHT
                this.videoId = videoId
                isHero = false
                active = ctx.isActive(videoId)
            }
            event {
                onPlay { ctx.playCard(videoId) }
                onPause { ctx.pauseCard() }
                onVideoFailed { ctx.onVideoFailed(videoId) }
            }
        }
        View {
            attr { padding(12f, 12f, 14f, 12f) }
            if (card.media.title.isNotEmpty()) {
                SDText(card.media.title, size = 14f, fontFamily = "Poppins-SemiBold", height = 1.4f)
            }
            if (card.media.description.isNotEmpty()) {
                VGap(4f)
                SDText(
                    card.media.description,
                    size = 12f,
                    fontFamily = "Poppins-Medium",
                    height = 1.5f,
                    color = UIColor.cFFF40,
                    maxLines = 4,
                )
            }
        }
    }
}

// ============================================================================ 商品区

private fun ViewContainer<*, *>.productBlock(ctx: RecallPage, page: RecallPageModel) {
    val product = page.product
    val goods = ctx.goodsModel()
    val discountPercentage = ctx.discountPercentage
    val canBuy = ctx.canSubscribe
    View {
        attr { alignItemsCenter() }
        if (discountPercentage != null) offerHeadline(discountPercentage)
        VGap(12f)
        View {
            attr {
                padding(8f, 0f, 8f, 0f)
                alignItemsCenter()
            }
            SDText(
                "recall_offer_title".tr,
                size = 18f,
                textAlign = SDTextAlign.CENTER,
                fontFamily = "Poppins-SemiBold",
                height = 24f / 18f,
            )
            VGap(8f)
            SDText(
                "recall_offer_subtitle".tr,
                size = 10f,
                textAlign = SDTextAlign.CENTER,
                fontFamily = "Poppins-Medium",
                height = 1.6f,
                color = UIColor.cFFF40,
            )
        }
        VGap(24f)
        if (ctx.isGoodsLoading() && goods == null) {
            View {
                attr {
                    height(100f)
                    alignSelfStretch()
                    allCenter()
                }
                // Dart CircularProgressIndicator(color: _purple)
                SDCircularIndicator(color = Color(0xFF7F58F7L))
            }
        } else if (goods != null && ctx.showProduct) {
            priceCard(goods)
            if (canBuy) {
                VGap(40f)
                SDText(
                    ctx.renewalNotice,
                    size = 10f,
                    maxLines = 3,
                    textAlign = SDTextAlign.CENTER,
                    fontFamily = "Poppins-Medium",
                    color = UIColor.cFFF40,
                )
                VGap(12f)
                View {
                    attr {
                        alignSelfStretch()
                        height(48f)
                        borderRadius(48f)
                        backgroundColor(recallPurple)
                        allCenter()
                    }
                    event { click { ctx.subscribe() } }
                    SDText(
                        product.buttonText.ifEmpty { "subscribe".tr },
                        size = 16f,
                        fontFamily = "Poppins-SemiBold",
                        height = 19f / 16f,
                    )
                }
                VGap(12f)
                SDText(
                    "recall_offer_terms".tr,
                    size = 10f,
                    textAlign = SDTextAlign.CENTER,
                    fontFamily = "Poppins-Medium",
                    height = 1.2f,
                    color = UIColor.cFFF40,
                )
            }
        }
    }
}

/** 「50% OFF」：白 → #C4B3FA 竖向渐变字。 */
private fun ViewContainer<*, *>.offerHeadline(percentage: Int) {
    View {
        attr {
            flexDirectionRow()
            justifyContentCenter()
            alignItemsFlexEnd()
        }
        gradientText("$percentage", 56f, 56f, "Poppins-Bold")
        gradientText("%", 56f, 56f, "Poppins-SemiBold")
        HGap(4f)
        gradientText("OFF", 40f, 44f, "Poppins-SemiBold")
    }
}

private fun ViewContainer<*, *>.gradientText(text: String, size: Float, lineHeight: Float, family: String) {
    Text {
        attr {
            text(text)
            fontSize(size)
            lineHeight(lineHeight)
            fontFamily(family)
            color(Color.WHITE)
            lines(1)
            backgroundLinearGradient(
                Direction.TO_BOTTOM,
                ColorStop(Color.WHITE, 0f),
                ColorStop(Color(0xFFC4B3FAL), 1f),
            )
        }
    }
}

/** 价格串拆成「货币符号 + 数字」（Dart `_splitPrice`）。 */
private fun splitPrice(formatted: String): Pair<String, String> {
    val trimmed = formatted.trim()
    val match = Regex("^(\\D*)(\\d[\\d.,]*)$").find(trimmed) ?: return "" to trimmed
    return match.groupValues[1] to match.groupValues[2]
}

private fun ViewContainer<*, *>.priceCard(goods: GoodsModel) {
    val price = goods.introductoryPrice.ifEmpty { goods.shopPrice }
    val (symbol, amount) = splitPrice(price)
    val original = goods.originalPrice
    val showOriginal = goods.numberOfPeriods > 0 && original.isNotEmpty() && original != price
    val dim = Color(0x99000000L)
    View {
        attr {
            size(300f, 128f)
            alignSelfCenter()
        }
        Image {
            attr {
                absolutePosition(top = 30f, left = 10f)
                size(280f, 90f)
                src(AssetsImage.recallCardBack.asset())
                resizeStretch()
                transform(Rotate(-3f))
            }
        }
        Image {
            attr {
                absolutePosition(top = 20f, left = 10f)
                size(280f, 100f)
                src(AssetsImage.recallCardFace.asset())
                resizeStretch()
            }
        }
        Image {
            attr {
                absolutePosition(top = 22f, left = 190f)
                size(88f, 88f)
                src(AssetsImage.recallCardNote.asset())
                resizeContain()
                transform(Rotate(-24f))
            }
        }
        Image {
            attr {
                absolutePosition(top = 0f, left = 253f)
                size(30f, 26f)
                src(AssetsImage.recallCardSquiggle.asset())
                resizeContain()
            }
        }
        Image {
            attr {
                absolutePosition(top = 12f, left = 266f)
                size(29f, 21f)
                src(AssetsImage.recallCardSpark.asset())
                resizeContain()
            }
        }
        Image {
            attr {
                absolutePosition(top = 26f, left = 270f)
                size(14f, 14f)
                src(AssetsImage.recallCardDot.asset())
                resizeContain()
            }
        }
        View {
            attr { absolutePosition(top = 33f, left = 26f, right = 26f) }
            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                }
                SDText(
                    if (AppEnv.isIOS) "recall_week_pass".tr else goods.productName ?: "pro_member".tr,
                    size = 12f,
                    fontFamily = "Poppins-Bold",
                    height = 16f / 12f,
                    color = UIColor.c0A0A0A,
                )
                HGap(4f)
                View {
                    attr {
                        height(18f)
                        padding(0f, 8f, 0f, 8f)
                        allCenter()
                        backgroundColor(recallPurple)
                        borderRadius(100f)
                    }
                    SDText("recall_limited_offer".tr, size = 10f, fontFamily = "Poppins-SemiBold", height = 1.2f)
                }
            }
            VGap(8f)
            View {
                attr {
                    flexDirectionRow()
                    alignItemsFlexEnd()
                }
                RichText {
                    attr {
                        fontFamily("Poppins-Bold")
                        color(UIColor.c0A0A0A)
                        lineHeight(28f)
                    }
                    Span {
                        text(if (symbol.isEmpty()) "$ " else "${symbol.trim()} ")
                        fontSize(20f)
                        fontFamily("Poppins-Bold")
                        color(UIColor.c0A0A0A)
                    }
                    Span {
                        text(amount)
                        fontSize(32f)
                        fontFamily("Poppins-Bold")
                        color(UIColor.c0A0A0A)
                    }
                }
                if (showOriginal) {
                    HGap(6f)
                    Text {
                        attr {
                            text(original)
                            fontSize(12f)
                            lineHeight(18f)
                            fontFamily("Poppins-SemiBold")
                            color(dim)
                            textDecorationLineThrough()
                            lines(1)
                        }
                    }
                }
                Spacer()
                SDText(
                    if (AppEnv.isIOS) "recall_pass_days".tr else "/${recallPeriodLabel(goods)}",
                    size = 10f,
                    fontFamily = "Poppins-SemiBold",
                    height = 18f / 10f,
                    color = dim,
                )
            }
            if (goods.points != null) {
                SDText(
                    "${goods.points} ${"credits".tr}",
                    size = 10f,
                    fontFamily = "Poppins-Medium",
                    height = 1.2f,
                    color = dim,
                )
            }
        }
    }
}

