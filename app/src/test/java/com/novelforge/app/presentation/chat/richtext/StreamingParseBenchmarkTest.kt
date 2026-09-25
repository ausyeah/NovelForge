package com.novelforge.app.presentation.chat.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 流式输出的解析成本基准。
 *
 * 场景就是 ChatScreen 里发生的那件事：每 50ms（20Hz）把正在流式输出的那条
 * 消息替换成一个更长的字符串，然后 `ChatRichText(text = ...)` 整棵重组。
 * 所以「一次流」= 拿最终文档的 1%、2%、…、100% 前缀各 parse 一次。
 *
 * 这不是 microbenchmark 洁癖：50ms 是一帧的预算，一个气泡必须在预算内
 * 重新出块列表，否则重排就会掉帧。先量出来，才知道该不该动刀。
 *
 * **量出来的结论（别再重复怀疑）：解析不是流式卡顿的原因。**
 *
 * - [parseMarkdown] 是**纯块切分**，一行都不碰行内语法。整篇 5054 字 /
 *   54 块 = 0.12~0.31ms（这台机器的噪声带），是 50ms 帧预算的 0.6%。
 *   一次 100 帧的流总共 8~16ms，挂在 5000ms 的挂钟时间上 = 0.3%。
 * - 唯一**不能**缓存的尾块 `buildMarkdownInline` = 0.012ms。
 * - 「什么都不缓存」的理论上界（整篇 161 个行内片段全渲染）= 0.16~0.56ms。
 * - 规模是线性的（约 50ns/字符）。要靠解析吃掉一帧需要 ~100 万字符。
 * - 下游本来就是 O(1~3 块)/帧：见 [changedBlocksPerFrame]。
 *
 * 所以「把 parseMarkdown 改成增量」能省下每帧 0.1~0.3ms —— 帧预算的 0.6%。
 * 这笔优化不成立，别做。流式卡顿在别处（重组/布局/测量，不在这四个文件里）。
 *
 * 真正有超线性行为的只有一处：[inlinePathologicalUnmatchedMarkers] ——
 * 单个段落里塞满「能开不能闭」的 `*` 时行内扫描是平方级的（1200 字符的
 * 段落 0.85ms）。真实尾块是一个句子，够不着这条线；但它是这四个文件里
 * 唯一一处复杂度真的会炸的地方。
 *
 * 计时口径：CPU 密集的解析报**最小值**和**中位数**两个数。一次 GC 或一次
 * JIT 编译就能把单次测量抬到几毫秒，只报平均值/最大值会把结论带偏。
 */
class StreamingParseBenchmarkTest {

    // ---------------------------------------------------------------- 素材

    /**
     * 一段「像样」的模型长答，循环铺到 [targetChars] 左右。
     *
     * 段落类型轮转铺开（散文 / 小标题 / 表格 / 无序 / 有序 / 任务 / 围栏代码 /
     * 引用 / 行内公式 / display 公式），刻意**不是**把所有重结构堆在结尾：
     * 真实答案的绝大多数字符都在早就定稿的块里，只有最后一块在长，
     * 而「重结构」在文档中段也大量存在 —— 那正是能靠缓存省掉的部分。
     */
    private fun sampleDocument(targetChars: Int = 5000): String {
        val sb = StringBuilder(targetChars + 512)
        var round = 0
        while (sb.length < targetChars) {
            appendSection(sb, round)
            round++
        }
        return sb.toString()
    }

