package com.sondo.app.features.music_video.style_template

import com.sondo.app.base.BasePager
import com.sondo.app.base.Routes
import com.sondo.app.core.i18n.tr
import com.sondo.app.data.models.MusicStyleModel
import com.sondo.app.data.models.jsonValueOrNull
import com.sondo.app.features.music_video.widgets.MusicVideoGenColors
import com.sondo.app.service.MemoryCacheKeys
import com.sondo.app.service.MemoryCacheService
import com.sondo.app.service.MvService
import com.sondo.app.service.PlayerService
import com.sondo.app.ui.anim.SDRotating
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDMarqueeText
import com.sondo.app.ui.atoms.SDRingProgress
import com.sondo.app.ui.atoms.SDShimmer
import com.sondo.app.ui.SDWeight
import com.sondo.app.ui.atoms.VGap
import com.sondo.app.ui.nav.SDNavBack
import com.sondo.app.ui.video.SDVideoWidget
import com.sondo.app.ui.video.VideoIcon
import com.sondo.app.ui.video.VideoIconKind
import com.sondo.app.ui.video.VideoPlaybackGate
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.directives.velseif
import com.tencent.kuikly.core.directives.vforIndex
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.timer.clearTimeout
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.List
import com.tencent.kuikly.core.views.ListView
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import kotlin.math.roundToInt

/**
 * MV 风格模板选择页：全屏预览 + 底部居中吸附缩略图列表
 * （Dart presentation/pages/style_template/style_template_page.dart + MvStyleTemplateController）。
 *
 * 页面参数：`initialSelectedId: Long?` —— 进入时预选中的模板 id；不传默认第一项（会话里浏览过的位置优先）。
 *
 * 返回（`nav.pop(result)`，仅点「使用模板」时）：
 * `{"template": MusicStyleModel JSON}` —— 即字典条目 `{id, label(预览视频), value(模板 JSON 字符串), dictType, remark(webp,封面), cssClass, colorType}`，
 * 调用方用 `MusicStyleModel.fromJson(result.getJSONObject("template"))` 还原；直接返回 / 侧滑为 null。
 */
@Page(Routes.mvStyleTemplateSelect)
internal class MvStyleTemplatePage : BasePager() {
    var isLoading by observable(true)
    var items by observableList<MusicStyleModel>()
    var selectedIndex by observable(0)
    var selected by observable<MusicStyleModel?>(null)
    /** debounce 后驱动视频的模板，与 UI 选中态解耦。 */
    var previewTemplate by observable<MusicStyleModel?>(null)
    /** 为 false 时卸载视频，切换期间仅展示静态封面。 */
    var showVideoPreview by observable(false)
    var isMuted by observable(false)

    /** 快速切换时仅最后一次预览请求生效。 */
    private var previewSwitchGeneration = 0

