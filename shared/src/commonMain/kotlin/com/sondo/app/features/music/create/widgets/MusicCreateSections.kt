package com.sondo.app.features.music.create.widgets

import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.whiteA
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.formFieldBorderColor
import com.sondo.app.features.music.create.MusicCreatePage
import com.sondo.app.features.music.create.MusicFormField
import com.sondo.app.ui.atoms.SDLoading
import com.sondo.app.ui.atoms.SDShimmer
import com.sondo.app.ui.atoms.SDSwitch
import com.sondo.app.ui.input.SDInput
import com.sondo.app.ui.input.SDTextArea
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.base.event.layoutFrameDidChange
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.velseif
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.List
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 带标签的输入卡片（Dart `MusicLabeledInputCard`）：简单模式是描述，自定义模式是歌词。
 * 自定义模式的歌词区只读，点击打开全屏歌词编辑。
 */
internal fun ViewContainer<*, *>.MusicLabeledInputCard(ctx: MusicCreatePage, isSimple: Boolean) {
    val maxLen = if (isSimple) 200 else 3000
    val minHeight = if (isSimple) 200f else 120f
    val field = if (isSimple) MusicFormField.description else MusicFormField.lyrics
    val curText: () -> String = { if (isSimple) ctx.description else ctx.lyrics }
    val shuffleLoading: () -> Boolean = { if (isSimple) ctx.isSimpleShuffleLoading else ctx.isLyricsShuffleLoading }
    // 纯音乐时歌词仍保留，字数暂不展示，关闭后恢复
    val disabled: () -> Boolean = { ctx.isInstrumental && !isSimple }
    View {
        if (!isSimple) {
            event { layoutFrameDidChange { ctx.sectionY[MusicFormField.lyrics] = it.y } }
        }
        // 标题行：标签 + 字数
        View {
            attr {
                padding(0f, 4f, 0f, 4f)
                flexDirectionRow()
                alignItemsCenter()
            }
            Text {
                attr {
                    flex(1f)
                    text(if (isSimple) "description".tr else "lyrics".tr)
                    fontSize(14f)
                    fontFamily(PoppinsFace.semiBold)
                    color(MusicGenColors.labelText)
                }
            }
            vif({ !disabled() && curText().isNotEmpty() }) {
                Text {
                    attr {
                        text("${curText().length}/$maxLen")
                        fontSize(12f)
                        fontFamily(PoppinsFace.regular)
                        color(MusicGenColors.counterText)
                    }
                }
            }
        }
        View { attr { height(10f) } }
        View {
            attr {
                padding(14f)
                backgroundColor(MusicGenColors.cardFill)
                borderRadius(16f)
                val err = ctx.invalidField == field
                border(Border(if (err) 1.5f else 1f, BorderStyle.SOLID, formFieldBorderColor(err, MusicGenColors.cardBorder)))
            }
            View {
                attr { height(minHeight) }
                vif({ disabled() }) {
                    // 纯音乐：藏起歌词文本，只展示无需歌词提示
                    Text {
                        attr {
                            text("pure_music_does_not_require_input_of_lyrics".tr)
                            fontSize(12f)
                            fontFamily(PoppinsFace.regular)
                            color(MusicGenColors.hintText)
                        }
                    }
                }
                velse {
                    if (isSimple) {
                        SDTextArea {
                            attr {
                                text = ctx.description
                                placeholder = "express_your_feelings".tr
                                enabled = !ctx.isSimpleShuffleLoading
                                areaHeight = minHeight
                                radius = 0f
                                fillColor = Color.TRANSPARENT
                                fontSize = 12f
                                hintColor = MusicGenColors.hintText
                                maxLength = 200
                                showCounter = false
                                contentPadding = 0f
                            }
                            event { onChanged { ctx.setDescription(it) } }
                        }
                    } else {
                        // 歌词只读，点击进入全屏编辑
                        Scroller {
                            attr {
                                height(minHeight)
                                showScrollerIndicator(false)
                            }
                            event { click { ctx.onLyricsInput() } }
                            Text {
                                attr {
                                    val empty = ctx.lyrics.isEmpty()
                                    text(if (empty) "please_enter_song_lyrics".tr else ctx.lyrics)
                                    fontSize(12f)
                                    fontFamily(if (empty) PoppinsFace.regular else PoppinsFace.medium)
                                    color(if (empty) MusicGenColors.hintText else Color.WHITE)
                                }
                            }
                        }
                    }
                }
            }
            View { attr { height(16f) } }
            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                    justifyContentSpaceBetween()
                }
                // 纯音乐开关
                View {
                    attr {
                        height(32f)
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    SDSwitch {
                        attr {
                            marginLeft(-4f)
                            scale = 0.6f
                            compact = true
                            value = ctx.isInstrumental
                            activeTrackColor = Color(0xFF6A4DFFL)
                        }
                        event { onChanged { v -> if (!shuffleLoading()) ctx.onInstrumental(v) } }
                    }
                    Text {
                        attr {
                            marginLeft(11f)
                            text("instrumental".tr)
                            fontSize(10f)
                            fontFamily(PoppinsFace.medium)
                            color(MusicGenColors.secondaryText)
                        }
                    }
                }
                View {
                    attr {
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    // 有内容才显示清空；纯音乐时歌词已藏起，不展示
                    vif({ !disabled() && curText().isNotEmpty() }) {
                        View {
                            attr {
                                flexDirectionRow()
                                alignItemsCenter()
                            }
                            MusicActionChip(AssetsImage.del4, withLabel = false, loading = { false }) {
                                if (!shuffleLoading()) {
                                    if (isSimple) ctx.setDescription("") else ctx.setLyrics("")
                                }
                            }
                            View { attr { width(8f) } }
                        }
                    }
                    if (!isSimple) {
                        MusicActionChip(AssetsImage.runSe, withLabel = true, loading = { false }) {
                            if (!shuffleLoading()) ctx.onRefinementPrompt()
                        }
                        View { attr { width(8f) } }
                    }
                    MusicActionChip(AssetsImage.shuffle2, withLabel = false, loading = shuffleLoading) {
                        if (!shuffleLoading()) {
                            if (isSimple) ctx.getMusicSimplePromptList() else ctx.createLyrics()
                        }
                    }
                }
            }
        }
    }
}

