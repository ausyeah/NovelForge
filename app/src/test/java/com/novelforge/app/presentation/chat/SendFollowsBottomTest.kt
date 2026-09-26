package com.novelforge.app.presentation.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「我发了话」必须立刻到底部并跟随（回归护栏）。
 *
 * 用户原话：「灵感助手发送消息出去也不会第一时间触底跟随滑动」
 *
 * ## 原来为什么不跟随
 *
 * 两个原因叠在一起，都不是「滚动命令没发」，是**发晚了**：
 *
 * 1. **触发时机错**。那个 `scrollToItem(0)` 挂在 `messages.size` 上。但发送那一刻
 *    `size` **还没变** —— ViewModel 是先 append USER + 空 ASSISTANT 才返回，
 *    而那次 append 和 `scrollToItem` 之间隔着一次重组。命令打在一个
 *    「列表还不接受新项」的状态上，被丢掉了。
 * 2. **冻结没解**。冻结由界面的 `atBottom` 驱动（`setFrozen(!atBottom || …)`）。
 *    「翻上去看历史 → 打一句话发出去」这个最常见的组合里，发送时 `atBottom`
 *    仍是 false（上一次翻上去留下的），要等下一次重组才变 true。冻着的时候
 *    流式增量只进缓冲、不落进列表 —— 表现就是**完全不跟随**。
 *
 * 而「我发了话，当然要从我这句话看起」是唯一没有例外的情形。所以现在由发送
 * 按钮直接调 `onUserSent()`（解冻）并自增 `sendSeq`（下一帧滚到底），两步都在
 * 发送那一刻发生，不再依赖 `size` 的时序。
 *
 * **已知不覆盖**：真正「滚到底部」这件事要真机 + Compose 运行时，JVM 测不了。
 * 这里钉的是「发送那一刻确实调了那两样东西，而且解冻在滚动之前」。
 */
class SendFollowsBottomTest {

    private val code: String get() = stripComments(chatSource())

    @Test
    fun theSendButtonUnfreezesAndRequestsScroll() {
        // 从 `{`（clickable 的尾随 lambda 起点）切到配平的 `}`。
        // 不能用「marker 后第一个 `(`」—— 那是 `clickable(` 自己，
        // 配平在参数列表结束就停了，切出来的是 `(enabled = …)` 而已。
        val lambdaStart = code.indexOf(".clickable(enabled = if (busy) true else canSend) {")
        assertTrue("找不到发送按钮的 clickable，源码结构变了？", lambdaStart >= 0)
        val click = blockStartingAt(code, ".clickable(enabled = if (busy) true else canSend) {")
        assertTrue(
            "发送按钮里没有调 onUserSent() —— 冻结没解，回复就不会跟随。\n\n" +
                "切出来的是：\n$click",
            click.contains("onUserSent()")
        )
        assertTrue(
            "发送按钮里没有推进 sendSeq —— 不会滚到底部。\n\n切出来的是：\n$click",
            click.contains("sendSeq++")
        )
        // 顺序：先解冻，再让界面滚。反过来的话 scrollToItem 会打在被冻结的列表上。
        val unfreeze = click.indexOf("onUserSent()")
        val scroll = click.indexOf("sendSeq++")
        assertTrue(
            "解冻必须在推进 sendSeq 之前（反了的话滚动会打进被冻结的列表）：" +
                "onUserSent at +$unfreeze, sendSeq++ at +$scroll",
            unfreeze in 0 until scroll
        )
    }

    @Test
    fun scrollingIsDrivenBySendSeqNotByMessageCount() {
        // 挂在 messages.size 上的那个 effect 正是「发出去不跟随」的成因：
        // 发送那一刻 size 还没变，而 append 与 scrollToItem 之间隔着一次重组。
        assertTrue(
            "没有 sendSeq 这个「刚发了话」的信号 —— 滚动仍然只能靠 messages.size，" +
                "而那在发送那一刻还没变。",
            code.contains("var sendSeq")
        )
        assertTrue(
            "滚动 effect 没有挂在 sendSeq 上",
            Regex("LaunchedEffect\\(sendSeq\\)[\\s\\S]{0,200}?scrollToItem\\(0\\)").containsMatchIn(code)
        )
        assertTrue(
            "onUserSent() 必须在 ViewModel 里真正解冻（frozen = false），" +
                "不能只是个空壳 —— 冻着的时候流式增量只进缓冲不落列表。",
            Regex("fun onUserSent\\(\\)\\s*\\{\\s*frozen = false").containsMatchIn(code)
        )
    }

    @Test
    fun switchingConversationsStillScrollsToBottom() {
        // 换会话 / 恢复历史也要到底部 —— 原来那个 effect 顺带管这件事，
        // 拆成 sendSeq 之后不能把它弄丢。
        assertTrue(
            "切换会话后不再跳到底部了",
            Regex("LaunchedEffect\\(activeId\\)[\\s\\S]{0,200}?scrollToItem\\(0\\)").containsMatchIn(code)
        )
    }

    @Test
    fun theScrollToLatestFabIsUnaffected() {
        // 「↓ 回到最新」那个悬浮按钮仍然只是滚。
        //
        // 锚点用 `if (!atBottom)` 而不是那句中文标签 —— 中文标签在源码里是
        // 独立的一行 `Text("↓ 回到最新")`，而它前面还隔着 Surface/Surface 修饰符，
        // 拿标签当起点会切到错的地方（第一版就这么写的，测试红而代码是对的）。
        val anchor = code.indexOf("if (!atBottom) {")
        assertTrue("找不到「回到最新」那个 if (!atBottom) 块，源码结构变了？", anchor >= 0)
        val fab = braceBlockAt(code, anchor)
        assertTrue(
            "「回到最新」按钮不见了",
            fab.contains("scrollToItem(0)")
        )
        // 它不该顺手推进 sendSeq —— 那是发送按钮的信号
        assertTrue(
            "「回到最新」里不该出现 sendSeq++ —— 那是发送按钮专用的信号",
            !fab.contains("sendSeq")
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
        SendFollowsBottomTest::class.java.protectionDomain?.codeSource?.location
            ?.toURI()?.let { File(it) }?.takeIf { it.isDirectory }
    } catch (_: Exception) {
        null
    }

    /** 从 `marker` 之后**第一个 `{`** 起按花括号配对切块。 */
    private fun blockStartingAt(source: String, marker: String): String {
        val start = source.indexOf(marker)
        if (start < 0) throw AssertionError("源码里找不到 `$marker`")
        val from = source.indexOf('{', start + marker.length - 1)
        if (from < 0) throw AssertionError("`$marker` 后面没有开块的 `{`")
        return braceBlockAt(source, from)
    }

    /** 从 `from`（一个 `{`）起按花括号配对取出整块。 */
    private fun braceBlockAt(source: String, from: Int): String {
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
                    if (depth == 0) return source.substring(from, i + 1)
                }
            }
            i++
        }
        throw AssertionError("从 +$from 起花括号没配平")
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
}
