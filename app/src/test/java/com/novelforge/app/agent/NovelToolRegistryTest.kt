package com.novelforge.app.agent

import com.novelforge.app.domain.model.ChapterRevision
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
    override suspend fun project(projectId: String): Project? = null
    override suspend fun saveProject(project: Project) = Unit
    override suspend fun latestOutline(projectId: String): OutlineVersion? = null
    override suspend fun saveOutline(version: OutlineVersion, project: Project) = Unit
    override suspend fun revisions(projectId: String): List<ChapterRevision> = emptyList()
    override suspend fun queueChapter(projectId: String, outlineItemId: String): String {
        queued = outlineItemId
        return "job-1"
    }
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
}
