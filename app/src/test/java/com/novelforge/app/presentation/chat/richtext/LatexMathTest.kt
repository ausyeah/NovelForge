package com.novelforge.app.presentation.chat.richtext

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LaTeX 子集的判定。
 *
 * 分两类断言：
 * - 认得的：文字内容 + span 区间（比如上标必须落在正确的那一段）
 * - 认不得的：**绝不能空白、绝不能抛异常**，必须退化成显示原文
 *
 * 第二类比第一类更重要。空白气泡是这里能犯的最糟错误。
 */
class LatexMathTest {

    private fun text(source: String) = buildMathExpression(source).text

    private fun expr(source: String, style: MathStyle = MathStyle()) =
        buildMathExpression(source, style)

    /** 私有的 LaTeX 反斜杠：源码里直接写 \frac 会被 Kotlin 当转义。 */
    private fun latex(body: String) = "\\$body"

    /**
     * 只取带基线偏移的 span。
     *
     * 变量默认是斜体（真公式就这样），所以 spanStyles 里混着一堆
     * italic；断言上下标位置时先把它们滤掉，别被噪声干扰。
     */
    private fun shiftsOf(out: androidx.compose.ui.text.AnnotatedString) =
        out.spanStyles.filter { it.item.baselineShift != null }
            .map { it.start to it.end to it.item.baselineShift }

    private fun scriptStyleOf(
        out: androidx.compose.ui.text.AnnotatedString,
        superscript: Boolean
    ) = out.spanStyles.first {
        it.item.baselineShift == if (superscript) {
            BaselineShift.Superscript
        } else {
            BaselineShift.Subscript
        }
    }.item

    // ---------- 上下标 ----------

    @Test
    fun superscript_usesBaselineShift() {
        val out = text("x^2")
        assertEquals("x2", out.text)
        assertEquals(listOf((1 to 2) to BaselineShift.Superscript), shiftsOf(out))
    }

    @Test
    fun subscript_usesBaselineShift() {
        val out = text("x_i")
        assertEquals("xi", out.text)
        assertEquals(listOf((1 to 2) to BaselineShift.Subscript), shiftsOf(out))
    }

    @Test
    fun combinedSubAndSup_keepBothShifts() {
        val out = text("x_{i}^{2}")
        assertEquals("xi2", out.text)
        assertEquals(
            listOf(
                (1 to 2) to BaselineShift.Subscript,
                (2 to 3) to BaselineShift.Superscript
            ),
            shiftsOf(out)
        )
    }

    @Test
    fun scriptOrderDoesNotMatter() {
        assertEquals("xi2", text("x^{2}_{i}").text)
    }

    @Test
    fun scripts_scaleDownWhenFontSizeIsGiven() {
        val out = expr("x^2", MathStyle(fontSize = 16.sp)).text
        // 16sp * 0.72 = 11.52sp
        assertEquals(11.52f, scriptStyleOf(out, superscript = true).fontSize.value, 0.01f)
    }

    @Test
    fun scriptWithoutFontSize_keepsBaselineShiftOnly() {
        val out = text("x^2")
        assertEquals(
            androidx.compose.ui.unit.TextUnit.Unspecified,
            scriptStyleOf(out, superscript = true).fontSize
        )
    }

    @Test
    fun variableIsItalic_butDigitsAreNot() {
        val out = text("x^2")
        val italic = out.spanStyles.filter { it.item.fontStyle == FontStyle.Italic }
        assertEquals(listOf(0 to 1), italic.map { it.start to it.end })
    }

    @Test
    fun bracesAreNotRendered() {
        assertEquals("x2", text("x^{2}").text)
    }

    @Test
    fun bigOperatorWithLimits_keepsLimitsNextToTheSymbol() {
        val out = text(latex("sum_{i=1}^{n}"))
        // 上下标画在 ∑ 旁边，不是叠成一张竖表，所以正文里是紧挨着的
        assertEquals("∑i=1n", out.text)
        assertEquals(
            listOf(
                (1 to 4) to BaselineShift.Subscript,
                (4 to 5) to BaselineShift.Superscript
            ),
            shiftsOf(out)
        )
    }

    // ---------- 分数 ----------

    @Test
    fun fraction_becomesAPartWithReadablePlaceholder() {
        val e = expr(latex("frac{a}{b}"))
        assertEquals(1, e.parts.size)
        val part = e.parts.values.first() as MathPart.Fraction
        assertEquals("a", part.numerator.text.text)
        assertEquals("b", part.denominator.text.text)
        // 正文里留下可读文本，选中复制不会拷出 \frac
        assertEquals("a/b", e.text.text)
        assertEquals(latex("frac{a}{b}"), e.altText)
    }

