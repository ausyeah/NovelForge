package com.novelforge.app.presentation.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 起始目的地不能有返回，也没有任何东西能对它 `popBackStack()`（回归护栏）。
 *
 * 这条是**白屏**护栏，不是审美规则。
 *
 * 书架页原来把 `onBack = { navController.popBackStack() }` 传给了顶栏。它看起来
 * 像"起始页没东西可弹，按了等于没按"—— 我自己也是这么描述的，**错的**。
 * 查 Navigation 2.8.5 的源码：
 *
 * - `popBackStack()` 走的是 `inclusive = true`（`NavController.kt:450-457`），
 *   所以 `books` **会**被弹掉；
 * - 弹完只剩根图，而 `dispatchOnDestinationChanged` 里有个 while 把栈尾的
 *   `NavGraph` 也弹掉（`NavController.kt:1068-1070`）→ **`backQueue` 变空**；
 * - `NavHost` 的 `visibleEntries.lastOrNull()` 于是是 null，整个 NavHost 一个
 *   节点都不发射 → **白屏，底栏还在，导航图已销毁**，只能杀掉应用重开。
 *
 * 而且这是 app 里用得最多的屏幕上、一次点击就能走到的路径。
 *
 * 顺带钉住另一条：书内目录不是路由而是 `books` 的内部状态，`books` 又是
 * startDestination，那套多返回栈参数**关不掉它**（`popUpTo` 非 inclusive 弹不到
 * 自己；`restoreState` 抢在 `launchSingleTop` 前面命中）。所以底栏「书架」必须
 * 显式 `close()`，否则那个 tab 在书内是彻底空操作。
 *
 * **已知不覆盖**：`popUpTo` / `restoreState` 的实际行为 JVM 测不了（要真
 * NavController + Android 运行时），这里只能静态钉住「起始页不碰返回栈」。
 * 跨 tab 切回书架时 `restoreState` 仍可能把别的栈换上来，那条要真机验。
 */
class StartDestinationBackTest {

    @Test
    fun theShelfTopBarHasNoBackAffordance() {
        val lib = libraryScreenSource()
        val code = stripComments(lib)

        // 书架的两处顶栏（空书架 / 有书）都不许再传 onBack
        val shelfBars = Regex("PaperTopBar\\(\\s*title\\s*=\\s*\"书架\"[^)]*\\)")
            .findAll(code)
            .map { it.value }
            .toList()
        assertTrue("没找到书架的 PaperTopBar 调用，源码结构变了？", shelfBars.isNotEmpty())
        for (bar in shelfBars) {
            assertFalse(
                "书架（startDestination）的顶栏又传了 onBack —— 点下去会弹空 backQueue，" +
                    "白屏且导航图销毁：$bar",
                bar.contains("onBack")
            )
        }

        // LibraryScreen 不该再有一个只服务于书架的 onBack 形参
        assertFalse(
            "LibraryScreen 又多了一个 onBack 形参；书内目录用的是 viewModel.close()，" +
                "不需要它。留着就一定会有人再传回书架那两处。",
            Regex("fun LibraryScreen\\([^)]*onBack").containsMatchIn(code)
        )
    }

    @Test
    fun nothingOnTheShelfRoutePopsTheBackStack() {
        val graph = stripComments(navGraphSource())

        val booksBlock = blockStartingAt(graph, "composable(Routes.BOOKS)")
        assertTrue(
            "books 这个 startDestination 里出现了 popBackStack —— 会把 backQueue 弹空，" +
                "白屏且导航图销毁。",
            !booksBlock.contains("popBackStack()")
        )
    }

    @Test
    fun theBooksTabClosesTheOpenBookItself() {
        val graph = stripComments(navGraphSource())

        assertTrue(
            "底栏「书架」没有关书：书内目录是 books 的内部状态，popUpTo/launchSingleTop/" +
                "restoreState 那套参数在书内关不掉它，不显式 close() 的话点书架是空操作。",
            Regex("TopLevelDestination\\.Books\\)\\s*\\{\\s*booksTab\\.viewModel\\?\\.close\\(\\)")
                .containsMatchIn(graph)
        )
        // holder 必须真的被回填，否则上面那句永远对着 null 调用
        assertTrue(
            "booksTab.viewModel 从来没被回填 —— 切书架时 close() 打在一个 null 上。",
            Regex("SideEffect\\s*\\{\\s*booksTab\\.viewModel\\s*=").containsMatchIn(graph)
        )
    }

    // ------------------------------------------------------------------ 取源码

    /**
     * 从 `marker` 所在处起，按花括号配对取出整个块。
     *
     * 不用正则去匹 `\{.*?\}`：嵌套一深就配错，而且闭合括号的缩进一变就失配 ——
     * 第一版就是这么写的，结果「没找到块」假报红，测的其实是自己。
     *
     * 注释已在上游剥过，这里再跳一次字符串内部（模型提示词、路由模板里
     * 都可能有花括号）。
     */
    private fun blockStartingAt(source: String, marker: String): String {
        val start = source.indexOf(marker)
        if (start < 0) throw AssertionError("源码里找不到 `$marker`，结构变了？")
        var depth = 0
        var seenOpen = false
        var inString = false
        var i = start
        while (i < source.length) {
            val c = source[i]
            when {
                c == '\\' && inString -> i++
                c == '"' -> inString = !inString
                !inString && c == '{' -> { depth++; seenOpen = true }
                !inString && c == '}' -> {
                    depth--
                    if (seenOpen && depth == 0) return source.substring(start, i + 1)
                }
            }
            i++
        }
        throw AssertionError("从 `$marker` 起的花括号没配平，源码结构变了？")
    }

    private fun libraryScreenSource(): String = readFirst(
        "presentation/library", "LibraryScreen.kt"
    )

    private fun navGraphSource(): String = readFirst(
        "presentation/navigation", "NovelForgeNavGraph.kt"
    )

    private fun readFirst(dir: String, name: String): String {
        val roots = listOfNotNull(
            File("").absoluteFile.takeIf { it.isDirectory },
            codeSourceDirectory()
        )
        for (root in roots) {
            var cursor: File? = root
            while (cursor != null) {
                val found = listOf(
                    File(cursor, "src/main/java/com/novelforge/app/$dir"),
                    File(cursor, "app/src/main/java/com/novelforge/app/$dir")
                ).firstOrNull { it.isDirectory }
                if (found != null) {
                    val file = found.walkTopDown().firstOrNull { it.name == name }
                        ?: throw AssertionError("$dir 下没有 $name")
                    return file.readText()
                }
                cursor = cursor.parentFile
            }
        }
        throw AssertionError("找不到 $dir 源码目录（源码不在预期位置）")
    }

    private fun codeSourceDirectory(): File? = try {
        StartDestinationBackTest::class.java
            .protectionDomain?.codeSource?.location
            ?.toURI()
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }
    } catch (_: Exception) {
        null
    }

    /**
     * 剥掉注释。必须剥：这一处修复上面挂着长注释，里面逐字写着
     * `onBack`、`popBackStack()`、`startDestination` 这些词，不剥就自己撞自己。
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
                        cut = i
                        i = line.length
                    }
                }
                if (i < line.length) i++
            }
            append(line, 0, cut).append('\n')
        }
    }
}
