package com.sondo.app.ui.works.upload

import com.sondo.app.base.BasePager
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.i18n.tr
import com.sondo.app.data.models.MusicStyleModel
import com.sondo.app.data.models.MvTagLabelModel
import com.sondo.app.service.MvService
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDLoading
import com.sondo.app.ui.dialog.showAppBottomSheet
import com.sondo.app.ui.overlay.DialogHandle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vforIndex
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.velseif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.List
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.sondo.app.base.Events

// ============================================================================
// ShotTagTextController（Dart shared/widgets/ui_core/shot_tag_text_controller.dart）
// ============================================================================

/**
 * `@TagName` 分镜标签文本工具（Dart `ShotTagTextController` 的纯逻辑部分）。
 * Kuikly 输入框不支持富文本着色：[highlight] 给出分段供 RichText 展示（标签色 #9C70FF），
 * [adjustForTagDeletion] 在输入框 onChanged 里调用实现「删一半标签 = 整个删掉」，[insertTag] 是各页 `_insertShotTag` 的插入规则。
 */
object ShotTagTextController {
    val tagColor: Color = Color(0xFF9C70FFL)

    /** 按 `MvService.mvShotTagNames` 现建正则（长名优先；负向前瞻避免短名命中长名的前缀）。 */
    private fun tagRegex(): Regex? {
        val names = MvService.mvShotTagNames.filter { it.isNotEmpty() }.sortedByDescending { it.length }
        if (names.isEmpty()) return null
        return Regex("@(" + names.joinToString("|") { Regex.escape(it) } + ")(?![\\w'-])")
    }

    /** 分段：(文本, 是否标签)。 */
    fun highlight(src: String): kotlin.collections.List<Pair<String, Boolean>> {
        if (src.isEmpty()) return listOf(src to false)
        val regex = tagRegex() ?: return listOf(src to false)
        val out = ArrayList<Pair<String, Boolean>>()
        var cursor = 0
        for (m in regex.findAll(src)) {
            if (m.range.first > cursor) out.add(src.substring(cursor, m.range.first) to false)
            out.add(m.value to true)
            cursor = m.range.last + 1
        }
        if (cursor < src.length) out.add(src.substring(cursor) to false)
        return if (out.isEmpty()) listOf(src to false) else out
    }

    /**
     * 删除只删掉了某个标签的一部分时，扩展成删除整个标签。
     * @return (调整后的文本, 光标位置)；不需要调整时 null
     */
    fun adjustForTagDeletion(oldText: String, newText: String): Pair<String, Int>? {
        if (newText.length >= oldText.length) return null
        val regex = tagRegex() ?: return null
        var pre = 0
        while (pre < newText.length && pre < oldText.length && newText[pre] == oldText[pre]) pre++
        var suf = 0
        while (suf < newText.length - pre && suf < oldText.length - pre &&
            newText[newText.length - 1 - suf] == oldText[oldText.length - 1 - suf]
        ) {
            suf++
        }
        val delStart = pre
        val delEnd = oldText.length - suf
        for (m in regex.findAll(oldText)) {
            val start = m.range.first
            val end = m.range.last + 1
            val overlaps = end > delStart && start < delEnd
            val fullyContained = delStart <= start && delEnd >= end
            if (overlaps && !fullyContained) {
                return (oldText.substring(0, start) + oldText.substring(end)) to start
            }
        }
        return null
    }

    /**
     * 在 [cursor]（无效时追加到末尾）插入标签，前后不是空格时补空格。
     * @return (新文本, 新光标位置)
     */
    fun insertTag(text: String, tag: String, cursor: Int = -1): Pair<String, Int> {
        val insertPos = if (cursor in 0..text.length) cursor else text.length
        val before = text.substring(0, insertPos)
        val after = text.substring(insertPos)
        val spaceBefore = if (before.isNotEmpty() && !before.endsWith(" ")) " " else ""
        val spaceAfter = if (after.isNotEmpty() && !after.startsWith(" ")) " " else ""
        val inserted = "$spaceBefore$tag$spaceAfter"
        return "$before$inserted$after" to insertPos + inserted.length
    }
}

