package com.novelforge.app.presentation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 列表项里不许重复出现操作提示（回归护栏）。
 *
 * 用户原话，截图是书架里一本书的章节目录：
 *
 * ```
 * 第 1 章 · 武者之名
 * 点击阅读 · 长按重命名/删除
 * ```
 *
 * 「这个每一个里面还是有小字啊，不需要这个小字提示啊」
 *
 * **判据是「这句在讲这个条目，还是在讲界面本身」。**
 *
 * 逐项重复的操作说明是纯噪声：五十张章节卡就是同一句话五十遍，一句也
 * 不多告诉你（说一遍就够了），却每张卡吃掉一整行高度——一屏能看的章数直接
 * 少一半。而且列表看上去像教程，不像内容。
 *
 * 合法的形态有三种：
 * - **说一次**，放在列表上方（`SettingsScreen.kt` 的已保存配置就是对的）
 * - **不说**（长按是 Android 通用手势，不需要逐个卡片教）
 * - 保留**每项不同**的信息（`未生成正文`、`已写正文`、`模型 · Base URL`）
 *
 * ## 两条规则，分开判
 *
 * 1. `repeatedItemsMustNotTeachGestures` —— 结构性。字面量（没有 `$` 插值）
 *    且含手势动词，出现在 `items` / `forEach` 的**大括号范围之内**。
 *    范围判断是关键：`ChapterScreen.kt` 的「点击名称即可在本章中排除」、
 *    `LibraryScreen.kt` 顶栏的「点击封面写作，长按可阅读」都在列表**外面**，
 *    说一次是对的，不该红。
 *
 * 2. `removedPerItemHintsStayRemoved` —— 点名清单。规则 1 的动词表是有限的，
 *    抓不到「别名在概要中出现时同样视为点名」这种不含手势词、但同样逐字
 *    重复的说明（那个在 `StoryBibleScreen` 的 `supportingText` 上，
 *    十张角色卡就是同一段话十遍）。所以把真删掉的三条钉死。
 *
 * ## 已知不覆盖
 *
 * - 靠函数返回的文案（`project.status.label()`）——静态看不出它是不是提示。
 * - 不含手势动词、也不在点名词单里的逐项重复说明。要覆盖这类只能上真正的
 *   Compose UI 测试，那需要设备。与其写一个误报率高的启发式，不如把范围讲清楚。
 * - 长按删除章节没有任何界面提示（唯一入口是长按）。这是**故意的**：
 *   用户明确说过不要那个小字，而且删除是两步（操作表 → 确认弹窗），
 *   确认弹窗会念出章节名和后果。代价是发现性靠长按本身，这一点是知情的取舍。
 */
class PerItemHintTest {

    /** 手势动词。命中即认为这句在「教用户怎么操作界面」而不是「讲这个条目」。 */
    private val gestureVerbs = listOf(
        "点击", "长按", "点按", "双击", "轻触", "滑动", "上滑", "下滑", "下拉", "上拉"
    )

    /**
     * 已经被删掉、且本测试不允许再出现的逐项提示。
     * 每条写清为什么禁，不然下一个人只会觉得这条规则莫名其妙然后删掉它。
     */
    private val removedPerItemHints = listOf(
        Triple(
            "点击阅读",
            "LibraryScreen",
            "逐张章节卡重复同一句手势说明；一屏少看一半章"
        ),
        Triple(
            "点击展开/收起",
            "ChatScreen",
            "逐个气泡重复，而且收着和展开着念的是同一句 —— 根本不区分状态"
        ),
        Triple(
            "别名在概要中出现时同样视为点名",
            "StoryBibleScreen",
            "挂在 forEach 里每个角色卡的 supportingText 上，十张卡重复十遍"
        )
    )

    @Test
    fun repeatedItemsMustNotTeachGestures() {
        val hits = mutableListOf<String>()
        for ((file, line, literal) in literalsInsideRepeatedItems()) {
            if (literal.contains('$')) continue          // 插值 = 每项可能不同
            val verb = gestureVerbs.firstOrNull { literal.contains(it) }
            if (verb != null) {
                hits += "$file:$line  列表项里的字面量含手势词「$verb」（每项都重复一遍）：$literal"
            }
        }
        assertTrue(
            "列表项里逐字重复的操作提示：\n" + hits.joinToString("\n") +
                "\n\n说一次（放列表上方）或者干脆不说；长按是 Android 通用手势。",
            hits.isEmpty()
        )
    }

