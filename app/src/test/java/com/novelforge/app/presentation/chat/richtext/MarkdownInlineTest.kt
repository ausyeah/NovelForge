package com.novelforge.app.presentation.chat.richtext

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行内 markdown 渲染的判定。
 *
 * 断言的是 AnnotatedString 里 span 的**位置**，不只是「没抛异常」：
 * 位置错了会出现「粗体范围多包一个字」「下划线盖到句号上」这种
 * 肉眼能看出、但肉眼看不出根因的毛病。
 */
class MarkdownInlineTest {

    private val dollar = "$"

    private fun styled(
        source: String,
        sink: MathInlineSink? = null
    ) = buildMarkdownInline(source, sink)

    /**
     * 链接标注的扁平视图：url + 覆盖区间。
     *
     * 现在链接只挂 [LinkAnnotation.Url]，不再发 stringAnnotation —— 后者唯一的
     * 用途是让人自己写指针命中测试，而那会把外层 SelectionContainer 的长按
     * 选词饿死（用户报"长按不能复制"）。
     */
    private data class UrlSpan(val url: String, val start: Int, val end: Int)

    private fun urlsOf(out: androidx.compose.ui.text.AnnotatedString) =
        out.getLinkAnnotations(0, out.length).map {
            UrlSpan((it.item as LinkAnnotation.Url).url, it.start, it.end)
        }

    private fun linksOf(out: androidx.compose.ui.text.AnnotatedString) =
        out.getLinkAnnotations(0, out.length)

    /**
     * 护栏：不得再发任何 tag 标注。
     *
     * 哪天有人又加回 stringAnnotation，多半就是想自己装 pointerInput 去点链接，
     * 而那正好会把长按选词重新打死。
     */
    private fun tagsOf(out: androidx.compose.ui.text.AnnotatedString) =
        out.getStringAnnotations(0, out.length)

    private fun mathSinkFor(parts: MutableMap<String, MathPart>) = MathInlineSink { builder, s ->
        appendMathExpression(builder, s, MathStyle(), parts)
    }

    // ---------- 强调 ----------

    @Test
    fun bold_producesBoldSpanOverTheWholeRun() {
        val out = styled("**粗体**")
        assertEquals("粗体", out.text)
        assertEquals(1, out.spanStyles.size)
        assertEquals(0 to 2, out.spanStyles[0].start to out.spanStyles[0].end)
        assertEquals(FontWeight.Bold, out.spanStyles[0].item.fontWeight)
    }

    @Test
    fun italic_producesItalicSpan() {
        val out = styled("*斜体*")
        assertEquals("斜体", out.text)
        assertEquals(1, out.spanStyles.size)
        assertEquals(FontStyle.Italic, out.spanStyles[0].item.fontStyle)
    }

    @Test
    fun italicWithUnderscore_works() {
        val out = styled("_斜体_")
        assertEquals("斜体", out.text)
        assertEquals(FontStyle.Italic, out.spanStyles[0].item.fontStyle)
    }

    @Test
    fun boldItalic_usesBothStyles() {
        val out = styled("***粗斜***")
        assertEquals("粗斜", out.text)
        assertEquals(1, out.spanStyles.size)
        assertEquals(FontWeight.Bold, out.spanStyles[0].item.fontWeight)
        assertEquals(FontStyle.Italic, out.spanStyles[0].item.fontStyle)
    }

    @Test
    fun nestedItalicInsideBold_nestsTheSpans() {
        val out = styled("**bold *italic* tail**")
        assertEquals("bold italic tail", out.text)
        // 外层粗体 0..16，内层斜体 5..11
        assertEquals(listOf(0 to 16, 5 to 11), out.spanStyles.map { it.start to it.end }.sortedBy { it.first })
        val bold = out.spanStyles.first { it.item.fontWeight == FontWeight.Bold }
        val italic = out.spanStyles.first { it.item.fontStyle == FontStyle.Italic }
        assertEquals(0 to 16, bold.start to bold.end)
        assertEquals(5 to 11, italic.start to italic.end)
    }

    @Test
    fun italicInsideBold_doesNotSwallowTheInnerMarkers() {
        // 顺序反了的话这里会退化成 "*bold italic tail*"（内层斜体被吃掉）
        val out = styled("**粗 *斜* 体**")
        assertEquals("粗 斜 体", out.text)
        assertEquals(2, out.spanStyles.size)
    }

