package com.novelforge.app.presentation.library

import com.novelforge.app.data.local.WrittenCountRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 点封面的分流规则（回归护栏）。
 *
 * 用户原话：「主页这个应该是点击可以阅读，长按可以写书」—— 后半句因为长按还要
 * 留着管理菜单（换封面 / 重命名 / 删除 / 导出备份），所以实际改的是**点封面**。
 *
 * 分流规则：**写过了去读，一次没写过的送去写作。**
 *
 * 空书为什么不能也点「阅读」：那会进目录页，而目录里每张卡都是「未生成正文」，
 * 点下去什么都不发生 —— 一片死胡同，用户得自己退出来去找写作。而开始写是所有书
 * 的必经一步。
 */
class CoverTapRoutingTest {

    @Test
    fun aBookWithContentOpensForReading() {
        assertTrue(LibraryRouting.tapGoesToReading(writtenChapters = 1))
        assertTrue(LibraryRouting.tapGoesToReading(writtenChapters = 12))
        assertTrue(LibraryRouting.tapGoesToReading(writtenChapters = 545))
    }

    @Test
    fun aBookWithNoContentGoesToWriting() {
        // 0 章是「筹备中」那本书的常态：点它去写作，而不是进一个空目录
        assertFalse(LibraryRouting.tapGoesToReading(writtenChapters = 0))
        // 负数不可能出现，但规则要能吃住 —— 判据是「有没有写过」而不是「是不是 0」
        assertFalse(LibraryRouting.tapGoesToReading(writtenChapters = -1))
    }

    /**
     * `content != ''` 这个条件不能省。
     *
     * `chapter_revisions` 里**确实**存在空正文的行：写一半进程被杀、正文被清空、
     * 修订回滚到空。算进「写过了」的话，点封面进阅读器，目录里又是一排
     * 「未生成正文」—— 和分流想避免的是同一个死胡同，只是晚了一步。
     */
    @Test
    fun aBookWithZeroWrittenChaptersIsPresentWithZero() {
        // 0 必须**留在表里**而不是被滤掉：漏掉的话 writtenCount() 会走
        // `?: 0` 的兜底，行为一样 —— 但那样「这本书存在且一章没写」和
        // 「这本书还没被统计过」就再也分不开了，将来想给空书换个提示都做不到。
        val counts = LibraryRouting.toCountMap(
            listOf(
                WrittenCountRow("book-A", 3),
                WrittenCountRow("book-B", 0)
            )
        )
        assertEquals(3, counts["book-A"])
        assertEquals(0, counts["book-B"])
    }

    /**
     * 多稿修订要并成一章。
     *
     * `outlineItemId` + `revision` 上有唯一索引，所以一章改三稿就是三行。
     * 按行数算会把「写过的章数」报成三倍 —— 用户看到「共 12 章」而目录里
     * 只有 4 章，续读还会落到一个错的位置。
     */
    @Test
    fun multipleRowsForOneBookTakeTheMaxNotTheLast() {
        val counts = LibraryRouting.toCountMap(
            listOf(
                WrittenCountRow("book-A", 12),
                WrittenCountRow("book-A", 1)
            )
        )
        assertEquals(
            "同一本书两行时取最大值 12；`associate` 那种「后到覆盖先到」会得 1，" +
                "而 1 章和 12 章会分流到不同页面",
            12,
            counts["book-A"]
        )
    }

    @Test
    fun countsOfSeveralBooksCoexist() {
        val counts = LibraryRouting.toCountMap(
            listOf(
                WrittenCountRow("book-A", 12),
                WrittenCountRow("book-B", 0),
                WrittenCountRow("book-C", 545)
            )
        )
        assertEquals(12, counts["book-A"])
        assertEquals(0, counts["book-B"])
        assertEquals(545, counts["book-C"])
    }

    /**
     * 顶栏副标题和长按菜单不能还在说旧的手势。
     *
     * 它写着「点击封面写作」而代码已经是点封面阅读 —— 用户按提示做，结果和
     * 提示相反。README 里同一句话也有一份。
     */
    @Test
    fun theShelfSubtitleMatchesTheActualGestures() {
        val source = libraryScreen()
        val code = stripComments(source)
        val subtitle = Regex("subtitle\\s*=\\s*\"([^\"]*封面[^\"]*)\"")
            .find(code)?.groupValues?.get(1)
        assertTrue(
            "书架顶栏没有一条提到封面的副标题了 —— 那条是唯一说明手势的地方",
            subtitle != null
        )
        assertTrue(
            "副标题还在说「点封面写作」，但代码已经改成点封面阅读：$subtitle",
            !subtitle!!.contains("点击封面写作")
        )
        assertTrue(
            "副标题该说清楚长按是干什么的（用户已经反馈过一次长按的问题）：$subtitle",
            subtitle.contains("长按")
        )
    }
}

private fun libraryScreen(): String {
    val roots = listOfNotNull(
        File("").absoluteFile.takeIf { it.isDirectory },
        codeSourceDir()
    )
    for (root in roots) {
        var cursor: File? = root
        while (cursor != null) {
            val found = listOf(
                File(cursor, "src/main/java/com/novelforge/app/presentation/library"),
                File(cursor, "app/src/main/java/com/novelforge/app/presentation/library")
            ).firstOrNull { it.isDirectory }
            if (found != null) {
                return (found.walkTopDown().firstOrNull { it.name == "LibraryScreen.kt" }
                    ?: throw AssertionError("presentation/library 下没有 LibraryScreen.kt"))
                    .readText()
            }
            cursor = cursor.parentFile
        }
    }
    throw AssertionError("找不到 presentation/library 源码目录")
}

private fun codeSourceDir(): File? = try {
    CoverTapRoutingTest::class.java.protectionDomain?.codeSource?.location
        ?.toURI()?.let { File(it) }?.takeIf { it.isDirectory }
} catch (_: Exception) {
    null
}

private fun stripComments(source: String): String = buildString {
    source.lineSequence().forEach { line ->
        var inString = false
        var cut = line.length
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' && inString -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && i + 1 < line.length && line[i + 1] == '/' -> {
                    cut = i; i = line.length
                }
            }
            if (i < line.length) i++
        }
        append(line, 0, cut).append('\n')
    }
}