    private fun appendSection(sb: StringBuilder, round: Int) {
        when (round % 8) {
            0 -> {
                sb.appendLine("## $round. 转折的层次")
                sb.appendLine()
                sb.appendLine(
                    "这一段要同时推进 **主线** 和 `伏笔` 两条线。前面埋的" +
                        "[时间线说明](https://example.com/timeline/$round) 到这里要第一次" +
                        "收网，所以每段之间都得留一点呼吸的空间，否则读者会觉得" +
                        "信息是硬塞进来的。第 $round 轮的落点是：让主角自己做选择，" +
                        "而不是让情节推着他走。"
                )
            }

            1 -> {
                sb.appendLine(
                    "于是有了这样一段：主角在 $round 步之前" +
                        "就已经知道真相的一半，但他选择不说 —— 因为说出来会" +
                        "连累另一个人。这个选择的代价在后面 $round + 2 步才结算，" +
                        "结算的方式不能是「他后悔了」，得是一个具体的损失。"
                )
                sb.appendLine()
                sb.appendLine("1. 先让主角**拒绝**这份契约，理由必须是他自己的。")
                sb.appendLine("2. 再让他发现契约的代价被谁转移了。")
                sb.appendLine("3. 最后才让他签 —— 签的时候要犹豫，而且不能犹豫太久。")
                sb.appendLine("4. 签完之后 $round 步之内不许再提这件事。")
            }

            2 -> {
                sb.appendLine("| 章节 | 出场次数 | 关键道具 | 备注 |")
                sb.appendLine("| --- | :---: | ---: | --- |")
                for (i in 1..4) {
                    sb.appendLine(
                        "| 第 $i 章 | ${12 * i + round} | ${"铜钥匙, 旧地图, 契约书".split(", ")[i % 3]} | ${if (i % 2 == 0) "推进" else "埋伏笔"} |"
                    )
                }
                sb.appendLine()
                sb.appendLine(
                    "表格的列宽是固定的，所以正文里不要再塞长句子 —— " +
                        "超过两行的单元格会把整张表撑到气泡外面去。"
                )
            }

            3 -> {
                sb.appendLine("- 表面上的盟友其实是上一章那个被放走的人")
                sb.appendLine("- 背叛的动机要落在**利益**上，不要落在感情上")
                sb.appendLine("- 主角当场不能翻脸，翻脸了就前功尽弃")
                sb.appendLine("- [x] 已经把线索埋进第 $round 段的旁白里")
                sb.appendLine("- [ ] 还需要在结尾回收一次")
                sb.appendLine()
                sb.appendLine(
                    "  - 嵌套一层：提醒自己列表可以很深，但超过两层读者就" +
                        "会开始扫而不是读了，$round 这一层够了。"
                )
            }

            4 -> {
                sb.appendLine("```kotlin")
                sb.appendLine("data class Turn(val name: String, val cost: Int)")
                sb.appendLine()
                sb.appendLine("fun apply(turns: List<Turn>): Int =")
                sb.appendLine("    turns.filter { it.cost > 0 }")
                sb.appendLine("        .sumOf { it.cost }")
                sb.appendLine("```")
                sb.appendLine()
                sb.appendLine(
                    "代码块只能证明「确实算了」，证明不了「算得对」。" +
                        "第 $round 轮的结论要能在纸面上复核一遍。"
                )
            }

            5 -> {
                sb.appendLine("> 引用也要省着用。一段里超过两个引用，读者就分不清" +
                    "哪句是叙述、哪句是转述了。")
                sb.appendLine(">")
                sb.appendLine("> —— 第 $round 轮批注")
                sb.appendLine()
                sb.appendLine(
                    "引用块外面这段是必要的：把转述和叙述分开，否则读者会把" +
                        "角色的台词当成作者的判断。"
                )
            }

            6 -> {
                sb.appendLine(
                    "代价按 " + "$" + "cost_{n} = " + "\\" + "frac{bases}{turns} 累加，" +
                        "其中 bases 是前 $round 轮的总和，所以"
                )
                sb.appendLine()
                sb.appendLine(
                    "$" + "$" + "\\sum_{i=1}^{n} cost_i = " +
                        "\\" + "sqrt{\\frac{T}{T_0}} \\times \\text{base}$" + "$"
                )
                sb.appendLine()
                sb.appendLine(
                    "算出来比预期高，说明这一章不能再加转折了，" +
                        "下一章得用来**收**而不是**开**。参考 " +
                        "https://example.com/cost-model/$round 里的推导，" +
                        "那边用了一个稍有不同的口径。"
                )
            }

            else -> {
                sb.appendLine(
                    "最后一段是收束：把前 $round 轮的变化各压成一句话，" +
                        "贴在章末当「本章要点」。不要复述正文，只写变化 —— " +
                        "复述会让读者觉得这一章白读了，而只写变化才会让人觉得" +
                        "时间真的往前走了。第 $round 轮到此为止。"
                )
            }
        }
        sb.appendLine()
    }

    /** 帧预算，和 ChatScreen 的 PUBLISH_INTERVAL_MS 一致。 */
    private val frameBudgetMs = 50.0

