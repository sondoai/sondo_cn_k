package com.sondo.app.features.guide

import com.sondo.app.base.BasePager
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.constants.asset
import com.sondo.app.core.constants.c
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.storage.Sp
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.Uuid
import com.sondo.app.features.guide.data.GuideApi
import com.sondo.app.nativeapi.SDAppModule
import com.sondo.app.nativeapi.SDVideo
import com.sondo.app.nativeapi.SDVideoState
import com.sondo.app.nativeapi.SDVideoView
import com.sondo.app.service.analytics.TrackingInitializer
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.service.audio.AppAudioOutputCoordinator
import com.sondo.app.ui.SDFit
import com.sondo.app.ui.anim.SDBreathing
import com.sondo.app.ui.atoms.SDImage
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 新手引导页（Dart `GuidePage` + `GuideController`）。首启时由根页面无转场压在主框架之上。
 *
 * 全屏循环播放 `assets common/guide/3.mp4`（首帧前铺封面），点任意位置结束引导：
 * 记 `isFirstOpen = false`、`isDisplayGuide = false`；AB 白名单用户（本地 splash 配置 `assignmentId > -1`）
 * 用试用创作页替换本页，否则关掉本页回到主框架。
 *
 * 页面参数：无。
 */
@Page(Routes.guide)
internal class GuidePage : BasePager() {
    /** 是否已点击开始，防重入 */
    private var isGetStarted = false

    /** 引导视频地址（随 AB 实验版本切换的分支在参考工程里已注释掉）。 */
    internal var videoUrl by observable("guide/3.mp4")

    /** 视频封面图 */
    internal var coverUrl by observable("guide/20261110_175137_bb19c33e.png")

    /** 引导页版本号，来自 SplashConfig.variantCode */
    internal var guideVersion by observable("1")

    internal var firstFrameShown by observable(false)

    internal var videoRef: ViewRef<SDVideoView>? = null
    private var videoReady = false
    private var audioClaimed = false
    private var videoPlaying = false
    private var paused = false
    private var detachAudio: (() -> Unit)? = null
    private var didRequestTracking = false

    override fun pageBackgroundColor(): Color = Color.BLACK

    override fun created() {
        super.created()
        val splashConfig = GuideApi.getSplashConfig()
        guideVersion = splashConfig.variantCode
        getOnboardingAbAssign()
        initData()
        val owner = this
        detachAudio = AppAudioOutputCoordinator.attach(
            owner,
            isPlaying = { videoPlaying },
            isAudible = { true },
            pauseForHandoff = { pauseVideo() },
        )
        launch {
            audioClaimed = AppAudioOutputCoordinator.claim(owner) >= 0
            tryPlay()
        }
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        // 与根页面共享同一条 ATT 请求，引导页再触发也只会请求一次。
        if (!didRequestTracking) {
            didRequestTracking = true
            postDelayed(16) { TrackingInitializer.initialize() }
        }
    }

    override fun pageWillDestroy() {
        detachAudio?.invoke()
        detachAudio = null
        videoRef = null
        super.pageWillDestroy()
    }

    /** 引导页是首个业务页面：返回键与参考工程一致直接退出应用（不能露出未挂载的主框架）。 */
    override fun interceptBack(): Boolean = true

    override fun onBackPressed(): Boolean {
        try {
            acquireModule<SDAppModule>(SDAppModule.MODULE_NAME).exitApp()
        } catch (e: Throwable) {
            DevLog.logger("guide exit error: $e")
        }
        return true
    }

    /** 拉取 onboarding AB 分配，白名单用户落库 assignmentId 供注册时回传 */
    private fun getOnboardingAbAssign() {
        val deviceId = Uuid.v4()
        launch {
            GuideApi.getOnboardingAbAssign(deviceId).fold(
                onSuccess = { assign ->
                    DevLog.logger("getOnboardingAbAssign result: ${assign.inWhitelist} ${assign.countryCode}")
                    if (assign.inWhitelist) {
                        // 以最新落盘的配置为底，只改 assignmentId
                        val updated = GuideApi.getSplashConfig().copy(assignmentId = assign.assignmentId)
                        GuideApi.saveSplashConfig(updated)
                    }
                },
                onFailure = { e -> DevLog.logger("getOnboardingAbAssign error: $e") },
            )
        }
    }

    private fun initData() {
        UmengUtils.logEvent("guide_start", mapOf("guideVersion" to "v4", "guidePageNum" to 1))
    }

