package com.novelforge.app.presentation.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 从书里打开灵感助手时，**不许带 tab 那套多返回栈参数**（回归护栏）。
 *
 * ## 那个 bug
 *
 * `onOpenChat` 原来是照抄 `onSelectTopLevel` 的三个参数：
 *
 * ```kotlin
 * navController.navigate(Routes.chat(projectId)) {
 *     popUpTo(navController.graph.findStartDestination().id) { saveState = true }
 *     launchSingleTop = true
 *     restoreState = true
 * }
 * ```
 *
 * 但那一套只对「一级目的地之间切换」成立。带上的后果（按 Navigation 2.8.5
 * 源码逐步走过）：`popUpTo(books, saveState)` 会把**当前这本书的大纲从栈里
 * 弹掉**，存进 `backStackMap[booksId]`。于是三个现象：
 *
 * 1. 顶栏「返回」落在**书架**，不是这本书的大纲 —— 用户预期是回书里。
 * 2. 之后点底栏「书架」，`restoreStateInternal` 把大纲恢复出来，于是
 *    **「书架」永远显示不出来** —— 屏幕上是大纲，底栏却亮着书架。
 * 3. 只有第一轮能恢复：第二轮 `backStackMap` 里 key 已存在，
 *    `takeWhile` 短路，第二份大纲状态直接被丢掉、永远泄漏。
 *
 * 三个问题同一个成因：一个多余的参数。改成普通 push 就都好了。
 *
 * ## 为什么这类东西必须有护栏
 *
 * 「跳过去时带上和别处一样的参数」读起来像是**为了保持一致**，
 * 而且它在任何单页测试里都看不出问题。改回一个普通 `navigate(...)` 也是
 * 一次「顺手简化」。所以把「书内跳灵感只准普通 push」钉住。
 *
 * **已知不覆盖**：真正点一下会落到哪个页面，JVM 测不了（要 NavController +
 * Android 运行时）。这里钉的是「有没有带那三个参数」。
 */
class BookScopedChatNavigationTest {

    @Test
    fun openingChatFromABookIsAPlainPush() {
        val code = stripComments(navGraphSource())
        val hop = blockStartingAt(code, "onOpenChat =")
        assertTrue(
            "找不到 onOpenChat 跳转（导航结构变了？）",
            hop.contains("Routes.chat(")
        )
        for (forbidden in listOf("popUpTo", "launchSingleTop", "restoreState", "saveState")) {
            assertFalse(
                "书内打开灵感助手时又带上了 `$forbidden` —— 那套参数只对「一级目的地" +
                    "之间切换」成立，带上会把当前这本书的大纲从栈里弹掉，返回就落到书架" +
                    "而不是回书里，之后点「书架」还会把大纲恢复出来。\n\n当前代码：\n$hop",
                hop.contains(forbidden)
            )
        }
    }

    @Test
    fun theBottomBarStillUsesTheMultiBackStackOptions() {
        // 反向对照：那一套参数在**底栏切 tab** 时是正确的、必须的。
        // 如果这条将来也红了，说明有人把它整个删了 —— 那样切 tab 就不再保留
        // 各自的返回栈，用户每次切走再切回来都要重新翻一遍。
        val code = stripComments(navGraphSource())
        val tab = blockStartingAt(code, "val onSelectTopLevel")
        for (required in listOf("popUpTo", "launchSingleTop", "restoreState")) {
            assertTrue(
                "底栏切 tab 的 navigate 上没有 `$required` —— 多返回栈失效了，" +
                    "切走再切回来会退回首页。\n\n当前代码：\n$tab",
                tab.contains(required)
            )
        }
    }

    @Test
    fun theBookTabClosesTheOpenBookBeforeNavigating() {
        // 书内目录不是路由而是 books 的内部状态，那套参数关不掉它，
        // 所以切书架前要先 close()。见 onSelectTopLevel 的注释。
        val code = stripComments(navGraphSource())
        val tab = blockStartingAt(code, "val onSelectTopLevel")
        assertTrue(
            "切到「书架」时不再先 close() 了 —— 书内目录会留在屏幕上出不去",
            tab.contains("close()")
        )
    }

    // ------------------------------------------------------------------ 取源码

    private fun navGraphSource(): String {
        val roots = listOfNotNull(
            File("").absoluteFile.takeIf { it.isDirectory },
            codeSourceDir()
        )
        for (root in roots) {
            var cursor: File? = root
            while (cursor != null) {
                val found = listOf(
                    File(cursor, "src/main/java/com/novelforge/app/presentation/navigation"),
                    File(cursor, "app/src/main/java/com/novelforge/app/presentation/navigation")
                ).firstOrNull { it.isDirectory }
                if (found != null) {
                    return (found.walkTopDown().firstOrNull { it.name == "NovelForgeNavGraph.kt" }
                        ?: throw AssertionError("navigation 下没有 NovelForgeNavGraph.kt"))
                        .readText()
                }
                cursor = cursor.parentFile
            }
        }
        throw AssertionError("找不到 navigation 源码目录")
    }

    private fun codeSourceDir(): File? = try {
        BookScopedChatNavigationTest::class.java.protectionDomain?.codeSource?.location
            ?.toURI()?.let { File(it) }?.takeIf { it.isDirectory }
    } catch (_: Exception) {
        null
    }

    /** 从 `marker` 处按花括号配对取块。不用正则猜闭合位置。 */
    private fun blockStartingAt(source: String, marker: String): String {
        val start = source.indexOf(marker)
        if (start < 0) throw AssertionError("源码里找不到 `$marker`")
        val from = source.indexOf('{', start)
        if (from < 0) throw AssertionError("`$marker` 后面没有大括号")
        var depth = 0
        var inString = false
        var i = from
        while (i < source.length) {
            val c = source[i]
            when {
                c == '\\' && inString -> i++
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
            i++
        }
        throw AssertionError("`$marker` 起的花括号没配平")
    }

    /** 剥掉注释 —— 各处解释「为什么不能带 popUpTo」的注释里会逐字出现那些词。 */
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