    @Test
    fun removedPerItemHintsStayRemoved() {
        val hits = mutableListOf<String>()
        for ((phrase, where, why) in removedPerItemHints) {
            for ((file, line, literal) in literalsInsideRepeatedItems()) {
                if (literal.contains(phrase)) {
                    hits += "$file:$line  「$phrase」又出现在列表项里了（$where，$why）"
                }
            }
        }
        assertTrue("逐项提示又长回来了：\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    // ------------------------------------------------------------------ 取字符串

    /**
     * **只取重复项内部**的字符串字面量。
     *
     * 两条规则都靠这个范围，而不是全仓扫 —— 这是被自己的测试逼出来的：
     * 点名词单第一条 `别名在概要中出现时同样视为点名` 全仓扫时会命中
     * `StoryBibleScreen.kt:400`，而那正是**特意保留**的那一份：它从每个角色卡
     * 的 `supportingText` 提到了角色列表上方，说一次。信息没丢，只是搬家了。
     * 全仓禁等于禁止提到这件事，可这恰恰是推荐的形状。
     *
     * 所以判据是「在不在 `forEach` 的大括号范围之内」，不是「代码里有没有这句话」。
     */
    private fun literalsInsideRepeatedItems(): List<Triple<String, Int, String>> {
        val out = mutableListOf<Triple<String, Int, String>>()
        for (file in presentationSources()) {
            val rel = file.path.replace(File.separatorChar, '/').substringAfterLast("/app/")
            val lines = readLinesOrEmpty(file)
            forEachRepeatedItemSpan(lines) { spanStart, spanEnd ->
                for (i in spanStart..spanEnd) {
                    val code = stripComment(lines[i])
                    if (code.count { it == '"' } < 2) continue
                    for (literal in quotedLiterals(code)) {
                        if (literal.isNotBlank()) out += Triple(rel, i + 1, literal)
                    }
                }
            }
        }
        return out
    }

    private fun readLinesOrEmpty(file: File): List<String> = try {
        file.readLines()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * 找出每个「重复项」的大括号范围，回调 (起始行, 结束行)。
     *
     * 判据：出现 `items(` / `itemsIndexed(` / `.forEach` / `.forEachIndexed`
     * 的行，从该行起按花括号配对到深度归零。
     *
     * 花括号必须在**字符串之外**计数，否则正文里一个 `{`（模型输出、
     * 代码块、LaTeX 都会出现）就会让深度对不上，范围一路吃到文件尾 ——
     * 那样整份文件都算「列表内部」，满屏误报。
     */
    private fun forEachRepeatedItemSpan(lines: List<String>, action: (Int, Int) -> Unit) {
        lines.forEachIndexed { index, raw ->
            val code = stripComment(raw)
            if (!REPEAT_MARKERS.any { code.contains(it) }) return@forEachIndexed
            var depth = 0
            var started = false
            var i = index
            while (i < lines.size && i - index < maxSpanLines) {
                val line = stripComment(lines[i])
                var inString = false
                var j = 0
                while (j < line.length) {
                    val c = line[j]
                    when {
                        c == '\\' && inString -> j++
                        c == '"' -> inString = !inString
                        !inString && c == '{' -> { depth++; started = true }
                        !inString && c == '}' -> depth--
                    }
                    j++
                }
                if (started && depth <= 0) {
                    action(index, i)
                    return@forEachIndexed
                }
                i++
            }
        }
    }

    private val REPEAT_MARKERS = listOf(
        "items(", "itemsIndexed(", ".forEach", ".forEachIndexed", ".map {"
    )

    /** 防御上限：真出问题时别把整个文件都算进去，宁可漏报不要满屏误报。 */
    private val maxSpanLines = 400

    private fun presentationSources(): List<File> {
        val roots = listOfNotNull(
            File("").absoluteFile.takeIf { it.isDirectory },
            codeSourceDirectory()
        )
        for (root in roots) {
            var dir: File? = root
            while (dir != null) {
                val found = listOf(
                    File(dir, "src/main/java/com/novelforge/app/presentation"),
                    File(dir, "app/src/main/java/com/novelforge/app/presentation")
                ).firstOrNull { it.isDirectory }
                if (found != null) {
                    return found.walkTopDown().filter { it.name.endsWith(".kt") }.toList()
                }
                dir = dir.parentFile
            }
        }
        throw AssertionError("找不到 presentation 源码目录（源码不在预期位置）")
    }

    private fun codeSourceDirectory(): File? = try {
        PerItemHintTest::class.java
            .protectionDomain?.codeSource?.location
            ?.toURI()
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }
    } catch (_: Exception) {
        null
    }

    /** 剥掉行注释。判据是「`//` 之前不在字符串里」，否则 `"https://x"` 会误伤。 */
    private fun stripComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && (i == 0 || line[i - 1] != '\\') -> inString = !inString
                !inString && c == '/' && i + 1 < line.length && line[i + 1] == '/' ->
                    return line.substring(0, i)
            }
            i++
        }
        return line
    }

    /** 取出这一行里所有双引号字符串字面量的内容。 */
    private fun quotedLiterals(code: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var inString = false
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '\\' && inString && i + 1 < code.length -> {
                    current.append(c).append(code[i + 1]); i++
                }
                c == '"' -> {
                    if (inString) { out += current.toString(); current.setLength(0) }
                    inString = !inString
                }
                inString -> current.append(c)
            }
            i++
        }
        return out
    }
}