    @Test
    fun fractionWidth_isEstimatedFromTheLongerSide() {
        val e = expr(latex("frac{12345}{1}"))
        val part = e.parts.values.first() as MathPart.Fraction
        assertTrue("太窄了会压到相邻文字", part.widthEm > 2f)
        assertTrue("高度要留得下上下叠排", part.heightEm >= 2f)
    }

    @Test
    fun nestedFraction_keepsItsOwnParts() {
        val e = expr(latex("frac{1}{1+${latex("frac{1}{x}")}}"))
        assertEquals(1, e.parts.size)
        val part = e.parts.values.first() as MathPart.Fraction
        assertEquals("1", part.numerator.text.text)
        // 分母自己也是一个 Text，它自己带一份 parts
        assertEquals("1+1/x", part.denominator.text.text)
        assertEquals(1, part.denominator.parts.size)
        val inner = part.denominator.parts.values.first() as MathPart.Fraction
        assertEquals("1", inner.numerator.text.text)
        assertEquals("x", inner.denominator.text.text)
    }

    @Test
    fun fractionWithBracesInsideBraces() {
        val e = expr(latex("frac{ {a+b} }{ {c} }"))
        val part = e.parts.values.first() as MathPart.Fraction
        assertEquals("a+b", part.numerator.text.text)
        assertEquals("c", part.denominator.text.text)
    }

    @Test
    fun fractionWithSingleTokenArgs() {
        // \frac ab 这种省略花括号的写法
        val e = expr(latex("frac ab"))
        val part = e.parts.values.first() as MathPart.Fraction
        assertEquals("a", part.numerator.text.text)
        assertEquals("b", part.denominator.text.text)
    }

    @Test
    fun fracIsNotWrittenAsSlash() {
        // 「不要只是写 a/b」：正文里必须是一个占位，不能是斜杠
        val e = expr(latex("frac{a}{b}"))
        assertFalse("分数被拍平成 a/b 了", e.text.text == "a/b" && e.parts.isEmpty())
        assertTrue(e.parts.isNotEmpty())
    }

    @Test
    fun fracWithNoArguments_degradesToRawSource() {
        val e = expr(latex("frac"))
        assertTrue(e.parts.isEmpty())
        assertEquals(latex("frac"), e.text.text)
        assertTrue(e.degraded)
    }

    // ---------- 根号 ----------

    @Test
    fun sqrt_becomesARadicalPart() {
        val e = expr(latex("sqrt{x}"))
        assertEquals(1, e.parts.size)
        val part = e.parts.values.first() as MathPart.Radical
        assertNull(part.index)
        assertEquals("x", part.radicand.text.text)
    }

    @Test
    fun sqrtWithIndex_keepsTheIndex() {
        val e = expr(latex("sqrt[3]{x}"))
        val part = e.parts.values.first() as MathPart.Radical
        assertNotNull(part.index)
        assertEquals("3", part.index!!.text.text)
        assertEquals("x", part.radicand.text.text)
    }

    @Test
    fun sqrtOfAnExpression_keepsTheBracesContent() {
        val part = expr(latex("sqrt{1+x^2}")).parts.values.first() as MathPart.Radical
        assertEquals("1+x2", part.radicand.text.text)
    }

    // ---------- 符号表 ----------

    @Test
    fun greekLetters_bothCases() {
        assertEquals("α", text(latex("alpha")).text)
        assertEquals("ω", text(latex("omega")).text)
        assertEquals("Γ", text(latex("Gamma")).text)
        assertEquals("Ω", text(latex("Omega")).text)
    }

    @Test
    fun greekLetters_areItalicLikeRealVariables() {
        val out = text(latex("alpha"))
        assertEquals(FontStyle.Italic, out.spanStyles.first().item.fontStyle)
    }

    @Test
    fun operators_mapToUnicode() {
        val cases = mapOf(
            "times" to "×", "cdot" to "·", "pm" to "±",
            "leq" to "≤", "geq" to "≥", "neq" to "≠", "approx" to "≈",
            "to" to "→", "rightarrow" to "→", "leftarrow" to "←",
            "infty" to "∞", "partial" to "∂", "nabla" to "∇",
            "sum" to "∑", "prod" to "∏", "int" to "∫"
        )
        for ((name, glyph) in cases) {
            assertEquals("命令 $name", glyph, text(latex(name)).text)
        }
    }

    @Test
    fun escapedBracesAndPipesAndPercent() {
        assertEquals("|", text(latex("|")).text)
        assertEquals("{", text(latex("{")).text)
        assertEquals("100%", text("100" + latex("%")).text)
        assertEquals("a&b", text("a" + latex("&") + "b").text)
    }

