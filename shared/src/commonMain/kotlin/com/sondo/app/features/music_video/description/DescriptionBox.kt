package com.sondo.app.features.music_video.description

import com.sondo.app.base.BasePager
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.whiteA
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.formFieldBorderColor
import com.sondo.app.core.utils.nowMs
import com.sondo.app.features.music_video.MvCreateForm
import com.sondo.app.features.music_video.MvFormField
import com.sondo.app.features.music_video.widgets.MusicVideoGenColors
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.anim.SDRotating
import com.sondo.app.ui.atoms.HGap
import com.sondo.app.ui.atoms.SDAssetImage
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDRingProgress
import com.sondo.app.ui.atoms.Spacer
import com.sondo.app.ui.atoms.VGap
import com.sondo.app.ui.dialog.showAppBottomSheet
import com.sondo.app.ui.input.findKeyboardAwareScroll
import com.sondo.app.ui.input.hideKeyboard
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.Rotate
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.event.layoutFrameDidChange
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.InputSpan
import com.tencent.kuikly.core.views.InputSpans
import com.tencent.kuikly.core.views.LengthLimitType
import com.tencent.kuikly.core.views.PageList
import com.tencent.kuikly.core.views.PageListView
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.TextArea
import com.tencent.kuikly.core.views.TextAreaView
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.timer.setTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

// Dart: presentation/pages/create/widgets/description_box/**

private val tagColor = Color(0xFF9C70FFL)

/** 风格模板按钮上的两张固定预览图（与 Dart 一致写死）。 */
private const val stylePreviewFirst = "https://cdn.sondo.ai/4121c08fdd16390fa4e4dd2ec1bc6a915555a8d4be5f3b315977312ea898a346.jpg"
private const val stylePreviewSecond = "https://cdn.sondo.ai/d2220c3622796e7da51522005868be54b1ba8f1b10afacaab3ec462dc105d44a.jpg"

