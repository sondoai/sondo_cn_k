package com.sondo.app.features.music_video.description

import com.sondo.app.data.models.MusicStyleModel
import com.sondo.app.data.models.MvTagLabelModel

// Dart: presentation/pages/create/widgets/description_box/description_idea_catalog.dart + description_idea_editor.dart（纯逻辑部分）

/** 描述框标签目录。名称在全部分类里唯一，插入和替换都认名称。 */
open class DescriptionIdeaCatalog(val categories: List<DescriptionIdeaCategory>) {
    val names: List<String> by lazy { categories.flatMap { c -> c.tags.map { it.name } } }

    fun categoryIdOf(name: String): String? {
        for (category in categories) {
            for (tag in category.tags) if (tag.name == name) return category.id
        }
        return null
    }

    fun tagByName(name: String): DescriptionIdeaTag? {
        for (category in categories) {
            for (tag in category.tags) if (tag.name == name) return tag
        }
        return null
    }

    val isEmpty: Boolean get() = categories.isEmpty()
}

class DescriptionIdeaTag(val name: String, val description: String = "", val sort: Int = 0, val enabled: Boolean = true)

class DescriptionIdeaCategory(
    val id: String,
    val name: String,
    val tags: List<DescriptionIdeaTag>,
    val sort: Int = 0,
    val enabled: Boolean = true,
)

/** 丢掉停用项和重名。停用项不占名称，同名只保留先出现的启用项。 */
fun normalizeIdeaCategories(source: List<DescriptionIdeaCategory>): List<DescriptionIdeaCategory> {
    val seen = HashSet<String>()
    val result = ArrayList<DescriptionIdeaCategory>()
    for (category in source.sortedBy { it.sort }) {
        if (!category.enabled) continue
        val tags = ArrayList<DescriptionIdeaTag>()
        for (tag in category.tags.sortedBy { it.sort }) {
            // 先跳过停用项，避免停用名称挡住后面的同名启用标签
            if (!tag.enabled) continue
            if (!seen.add(tag.name)) continue
            tags.add(tag)
        }
        if (tags.isEmpty()) continue
        result.add(DescriptionIdeaCategory(category.id, category.name, tags, category.sort))
    }
    return result
}

/** 调用方传入的目录，会先按名称去重（Dart `ListDescriptionIdeaCatalog`）。 */
class ListDescriptionIdeaCatalog(source: List<DescriptionIdeaCategory>) : DescriptionIdeaCatalog(normalizeIdeaCategories(source))

/** 把 `mvIdeas` 收成描述框目录。标签名是 value，说明是 remark，重名只留先出现的。 */
fun ideaCatalogFromMvIdeas(labels: List<MvTagLabelModel>, tagMap: Map<String, List<MusicStyleModel>>): DescriptionIdeaCatalog {
    val categories = ArrayList<DescriptionIdeaCategory>()
    for ((i, label) in labels.withIndex()) {
        val items = tagMap[label.id] ?: emptyList()
        val tags = ArrayList<DescriptionIdeaTag>()
        for ((j, item) in items.withIndex()) {
            if (item.value.isEmpty()) continue
            tags.add(DescriptionIdeaTag(item.value, item.remark ?: "", j))
        }
        if (tags.isEmpty()) continue
        categories.add(DescriptionIdeaCategory(label.id, label.label, tags, i))
    }
    return ListDescriptionIdeaCatalog(categories)
}

/** 正文里的一段 `@名称`。 */
class IdeaTagSpan(val name: String, val start: Int, val end: Int)

class IdeaTagEdit(val text: String, val cursor: Int)

/** 按名称从长到短匹配，避免 `@Singing` 吃掉 `@Singing + Story`。 */
fun findIdeaTagSpans(text: String, names: List<String>): List<IdeaTagSpan> {
    if (text.isEmpty() || names.isEmpty()) return emptyList()
    val body = names.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    val pattern = Regex("@($body)(?![\\w'-])")
    return pattern.findAll(text).mapNotNull { m ->
        val g = m.groups[1]?.value ?: return@mapNotNull null
        IdeaTagSpan(g, m.range.first, m.range.last + 1)
    }.toList()
}

