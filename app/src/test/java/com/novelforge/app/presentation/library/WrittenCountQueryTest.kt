package com.novelforge.app.presentation.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「写了几章」那条 SQL 的两个条件不许被优化掉（回归护栏）。
 *
 * 分流规则本身在 [CoverTapRoutingTest] 里，那边验的是「0 章去写、12 章去读」。
 * 这边验的是**喂进来的数对不对** —— 规则再对，SQL 算错了也白搭。
 *
 * ## 两条都不能省
 *
 * - `content != ''` —— `chapter_revisions` 里确实存在空正文的行：写一半进程
 *   被杀、正文被清空、修订回滚到空。算进「写过了」的话，点封面进阅读器，
 *   目录里又是一排「未生成正文」—— 正是分流想避免的那个死胡同。
 * - `COUNT(DISTINCT outlineItemId)` —— `outlineItemId` + `revision` 上有唯一
 *   索引，一章改三稿就是三行。按 `COUNT(*)` 算会把「写过的章数」报成三倍：
 *   用户看到「共 12 章」而目录里只有 4 章。
 *
 * 两条都是「顺手清理一下」会被删掉的那种，而它们出错时**编译照过、界面照动**，
 * 只是数字悄悄错了。所以钉在这里。
 *
 * **已知不覆盖**：跑的是真 Room 才有意义，本文件只静态检查 SQL 文本 ——
 * 真正的执行验证要仪器测试（这台机器没有设备）。
 */
class WrittenCountQueryTest {

    /**
     * 这一条查询的 SQL 正文。
     *
     * 用「往上找最近的 `@Query`」而不是 `substringAfter(方法名)`：方法名在
     * `@Query` **之后**，从方法名往后切会切到下一个查询（或者文件尾）去 ——
     * 第一版就是这么写的，结果三条规则全在别处的内容上判红，测的完全不是
     * 这条 SQL。这正是「测试自己抓出自己的漏洞」那种错误的又一次。
     */
    private fun sqlOfThisQuery(): String {
        val code = stripComments(daosSource())
        val at = code.indexOf("fun observeWrittenCountsByProject")
        if (at < 0) throw AssertionError("找不到 observeWrittenCountsByProject")
        val before = code.substring(0, at)
        val q = before.lastIndexOf("@Query")
        if (q < 0) throw AssertionError("observeWrittenCountsByProject 前面没有 @Query")
        return before.substring(q, at)
    }

    private val query: String get() = sqlOfThisQuery()

    @Test
    fun emptyContentRowsAreExcluded() {
        assertTrue(
            "SQL 里没有 `content != ''` —— 空正文的修订行会被算成「写过了」，" +
                "点封面进阅读器却是一排「未生成正文」。\n\n当前 SQL：\n$query",
            query.contains("content != ''")
        )
    }

    @Test
    fun revisionsAreCountedPerChapterNotPerRow() {
        assertTrue(
            "SQL 里不是 `COUNT(DISTINCT outlineItemId)` —— 一章改三稿会被数成三章。\n\n" +
                "当前 SQL：\n$query",
            query.contains("COUNT(DISTINCT outlineItemId)")
        )
        assertFalse(
            "SQL 里出现了裸 `COUNT(*)` —— 那是按行数算，正是这个 bug。\n\n当前 SQL：\n$query",
            Regex("COUNT\\s*\\(\\s*\\*\\s*\\)").containsMatchIn(query)
        )
    }

    @Test
    fun itIsGroupedByProject() {
        assertTrue(
            "SQL 没有 `GROUP BY projectId` —— 返回的是全书一个总数，" +
                "分流会对每一本书给出同一个答案。\n\n当前 SQL：\n$query",
            Regex("GROUP\\s+BY\\s+projectId").containsMatchIn(query)
        )
    }

    // ------------------------------------------------------------------ 取源码

    private fun daosSource(): String {
        val roots = listOfNotNull(
            File("").absoluteFile.takeIf { it.isDirectory },
            codeSourceDir()
        )
        for (root in roots) {
            var cursor: File? = root
            while (cursor != null) {
                val found = listOf(
                    File(cursor, "src/main/java/com/novelforge/app/data/local"),
                    File(cursor, "app/src/main/java/com/novelforge/app/data/local")
                ).firstOrNull { it.isDirectory }
                if (found != null) {
                    val file = found.walkTopDown().firstOrNull { it.name == "Daos.kt" }
                        ?: throw AssertionError("data/local 下没有 Daos.kt")
                    return file.readText()
                }
                cursor = cursor.parentFile
            }
        }
        throw AssertionError("找不到 data/local 源码目录")
    }

    private fun codeSourceDir(): File? = try {
        WrittenCountQueryTest::class.java.protectionDomain?.codeSource?.location
            ?.toURI()?.let { File(it) }?.takeIf { it.isDirectory }
    } catch (_: Exception) {
        null
    }

    /**
     * 剥掉注释。
     *
     * 必须剥：这段查询上面挂着长注释，里面逐字写着 `content != ''` 和
     * `COUNT(DISTINCT outlineItemId)` —— 不剥的话每条规则都在自己那段
     * 解释文字上通过，测的等于没测。
     */
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
}
