package com.sondo.app.features.music_video.activity

import com.sondo.app.base.BasePager
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.i18n.tr
import com.sondo.app.data.api.AppConfigApi
import com.sondo.app.features.music_video.MvCreateForm
import com.sondo.app.features.music_video.MvFormBody
import com.sondo.app.features.music_video.MvSectionAnchors
import com.sondo.app.features.music_video.data.MvActivityConfig
import com.sondo.app.features.music_video.data.MvCreateBlock
import com.sondo.app.features.music_video.widgets.MusicVideoGenColors
import com.sondo.app.features.music_video.widgets.MvBottomBar
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDShimmer
import com.sondo.app.ui.input.SDUnfocus
import com.sondo.app.ui.nav.SDNavBack
import com.sondo.app.ui.video.SDVideoWidget
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.velseif
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 活动 MV 创作页（Dart presentation/pages/activity/activity_page.dart + MvActivityController）。
 * 由深链 `deep_link_value=mv_activity&activity_code=…` 打开（`DeepLinkRouter` → `Routes.mvActivity`）。
 *
 * 页面参数：`activityCode: String` —— 活动素材码，配置（`AppConfigApi.fetchMaterialData`）每次进入重新请求。
 * 不写草稿，也不写本地画幅、模式、字幕、特效偏好；没有 Ultra。
 */
@Page(Routes.mvActivity)
internal class MvActivityPage : BasePager() {
    lateinit var form: MvCreateForm
    val anchors = MvSectionAnchors()
    private var firstAppear = true

    /** 导航标题。空配置时用现有多语言标题。 */
    var navTitle by observable("")
    /** 素材底部文案。空则不占位。 */
    var copy by observable("")
    var coverUrl by observable("")
    var videoUrl by observable("")
    /** 素材加载失败后收起 1:1 区域。 */
    var heroFailed by observable(false)
    /** 配置返回前顶部用骨架占位。没有活动码时不请求，也不占位。 */
    var configLoading by observable(false)
    /** 导航底色透明度。0 透出头图，1 为页面底色。 */
    var navOpacity by observable(0f)
    var sectionOrder by observable(MvActivityConfig.defaultOrder)

    val showHero: Boolean get() = !heroFailed && (videoUrl.isNotEmpty() || coverUrl.isNotEmpty())
    val reserveTop: Boolean get() = configLoading || showHero

    override fun created() {
        super.created()
        form = MvCreateForm(this, persistPreferences = false, pageName = "/mv_activity")
        form.scrollToSection = { anchors.scrollTo(it) }
        form.scrollToBottom = { anchors.scrollToBottom() }
        form.bind()
        // 落地页没有 Ultra，也不读用户本地画幅和清晰度。
        form.lockStandardMode()
        navTitle = "music_video".tr
        navOpacity = 0f
        val code = pagerData.params.optString("activityCode", "").trim()
        if (code.isNotEmpty()) {
            // 配置返回前先占住头图位置，返回后再决定留头图还是收起。
            configLoading = true
            launch { load(code) }
        }
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        if (firstAppear) {
            firstAppear = false
            form.onFirstFrame()
            observeScroll()
        }
    }

    private fun observeScroll(retry: Int = 0) {
        if (anchors.observeScroll { onContentScroll(it) } || retry > 10) return
        setTimeout(pagerId, 50) { observeScroll(retry + 1) }
    }

    override fun pageWillDestroy() {
        // 活动页不写草稿，也不记这次的 code。
        form.unbind()
        super.pageWillDestroy()
    }

    override fun pageBackgroundColor(): Color = MusicVideoGenColors.bgPrimary

    /** 向上滑动时导航底色逐渐变深，回到顶部再透出头图。 */
    fun onContentScroll(pixels: Float) {
        if (form.isInactive()) return
        val opacity = (pixels / NAV_FADE_DISTANCE).coerceIn(0f, 1f)
        // 两端必须落到 0 和 1，中间小抖动不写，避免导航闪。
        if (opacity != 0f && opacity != 1f && kotlin.math.abs(navOpacity - opacity) < 0.01f) return
        if (navOpacity == opacity) return
        navOpacity = opacity
    }

    fun onHeroFailed() {
        if (form.isInactive()) return
        heroFailed = true
    }

    private suspend fun load(code: String) {
        val raw = AppConfigApi.fetchMaterialData(code).getOrNull()
        if (form.isInactive()) return
        configLoading = false
        if (raw == null) return
        apply(MvActivityConfig.fromJson(raw))
    }

