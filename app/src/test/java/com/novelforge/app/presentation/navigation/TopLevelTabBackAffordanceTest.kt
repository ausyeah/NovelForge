package com.novelforge.app.presentation.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「tab 能到的页面，顶栏不该再给一个返回」（回归护栏）。
 *
 * 用户原话：「现在这种界面设计不需要左上角的返回了吧」，
 * 后面又追了一次：「灵感和设置左上角的返回还在」。
 *
 * ## 判据
 *
 * **底栏三个 tab（书架 / 灵感 / 设置）就在那一行。** 屏幕上再放一个「返回」，
 * 点它能到的地方，底栏里都有更直接的一个按钮 —— 纯冗余，还多占一格。
 *
 * **但有三个例外，一个都不能动：**
 *
 * 1. **从书里打开的灵感助手** —— 底栏三个 tab 里没有一个是「这本书」：
 *    书架是整个书架，灵感是全局桶。想去回那本书只有顶栏这一条路。
 *    所以 ChatScreen 按 `isBookScoped()` 决定给不给，而不是一刀切。
 * 2. **新建小说 / 创作设置 / 正文** —— 这三页**没有底栏**，顶栏返回是唯一
 *    可见的退路。`Routes.kt` 里那条「隐藏底栏又把返回藏起来是这份设计唯一
 *    明确禁止的失败模式」说的就是这个。
 * 3. **账本 / 备份与导出** —— 设置的二级页，「上一层」不是 tab，返回仍然有用。
 *
 * ## 这类改动为什么需要护栏
 *
 * 「每页都放一个返回」是个非常自然的默认写法，而且它在**任何单页上都能编译、
 * 都能跑**。删掉之后没有任何东西会提醒下一个人把它加回来 —— 上一次就是这么
 * 加回来的。所以把「哪些页面该有、哪些不该有」逐条钉住。
 *
 * **已知不覆盖**：真正点一下会去哪，JVM 测不了（要 NavController + Android
 * 运行时）。这里只钉住「哪个页面渲染了返回」。
 */
class TopLevelTabBackAffordanceTest {

    @Test
    fun theShelfHasNoBackAffordance() {
        // 起点页，而且历史上这里曾经是白屏（popBackStack inclusive=true 弹空 backQueue）
        val bars = paperTopBarsIn(libraryScreen(), "书架")
        assertTrue("没找到书架的 PaperTopBar", bars.isNotEmpty())
        for (bar in bars) {
            assertFalse(
                "书架（tab 之一）的顶栏又出现了返回：$bar",
                bar.contains("onBack")
            )
        }
    }

    @Test
    fun settingsHasNoBackAffordance() {
        // 设置全仓库只有底栏一个入口（Routes.kt:79），tab 就在下面
        val bars = paperTopBarsIn(settingsScreen(), "设置")
        assertTrue("没找到设置的 PaperTopBar", bars.isNotEmpty())
        for (bar in bars) {
            assertFalse(
                "设置（tab 之一）的顶栏又出现了返回 —— 底栏「设置」自己就是入口：$bar",
                bar.contains("onBack")
            )
        }
        // 而且形参也该删掉：留着 onBack 就会有人顺手传回去
        assertFalse(
            "SettingsScreen 又多了一个 onBack 形参；留着就一定会有人再把返回加回来。",
            Regex("fun SettingsScreen\\([^)]*onBack").containsMatchIn(stripComments(settingsScreen()))
        )
    }

    @Test
    fun chatShowsBackOnlyWhenItWasOpenedFromABook() {
        val code = stripComments(chatScreen())
        val bars = paperTopBarsIn(chatScreen(), "灵感助手")
        assertTrue("没找到灵感助手的 PaperTopBar", bars.isNotEmpty())
        for (bar in bars) {
            assertTrue(
                "灵感助手的顶栏必须按来源决定返回（从书里进才有），不能写死 onBack：$bar",
                bar.contains("isBookScoped()")
            )
            assertFalse(
                "灵感助手写死了 onBack = onBack —— 从底栏进时那是纯冗余：$bar",
                Regex("onBack\\s*=\\s*onBack\\s*,").containsMatchIn(bar)
            )
        }
        // 判据本身：从书里进（projectScope 非空）才算
        assertTrue(
            "找不到 isBookScoped 的判据（应当是 projectScope 非空）",
            Regex("fun isBookScoped\\(\\)\\s*:\\s*Boolean\\s*=\\s*!_projectScope\\.value\\.isNullOrBlank\\(\\)")
                .containsMatchIn(code)
        )
    }