/** 退格只擦掉标签的一部分时，扩成整段 `@名称` 一起删。返回 (新文本, 光标)；不处理返回 null。 */
fun expandIdeaTagDeletion(oldText: String, newText: String, names: List<String>): IdeaTagEdit? {
    if (newText.length >= oldText.length) return null
    var prefix = 0
    while (prefix < newText.length && prefix < oldText.length && newText[prefix] == oldText[prefix]) prefix++
    var suffix = 0
    while (suffix < newText.length - prefix && suffix < oldText.length - prefix &&
        newText[newText.length - 1 - suffix] == oldText[oldText.length - 1 - suffix]
    ) suffix++
    // 中间还有新打的字，是在把 @Story 改成 @Sing，不能当成退格整段清空。
    if (newText.length - prefix - suffix > 0) return null
    // 点正文标签换成更短的名称时，前后缀会把新文本吃满。删除点落在新标签上就保留。
    if (replacementKeepsTag(newText, names, prefix)) return null
    val deleteStart = prefix
    val deleteEnd = oldText.length - suffix
    var expandedStart = deleteStart
    var expandedEnd = deleteEnd
    var touchedPartial = false
    for (span in findIdeaTagSpans(oldText, names)) {
        val overlaps = span.end > deleteStart && span.start < deleteEnd
        if (!overlaps) continue
        val fullyContained = deleteStart <= span.start && deleteEnd >= span.end
        if (fullyContained) continue
        touchedPartial = true
        if (span.start < expandedStart) expandedStart = span.start
        if (span.end > expandedEnd) expandedEnd = span.end
    }
    if (!touchedPartial) return null
    return IdeaTagEdit(oldText.replaceRange(expandedStart, expandedEnd, ""), expandedStart)
}

private fun replacementKeepsTag(newText: String, names: List<String>, caret: Int): Boolean {
    for (span in findIdeaTagSpans(newText, names)) {
        if (span.start <= caret && caret <= span.end) return true
        if (span.end < caret && newText.substring(span.end, caret).isBlank()) return true
        if (caret < span.start && newText.substring(caret, span.start).isBlank()) return true
    }
    return false
}

/** 点选标签：同分类原位替换，没有则插在光标处。已选再点则删掉该名称。 */
fun applyIdeaTagSelection(text: String, cursor: Int, name: String, catalog: DescriptionIdeaCatalog, remove: Boolean): IdeaTagEdit {
    val names = catalog.names
    if (remove) return removeName(text, name, names)
    val categoryId = catalog.categoryIdOf(name)
    val spans = findIdeaTagSpans(text, names)
    val sameCategory = spans.filter { catalog.categoryIdOf(it.name) == categoryId }
    if (categoryId == null || sameCategory.isEmpty()) return insertAtCursor(text, cursor, name, names)
    return replaceCategory(text, sameCategory, name)
}

private fun removeName(text: String, name: String, names: List<String>): IdeaTagEdit {
    val spans = findIdeaTagSpans(text, names).filter { it.name == name }
    if (spans.isEmpty()) return IdeaTagEdit(text, clampCursor(text, text.length))
    var result = text
    for (span in spans.reversed()) result = result.replaceRange(span.start, span.end, "")
    return IdeaTagEdit(result, spans.first().start.coerceIn(0, result.length))
}

private fun replaceCategory(text: String, sameCategory: List<IdeaTagSpan>, name: String): IdeaTagEdit {
    val ordered = sameCategory.sortedBy { it.start }
    val firstStart = ordered.first().start
    val token = "@$name"
    val buffer = StringBuilder()
    var read = 0
    var cursor = 0
    for (span in ordered) {
        buffer.append(text.substring(read, span.start))
        if (span.start == firstStart) {
            buffer.append(token)
            cursor = buffer.length
        }
        read = span.end
    }
    buffer.append(text.substring(read))
    return IdeaTagEdit(buffer.toString(), cursor)
}