    // ---------------------------------------------------------------- 用例

    /**
     * 一次「流」的总成本曲线。打印出来看：最后几帧的耗时决定了观感，
     * 总和决定了整段流式输出期间主线程被占掉多少。
     */
    @Test
    fun streamingPrefixCurve() {
        report(streamingPrefixes())

        // 只钉一个粗框：解析不该自己吃掉一整帧。真正的判据是上面打印出来的
        // 数字，不是这条断言 —— 它只是防止量到一个离谱的实现。
        val full = time(sampleDocument(), 21) { parseMarkdown(it) }.second
        assertTrue(
            "整篇 parseMarkdown ${"%.2f".format(Locale.ROOT, full)}ms 已经吃掉一帧预算 ${frameBudgetMs}ms",
            full < frameBudgetMs
        )
    }

    /**
     * 尾块的行内渲染成本。`buildMarkdownInline` 按块 key 缓存，尾块每帧都在变，
     * 所以它是**唯一无法缓存**的那一段，必须单独看它多大。
     */
    @Test
    fun tailBlockInlineBuildCost() {
        val doc = sampleDocument()
        val blocks = parseMarkdown(doc)
        assertTrue("样例文档应该切出多个块", blocks.size >= 40)
        val inlineSources = blocks.last().inlineSources()
        assertTrue("尾块应该有正文可渲染", inlineSources.isNotEmpty())
        val sink = MathInlineSink { _, _ -> false }
        val options = InlineStyleOptions()

        repeat(200) {
            for (s in inlineSources) buildMarkdownInline(s, sink, options)
        }

        var total = 0.0
        var worst = 0.0
        var n = 0
        for (round in 0 until 200) {
            for (s in inlineSources) {
                val t0 = System.nanoTime()
                buildMarkdownInline(s, sink, options)
                val dt = (System.nanoTime() - t0) / 1e6
                if (round >= 20) { // 丢掉预热轮
                    total += dt
                    n++
                    if (dt > worst) worst = dt
                }
            }
        }
        val mean = total / n
        println(
            "[bench] buildMarkdownInline 尾块 x%d : 平均 %6.3f ms / 最坏 %6.3f ms"
                .format(Locale.ROOT, inlineSources.size, mean, worst)
        )
        assertTrue("行内解析不该吃掉一帧", mean < frameBudgetMs)
    }

    /**
     * 解析必须是确定的，而且块是**结构相等**的。
     *
     * 这是 per-block 缓存的地基：`rememberInlineRich(block.text)` 靠块结构
     * 相等来命中，一旦有人把 MarkdownBlock 从 data class 改成普通类，
     * 「每帧只重算 1~3 个块」这个前提当场失效，而且不会有任何测试变红 ——
     * 所以把它钉在这里。只比较结构（不是实例）：本测试不跑 Compose 重组，
     * 拿不到重组层的行为。
     */
    @Test
    fun parseIsDeterministicAndStructurallyStable() {
        val doc = sampleDocument()
        for (pct in intArrayOf(1, 3, 7, 13, 29, 50, 71, 88, 96, 100)) {
            val prefix = doc.take(doc.length * pct / 100)
            val a = parseMarkdown(prefix)
            val b = parseMarkdown(prefix)
            // 逐块比结构，不比实例：data class 的 equals 保证的正是这个
            assertEquals("pct=$pct 块数不一致", a.size, b.size)
            for (i in a.indices) {
                assertEquals("pct=$pct 第 $i 块结构不相等", a[i], b[i])
            }
        }
    }

    // ---------------------------------------------------------------- 规模 / 病态输入

    /**
     * 规模曲线：文档翻几倍，parseMarkdown 是不是线性。
     *
     * 「整篇重解析」听起来是二次的（流式 N 帧 -> 总代价 N²），但那说的是
     * **总**代价。**单帧**代价只要是 O(文档)，就已经没有病态了。
     */
    @Test
    fun scalingWithDocumentSize() {
        println("[bench] 规模曲线（单帧 = 整篇一次 parse）")
        println("[bench]   字符数   块数   parseMarkdown")
        for (chars in intArrayOf(2_000, 5_000, 10_000, 20_000, 40_000)) {
            val doc = sampleDocument(chars)
            repeat(25) { parseMarkdown(doc) }
            val ms = time(doc, 21) { parseMarkdown(it) }.first
            println(
                "[bench] %8d %6d %8.3f ms  (%.1f ns/字符)"
                    .format(Locale.ROOT, doc.length, parseMarkdown(doc).size, ms, ms * 1e6 / doc.length)
            )
        }
    }