/** 卡片底部的圆形操作按钮（Dart `_ActionChip`）。 */
internal fun ViewContainer<*, *>.MusicActionChip(
    icon: String,
    withLabel: Boolean,
    loading: () -> Boolean,
    onTap: () -> Unit,
) {
    View {
        attr {
            height(32f)
            // 纯图标用固定宽度：鸿蒙渲染器上 minWidth + 左右 padding 会把图标挤到右边缘被裁掉
            if (withLabel) padding(0f, 14f, 0f, 14f) else width(40f)
            backgroundColor(MusicGenColors.actionButtonBg)
            borderRadius(100f)
            flexDirectionRow()
            allCenter()
        }
        event { click { onTap() } }
        vif({ loading() }) {
            SDLoading { attr { size = 16f } }
        }
        velse {
            Image {
                attr {
                    size(16f, 16f)
                    src(ImageUri.commonAssets(icon))
                    resizeContain()
                }
            }
        }
    }
}

/** 自定义模式滚动区内容：歌词 / 风格 / 人声 / 标题。 */
internal fun ViewContainer<*, *>.MusicCustomSections(ctx: MusicCreatePage) {
    MusicLabeledInputCard(ctx, isSimple = false)
    View { attr { height(12f) } }
    MusicStyleSection(ctx)
    View { attr { height(12f) } }
    MusicVocalGenderRow(ctx)
    View { attr { height(12f) } }
    MusicTitleField(ctx)
    View { attr { height(80f + ctx.safeBottom) } }
}

/** 标题行：左标签，右侧清空图标 + 字数（Dart `MusicLabelClearCounter`）。 */
private fun ViewContainer<*, *>.MusicLabelClearCounter(
    label: String,
    maxLen: Int,
    curText: () -> String,
    onClear: () -> Unit,
) {
    View {
        attr {
            padding(0f, 4f, 0f, 4f)
            flexDirectionRow()
            alignItemsCenter()
        }
        Text {
            attr {
                flex(1f)
                text(label)
                fontSize(14f)
                fontFamily(PoppinsFace.semiBold)
                color(MusicGenColors.labelText)
            }
        }
        vif({ curText().isNotEmpty() }) {
            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                }
                Image {
                    attr {
                        size(18f, 18f)
                        src(ImageUri.commonAssets(AssetsImage.inputClear))
                        resizeContain()
                    }
                    event { click { onClear() } }
                }
                Text {
                    attr {
                        marginLeft(8f)
                        text("${curText().length}/$maxLen")
                        fontSize(12f)
                        fontFamily(PoppinsFace.regular)
                        color(MusicGenColors.counterText)
                    }
                }
            }
        }
    }
}