    private val initialSelectedId: Long?
        get() = when (val v = jsonValueOrNull(pagerData.params.opt("initialSelectedId"))) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }

    override fun created() {
        super.created()
        restoreSessionUi()
        setTimeout(pagerId, 0) { VideoPlaybackGate.acquire() }
        launch { PlayerService.pause() }
        launch { loadTemplates() }
    }

    override fun pageWillDestroy() {
        VideoPlaybackGate.release()
        super.pageWillDestroy()
    }

    override fun pageBackgroundColor(): Color = MusicVideoGenColors.bgPrimary

    private fun sessionUi(): JSONObject? = MemoryCacheService.getForm<JSONObject>(MemoryCacheKeys.mvStyleTemplateUi)

    /** 读出会话内静音。 */
    private fun restoreSessionUi() {
        val draft = sessionUi() ?: return
        isMuted = draft.optBoolean("isMuted", false)
    }

    /** 写入静音与浏览位置（会话级）。 */
    private fun persistSessionUi(viewedIndex: Int? = null) {
        MemoryCacheService.putForm(
            MemoryCacheKeys.mvStyleTemplateUi,
            JSONObject().put("isMuted", isMuted).put("lastViewedIndex", viewedIndex ?: selectedIndex),
        )
    }

    /** 定位：会话浏览位置 > 已确认模板 id > 首项。 */
    private fun resolveInitialIndex(list: kotlin.collections.List<MusicStyleModel>): Int {
        val cached = sessionUi()?.let { jsonValueOrNull(it.opt("lastViewedIndex")) as? Number }?.toInt()
        if (cached != null && cached >= 0 && cached < list.size) return cached
        val initialId = initialSelectedId
        if (initialId != null) {
            val index = list.indexOfFirst { it.id == initialId }
            if (index != -1) return index
        }
        return 0
    }

    /** 拉取 MV 风格模板列表并恢复上次浏览项。 */
    private suspend fun loadTemplates() {
        isLoading = true
        if (MvService.mvTemplateList.isEmpty() && !MvService.isLoadingMvTemplate) MvService.getMvTemplateList()
        val list = MvService.mvTemplateList.toList()
        if (list.isEmpty()) {
            items.clear()
            isLoading = false
            return
        }
        val index = resolveInitialIndex(list)
        items.clear()
        items.addAll(list)
        selectedIndex = index
        selected = list[index]
        previewTemplate = list[index]
        isLoading = false
        persistSessionUi(index)
        schedulePreviewUpdate(++previewSwitchGeneration, isInitial = true)
    }

    /** UI 选中立即更新；预览视频 debounce 后再挂载。 */
    fun onTemplateSelected(index: Int) {
        if (index < 0 || index >= items.size) return
        val item = items[index]
        if (selected?.id == item.id) return
        selectedIndex = index
        selected = item
        showVideoPreview = false
        persistSessionUi(index)
        schedulePreviewUpdate(++previewSwitchGeneration)
    }

    /** 防抖 + 等旧解码器释放（Dart 500ms / 100ms + 150ms codec settle）。 */
    private fun schedulePreviewUpdate(generation: Int, isInitial: Boolean = false) {
        setTimeout(pagerId, (if (isInitial) 100 else 500) + 150) {
            if (generation != previewSwitchGeneration) return@setTimeout
            previewTemplate = selected
            showVideoPreview = true
        }
    }

    fun toggleMute() {
        isMuted = !isMuted
        persistSessionUi()
    }

    /** 确认当前模板并返回上一页。 */
    fun confirm() {
        val current = selected ?: return
        nav.pop(JSONObject().put("template", current.toJson()))
    }

    fun coverUrl(item: MusicStyleModel): String = item.imageUrl.ifEmpty { item.remark ?: "" }

    // ------------------------------------------------------------------ 尺寸

    /** 底部区：padding 20 + 缩略图 76 + 30 + 按钮 48 + 30。 */
    private val bottomAreaHeight = 20f + ThumbnailSpec.frameHeight + 30f + 48f + 30f

    /** 模板预览固定为 16:9 横屏，左右各留 20。 */
    val previewSize: Pair<Float, Float>
        get() {
            val maxW = screenWidth - 40f
            val maxH = screenHeight - statusBarHeight - safeBottom - 56f - bottomAreaHeight
            var w = maxW
            var h = w / (16f / 9f)
            if (h > maxH) {
                h = maxH
                w = h * (16f / 9f)
            }
            return w to h
        }

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    flex(1f)
                    paddingTop(ctx.statusBarHeight)
                    paddingBottom(ctx.safeBottom)
                    backgroundColor(MusicVideoGenColors.bgPrimary)
                }
                // 导航：返回 + 模板名（跑马灯）
                View {
                    attr {
                        height(56f)
                        justifyContentCenter()
                    }
                    View {
                        attr { absolutePosition(left = 0f, top = 4f) }
                        SDNavBack()
                    }
                    SDMarqueeText {
                        attr {
                            marginLeft(48f)
                            marginRight(48f)
                            text = ctx.selected?.template?.template_type ?: ""
                            size = 16f
                            fontWeight = SDWeight.W700
                            fontFamily = "Poppins"
                            color = Color.WHITE
                        }
                    }
                }
                View {
                    attr {
                        flex(1f)
                        allCenter()
                    }
                    ctx.PreviewCard(this)
                }
                ctx.BottomArea(this)
            }
        }
    }

    private fun PreviewCard(container: ViewContainer<*, *>) {
        val ctx = this
        val (w, h) = previewSize
        with(container) {
            vif({ ctx.isLoading }) {
                View {
                    attr {
                        size(w, h)
                        allCenter()
                    }
                    SDRotating {
                        content {
                            SDRingProgress {
                                attr {
                                    size(36f, 36f)
                                    strokeWidth = 4f
                                    progress = 0.25f
                                    progressColor = Color.WHITE
                                    trackColor = Color(0xFF7F58F7L)
                                }
                            }
                        }
                    }
                }
            }
            velseif({ ctx.previewTemplate == null || ctx.selected == null }) {
                View {
                    attr {
                        size(w, h)
                        allCenter()
                    }
                    Text {
                        attr {
                            text("loading".tr)
                            fontSize(14f)
                            color(Color(255, 255, 255, 0.7f))
                        }
                    }
                }
            }
            vif({ !ctx.isLoading && ctx.previewTemplate != null && ctx.selected != null }) {
                View {
                    attr {
                        size(w, h)
                        borderRadius(16f)
                    }
                    // 切换模板时封面重建并淡入
                    vbind({ ctx.selected?.id ?: 0L }) {
                        val item = ctx.selected
                        if (item != null) {
                            SDImage {
                                attr {
                                    absolutePositionAllZero()
                                    url = ctx.coverUrl(item)
                                }
                            }
                        }
                    }
                    vbind({ "${ctx.showVideoPreview}|${ctx.previewTemplate?.id ?: 0L}" }) {
                        val preview = ctx.previewTemplate
                        val sel = ctx.selected
                        if (ctx.showVideoPreview && preview != null && sel != null && preview.label.isNotEmpty()) {
                            SDVideoWidget {
                                attr {
                                    absolutePositionAllZero()
                                    url = preview.label
                                    coverUrl = ctx.coverUrl(sel)
                                    isFill = true
                                    needLoading = true
                                    bypassPlaybackGate = true
                                    radius = 16f
                                    containerWidth = w
                                    containerHeight = h
                                }
                                attr { volume = if (ctx.isMuted) 0f else 1f }
                            }
                        }
                    }
                    View {
                        attr {
                            absolutePosition(top = 12f, right = 12f)
                            size(32f, 32f)
                            borderRadius(16f)
                            backgroundColor(Color(0, 0, 0, 0.4f))
                            allCenter()
                        }
                        event { click { ctx.toggleMute() } }
                        vif({ ctx.isMuted }) { VideoIcon(VideoIconKind.VOLUME_OFF, 18f, Color(0xFFE6E6E6L)) }
                        vif({ !ctx.isMuted }) { VideoIcon(VideoIconKind.VOLUME_UP, 18f, Color(0xFFE6E6E6L)) }
                    }
                }
            }
        }
    }

    private fun BottomArea(container: ViewContainer<*, *>) {
        val ctx = this
        with(container) {
            View {
                attr {
                    padding(20f, 20f, 30f, 20f)
                    backgroundLinearGradient(
                        Direction.TO_BOTTOM,
                        ColorStop(Color(0x0A, 0x0A, 0x0A, 0f), 0.068f),
                        ColorStop(MusicVideoGenColors.bgPrimary, 0.773f),
                    )
                }
                addChild(MvStyleTemplateThumbnailPickerView()) {
                    attr {
                        page = ctx
                        viewportWidth = ctx.screenWidth - 40f
                    }
                }
                VGap(30f)
                View {
                    attr {
                        height(48f)
                        borderRadius(48f)
                        allCenter()
                        backgroundColor(if (ctx.selected == null) Color(0xF3, 0xEF, 0xF7, 0.5f) else MusicVideoGenColors.btnPrimary)
                    }
                    event { click { if (ctx.selected != null) ctx.confirm() } }
                    Text {
                        attr {
                            text("use_the_template".tr)
                            color(MusicVideoGenColors.btnPrimaryText)
                            fontSize(16f)
                            fontFamily("Poppins-SemiBold")
                        }
                    }
                }
            }
        }
    }
}

