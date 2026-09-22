package com.novelforge.app.agent

import com.novelforge.app.domain.model.ChapterRevision
import com.novelforge.app.domain.model.OutlineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookQuestionTest {
    @Test
    fun packSendsOutlineAndExcerptNotTheWholeChapter() {
        val filler = "甲".repeat(2_000)
        val chapters = listOf(
            OutlineItem("c1", 0, "丢失", "阿禾出门寻找玉佩"),
            OutlineItem("c2", 1, "天气", "只写了下雨")
        )
        val revisions = listOf(
            ChapterRevision(
                id = "r1",
                projectId = "p",
                outlineItemId = "c1",
                outlineVersionId = "o",
                revision = 1,
                title = "丢失",
                content = filler + "玉佩在井边。" + filler,
                createdAt = 1
            ),
            ChapterRevision(
                id = "r2",
                projectId = "p",
                outlineItemId = "c2",
                outlineVersionId = "o",
                revision = 1,
                title = "天气",
                content = "雨下了一整夜，和玉佩无关的后文" + filler,
                createdAt = 1
            )
        )
        val pack = buildQuestionPack(chapters, revisions, "玉佩在哪")
        assertEquals(2, pack.outlineCount)
        assertTrue(pack.prompt.contains("阿禾出门寻找玉佩"))
        assertTrue(pack.prompt.contains("玉佩在井边"))
        assertFalse(pack.prompt.contains(filler))
        assertTrue(pack.excerptCount >= 1)
        assertTrue(pack.prompt.length < filler.length)
    }
}
