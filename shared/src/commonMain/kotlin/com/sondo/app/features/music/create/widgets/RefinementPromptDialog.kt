package com.sondo.app.features.music.create.widgets

import com.sondo.app.base.BasePager
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.DevLog
import com.sondo.app.data.api.StreamHandle
import com.sondo.app.features.music.data.MusicGenApi
import com.sondo.app.ui.input.forceSetText
import com.sondo.app.ui.input.hideKeyboard
import com.sondo.app.ui.dialog.showAppBottomSheet
import com.sondo.app.ui.overlay.DialogHandle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.LengthLimitType
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.ScrollerView
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.TextArea
import com.tencent.kuikly.core.views.TextAreaView
import com.tencent.kuikly.core.views.View

/**
 * 歌词润色弹窗（Dart: create/widgets/refinement_prompt_dialog.dart `RefinementPromptDialog`）。
 * 流式结果逐段追加；弹窗关闭时关闭流（不再有回调）。
 */
internal fun BasePager.showRefinementPromptDialog(
    initialText: String,
    maxLength: Int,
    onAccepted: (String) -> Unit,
): DialogHandle = showAppBottomSheet(
    enableDrag = false,
    keyboardAware = true,
    showHandle = false,
    topRadius = 16f,
) { handle ->
    addChild(RefinementPromptView()) {
        attr {
            this.handle = handle
            this.initialText = initialText
            this.maxLength = maxLength
            this.onAccepted = onAccepted
        }
    }
}

internal class RefinementPromptAttr : ComposeAttr() {
    lateinit var handle: DialogHandle
    var initialText: String = ""
    var maxLength: Int = 1000
    var onAccepted: ((String) -> Unit)? = null
}

internal class RefinementPromptView : ComposeView<RefinementPromptAttr, ComposeEvent>() {
    private var promptText by observable("")
    private var streamText by observable("")
    private var isStreamLoading by observable(false)
    private var isStreamError by observable(false)
    private var stream: StreamHandle? = null
    private var alive = true
    private var originalRef: ViewRef<TextAreaView>? = null
    private var resultRef: ViewRef<ScrollerView<*, *>>? = null

    override fun createAttr(): RefinementPromptAttr = RefinementPromptAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun created() {
        super.created()
        promptText = attr.initialText
    }

    override fun viewDestroyed() {
        alive = false
        // 弹窗关闭不会自动取消流，必须释放，避免回调继续写已销毁的视图
        disposeStream()
        super.viewDestroyed()
    }

    private fun disposeStream() {
        stream?.close()
        stream = null
    }

    private val pager: BasePager get() = getPager() as BasePager

    private fun startRefinement() {
        if (isStreamLoading) {
            pager.toast("refining".tr)
            return
        }
        originalRef?.view?.blur()
        polishStream()
    }

    private fun polishStream() {
        val prompt = promptText
        if (prompt.isEmpty()) return
        isStreamLoading = true
        streamText = ""
        isStreamError = false
        var metaData = JSONObject()
        disposeStream()
        stream = MusicGenApi.polishLyricsStream(
            lyrics = prompt,
            onDelta = { text -> if (alive) appendStreamText(text) },
            onMeta = { meta ->
                if (alive) {
                    DevLog.logger("on start: $meta")
                    metaData = meta
                }
            },
            onError = { msg ->
                if (alive) onStreamError(msg, metaData, prompt)
            },
            onDone = {
                if (alive && isStreamLoading) isStreamLoading = false
            },
        )
    }

    private fun onStreamError(msg: String, meta: JSONObject, prompt: String) {
        DevLog.logger("on error: $msg ")
        if (msg == "error") {
            // 次数不足
            val subscribed = meta.optBoolean("subscribed", false)
            val hint = meta.optString("subscribeHintEn", "") // 与参考工程一致只看 subscribeHintEn
            val needSubscribe = meta.optBoolean("needSubscribe", false)
            if (subscribed) {
                // 会员：今日剩余次数不足则提示
                if (meta.optInt("dailyRemaining", 0) <= 0 && hint.isNotEmpty()) {
                    pager.toast("insufficient_optimization_descriptions".tr)
                }
            } else {
                // 非会员：试用次数不足则提示并引导订阅
                if (meta.optInt("trialRemaining", 0) <= 0) {
                    pager.toast("polish_need_subscribe".tr)
                    if (needSubscribe) pager.nav.push(Routes.subscribe)
                }
            }
        } else {
            pager.toast("optimize_description_failed".tr)
        }
        if (!alive) return
        streamText = prompt
        isStreamError = true
        isStreamLoading = false
    }