    /**
     * 病态输入：单段里塞满「开得了、闭不上」的标记。
     *
     * 闭合位置是从当前位置一路扫到段尾的（`findCloser` / `linkAt`）。要让
     * 每次扫描都白跑到底，就得让每个标记都**能开**但**不能闭**：左标记后面
     * 跟非空白（能开），右标记前面是空白（`rightFlanking` 为假，闭不上）。
     * 所以是 `*x *x *x ...` 而不是 `*x*x*x` —— 后者两两配对，反而是线性的。
     * 这类输入 LLM 真的会吐（写文档时满是 `*`，或贴了一段没写完的 `[链接`）。
     */
    @Test
    fun pathologicalUnmatchedOpeners() {
        println("[bench] 病态输入：单段 N 个「能开不能闭」的标记（平方级扫描）")
        for (n in intArrayOf(50, 100, 200, 400, 800)) {
            for (marker in listOf("*", "[")) {
                val para = buildString { repeat(n) { append(marker); append("x ") } }
                val doc = "前面一段正常的散文，用来提供一点上下文。\n\n$para\n"
                repeat(20) { parseMarkdown(doc) }
                val (min, med) = time(doc, 11) { parseMarkdown(it) }
                println(
                    "[bench]   marker='$marker' n=%4d 段长 %5d : min %8.3f ms / med %8.3f ms"
                        .format(Locale.ROOT, n, para.length, min, med)
                )
            }
        }
    }

    /** 表格是「补齐/折行」的重结构，一格一行的大表是最坏的正常输入。 */
    @Test
    fun tableHeavyDocument() {
        println("[bench] 表格密集")
        for (rows in intArrayOf(10, 50, 200, 800)) {
            val sb = StringBuilder()
            sb.appendLine("| a | b | c | d | e | f |")
            sb.appendLine("| --- | :---: | ---: | :--- | --- | :-: |")
            repeat(rows) { sb.appendLine("| r$it | 值$it | ${it * 3} | x | y | z |") }
            val doc = sb.toString()
            repeat(15) { parseMarkdown(doc) }
            val ms = time(doc, 11) { parseMarkdown(it) }.first
            println("[bench]   rows=%4d 字符 %6d : %8.3f ms".format(Locale.ROOT, rows, doc.length, ms))
        }
    }

    /** 链接是最容易在行内层出平方行为的地方（每个 `[` 都要扫到 `]`）。 */
    @Test
    fun linkHeavyDocument() {
        println("[bench] 链接密集（每帧都会重走的行内路径）")
        for (n in intArrayOf(10, 50, 200, 500)) {
            val sb = StringBuilder()
            repeat(n) { sb.append("参考 [说明$it](https://example.com/doc/$it) 和 `code$it`。\n\n") }
            val doc = sb.toString()
            repeat(20) { parseMarkdown(doc) }
            val ms = time(doc, 11) { parseMarkdown(it) }.first
            println("[bench]   links=%4d 字符 %6d : %8.3f ms".format(Locale.ROOT, n, doc.length, ms))
        }
    }

