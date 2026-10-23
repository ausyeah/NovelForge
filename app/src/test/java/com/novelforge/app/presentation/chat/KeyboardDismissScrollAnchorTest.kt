package com.novelforge.app.presentation.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 键盘收起时「先触底（底栏冒出来）再弹回」（回归护栏）。
 *
 * 用户原话：「收起键盘后对话框提前触底（导航栏显示）之后才回到正常位置」
 *
 * ## 旧做法为什么**注定**是「先错后对」
 *
 * 旧代码挂在 `LaunchedEffect(imeVisible)` 上，而 `imeVisible` 是
 * `WindowInsets.ime.getBottom(density) > 0` 这个**布尔值**。
 *
 * 键盘收起的动画约 250ms，这整段时间里 inset 都还大于 0，所以 `imeVisible`
 * **一直是 true**，只在 inset 归零那一刻才翻转。
 * 也就是说：修正发生在**漂移全部画完之后**。用户先看到内容冲到最底下、
 * 底栏冒出来，然后才被拉回去 —— 跟报的顺序一模一样。
 *
 * 旧代码还叠了一句「等两帧」（`withFrameNanos` 两回）。两帧约 33ms，
 * 而键盘动画 250ms —— **数量级差了将近十倍，等于没等**。
 *
 * ## 现在的判据
 *
 * 视口每变一次就按锚点钉回去，**一次一帧**，不等动画结束。
 * 关键在结构：「记锚点」和「用锚点」必须在**同一个 `collect`** 里。
 * 分成两条 flow 就有竞态 —— 漂移值可能在复位之前就把锚点覆盖掉，
 * 而视口之后不再变化时那条 flow 也不会再触发，错误就永久留下了。
 *
 * **已知不覆盖**：真机上还剩几帧的抖动、以及「从视口变到钉回去」之间那一帧，
 * JVM 测不了（要真机 + 真键盘动画）。这里钉住的是**结构**：
 * 不会再退回「等布尔翻转 + 拍脑袋等两帧」。
 */
class KeyboardDismissScrollAnchorTest {

    private val src = stripComments(
        File("src/main/java/com/novelforge/app/presentation/chat/ChatScreen.kt")
            .readText(Charsets.UTF_8)
    )

    @Test
    fun theRestoreIsNotTriggeredByTheImeBooleanFlipping() {
        // 这条是根因。imeVisible 是布尔量，键盘动画期间恒为 true ——
        // 挂在它上面的 effect 只会在漂移画完之后才跑。
        assertFalse(
            "复位又挂回 LaunchedEffect(imeVisible) 了：它只在 inset 归零那一刻翻转，" +
                "也就是漂移全部画完之后才修正 —— 必然还是「先触底再弹回」",
            Regex("LaunchedEffect\\(\\s*imeVisible\\s*\\)").containsMatchIn(src)
        )
    }

    @Test
    fun thereIsNoFrameCountHeuristic() {
        // `withFrameNanos` 等两帧 ≈ 33ms，键盘动画 ≈ 250ms。差一个数量级。
        // 拍出来的帧数没有依据，只会在动画时长变化时静默失效。
        assertFalse(
            "又出现了 withFrameNanos 这种拍脑袋的等帧：" +
                "键盘动画约 250ms，等两帧约 33ms，差一个数量级",
            src.contains("withFrameNanos")
        )
    }

    @Test
    fun theAnchorIsWatchedTogetherWithTheViewportHeight() {
        // 视口高度必须和滚动位置在**同一个 snapshotFlow** 里。
        // 键盘动画每一帧都在改它，底栏出现时又改一次。
        val flow = Regex("snapshotFlow\\s*\\{([\\s\\S]{0,400}?)\\}\\s*\\.collect")
            .findAll(src)
            .map { it.groupValues[1] }
            .firstOrNull { it.contains("viewportSize") }
        assertTrue(
            "没找到同时观察滚动位置和视口高度的那个 snapshotFlow —— " +
                "只观察滚动位置就发现不了视口变了",
            flow != null
        )
        assertTrue(
            "那个 flow 必须同时读 firstVisibleItemIndex 和 firstVisibleItemScrollOffset：\n$flow",
            flow!!.contains("firstVisibleItemIndex") && flow.contains("firstVisibleItemScrollOffset")
        )
    }

    @Test
    fun recordingAndRestoringLiveInTheSameCollect() {
        // 分成两条 flow 就有竞态：漂移值可能在复位之前覆盖掉锚点，
        // 而视口之后不再变化时不会再触发，错误就永久留下了。
        val body = anchorCollect()
        assertTrue("没找到处理 anchor 的 collect 块", body.isNotEmpty())
        assertTrue(
            "记锚点和钉回去必须写进同一个 collect（否则有竞态）：\n$body",
            body.contains("scrollToItem") && body.contains("anchor.value = index to offset")
        )
    }

