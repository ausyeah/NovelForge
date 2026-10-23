package com.novelforge.app.presentation.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 复制不许靠「每条消息下面挂一行按钮」实现（回归护栏）。
 *
 * 用户原话，附截图（每条气泡下面都跟着一行灰色「复制」）：
 *
 * > 是长按复制，不是这样在气泡里影响我看
 *
 * ## 这件事绕了一圈，值得记下来
 *
 * 1. **最早**：给气泡挂 `pointerInput { detectTapGestures(onLongPress = …) }` 做长按复制。
 *    那个 API 会 `down.consume()`，把外层 `SelectionContainer` 的长按选词整个吃掉
 *    —— 系统工具栏全废，而且只在默认配置下或 assistant 气泡才碰巧没事。
 * 2. **退一步**：改成正文下面一行「复制」按钮（`CopyRow`）。和选词共存了，
 *    但每条回复下面都多一行，聊天记录被切得七零八落，点它就在动正文。
 * 3. **现在**：**一个多余手势都不加**。整条复制走系统 —— 长按正文 → 系统选择
 *    工具栏（复制 / 全选 / 分享）。长按本来就要留给选词，两者不冲突。
 *
 * 代价是「一键复制整条」没了，得长按 → 全选 → 复制。换来的是屏幕上没有任何
 * 不属于对话内容的元素。
 *
 * ## 为什么 `PerItemHintTest` 抓不到
 *
 * 它查的是「重复项**里面**的字面量含手势词」。`CopyRow` 渲染的是 `Text("复制")`，
 * **不含任何手势词** —— 它是一个按钮，不是一句提示。所以那条规则完全不适用。
 * 这正是「以为有护栏、其实没有」的那种情况，所以单独立一条。
 *
 * **已知不覆盖**：真正长按会复制到什么，JVM 测不了（要 Android 运行时）。
 * 这里钉的是「那行按钮不存在」。
 */
class CopyAffordanceTest {

    private val code: String get() = stripComments(chatSource())

    @Test
    fun noPerMessageCopyButtonRow() {
        // 那行按钮的渲染函数
        assertFalse(
            "又出现了每条消息下面的复制按钮行（CopyRow）—— 用户明确说过" +
                "「不是这样在气泡里影响我看」。整条复制走系统：长按 → 全选 → 复制。",
            Regex("fun\\s+CopyRow\\s*\\(").containsMatchIn(code)
        )
        assertFalse(
            "又出现了那个 48dp 的复制按钮（CopyButton）本体",
            Regex("fun\\s+CopyButton\\s*\\(").containsMatchIn(code)
        )
        // 而且不许有任何地方再调它
        assertFalse(
            "还有地方在调用那个复制按钮行",
            Regex("(?<![\\w.])CopyRow\\s*\\(").containsMatchIn(code)
        )
    }

    @Test
    fun theUserBubbleDoesNotCopyEither() {
        // 用户自己的提问也挂过同一个按钮行（"自己的提问也常要复制走"）
        assertFalse(
            "用户气泡下面还留着复制按钮 —— 它也是每条一行，同样干扰阅读",
            code.contains("自己的提问也常要复制走")
        )
    }

    @Test
    fun theClipboardHelperIsGoneToo() {
        // copyToClipboard 原本只被 CopyRow 用。留着的话下一个人会直接复用它
        // 再把按钮挂回去。
        assertFalse(
            "copyToClipboard 还留着但没有调用方了 —— 它是那行按钮的实现，" +
                "留着等于给下一个人留了个零件",
            Regex("fun\\s+copyToClipboard\\s*\\(").containsMatchIn(code)
        )
    }

