package com.novelforge.app.presentation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 界面文案不许退回口语（回归护栏）。
 *
 * 用户原话：「把一些口语化的表达都改一改太土了」。这一版把 70 来条文案
 * 统一成了平静、专业、明白的调子，但文案是最容易悄悄退化的东西：
 * 下次谁写了个 `Text("点这里试试")`，没有任何东西会拦他。
 *
 * 所以把**禁词**钉在这里。
 *
 * **扫描范围：只扫 `presentation/` 下的 .kt，且跳过 LLM 提示词常量块。**
 *
 * 这个范围是踩出来的：第一版扫 `app/src/main` 全部，于是把
 * `INSPIRATION_SYSTEM_PROMPT`（给模型看的提示词）、`GenerateOutlineUseCase`
 * 的提示词模板、`GenerationRuntime` 里的提示词片段全当成"界面文案"，
 * 三个测试一起变红 —— 全是误报。提示词里的「卡壳」「大纲」是写给模型读的，
 * 不是写给用户看的，不该受这套规则管。
 *
 * 已知未覆盖：`infrastructure/` 和 `domain/` 里直接透传给用户的少量消息
 * （如 `GenerationRuntime` 的 `errorMessage`）。那些这次是手工改的，
 * 没纳入护栏 —— 与其写一个误报率高的启发式，不如把范围讲清楚。
 */
class UiCopyToneTest {

    /**
     * 口语化 / 卖萌 / 土味词。命中即失败。
     *
     * 每条都写清为什么禁，不然下一个人只会觉得这条规则莫名其妙然后删掉它。
     */
    private val banned = listOf(
        "新建一本" to "口语量词 + 一本；按钮名用「新建小说」",
        "写第一本" to "同上，空态按钮与主页按钮用同一个词",
        "写这本书" to "口语；进入编辑器的动作用「继续写作」",
        "聊聊" to "聊天口吻；界面是提问工具，不是唠嗑",
        "破局" to "营销动词冒充名词，跟旁边两个类别词不同类",
        "魔法棒" to "手工作品式的玩梗命名",
        "卡壳" to "卖萌式空态",
        "加不进来" to "口语；用「无法添加…」",
        "没存上" to "口语；用「保存失败」",
        "没读出来" to "口语",
        "拉回" to "口语；用「载入」",
        "拉到" to "口语；用「已获取」",
        "换好了" to "口语；用「已更新」",
        "点一下" to "口语",
        "挑一个" to "口语",
        "模型觉得" to "卖萌；用「模型判定：」",
        "文青" to "在「网文风」旁边像在说一个题材贬义词；用「文艺」"
    )

    @Test
    fun noBannedColloquialismsInUserVisibleStrings() {
        val hits = mutableListOf<String>()
        for ((phrase, why) in banned) {
            for ((file, line, text) in userVisibleStrings()) {
                if (text.contains(phrase)) {
                    hits += "$file:$line  「$phrase」($why)  →  $text"
                }
            }
        }
        assertTrue(
            "界面文案里出现了口语表达：\n" + hits.joinToString("\n"),
            hits.isEmpty()
        )
    }