// ============================================================================
// DialogShotTag（Dart shared/widgets/dialog_shot_tag.dart）
// ============================================================================

internal class DialogShotTagAttr : ComposeAttr() {
    var onSelect: ((String) -> Unit)? = null
    var onClose: (() -> Unit)? = null
}

/** 分镜标签选择面板：分类 Tab + 3 列图片卡片；标签表从 `MvService` 读取，`Events.MV_DICT_CHANGED{kind:"tags"}` 时刷新。 */
internal class DialogShotTagView : ComposeView<DialogShotTagAttr, ComposeEvent>() {
    private var selectedCategoryIndex by observable(0)
    private var isLoading by observable(MvService.isLoadingMvTags)
    private val tagList by observableList<MvTagLabelModel>()
    private var tagMap: Map<String, kotlin.collections.List<MusicStyleModel>> = emptyMap()
    private val items by observableList<MusicStyleModel>()

    override fun createAttr(): DialogShotTagAttr = DialogShotTagAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun created() {
        super.created()
        sync()
        (getPager() as BasePager).on(Events.MV_DICT_CHANGED) { json ->
            if (json.optString("kind", "") == "tags") sync()
        }
    }

    private fun sync() {
        isLoading = MvService.isLoadingMvTags
        tagMap = MvService.mvTagMap.mapValues { it.value.toList() }
        tagList.clear()
        tagList.addAll(MvService.mvTagList)
        if (selectedCategoryIndex >= tagList.size) selectedCategoryIndex = 0
        refreshItems()
    }

    private fun selectCategory(index: Int) {
        selectedCategoryIndex = index
        refreshItems()
    }

    private fun refreshItems() {
        items.clear()
        items.addAll(itemsOfSelected())
    }