    /**
     * 行内层按内容形状分档。**这一层才是每帧真会重走的**
     * （尾块的 `rememberInlineRich` 每帧失效），所以比块切分重要得多。
     */
    @Test
    fun inlineBuildCostByContentShape() {
        val sink = MathInlineSink { _, _ -> false }
        val options = InlineStyleOptions()

        fun bench(name: String, source: String) {
            repeat(200) { buildMarkdownInline(source, sink, options) }
            val (min, med) = time(source, 51) { buildMarkdownInline(it, sink, options) }
            println(
                "[bench] %-34s len=%5d : min %8.3f ms / med %8.3f ms"
                    .format(Locale.ROOT, name, source.length, min, med)
            )
        }

        val prose = "这是一段普通的中文散文，没有粗体也没有代码，只是长度接近一个真实段落，" +
            "用来当基准线。" + "再补一点让它更长一点，再补一点让它更长一点。"
        bench("纯散文", prose)
        bench("1 个粗体", "这一段里有一个**粗体**标记，还有一段很长的尾巴文字用来把段撑开。")
        bench("1 个行内代码", "这一段里有一个 `codeSpan` 标记，还有一段很长的尾巴文字。")
        bench("1 个链接", "看 [文字](https://example.com/a) 这段文字尾巴很长很长很长。")
        bench("1 个裸链接", "看 https://example.com/a 这段文字尾巴很长很长很长很长。")
        bench("1 个 <> autolink", "看 <https://example.com/a> 这段文字尾巴很长很长很长。")
        bench("1 个 <> email", "看 <a@b.com> 这段文字尾巴很长很长很长很长很长很长的尾巴。")
        bench("1 个 $$ display", "尾巴文字尾巴文字尾巴文字 $$ \\frac{a}{b} $$ 尾巴文字。")
        bench("1 个 " + "$" + "x" + "$" + " 行内公式", "尾巴文字尾巴文字尾巴文字 " + "$" + "x^2 + y^2" + "$" + " 尾巴文字尾巴。")

        for (n in intArrayOf(10, 50, 200)) {
            bench("$n 个 [x](url) 链接", buildString {
                repeat(n) { append("看第").append(it).append("点[说明](https://example.com/d/").append(it).append(")。\n\n") }
            })
        }
        for (n in intArrayOf(10, 50, 200)) {
            bench("$n 个 <https://x> autolink", buildString {
                repeat(n) { append("看第").append(it).append("点<https://example.com/d/").append(it).append(">。\n\n") }
            })
        }
        for (n in intArrayOf(10, 50, 200)) {
            bench("$n 个裸链接", buildString {
                repeat(n) { append("看 https://example.com/d/").append(it).append(" 这个。\n\n") }
            })
        }
    }

    /**
     * 行内层的平方级：单段里 N 个「能开不能闭」的 `*`。
     *
     * [parseMarkdown] 只做块切分，**不碰行内**，所以块级再快也救不了这里。
     * 尾块每帧都要重跑一遍，所以这一项直接乘以帧数。
     */
    @Test
    fun inlinePathologicalUnmatchedMarkers() {
        val sink = MathInlineSink { _, _ -> false }
        val options = InlineStyleOptions()
        println("[bench] 行内病态：单段 N 个「能开不能闭」的标记")
        for (n in intArrayOf(50, 100, 200, 400)) {
            for (marker in listOf('*', '[')) {
                val para = buildString { repeat(n) { append(marker); append("x ") } }
                repeat(100) { buildMarkdownInline(para, sink, options) }
                val (min, med) = time(para, 21) { buildMarkdownInline(it, sink, options) }
                println(
                    "[bench]   marker='$marker' n=%4d 段长 %5d : min %8.3f ms / med %8.3f ms"
                        .format(Locale.ROOT, n, para.length, min, med)
                )
            }
        }
    }

    /**
     * 整篇行内渲染的总成本 —— 「如果什么都没缓存」的上界。
     *
     * 实际不会发生（`rememberInlineRich` 按块缓存），但它给出这套气泡的
     * 行内成本天花板，方便和帧预算比。
     */
    @Test
    fun wholeDocumentInlineBuildUpperBound() {
        val doc = sampleDocument()
        val blocks = parseMarkdown(doc)
        val all = blocks.inlineSources()
        val sink = MathInlineSink { _, _ -> false }
        val options = InlineStyleOptions()
        assertTrue("样例文档应该有很多行内片段", all.size >= 40)

        repeat(10) { for (s in all) buildMarkdownInline(s, sink, options) }
        val (min, med) = time("x", 21) {
            for (s in all) buildMarkdownInline(s, sink, options)
        }
        println(
            "[bench] 整篇行内渲染 x%d 片段（未缓存上界）: min %8.3f ms / med %8.3f ms"
                .format(Locale.ROOT, all.size, min, med)
        )
        // 实际每帧只有尾块重跑，所以再乘一个「尾块占总片段的比例」之外的上界：
        // 即使整篇都重渲染，也得在一帧预算内。
        assertTrue("整篇行内渲染不该吃掉一帧", min < frameBudgetMs)
    }

