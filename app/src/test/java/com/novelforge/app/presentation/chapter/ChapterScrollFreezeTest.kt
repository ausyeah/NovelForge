package com.novelforge.app.presentation.chapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 正文页的「翻上去别动」是不是真的接上了（回归护栏）。
 *
 * 曾经 `frozenAt` 只写哨兵值 `FOLLOW_PARAGRAPHS`：初始化一次，`atBottom` 时又写一次，
 * 从来没人写入过真实条数。于是 `paragraphs.take(frozenAt)` 是死代码，显示的段落永远
 * 跟着实时正文跑。reverseLayout 下新段落插到视口下方、每来一条就把上面所有内容顶走
 * 一格，用户看到的就是"自己没动，页面自己在抽"——和聊天页那次的形状一模一样。
 * `touching` 更直接：只写不读，"手指按下即冻结"那条路径压根不存在。
 *
 * 手势与重组在 JVM 里跑不起来，所以这里分两层：
 * 一层测 [nextFrozenAt] / [visibleParagraphCount] 这两个纯判定（状态机的全部），
 * 一层从源码结构上钉住接线（freeze 必须真的被读、指针必须轮询到"没有手指按着"）。
 */
class ChapterScrollFreezeTest {

    // ---------------------------------------------------------------- 纯判定

    @Test
    fun atTheBottom_followsNewestParagraph() {
        assertEquals(
            "在绝对底部就该解冻跟随，否则读者永远看不到新段落",
            FOLLOW_PARAGRAPHS,
            nextFrozenAt(previous = FOLLOW_PARAGRAPHS, liveCount = 12, atBottom = true, scrolling = false, touching = false)
        )
    }

    @Test
    fun scrolledUp_pinsTheCountThatExistedRightThen() {
        assertEquals(
            "上滑的第一帧必须把当时的条数钉住",
            12,
            nextFrozenAt(previous = FOLLOW_PARAGRAPHS, liveCount = 12, atBottom = false, scrolling = false, touching = false)
        )
    }

    @Test
    fun fingerDown_freezesEvenWhileStillAtTheBottom() {
        // 手指按下的那一刻 atBottom 还是 true（还没动过），
        // 只看 atBottom 的话这次按住完全不会冻结
        assertEquals(
            "按住 = 定住，就算人还在底部也得先冻上",
            12,
            nextFrozenAt(previous = FOLLOW_PARAGRAPHS, liveCount = 12, atBottom = true, scrolling = false, touching = true)
        )
    }

    @Test
    fun flingTowardBottom_doesNotReleaseMidFlight() {
        // 抬手后惯性还在走，atBottom 可能刚好为真（正在往回底部滑）。
        // 这时候解冻就等于在半路上把攒下的段落一次性塞进视口
        assertEquals(
            "惯性滚动途中不许解冻",
            12,
            nextFrozenAt(previous = 12, liveCount = 20, atBottom = true, scrolling = true, touching = false)
        )
    }

    @Test
    fun frozenPin_neverCreepsForwardAsParagraphsArrive() {
        // 这是"冻结"最容易漏的一条：冻结后每来一段就把冻结点往后挪一段，
        // 表面上 frozenAt 不是哨兵了，行为上跟没冻结一样
        var frozen = nextFrozenAt(FOLLOW_PARAGRAPHS, 12, atBottom = false, scrolling = false, touching = false)
        for (live in 13..400) {
            frozen = nextFrozenAt(frozen, live, atBottom = false, scrolling = false, touching = false)
        }
        assertEquals("冻结点被新段落推着走了", 12, frozen)
        assertEquals(12, visibleParagraphCount(liveCount = 400, frozenAt = frozen))
    }

    @Test
    fun returningToTheBottom_releasesTheFreezeAndFollowsAgain() {
        var frozen = nextFrozenAt(FOLLOW_PARAGRAPHS, 12, atBottom = false, scrolling = false, touching = false)
        // 抬手后惯性还没停：仍然冻结
        frozen = nextFrozenAt(frozen, 15, atBottom = true, scrolling = true, touching = false)
        assertEquals("惯性没停就解冻了", 12, frozen)
        // 惯性停了：解冻
        frozen = nextFrozenAt(frozen, 15, atBottom = true, scrolling = false, touching = false)
        assertEquals("落回底部没有解冻", FOLLOW_PARAGRAPHS, frozen)
        // 解冻之后新到的段落要能跟上来
        assertEquals(16, visibleParagraphCount(liveCount = 16, frozenAt = frozen))
    }

