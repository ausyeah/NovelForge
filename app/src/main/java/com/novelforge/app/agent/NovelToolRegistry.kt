package com.novelforge.app.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class ToolSpec(val name: String, val description: String, val argumentsHint: String)

sealed interface ToolResult {
    data class Ok(val content: String) : ToolResult
    data class Fail(val reason: String) : ToolResult
}

class NovelToolRegistry(
    private val store: NovelBookStore,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() }
) {
    fun specs(): List<ToolSpec> = listOf(
        ToolSpec(
            name = "search_chapters",
            description = "在已写章节里按关键词搜索，只返回短摘录",
            argumentsHint = "query"
        ),
        ToolSpec(
            name = "read_chapter",
            description = "阅读指定章节的摘要和结尾",
            argumentsHint = "chapterId"
        ),
        ToolSpec(
            name = "get_story_bible",
            description = "读取规则、角色、伏笔和事实，待确认单独列出",
            argumentsHint = ""
        ),
        ToolSpec(
            name = "propose_fact",
            description = "提议一条事实，只进入待确认",
            argumentsHint = "statement, kind"
        ),
        ToolSpec(
            name = "patch_outline",
            description = "修改大纲标题或摘要，不改已写正文",
            argumentsHint = "chapterId, title, summary"
        ),
        ToolSpec(
            name = "queue_chapter",
            description = "把大纲里的一章加入写作队列",
            argumentsHint = "chapterId"
        )
    )

    suspend fun call(projectId: String, name: String, arguments: JsonObject): ToolResult {
        if (specs().none { it.name == name }) return ToolResult.Fail("未知工具")
        if (name == "search_chapters" && arguments.text("query").isEmpty()) {
            return ToolResult.Fail("查询不能为空")
        }
        return when (name) {
            "search_chapters" -> searchChapters(projectId, arguments.text("query"))
            "read_chapter" -> readChapter(projectId, arguments.text("chapterId"))
            else -> ToolResult.Fail("尚未实现")
        }
    }

    private suspend fun searchChapters(projectId: String, query: String): ToolResult {
        val outline = store.latestOutline(projectId) ?: return ToolResult.Ok("没有找到")
        val latestRevision = store.revisions(projectId)
            .groupBy { it.outlineItemId }
            .mapValues { (_, revisions) -> revisions.maxBy { it.revision } }
        val lines = outline.chapters
            .sortedBy { it.orderIndex }
            .mapNotNull { item ->
                val revision = latestRevision[item.id]
                val hit = listOf(
                    item.title,
                    item.summary,
                    revision?.summary.orEmpty(),
                    revision?.content.orEmpty()
                ).firstOrNull { it.contains(query) } ?: return@mapNotNull null
                "${item.id}|${item.title}|${excerpt(hit, query)}"
            }
            .take(5)
        if (lines.isEmpty()) return ToolResult.Ok("没有找到")
        return ToolResult.Ok(lines.joinToString("\n"))
    }

    private fun excerpt(text: String, query: String): String {
        val index = text.indexOf(query)
        if (index < 0) return ""
        val start = (index - 20).coerceAtLeast(0)
        val end = (index + query.length + 40).coerceAtMost(text.length)
        return text.substring(start, end).take(120)
    }

    private suspend fun readChapter(projectId: String, chapterId: String): ToolResult {
        val item = store.latestOutline(projectId)?.chapters?.find { it.id == chapterId }
            ?: return ToolResult.Fail("章节不存在")
        val revision = store.revisions(projectId)
            .filter { it.outlineItemId == item.id }
            .maxByOrNull { it.revision }
            ?: return ToolResult.Fail("章节不存在")
        val tail = revision.content.takeLast(400)
        return ToolResult.Ok("摘要：${revision.summary.orEmpty()}\n结尾：$tail")
    }
}

private fun JsonObject.text(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
