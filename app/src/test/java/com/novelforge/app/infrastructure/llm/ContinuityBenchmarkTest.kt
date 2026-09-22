package com.novelforge.app.infrastructure.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuityBenchmarkTest {
    @Test
    fun chapterHintKeepsOldCanonThatRecencyDrops() {
        val scores = ContinuityBenchmark.run().associateBy { it.name }
        val blind = scores.getValue("不带记忆")
        val recent = scores.getValue("只留最近")
        val hinted = scores.getValue("按本章捞回")
        val dumped = scores.getValue("全量塞入")

        assertEquals(0, blind.requiredKept)
        assertEquals(0, recent.requiredKept)
        assertEquals(0, recent.polluted)
        assertEquals(3, hinted.requiredKept)
        assertEquals(3, dumped.requiredKept)
        assertEquals(0, hinted.polluted)
        assertEquals(0, recent.polluted)
        assertTrue(dumped.polluted > 0)
        assertTrue(hinted.promptChars < dumped.promptChars)
    }

    @Test
    fun hintedSliceStaysInsideCapsAndDropsUnconfirmedFacts() {
        val slice = MemorySelector.select(
            ContinuityBenchmark.fixture(),
            chapterHint = ContinuityBenchmark.CHAPTER_HINT
        )
        assertTrue(slice.characters.size <= MemorySelector.MAX_CHARACTERS)
        assertTrue(slice.factCount <= MemorySelector.MAX_FACTS)
        assertTrue(slice.threads.size <= MemorySelector.MAX_THREADS)
        assertTrue(slice.characterNames.contains(ContinuityBenchmark.REQUIRED_CHARACTER))
        assertTrue(slice.continuity.factsWithSources.none { it.statement == ContinuityBenchmark.POLLUTED_FACT })
        assertTrue(slice.continuity.pendingFacts.isEmpty())
    }
}