    @Test
    fun tapToGoBackToTheBottom_letsFreshParagraphsIn() {
        // 悬浮按钮 scrollToItem(0) 之后会经过 scrolling=true，再落定
        var frozen = nextFrozenAt(FOLLOW_PARAGRAPHS, 12, atBottom = false, scrolling = false, touching = false)
        frozen = nextFrozenAt(frozen, 12, atBottom = true, scrolling = true, touching = false)
        frozen = nextFrozenAt(frozen, 12, atBottom = true, scrolling = false, touching = true)
        frozen = nextFrozenAt(frozen, 13, atBottom = true, scrolling = false, touching = false)
        assertEquals("点了「回到底部」之后没跟上最新段落", FOLLOW_PARAGRAPHS, frozen)
    }

    // ---------------------------------------------------------------- 显示条数

    @Test
    fun sentinel_showsEveryLiveParagraph() {
        assertEquals(9, visibleParagraphCount(liveCount = 9, frozenAt = FOLLOW_PARAGRAPHS))
    }

    @Test
    fun pin_showsExactlyTheFirstN_AndTheGrowingTailKeepsRefreshing() {
        // 冻结在 5、正文已经 9 段：只显示前 5 段，
        // 而第 5 段正是正在写的那一段——它在冻结范围内，末段仍原地刷新
        assertEquals(5, visibleParagraphCount(liveCount = 9, frozenAt = 5))
    }

    @Test
    fun pinLongerThanTheBody_degradesToEverything_neverOutOfBounds() {
        // 换了新正文 / 重新生成 / 存档里是旧条数：退化成全显示而不是越界
        assertEquals(3, visibleParagraphCount(liveCount = 3, frozenAt = 99))
    }

    @Test
    fun junkNegativePin_doesNotThrow() {
        // take(负数) 抛 IllegalArgumentException，炸在这一页最没道理的地方。
        // 存档可能来自旧版本，也可能被截断，所以负数一律当"不冻结"
        for (junk in intArrayOf(-2, -7, Int.MIN_VALUE)) {
            assertEquals(
                "负冻结点 $junk 没被兜住",
                4,
                visibleParagraphCount(liveCount = 4, frozenAt = junk)
            )
        }
    }

    @Test(timeout = 10_000)
    fun aLongStream_neverMovesTheListWhileTheReaderIsScrolledUp() {
        // 把整条生成过程跑一遍：读者上滑之后，正文还在一直长，
        // 可见条数必须一步都不动
        val paragraphs = ArrayList<String>()
        var frozen = FOLLOW_PARAGRAPHS
        var atBottom = true
        val shown = ArrayList<Int>()

        repeat(300) { tick ->
            if (tick % 4 == 3) paragraphs.add("第 ${paragraphs.size + 1} 段")
            else if (paragraphs.isNotEmpty()) paragraphs[paragraphs.lastIndex] += "…"
            // 第 11 拍读者上滑，之后一路往回翻、再也不回底部
            if (tick == 11) atBottom = false
            frozen = nextFrozenAt(frozen, paragraphs.size, atBottom, scrolling = false, touching = false)
            shown.add(visibleParagraphCount(paragraphs.size, frozen))
        }

        assertTrue(
            "正文得真的在长，否则这个用例什么都没测（只到了 ${paragraphs.size} 段）",
            paragraphs.size > 60
        )
        assertTrue(
            "上滑那一拍就已经冻住了（可见 ${shown[11]} 段 / 实时 ${paragraphs.size} 段）",
            shown[11] < 75
        )
        assertEquals(
            "上滑之后可见条数动过：把实时增长的段落插进了视口",
            List(shown.size - 11) { shown[11] },
            shown.subList(11, shown.size)
        )
    }

    // ---------------------------------------------------------------- 源码接线