    @Test
    fun longPressSelectionIsNotHijacked() {
        // 消息气泡上绝对不许再装任何会 consume 按键事件的手势。
        // detectTapGestures / combinedClickable / clickable 全都会 down.consume()，
        // 把 SelectionContainer 的长按选词废掉。
        //
        // 这条继承自「最早的实现」—— 那个 bug 表现为「assistant 气泡长按没反应」，
        // 而且只在默认配置下复现，极难查。
        //
        // **取消息列表那一段**：从 `items(reversed, …)` 到**下一个** `items(`。
        //
        // 走过的弯路，每一个都让护栏**静默失效**（注入 sabotage 后测试照样绿）：
        //  1. 按「第一个 items(」切 —— 文件后面还有历史面板、附件条等好几个
        //     items 块，里面有正当的 clickable，切错了断言方向整个反了。
        //  2. 按「从 items 起 12000 字符」切 —— 消息区比这长，注入到气泡里的
        //     clickable 落在窗口**之外**。窗口大小是猜的，猜错就等于没有护栏。
        //  3. 只切 `items` 的尾随 lambda —— **用户气泡在它的兄弟分支里**，
        //     往用户气泡上加 clickable（一样会毁掉长按选词）测试看不见。
        //  4. 切到整个 LazyColumn —— 那就把「↓ 回到最新」那个正当的
        //     clickable 也圈进来了，测试永远红。两个极端都不行。
        //
        // 正确形状：**这一段消息列表**。下一个 `items(` 是它的右边界
        // （历史面板），那里面的 clickable 是正当的。
        val start = code.indexOf("items(reversed, key = { it.id })")
        assertTrue("找不到消息列表的 items 块，源码结构变了？", start >= 0)
        val nextItems = code.indexOf("items(", start + 10)
        val end = if (nextItems > 0) nextItems else code.length
        val bubbleBlock = code.substring(start, end)

        // 判据是 `clickable(` 而不是 `.clickable(`：真实代码里几乎都写成
        // `Modifier.clickable(…`，那个点属于 `Modifier` 不属于调用。
        // 写成 `.clickable(` 的话，`Modifier.clickable { }` 都不会命中 ——
        // 而那正是要防的。破坏验证时才发现：注入进去，测试照样绿。
        for (forbidden in listOf("detectTapGestures", "combinedClickable", "clickable(")) {
            assertFalse(
                "气泡里又出现了 `$forbidden` —— 它会 down.consume()，把长按选词整个吃掉。" +
                    "整条复制请走系统选择工具栏。",
                bubbleBlock.contains(forbidden)
            )
        }
        assertTrue(
            "气泡里应该还留着 SelectionContainer（长按选词靠它）—— 它被误删了？",
            bubbleBlock.contains("SelectionContainer")
        )
    }

    /**
     * 「复制」这个词只许出现在注释里，不许是渲染出来的文字。
     *
     * 截图里那行灰字就是 `Text("复制")`。这条比「函数不存在」更宽 ——
     * 换个名字重新实现一遍（比如叫 `CopyAction`）也躲不掉。
     */
    @Test
    fun noCopyTextIsRendered() {
        val rendered = Regex("\"([^\"\\n]*复制[^\"\\n]*)\"")
            .findAll(code)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .toList()
        assertTrue(
            "代码里又出现了会被渲染出来的含「复制」字样：$rendered\n" +
                "（注释已剥掉，所以这些都是字符串字面量。整条复制走系统选择工具栏，" +
                "界面上不需要任何「复制」文字。）",
            rendered.isEmpty()
        )
    }

    // ------------------------------------------------------------------ 取源码

    private fun chatSource(): String {
        val roots = listOfNotNull(
            File("").absoluteFile.takeIf { it.isDirectory },
            codeSourceDir()
        )
        for (root in roots) {
            var cursor: File? = root
            while (cursor != null) {
                val found = listOf(
                    File(cursor, "src/main/java/com/novelforge/app/presentation/chat"),
                    File(cursor, "app/src/main/java/com/novelforge/app/presentation/chat")
                ).firstOrNull { it.isDirectory }
                if (found != null) {
                    return (found.walkTopDown().firstOrNull { it.name == "ChatScreen.kt" }
                        ?: throw AssertionError("presentation/chat 下没有 ChatScreen.kt"))
                        .readText()
                }
                cursor = cursor.parentFile
            }
        }
        throw AssertionError("找不到 presentation/chat 源码目录")
    }

    private fun codeSourceDir(): File? = try {
        CopyAffordanceTest::class.java.protectionDomain?.codeSource?.location
            ?.toURI()?.let { File(it) }?.takeIf { it.isDirectory }
    } catch (_: Exception) {
        null
    }

    /**
     * 取出 `… { x -> BODY }` 这个尾随 lambda 的 **BODY**。
     *
     * 关键：`marker` 之后还有**一个** `{` 才是块的开头。`items(… ) { message ->`
     * 里，第一个 `{` 是 `key = { it.id }` 那个 lambda 的参数括号，配对会在那里
     * 就结束 —— 切出来 30 个字符就完事，护栏变成空转。
     */
    private fun lambdaBodyOf(source: String, marker: String): String {
        val start = source.indexOf(marker)
        if (start < 0) throw AssertionError("源码里找不到 `$marker`")
        val from = source.indexOf('{', start + marker.length)
        if (from < 0) throw AssertionError("`$marker` 后面没有开块的 `{`")
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
        throw AssertionError("`$marker` 的 lambda 体花括号没配平")
    }

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

    @Suppress("unused")
    private fun keepBlockHelperReferenced() = Unit

    /** 剥掉注释 —— 各处解释「为什么删掉复制按钮」的注释里会逐字出现「复制」。 */
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