    private fun apply(config: MvActivityConfig) {
        if (form.isInactive()) return
        navTitle = config.navTitle.ifEmpty { "music_video".tr }
        copy = config.copy
        coverUrl = config.coverUrl
        videoUrl = config.videoUrl
        heroFailed = false
        if (sectionOrder != config.order) {
            sectionOrder = config.order
            // 表单区按新顺序重建，重新挂滚动监听
            setTimeout(pagerId, 50) { observeScroll() }
        }
        if (config.audioUrl.isNotEmpty()) form.applyPresetAudio(config.audioUrl, config.audioDurationSeconds, config.audioName)
        if (config.roleImageUrls.isNotEmpty()) form.applyPresetRoleImages(config.roleImageUrls)
        if (config.description.isNotEmpty()) form.applyPresetDescription(config.description)
        if (config.harmonyTitle.isNotEmpty()) form.applyPresetTitle(config.harmonyTitle)
        form.applyPresetAspectRatio(config.aspectRatio)
        form.applyPresetQuality(config.quality)
        form.applyPresetSubtitles(config.subtitles)
    }

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            SDUnfocus {
                attr {
                    backgroundColor(MusicVideoGenColors.bgPrimary)
                    paddingBottom(ctx.safeBottom)
                }
                // 无头图时：普通导航占位在内容上方
                vif({ !ctx.reserveTop }) {
                    View {
                        attr {
                            paddingTop(ctx.statusBarHeight)
                            backgroundColor(UIColor.c0A0A0A)
                        }
                        ActivityNavBar(ctx, overlay = false)
                    }
                }
                View {
                    attr { flex(1f) }
                    // 区块顺序来自配置：顺序变化时重建表单区
                    vbind({ ctx.sectionOrder.joinToString { it.name } }) {
                        val order: List<MvCreateBlock> = ctx.sectionOrder
                        MvFormBody(ctx.form, ctx.anchors, order, ctx.safeBottom) {
                            ActivityHeader(ctx)
                        }
                    }
                    MvBottomBar(ctx.form, 0f)
                }
                // 有头图时：导航悬浮在头图上，底色随滚动加深，标题始终留在导航上
                vif({ ctx.reserveTop }) {
                    View {
                        attr {
                            absolutePosition(top = 0f, left = 0f, right = 0f)
                            paddingTop(ctx.statusBarHeight)
                        }
                        attr { backgroundColor(Color(0x0A, 0x0A, 0x0A, ctx.navOpacity)) }
                        ActivityNavBar(ctx, overlay = true)
                    }
                }
            }
        }
    }
}

private const val NAV_FADE_DISTANCE = 80f

private fun ViewContainer<*, *>.ActivityNavBar(page: MvActivityPage, overlay: Boolean) {
    View {
        attr {
            height(56f)
            justifyContentCenter()
            alignItemsCenter()
        }
        View {
            attr { absolutePosition(left = 0f, top = 4f) }
            SDNavBack()
        }
        Text {
            attr {
                if (overlay) {
                    marginLeft(48f)
                    marginRight(48f)
                    lines(1)
                    textOverFlowTail()
                }
                text(page.navTitle)
                fontSize(18f)
                fontFamily("Poppins-Bold")
                color(Color.WHITE)
            }
        }
    }
}

/** 滚动区顶部：骨架 / 1:1 头图 / 只有文案。 */
private fun ViewContainer<*, *>.ActivityHeader(page: MvActivityPage) {
    val side = page.screenWidth
    vif({ page.configLoading }) {
        // 配置返回前占住 1:1 头图
        SDShimmer {
            attr {
                size(side, side)
                baseColor = Color(255, 255, 255, 0.1f)
                highlightColor = Color(255, 255, 255, 0.15f)
            }
        }
    }
    velseif({ page.showHero }) {
        View {
            vbind({ "${page.videoUrl}|${page.coverUrl}" }) {
                MvActivityHero(page, side)
            }
        }
    }
    velse {
        vif({ page.copy.isNotEmpty() }) {
            View {
                attr { padding(8f, 16f, 0f, 16f) }
                Text {
                    attr {
                        text(page.copy)
                        fontSize(16f)
                        lineHeight(16f * 1.4f)
                        fontFamily("Poppins-Medium")
                        color(Color.WHITE)
                        lines(0)
                    }
                }
            }
        }
    }
}

/** 活动页顶部 1:1 素材（Dart create/widgets/activity_hero.dart `MvActivityHero`）。视频静音循环自动播放，失败后不占位。 */
internal fun ViewContainer<*, *>.MvActivityHero(page: MvActivityPage, side: Float) {
    val videoUrl = page.videoUrl
    val coverUrl = page.coverUrl
    val showVideo = videoUrl.isNotEmpty()
    val showImage = !showVideo && coverUrl.isNotEmpty()
    if (!showVideo && !showImage) return
    View {
        attr { size(side, side) }
        if (showVideo) {
            SDVideoWidget {
                attr {
                    absolutePositionAllZero()
                    url = videoUrl
                    this.coverUrl = coverUrl
                    volume = 0f
                    isLopper = true
                    isFill = true
                    showBottomControls = false
                    isActive = true
                    bypassPlaybackGate = true
                }
                event { onError { page.onHeroFailed() } }
            }
        } else {
            SDImage {
                attr {
                    absolutePositionAllZero()
                    url = coverUrl
                }
            }
        }
        View {
            attr {
                absolutePositionAllZero()
                touchEnable(false)
                backgroundLinearGradient(Direction.TO_BOTTOM, ColorStop(Color(0x99000000L), 0f), ColorStop(Color(0x00000000L), 0.35f))
            }
        }
        View {
            attr {
                absolutePositionAllZero()
                touchEnable(false)
                backgroundLinearGradient(Direction.TO_BOTTOM, ColorStop(Color(0x000A0A0AL), 0.61f), ColorStop(Color(0xFF0A0A0AL), 1f))
            }
        }
        vif({ page.copy.isNotEmpty() }) {
            Text {
                attr {
                    absolutePosition(left = 16f, right = 16f, bottom = 16f)
                    text(page.copy)
                    fontSize(16f)
                    lineHeight(16f * 1.4f)
                    fontFamily("Poppins-Medium")
                    color(Color.WHITE)
                    lines(0)
                }
            }
        }
    }
}