/** 缩略图尺寸（Dart `MvStyleTemplateThumbnailPicker` 常量）。 */
object ThumbnailSpec {
    const val thumbnailWidth = 54f
    const val thumbnailHeight = 72f
    const val itemGap = 10f
    const val itemExtent = thumbnailWidth + itemGap
    const val frameWidth = 58f
    const val frameHeight = 76f
    const val frameRadius = 12f
}

internal class MvThumbnailPickerAttr : com.tencent.kuikly.core.base.ComposeAttr() {
    lateinit var page: MvStyleTemplatePage
    var viewportWidth: Float = 0f
}

/**
 * 底部模板缩略图选择器：中间白色选框固定，列表滑动后吸附至框内（Dart `MvStyleTemplateThumbnailPicker`）。
 */
internal class MvStyleTemplateThumbnailPickerView :
    com.tencent.kuikly.core.base.ComposeView<MvThumbnailPickerAttr, com.tencent.kuikly.core.base.ComposeEvent>() {
    private var listRef: ViewRef<ListView<*, *>>? = null
    private var isSnapping = false
    private var snapTimer: String? = null
    private var offsetX = 0f
    private var lastSelected = -1
    private var jumped = false

    override fun createAttr(): MvThumbnailPickerAttr = MvThumbnailPickerAttr()
    override fun createEvent(): com.tencent.kuikly.core.base.ComposeEvent = com.tencent.kuikly.core.base.ComposeEvent()

    private val page: MvStyleTemplatePage get() = attr.page
    private val sidePadding: Float get() = attr.viewportWidth / 2f - ThumbnailSpec.thumbnailWidth / 2f

    override fun viewDidLoad() {
        super.viewDidLoad()
        // 外部选中变化（初次定位 / 列表加载完成）时滚到对应项
        bindValueChange({ "${page.selectedIndex}|${page.items.size}|${page.isLoading}" }) {
            if (page.isLoading || page.items.isEmpty()) return@bindValueChange
            val idx = page.selectedIndex
            if (!jumped) {
                jumped = true
                lastSelected = idx
                setTimeout(pagerId, 16) { scrollToIndex(idx, animated = false) }
                return@bindValueChange
            }
            if (idx != lastSelected && !isSnapping) scrollToIndex(idx, animated = true)
            lastSelected = idx
        }
    }

    override fun viewDestroyed() {
        unbindAllValueChange()
        snapTimer?.let { clearTimeout(it) }
        super.viewDestroyed()
    }

    private fun scrollToIndex(index: Int, animated: Boolean) {
        val n = page.items.size
        if (n == 0) return
        val target = index.coerceIn(0, n - 1) * ThumbnailSpec.itemExtent
        listRef?.view?.setContentOffset(target, 0f, animated)
    }

    private fun indexFromOffset(offset: Float): Int {
        val n = page.items.size
        if (n == 0) return 0
        return (offset / ThumbnailSpec.itemExtent).roundToInt().coerceIn(0, n - 1)
    }

    /** 滚动停止 300ms 后吸附到最近一项。 */
    private fun snapToNearest() {
        if (page.items.isEmpty() || isSnapping) return
        snapTimer?.let { clearTimeout(it) }
        snapTimer = setTimeout(pagerId, 300) {
            if (page.items.isEmpty() || isSnapping) return@setTimeout
            isSnapping = true
            val index = indexFromOffset(offsetX)
            if (index != page.selectedIndex) page.onTemplateSelected(index)
            lastSelected = index
            scrollToIndex(index, animated = true)
            setTimeout(pagerId, 240) { isSnapping = false }
        }
    }

    private fun onItemTap(index: Int) {
        if (isSnapping) return
        isSnapping = true
        page.onTemplateSelected(index)
        lastSelected = index
        scrollToIndex(index, animated = true)
        setTimeout(pagerId, 240) { isSnapping = false }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { height(ThumbnailSpec.frameHeight) }
            List {
                ref { ctx.listRef = it }
                attr {
                    absolutePositionAllZero()
                    flexDirectionRow()
                    showScrollerIndicator(false)
                    bouncesEnable(false)
                }
                event {
                    scroll { ctx.offsetX = it.offsetX }
                    scrollEnd {
                        ctx.offsetX = it.offsetX
                        ctx.snapToNearest()
                    }
                }
                View { attr { width(ctx.sidePadding) } }
                vif({ ctx.page.isLoading }) {
                    View {
                        attr { flexDirectionRow() }
                        for (i in 0 until 7) {
                            View {
                                attr {
                                    width(ThumbnailSpec.itemExtent)
                                    height(ThumbnailSpec.frameHeight)
                                    justifyContentCenter()
                                }
                                SDShimmer {
                                    attr {
                                        size(ThumbnailSpec.thumbnailWidth, ThumbnailSpec.thumbnailHeight)
                                        radius = 9f
                                        baseColor = Color(255, 255, 255, 0.2f)
                                        highlightColor = Color(255, 255, 255, 0.3f)
                                    }
                                }
                            }
                        }
                    }
                }
                vforIndex({ ctx.page.items }) { item, index, _ ->
                    View {
                        attr {
                            width(ThumbnailSpec.itemExtent)
                            height(ThumbnailSpec.frameHeight)
                            justifyContentCenter()
                        }
                        event { click { ctx.onItemTap(index) } }
                        SDImage {
                            attr {
                                size(ThumbnailSpec.thumbnailWidth, ThumbnailSpec.thumbnailHeight)
                                borderRadius(9f)
                                url = ctx.page.coverUrl(item)
                            }
                        }
                    }
                }
                // 末项能滚到中间：右侧补 sidePadding（扣掉最后一项的间距）
                View { attr { width(ctx.sidePadding - ThumbnailSpec.itemGap) } }
            }
            // 固定在中间的白色选框
            View {
                attr {
                    absolutePosition(top = 0f, left = ctx.attr.viewportWidth / 2f - ThumbnailSpec.frameWidth / 2f)
                    size(ThumbnailSpec.frameWidth, ThumbnailSpec.frameHeight)
                    borderRadius(ThumbnailSpec.frameRadius)
                    border(Border(1f, BorderStyle.SOLID, Color.WHITE))
                    touchEnable(false)
                }
            }
        }
    }
}