    /**
     * 术语不许再分叉。
     *
     * 这一版统一了：小说（不是作品/项目/书）、思考（不是推理）、
     * 章（不是段/推进段）。同一个概念两个名字，用户会以为是两件事。
     */
    @Test
    fun terminologyIsNotForkedAgain() {
        val pairs = listOf(
            Triple("推理", "思考", "灵感助手的开关叫「思考」，正文里却写「看不到推理过程」"),
            Triple("推进段", "章节", "「推进段」是提示词内部说法，界面上叫「章」"),
            Triple("搭建大纲", "生成大纲", "同一件事 elsewhere 全写「生成大纲」"),
            Triple("作品列表", "书架", "项目列表那一页已经并进书架了")
        )
        val hits = mutableListOf<String>()
        for ((wrong, right, why) in pairs) {
            for ((file, line, text) in userVisibleStrings()) {
                if (text.contains(wrong)) {
                    hits += "$file:$line  「$wrong」应为「$right」($why)"
                }
            }
        }
        assertTrue("术语又分叉了：\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    /**
     * 引用界面上的按钮名一律用「」，不许用弯引号。
     *
     * 弯引号只在 GenerationRuntime 里出现过两处，而全 App 其他地方都是「」。
     */
    @Test
    fun quotedButtonNamesUseCornerBrackets() {
        val hits = mutableListOf<String>()
        for ((file, line, text) in userVisibleStrings()) {
            for (q in listOf("“", "”")) {
                if (text.contains(q)) {
                    hits += "$file:$line  用了弯引号 $q → $text"
                }
            }
        }
        assertTrue("弯引号应统一成「」：\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    // ---------------------------------------------------------------- 取字符串

    /**
     * 扫 `presentation/` 下所有 .kt，返回 (相对路径, 行号, 字符串字面量内容)。
     *
     * 两层过滤，缺一不可：
     * 1. **只扫 `presentation/`** —— 提示词在 `infrastructure/llm` 和
     *    `domain/usecase`，那里面的中文是写给模型看的。
     * 2. **跳过提示词常量块** —— `INSPIRATION_SYSTEM_PROMPT` 就住在
     *    `presentation/chat/ChatScreen.kt` 里，光靠目录过滤拦不住它。
     *    判据：从含 `PROMPT` 的赋值行开始，跳到第一行不以 `+` 结尾为止。
     *
     * 另外只保留**看起来像字符串字面量**的行：含一个双引号，且双引号成对。
     * 注释和 KDoc 天然被排除 —— 它们在 `//` 之后，剥掉行注释后就没有引号了。
     */
    private fun userVisibleStrings(): List<Triple<String, Int, String>> {
        val result = mutableListOf<Triple<String, Int, String>>()
        for (file in sourceFiles()) {
            val lines = try {
                file.readLines()
            } catch (_: Exception) {
                continue
            }
            var inPrompt = false
            lines.forEachIndexed { index, raw ->
                val code = stripComment(raw)
                // 提示词常量块：声明行进入，后续以 `+` 续行的行都属于它。
                // 声明行本身必须**跳过而不是判定结束** —— 它以 `=` 结尾，
                // 一进循环就被 "不以 + 结尾" 那条判掉，标记立刻失效，
                // 于是整块提示词照样被扫进去（第一版就是这么错的）。
                if (!inPrompt && code.contains("PROMPT") && code.contains("=")) {
                    inPrompt = true
                    return@forEachIndexed
                }
                if (inPrompt) {
                    if (!code.trimEnd().endsWith("+")) inPrompt = false
                    return@forEachIndexed
                }
                if (code.count { it == '"' } < 2) return@forEachIndexed
                for (literal in quotedLiterals(code)) {
                    if (literal.isNotBlank()) {
                        result += Triple(
                            file.path.replace(File.separatorChar, '/').substringAfterLast("/app/"),
                            index + 1,
                            literal
                        )
                    }
                }
            }
        }
        return result
    }

    private fun sourceFiles(): List<File> {
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
        UiCopyToneTest::class.java
            .protectionDomain?.codeSource?.location
            ?.toURI()
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }
    } catch (_: Exception) {
        null
    }

    /**
     * 剥掉行注释。注释里写禁词是给开发者看的，不该让本测试变红，
     * 所以必须在取字符串之前剥掉。
     *
     * 判据是"// 之前没有引号"：否则 `"https://x"` 里的 `//` 会被当注释开头。
     */
    private fun stripComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && (i == 0 || line[i - 1] != '\\') -> inString = !inString
                !inString && c == '/' && i + 1 < line.length && line[i + 1] == '/' -> {
                    return line.substring(0, i)
                }
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
                    current.append(c).append(code[i + 1])
                    i++
                }
                c == '"' -> {
                    if (inString) {
                        out += current.toString()
                        current.setLength(0)
                    }
                    inString = !inString
                }
                inString -> current.append(c)
            }
            i++
        }
        return out
    }
}