    // ---------- 配不上的标记必须原样显示 ----------
    //
    // 这一组都带 timeout：扫不到配对符号时如果忘了把游标前移，
    // 表现是测试进程直接挂住而不是报错，更难查。

    @Test(timeout = 5_000L)
    fun multiplicationExpression_isNotEmphasis() {
        val out = styled("2 * 3 * 4")
        assertEquals("2 * 3 * 4", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test(timeout = 5_000L)
    fun unclosedBold_staysLiteral() {
        val out = styled("**没闭合")
        assertEquals("**没闭合", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test(timeout = 5_000L)
    fun unclosedItalic_staysLiteral() {
        val out = styled("结尾一个 *")
        assertEquals("结尾一个 *", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test(timeout = 5_000L)
    fun underscoreInsideWord_isNotEmphasis() {
        // snake_case_name 被拆成斜体是最经典的误伤
        val out = styled("snake_case_name")
        assertEquals("snake_case_name", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test(timeout = 5_000L)
    fun trailingUnderscore_staysLiteral() {
        val out = styled("file_name_")
        assertEquals("file_name_", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test(timeout = 5_000L)
    fun unmatchedBacktick_staysLiteral() {
        val out = styled("a ` b")
        assertEquals("a ` b", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test(timeout = 5_000L)
    fun onlyBackticks_noInnerCloser() {
        val out = styled("`")
        assertEquals("`", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test(timeout = 5_000L)
    fun unmatchedBracket_staysLiteral() {
        val out = styled("[只是方括号]")
        assertEquals("[只是方括号]", out.text)
        assertTrue(urlsOf(out).isEmpty())
    }

    @Test(timeout = 5_000L)
    fun unclosedLinkParenthesis_keepsTheBrackets() {
        val out = styled("[文字](https://a.cn")
        assertEquals("[文字](https://a.cn", out.text)
        // 方括号和左括号都留着；里面的裸 URL 仍然会被识别成链接，那是另一回事
        assertTrue(out.text.startsWith("[文字]("))
    }

    @Test(timeout = 5_000L)
    fun singleTilde_isNotStrikethrough() {
        val out = styled("~单个波浪~")
        assertEquals("~单个波浪~", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test(timeout = 5_000L)
    fun lessThanThatIsNotAnAutolink_staysLiteral() {
        val out = styled("a < b 且 c > d")
        assertEquals("a < b 且 c > d", out.text)
        assertTrue(urlsOf(out).isEmpty())
    }

    // ---------- 行内代码 ----------

    @Test
    fun inlineCode_usesMonospaceAndNoInnerParsing() {
        val out = styled("`code`")
        assertEquals("code", out.text)
        assertEquals(1, out.spanStyles.size)
        assertEquals(0 to 4, out.spanStyles[0].start to out.spanStyles[0].end)
        assertEquals(FontFamily.Monospace, out.spanStyles[0].item.fontFamily)
    }

    @Test
    fun inlineCode_doesNotParseInnerMarkdown() {
        val out = styled("`**不是粗体**`")
        assertEquals("**不是粗体**", out.text)
        assertEquals(1, out.spanStyles.size)
        assertEquals(FontFamily.Monospace, out.spanStyles[0].item.fontFamily)
    }

    @Test
    fun doubleBacktick_canContainASingleBacktick() {
        val out = styled("``a`b``")
        assertEquals("a`b", out.text)
        assertEquals(1, out.spanStyles.size)
        assertEquals(0 to 3, out.spanStyles[0].start to out.spanStyles[0].end)
    }

    // ---------- 删除线 ----------

    @Test
    fun strikethrough_producesLineThrough() {
        val out = styled("~~删掉~~")
        assertEquals("删掉", out.text)
        assertEquals(1, out.spanStyles.size)
        assertEquals(TextDecoration.LineThrough, out.spanStyles[0].item.textDecoration)
    }

    // ---------- 链接 ----------

    @Test
    fun markdownLink_keepsLabelAndUrl() {
        val out = styled("[文字](https://example.com)")
        assertEquals("文字", out.text)
        val links = urlsOf(out)
        assertEquals(1, links.size)
        assertEquals("https://example.com", links[0].url)
        assertEquals(0 to 2, links[0].start to links[0].end)
        assertEquals(1, linksOf(out).size)
    }

    @Test
    fun markdownLink_dropsTheTitle() {
        val out = styled("[文字](https://example.com \"点我\")")
        assertEquals("https://example.com", urlsOf(out).first().url)
    }

    @Test
    fun markdownLink_nestedMarkupInLabel() {
        val out = styled("[**粗**的链接](https://a.cn)")
        assertEquals("粗的链接", out.text)
        val bold = out.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertEquals(1, bold.size)
        assertEquals(0 to 1, bold[0].start to bold[0].end)
        // 链接本身还压着一层下划线
        assertEquals(1, linksOf(out).size)
    }

    @Test
    fun bareUrl_becomesALink() {
        val out = styled("地址 https://example.com 就行")
        assertEquals("地址 https://example.com 就行", out.text)
        val links = urlsOf(out)
        assertEquals(1, links.size)
        assertEquals("https://example.com", links[0].url)
        // "地址 " 占 3 个字符，网址本身 19 个
        assertEquals(3 to 22, links[0].start to links[0].end)
    }

    @Test
    fun bareUrl_stripsTrailingPunctuation() {
        val out = styled("见 https://example.com。")
        val links = urlsOf(out)
        assertEquals("https://example.com", links[0].url)
    }

    @Test
    fun angleAutolink_works() {
        val out = styled("<https://a.cn/x>")
        assertEquals("https://a.cn/x", out.text)
        assertEquals("https://a.cn/x", urlsOf(out).first().url)
    }

    @Test
    fun emailAutolink_becomesMailto() {
        val out = styled("<hi@a.cn>")
        assertEquals("mailto:hi@a.cn", urlsOf(out).first().url)
    }

    @Test
    fun linksAlwaysCarryALinkAnnotationAndNeverATagAnnotation() {
        // 只有一种给法：LinkAnnotation。tag 标注是留给指针命中测试的，
        // 而那会打死长按选词，所以它必须恒空。
        val out = buildMarkdownInline("[文字](https://a.cn)")
        assertEquals(1, linksOf(out).size)
        assertTrue(tagsOf(out).isEmpty())
    }

    /**
     * 任何输入都不得产出 tag 标注。
     *
     * stringAnnotation 唯一的作用是"自己拿它做指针命中测试"。而任何这样做的
     * pointerInput 都会 consume 按下事件，把气泡外层 SelectionContainer 的
     * 长按选词打死 —— 这正是本文件一度出现的 bug。留着这个开关等于把修好的
     * 坑重新挖开，所以从产出侧钉死。
     */
    @Test
    fun noInputEverEmitsAStringAnnotation() {
        val inputs = listOf(
            "[文字](https://a.cn)",
            "https://example.com",
            "<hi@a.cn>",
            "<https://a.cn>",
            "**粗**[链接](https://a.cn)",
            "[带标题](https://a.cn \"t\")",
            "普通一段话"
        )
        for (input in inputs) {
            val out = buildMarkdownInline(input)
            assertTrue(
                "输入 <$input> 产出了 tag 标注：" + tagsOf(out).joinToString(),
                tagsOf(out).isEmpty()
            )
        }
    }

    // ---------- 转义 ----------
    @Test
    fun escapedAsterisk_rendersLiterally() {
        val out = styled("\\*不是斜体\\*")
        assertEquals("*不是斜体*", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun escapedHash_isNotAHeading() {
        val out = styled("\\# 不是标题")
        assertEquals("# 不是标题", out.text)
    }

    @Test
    fun escapedBackslash_beforeAsterisk_keepsTheEmphasis() {
        // \\*  第一个反斜杠吃掉反斜杠，剩下一个 * 就该是强调
        val out = styled("\\\\*斜体*")
        assertTrue(out.spanStyles.isNotEmpty())
    }

    @Test
    fun escapedDollar_isNotMath() {
        val out = styled("花掉 ${dollar}100")
        assertEquals("花掉 ${dollar}100", out.text)
    }

    // ---------- 中文与 emoji ----------

    @Test
    fun chineseBold_rangeIsInCodeUnits() {
        val out = styled("中文**加粗**尾巴")
        assertEquals("中文加粗尾巴", out.text)
        assertEquals(2 to 4, out.spanStyles[0].start to out.spanStyles[0].end)
    }

    @Test
    fun fullWidthBracketsAllowEmphasisInside() {
        val out = styled("（*重点*）")
        assertEquals("（重点）", out.text)
        assertEquals(1, out.spanStyles.size)
        assertEquals(1 to 3, out.spanStyles[0].start to out.spanStyles[0].end)
    }

    @Test
    fun emojiInsideBold_coversBothSurrogateUnits() {
        val out = styled("**😀**")
        // 😀 是一个代理对（两个 UTF-16 单元），范围必须盖满两个
        assertEquals(2, out.text.length)
        assertEquals(0 to 2, out.spanStyles[0].start to out.spanStyles[0].end)
    }

    @Test
    fun emojiDoesNotBreakSpanRanges() {
        val out = styled("😀**后**")
        assertEquals("😀后", out.text)
        assertEquals(2 to 3, out.spanStyles[0].start to out.spanStyles[0].end)
    }

    // ---------- 行内公式入口 ----------

    @Test
    fun inlineMath_withoutSink_staysLiteral() {
        val out = styled("${dollar}x^2${dollar}")
        assertEquals("${dollar}x^2${dollar}", out.text)
    }

    @Test
    fun inlineMath_withSink_replacesTheDollarPair() {
        val parts = LinkedHashMap<String, MathPart>()
        val out = styled("看 ${dollar}x^2${dollar} 公式", mathSinkFor(parts))
        assertEquals("看 x2 公式", out.text)
        val shifts = out.spanStyles.filter { it.item.baselineShift != null }
        assertEquals(1, shifts.size)
        assertEquals(3 to 4, shifts[0].start to shifts[0].end)
        assertEquals(BaselineShift.Superscript, shifts[0].item.baselineShift)
    }

    @Test
    fun inlineMath_withFraction_producesAPart() {
        val parts = LinkedHashMap<String, MathPart>()
        val out = styled("${dollar}\\frac{a}{b}${dollar}", mathSinkFor(parts))
        assertEquals(1, parts.size)
        assertTrue(parts.values.first() is MathPart.Fraction)
        // 正文里留下的是可读的替代文本，不是源码
        assertEquals("a/b", out.text)
    }

    @Test
    fun dollarsWithNothingInside_stayLiteral() {
        assertEquals("${dollar}${dollar}", styled("${dollar}${dollar}").text)
        assertEquals("a ${dollar}${dollar} b", styled("a ${dollar}${dollar} b").text)
    }

    @Test
    fun unclosedMath_staysLiteral() {
        val out = styled("${dollar}没闭合的公式")
        assertEquals("${dollar}没闭合的公式", out.text)
    }

    @Test
    fun moneyAmounts_areNotMistakenForMath() {
        // 中文正文里 $ 极常见，别把「$100 和 $200」当公式
        val out = styled("预算 ${dollar}100 和 ${dollar}200")
        assertEquals("预算 ${dollar}100 和 ${dollar}200", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun displayMathReachingTheInlineLayer_staysLiteral() {
        // display 数学正常已经被 splitDisplayMath 切走；漏到这里也不能空白
        val out = styled("${dollar}${dollar}x${dollar}${dollar}")
        assertEquals("${dollar}${dollar}x${dollar}${dollar}", out.text)
    }

    // ---------- 规模 ----------

    @Test(timeout = 20_000L)
    fun tenThousandCharLine_doesNotThrow() {
        val out = styled("字".repeat(10_000))
        assertEquals(10_000, out.text.length)
    }

    @Test(timeout = 20_000L)
    fun manyAsterisks_doNotHangOrThrow() {
        val out = styled("*".repeat(500))
        assertEquals(500, out.text.length)
    }

    @Test(timeout = 20_000L)
    fun manyDollars_doNotHangOrThrow() {
        val out = styled("${dollar} ".repeat(200))
        assertEquals(400, out.text.length)
    }

    @Test(timeout = 20_000L)
    fun manyBackticks_doNotHangOrThrow() {
        val out = styled("`".repeat(300))
        assertEquals(300, out.text.length)
    }

    @Test
    fun mixedDocumentIsRenderedEndToEnd() {
        val parts = LinkedHashMap<String, MathPart>()
        val out = styled(
            "**标题**：见 [文档](https://a.cn) 的 `code`，以及 ${dollar}\\frac{1}{2}${dollar}",
            mathSinkFor(parts)
        )
        assertEquals("标题：见 文档 的 code，以及 1/2", out.text)
        assertTrue(out.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertEquals(1, urlsOf(out).size)
        assertEquals(1, parts.size)
    }
}
