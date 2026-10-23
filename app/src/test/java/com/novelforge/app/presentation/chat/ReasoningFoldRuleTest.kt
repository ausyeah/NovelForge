package com.novelforge.app.presentation.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 思考折叠只由「结束没有」决定，长度不参与（回归护栏）。
 *
 * 用户原话：「不是思考超出一定长度就折叠，是思考结束就折叠思考」
 *
 * 原来的实现有两条规则，两条都不对：
 *
 * 1. `var open by remember(message.id) { mutableStateOf(message.streaming) }`
 *    —— **首次组合求值一次就不动了。** 流式结束把 `streaming` 翻成 false 之后
 *    那个 `remember` 不会重跑，所以思考一旦展开就永远不会再自动折起来。
 *    key 的 LazyColumn 里条目是持续存在的，不会因为状态变就重新组合。
 *    也就是说这一条**方向正好相反**：从不折叠。
 * 2. 超过 300 字截成 8 行 + 一个「展开全部」。按长度折叠比第一条还别扭 ——
 *    正在生成的时候你最想看它在想什么，它偏偏在这时候开始截断，
 *    而且每来一批新 token 就重新截一次。
 *
 * 这类东西特别容易退回去：把 `LaunchedEffect` 当成冗余删掉，编译照过、
 * 界面照动，只是又变回永不折叠。所以钉在这里。
 *
 * **已知不覆盖**：折叠状态本身是 Compose 状态机，JVM 测不了；这里只能静态
 * 检查「规则由什么驱动」。真机验证点是"生成结束后思考自己折起来"。
 */
class ReasoningFoldRuleTest {

    @Test
    fun theFoldIsDrivenByAStreamingEffect_notOnlyByTheRememberInitializer() {
        val src = chatSource()
        val text = stripComments(src)

        assertTrue(
            "找不到 `LaunchedEffect(message.streaming)` —— 折叠靠的是这个 effect 监听\n" +
                "streaming 的翻转。只有 `remember { mutableStateOf(message.streaming) }`\n" +
                "的话，那行只在首次组合求值一次，思考展开后就永远不会再自动折起来。",
            text.contains("LaunchedEffect(message.streaming)")
        )
        // effect 必须真的把 streaming 写回去，而不是只启动不做事
        assertTrue(
            "`LaunchedEffect(message.streaming)` 里面没有把 streaming 写回 reasoningOpen，\n" +
                "这个 effect 是空转的，折叠不会发生。",
            Regex("LaunchedEffect\\(message\\.streaming\\)\\s*\\{\\s*reasoningOpen = message\\.streaming")
                .containsMatchIn(text)
        )
    }

    @Test
    fun noLengthBasedPreviewFoldRemains() {
        val text = stripComments(chatSource())

        // 常量和按长度截断的两个支点，一起钉
        for (forbidden in listOf("REASONING_PREVIEW_LINES", "REASONING_PREVIEW_CHARS")) {
            assertTrue(
                "`$forbidden` 又回来了 —— 思考折叠只看结束没有，不看长度。",
                !text.contains(forbidden)
            )
        }
        assertTrue(
            "reasoning 的 Text 上又挂回 maxLines 截断了。展开就该整段铺开；\n" +
                "「太长」这件事由结束自动折叠兜底，不该在展开状态下截断。",
            !Regex("maxLines\\s*=\\s*if\\s*\\(\\s*reasoningLong").containsMatchIn(text)
        )
        // 嵌套的第二层折叠（展开全部 / 收起全部）
        for (forbidden in listOf("reasoningFull", "reasoningLong")) {
            assertTrue(
                "`$forbidden` 又回来了 —— 折叠只有一层，「展开全部/收起全部」那一档已经删掉。",
                !text.contains(forbidden)
            )
        }
    }

    @Test
    fun theThreeFoldLabelsAreDistinctAndNoneIsAnInstruction() {
        val text = stripComments(chatSource())
        val labels = Regex("\"([^\"\\n]*(?:思考中|收起思考|展开思考)[^\"\\n]*)\"")
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()

        assertEquals(
            "折叠标签应该正好三个（思考中 / 收起思考 / 展开思考），实际找到：$labels",
            3,
            labels.size
        )
        assertEquals(
            "三个标签必须互不相同 —— 原来收着和展开着都念「展开/收起」，根本不区分状态：$labels",
            labels.size,
            labels.toSet().size
        )
        // 逐个气泡重复的操作说明（用户明确要求去掉的那类小字）
        for (label in labels) {
            assertTrue(
                "折叠标签不该带操作说明「$label」—— 标签自己就在那儿、又是可点的，说一遍就够。",
                !label.contains("点击") && !label.contains("点按")
            )
        }
    }

    // ------------------------------------------------------------------ 取源码

    private fun chatSource(): String {
        val roots = listOfNotNull(
            File("").absoluteFile.takeIf { it.isDirectory },
            codeSourceDirectory()
        )
        for (root in roots) {
            var dir: File? = root
            while (dir != null) {
                val found = listOf(
                    File(dir, "src/main/java/com/novelforge/app/presentation/chat"),
                    File(dir, "app/src/main/java/com/novelforge/app/presentation/chat")
                ).firstOrNull { it.isDirectory }
                if (found != null) {
                    val file = (found.walkTopDown().firstOrNull { it.name == "ChatScreen.kt" }
                        ?: throw AssertionError("presentation/chat 下没有 ChatScreen.kt"))
                    return file.readText()
                }
                dir = dir.parentFile
            }
        }
        throw AssertionError("找不到 presentation/chat 源码目录（源码不在预期位置）")
    }

    private fun codeSourceDirectory(): File? = try {
        ReasoningFoldRuleTest::class.java
            .protectionDomain?.codeSource?.location
            ?.toURI()
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }
    } catch (_: Exception) {
        null
    }

    /**
     * 剥掉注释。
     *
     * 必须剥：这段折叠逻辑上挂着很长一段注释，里面逐字提到了
     * `REASONING_PREVIEW_LINES`、`reasoningFull`、`maxLines` 这些被删掉的写法 ——
     * 不剥的话每条规则都会在自己那段解释文字上误报。
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
