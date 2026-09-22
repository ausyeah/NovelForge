package com.novelforge.app.agent

import com.novelforge.app.domain.model.ContinuityFact
import com.novelforge.app.domain.model.OutlineVersion
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
            "get_story_bible" -> storyBible(projectId)
            "propose_fact" -> proposeFact(projectId, arguments)
            "patch_outline" -> patchOutline(projectId, arguments)
            "queue_chapter" -> queueChapter(projectId, arguments.text("chapterId"))
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

    private suspend fun storyBible(projectId: String): ToolResult {
        val project = store.project(projectId) ?: return ToolResult.Fail("项目不存在")
        val state = project.continuityState
        val confirmed = state.factsWithSources.filter { it.confirmed }
        return ToolResult.Ok(
            buildString {
                appendLine("规则")
                appendLine(state.worldRules.joinToString("\n").ifBlank { "无" })
                appendLine("角色")
                appendLine(
                    state.characters.joinToString("\n") { "${it.name}：${it.appearance}" }.ifBlank { "无" }
                )
                appendLine("未解伏笔")
                appendLine(state.unresolvedThreads.joinToString("\n").ifBlank { "无" })
                appendLine("已确认事实")
                appendLine(confirmed.joinToString("\n") { it.statement }.ifBlank { "无" })
                appendLine("待确认")
                append(state.pendingFacts.joinToString("\n") { it.statement }.ifBlank { "无" })
            }
        )
    }

    private suspend fun proposeFact(projectId: String, arguments: JsonObject): ToolResult {
        val statement = arguments.text("statement")
        if (statement.length !in 2..80) return ToolResult.Fail("事实长度不合适")
        val kind = arguments.text("kind").ifBlank { "fact" }
        if (kind !in setOf("fact", "thread", "resolved")) return ToolResult.Fail("kind 不合法")
        val project = store.project(projectId) ?: return ToolResult.Fail("项目不存在")
        val pending = (project.continuityState.pendingFacts + ContinuityFact(
            id = newId(),
            statement = statement,
            sourceChapterId = null,
            confirmed = false,
            updatedAt = now(),
            kind = kind
        )).takeLast(40)
        store.saveProject(
            project.copy(
                continuityState = project.continuityState.copy(pendingFacts = pending),
                updatedAt = now()
            )
        )
        return ToolResult.Ok("已放入待确认")
    }

    private suspend fun patchOutline(projectId: String, arguments: JsonObject): ToolResult {
        val summary = arguments.text("summary")
        if (summary.isBlank()) return ToolResult.Fail("概要不能为空")
        val chapterId = arguments.text("chapterId")
        val project = store.project(projectId) ?: return ToolResult.Fail("项目不存在")
        val outline = store.latestOutline(projectId) ?: return ToolResult.Fail("章节不存在")
        val current = outline.chapters.find { it.id == chapterId } ?: return ToolResult.Fail("章节不存在")
        val title = arguments.text("title").ifBlank { current.title }
        val chapters = outline.chapters.map { item ->
            if (item.id == chapterId) item.copy(title = title, summary = summary) else item
        }
        val next = OutlineVersion(
            id = newId(),
            projectId = projectId,
            version = outline.version + 1,
            chapters = chapters,
            diffSummary = "agent 修改大纲",
            createdAt = now()
        )
        store.saveOutline(next, project.copy(activeOutlineVersionId = next.id, updatedAt = now()))
        return ToolResult.Ok("已更新大纲")
    }

    private suspend fun queueChapter(projectId: String, chapterId: String): ToolResult {
        val outline = store.latestOutline(projectId) ?: return ToolResult.Fail("章节不存在")
        if (outline.chapters.none { it.id == chapterId }) return ToolResult.Fail("章节不存在")
        return try {
            val jobId = store.queueChapter(projectId, chapterId)
            ToolResult.Ok("已排队 $chapterId $jobId")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ToolResult.Fail(error.message ?: "无法排队")
        }
    }
}

private fun JsonObject.text(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
