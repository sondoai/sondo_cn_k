package com.sondo.app.features.music_video

import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.i18n.tr
import com.sondo.app.data.models.jsonValueOrNull
import com.sondo.app.data.models.parseJsonObjectOrNull
import com.sondo.app.features.music_video.data.MvCreateBlock
import com.sondo.app.features.music_video.description.MvDescriptionBox
import com.sondo.app.features.music_video.widgets.MusicVideoGenColors
import com.sondo.app.features.music_video.widgets.MvBottomBar
import com.sondo.app.features.music_video.widgets.MvModeSegment
import com.sondo.app.features.music_video.widgets.MvMusicSection
import com.sondo.app.features.music_video.widgets.MvOptionsPanel
import com.sondo.app.features.music_video.widgets.MvSingerSection
import com.sondo.app.features.music_video.widgets.MvTitleField
import com.sondo.app.service.MemoryCacheKeys
import com.sondo.app.service.MemoryCacheService
import com.sondo.app.service.UserStore
import com.sondo.app.ui.atoms.SDAssetImage
import com.sondo.app.ui.atoms.VGap
import com.sondo.app.ui.input.SDKeyboardAwareScroll
import com.sondo.app.ui.input.SDUnfocus
import com.sondo.app.ui.nav.SDNavBack
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * MV 创作页（Dart features/music_video/presentation/pages/create/create_page.dart + MusicVideoGenController）。
 * 从下至上弹出（`Routes.transitionOf`）。
 *
 * 页面参数（`pagerData.params`，均可选）：
 * - `song`: SongModel JSON —— 带入歌曲（优先于草稿），打开裁剪器、标题取歌名；
 * - `customStoryPlot`: String —— 「做同款」描述，只覆盖描述。
 *
 * 已在栈内时由 `WorksUi.openCreatePage` 广播 `Events.CREATE_ARGUMENTS {page: "music_video_gen", args}` 灌入同样的参数。
 * 离开页面写入表单草稿（`MemoryCacheKeys.musicVideoGenForm`），下次进入恢复。
 */
@Page(Routes.musicVideoGen)
internal class MusicVideoGenPage : BasePager() {
    lateinit var form: MvCreateForm
    val anchors = MvSectionAnchors()
    private var firstAppear = true

    override fun created() {
        super.created()
        form = MvCreateForm(this)
        form.scrollToSection = { anchors.scrollTo(it) }
        form.scrollToBottom = { anchors.scrollToBottom() }
        form.bind()
        initData(pagerData.params)
        on(Events.CREATE_ARGUMENTS) { json ->
            if (json.optString("page", "") != Routes.musicVideoGen) return@on
            initData(json.optJSONObject("args") ?: JSONObject())
        }
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        if (firstAppear) {
            firstAppear = false
            form.onFirstFrame()
        }
    }

    override fun pageWillDestroy() {
        // 离开页前写入草稿，创建成功后再进页可恢复全部参数
        saveFormDraft()
        form.unbind()
        super.pageWillDestroy()
    }

    /** 已在创作页时由首页灌入参数。路由歌曲优先于草稿。 */
    fun initData(arguments: JSONObject) {
        if (form.isInactive()) return
        val song = when (val raw = jsonValueOrNull(arguments.opt("song"))) {
            is JSONObject -> raw
            is String -> parseJsonObjectOrNull(raw)
            else -> null
        }
        if (song != null) form.applyRoutedSong(song) else restoreFormDraft()
        // 做同款只覆盖描述，避免被草稿里的旧描述盖掉
        val plot = jsonValueOrNull(arguments.opt("customStoryPlot"))
        if (plot is String) form.applyPresetDescription(plot)
        form.applyStartupMode()
    }

    private fun saveFormDraft() {
        // 登出/删号后禁止写回，避免 clearAll 被页面销毁抵消
        if (!UserStore.isLogin) return
        val snapshot = form.draftSnapshot()
        if (snapshot == null) {
            MemoryCacheService.removeForm(MemoryCacheKeys.musicVideoGenForm)
            return
        }
        MemoryCacheService.putForm(MemoryCacheKeys.musicVideoGenForm, snapshot)
    }