    /**
     * 直接 A/B：「每次调用现构造 Regex」vs「提到顶层 val」。
     *
     * 这是本文件里唯一一处**每帧**都会重复付的钱：`extractUrl` 对每个
     * `[x](url)` 都要走一次，而 `kotlin.text.Regex` 的构造会把 pattern
     * 立刻 `Pattern.compile` 一遍。所以把它单独量出来，而不是靠
     * `buildMarkdownInline` 的总时长去猜。
     */
    @Test
    fun regexConstructionPerCall_vsHoisted() {
        val pattern = """^(\S*?)\s+(?:"[^"]*"|'[^']*'|\([^)]*\))$"""
        val dest = "https://example.com/a"
        val hoisted = Regex(pattern)

        fun inline(): Boolean = Regex(pattern).find(dest) != null
        fun topLevel(): Boolean = hoisted.find(dest) != null

        repeat(2000) { inline(); topLevel() }

        fun bench(name: String, f: () -> Any) {
            val (min, med) = time("x", 201) { f() }
            println(
                "[bench] %-40s : min %8.3f us / med %8.3f us"
                    .format(Locale.ROOT, name, min * 1000, med * 1000)
            )
        }
        println("[bench] Regex 构造：现构造 vs 顶层 val（单次调用）")
        bench("Regex(pattern).find(dest)  [现构造]", ::inline)
        bench("hoisted.find(dest)         [顶层 val]", ::topLevel)
        bench("Regex(pattern) 仅构造", { Regex(pattern); Unit })
    }

    /**
     * `\r\n` 归一化的代价：`splitLines` 现在无条件做两次整篇 `replace`。
     *
     * LLM 流的正文几乎永远是纯 `\n`，那两次 replace 就是每帧白拷贝两遍
     * 整篇。这里量的是「有 `\r`」与「没有 `\r`」两种输入下整篇切分的成本。
     */
    @Test
    fun lineSplitCostCrlfVsLf() {
        val doc = sampleDocument()
        val crlf = doc.replace("\n", "\r\n")
        println("[bench] 整篇行切分：换行归一化")
        for ((label, text) in listOf("纯 \\n" to doc, "\\r\\n" to crlf)) {
            repeat(200) { parseMarkdown(text) }
            val (min, med) = time(text, 51) { parseMarkdown(it) }
            println(
                "[bench]   %-10s len=%5d : min %8.3f ms / med %8.3f ms"
                    .format(Locale.ROOT, label, text.length, min, med)
            )
        }
    }

    /**
     * 每帧真正「变了」的块有几个 —— 这才是决定重组/重排量的东西。
     *
     * `rememberInlineRich(block.text)` 按块缓存，所以只要块结构相等，
     * 已定稿的块就不会重跑行内解析。这个用例把「流式一帧改了几个块」
     * 量出来，顺便钉住那个前提：块必须是**结构相等**的（data class），
     * 一旦有人把 MarkdownBlock 改成普通类，per-block 缓存立刻失效。
     */
    @Test
    fun changedBlocksPerFrame() {
        val doc = sampleDocument()
        var prev = parseMarkdown("")
        var worst = 0
        var totalChanged = 0
        val changedHistogram = IntArray(24)
        for (pct in 1..100) {
            val next = parseMarkdown(doc.take(doc.length * pct / 100))
            var changed = 0
            val n = maxOf(prev.size, next.size)
            for (i in 0 until n) {
                val a = prev.getOrNull(i)
                val b = next.getOrNull(i)
                if (a != b) changed++
            }
            changedHistogram[minOf(changed, changedHistogram.size - 1)]++
            worst = maxOf(worst, changed)
            totalChanged += changed
            prev = next
        }
        println("[bench] 每帧变化的块数（越少越好，0 = 全部命中 per-block 缓存）")
        for (c in changedHistogram.indices) {
            if (changedHistogram[c] > 0) {
                println("[bench]   变化 $c 块 : ${changedHistogram[c]} 帧")
            }
        }
        println(
            "[bench] 最坏一帧变化 $worst 块 / 共 ${prev.size} 块；100 帧合计变化 $totalChanged 块" +
                "（整篇只重算 $totalChanged 次，而「每次整篇重解析」是 ${prev.size * 100} 次）"
        )
        // 块是 data class：同样的源码必须给出结构相等的块
        assertEquals("两次解析应结构相等", parseMarkdown(doc), parseMarkdown(doc))
    }

