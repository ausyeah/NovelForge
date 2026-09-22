package com.novelforge.app.agent

import com.novelforge.app.domain.model.ChapterRevision
import com.novelforge.app.domain.model.OutlineItem
import com.novelforge.app.domain.model.OutlineVersion
import com.novelforge.app.domain.model.Project
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeBook : NovelBookStore {
    var queued: String? = null
    var storedProject: Project? = null
    var savedProject: Project? = null
    var outline: OutlineVersion? = null
    var savedOutline: OutlineVersion? = null
    var chapterRevisions: List<ChapterRevision> = emptyList()
    var revisionWrites: Int = 0

    override suspend fun project(projectId: String): Project? =
        storedProject?.takeIf { it.id == projectId }

    override suspend fun saveProject(project: Project) {
        savedProject = project
        storedProject = project
    }

    override suspend fun latestOutline(projectId: String): OutlineVersion? =
        outline?.takeIf { it.projectId == projectId }

    override suspend fun saveOutline(version: OutlineVersion, project: Project) {
        savedOutline = version
        outline = version
        storedProject = project
    }

    override suspend fun revisions(projectId: String): List<ChapterRevision> =
        chapterRevisions.filter { it.projectId == projectId }

    override suspend fun queueChapter(projectId: String, outlineItemId: String): String {
        queued = outlineItemId
        return "job-1"
    }
}

private fun bookWithChapters(): FakeBook {
    val book = FakeBook()
    book.storedProject = Project(
        id = "p",
        title = "测试",
        createdAt = 1,
        updatedAt = 1
    )
    book.outline = OutlineVersion(
        id = "outline-1",
        projectId = "p",
        version = 1,
        chapters = listOf(
            OutlineItem(id = "c1", orderIndex = 0, title = "丢失", summary = "阿禾出门"),
            OutlineItem(id = "c2", orderIndex = 1, title = "无关", summary = "天气很好")
        ),
        createdAt = 1
    )
    book.chapterRevisions = listOf(
        ChapterRevision(
            id = "rev-c1",
            projectId = "p",
            outlineItemId = "c1",
            outlineVersionId = "outline-1",
            revision = 1,
            title = "丢失",
            content = "开头。" + "甲".repeat(800) + "玉佩在井边。" + "这段不应该整章出现在搜索结果里",
            summary = "阿禾弄丢了玉佩",
            createdAt = 1
        ),
        ChapterRevision(
            id = "rev-c2",
            projectId = "p",
            outlineItemId = "c2",
            outlineVersionId = "outline-1",
            revision = 1,
            title = "无关",
            content = "这一章只写了天气。",
            createdAt = 1
        )
    )
    return book
}

class NovelToolRegistryTest {
    @Test
    fun unknownToolAndBlankSearchDoNotThrow() = runBlocking {
        val book = FakeBook()
        val registry = NovelToolRegistry(book)
        val names = registry.specs().map { it.name }
        assertEquals(
            listOf(
                "search_chapters", "read_chapter", "get_story_bible",
                "propose_fact", "patch_outline", "queue_chapter"
            ),
            names
        )
        val unknown = registry.call("p", "delete_book", JsonObject(emptyMap()))
        assertTrue(unknown is ToolResult.Fail)
        val blank = registry.call("p", "search_chapters", buildJsonObject { put("query", JsonPrimitive("  ")) })
        assertTrue(blank is ToolResult.Fail)
        assertEquals(null, book.queued)
    }

    @Test
    fun searchReturnsAtMostFiveShortExcerpts() = runBlocking {
        val book = bookWithChapters()
        val registry = NovelToolRegistry(book)
        val result = registry.call("p", "search_chapters", buildJsonObject { put("query", JsonPrimitive("玉佩")) })
        val ok = result as ToolResult.Ok
        val lines = ok.content.lines().filter { it.isNotBlank() }
        assertTrue(lines.size <= 5)
        assertTrue(lines.all { it.contains("玉佩") })
        assertTrue(lines.all { it.substringAfterLast('|').length <= 120 })
        assertTrue(lines.none { it.contains("这段不应该整章出现在搜索结果里") })
    }