    internal fun onVideoState(state: SDVideoState) {
        videoPlaying = state.isPlaying
        if (state.state == SDVideoState.READY && !videoReady) {
            videoReady = true
            tryPlay()
        }
    }

    private fun tryPlay() {
        if (!videoReady || !audioClaimed || paused) return
        videoRef?.view?.play()
    }

    private fun pauseVideo() {
        audioClaimed = false
        videoRef?.view?.pause()
    }

    /** 用户点击引导页任意区域：埋点、标记非首次打开并跳转 */
    internal fun onTap() {
        paused = true
        videoRef?.view?.pause()
        if (isGetStarted) return
        isGetStarted = true
        try {
            UmengUtils.logEvent("guide_end", mapOf("guide_version" to "v4"))
            Sp.setBool(StorageKey.isFirstOpen, false)
            // 后续启动不再展示引导页
            Sp.setBool(StorageKey.isDisplayGuide, false)
        } catch (e: Throwable) {
            DevLog.logger("guide_end error: $e")
        }
        val splashConfig = GuideApi.getSplashConfig()
        if (splashConfig.assignmentId > -1) {
            nav.replace(Routes.tryFreeCreate)
        } else {
            // 主框架就在下面：关掉引导页即回到首页
            nav.pop()
        }
    }

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            attr { flex(1f) }
            event { click { ctx.onTap() } }
            guideVideo(ctx)
            vif({ ctx.guideVersion == "2" }) {
                View {
                    attr {
                        absolutePosition(left = 0f, right = 0f, bottom = 0f)
                        height(320f)
                        backgroundLinearGradient(
                            Direction.TO_BOTTOM,
                            ColorStop(Color.TRANSPARENT, 0f),
                            ColorStop(Color(0, 0, 0, 211f / 255f), 1f),
                        )
                    }
                }
            }
            View {
                attr {
                    absolutePositionAllZero()
                    paddingTop(ctx.statusBarHeight)
                    justifyContentFlexEnd()
                }
                guideCaption(ctx)
            }
        }
    }
}

/** 全屏视频（cover 填满）；首帧出来之前铺同尺寸封面。 */
private fun ViewContainer<*, *>.guideVideo(ctx: GuidePage) {
    View {
        attr { absolutePositionAllZero() }
        SDVideo {
            ref { ctx.videoRef = it }
            attr {
                absolutePositionAllZero()
                src(ctx.videoUrl)
                autoPlay(false)
                loop(true)
                fitCover()
            }
            event {
                onState { ctx.onVideoState(it) }
                onFirstFrame { ctx.firstFrameShown = true }
            }
        }
        vif({ !ctx.firstFrameShown && ctx.coverUrl.isNotEmpty() }) {
            SDImage {
                attr {
                    absolutePositionAllZero()
                    url = ctx.coverUrl
                    fit = SDFit.COVER
                    placeholder = false
                    fadeIn = false
                }
            }
        }
    }
}

/** 底部文案。标题用图片是因为优设标题黑无法用现有字体还原。 */
private fun ViewContainer<*, *>.guideCaption(ctx: GuidePage) {
    // guide_title.png 1084×496，在 126 高的槽里 contain + 靠左
    val slotWidth = ctx.screenWidth - 48f
    val titleWidth = minOf(slotWidth, 126f * 1084f / 496f)
    val titleHeight = titleWidth * 496f / 1084f
    View {
        attr {
            padding(0f, 24f, 60f, 24f)
            alignItemsFlexStart()
        }
        View {
            attr {
                height(126f)
                alignSelfStretch()
                justifyContentCenter()
            }
            Image {
                attr {
                    size(titleWidth, titleHeight)
                    resizeContain()
                    src(AssetsImage.guideTitle.asset())
                }
            }
        }
        View { attr { height(50f) } }
        SDBreathing {
            attr {
                // 参考是铺满宽度的 Row 在缩放：以整行中心为原点，文字仍靠左
                alignSelfStretch()
                minScale = 1f
                maxScale = 1.06f
                periodS = 1f
            }
            content {
                View {
                    attr {
                        flexDirectionRow()
                        alignItemsCenter()
                        padding(0f, 6f, 0f, 6f)
                    }
                    Text {
                        attr {
                            text("guide_click_anywhere".tr)
                            fontSize(14f)
                            fontFamily("Poppins-Medium")
                            color(c(0xCCFFFFFFL))
                            lines(1)
                        }
                    }
                    View { attr { width(8f) } }
                    Image {
                        attr {
                            size(20f, 20f)
                            resizeContain()
                            src(AssetsImage.guideArrow.asset())
                        }
                    }
                }
            }
        }
    }
}