    @Test
    fun textCommand_rendersUpright() {
        val out = text(latex("text{你好世界}"))
        assertEquals("你好世界", out.text)
        assertEquals(1, out.spanStyles.size)
        // \text 是正体，不能继承变量的斜体
        assertEquals(FontStyle.Normal, out.spanStyles[0].item.fontStyle)
    }

    @Test
    fun leftRight_rendersTheDelimiters() {
        val source = latex("left(") + " x+1 " + latex("right)")
        assertEquals("(x+1)", text(source).text)
    }

    @Test
    fun leftRight_withNamedDelimiters() {
        val source = latex("left") + latex("langle") + " x " +
            latex("right") + latex("rangle")
        assertEquals("⟨x⟩", text(source).text)
    }

    @Test
    fun nestedLeftRight_closesCorrectly() {
        val source = latex("left(") + " a " + latex("left(") + " b " +
            latex("right)") + " c " + latex("right)")
        assertEquals("(a(b)c)", text(source).text)
    }

    @Test
    fun missingRight_degradesButKeepsTheBody() {
        val e = expr(latex("left( x+1"))
        // 内容一个字都不能吞
        assertTrue(e.text.text.contains("x+1"))
        assertTrue(e.text.text.contains('('))
        assertTrue(e.degraded)
    }

    @Test
    fun mathSpaces_areIgnored_butTextSpacesAreKept() {
        assertEquals("ab", text("a b").text)
        assertEquals("a b", text(latex("text{a b}")).text)
    }

    @Test
    fun alignmentMarkers_areDropped() {
        assertEquals("ab", text("a & b").text)
    }

    @Test
    fun displayLineBreakDoubleBackslash_becomesANewline() {
        // display 数学里的 `\\` 是换行
        assertEquals("a\nb", text("a " + latex("\\") + " b").text)
    }

    // ---------- 认不出来的东西必须显示原文 ----------

    @Test
    fun unknownCommand_showsRawSourceAndMarksDegraded() {
        val e = expr(latex("foo{x}"))
        assertTrue(e.text.text.contains(latex("foo")))
        assertTrue(e.text.text.contains("x"))
        assertTrue(e.degraded)
    }

    @Test(timeout = 20_000L)
    fun unknownCommand_doesNotThrow() {
        // 断言点在于「不抛」也不「挂」
        expr(latex("notacommand{a}{b}{c}"))
        expr(latex("\\"))
        expr(latex("frac"))
        expr(latex("sqrt"))
        expr(latex("left("))
        expr(latex("{"))
        expr(latex("}"))
        expr(latex("^"))
        expr(latex("_"))
        expr(latex("begin"))
        expr("((((((((((")
        expr("$")
        expr("$$")
        assertTrue(true)
    }

    @Test
    fun unclosedBrace_doesNotLoseTheContent() {
        val e = expr(latex("frac{a}{b"))
        assertTrue(e.text.text.contains("a"))
    }

    @Test
    fun unsupportedEnvironment_showsTheCommandName() {
        val e = expr(latex("begin{aligned} x " + latex("end")))
        assertTrue(e.text.text.contains("begin"))
    }

    @Test
    fun emptySource_rendersEmpty() {
        assertTrue(expr("").isEmpty)
        assertTrue(expr("   ").isEmpty)
    }

    @Test
    fun mathNeverRendersBlankForGarbageInput() {
        // 契约：appendMathExpression 要么塞进一段非空白正文，
        // 要么返回 false 让调用方退回显示 $...$ 原文。两种都不许给用户看空白。
        val garbage = listOf(
            latex("frac"), latex("sqrt"), latex("left("), "}", "^2", "_1",
            latex("foo"), "$$", latex("\\"), latex("text{"), "\uD83D\uDE00",
            latex("sum_{"), latex("frac{{}{}}"), "   ", ""
        )
        for (source in garbage) {
            val builder = androidx.compose.ui.text.AnnotatedString.Builder()
            val parts = LinkedHashMap<String, MathPart>()
            val accepted = appendMathExpression(builder, source, MathStyle(), parts)
            if (accepted) {
                assertFalse(
                    "「$source」被当成公式接进去了，可渲染出来是空白",
                    builder.toAnnotatedString().text.isBlank()
                )
            }
        }
    }

    @Test
    fun appendMathExpression_rejectsBlankAndKeepsTheLiteral() {
        val builder = androidx.compose.ui.text.AnnotatedString.Builder()
        val parts = LinkedHashMap<String, MathPart>()
        assertFalse(appendMathExpression(builder, "   ", MathStyle(), parts))
        assertTrue(parts.isEmpty())
        assertEquals("", builder.toAnnotatedString().text)
    }

