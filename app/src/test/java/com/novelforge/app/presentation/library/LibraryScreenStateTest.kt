package com.novelforge.app.presentation.library

import com.novelforge.app.domain.model.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 书架顶部「继续写」挑哪本书、以及转屏后还能不能落回原来那一章。
 * 这两条是纯逻辑，所以不碰 ViewModel：ReadingPositionStore 需要真实 Context，
 * 把它拉进单测就得先加一层接口。
 */
class LibraryScreenStateTest {
    private fun project(id: String, updatedAt: Long) = Project(
        id = id,
        title = "书$id",
        createdAt = 0L,
        updatedAt = updatedAt
    )

    private fun chapter(orderIndex: Int, content: String? = "正文") =
        LibraryChapter(orderIndex, "第 $orderIndex 章", content)

    @Test
    fun continueWritingPicksMostRecentlyUpdatedBook() {
        val shelf = listOf(
            project("a", updatedAt = 100L),
            project("c", updatedAt = 900L),
            project("b", updatedAt = 500L)
        )

        assertEquals("c", pickContinueWritingProject(shelf)?.id)
    }

    @Test
    fun continueWritingIsNullOnEmptyShelfSoHeroStaysHidden() {
        assertNull(pickContinueWritingProject(emptyList()))
    }

    @Test
    fun readingChapterIsReDerivedFromOrderIndexAfterRotation() {
        val chapters = listOf(chapter(1), chapter(2), chapter(3))

        assertEquals(2, resolveReadingChapter(chapters, 2)?.orderIndex)
    }

    @Test
    fun deletedChapterFallsBackToDirectoryInsteadOfBlankReader() {
        // 转屏时那一章可能已经被删掉，或大纲换过版本。
        // 解析不到就回目录页，好过把读者留在没有正文的阅读界面里。
        val chapters = listOf(chapter(1), chapter(3))

        assertNull(resolveReadingChapter(chapters, 2))
    }

    @Test
    fun nullOrderIndexMeansNotReading() {
        assertNull(resolveReadingChapter(listOf(chapter(1)), null))
    }
}
