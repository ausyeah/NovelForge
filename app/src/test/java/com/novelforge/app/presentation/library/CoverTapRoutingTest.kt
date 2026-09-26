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
     * 点封面**不许**自动跳进阅读器。
     *
     * 用户原话：「没让你点进书自动跳转最近阅读啊，别自作主张」
     *
     * 背景：把「点封面 = 继续写」改成「点封面 = 读」的那一轮里，我顺手在封面的
     * 点击里多接了一句 `pendingReadId = project.id`。于是点一下封面就直接进阅读器，
     * 而且落在上次读到的那一章 —— 跳过整张目录页。
     *
     * 用户只说了「点封面可以阅读」。**「进这本书」和「读上次那一章」是两件事**：
     * 前者是封面这一下该给的，后者是书内目录顶部「▶ 续读：第 N 章」那个按钮
     * 明确标着的东西。多做的那一步没人要求，而且跳过了用户想看到的那一层。
     *
     * 这条钉住「只有续读按钮能设 `pendingReadId`」。
     */
    @Test
    fun onlyTheExplicitResumeButtonJumpsIntoTheReader() {
        val code = stripComments(libraryScreen())
        // 只看赋**非 null** 的地方（`pendingReadId = null` 是清空，不是跳转）
        val setters = Regex("pendingReadId\\s*=\\s*(?!null)\\S+")
            .findAll(code)
            .map { it.value.substringAfter("=").trim() }
            .toList()
        assertTrue(
            "找不到任何设 pendingReadId 的地方 —— 「▶ 续读」按钮失效了？" +
                "它靠 pendingReadId 落到上次那一章。",
            setters.isNotEmpty()
        )
        assertEquals(
            "只有「▶ 续读」按钮可以设 pendingReadId，实际有 ${setters.size} 处：$setters。" +
                "点封面**不许**自动跳进阅读器（用户原话：「别自作主张」）。",
            1,
            setters.size
        )
        // 唯一那处必须绑在长按菜单/顶部续读用的 `target` 上，
        // 不许是封面点击用的 `project` —— 后者就是「点封面自动跳」。
        assertEquals(
            "唯一那处赋值绑的是 $setters，应该绑 `target`（续读按钮）。" +
                "绑 `project` 就意味着封面点击会跳进阅读器。",
            "target.id",
            setters.first()
        )
    }

    /**
     * 书架这一屏不许出现会骗人的手势说明。
     *
     * ## 这条测试改过一次，因为它守的东西变了
     *
     * 原来它要求「副标题必须存在，而且要说清楚长按是干什么的」。副标题当时写的是
     * 「点封面阅读，长按可写作、换封面、重命名、删除」，测试只查了它**没**说
     * 「点击封面写作」，就放行了 ——
     *
     * **但那句话本身就是错的**：点封面现在只打开目录，不跳阅读器。
     * 于是测试绿灯，界面在骗人。用户照着提示做，得到的结果和提示相反。
     *
     * 测试只查了「没写错的那一种写法」，没查「写的是不是对的」。
     *
     * 现在副标题**整条删掉了**，这比改对文案更彻底：长按是平台惯例，
     * 而用户在别的页面已经明确说过不需要这种逐项操作提示。
     * 于是判据从「文案对不对」变成「**根本没有文案**」——
     * 一个不存在的句子不可能说错话。
     */
    @Test
    fun theShelfRendersNoGestureInstructionsAtAll() {
        val code = stripComments(libraryScreen())
        // **只看书架这一屏。** 第一版断言的是「整个文件里没有 subtitle」，
        // 结果目录页那个正经的「已生成 N/M 章」把它判死了 —— 判据比意图宽，
        // 抓到了不该抓的东西。判据必须和它在守的那件事一样窄。
        val start = code.indexOf("""PaperTopBar(title = "书架")""")
        assertTrue("没找到书架顶栏", start >= 0)
        val shelf = code.substring(start, minOf(code.length, start + 400))

        val subtitle = Regex("subtitle\\s*=\\s*\"([^\"]*)\"")
            .find(shelf)?.groupValues?.get(1)
        assertTrue(
            "书架顶栏又挂上了操作提示副标题：$subtitle。" +
                "它之前教用户「点封面阅读」，而点封面只打开目录 —— 提示是假的。",
            subtitle == null
        )
        for (phrase in listOf("点封面", "长按可", "点击封面")) {
            assertFalse(
                "书架这一屏又出现了操作提示「$phrase」：\n$shelf",
                shelf.contains(phrase)
            )
        }
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

/**
 * 剥掉注释，**块注释和行注释都要剥**。
 *
 * 原来这里只处理 `//`，`/** ... */` 整块留下了 —— 而这个文件里绝大多数
 * 解释性文字（包括那句骗人的「点封面阅读」为什么被删）都写在 KDoc 里。
 *
 * 后果不是「测试没抓到 bug」，而是**测试抓错了东西**：
 * 「副标题不许说错手势」那条断言去数 KDoc 里的字，而不是数真正渲染出来的字。
 * 之前它绿灯通过，恰恰是因为真正的副标题是错的、而它查的是注释。
 *
 * 教训：**断言工具本身不完整时，绿色不代表结论成立**，只代表没人核对过工具。
 */
private fun stripComments(source: String): String {
    val out = StringBuilder(source.length)
    var i = 0
    var inBlock = false
    while (i < source.length) {
        val c = source[i]
        val n = if (i + 1 < source.length) source[i + 1] else ' '
        when {
            inBlock -> {
                if (c == '*' && n == '/') {
                    inBlock = false
                    i += 2
                } else {
                    i++
                }
            }
            c == '/' && n == '*' -> {
                inBlock = true
                i += 2
            }
            c == '/' && n == '/' -> {
                val end = source.indexOf('\n', i)
                i = if (end < 0) source.length else end
            }
            c == '"' -> {
                // 字符串字面量整体抄过去，注释标记在引号里不算注释
                out.append(c)
                i++
                while (i < source.length) {
                    val d = source[i]
                    out.append(d)
                    if (d == '\\' && i + 1 < source.length) {
                        out.append(source[i + 1])
                        i += 2
                        continue
                    }
                    i++
                    if (d == '"') break
                }
            }
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString()
}
