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

    /**
     * 回归：长书不能把整本大纲发出去。1500 章 × 80 字概要约 12 万字符，
     * 比任何中文网关的上下文都大，表现是「这本书它好像不认识」。
     */
    @Test
    fun longBookDoesNotShipTheWholeOutline() {
        val chapters = (0 until 600).map {
            OutlineItem("c$it", it, "第${it}章标题", "这一章的概要内容".repeat(8))
        }
        val pack = buildQuestionPack(chapters, emptyList(), "第 500 章讲了什么")
        assertEquals(600, pack.outlineCount)
        // 最近 30 章 + 命中章，是有界的；整本大纲不是
        assertTrue(
            "prompt 不该随章数线性膨胀：${pack.prompt.length}",
            pack.prompt.length < 20_000
        )
        assertTrue(pack.prompt.contains("全书共 600 章"))
    }

    /** 命中数多的片段要排在前面，而不是按大纲顺序取前四个。 */
    @Test
    fun strongestMatchesWinOverOutlineOrder() {
        val weak = "无关内容。".repeat(20) + "玉佩" + "别处。".repeat(20)
        val strong = "玉佩相关的细节在这里反复出现。玉佩玉佩玉佩。"
        val chapters = (0 until 5).map { OutlineItem("c$it", it, "第${it}章", "平淡的一章") }
        val revisions = listOf(
            revision("c0", weak),
            revision("c4", strong)
        )
        val pack = buildQuestionPack(chapters, revisions, "玉佩")
        val prompt = pack.prompt
        val strongAt = prompt.indexOf("反复出现")
        val weakAt = prompt.indexOf("无关内容")
        assertTrue("强命中片段必须先出现", strongAt >= 0 && (weakAt < 0 || strongAt < weakAt))
    }

    private fun revision(itemId: String, content: String) = ChapterRevision(
        id = "r-$itemId",
        projectId = "p",
        outlineItemId = itemId,
        outlineVersionId = "o",
        revision = 1,
        title = "t",
        content = content,
        createdAt = 1
    )
}