    // ---------------------------------------------------------------- 工具

    /** 一次「流」：把整篇按 1% 步进喂进去。 */
    private fun streamingPrefixes(count: Int = 100): List<String> {
        val doc = sampleDocument()
        println("[bench] 文档 ${doc.length} 字符 / ${doc.count { it == '\n' } + 1} 行")
        println("[bench] 一次流 = $count 帧 @ ${frameBudgetMs.toInt()}ms 预算 = ${count * frameBudgetMs / 1000}s 挂钟时间")
        return (1..count).map { doc.take(doc.length * it / count) }
    }

    /**
     * 逐前缀计时。CPU 密集的解析用**最小值**和**中位数**两个口径：一次 GC
     * 或一次 JIT 编译就能把单次测量抬到几毫秒，只报平均/最大值会把优化
     * 方向带偏。最小值是真实成本的下界，中位数是典型值。
     */
    private inline fun report(prefixes: List<String>) {
        val repeats = 25
        val warmups = 30
        repeat(warmups) { parseMarkdown(prefixes.last()) }

        val best = DoubleArray(prefixes.size)
        val med = DoubleArray(prefixes.size)
        val blockCounts = IntArray(prefixes.size)
        for ((i, prefix) in prefixes.withIndex()) {
            val (m, d) = time(prefix, repeats) { parseMarkdown(it) }
            best[i] = m
            med[i] = d
            blockCounts[i] = parseMarkdown(prefix).size
        }

        for ((label, a) in listOf("min" to best, "median" to med)) {
            println(
                "[bench] parseMarkdown($label) 整篇 %6.3f ms | 最坏帧 %6.3f ms | 100 帧合计 %8.3f ms"
                    .format(Locale.ROOT, a.last(), a.max(), a.sum())
            )
        }
        println(
            "[bench] 帧预算 50 ms -> 最坏帧占预算 min ${"%.2f".format(Locale.ROOT, 100 * best.max() / frameBudgetMs)}% / " +
                "median ${"%.2f".format(Locale.ROOT, 100 * med.max() / frameBudgetMs)}%"
        )
        println("[bench] 整篇 ${sampleDocument().length} 字符 / ${blockCounts.last()} 块")
        printCurve(best, med)
    }

    /** 跑 [repeats] 遍，返回 (最小值, 中位数) 毫秒。 */
    private inline fun time(input: String, repeats: Int, body: (String) -> Any): Pair<Double, Double> {
        val xs = DoubleArray(repeats)
        repeat(repeats) { r ->
            val t0 = System.nanoTime()
            body(input)
            xs[r] = (System.nanoTime() - t0) / 1e6
        }
        xs.sort()
        return xs[0] to xs[repeats / 2]
    }


    private fun printCurve(best: DoubleArray, med: DoubleArray) {
        val sb = StringBuilder("[bench] 曲线 1%..100% (min / median ms):\n")
        for (i in best.indices step 5) {
            sb.append(
                String.format(Locale.ROOT, "  %3d%% %6.3f / %6.3f%n", i + 1, best[i], med[i])
            )
        }
        sb.append(String.format(Locale.ROOT, "  100%% %6.3f / %6.3f", best.last(), med.last()))
        println(sb)
    }

    /** 这个块实际会送进 `buildMarkdownInline` 的所有源码。 */
    private fun MarkdownBlock.inlineSources(): List<String> = when (this) {
        is MarkdownBlock.Heading -> listOf(text)
        is MarkdownBlock.Paragraph -> splitDisplayMath(text).let { segs ->
            segs.filterIsInstance<MathSegment.Text>().map { it.text } +
                segs.filterIsInstance<MathSegment.Display>().map { it.source }
        }

        is MarkdownBlock.CodeBlock -> emptyList()
        is MarkdownBlock.ThematicBreak -> emptyList()
        is MarkdownBlock.TableBlock -> header + rows.flatten()
        is MarkdownBlock.ListBlock -> items.flatMap { it.blocks.inlineSources() }
        is MarkdownBlock.Blockquote -> blocks.inlineSources()
    }

    private fun List<MarkdownBlock>.inlineSources(): List<String> = flatMap { it.inlineSources() }
}