/** MV 描述框（Dart `DescriptionBox`）。数据和动作都来自 [form]。 */
fun ViewContainer<*, *>.MvDescriptionBox(form: MvCreateForm) {
    View {
        View {
            attr {
                marginLeft(4f)
                marginRight(4f)
                flexDirectionRow()
                alignItemsCenter()
            }
            Text {
                attr {
                    flex(1f)
                    text("description".tr)
                    fontSize(14f)
                    fontFamily("Poppins-SemiBold")
                    color(MusicVideoGenColors.labelText)
                }
            }
            vif({ form.description.isNotEmpty() }) {
                Text {
                    attr {
                        text("${form.description.length}/${MvCreateForm.descriptionMaxLength}")
                        fontSize(12f)
                        fontFamily("Poppins")
                        color(MusicVideoGenColors.hintText)
                    }
                }
            }
        }
        VGap(10f)
        View {
            attr {
                padding(14f)
                backgroundColor(MusicVideoGenColors.cardFill)
                borderRadius(16f)
            }
            attr {
                val err = form.invalidField == MvFormField.description
                border(Border(if (err) 1.5f else 1f, BorderStyle.SOLID, formFieldBorderColor(err, MusicVideoGenColors.cardBorder)))
            }
            addChild(MvDescriptionInputView()) { attr { this.form = form } }
            VGap(16f)
            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                }
                vif({ form.showStyleTemplate }) {
                    View {
                        attr {
                            flexDirectionRow()
                            alignItemsCenter()
                        }
                        MvStyleTemplateButton(form)
                        HGap(6f)
                    }
                }
                MvIdeasButton(form)
                Spacer()
                vif({ form.description.isNotEmpty() }) {
                    View {
                        attr {
                            flexDirectionRow()
                            alignItemsCenter()
                        }
                        View {
                            attr {
                                size(40f, 32f)
                                backgroundColor(MusicVideoGenColors.actionButtonBg)
                                borderRadius(100f)
                                allCenter()
                            }
                            event { click { if (!form.isTextOutputting) form.clearDescription() } }
                            SDAssetImage(AssetsImage.del4, 16f) { attr { opacity(0.8f) } }
                        }
                        HGap(4f)
                    }
                }
                View {
                    attr {
                        size(40f, 32f)
                        backgroundColor(MusicVideoGenColors.actionButtonBg)
                        borderRadius(100f)
                        allCenter()
                    }
                    event { click { if (!form.isStreamLoading) form.polishDescription() } }
                    vif({ form.isStreamLoading }) {
                        SDRotating {
                            attr { periodS = 0.9f }
                            content {
                                SDRingProgress {
                                    attr {
                                        size(16f, 16f)
                                        strokeWidth = 2f
                                        progress = 0.25f
                                        progressColor = Color.WHITE
                                        trackColor = Color.TRANSPARENT
                                    }
                                }
                            }
                        }
                    }
                    vif({ !form.isStreamLoading }) {
                        SDAssetImage(AssetsImage.runSe, 16f)
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.MvStyleTemplateButton(form: MvCreateForm) {
    View {
        attr {
            height(32f)
            padding(0f, 6f, 0f, 10f)
            backgroundColor(MusicVideoGenColors.actionButtonBg)
            borderRadius(100f)
            flexDirectionRow()
            alignItemsCenter()
        }
        event { click { form.openStyleTemplate() } }
        View {
            attr { size(38f, 32f) }
            View {
                attr {
                    absolutePosition(left = 2f, top = 6f)
                    transform(Rotate(-12f))
                }
                MvPreviewIcon(stylePreviewFirst)
            }
            View {
                attr { absolutePosition(left = 15.5f, top = 8f) }
                MvPreviewIcon(stylePreviewSecond)
            }
        }
        HGap(4f)
        Text {
            attr {
                text("style_template".tr)
                color(Color.WHITE)
                fontSize(10f)
                fontFamily("Poppins-SemiBold")
            }
        }
    }
}

private fun ViewContainer<*, *>.MvPreviewIcon(url: String) {
    SDImage {
        attr {
            size(21f, 28f)
            borderRadius(4f)
            border(Border(1f, BorderStyle.SOLID, whiteA(0.8f)))
            this.url = url
        }
    }
}

/** `@ Ideas` 入口（Dart `DescriptionIdeasButton`）。点开后收起键盘，列表和键盘不同时出现。 */
private fun ViewContainer<*, *>.MvIdeasButton(form: MvCreateForm) {
    View {
        attr {
            height(32f)
            padding(0f, 10f, 0f, 10f)
            borderRadius(100f)
            allCenter()
        }
        attr {
            val enabled = !form.isTextOutputting && !form.isStreamLoading
            opacity(if (enabled) 1f else 0.5f)
            // 正文里已有目录标签时用 #7F58F7 的 20% 底
            val hasTags = findIdeaTagSpans(form.description, form.ideasCatalog.names).isNotEmpty()
            backgroundColor(if (hasTags) Color(0x7F, 0x58, 0xF7, 0.2f) else MusicVideoGenColors.actionButtonBg)
        }
        event {
            click {
                if (!form.isTextOutputting && !form.isStreamLoading) form.openIdeas?.invoke(null)
            }
        }
        Text {
            attr {
                text("@ 玩法")
                color(Color.WHITE)
                fontSize(10f)
                fontFamily("Poppins-SemiBold")
            }
        }
    }
}

// ============================================================================
// 输入框：`@名称` 高亮、整块退格、同分类替换（Dart `_DescriptionInput` + `DescriptionIdeaTextController`）
// ============================================================================

internal class MvDescriptionInputAttr : ComposeAttr() {
    lateinit var form: MvCreateForm
}

/**
 * Kuikly 原生输入框不能按字形命中点击，这里用「光标落进标签内部」代替 Dart 的点击标签坐标命中：
 * 用户点到 `@名称` 上时光标会停在标签内，借 selectionChange 打开对应分类的标签列表。
 */
internal class MvDescriptionInputView : ComposeView<MvDescriptionInputAttr, ComposeEvent>() {
    private var areaRef: ViewRef<TextAreaView>? = null
    private var lastText = ""
    private var lastSignature = ""
    private var lastChangeAt = 0L
    private var spans by observable(InputSpans())
    private var myWriter: ((String, Int?) -> Unit)? = null

    /**
     * 最近由程序写进输入框的文本。原生输入框在程序写入后会异步回调 textDidChange，带回的是写入当时的文本；
     * AI 优化流式追加时回调到达已经落后好几段，若当成用户编辑：带回开头清空时的 "" 会把描述清掉，
     * 带回缺了后面 `@玩法` 的旧文本又会被当成「删除标签」把标签整块删掉。这些回声一律忽略。
     */
    private val echoes = ArrayList<String>()
    private var lastWriteAt = 0L
    private var myOpener: ((String?) -> Unit)? = null

    private val form: MvCreateForm get() = attr.form

    override fun createAttr(): MvDescriptionInputAttr = MvDescriptionInputAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun created() {
        super.created()
        lastText = form.description
        lastSignature = signature(lastText)
        spans = buildSpans(lastText)
        val writer: (String, Int?) -> Unit = { text, cursor -> applyText(text, cursor, fromUser = false) }
        val opener: (String?) -> Unit = { name -> openIdeas(name) }
        myWriter = writer
        myOpener = opener
        form.descriptionWriter = writer
        form.openIdeas = opener
    }

    override fun viewDestroyed() {
        // 区块重建时新输入框可能先于旧的创建，只摘掉自己挂的入口
        if (form.descriptionWriter === myWriter) form.descriptionWriter = null
        if (form.openIdeas === myOpener) form.openIdeas = null
        super.viewDestroyed()
    }

    private fun catalog(): DescriptionIdeaCatalog = form.ideasCatalog

    private fun signature(text: String): String =
        findIdeaTagSpans(text, catalog().names).joinToString(",") { "${it.start}-${it.end}" }

    private fun buildSpans(text: String): InputSpans {
        val out = InputSpans()
        val tags = findIdeaTagSpans(text, catalog().names)
        var cursor = 0
        fun add(seg: String, color: Color) {
            if (seg.isEmpty()) return
            out.addSpan(InputSpan().text(seg).color(color).fontSize(12f).lineHeight(16.8f))
        }
        for (span in tags) {
            if (span.start > cursor) add(text.substring(cursor, span.start), Color.WHITE)
            add(text.substring(span.start, span.end), tagColor)
            cursor = span.end
        }
        if (cursor < text.length) add(text.substring(cursor), Color.WHITE)
        return out
    }

    /** 写入文本并重设高亮与光标；[fromUser] 为用户输入后的修正（同样回写表单）。 */
    private fun applyText(text: String, cursor: Int?, fromUser: Boolean) {
        echoes.add(text)
        if (echoes.size > ECHO_HISTORY) echoes.removeAt(0)
        lastWriteAt = nowMs()
        lastText = text
        lastSignature = signature(text)
        lastChangeAt = nowMs()
        val area = areaRef?.view
        if (text.isEmpty()) {
            spans = InputSpans()
            area?.setText("")
        } else {
            spans = buildSpans(text)
        }
        val target = cursor ?: text.length
        if (area != null) setTimeout(pagerId, 16) { area.setCursorIndex(target.coerceIn(0, lastText.length)) }
        if (fromUser) form.onDescriptionInput(text)
    }

    private fun onUserText(newText: String) {
        val old = lastText
        if (newText == old) return
        // 程序写入的回声（AI 输出中输入框不可编辑，此时的回调都是回声）
        if (form.isTextOutputting || form.isStreamLoading) return
        // 回声只会紧跟在写入之后到达；过了这段时间再出现同样的文本是用户自己改的（比如优化完退格回到某个中间结果）
        if (nowMs() - lastWriteAt < ECHO_WINDOW_MS && echoes.contains(newText)) return
        // 真正的用户输入：之前的写入都已落后，清掉
        echoes.clear()
        lastChangeAt = nowMs()
        val names = catalog().names
        val expanded = expandIdeaTagDeletion(old, newText, names)
        if (expanded != null) {
            applyText(expanded.text, expanded.cursor, fromUser = true)
            return
        }
        lastText = newText
        form.onDescriptionInput(newText)
        val sig = signature(newText)
        if (names.isEmpty()) return
        areaRef?.view?.cursorIndex { cursor ->
            if (lastText != newText) return@cursorIndex
            val dropped = dropPreviousSameCategoryTag(old, newText, cursor, catalog())
            if (dropped != null) {
                applyText(dropped.text, dropped.cursor, fromUser = true)
            } else if (sig != lastSignature) {
                // 标签出现 / 消失：刷新高亮
                applyText(newText, cursor, fromUser = false)
            }
        }
    }

    private fun onSelection(start: Int, end: Int) {
        if (start != end || form.isStreamLoading || form.isTextOutputting) return
        // 刚输入或程序写入后的光标变化不算点击
        if (nowMs() - lastChangeAt < 400) return
        val span = findIdeaTagSpans(lastText, catalog().names).firstOrNull { start > it.start && start < it.end } ?: return
        openIdeas(span.name)
    }

    /** 打开标签列表；[name] 为正文里被点的标签（定位到它的分类）。 */
    fun openIdeas(name: String?) {
        val pager = getPager() as? BasePager ?: return
        if (form.isTextOutputting || form.isStreamLoading || ideasSheetOpen) return
        areaRef?.view?.blur()
        hideKeyboard()
        val current = catalog()
        val initialCategory = name?.let { current.categoryIdOf(it) }
        val text = lastText
        val area = areaRef?.view
        val proceed: (Int) -> Unit = { cursor ->
            pager.showDescriptionIdeasSheet(form, text, initialCategory) { pick ->
                if (pick == null) return@showDescriptionIdeasSheet
                val edit = applyIdeaTagSelection(lastText, cursor, pick.name, catalog(), pick.remove)
                // 超出字数上限就不写入，避免把正文撑过限制后截成半个 @名称
                if (edit.text.length > MvCreateForm.descriptionMaxLength) return@showDescriptionIdeasSheet
                applyText(edit.text, edit.cursor.takeIf { it in 0..edit.text.length } ?: edit.text.length, fromUser = true)
                // 选了标签才把光标放回正文
                setTimeout(pagerId, 32) { areaRef?.view?.focus() }
            }
        }
        if (area != null) area.cursorIndex { proceed(it) } else proceed(text.length)
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val initial = ctx.lastText
        return {
            View {
                TextArea {
                    ref { ctx.areaRef = it }
                    attr {
                        text(initial)
                        inputSpans(ctx.buildSpans(initial))
                    }
                    attr {
                        height(84f)
                        fontSize(12f)
                        lineHeight(16.8f)
                        tintColor(Color.WHITE)
                        placeholderColor(MusicVideoGenColors.hintText)
                        maxTextLength(MvCreateForm.descriptionMaxLength, LengthLimitType.CHARACTER)
                    }
                    attr {
                        val loading = ctx.form.isStreamLoading
                        color(if (loading) Color.TRANSPARENT else Color.WHITE)
                        placeholder(if (loading) "" else "mv_description_hint".tr)
                        editable(!loading && !ctx.form.isTextOutputting)
                        opacity(if (loading) 0f else 1f)
                    }
                    attr { inputSpans(ctx.spans) }
                    event {
                        textDidChange { ctx.onUserText(it.text) }
                        selectionChange { ctx.onSelection(it.selectionStart, it.selectionEnd) }
                        inputFocus { ctx.findKeyboardAwareScroll()?.ensureVisible(ctx) }
                        inputBlur { ctx.findKeyboardAwareScroll()?.clearActiveTarget(ctx) }
                        keyboardHeightChange { ctx.findKeyboardAwareScroll()?.updateKeyboardHeight(it.height) }
                    }
                }
                vif({ ctx.form.isStreamLoading }) {
                    Text {
                        attr {
                            absolutePosition(top = 0f, left = 0f, right = 0f)
                            touchEnable(false)
                            text("mv_description_thinking".tr)
                            color(Color.WHITE)
                            fontSize(12f)
                            fontFamily("Poppins")
                            lineHeight(16.8f)
                            lines(0)
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
/** 记住的程序写入次数：流式输出一次几十段，回调最多落后几段。 */
private const val ECHO_HISTORY = 64

/** 程序写入后多久内到达的同文本回调算回声（毫秒）。 */
private const val ECHO_WINDOW_MS = 1000L

// 标签列表弹层（Dart `showDescriptionIdeasSheet` / `_DescriptionIdeasSheet`）
// ============================================================================

class DescriptionIdeaPick(val name: String, val remove: Boolean)

private var ideasSheetOpen = false

private class IdeasSheetState(pager: BasePager, catalog: DescriptionIdeaCatalog, text: String, initialCategoryId: String?) {
    var categories by pager.observable(catalog.categories)
    var selectedNames by pager.observable(findIdeaTagSpans(text, catalog.names).map { it.name }.toSet())
    var categoryIndex by pager.observable(
        catalog.categories.indexOfFirst { it.id == initialCategoryId }.let { if (it < 0) 0 else it },
    )
    var retrying by pager.observable(false)
    var pageListRef: ViewRef<PageListView<*, *>>? = null
}

/** 多于一个分类时，在拖拽条下方展示分类栏。没有数据时留在弹窗里重试。 */
fun BasePager.showDescriptionIdeasSheet(
    form: MvCreateForm,
    text: String,
    initialCategoryId: String?,
    onPicked: (DescriptionIdeaPick?) -> Unit,
) {
    if (ideasSheetOpen) return
    ideasSheetOpen = true
    val pager = this
    val state = IdeasSheetState(pager, form.ideasCatalog, text, initialCategoryId)
    var result: DescriptionIdeaPick? = null
    val contentWidth = screenWidth - 40f
    showAppBottomSheet(onDismiss = {
        ideasSheetOpen = false
        onPicked(result)
    }) { handle ->
        View {
            attr { padding(0f, 20f, 0f, 20f) }
            vbind({ "${state.retrying}|${state.categories.size}|${state.categories.joinToString { it.id }}" }) {
                if (state.categories.isEmpty() || state.retrying) {
                    View {
                        attr {
                            height(352f)
                            allCenter()
                        }
                        if (state.retrying) {
                            SDRotating {
                                content {
                                    SDRingProgress {
                                        attr {
                                            size(24f, 24f)
                                            strokeWidth = 2f
                                            progress = 0.25f
                                            progressColor = Color.WHITE
                                            trackColor = Color.TRANSPARENT
                                        }
                                    }
                                }
                            }
                        } else {
                            Text {
                                attr {
                                    text("no_data".tr)
                                    fontSize(14f)
                                    fontFamily("Poppins")
                                    color(whiteA(0.5f))
                                }
                            }
                            VGap(16f)
                            View {
                                attr {
                                    padding(10f, 24f, 10f, 24f)
                                    backgroundColor(whiteA(0.15f))
                                    borderRadius(20f)
                                }
                                event {
                                    click {
                                        if (state.retrying) return@click
                                        state.retrying = true
                                        pager.launch {
                                            com.sondo.app.service.MvService.getMvIdeas()
                                            val next = form.readIdeas()
                                            form.ideasCatalog = next
                                            state.categories = next.categories
                                            state.selectedNames = findIdeaTagSpans(text, next.names).map { it.name }.toSet()
                                            state.categoryIndex = if (next.categories.isEmpty()) 0 else state.categoryIndex.coerceIn(0, next.categories.size - 1)
                                            state.retrying = false
                                        }
                                    }
                                }
                                Text {
                                    attr {
                                        text("retry".tr)
                                        color(Color.WHITE)
                                        fontSize(14f)
                                        fontFamily("Poppins-SemiBold")
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val cats = state.categories
                    // Dart _revealCategory：Scrollable.ensureVisible(alignment: 0)，把当前分类滚到可见区起点（夹紧到最大偏移）
                    var tabBarRef: ViewRef<com.tencent.kuikly.core.views.ScrollerView<*, *>>? = null
                    val tabX = FloatArray(cats.size)
                    val revealCategory = { index: Int ->
                        val bar = tabBarRef?.view
                        if (bar != null && index in tabX.indices) {
                            val maxOffset = ((bar.contentView?.frame?.width ?: 0f) - bar.frame.width).coerceAtLeast(0f)
                            bar.setContentOffset(tabX[index].coerceIn(0f, maxOffset), 0f, true)
                        }
                    }
                    // 分类栏（只有一个分类也展示名称和下划线）
                    Scroller {
                        ref { tabBarRef = it }
                        attr {
                            height(28f)
                            flexDirectionRow()
                            showScrollerIndicator(false)
                        }
                        cats.forEachIndexed { index, category ->
                            View {
                                attr {
                                    height(28f)
                                    marginLeft(if (index > 0) 4f else 0f)
                                    padding(0f, 4f, 0f, 4f)
                                    alignItemsCenter()
                                }
                                event {
                                    layoutFrameDidChange { f -> tabX[index] = f.x }
                                    click {
                                        if (index == state.categoryIndex) return@click
                                        state.categoryIndex = index
                                        state.pageListRef?.view?.scrollToPageIndex(index, true)
                                        revealCategory(index)
                                    }
                                }
                                Text {
                                    attr {
                                        height(16f)
                                        text(category.name)
                                        fontSize(14f)
                                        lineHeight(16f)
                                        val sel = state.categoryIndex == index
                                        fontFamily(if (sel) "Poppins-Medium" else "Poppins")
                                        color(if (sel) Color.WHITE else SDColors.white40)
                                    }
                                }
                                View {
                                    attr {
                                        absolutePosition(bottom = 0f)
                                        size(20f, 4f)
                                        borderRadius(100f)
                                        backgroundColor(Color(0xFF7F58F7L))
                                        opacity(if (state.categoryIndex == index) 1f else 0f)
                                        animate(Animation.easeOut(0.25f), state.categoryIndex)
                                    }
                                }
                            }
                        }
                    }
                    VGap(20f)
                    // 设计稿内容区高 352。切换分类时高度不变，短列表留空，超出再滚动。
                    if (cats.size > 1) {
                        PageList {
                            ref { state.pageListRef = it }
                            attr {
                                size(contentWidth, 352f)
                                pageDirection(true)
                                pageItemWidth(contentWidth)
                                pageItemHeight(352f)
                                defaultPageIndex(state.categoryIndex)
                                showScrollerIndicator(false)
                                keepItemAlive(true)
                            }
                            event {
                                pageIndexDidChanged {
                                    val i = (it as JSONObject).optInt("index")
                                    if (i != state.categoryIndex) state.categoryIndex = i
                                    revealCategory(i)
                                }
                            }
                            for (category in cats) {
                                View {
                                    attr { size(contentWidth, 352f) }
                                    IdeaTagList(category, state) { pick ->
                                        result = pick
                                        handle.dismiss()
                                    }
                                }
                            }
                        }
                    } else {
                        View {
                            attr { height(352f) }
                            IdeaTagList(cats.first(), state) { pick ->
                                result = pick
                                handle.dismiss()
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.IdeaTagList(
    category: DescriptionIdeaCategory,
    state: IdeasSheetState,
    onPick: (DescriptionIdeaPick) -> Unit,
) {
    Scroller {
        attr {
            flex(1f)
            showScrollerIndicator(false)
        }
        category.tags.forEachIndexed { index, tag ->
            View {
                attr {
                    if (index > 0) marginTop(16f)
                    padding(10f, 8f, 10f, 12f)
                    borderRadius(16f)
                    flexDirectionRow()
                    alignItemsCenter()
                }
                attr { backgroundColor(if (state.selectedNames.contains(tag.name)) SDColors.white5 else Color.TRANSPARENT) }
                event { click { onPick(DescriptionIdeaPick(tag.name, state.selectedNames.contains(tag.name))) } }
                View {
                    attr {
                        flex(1f)
                        padding(0f, 4f, 0f, 4f)
                    }
                    Text {
                        attr {
                            text(tag.name)
                            lines(1)
                            textOverFlowTail()
                            color(Color.WHITE)
                            fontSize(14f)
                            lineHeight(20f)
                            fontFamily("Poppins-SemiBold")
                        }
                    }
                    // 说明按实际行数撑开，空说明不留空白。
                    if (tag.description.isNotBlank()) {
                        Text {
                            attr {
                                marginTop(8f)
                                text(tag.description)
                                color(SDColors.white40)
                                fontSize(12f)
                                lineHeight(16f)
                                fontFamily("Poppins-Medium")
                                lines(0)
                            }
                        }
                    }
                }
                HGap(8f)
                View {
                    attr { size(20f, 20f) }
                    vif({ state.selectedNames.contains(tag.name) }) {
                        SDAssetImage(AssetsImage.ideaSelected, 20f)
                    }
                }
            }
        }
    }
}

/** suspend 版本：关闭后返回选择（取消 null）。 */
suspend fun BasePager.pickDescriptionIdea(form: MvCreateForm, text: String, initialCategoryId: String?): DescriptionIdeaPick? =
    suspendCoroutine { cont -> showDescriptionIdeasSheet(form, text, initialCategoryId) { cont.resume(it) } }