    @Test
    fun aDriftedPositionIsNotRecordedAsTheAnchor() {
        // 视口刚变过的时候读到的位置是被重锚定冲刷出来的。
        // 记下来就等于把漂移当成用户的位置钉住 —— 越修越偏。
        val body = anchorCollect()
        assertTrue("没找到处理 anchor 的 collect 块", body.isNotEmpty())
        val guardAt = body.indexOf("viewportHeight.intValue != height")
        val pinAt = body.indexOf("scrollToItem")
        val recordAt = body.indexOf("anchor.value = index to offset")
        assertTrue("没找到「视口刚变过」的判断", guardAt >= 0)
        assertTrue("没找到钉回去的动作", pinAt >= 0)
        assertTrue("没找到记锚点的动作", recordAt >= 0)
        assertTrue(
            "钉回去必须发生在记锚点之前（顺序反了就等于把漂移记成用户位置）：\n$body",
            guardAt < pinAt && pinAt < recordAt
        )
    }

    @Test
    fun atTheBottomNothingIsRecordedOrPinned() {
        // 在底部时列表本来就该跟着键盘走。不记也不钉 ——
        // 否则「发话要能看见回复」这条会被这条护栏自己的修复挡掉。
        val body = anchorCollect()
        assertTrue("没找到处理 anchor 的 collect 块", body.isNotEmpty())
        val bottomAt = body.indexOf("index == 0 && offset == 0")
        assertTrue("没找到「在底部」的判断", bottomAt >= 0)
        val afterBottom = body.substring(bottomAt)
        val earlyReturn = afterBottom.indexOf("return@collect")
        assertTrue(
            "在底部时必须立刻返回，不记也不钉：\n$body",
            earlyReturn >= 0 && earlyReturn < afterBottom.indexOf("anchor.value = index to offset")
        )
        assertFalse(
            "在底部时不能钉位置：\n${afterBottom.substring(0, earlyReturn)}",
            afterBottom.substring(0, earlyReturn).contains("scrollToItem")
        )
    }

    /**
     * 取出「处理 anchor 的那个 `collect { … }`」的**完整**函数体。
     *
     * 两个坑，都是这版测试自己踩的：
     *
     * 1. 不用正则。这个 `collect` 里有嵌套的 `if { … }`，非贪婪的正则会在
     *    第一个 `}` 就停下 —— 拿到半个函数体，断言自然对不上，而且错得隐蔽
     *    （看着像代码有问题，而不是像断言工具有问题）。
     * 2. **不能拿 `anchor.value` 当锚点往回找 `{`。** 文件里第一个
     *    `anchor.value` 是 `anchor.value = null`，它最近的前一个 `{` 是
     *    `if (index == 0 && offset == 0) {` 的花括号 —— 拿到的是那个 if，
     *    不是 collect。得从 `.collect` 往后找第一个 `{`。
     *
     * 文件里有多个 `.collect`，所以逐个配平，取第一个含 `anchor.value` 的。
     */
    private fun anchorCollect(): String {
        var from = 0
        while (true) {
            val c = src.indexOf(".collect", from)
            if (c < 0) return ""
            val open = src.indexOf('{', c)
            if (open < 0) return ""
            val close = matchBrace(open)
            if (close > open) {
                val block = src.substring(open, close)
                if (block.contains("anchor.value")) return block
            }
            from = open + 1
        }
    }

    /** 从 [open]（必须是 `{`）开始括号配平，返回配平的那个 `}` 的下标。 */
    private fun matchBrace(open: Int): Int {
        var depth = 0
        var j = open
        while (j < src.length) {
            when (src[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return j
                }
            }
            j++
        }
        return -1
    }

    @Test
    fun imeVisibleStillDrivesTheBottomBar() {
        // 删掉的是「用 imeVisible 触发复位」，不是 imeVisible 本身 ——
        // 底栏还要靠它在键盘起来时藏起来（那是另一个修好的 bug）。
        assertTrue(
            "imeVisible 仍然要算出来给底栏用",
            src.contains("WindowInsets.ime.getBottom(density) > 0")
        )
    }

    /**
     * 剥掉注释（`//` 和 `/** */` 都剥，字符串字面量里的不算）。
     * 不剥的话，上面那条「不许再出现 withFrameNanos」会被它自己那句解释判死。
     */
    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inBlock = false
        while (i < text.length) {
            val c = text[i]
            val n = if (i + 1 < text.length) text[i + 1] else ' '
            when {
                inBlock -> {
                    if (c == '*' && n == '/') {
                        inBlock = false; i += 2
                    } else i++
                }
                c == '/' && n == '*' -> {
                    inBlock = true; i += 2
                }
                c == '/' && n == '/' -> {
                    val end = text.indexOf('\n', i)
                    i = if (end < 0) text.length else end
                }
                c == '"' -> {
                    out.append(c); i++
                    while (i < text.length) {
                        val d = text[i]
                        out.append(d)
                        if (d == '\\' && i + 1 < text.length) {
                            out.append(text[i + 1]); i += 2; continue
                        }
                        i++
                        if (d == '"') break
                    }
                }
                else -> {
                    out.append(c); i++
                }
            }
        }
        return out.toString()
    }
}