    @Test
    fun screensWithoutABottomBarKeepTheirBackAffordance() {
        // 这三页没有底栏（Routes.kt 的 BOTTOM_BAR_ROOTS 不含它们），
        // 顶栏返回是唯一可见退路。哪一页把它删了，用户就出不去。
        assertFalse(
            "正文页没有底栏，顶栏返回不能删",
            paperTopBarsIn(chapterScreen(), "《").none { it.contains("onBack") } &&
                !paperTopBarsIn(chapterScreen(), "《").isEmpty()
        )
        assertFalse(
            "新建小说的顶栏返回被删了 —— 它没有底栏，返回是唯一退路",
            paperTopBarsIn(createProjectScreen(), "新建小说").none { it.contains("onBack") }
        )
        assertFalse(
            "创作设置的顶栏返回被删了 —— 它没有底栏，返回是唯一退路",
            paperTopBarsIn(creativeSetupScreen(), "创作设置").none { it.contains("onBack") }
        )
    }

    @Test
    fun secondLevelPagesOfSettingsKeepTheirBackAffordance() {
        // 账本 / 备份是设置的二级页：「上一层」不是 tab，返回仍然有用
        assertFalse(
            "用量账本的返回被删了 —— 它是设置的二级页，返回是回上一层",
            paperTopBarsIn(ledgerScreen(), "用量账本").none { it.contains("onBack") }
        )
        assertFalse(
            "备份与导出的返回被删了 —— 它是设置的二级页，返回是回上一层",
            paperTopBarsIn(exportsScreen(), "备份与导出").none { it.contains("onBack") }
        )
    }

    // ------------------------------------------------------------------ 取源码

    private fun libraryScreen() = read("presentation/library", "LibraryScreen.kt")
    private fun chatScreen() = read("presentation/chat", "ChatScreen.kt")
    private fun settingsScreen() = read("presentation/settings", "SettingsScreen.kt")
    private fun chapterScreen() = read("presentation/chapter", "ChapterScreen.kt")
    private fun ledgerScreen() = read("presentation/ledger", "LedgerScreen.kt")
    private fun exportsScreen() = read("presentation/exports", "ExportsScreen.kt")
    private fun createProjectScreen() = read("presentation/project", "CreateProjectScreen.kt")
    private fun creativeSetupScreen() = read("presentation/project", "CreativeSetupScreen.kt")

    /**
     * 取出标题匹配的 `PaperTopBar(...)` 调用（带括号配对，不是正则猜）。
     *
     * 这些调用的参数里嵌套着 lambda（`trailing = { ... }`），正则的
     * `[^)]*` 会在第一个右括号处停下，拿到半截调用。配对才拿得全。
     */
    private fun paperTopBarsIn(source: String, title: String): List<String> {
        val code = stripComments(source)
        val out = mutableListOf<String>()
        var i = 0
        while (i < code.length) {
            val at = code.indexOf("PaperTopBar(", i)
            if (at < 0) break
            var depth = 0
            var inString = false
            var j = at + "PaperTopBar".length
            while (j < code.length) {
                val c = code[j]
                when {
                    c == '\\' && inString -> j++
                    c == '"' -> inString = !inString
                    !inString && c == '(' -> depth++
                    !inString && c == ')' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                j++
            }
            val call = code.substring(at, (j + 1).coerceAtMost(code.length))
            if (Regex("title\\s*=\\s*\"[^\"]*${Regex.escape(title)}").containsMatchIn(call)) {
                out += call
            }
            i = j + 1
        }
        return out
    }

    private fun read(dir: String, name: String): String {
        val roots = listOfNotNull(
            File("").absoluteFile.takeIf { it.isDirectory },
            codeSourceDir()
        )
        for (root in roots) {
            var cursor: File? = root
            while (cursor != null) {
                val found = listOf(
                    File(cursor, "src/main/java/com/novelforge/app/$dir"),
                    File(cursor, "app/src/main/java/com/novelforge/app/$dir")
                ).firstOrNull { it.isDirectory }
                if (found != null) {
                    return (found.walkTopDown().firstOrNull { it.name == name }
                        ?: throw AssertionError("$dir 下没有 $name")).readText()
                }
                cursor = cursor.parentFile
            }
        }
        throw AssertionError("找不到 $dir 源码目录")
    }

    private fun codeSourceDir(): File? = try {
        TopLevelTabBackAffordanceTest::class.java.protectionDomain?.codeSource?.location
            ?.toURI()?.let { File(it) }?.takeIf { it.isDirectory }
    } catch (_: Exception) {
        null
    }

    /** 剥掉行注释。必须剥 —— 各处解释「为什么没有返回」的注释里会逐字出现 onBack。 */
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