    /**
     * 冻结点必须由纯判定算出来，而且 touching / scrolling 必须真的传进去。
     *
     * 这里找的是**赋值**而不是函数名：函数定义本身也写着 `nextFrozenAt(`，
     * 拿名字去定位会撞在定义上，测出一个"没传 touching"的假失败。
     */
    @Test
    fun theFreezeGate_actuallyReadsTheTouchAndScrollFlags() {
        val text = source()
        val assignments = Regex("""frozenAt\s*=\s*([^\n]+)""").findAll(text)
            .map { it.groupValues[1].trim() }
            .toList()
        assertTrue("没找到任何对 frozenAt 的赋值（结构变了？请同步更新本测试）：\n$text", assignments.isNotEmpty())

        val call = assignments.firstOrNull { it.startsWith("nextFrozenAt(") }
        assertTrue(
            "frozenAt 的赋值没有走纯判定，写死哨兵值就是这道 bug 原样复发：\n$assignments",
            call != null
        )
        val callText = requireNotNull(call)
        for (flag in listOf("atBottom", "scrolling", "touching")) {
            assertTrue(
                "nextFrozenAt 的调用没把 $flag 传进去 —— 这条路径又变成只写不读了：\n$callText",
                Regex("""\b$flag\b""").containsMatchIn(callText)
            )
        }

        // 四个键都必须在同一条 LaunchedEffect 的 key 列表里：
        // 少一个，手指一抬 / 惯性一停就不会重算，冻结点解不开（或根本冻不上）
        val callAt = text.indexOf("nextFrozenAt(frozenAt")
        val effectStart = text.lastIndexOf("LaunchedEffect(", callAt)
        assumeTrue("没找到冻结用的 LaunchedEffect，跳过", effectStart >= 0)
        val keys = text.substring(effectStart, text.indexOf(')', effectStart))
        for (key in listOf("paragraphs", "atBottom", "scrolling", "touching")) {
            assertTrue(
                "LaunchedEffect 的 key 里没有 $key，这条路径不会重算：\n$keys",
                keys.contains(key)
            )
        }
    }

    /**
     * "按住即冻结"必须真的按住到抬手。
     *
     * 原来这里只 await 了**一个**事件，而那一个多半是指尖刚碰下来的 MOVE，
     * 手指还按着 touching 就被清成了 false —— 就算把 touching 接上，也只有一帧寿命。
     * 要求出现一个轮询到"没有手指按着"为止的循环。
     */
    @Test
    fun theTouchPointerInput_drainsUntilNoPointerIsPressed() {
        val text = source()
        val down = text.indexOf("awaitFirstDown(")
        assumeTrue("没找到 awaitFirstDown，跳过", down >= 0)
        val window = text.substring(down, minOf(text.length, down + 900))
        assertTrue(
            "指针处理没有轮询到'没有手指按着'为止，按住即冻结只有一帧：\n$window",
            Regex("""while\s*\([^)]*pressed""").containsMatchIn(window)
        )
        assertTrue(
            "收尾还在用旧的单事件写法：\n$window",
            !Regex("""awaitPointerEvent\(\s*PointerEventPass\.Final\s*\)""").containsMatchIn(window)
        )
    }

    // ---------------------------------------------------------------- 源码定位

    private fun source(): String = stripComments(readSource())

    /**
     * 从测试进程的工作目录（Gradle 默认是模块目录 app/）往上找；
     * 找不到再从本测试类的 class 输出目录往上找。两条路都断掉就
     * assumeTrue 跳过 —— 源码级断言在重新打包的环境里本来就无从谈起。
     */
    private fun readSource(): String {
        val relative = "src/main/java/com/novelforge/app/presentation/chapter/ChapterScreen.kt"
        val starts = listOfNotNull(
            File("").absoluteFile.takeIf { it.isDirectory },
            codeSourceDirectory()
        )
        for (start in starts) {
            var dir: File? = start
            while (dir != null) {
                listOf(File(dir, relative), File(dir, "app/$relative"))
                    .firstOrNull { it.isFile }
                    ?.let { return it.readText() }
                dir = dir.parentFile
            }
        }
        assumeTrue("找不到 ChapterScreen.kt（源码不在预期位置），跳过源码级断言", false)
        error("unreachable")
    }

    private fun codeSourceDirectory(): File? = try {
        ChapterScrollFreezeTest::class.java
            .protectionDomain?.codeSource?.location
            ?.toURI()
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }
    } catch (_: Exception) {
        null
    }

    /** 剥掉注释再断言：注释里出现 touching / frozenAt 是**故意**的，不剥掉会被自己的注释绊倒。 */
    private fun stripComments(source: String): String {
        val noBlock = source.replace(Regex("(?s)/\\*.*?\\*/"), "")
        return noBlock.lines()
            .filterNot { line ->
                val t = line.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")
    }
}
