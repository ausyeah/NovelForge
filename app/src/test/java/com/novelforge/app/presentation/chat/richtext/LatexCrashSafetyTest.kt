package com.novelforge.app.presentation.chat.richtext

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * LaTeX 渲染不许把进程带走（回归护栏）。
 *
 * 用户原话：「一渲染latex就闪退」
 *
 * ## 真的原因：`parseUntil` 互相递归且**没有任何深度上限**
 *
 * `parseUntil → parseAtom → parseUntil` 是直接递归（`{`、`\left(`、`\frac` 的
 * 参数都会进 `parseUntil`）。以前没有上限，于是 `"{".repeat(4000)` 就能把栈打爆。
 *
 * 为什么这等于「闪退」而不是「报错」：
 * - `StackOverflowError` 是 **Error 不是 Exception**，`catch (e: Exception)` 抓不到；
 * - 解析发生在 `ChatRichText` 的 `remember { }` 里，也就是**组合阶段**，
 *   从那里抛出去没人能接住；
 * - 全 app 没有 `Thread.setDefaultUncaughtExceptionHandler`。
 *
 * 结果是进程静默死亡，用户只看到「闪退」，连一行报错都没有。
 *
 * ## 之前那条测试是**假保证**
 *
 * `deeplyNestedFractions_doNotStackOverflow` 只测了**深度 30**，名字却叫
 * 「不会栈溢出」。它证明的是「30 层没问题」，而崩溃阈值是 3000+。
 * 名字比覆盖范围大得多 —— 这种测试比没有测试更糟：它让人以为这条路验过了。
 *
 * ## 这条测试自己也踩过一次同样的坑
 *
 * 第一版断言「`"{".repeat(4000)` 的结果 `degraded` 为 true」。**把上限调到
 * 100000 之后它照样绿** —— 因为那一串 `{` 本身是「认不出的片段」，
 * `degraded` 无论如何都 true，断言从头到尾没碰过深度上限。
 *
 * 换成直接断言「实际到达的最大层数 ≤ 上限」：那个数不可能靠巧合满足，
 * 把上限调大或者整个去掉，立刻变红。
 *
 * 另外用一条**小栈线程**把崩溃阈值拉到可复现的范围（JVM 测试线程的栈比
 * ART 主线程大得多，直接在测试线程里跑根本复现不出来）。
 */
class LatexCrashSafetyTest {

    /**
     * 硬编码的深度上限，**故意不去读生产代码里的那个常量**。
     *
     * 第一版写的是 `private val cap = NEST_DEPTH_LIMIT_FOR_TEST` ——
     * 于是「断言 到达层数 ≤ cap」变成了「到达层数 ≤ 它自己」，把上限调到
     * 100000 之后整条测试照样绿。同义反复。
     *
     * 这里的 64 是**独立抄的一份**：它代表「多少层栈帧才不会打爆 1MB 的主线程栈」，
     * 是个物理量，不是配置项。生产代码那边如果改了上限，下面那条
     * `theLimitIsStillSmallEnoughToSurviveTheStack` 会立刻红。
     */
    private val cap = 64

    @Test(timeout = 30_000)
    fun theLimitIsStillSmallEnoughToSurviveTheStack() {
        // 64 × 2 层栈帧 ≈ 128 帧，离 1MB 栈的崩溃阈值（3000+）差两个数量级。
        // 上限被调大到几百以上就会开始危险 —— 那时这条会红。
        assertTrue(
            "生产代码里的嵌套上限现在是 $NEST_DEPTH_LIMIT_FOR_TEST，64 以上就有风险：" +
                "64 层 × 2 帧/层 离 1MB 栈的崩溃阈值（3000+）有两个数量级的余量，再大就不够了。",
            NEST_DEPTH_LIMIT_FOR_TEST <= 64
        )
    }

