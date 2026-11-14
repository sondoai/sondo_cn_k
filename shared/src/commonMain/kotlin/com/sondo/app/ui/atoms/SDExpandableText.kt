package com.sondo.app.ui.atoms

import com.sondo.app.ui.SDColors
import com.sondo.app.ui.SDWeight
import com.sondo.app.ui.weight
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

class SDExpandableTextAttr : ComposeAttr() {
    var text: String by observable("")
    /** 折叠时的最大行数 */
    var maxLines: Int = 3
    var fontSize: Float = 14f
    var color: Color by observable(SDColors.white80)
    var weight: SDWeight? = null
    /** 行高（dp）；<= 0 用默认 */
    var lineHeightDp: Float = 0f
    /** 「展开」文案（调用方传 i18n 文案，如 `"more".tr`）；为空则只能点正文切换 */
    var expandText: String = ""
    /** 「收起」文案；为空则展开后不显示收起入口（点正文收起） */
    var collapseText: String = ""
    var actionColor: Color = Color.WHITE
    /** 是否展开（可由父级控制；不绑定时组件自己维护） */
    var expanded: Boolean by observable(false)
}

class SDExpandableTextEvent : ComposeEvent() {
    internal var toggleHandler: ((Boolean) -> Unit)? = null
    /** 展开 / 收起切换后回调。 */
    fun onToggle(handler: (expanded: Boolean) -> Unit) { toggleHandler = handler }
}

/**
 * 可展开文本：折叠时最多 [SDExpandableTextAttr.maxLines] 行，被截断才显示「展开」入口。
 * （参考工程里歌词 / 描述是各页面自己做的折叠，这里收敛成一个组件。）
 */
class SDExpandableTextView : ComposeView<SDExpandableTextAttr, SDExpandableTextEvent>() {
    /** 折叠态下文本是否真的被截断 */
    private var truncated by observable(false)

    override fun createAttr(): SDExpandableTextAttr = SDExpandableTextAttr()
    override fun createEvent(): SDExpandableTextEvent = SDExpandableTextEvent()

    private fun toggle() {
        if (!truncated && !attr.expanded) return
        attr.expanded = !attr.expanded
        event.toggleHandler?.invoke(attr.expanded)
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            Text {
                attr {
                    text(ctx.attr.text)
                    fontSize(ctx.attr.fontSize)
                    color(ctx.attr.color)
                    weight(ctx.attr.weight)
                    if (ctx.attr.lineHeightDp > 0f) lineHeight(ctx.attr.lineHeightDp)
                    if (ctx.attr.expanded) {
                        lines(0)
                    } else {
                        lines(ctx.attr.maxLines)
                        textOverFlowTail()
                        // 给右下角的「展开」留位置
                        if (ctx.attr.expandText.isNotEmpty()) {
                            lineBreakMargin(ctx.attr.expandText.length * ctx.attr.fontSize + 8f)
                        }
                    }
                }
                event {
                    onLineBreakMargin { ctx.truncated = true }
                    click { ctx.toggle() }
                }
            }
            vif({ !ctx.attr.expanded && ctx.truncated && ctx.attr.expandText.isNotEmpty() }) {
                View {
                    attr { absolutePosition(bottom = 0f, right = 0f) }
                    event { click { ctx.toggle() } }
                    Text {
                        attr {
                            text(ctx.attr.expandText)
                            fontSize(ctx.attr.fontSize)
                            if (ctx.attr.lineHeightDp > 0f) lineHeight(ctx.attr.lineHeightDp)
                            fontWeight600()
                            color(ctx.attr.actionColor)
                        }
                    }
                }
            }
            vif({ ctx.attr.expanded && ctx.attr.collapseText.isNotEmpty() }) {
                View {
                    attr {
                        alignSelfFlexEnd()
                        marginTop(4f)
                    }
                    event { click { ctx.toggle() } }
                    Text {
                        attr {
                            text(ctx.attr.collapseText)
                            fontSize(ctx.attr.fontSize)
                            fontWeight600()
                            color(ctx.attr.actionColor)
                        }
                    }
                }
            }
        }
    }
}

fun ViewContainer<*, *>.SDExpandableText(init: SDExpandableTextView.() -> Unit) {
    addChild(SDExpandableTextView(), init)
}
