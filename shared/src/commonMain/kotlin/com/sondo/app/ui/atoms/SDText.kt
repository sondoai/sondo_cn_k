package com.sondo.app.ui.atoms

import com.sondo.app.ui.SDTextAlign
import com.sondo.app.ui.SDWeight
import com.sondo.app.ui.align
import com.sondo.app.ui.weight
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.event.ClickParams
import com.tencent.kuikly.core.views.RichText
import com.tencent.kuikly.core.views.RichTextAttr
import com.tencent.kuikly.core.views.Span
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.TextView

/**
 * Dart `TextWidget` → `SDText`。
 *
 * 就是原生 `Text`，只是先套上 TextWidget 的默认值：**白色、14 号、单行（超出裁剪）**。
 * 其余属性照常写在 `attr { }` 里（响应式），需要换行时显式 `lines(n)`（`lines(0)` 不限行）。
 *
 * ```
 * SDText { attr { text(ctx.title); fontSize(16f); fontWeight600(); lines(2) } }
 * ```
 */
fun ViewContainer<*, *>.SDText(init: TextView.() -> Unit) {
    Text {
        attr {
            color(Color.WHITE)
            fontSize(14f)
            lines(1)
            textOverFlowClip()
        }
        init()
    }
}

/**
 * 静态文本的快捷写法，参数名与 Dart `TextWidget(text, color:, size:, fontWeight:, height:, maxLines:, …)` 一致。
 *
 * 注意：参数在构建时求值一次，**不是响应式的**；文本 / 样式要随状态变化时用上面的闭包写法。
 *
 * @param height 行高倍数（Dart TextStyle.height）
 * @param maxLines 默认 1；0 表示不限行
 * @param init 可选：继续补充 attr / event（如 `attr { marginTop(8f) }`、`event { click { } }`）
 */
fun ViewContainer<*, *>.SDText(
    text: String,
    color: Color = Color.WHITE,
    size: Float = 14f,
    fontWeight: SDWeight? = null,
    height: Float? = null,
    maxLines: Int = 1,
    textAlign: SDTextAlign = SDTextAlign.START,
    fontFamily: String? = null,
    letterSpacing: Float? = null,
    ellipsis: Boolean = false,
    init: (TextView.() -> Unit)? = null,
) {
    Text {
        attr {
            text(text)
            color(color)
            fontSize(size)
            weight(fontWeight)
            if (height != null) lineHeight(size * height)
            lines(maxLines)
            if (ellipsis) textOverFlowTail() else textOverFlowClip()
            align(textAlign)
            if (fontFamily != null) fontFamily(fontFamily)
            if (letterSpacing != null) letterSpacing(letterSpacing)
        }
        init?.invoke(this)
    }
}

/** 富文本里的一段：普通文字或可点击链接。 */
class SDLinkSpan(
    val text: String,
    val onTap: (() -> Unit)? = null,
)

/**
 * 把 [source] 按 [labels] 里的字面量切成「普通文字 + 链接」片段
 * （Dart: privacy_consent.dart `_linkSpans`，链接文案靠字面量匹配）。
 */
fun splitLinkSpans(source: String, labels: List<String>, onTap: (label: String) -> Unit): List<SDLinkSpan> {
    val out = ArrayList<SDLinkSpan>()
    var cursor = 0
    while (cursor < source.length) {
        var nextIndex = source.length
        var nextLabel: String? = null
        for (label in labels) {
            val index = source.indexOf(label, cursor)
            if (index in 0 until nextIndex) {
                nextIndex = index
                nextLabel = label
            }
        }
        if (nextLabel == null) {
            out.add(SDLinkSpan(source.substring(cursor)))
            break
        }
        if (nextIndex > cursor) out.add(SDLinkSpan(source.substring(cursor, nextIndex)))
        val label = nextLabel
        out.add(SDLinkSpan(label) { onTap(label) })
        cursor = nextIndex + label.length
    }
    return out
}

/**
 * 带可点击链接的富文本（Dart `Text.rich(TextSpan(children: [… recognizer …]))`）。
 *
 * @param spans 片段；`onTap != null` 的片段按链接样式渲染
 * @param size / color / height 正文样式（height 为行高倍数）
 * @param linkColor / linkWeight / linkUnderline 链接样式
 * @param attrs 可选：补充 RichText 自身的属性（如 `{ textAlignCenter(); marginTop(4f) }`）。
 *   注意 Kuikly 的 RichText 只保留最后一个 `attr { }` 块，所以这里用参数而不是让调用方再写 attr。
 */
fun ViewContainer<*, *>.SDLinkText(
    spans: List<SDLinkSpan>,
    size: Float = 14f,
    color: Color = Color.WHITE,
    height: Float? = null,
    weight: SDWeight? = null,
    linkColor: Color = Color.WHITE,
    linkWeight: SDWeight? = null,
    linkUnderline: Boolean = true,
    attrs: (RichTextAttr.() -> Unit)? = null,
) {
    RichText {
        attr {
            if (height != null) lineHeight(size * height)
            attrs?.invoke(this)
        }
        for (span in spans) {
            val tap = span.onTap
            Span {
                text(span.text)
                fontSize(size)
                if (tap == null) {
                    color(color)
                    weight(weight)
                } else {
                    color(linkColor)
                    weight(linkWeight ?: weight)
                    if (linkUnderline) textDecorationUnderLine()
                    click { _: ClickParams -> tap() }
                }
            }
        }
    }
}