    private fun itemsOfSelected(): kotlin.collections.List<MusicStyleModel> {
        val cat = tagList.getOrNull(selectedCategoryIndex) ?: return emptyList()
        return tagMap[cat.id] ?: emptyList()
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val screenW = ctx.pagerData.pageViewWidth
        val cardSize = kotlin.math.floor(screenW / 3f) - 20f
        return {
            attr { paddingTop(4f) }
            // 标题
            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                    justifyContentSpaceBetween()
                    padding(0f, 20f, 0f, 20f)
                }
                Text {
                    attr {
                        text("Tag")
                        fontSize(16f)
                        fontFamily("Poppins-SemiBold")
                        letterSpacing(0.016f)
                        color(Color.WHITE)
                    }
                }
                Image {
                    attr {
                        size(26f, 26f)
                        src(ImageUri.commonAssets(AssetsImage.close))
                        tintColor(Color.WHITE)
                    }
                    event { click { ctx.attr.onClose?.invoke() } }
                }
            }
            vif({ ctx.isLoading }) {
                View {
                    attr {
                        marginTop(20f)
                        height(452f)
                        allCenter()
                    }
                    SDLoading { attr { size = 24f } }
                }
            }
            velseif({ ctx.tagList.isEmpty() }) {
                View {
                    attr {
                        marginTop(20f)
                        height(452f)
                        allCenter()
                    }
                    Text {
                        attr {
                            text("no_data".tr)
                            fontSize(14f)
                            color(SDColors.white50)
                        }
                    }
                    View {
                        attr {
                            marginTop(16f)
                            padding(10f, 24f, 10f, 24f)
                            borderRadius(20f)
                            backgroundColor(Color(255, 255, 255, 0.15f))
                        }
                        event {
                            click {
                                ctx.isLoading = true
                                com.sondo.app.base.AppHost.launch { MvService.getMvTagsList() }
                            }
                        }
                        Text {
                            attr {
                                text("retry".tr)
                                fontSize(14f)
                                fontFamily("Poppins-SemiBold")
                                color(Color.WHITE)
                            }
                        }
                    }
                }
            }
            velse {
                View {
                    attr { marginTop(20f) }
                    List {
                        attr {
                            height(32f)
                            flexDirectionRow()
                            showScrollerIndicator(false)
                        }
                        vforIndex({ ctx.tagList }) { cat, index, count ->
                            View {
                                attr {
                                    height(32f)
                                    marginLeft(if (index == 0) 20f else 8f)
                                    marginRight(if (index == count - 1) 20f else 0f)
                                    padding(4f, 12f, 4f, 12f)
                                    borderRadius(29f)
                                    allCenter()
                                    val sel = index == ctx.selectedCategoryIndex
                                    backgroundColor(if (sel) SDColors.white20 else SDColors.white5)
                                }
                                event { click { ctx.selectCategory(index) } }
                                Text {
                                    attr {
                                        text(cat.label)
                                        fontSize(12f)
                                        fontFamily("Poppins-SemiBold")
                                        color(if (index == ctx.selectedCategoryIndex) Color.WHITE else SDColors.white40)
                                    }
                                }
                            }
                        }
                    }
                    Scroller {
                        attr {
                            marginTop(12f)
                            height(440f)
                            showScrollerIndicator(false)
                        }
                        // Dart Wrap(spacing: 10, runSpacing: 10)：间距只在卡片之间。这里每张卡右边都带 10，
                        // 换行容器多给 10 宽（屏宽 - 左右 20 + 10）抵掉行尾那份，一行正好 3 张；
                        // 宽度要显式给：Scroller 内容不会自动撑满横向
                        View {
                            attr {
                                marginLeft(20f)
                                width(screenW - 40f + 10f)
                                flexDirectionRow()
                                flexWrapWrap()
                            }
                            vfor({ ctx.items }) { item ->
                                View {
                                    attr {
                                        size(cardSize, cardSize)
                                        marginRight(10f)
                                        marginBottom(10f)
                                        borderRadius(12f)
                                        overflow(true)
                                        backgroundColor(Color(255, 255, 255, 0.08f))
                                    }
                                    event {
                                        click {
                                            val r = item.remark
                                            val tag = if (!r.isNullOrEmpty()) r else item.value
                                            ctx.attr.onSelect?.invoke(tag)
                                            ctx.attr.onClose?.invoke()
                                        }
                                    }
                                    SDImage {
                                        attr {
                                            absolutePositionAllZero()
                                            url = item.colorType
                                        }
                                    }
                                    // 底部渐变（Alignment(0, 0.765) → bottom：高度的后 11.75%）
                                    View {
                                        attr {
                                            absolutePosition(top = cardSize * 0.8825f, left = 0f, right = 0f, bottom = 0f)
                                            backgroundLinearGradient(
                                                Direction.TO_BOTTOM,
                                                ColorStop(Color.TRANSPARENT, 0f),
                                                ColorStop(Color(0xCC000000L), 1f),
                                            )
                                        }
                                    }
                                    View {
                                        attr {
                                            absolutePosition(left = 0f, right = 0f, bottom = 0f)
                                            padding(4f, 6f, 4f, 6f)
                                        }
                                    Text {
                                        attr {
                                            text(item.value)
                                            fontSize(10f)
                                            fontFamily("Poppins-Medium")
                                            letterSpacing(0.01f)
                                            color(Color.WHITE)
                                            lines(1)
                                            textOverFlowTail()
                                        }
                                    }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dart `Get.bottomSheet(DialogShotTag(onSelect:, tagList:, tagMap:))`：分镜标签面板（顶部圆角 16、#1C1C1C、上内边距 20）。
 * 点卡片：回调 `remark`（非空）否则 `value`，然后关闭。标签表为空时调用方应先触发 `MvService.getMvTagsList()`（同 Dart）。
 */
fun BasePager.showDialogShotTag(onSelect: (String) -> Unit): DialogHandle = showAppBottomSheet(
    showHandle = false,
    enableDrag = false,
    topRadius = 16f,
    maskColor = Color(0, 0, 0, 0.54f),
) { handle ->
    View {
        attr { paddingTop(16f) }
        addChild(DialogShotTagView()) {
            attr {
                this.onSelect = onSelect
                this.onClose = { handle.dismiss() }
            }
        }
    }
}
