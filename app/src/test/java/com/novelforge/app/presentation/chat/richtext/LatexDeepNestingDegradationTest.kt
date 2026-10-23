package com.novelforge.app.presentation.chat.richtext

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 超深嵌套的**降级形态**。
 *
 * ## 为什么单独测形态而不只测「不崩」
 *
 * 「不崩」是底线，但用户看到的是**具体画成什么样**。README 里对这件事有承诺，
 * 承诺就必须钉死 —— 而且这个承诺一开始是**写错的**：
 * 我先写「只渲染前面一部分」，实际测出来两种写法表现完全不同。
 *
 * 实测（`{` × N 与 `\frac` 链 × N）：
 *
 * | 输入            | 结果                                                        |
 * |-----------------|-------------------------------------------------------------|
 * | `{` × 63        | 正常渲染，文本就是 `x`                                        |
 * | `{` × 64        | **完全空**（`isEmpty=true`）→ 调用方退回显示 `$...$` 原文     |
 * | `{` × 4000      | 同上                                                        |
 * | `\frac` × 60    | 正常，最外层分数是真排版                                      |
 * | `\frac` × 70    | **只排了最外层 1 个**分数部件，更深的摊成普通文字 `1/1/1/...`  |
 *
 * 「截断」这个词盖不住这两种：一个丢的是全部，一个是尾部。
 * 所以这里逐个钉死，别让 README 的说法和实现漂移。
 *
 * ## 这些数字为什么重要
 *
 * `isEmpty=true` 走的是 [appendMathExpression] 的 false 分支 —— 调用方会显示
 * 原始 `$...$`。**这条路径必须真的被走到**，否则用户看到的是一片空白，
 * 那比崩还难懂。（`buildMathExpression` 返回空是安全的；不安全的是调用方
 * 拿到空表达式之后还硬画。）
 */
class LatexDeepNestingDegradationTest {

    @Test(timeout = 30_000)
    fun braceGroupsJustUnderTheCapRenderNormally() {
        // 63 层：上限之内，行为必须和加这个上限之前完全一样。
        val e = buildMathExpression("{".repeat(63) + "x", MathStyle())
        assertFalse("63 层不该是空的", e.isEmpty)
        assertFalse("63 层不该被标成降级（上限之内行为不变）", e.degraded)
        assertEquals("文本应当就是 x", "x", e.text.text)
    }

    @Test(timeout = 30_000)
    fun braceGroupsAtTheCapCollapseToEmptySoCallerFallsBackToSource() {
        val e = buildMathExpression("{".repeat(64) + "x", MathStyle())
        assertTrue(
            "64 层应当整条渲染不出来，调用方据此退回显示原文 —— " +
                "如果这里非空，用户会看到一片没有意义的括号",
            e.isEmpty
        )
        assertTrue("并且要标成降级，UI 才知道这不是模型写的", e.degraded)
    }

    @Test(timeout = 30_000)
    fun wayOverTheCapBehavesTheSameAsExactlyAtIt() {
        // 4000 层和 64 层必须是同一种结果。多出来的几千层不该改变任何表现，
        // 否则「上限」就不是上限，而是另一个没有文档的分支。
        val a = buildMathExpression("{".repeat(64) + "x", MathStyle())
        val b = buildMathExpression("{".repeat(4_000) + "x", MathStyle())
        assertEquals("isEmpty 应当一致", a.isEmpty, b.isEmpty)
        assertEquals("degraded 应当一致", a.degraded, b.degraded)
    }

    @Test(timeout = 30_000)
    fun nestedFractionsKeepOnlyTheOutermostPartAndFlagItDegraded() {
        // `\frac` 链和 `{` 组的行为不一样，这条把差异钉死：
        // 最外层分数仍然是真排版（parts=1），更深的部分摊成 `1/1/1/...` 文字。
        var s = "x"
        repeat(70) { s = "\\frac{1}{$s}" }
        val e = buildMathExpression(s, MathStyle())
        assertFalse("不该整条变空", e.isEmpty)
        assertEquals("只有最外层那一个分数是真排版", 1, e.parts.size)
        assertTrue("必须标成降级 —— 里面已经不是原来的公式了", e.degraded)
    }

    @Test(timeout = 30_000)
    fun nestedFractionsJustUnderTheCapAreNotDegraded() {
        var s = "x"
        repeat(60) { s = "\\frac{1}{$s}" }
        val e = buildMathExpression(s, MathStyle())
        assertFalse("60 层不该被降级（上限之内行为不变）", e.degraded)
    }

    @Test(timeout = 30_000)
    fun anEmptyResultMakesAppendMathExpressionRefuseRatherThanDrawNothing() {
        // 这是「空表达式」唯一的安全出口：返回 false，调用方显示原文。
        // 如果这一条被改坏，用户看到的就是**一片空白**而不是原文 ——
        // 比崩更隐蔽，因为它不报错、不留痕迹。
        val builder = AnnotatedString.Builder("")
        val parts = mutableMapOf<String, MathPart>()
        val accepted = appendMathExpression(
            builder = builder,
            source = "{".repeat(4_000),
            style = MathStyle(),
            parts = parts
        )
        assertFalse("渲染不出来时必须返回 false（调用方据此显示原文）", accepted)
        assertTrue("并且不能往正文里塞任何占位标记", builder.toAnnotatedString().text.isEmpty())
        assertTrue("也不能留下任何占位部件", parts.isEmpty())
    }
}
