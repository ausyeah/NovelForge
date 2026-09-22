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
    val related = ordered.mapNotNull { item ->
        val revision = latest[item.id]
        val haystack = item.title + item.summary + revision?.content.orEmpty()
        val term = terms.firstOrNull { haystack.contains(it) } ?: return@mapNotNull null
        val source = revision?.content?.takeIf { it.contains(term) } ?: item.summary
        Excerpt(item, excerptAround(source, term))
    }.take(4)
    val outlineText = ordered.joinToString("\n") { item ->
        "${chapterLabel(item.orderIndex)} ${item.title}：${item.summary.take(80)}"
    }.ifBlank { "还没有大纲" }
    val relatedText = if (related.isEmpty()) {
        "没有命中的正文片段。请只根据大纲回答，不知道就说不确定。"
    } else {
        related.joinToString("\n") { "《${it.item.title}》：${it.text}" }
    }
    val prompt = """
        【大纲】
        $outlineText

        【相关片段】
        $relatedText
    """.trimIndent()
    return QuestionPack(prompt, ordered.size, related.size)
}

private data class Excerpt(val item: OutlineItem, val text: String)

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
