package com.novelforge.app.infrastructure.llm

import com.novelforge.app.domain.model.CharacterProfile
import com.novelforge.app.domain.model.ContinuityFact
import com.novelforge.app.domain.model.ContinuityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySelectorTest {
    @Test
    fun select_dropsPendingAndHonorsExclusions() {
        val state = ContinuityState(
            worldRules = listOf("不能复活"),
            unresolvedThreads = listOf("玉佩", "旧案"),
            characters = listOf(
                CharacterProfile(id = "a", projectId = "p", name = "阿禾", appearance = "左眼有疤"),
                CharacterProfile(id = "b", projectId = "p", name = "老周", appearance = "白发")
            ),
            factsWithSources = listOf(
                ContinuityFact("f1", "阿禾丢了玉佩", "c1", confirmed = true, updatedAt = 1)
            ),
            pendingFacts = listOf(
                ContinuityFact("p1", "还没确认", "c1", confirmed = false, updatedAt = 2)
            )
        )
        val slice = MemorySelector.select(
            state,
            excludedCharacterIds = setOf("b"),
            excludedThreads = setOf("旧案")
        )
        assertEquals(listOf("阿禾"), slice.characterNames)
        assertEquals(listOf("玉佩"), slice.threads)
        assertEquals(1, slice.factCount)
        assertTrue(slice.continuity.pendingFacts.isEmpty())
        assertFalse(slice.continuity.factsWithSources.any { it.statement == "还没确认" })
    }

    @Test
    fun select_keepsOnlyTheNewestFacts() {
        val facts = (1..20).map {
            ContinuityFact("f$it", "事实$it", null, confirmed = true, updatedAt = it.toLong())
        }
        val slice = MemorySelector.select(ContinuityState(factsWithSources = facts))
        assertEquals(MemorySelector.MAX_FACTS, slice.factCount)
        assertEquals("事实20", slice.continuity.factsWithSources.last().statement)
        assertEquals("事实9", slice.continuity.factsWithSources.first().statement)
    }

    @Test
    fun parseMemoryNotes_readsFencedJsonAndDropsGarbage() {
        val raw = """
            好的。
            ```json
            {"facts":["阿禾左眼有疤","x"],"threads":["玉佩还没找回"],"resolved":["客栈的债已还"]}
            ```
        """.trimIndent()
        val notes = parseMemoryNotes(raw, now = 9)
        assertEquals(listOf("fact", "thread", "resolved"), notes.map { it.kind })
        assertTrue(notes.none { it.statement == "x" })
        assertTrue(notes.all { !it.confirmed })
    }

    @Test
    fun parseMemoryNotes_blankOrProseReturnsEmpty() {
        assertTrue(parseMemoryNotes("模型没按格式回答").isEmpty())
        assertTrue(parseMemoryNotes("").isEmpty())
    }
}