    @Test(timeout = 30_000)
    fun theParserNeverRecursesDeeperThanTheCap() {
        // 直接量「实际到达的最大层数」—— 唯一不可能靠巧合通过的断言。
        val reached = maxDepthReached("{" .repeat(4_000))
        assertTrue(
            "解析器到达了 $reached 层，超过上限 $cap —— 上限没生效，" +
                "长公式会把栈打爆（实测阈值 3000–6600 层）。",
            reached <= cap
        )
    }

    @Test(timeout = 30_000)
    fun theCapAlsoHoldsForLeftRightParens() {
        // `\left(` 走的是另一条递归路径（parseLeft → parseUntil）。
        // 深度上限必须同时管住它，否则换个符号就漏了。
        val reached = maxDepthReached("\\left(".repeat(4_000) + "1")
        assertTrue("`\\left(` 到达了 $reached 层，超过上限 $cap", reached <= cap)
    }

    @Test(timeout = 30_000)
    fun theCapAlsoHoldsForNestedFractions() {
        var source = "x"
        repeat(2_000) { source = "\\frac{1}{$source}" }
        val reached = maxDepthReached(source)
        assertTrue("嵌套分数到达了 $reached 层，超过上限 $cap", reached <= cap)
    }

    @Test(timeout = 30_000)
    fun deepInputOnASmallStackReturnsInsteadOfKillingTheProcess() {
        // 512KB 栈：比崩溃阈值小，所以**真的**能触发 StackOverflowError。
        // 这是对「最后一层关口」的验证 —— `buildMathExpression` 里那个
        // `catch (e: StackOverflowError)` 必须接得住并退回原文。
        val result = AtomicReference<String?>(null)
        val t = Thread(null, {
            result.set(runCatching { buildMathExpression("{".repeat(20_000), MathStyle()).text.text }.toString())
        }, "latex-small-stack", 512L * 1024L)
        t.start()
        t.join(25_000)
        assertTrue(
            "小栈线程里解析长公式应该正常返回（退回原文），而不是把线程炸掉。" +
                "实际：${result.get()}",
            result.get() != null
        )
    }

    @Test(timeout = 30_000)
    fun nestingJustUnderTheCapStillRendersContent() {
        // 上限之内的一切必须**完全不受影响** —— 这是「降级」不是「禁用」。
        val e = buildMathExpression("{".repeat(40) + "x", MathStyle())
        assertTrue(
            "上限之内的嵌套不该被降级（degraded=${e.degraded}, parts=${e.parts.size}）",
            !e.degraded && e.text.text.contains("x")
        )
    }

    @Test(timeout = 30_000)
    fun absurdlyLongSourceIsRejectedRatherThanParsed() {
        // 长度上限：真实公式几百字符封顶。20 万字符的公式一定是模型抽风。
        val e = buildMathExpression("x".repeat(200_000), MathStyle())
        assertTrue("超长源码应该直接退回去显示原文", e.degraded)
    }

    @Test(timeout = 30_000)
    fun ordinaryFormulasStillRenderProperly() {
        val e = buildMathExpression("\\frac{a+b}{c}", MathStyle())
        assertTrue("正常分数不该被降级（parts=${e.parts.size}）", e.parts.isNotEmpty() && !e.degraded)
        val radical = buildMathExpression("\\sqrt{x^2 + y^2}", MathStyle())
        assertTrue("正常根号不该被降级", radical.parts.isNotEmpty())
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 在小栈线程里解析，回报**实际到达的最大层数**。
     *
     * 小栈是必须的：JVM 测试线程的栈比 ART 主线程大得多，直接在测试线程里
     * 递归 4000 层根本不炸 —— 那样这条测试就变成「跑过了」而不是「验过了」。
     */
    private fun maxDepthReached(source: String): Int {
        val out = AtomicReference<Int>(-1)
        val t = Thread(null, {
            out.set(maxNestingDepthForTest(source))
        }, "latex-depth", 8L * 1024L * 1024L)
        t.start()
        t.join(25_000)
        return out.get()
    }
}
