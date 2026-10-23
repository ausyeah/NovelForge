package com.novelforge.app.presentation.chapter

import androidx.lifecycle.SavedStateHandle
import com.novelforge.app.domain.model.OutlineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守住正文页最容易丢的两样东西：相邻章节的判定，和作家手动关掉的记忆条目。
 *
 * 纯 JVM 单测里不实例化 ChapterViewModel（它的 viewModelScope 要 Main dispatcher，
 * 而本模块没有 kotlinx-coroutines-test），所以这里直接测它背后的纯函数与存档编解码。
 */
class ChapterNavigationTest {
    private fun chapter(id: String, orderIndex: Int) = OutlineItem(
        id = id,
        orderIndex = orderIndex,
        title = "第 $orderIndex 章",
        summary = ""
    )

    // 留洞的大纲：0、2、5、7（删过章节，orderIndex 不连续）
    private val gapped = listOf(
        chapter("c0", 0),
        chapter("c2", 2),
        chapter("c5", 5),
        chapter("c7", 7)
    )

    @Test
    fun nextChapter_picksTheSmallestLargerOrder_evenWhenThereAreGaps() {
        assertEquals("c2", nextChapterIn(gapped, orderIndex = 0)?.id)
        assertEquals("c5", nextChapterIn(gapped, orderIndex = 2)?.id)
    }

    @Test
    fun nextChapter_isNullAtTheLastChapter() {
        assertNull(nextChapterIn(gapped, orderIndex = 7))
    }

    @Test
    fun previousChapter_picksTheLargestSmallerOrder_evenWhenThereAreGaps() {
        assertEquals("c5", previousChapterIn(gapped, orderIndex = 7)?.id)
        assertEquals("c2", previousChapterIn(gapped, orderIndex = 5)?.id)
    }

    @Test
    fun previousChapter_isNullAtTheFirstChapter() {
        assertNull(previousChapterIn(gapped, orderIndex = 0))
    }

    @Test
    fun previousAndNext_areSymmetricOnASingleChapterBook() {
        val only = listOf(chapter("c0", 0))

        assertNull(previousChapterIn(only, orderIndex = 0))
        assertNull(nextChapterIn(only, orderIndex = 0))
    }

    @Test
    fun neighbors_neverReturnTheChapterItself() {
        // 同一章不能出现在自己两个方向上：按钮点下去会原地打转
        gapped.forEach { item ->
            assertTrue(nextChapterIn(gapped, item.orderIndex)?.orderIndex != item.orderIndex)
            assertTrue(previousChapterIn(gapped, item.orderIndex)?.orderIndex != item.orderIndex)
        }
    }

    @Test
    fun exclusionSet_survivesTheSavedStateRoundTrip() {
        val handle = SavedStateHandle()
        val pruned = setOf("char-2", "char-5")

        handle[EXCLUDED_CHARACTERS_KEY] = exclusionToSavedState(pruned)

        // 必须是 ArrayList<String>：这是 Bundle 的原生类型，进程被杀后整份 SavedStateHandle
        // 会被序列化再读回来，换成别的容器读不回来
        assertTrue(handle.get<List<*>>(EXCLUDED_CHARACTERS_KEY) is ArrayList<*>)
        assertEquals(pruned, exclusionFromSavedState(handle.get<List<*>>(EXCLUDED_CHARACTERS_KEY)))
    }

    @Test
    fun exclusionFromSavedState_survivesJunkInOldBundles() {
        // 存档可能来自旧版本；不该在第一次用这个字段时才炸在强转上
        assertEquals(setOf("thread-a"), exclusionFromSavedState(listOf("thread-a", 7, null)))
        assertEquals(emptySet<String>(), exclusionFromSavedState(null))
    }
}