    @Test
    fun readChapterReturnsSummaryAndTailOnly() = runBlocking {
        val registry = NovelToolRegistry(bookWithChapters())
        val result = registry.call("p", "read_chapter", buildJsonObject { put("chapterId", JsonPrimitive("c1")) })
        val ok = result as ToolResult.Ok
        assertTrue(ok.content.startsWith("摘要：阿禾弄丢了玉佩"))
        assertTrue(ok.content.contains("玉佩"))
        assertTrue(ok.content.length < 500)
        val missing = registry.call("p", "read_chapter", buildJsonObject { put("chapterId", JsonPrimitive("nope")) })
        assertTrue(missing is ToolResult.Fail)
    }

    @Test
    fun storyBibleDoesNotTreatPendingAsConfirmed() = runBlocking {
        val book = bookWithChapters()
        book.storedProject = book.storedProject!!.copy(
            continuityState = book.storedProject!!.continuityState.copy(
                worldRules = listOf("不能复活"),
                unresolvedThreads = listOf("玉佩"),
                characters = listOf(
                    com.novelforge.app.domain.model.CharacterProfile(
                        id = "a", projectId = "p", name = "阿禾", appearance = "左眼有疤"
                    )
                ),
                factsWithSources = listOf(
                    com.novelforge.app.domain.model.ContinuityFact(
                        "f1", "阿禾丢了玉佩", "c1", confirmed = true, updatedAt = 1
                    )
                ),
                pendingFacts = listOf(
                    com.novelforge.app.domain.model.ContinuityFact(
                        "p1", "还没确认的事", "c1", confirmed = false, updatedAt = 2
                    )
                )
            )
        )
        val ok = NovelToolRegistry(book).call("p", "get_story_bible", JsonObject(emptyMap())) as ToolResult.Ok
        val confirmed = ok.content.substringAfter("已确认事实").substringBefore("待确认")
        assertTrue(confirmed.contains("阿禾丢了玉佩"))
        assertTrue(!confirmed.contains("还没确认的事"))
        assertTrue(ok.content.substringAfter("待确认").contains("还没确认的事"))
    }

    @Test
    fun proposeFactStaysPending() = runBlocking {
        val book = bookWithChapters()
        val before = book.storedProject!!
        val ok = NovelToolRegistry(book, now = { 9L }, newId = { "new-fact" }).call(
            "p",
            "propose_fact",
            buildJsonObject {
                put("statement", JsonPrimitive("阿禾左眼有疤"))
                put("kind", JsonPrimitive("thread"))
            }
        )
        assertTrue(ok is ToolResult.Ok)
        val fact = book.savedProject!!.continuityState.pendingFacts.single()
        assertEquals("new-fact", fact.id)
        assertEquals(false, fact.confirmed)
        assertEquals(before.continuityState.factsWithSources, book.savedProject!!.continuityState.factsWithSources)
        assertEquals(before.continuityState.unresolvedThreads, book.savedProject!!.continuityState.unresolvedThreads)
        val bad = NovelToolRegistry(book).call(
            "p",
            "propose_fact",
            buildJsonObject { put("statement", JsonPrimitive("x")) }
        )
        assertTrue(bad is ToolResult.Fail)
    }

    @Test
    fun patchOutlineWritesNewVersionWithoutTouchingRevisions() = runBlocking {
        val book = bookWithChapters()
        val ok = NovelToolRegistry(book, now = { 20L }, newId = { "outline-2" }).call(
            "p",
            "patch_outline",
            buildJsonObject {
                put("chapterId", JsonPrimitive("c1"))
                put("summary", JsonPrimitive("阿禾决定下井"))
            }
        )
        assertTrue(ok is ToolResult.Ok)
        val saved = book.savedOutline!!
        assertEquals("outline-2", saved.id)
        assertEquals(2, saved.version)
        assertEquals("阿禾决定下井", saved.chapters.first { it.id == "c1" }.summary)
        assertEquals("丢失", saved.chapters.first { it.id == "c1" }.title)
        assertEquals(0, book.revisionWrites)
    }

    @Test
    fun queueChapterRefusesMissingOutlineItem() = runBlocking {
        val book = bookWithChapters()
        val registry = NovelToolRegistry(book)
        val missing = registry.call("p", "queue_chapter", buildJsonObject { put("chapterId", JsonPrimitive("nope")) })
        assertTrue(missing is ToolResult.Fail)
        assertEquals(null, book.queued)
        val ok = registry.call("p", "queue_chapter", buildJsonObject { put("chapterId", JsonPrimitive("c1")) }) as ToolResult.Ok
        assertEquals("c1", book.queued)
        assertTrue(ok.content.contains("job-1"))
    }
}