    @Test
    fun appendMathExpression_appendsTextAndRecordsParts() {
        val builder = androidx.compose.ui.text.AnnotatedString.Builder()
        val parts = LinkedHashMap<String, MathPart>()
        builder.append("前")
        assertTrue(appendMathExpression(builder, latex("frac{a}{b}"), MathStyle(), parts))
        builder.append("后")
        assertEquals("前a/b后", builder.toAnnotatedString().text)
        assertEquals(1, parts.size)
    }

    // ---------- $$...$$ 切分 ----------

    @Test
    fun splitDisplayMath_splitsAroundTheDelimiters() {
        val segments = splitDisplayMath("前${'$'}${'$'}${latex("frac{a}{b}")}${'$'}${'$'}后")
        assertEquals(3, segments.size)
        assertEquals("前", (segments[0] as MathSegment.Text).text)
        assertEquals(latex("frac{a}{b}"), (segments[1] as MathSegment.Display).source)
        assertEquals("后", (segments[2] as MathSegment.Text).text)
    }

    @Test
    fun splitDisplayMath_withoutDelimiters_isOneTextSegment() {
        val segments = splitDisplayMath("没有公式")
        assertEquals(1, segments.size)
        assertEquals("没有公式", (segments[0] as MathSegment.Text).text)
    }

    @Test
    fun splitDisplayMath_emptyBody_doesNotProduceABlankSegment() {
        val segments = splitDisplayMath("${'$'}${'$'}\n\n${'$'}${'$'}")
        assertEquals(1, segments.size)
        assertEquals("${'$'}${'$'}\n\n${'$'}${'$'}", (segments[0] as MathSegment.Text).text)
    }

    @Test
    fun splitDisplayMath_unterminated_keepsTheRawSource() {
        val raw = "前面${'$'}${'$'}没有收尾"
        val segments = splitDisplayMath(raw)
        // 切出来可能多几段，但拼回去必须一字不差
        assertEquals(
            raw,
            segments.joinToString("") {
                when (it) {
                    is MathSegment.Text -> it.text
                    is MathSegment.Display -> "${'$'}${'$'}${it.source}${'$'}${'$'}"
                }
            }
        )
        assertTrue(segments.none { it is MathSegment.Display })
    }

    @Test
    fun splitDisplayMath_trimsTheSource() {
        val segments = splitDisplayMath("${'$'}${'$'}  x  ${'$'}${'$'}")
        assertEquals("x", (segments[0] as MathSegment.Display).source)
    }

    @Test
    fun splitDisplayMath_doesNotOpenOnEscapedDollars() {
        val raw = "价格 \\" + "$$" + " 不是公式"
        val segments = splitDisplayMath(raw)
        assertEquals(1, segments.size)
        assertEquals(raw, (segments[0] as MathSegment.Text).text)
    }

    // ---------- 规模与中文 ----------

    @Test(timeout = 20_000L)
    fun veryLongFormula_doesNotThrow() {
        val source = latex("sum") + "_{i=1}^{n}" + "i".repeat(2_000)
        val e = expr(source)
        assertFalse(e.text.text.isBlank())
    }

    /**
     * 30 层嵌套分数能正常解析。
     *
     * **这条原来叫 `deeplyNestedFractions_doNotStackOverflow` —— 那是假保证。**
     * 它只测到 30 层，而名字宣称的是「不会栈溢出」。崩溃阈值是 3000–6600 层，
     * 差了两个数量级。更糟的是解析器当时**根本没有深度上限**，所以这个名字
     * 不只是名不副实，它描述的那个性质当时压根不存在。
     *
     * 真正的护栏是 [LatexCrashSafetyTest]，它直接量「实际到达的最大层数」并
     * 断言它 ≤ 64。这里只保留「浅层嵌套确实能解析」这一半。
     */
    @Test(timeout = 20_000L)
    fun nestedFractionsUpTo30LevelsParse() {
        var source = "x"
        repeat(30) { source = latex("frac{1}{$source}") }
        val e = expr(source)
        assertTrue(e.parts.isNotEmpty())
    }

    @Test
    fun chineseInsideTextCommand_keepsEveryCharacter() {
        val out = text(latex("text{第一章 开始}"))
        assertEquals("第一章 开始", out.text)
    }

    @Test
    fun emojiInsideTextCommand_doesNotBreakRendering() {
        val out = text(latex("text{很好 😀 真的}"))
        assertEquals("很好 😀 真的", out.text)
    }

    @Test
    fun styleColorIsNotBakedIntoTheParser() {
        // 颜色由 UI 的 TextStyle 给，解析结果里不该出现硬编码颜色
        val e = expr("x^2", MathStyle(color = androidx.compose.ui.graphics.Color.Red))
        assertEquals(SpanStyle().color, e.text.spanStyles[0].item.color)
    }
}