/** 自定义模式风格选择与输入区（Dart `MusicStyleSection`）。 */
private fun ViewContainer<*, *>.MusicStyleSection(ctx: MusicCreatePage) {
    View {
        event { layoutFrameDidChange { ctx.sectionY[MusicFormField.style] = it.y } }
        MusicLabelClearCounter("style".tr, MusicCreatePage.styleMaxLength, { ctx.style }) { ctx.clearStyle() }
        View { attr { height(10f) } }
        View {
            attr {
                padding(14f)
                backgroundColor(MusicGenColors.cardFill)
                borderRadius(16f)
                val err = ctx.invalidField == MusicFormField.style
                border(Border(if (err) 1.5f else 1f, BorderStyle.SOLID, formFieldBorderColor(err, MusicGenColors.cardBorder)))
            }
            SDTextArea {
                attr {
                    text = ctx.style
                    placeholder = "enter_style_here".tr
                    areaHeight = 54f
                    radius = 0f
                    fillColor = Color.TRANSPARENT
                    fontSize = 12f
                    hintColor = MusicGenColors.hintText
                    maxLength = MusicCreatePage.styleMaxLength
                    showCounter = false
                    contentPadding = 0f
                }
                event { onChanged { ctx.setStyle(it) } }
            }
            View { attr { height(16f) } }
            vif({ ctx.isStyleListLoading }) {
                SDShimmer {
                    attr {
                        height(28f)
                        radius = 55f
                        baseColor = MusicGenColors.chipUnselected
                        highlightColor = whiteA(0.24f)
                    }
                }
            }
            velseif({ ctx.styleList.isEmpty() }) {
                Text {
                    attr {
                        text("retry".tr)
                        fontSize(12f)
                        color(MusicGenColors.hintText)
                    }
                    event { click { ctx.getMusicStyleList() } }
                }
            }
            velse {
                View {
                    attr {
                        height(28f)
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    // 随机风格
                    View {
                        attr {
                            size(38f, 28f)
                            backgroundColor(MusicGenColors.chipUnselected)
                            borderRadius(55f)
                            allCenter()
                        }
                        event { click { if (!ctx.isStyleShuffleLoading) ctx.getMusicStylePrompt() } }
                        vif({ ctx.isStyleShuffleLoading }) {
                            SDLoading { attr { size = 16f } }
                        }
                        velse {
                            Image {
                                attr {
                                    size(16f, 16f)
                                    src(ImageUri.commonAssets(AssetsImage.shuffle2))
                                    resizeContain()
                                }
                            }
                        }
                    }
                    View { attr { width(8f) } }
                    List {
                        attr {
                            flex(1f)
                            height(28f)
                            flexDirectionRow()
                            showScrollerIndicator(false)
                        }
                        vfor({ ctx.styleList }) { item ->
                            View {
                                attr {
                                    height(28f)
                                    // 参考 ListView.separated：只在两项之间留 8
                                    if (ctx.styleList.firstOrNull() !== item) marginLeft(8f)
                                    padding(0f, 12f, 0f, 12f)
                                    borderRadius(55f)
                                    allCenter()
                                    val selected = ctx.selectedStyle.contains(item.value)
                                    backgroundColor(if (selected) MusicGenColors.btnPrimary else MusicGenColors.chipUnselected)
                                }
                                event { click { ctx.toggleStyleChip(item.value) } }
                                Text {
                                    attr {
                                        val selected = ctx.selectedStyle.contains(item.value)
                                        text(item.value)
                                        fontSize(12f)
                                        fontFamily(if (selected) PoppinsFace.semiBold else PoppinsFace.medium)
                                        color(if (selected) MusicGenColors.chipSelectedText else MusicGenColors.labelText2)
                                        lines(1)
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

/** 人声性别选择行（随机 / 男 / 女）（Dart `MusicVocalGenderRow`）。 */
private fun ViewContainer<*, *>.MusicVocalGenderRow(ctx: MusicCreatePage) {
    val options = listOf("r" to "random".tr, "m" to "male".tr, "f" to "female".tr)
    View {
        Text {
            attr {
                margin(0f, 4f, 0f, 4f)
                text("select_vocal_gender".tr)
                fontSize(14f)
                fontFamily(PoppinsFace.semiBold)
                color(MusicGenColors.labelText)
            }
        }
        View { attr { height(10f) } }
        View {
            attr { flexDirectionRow() }
            for ((value, label) in options) {
                View {
                    attr {
                        flex(1f)
                        height(42f)
                        if (value != "f") marginRight(8f)
                        allCenter()
                        backgroundColor(MusicGenColors.chipUnselected)
                        borderRadius(10f)
                        val selected = ctx.vocal == value
                        border(Border(1f, BorderStyle.SOLID, if (selected) Color.WHITE else Color.TRANSPARENT))
                    }
                    event { click { ctx.onVocalSelected(value) } }
                    Text {
                        attr {
                            text(label)
                            fontSize(12f)
                            fontFamily(PoppinsFace.medium)
                            color(if (ctx.vocal == value) MusicGenColors.labelText2 else MusicGenColors.secondaryText)
                        }
                    }
                }
            }
        }
    }
}

/** 歌曲标题输入框（Dart `MusicTitleField`）。 */
private fun ViewContainer<*, *>.MusicTitleField(ctx: MusicCreatePage) {
    View {
        event { layoutFrameDidChange { ctx.sectionY[MusicFormField.title] = it.y } }
        attr {
            height(50f)
            padding(0f, 14f, 0f, 14f)
            flexDirectionRow()
            alignItemsCenter()
            backgroundColor(MusicGenColors.cardFill)
            borderRadius(16f)
            val err = ctx.invalidField == MusicFormField.title
            border(Border(if (err) 1.5f else 1f, BorderStyle.SOLID, formFieldBorderColor(err, MusicGenColors.cardBorder)))
        }
        SDInput {
            attr {
                flex(1f)
                height(40f)
                text = ctx.title
                placeholder = "enter_title".tr
                fontSize = 12f
                hintColor = MusicGenColors.hintText
                maxLength = MusicCreatePage.titleMaxLength
                contentPaddingH = 0f
            }
            event { onChanged { ctx.setTitle(it) } }
        }
        // 无内容时不展示字数与清空
        vif({ ctx.title.isNotEmpty() }) {
            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                }
                Image {
                    attr {
                        size(18f, 18f)
                        src(ImageUri.commonAssets(AssetsImage.inputClear))
                        resizeContain()
                    }
                    event { click { ctx.setTitle("") } }
                }
                Text {
                    attr {
                        marginLeft(8f)
                        text("${ctx.title.length}/${MusicCreatePage.titleMaxLength}")
                        fontSize(12f)
                        fontFamily(PoppinsFace.regular)
                        color(MusicGenColors.counterText)
                    }
                }
            }
        }
    }
}

/** 底部渐变 + 创作按钮（Dart `_buildBottomBar` + `MusicCreateButton`）。 */
internal fun ViewContainer<*, *>.MusicCreateBottomBar(ctx: MusicCreatePage) {
    View {
        attr {
            absolutePosition(left = 0f, right = 0f, bottom = 0f)
            padding(10f, 16f, 4f + ctx.safeBottom, 16f)
            backgroundLinearGradient(
                Direction.TO_BOTTOM,
                ColorStop(Color(0x000A0A0AL), 0.068f),
                ColorStop(MusicGenColors.bgPrimary, 0.773f),
            )
        }
        View {
            attr {
                height(48f)
                backgroundColor(MusicGenColors.btnPrimary)
                borderRadius(53f)
                flexDirectionRow()
                allCenter()
            }
            event { click { ctx.generateMusic() } }
            Text {
                attr {
                    text(ctx.createLabelKey.tr)
                    fontSize(16f)
                    fontWeight600()
                    color(MusicGenColors.btnPrimaryText)
                    lines(1)
                }
            }
            vif({ ctx.createShowPoints }) {
                View {
                    attr {
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    Image {
                        attr {
                            marginLeft(10f)
                            size(16f, 16f)
                            src(ImageUri.commonAssets(AssetsImage.c1Icon))
                            resizeContain()
                            tintColor(MusicGenColors.btnPrimaryText)
                        }
                    }
                    Text {
                        attr {
                            marginLeft(4f)
                            text("${ctx.createPoints}")
                            fontSize(16f)
                            fontWeight600()
                            color(MusicGenColors.btnPrimaryText)
                        }
                    }
                }
            }
        }
    }
}
