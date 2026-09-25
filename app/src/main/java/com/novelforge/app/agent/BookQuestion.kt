package com.novelforge.app.agent

import com.novelforge.app.domain.model.ChapterRevision
import com.novelforge.app.domain.model.OutlineItem
import com.novelforge.app.presentation.common.chapterLabel

data class QuestionPack(
    val prompt: String,
    val outlineCount: Int,
    val excerptCount: Int
)

/**
 * 提问只带大纲和相关摘录。正文再长，也只截命中词附近的一小段。
 */
fun buildQuestionPack(
    chapters: List<OutlineItem>,
    revisions: List<ChapterRevision>,
    question: String
): QuestionPack {
    val latest = revisions.groupBy { it.outlineItemId }
        .mapValues { (_, values) -> values.maxBy { it.revision } }
    val ordered = chapters.sortedBy { it.orderIndex }
    val corpus = buildString {
        ordered.forEach { item ->
            append(item.title)
            append(item.summary)
            append(latest[item.id]?.content.orEmpty())
        }
    }
    val terms = questionTerms(question, corpus)
    // 按「命中总次数」排序再取前 4。以前是按大纲顺序取前 4 个含有任意一个
    // 二字组的章节 —— 问「第 300 章」时拿回来的可能是第 1 章里一句无关的巧合重叠。
    val related = ordered.mapNotNull { item ->
        val revision = latest[item.id]
        val haystack = item.title + item.summary + revision?.content.orEmpty()
        val hits = terms.sumOf { term -> haystack.countOccurrencesOf(term) }
        if (hits == 0) return@mapNotNull null
        val term = terms.maxByOrNull { candidate -> haystack.countOccurrencesOf(candidate) }
            ?: return@mapNotNull null
        val source = revision?.content?.takeIf { it.contains(term) } ?: item.summary
        Excerpt(item, excerptAround(source, term), hits)
    }.sortedByDescending { it.hits }.take(4)
    // 大纲不能全发。1500 章 × 80 字概要 ≈ 12 万字符，比任何中文网关的上下文都大，
    // 表现为「这本书它好像不认识」。只发命中章 + 最近若干章，其余用一句话概括规模。
    val hitIds = related.mapTo(HashSet()) { it.item.id }
    val tail = ordered.takeLast(OUTLINE_TAIL_CHAPTERS).map { it.id }
    val outlineLines = ordered.filter { it.id in hitIds || it.id in tail }
        .map { item -> "${chapterLabel(item.orderIndex)} ${item.title}：${item.summary.take(80)}" }
    val omittedOutline = ordered.size - outlineLines.size
    val outlineText = buildString {
        append(outlineLines.joinToString("\n").ifBlank { "还没有大纲" })
        if (omittedOutline > 0) {
            append("\n（全书共 ${ordered.size} 章，这里只列出了最近 $OUTLINE_TAIL_CHAPTERS 章")
            append("和问题相关的 ${hitIds.size} 章，省略了中间 $omittedOutline 章的概要）")
        }
    }
    val relatedText = if (related.isEmpty()) {
        "没有命中的正文片段。请只根据大纲回答，不知道就说不确定。"
    } else {
        related.joinToString("\n") { "《${it.item.title}》：${it.text}" }
    }
    val prompt = """
        【作品】《书名以提问者所在的书为准》
        【大纲】
        $outlineText

        【相关片段】
        $relatedText
    """.trimIndent()
    return QuestionPack(prompt, ordered.size, related.size)
}

private data class Excerpt(val item: OutlineItem, val text: String, val hits: Int)

private fun String.countOccurrencesOf(needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var index = indexOf(needle)
    while (index >= 0) {
        count++
        index = indexOf(needle, index + needle.length)
    }
    return count
}

/** 大纲全量会撑爆上下文，只保留最近这么多章。 */
private const val OUTLINE_TAIL_CHAPTERS = 30

internal fun questionTerms(question: String, corpus: String): List<String> {
    val compact = question.filter { !it.isWhitespace() }
    if (compact.length < 2 || corpus.isEmpty()) return emptyList()
    val found = ArrayList<String>()
    for (index in 0..compact.length - 2) {
        val gram = compact.substring(index, index + 2)
        if (gram.any { it.isDigit() }) continue
        if (corpus.contains(gram)) found += gram
    }
    return found.distinct().take(8)
}

internal fun excerptAround(text: String, term: String): String {
    val index = text.indexOf(term)
    if (index < 0) return text.take(160)
    val start = (index - 40).coerceAtLeast(0)
    val end = (index + term.length + 80).coerceAtMost(text.length)
    val slice = text.substring(start, end)
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < text.length) "…" else ""
    return prefix + slice + suffix
}

class BookQuestion(
    private val store: NovelBookStore,
    private val complete: suspend (context: String, question: String) -> String
) {
    suspend fun ask(projectId: String, question: String): String {
        val chapters = store.latestOutline(projectId)?.chapters.orEmpty()
        val pack = buildQuestionPack(chapters, store.revisions(projectId), question)
        val note = "这次只带了大纲 ${pack.outlineCount} 章、相关片段 ${pack.excerptCount} 条，没有发送全文。"
        val answer = complete(pack.prompt, question.trim())
        return "$note\n\n$answer"
    }
}
