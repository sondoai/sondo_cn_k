package com.sondo.app.features.music.create.widgets

import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.i18n.tr
import com.sondo.app.features.music.create.MusicCreatePage
import com.sondo.app.ui.atoms.SDLoading
import com.sondo.app.ui.input.forceSetText
import com.sondo.app.ui.input.hideKeyboard
import com.sondo.app.ui.overlay.DialogEntry
import com.sondo.app.ui.overlay.DialogHandle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.LengthLimitType
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.TextArea
import com.tencent.kuikly.core.views.TextAreaView
import com.tencent.kuikly.core.views.View

/** 打开全屏歌词编辑弹层（Dart `LyricsInput`，SmartDialog 底部对齐）。 */
internal fun MusicCreatePage.showLyricsInputSheet(): DialogHandle {
    val page = this
    return showDialog(align = DialogEntry.ALIGN_BOTTOM) { handle ->
        addChild(LyricsInputSheetView()) {
            attr {
                this.page = page
                this.handle = handle
            }
        }
    }
}

internal class LyricsInputSheetAttr : ComposeAttr() {
    lateinit var page: MusicCreatePage
    lateinit var handle: DialogHandle
}

/**
 * 歌词编辑弹层：键盘收起时撑到八成屏；弹起后输入区高度按屏高 32% 固定，整块弹层跟键盘上移。
 */
internal class LyricsInputSheetView : ComposeView<LyricsInputSheetAttr, ComposeEvent>() {
    private var keyboardHeight by observable(0f)
    private var areaRef: ViewRef<TextAreaView>? = null

    override fun createAttr(): LyricsInputSheetAttr = LyricsInputSheetAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun created() {
        super.created()
        attr.page.lyricsSheetSync = { v -> areaRef?.view?.forceSetText(v) }
    }

    override fun viewDestroyed() {
        attr.page.lyricsSheetSync = null
        super.viewDestroyed()
    }

    private fun onTag() {
        val area = areaRef?.view
        if (area == null) {
            openTagLater(-1)
            return
        }
        // 先取光标再收键盘，插入位置与 Dart selection 一致
        area.cursorIndex { index -> openTagLater(index) }
    }

    private fun openTagLater(cursor: Int) {
        hideKeyboard()
        val page = attr.page
        page.postDelayed(100) { page.openTag(cursor) }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val page = ctx.attr.page
        val screenH = page.screenHeight
        // 收起时整块 80% 屏高：减去内边距 40、标题行 26、两处间距 30、底部操作行 34
        val closedEditorHeight = (screenH * 0.8f - 40f - 26f - 30f - 34f).coerceAtLeast(120f)
        val openEditorHeight = screenH * 0.32f
        val initialText = page.lyrics
        return {
            attr {
                marginBottom(if (ctx.keyboardHeight > 0f) ctx.keyboardHeight else page.safeBottom)
                padding(20f)
                backgroundColor(Color(0xFF1C1C1CL))
                borderRadius(16f, 16f, 0f, 0f)
            }
            event { click { ctx.hideKeyboard() } }
            // 标题行
            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                    justifyContentSpaceBetween()
                }
                View {
                    attr {
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    Text {
                        attr {
                            text("lyrics".tr)
                            fontSize(16f)
                            fontFamily(PoppinsFace.semiBold)
                            color(Color.WHITE)
                        }
                    }
                    View {
                        attr {
                            marginLeft(10f)
                            padding(5f, 12f, 5f, 5f)
                            backgroundColor(Color(0x1AFFFFFFL))
                            borderRadius(55f)
                            flexDirectionRow()
                            alignItemsCenter()
                        }
                        event { click { if (!page.isLyricsShuffleLoading) ctx.onTag() } }
                        Text {
                            attr {
                                text("tags".tr)
                                fontSize(12f)
                                fontFamily(PoppinsFace.semiBold)
                                color(Color(0xB3FFFFFFL))
                            }
                        }
                        Image {
                            attr {
                                marginLeft(2f)
                                size(16f, 16f)
                                src(ImageUri.commonAssets(AssetsImage.arrowRight))
                                resizeContain()
                            }
                        }
                    }
                }
                Image {
                    attr {
                        size(26f, 26f)
                        src(ImageUri.commonAssets(AssetsImage.arrowDown1))
                        resizeContain()
                        tintColor(Color.WHITE)
                    }
                    event { click { if (!page.isLyricsShuffleLoading) ctx.attr.handle.dismiss() } }
                }
            }
            View { attr { height(15f) } }
            // 编辑区
            View {
                attr {
                    height(if (ctx.keyboardHeight > 0f) openEditorHeight else closedEditorHeight)
                    padding(16f)
                    backgroundColor(Color(0x14FFFFFFL))
                    borderRadius(16f)
                }
                TextArea {
                    ref { ctx.areaRef = it }
                    attr {
                        text(initialText)
                        flex(1f)
                        fontSize(12f)
                        color(Color.WHITE)
                        tintColor(Color.WHITE)
                        placeholder("enter_lyrics_here".tr)
                        placeholderColor(Color(0x4DFFFFFFL))
                        maxTextLength(3000, LengthLimitType.CHARACTER)
                    }
                    attr { editable(!page.isLyricsShuffleLoading) }
                    event {
                        textDidChange { page.setLyrics(it.text, fromSheet = true) }
                        keyboardHeightChange { ctx.keyboardHeight = it.height.coerceAtLeast(0f) }
                    }
                }
            }
            View { attr { height(15f) } }
            // 底部：清空 + 字数 / 随机 + 完成
            View {
                attr {
                    height(34f)
                    flexDirectionRow()
                    alignItemsCenter()
                    justifyContentSpaceBetween()
                }
                View {
                    attr {
                        flexDirectionRow()
                        alignItemsFlexEnd()
                    }
                    vif({ page.lyrics.isNotEmpty() }) {
                        View {
                            attr { padding(10f, 0f, 0f, 10f) }
                            event { click { if (!page.isLyricsShuffleLoading) page.setLyrics("") } }
                            Image {
                                attr {
                                    size(14f, 14f)
                                    src(ImageUri.commonAssets(AssetsImage.clear3))
                                    resizeContain()
                                }
                            }
                        }
                    }
                    Text {
                        attr {
                            text("${page.lyrics.length}/3000")
                            fontSize(12f)
                            fontFamily(PoppinsFace.regular)
                            color(Color(0x4DFFFFFFL))
                        }
                    }
                }
                View {
                    attr {
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    View {
                        attr {
                            size(34f, 34f)
                            allCenter()
                            backgroundColor(Color(0x1AFFFFFFL))
                            borderRadius(10f)
                        }
                        event { click { if (!page.isLyricsShuffleLoading) page.createLyrics() } }
                        vif({ page.isLyricsShuffleLoading }) {
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
                    View {
                        attr {
                            marginLeft(10f)
                            padding(8f, 14f, 8f, 14f)
                            allCenter()
                            backgroundColor(Color(0x1AFFFFFFL))
                            borderRadius(10f)
                        }
                        event { click { if (!page.isLyricsShuffleLoading) ctx.attr.handle.dismiss() } }
                        Text {
                            attr {
                                text("complete".tr)
                                fontSize(12f)
                                fontFamily(PoppinsFace.semiBold)
                                color(Color.WHITE)
                            }
                        }
                    }
                }
            }
        }
    }
}