    private fun appendStreamText(text: String) {
        isStreamError = false
        streamText = (streamText + text).replace("\\n", "\n")
        scrollToBottom()
    }

    private fun scrollToBottom() {
        pager.postDelayed(16) {
            if (!alive) return@postDelayed
            val scroller = resultRef?.view ?: return@postDelayed
            val contentH = scroller.contentView?.frame?.height ?: return@postDelayed
            val max = (contentH - scroller.frame.height).coerceAtLeast(0f)
            scroller.setContentOffset(0f, max, true)
        }
    }

    private fun handleClearText() {
        promptText = ""
        originalRef?.view?.forceSetText("")
    }

    private fun handleAccept() {
        if (streamText.isEmpty()) return
        if (isStreamLoading) {
            pager.toast("refining".tr)
            return
        }
        attr.onAccepted?.invoke(streamText)
        attr.handle.dismiss()
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val initial = ctx.attr.initialText
        return {
            attr { padding(20f, 20f, 20f + ctx.pagerData.safeAreaInsets.bottom, 20f) }
            event { click { ctx.hideKeyboard() } }
            // 标题
            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                    justifyContentSpaceBetween()
                }
                Text {
                    attr {
                        text("optimization_tip".tr)
                        fontSize(16f)
                        fontFamily(PoppinsFace.semiBold)
                        color(Color.WHITE)
                    }
                }
                Image {
                    attr {
                        size(26f, 26f)
                        src(ImageUri.commonAssets(AssetsImage.close))
                        resizeContain()
                    }
                    event { click { ctx.attr.handle.dismiss() } }
                }
            }
            View { attr { height(8f) } }
            // 原始歌词
            View {
                attr {
                    padding(12.5f, 16f, 16f, 12.5f)
                    backgroundColor(UIColor.cFFF10)
                    borderRadius(12f)
                }
                refinementSectionHeader("original_prompt".tr) {
                    vif({ !ctx.isStreamLoading && ctx.promptText.isNotEmpty() }) {
                        View {
                            attr {
                                size(30f, 30f)
                                allCenter()
                            }
                            event { click { ctx.handleClearText() } }
                            Image {
                                attr {
                                    size(16f, 16f)
                                    src(ImageUri.commonAssets(AssetsImage.clear))
                                    resizeContain()
                                }
                            }
                        }
                    }
                }
                View { attr { height(10.5f) } }
                TextArea {
                    ref { ctx.originalRef = it }
                    attr {
                        text(initial)
                        marginRight(3.5f)
                        height(68f)
                        fontSize(12f)
                        fontWeightMedium()
                        color(Color(0xB3FFFFFFL))
                        tintColor(Color.WHITE)
                        placeholder("describe_lyrics_you_desire".tr)
                        placeholderColor(Color(0x4CFFFFFFL))
                        maxTextLength(ctx.attr.maxLength, LengthLimitType.CHARACTER)
                    }
                    event { textDidChange { ctx.promptText = it.text } }
                }
                View { attr { height(16f) } }
                View {
                    attr {
                        marginRight(3.5f)
                        flexDirectionRow()
                        justifyContentFlexEnd()
                    }
                    View {
                        attr {
                            padding(8f, 14f, 8f, 16f)
                            backgroundColor(Color(0x33FFFFFFL))
                            borderRadius(256f)
                            flexDirectionRow()
                            alignItemsCenter()
                        }
                        event {
                            click {
                                ctx.hideKeyboard()
                                ctx.startRefinement()
                            }
                        }
                        Image {
                            attr {
                                size(16f, 16f)
                                src(ImageUri.commonAssets(AssetsImage.runSe))
                                resizeContain()
                            }
                        }
                        Text {
                            attr {
                                marginLeft(4f)
                                text(if (ctx.isStreamLoading) "refining".tr else "refine_it".tr)
                                fontSize(12f)
                                fontWeight600()
                                color(Color(0xE6FFFFFFL))
                            }
                        }
                    }
                }
            }
            View { attr { height(16f) } }
            // 润色结果
            View {
                attr {
                    padding(12.5f, 16f, 16f, 12.5f)
                    backgroundColor(UIColor.cFFF10)
                    borderRadius(12f)
                }
                refinementSectionHeader("optimized_result".tr) { }
                View { attr { height(12.5f) } }
                Scroller {
                    ref { ctx.resultRef = it }
                    attr {
                        height(200f)
                        marginRight(3.5f)
                        showScrollerIndicator(false)
                    }
                    vif({ ctx.isStreamError }) {
                        View {
                            attr {
                                padding(20f, 16f, 20f, 16f)
                                backgroundColor(Color(0x8D35294DL))
                                borderRadius(16f)
                                alignItemsCenter()
                            }
                            Image {
                                attr {
                                    size(60f, 60f)
                                    src(ImageUri.commonAssets(AssetsImage.errorMusic))
                                    resizeContain()
                                }
                            }
                            Text {
                                attr {
                                    marginTop(10f)
                                    text("generate_lyrics_given_prompts_works".tr)
                                    textAlignCenter()
                                    fontSize(12f)
                                    fontFamily(PoppinsFace.regular)
                                    color(Color(0x99FFFFFFL))
                                }
                            }
                            View {
                                attr {
                                    marginTop(20f)
                                    padding(8f, 20f, 8f, 20f)
                                    backgroundColor(UIColor.cF3EFF7)
                                    borderRadius(77f)
                                }
                                event { click { ctx.startRefinement() } }
                                Text {
                                    attr {
                                        text("refresh".tr)
                                        fontSize(14f)
                                        fontFamily(PoppinsFace.semiBold)
                                        color(UIColor.c0A0A0A)
                                    }
                                }
                            }
                        }
                    }
                    velse {
                        Text {
                            attr {
                                val empty = ctx.streamText.isEmpty()
                                text(if (empty) "generate_better_works_tip".tr else ctx.streamText)
                                fontSize(12f)
                                fontFamily(PoppinsFace.medium)
                                color(if (empty) Color(0x4DFFFFFFL) else Color.WHITE)
                            }
                        }
                    }
                }
            }
            View { attr { height(16f) } }
            // 接受 / 取消
            View {
                attr { flexDirectionRow() }
                View {
                    attr {
                        flex(1f)
                        height(44f)
                        borderRadius(22f)
                        backgroundColor(UIColor.cF3EFF7)
                        allCenter()
                    }
                    event { click { ctx.handleAccept() } }
                    Text {
                        attr {
                            text("accept".tr)
                            fontSize(16f)
                            color(UIColor.c0A0A0A)
                        }
                    }
                }
                View { attr { width(12f) } }
                View {
                    attr {
                        flex(1f)
                        height(44f)
                        borderRadius(22f)
                        backgroundColor(Color(0x33FFFFFFL))
                        allCenter()
                    }
                    event { click { ctx.attr.handle.dismiss() } }
                    Text {
                        attr {
                            text("cancel".tr)
                            fontSize(16f)
                            color(Color(0x66FFFFFFL))
                        }
                    }
                }
            }
        }
    }
}

/** 区块标题行（高 30，左标题，右侧可选内容）。 */
private fun ViewContainer<*, *>.refinementSectionHeader(title: String, trailing: ViewContainer<*, *>.() -> Unit) {
    View {
        attr {
            height(30f)
            flexDirectionRow()
            alignItemsCenter()
            justifyContentSpaceBetween()
        }
        Text {
            attr {
                text(title)
                fontSize(14f)
                fontFamily(PoppinsFace.semiBold)
                color(Color.WHITE)
            }
        }
        trailing()
    }
}
