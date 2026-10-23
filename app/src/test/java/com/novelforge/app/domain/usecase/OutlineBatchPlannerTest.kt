package com.novelforge.app.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 超长篇「分批滚动生成大纲」的回归测试。
 *
 * 症状：模型一次吐回整本书的章节时，如果运行时没有批大小护栏，全书大纲会被
 * 一次性落库并直接 COMPLETED —— 不再分批、不等正文，超长篇后半段彻底失去质量。
 * 这组测试把「一次只推进一批」钉死。
 */
class OutlineBatchPlannerTest {

    @Test
    fun window_coversTenChaptersByDefault() {
        assertEquals(1..10, OutlineBatchPlanner.window(chapterNumber = 1, chapterCount = 300))
        assertEquals(11..20, OutlineBatchPlanner.window(chapterNumber = 11, chapterCount = 300))
    }

    @Test
    fun window_clampsToBookEnd() {
        assertEquals(96..100, OutlineBatchPlanner.window(chapterNumber = 96, chapterCount = 100))
        assertEquals(100..100, OutlineBatchPlanner.window(chapterNumber = 100, chapterCount = 100))
    }

    @Test
    fun window_respectsCustomBatchSize() {
        assertEquals(1..5, OutlineBatchPlanner.window(chapterNumber = 1, chapterCount = 100, batchSize = 5))
    }

    @Test
    fun acceptedCount_trimsOverflowToSingleBatch() {
        // 模型一次返回 300 章、全书还剩 300 章：本批仍然只收 10 章
        assertEquals(10, OutlineBatchPlanner.acceptedCount(returned = 300, remainingInBook = 300))
        // 已写到第 20 章，模型又返回 50 章：本批只收 10 章
        assertEquals(10, OutlineBatchPlanner.acceptedCount(returned = 50, remainingInBook = 280))
    }

    @Test
    fun acceptedCount_keepsUnderSizedBatch() {
        assertEquals(4, OutlineBatchPlanner.acceptedCount(returned = 4, remainingInBook = 300))
        assertEquals(0, OutlineBatchPlanner.acceptedCount(returned = 0, remainingInBook = 300))
    }

    @Test
    fun acceptedCount_neverExceedsRemainingChapters() {
        assertEquals(3, OutlineBatchPlanner.acceptedCount(returned = 10, remainingInBook = 3))
        assertEquals(0, OutlineBatchPlanner.acceptedCount(returned = 10, remainingInBook = 0))
    }

    @Test
    fun shouldPause_untilWholeBookIsOutlined() {
        // 本批 10 章落库后还有剩余 → 暂停，等正文写完再继续下一批
        assertTrue(OutlineBatchPlanner.shouldPause(nextOrderIndex = 0, accepted = 10, totalChapterCount = 300))
        assertTrue(OutlineBatchPlanner.shouldPause(nextOrderIndex = 290, accepted = 5, totalChapterCount = 300))
        // 全书已写完 → 不暂停，直接完成
        assertFalse(OutlineBatchPlanner.shouldPause(nextOrderIndex = 290, accepted = 10, totalChapterCount = 300))
    }

    @Test
    fun shouldPause_falseExactlyWhenOutlineCompletes_butBodyMustStillRun() {
        // shouldPause=false 只表示「大纲批次数到此为止」，绝不表示「正文已完成」。
        // finishOutlineExecution 在这个分支过去直接 return、不 driveAutoNext，导致：
        // 小书（≤10 章）、超长篇的最后一批、以及模型一次吐全书被护栏截到刚好凑齐时，
        // 大纲齐了就再也不写正文（beta66 修复的回归点）。这里把边界钉死。
        assertFalse(OutlineBatchPlanner.shouldPause(nextOrderIndex = 0, accepted = 100, totalChapterCount = 100))
        assertFalse(OutlineBatchPlanner.shouldPause(nextOrderIndex = 90, accepted = 10, totalChapterCount = 100))
        // 差一章没凑齐 → 仍要暂停等正文
        assertTrue(OutlineBatchPlanner.shouldPause(nextOrderIndex = 90, accepted = 9, totalChapterCount = 100))
    }

    @Test
    fun overflowNeverSkipsThePause() {
        // 组装场景：全书 300 章，已生成 0 章，模型一次返回 300 章
        val total = 300
        val nextOrderIndex = 0
        val accepted = OutlineBatchPlanner.acceptedCount(
            returned = total,
            remainingInBook = total - nextOrderIndex
        )

        assertEquals(10, accepted)
        assertTrue(OutlineBatchPlanner.shouldPause(nextOrderIndex, accepted, total))
        // 下一批从已生成到的下一章继续
        assertEquals(11..20, OutlineBatchPlanner.window(chapterNumber = nextOrderIndex + accepted + 1, chapterCount = total))
    }

    @Test
    fun useCaseBatchSizeSharesPlannerConstant() {
        assertEquals(OutlineBatchPlanner.BATCH_SIZE, GenerateOutlineUseCase.OUTLINE_BATCH_SIZE)
    }
}