private fun insertAtCursor(text: String, cursor: Int, name: String, names: List<String>): IdeaTagEdit {
    var pos = clampCursor(text, cursor)
    // 光标落在已有标签中间时插到它后面，避免把 @名称 拆成两段普通文字
    for (span in findIdeaTagSpans(text, names)) {
        if (pos > span.start && pos < span.end) {
            pos = span.end
            break
        }
    }
    val before = text.substring(0, pos)
    val after = text.substring(pos)
    val token = "@$name"
    // 贴着普通文字时补空格，否则 `@名称` 会黏进前后词，匹配失败
    val spaceBefore = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
    val spaceAfter = if (after.isNotEmpty() && !after.first().isWhitespace()) " " else ""
    val next = "$before$spaceBefore$token$spaceAfter$after"
    return IdeaTagEdit(next, before.length + spaceBefore.length + token.length + spaceAfter.length)
}

private fun clampCursor(text: String, cursor: Int): Int = if (cursor < 0 || cursor > text.length) text.length else cursor

/**
 * 手打或粘贴凑成同分类的另一个 `@名称` 时，清掉该分类里原先那段（Dart `dropPreviousSameCategoryTag`）。
 * Kuikly 输入事件不带组字区间，这里只按前后文本差异判断这次输入的区间。
 */
fun dropPreviousSameCategoryTag(oldText: String, newText: String, cursor: Int, catalog: DescriptionIdeaCatalog): IdeaTagEdit? {
    if (catalog.names.isEmpty() || newText == oldText) return null
    var prefix = 0
    val prefixLimit = minOf(oldText.length, newText.length)
    while (prefix < prefixLimit && oldText[prefix] == newText[prefix]) prefix++
    var suffix = 0
    while (suffix < oldText.length - prefix && suffix < newText.length - prefix &&
        oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
    ) suffix++
    val insertEnd = newText.length - suffix
    val spans = findIdeaTagSpans(newText, catalog.names)
    if (spans.size < 2) return null
    val shift = insertEnd - (oldText.length - suffix)
    val oldSpans = findIdeaTagSpans(oldText, catalog.names)
    fun hasSpan(name: String, start: Int, end: Int) = oldSpans.any { it.name == name && it.start == start && it.end == end }
    val incoming = spans.filter { span ->
        val stable = when {
            span.end <= prefix -> hasSpan(span.name, span.start, span.end)
            span.start >= insertEnd -> hasSpan(span.name, span.start - shift, span.end - shift)
            else -> false
        }
        !stable
    }
    if (incoming.isEmpty()) return null
    val removed = ArrayList<IdeaTagSpan>()
    val seen = HashSet<String>()
    for (span in incoming) {
        val categoryId = catalog.categoryIdOf(span.name) ?: continue
        if (!seen.add(categoryId)) continue
        val group = spans.filter { catalog.categoryIdOf(it.name) == categoryId }.sortedBy { it.start }
        if (group.size < 2) continue
        val keep = incoming.filter { catalog.categoryIdOf(it.name) == categoryId }.reduce { a, b -> if (a.start >= b.start) a else b }
        for (item in group) {
            if (item.start == keep.start && item.end == keep.end) continue
            removed.add(item)
        }
    }
    if (removed.isEmpty()) return null
    removed.sortBy { it.start }
    var result = newText
    for (span in removed.reversed()) result = result.replaceRange(span.start, span.end, "")
    // 光标随删掉的片段前移
    var cut = 0
    var nextCursor = -1
    for (span in removed) {
        if (span.end <= cursor) {
            cut += span.end - span.start
        } else if (span.start < cursor) {
            nextCursor = (span.start - cut).coerceIn(0, result.length)
            break
        }
    }
    if (nextCursor < 0) nextCursor = (cursor - cut).coerceIn(0, result.length)
    return IdeaTagEdit(result, nextCursor)
}
