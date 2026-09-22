package com.novelforge.app.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAgentTest {
    @Test
    fun replyStopsBeforeTheStepCap() = runBlocking {
        var calls = 0
        val agent = NovelAgent(
            NovelToolRegistry(bookForAgent()),
            AgentModel { _, _, _ ->
                calls += 1
                if (calls < 3) {
                    AgentDecision.CallTool(
                        "search_chapters",
                        buildJsonObject { put("query", JsonPrimitive("玉佩")) }
                    )
                } else {
                    AgentDecision.Reply("够了")
                }
            }
        )
        val trace = agent.run("p", "玉佩在哪")
        assertEquals(2, trace.count { it.kind == "tool" })
        assertEquals("够了", trace.last().detail)
    }

    @Test
    fun sixthToolEndsTheLoop() = runBlocking {
        val agent = NovelAgent(
            NovelToolRegistry(bookForAgent()),
            AgentModel { _, _, _ ->
                AgentDecision.CallTool(
                    "search_chapters",
                    buildJsonObject { put("query", JsonPrimitive("玉佩")) }
                )
            }
        )
        val trace = agent.run("p", "一直搜")
        assertEquals(6, trace.count { it.kind == "tool" })
        assertTrue(trace.last().detail.contains("6 步上限"))
    }
}

private fun bookForAgent(): FakeBook {
    val book = FakeBook()
    book.storedProject = com.novelforge.app.domain.model.Project(
        id = "p", title = "测试", createdAt = 1, updatedAt = 1
    )
    book.outline = com.novelforge.app.domain.model.OutlineVersion(
        id = "o",
        projectId = "p",
        version = 1,
        chapters = listOf(
            com.novelforge.app.domain.model.OutlineItem("c1", 0, "丢失", "阿禾出门")
        ),
        createdAt = 1
    )
    book.chapterRevisions = listOf(
        com.novelforge.app.domain.model.ChapterRevision(
            id = "r",
            projectId = "p",
            outlineItemId = "c1",
            outlineVersionId = "o",
            revision = 1,
            title = "丢失",
            content = "玉佩在井边。",
            summary = "丢了玉佩",
            createdAt = 1
        )
    )
    return book
}