    private fun restoreFormDraft() {
        val draft = MemoryCacheService.getForm<JSONObject>(MemoryCacheKeys.musicVideoGenForm) ?: return
        form.restoreFields(draft)
    }

    override fun pageBackgroundColor(): Color = MusicVideoGenColors.bgPrimary

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            SDUnfocus {
                attr { backgroundColor(MusicVideoGenColors.bgPrimary) }
                Image {
                    attr {
                        absolutePosition(top = 0f, left = 0f, right = 0f)
                        height(ctx.screenWidth * 270f / 360f)
                        src(AssetsImage.musicVideoGenBg.let { com.tencent.kuikly.core.base.attr.ImageUri.commonAssets(it) })
                        resizeCover()
                    }
                }
                View {
                    attr {
                        flex(1f)
                        paddingTop(ctx.statusBarHeight)
                        paddingBottom(ctx.safeBottom)
                    }
                    MvGenAppBar(ctx)
                    vif({ ctx.form.mvFlagshipOpen }) {
                        View {
                            VGap(10f)
                            MvModeSegment(ctx.form)
                        }
                    }
                    VGap(8f)
                    View {
                        attr { flex(1f) }
                        MvFormBody(ctx.form, ctx.anchors, MvCreateBlockOrder.default, ctx.safeBottom)
                        MvBottomBar(ctx.form, 0f)
                    }
                }
            }
        }
    }
}

/** 区块顺序（普通页固定；活动页来自配置）。 */
object MvCreateBlockOrder {
    val default: List<MvCreateBlock> = listOf(
        MvCreateBlock.music,
        MvCreateBlock.role,
        MvCreateBlock.description,
        MvCreateBlock.title,
        MvCreateBlock.options,
    )
}

private fun ViewContainer<*, *>.MvGenAppBar(page: MusicVideoGenPage) {
    View {
        attr {
            height(56f)
            allCenter()
        }
        View {
            attr { absolutePosition(left = 0f, top = 4f) }
            SDNavBack()
        }
        Text {
            attr {
                text("music_video".tr)
                fontSize(18f)
                fontFamily("Poppins-Bold")
                color(Color.WHITE)
            }
        }
        vif({ page.form.mvFlagshipOpen }) {
            View {
                attr {
                    absolutePosition(right = 0f, top = 4f)
                    size(48f, 48f)
                    allCenter()
                }
                event { click { page.form.showMvModeInfo() } }
                SDAssetImage(AssetsImage.infoIcon, 24f)
            }
        }
    }
}

/** 表单滚动区（普通页 / 活动页共用）。[header] 是滚动区顶部的额外内容（活动页头图）。 */
fun ViewContainer<*, *>.MvFormBody(
    form: MvCreateForm,
    anchors: MvSectionAnchors,
    order: List<MvCreateBlock>,
    safeBottom: Float,
    header: (ViewContainer<*, *>.() -> Unit)? = null,
) {
    SDKeyboardAwareScroll {
        ref { anchors.scrollRef = it }
        attr {
            flex(1f)
            // Create 底栏：padding 20 + 48 + 20
            marginBottom = 88f
            bounces = false
        }
        content {
            header?.invoke(this)
            View {
                attr { padding(10f, 16f, 20f, 16f) }
                order.forEachIndexed { i, block ->
                    if (i > 0) VGap(12f)
                    val field = when (block) {
                        MvCreateBlock.music -> MvFormField.music
                        MvCreateBlock.role -> MvFormField.cover
                        MvCreateBlock.description -> MvFormField.description
                        MvCreateBlock.title -> MvFormField.title
                        MvCreateBlock.options -> MvFormField.author
                    }
                    View {
                        ref { anchors.refs[field] = it }
                        when (block) {
                            MvCreateBlock.music -> MvMusicSection(form)
                            MvCreateBlock.role -> MvSingerSection(form)
                            MvCreateBlock.description -> MvDescriptionBox(form)
                            MvCreateBlock.title -> MvTitleField(form)
                            MvCreateBlock.options -> MvOptionsPanel(form)
                        }
                    }
                }
                VGap(80f + safeBottom)
            }
        }
    }
}
