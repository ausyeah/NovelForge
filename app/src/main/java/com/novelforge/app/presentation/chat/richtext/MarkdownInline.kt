package com.novelforge.app.presentation.chat.richtext

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * 行内 markdown：粗体/斜体/删除线/行内代码/链接/转义/行内公式入口。
 *
 * 核心约束是「配不上的标记必须原样显示」：`2 * 3 * 4` 不是斜体，
 * `**没闭合` 就得显示成 `**没闭合`。靠的是 CommonMark 那套
 * left/right-flanking 规则 —— 光找配对符号的话 `2 * 3 * 4` 会被吃掉。
 */

/**
 * 行内公式的接入点。
 *
 * MarkdownInline 扫到 `$` 时回调，命中就在 builder 里把公式（含占位符）
 * 追加进去并返回 true；返回 false 表示不认，`$` 当普通字符处理。
 * 拆成接口而不是让行内层直接依赖公式实现，是为了两个解析器能各自单测。
 */
fun interface MathInlineSink {
    fun append(builder: AnnotatedString.Builder, source: String): Boolean
}

/** 行内语法树。保留成树是为了能处理嵌套（`**粗体里的 *斜体*`**）。 */
sealed interface InlineNode {
    data class Text(val text: String) : InlineNode

    /** 带样式的子树。 */
    data class Styled(val style: SpanStyle, val children: List<InlineNode>) : InlineNode

    /** 行内代码：内容原样，不做任何二次解析。 */
    data class Code(val text: String) : InlineNode

    data class Link(val label: List<InlineNode>, val url: String) : InlineNode

    /** `$...$` 的源码，交给 [MathInlineSink] 渲染。 */
    data class Math(val source: String) : InlineNode
}

/** 行内渲染参数。默认全 Unspecified，方便测试里不关心颜色。 */
data class InlineStyleOptions(
    val bold: SpanStyle = SpanStyle(fontWeight = FontWeight.Bold),
    val italic: SpanStyle = SpanStyle(fontStyle = FontStyle.Italic),
    val strikethrough: SpanStyle = SpanStyle(textDecoration = TextDecoration.LineThrough),
    val code: SpanStyle = SpanStyle(fontFamily = FontFamily.Monospace),
    val codeBackground: Color = Color.Unspecified,
    val link: SpanStyle = SpanStyle(textDecoration = TextDecoration.Underline)
)

/**
 * 把一段行内 markdown 渲染成 [AnnotatedString]。
 *
 * @param mathSink 行内公式渲染器；为 null 时 `$x$` 原样输出。
 */
fun buildMarkdownInline(
    text: String,
    mathSink: MathInlineSink? = null,
    options: InlineStyleOptions = InlineStyleOptions()
): AnnotatedString {
    val builder = AnnotatedString.Builder(text.length + 16)
    builder.appendNodes(parseInlineMarkdown(text), options, mathSink)
    return builder.toAnnotatedString()
}

/** 解析行内语法（供单测断言结构用）。 */
fun parseInlineMarkdown(text: String): List<InlineNode> = parseInline(text)

// ---------------------------------------------------------------- 展开成 AnnotatedString

private fun AnnotatedString.Builder.appendNodes(
    nodes: List<InlineNode>,
    options: InlineStyleOptions,
    mathSink: MathInlineSink?
) {
    for (node in nodes) {
        when (node) {
            is InlineNode.Text -> append(node.text)

            is InlineNode.Code -> {
                val start = length
                pushStyle(options.code)
                append(node.text)
                pop()
                if (options.codeBackground != Color.Unspecified) {
                    addStyle(SpanStyle(background = options.codeBackground), start, length)
                }
            }

            is InlineNode.Styled -> {
                pushStyle(node.style)
                appendNodes(node.children, options, mathSink)
                pop()
            }

            is InlineNode.Link -> {
                pushStyle(options.link)
                // 只挂 LinkAnnotation：Text 自己的命中测试会把它交给
                // LocalUriHandler，朗读也会念"链接"。
                // 不要再 pushStringAnnotation(URL_TAG) —— 那是给指针命中测试用的，
                // 而指针命中测试会 down.consume()，直接把外层 SelectionContainer
                // 的长按选词饿死。留着它还会让人以为那条老路还在跑。
                pushLink(LinkAnnotation.Url(node.url, TextLinkStyles(style = options.link)))
                appendNodes(node.label, options, mathSink)
                pop()
                pop()
            }

            is InlineNode.Math -> {
                // 公式渲染器不认（内容为空等）就退回原文：宁可显示 $x$，也不能空白
                if (mathSink == null || !mathSink.append(this, node.source)) {
                    append('$')
                    append(node.source)
                    append('$')
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 扫描

/** 一个识别出来的片段：节点 + 它在源串里结束的下标。 */
private class Span(val node: InlineNode, val end: Int)

private fun isWs(c: Char?): Boolean = c == null || c.isWhitespace()

/**
 * 标点判定。CJK 标点（，。：）也算标点 —— 它们在 flanking 规则里
 * 必须和 ASCII 标点一个待遇，否则「（*重点*）」这类中文排版会散架。
 * CJK 汉字是字母（isLetterOrDigit 为真），所以不算标点。
 */
private fun isPunct(c: Char?): Boolean {
    if (c == null) return false
    return !c.isLetterOrDigit() && !c.isWhitespace()
}

private fun leftFlanking(before: Char?, after: Char?): Boolean =
    !isWs(after) && (!isPunct(after) || isWs(before) || isPunct(before))

private fun rightFlanking(before: Char?, after: Char?): Boolean =
    !isWs(before) && (!isPunct(before) || isWs(after) || isPunct(after))

/** `_` 比 `*` 严：不然 snake_case_name 会被拆成斜体。 */
private fun canOpenEmphasis(marker: Char, before: Char?, after: Char?): Boolean {
    val left = leftFlanking(before, after)
    if (marker == '_') return left && (!rightFlanking(before, after) || isPunct(before))
    return left
}

private fun canCloseEmphasis(marker: Char, before: Char?, after: Char?): Boolean {
    val right = rightFlanking(before, after)
    if (marker == '_') return right && (!leftFlanking(before, after) || isPunct(after))
    return right
}

/** ASCII 标点可以反斜杠转义。中文标点不行（`\。` 会原样显示）。 */
private fun isEscapable(c: Char): Boolean =
    c in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

private fun parseInline(text: String): List<InlineNode> {
    val out = ArrayList<InlineNode>()
    val lit = StringBuilder()
    var i = 0

    fun flush() {
        if (lit.isNotEmpty()) {
            out.add(InlineNode.Text(lit.toString()))
            lit.setLength(0)
        }
    }

    while (i < text.length) {
        val c = text[i]
        // 每条分支都必须 i 前移（要么自己移，要么靠 span.end）。
        // 漏掉一次就是死循环 —— 单测里的「配不上的标记」那一组就是守这个的。
        val span: Span? = when {
            // 转义优先于一切：\* 就该是个星号，不能当强调标记
            c == '\\' && i + 1 < text.length && isEscapable(text[i + 1]) -> {
                lit.append(text[i + 1])
                i += 2
                null
            }

            c == '`' -> {
                val found = codeSpanAt(text, i)
                if (found == null) {
                    lit.append('`')
                    i++
                    null
                } else {
                    Span(InlineNode.Code(found.text), found.end)
                }
            }

            c == '*' || c == '_' -> {
                val found = emphasisAt(text, i)
                if (found == null) {
                    lit.append(c)
                    i++
                    null
                } else {
                    found
                }
            }

            c == '~' -> {
                val found = strikethroughAt(text, i)
                if (found == null) {
                    lit.append(c)
                    i++
                    null
                } else {
                    found
                }
            }

            c == '$' -> {
                val found = dollarAt(text, i)
                if (found == null) {
                    // $100、$200 这种金额里的 $：当普通字符
                    lit.append('$')
                    i++
                    null
                } else {
                    found
                }
            }

            c == '[' -> {
                val found = linkAt(text, i)
                if (found == null) {
                    // 配不上的方括号原样留着：宁可多一个括号，也不吞内容
                    lit.append('[')
                    i++
                    null
                } else {
                    found
                }
            }

            c == '<' -> {
                val found = autolinkAt(text, i)
                if (found == null) {
                    lit.append('<')
                    i++
                    null
                } else {
                    found
                }
            }

            c == 'h' || c == 'H' || c == 'w' || c == 'W' -> {
                val found = bareUrlAt(text, i)
                if (found == null) {
                    lit.append(c)
                    i++
                    null
                } else {
                    found
                }
            }

            else -> {
                lit.append(c)
                i++
                null
            }
        }
        if (span != null) {
            flush()
            out.add(span.node)
            i = span.end
        }
    }
    flush()
    return out
}

// ---------------------------------------------------------------- 各类行内结构

/**
 * 链接目标里的标题写法：`url "title"` / `url 'title'` / `url (title)`。
 *
 * 提在顶层，不是为了好看：`kotlin.text.Regex` 的构造会把 pattern **立刻**
 * `Pattern.compile` 一遍。写在 [extractUrl] 里就是「每个链接付一次编译」，
 * 而流式输出里链接是随增量反复重解析的。MarkdownBlocks 里的 `DelimiterCell`
 * 是同一个道理。
 *
 * 实测：带一个 `[x](url)` 的段落 `buildMarkdownInline` 从 0.031ms 降到
 * 0.018ms，一次调用省 0.2~2.3µs（取决于机器负载）。钱很小，但白付的，
 * 而且改动不改变任何输出。真正的结论是：**行内层整体都不是流式卡顿的来源**
 * （尾块 0.012ms，帧预算 50ms），见 StreamingParseBenchmarkTest。
 */
private val TitledLinkTarget = Regex("""^(\S*?)\s+(?:"[^"]*"|'[^']*'|\([^)]*\))$""")

/** `<a@b.com>`：见 [TitledLinkTarget]，同样只编译一次。 */
private val EmailAutolink = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

/** `<scheme:...>`。 */
private val SchemeAutolink = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")

/** 找闭合标记；返回 [start, end) 区间。扫的时候要跳过转义和代码块。 */
private fun findCloser(text: String, from: Int, marker: Char, need: Int): Pair<Int, Int>? {
    var j = from
    while (j < text.length) {
        val c = text[j]
        if (c == '\\' && j + 1 < text.length && isEscapable(text[j + 1])) {
            j += 2
            continue
        }
        if (c == '`') {
            val span = codeSpanAt(text, j)
            if (span != null) {
                j = span.end
                continue
            }
            j++
            continue
        }
        if (c == marker) {
            var e = j
            while (e < text.length && text[e] == marker) e++
            val before = if (j > 0) text[j - 1] else null
            val after = if (e < text.length) text[e] else null
            if (e - j >= need && canCloseEmphasis(marker, before, after)) return j to e
            j = e
            continue
        }
        j++
    }
    return null
}

private fun emphasisAt(text: String, at: Int): Span? {
    val marker = text[at]
    var runEnd = at
    while (runEnd < text.length && text[runEnd] == marker) runEnd++
    val run = runEnd - at
    val before = if (at > 0) text[at - 1] else null
    val after = if (runEnd < text.length) text[runEnd] else null
    if (!canOpenEmphasis(marker, before, after)) return null

    // 从最长的标记往下试：***粗斜*** 要先试 3 个，
    // 而 **粗体里 *斜体*** 只能试 2 个 —— 顺序反了内层斜体就会被吃掉
    for (use in minOf(run, 3) downTo 1) {
        val close = findCloser(text, runEnd, marker, use) ?: continue
        val inner = parseInline(text.substring(runEnd, close.first))
        val style = when (use) {
            3 -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
            2 -> SpanStyle(fontWeight = FontWeight.Bold)
            else -> SpanStyle(fontStyle = FontStyle.Italic)
        }
        // 只吃掉 use 个闭合标记，多出来的留给外层，避免吞字符
        return Span(InlineNode.Styled(style, inner), close.first + use)
    }
    return null
}

private fun strikethroughAt(text: String, at: Int): Span? {
    var runEnd = at
    while (runEnd < text.length && text[runEnd] == '~') runEnd++
    if (runEnd - at < 2) return null
    val before = if (at > 0) text[at - 1] else null
    val after = if (runEnd < text.length) text[runEnd] else null
    if (!canOpenEmphasis('~', before, after)) return null
    val close = findCloser(text, runEnd, '~', 2) ?: return null
    val inner = parseInline(text.substring(runEnd, close.first))
    return Span(
        InlineNode.Styled(SpanStyle(textDecoration = TextDecoration.LineThrough), inner),
        close.first + 2
    )
}

private class CodeSpan(val text: String, val end: Int)

/** `` `code` ``。反引号数量必须配平；配不上就当普通字符。 */
private fun codeSpanAt(text: String, at: Int): CodeSpan? {
    var openEnd = at
    while (openEnd < text.length && text[openEnd] == '`') openEnd++
    val n = openEnd - at
    var j = openEnd
    while (j < text.length) {
        val c = text[j]
        if (c == '\n') return null
        if (c == '`') {
            var e = j
            while (e < text.length && text[e] == '`') e++
            if (e - j == n) {
                var body = text.substring(openEnd, j)
                // CommonMark：首尾都带空格且不全是空格时，各去掉一个
                if (body.length >= 2 && body.first() == ' ' && body.last() == ' ' &&
                    body.any { !it.isWhitespace() }
                ) {
                    body = body.substring(1, body.length - 1)
                }
                return CodeSpan(body, e)
            }
            j = e
            continue
        }
        j++
    }
    return null
}

/** `$...$`。找不到就返回 null，交给调用方把 `$` 当普通字符。 */
private fun dollarAt(text: String, at: Int): Span? {
    // $$ 是 display 数学，正常路径已经在上层切走了。这里碰到就整段按原文输出，
    // 免得两个 `$` 被配成一对空公式
    if (text.getOrNull(at + 1) == '$') {
        var j = at + 2
        while (j < text.length) {
            if (text[j] == '$' && text.getOrNull(j + 1) == '$') {
                return Span(InlineNode.Text(text.substring(at, j + 2)), j + 2)
            }
            j++
        }
        return Span(InlineNode.Text(text.substring(at)), text.length)
    }
    val end = findInlineMathEnd(text, at)
    if (end < 0) return null
    return Span(InlineNode.Math(text.substring(at + 1, end)), end + 1)
}

/**
 * `$...$` 的闭合位置。
 *
 * 开头的 `$` 后面不能是空白（否则「$100 和 $200」这种中文金额会被当公式），
 * 闭合的 `$` 前面不能是空白，内容不能为空，也不能跨行。
 */
private fun findInlineMathEnd(text: String, open: Int): Int {
    if (open + 1 >= text.length) return -1
    if (text[open + 1].isWhitespace() || text[open + 1] == '$') return -1
    var j = open + 1
    while (j < text.length) {
        val c = text[j]
        if (c == '\n') return -1
        if (c == '\\' && j + 1 < text.length && isEscapable(text[j + 1])) {
            j += 2
            continue
        }
        if (c == '$') {
            if (text.getOrNull(j + 1) == '$') {
                j += 2
                continue
            }
            if (j - 1 < open + 1 || text[j - 1].isWhitespace()) {
                j++
                continue
            }
            return j
        }
        j++
    }
    return -1
}

/** `[文字](url "标题")`。配不上的方括号原样保留。 */
private fun linkAt(text: String, at: Int): Span? {
    var depth = 0
    var j = at
    var found = -1
    while (j < text.length) {
        val c = text[j]
        if (c == '\\' && j + 1 < text.length && isEscapable(text[j + 1])) {
            j += 2
            continue
        }
        if (c == '\n') return null
        if (c == '`') {
            val span = codeSpanAt(text, j)
            if (span != null) {
                j = span.end
                continue
            }
        }
        if (c == '[') depth++
        if (c == ']') {
            depth--
            if (depth == 0) {
                found = j
                break
            }
        }
        j++
    }
    if (found < 0) return null
    if (text.getOrNull(found + 1) != '(') return null

    var k = found + 2
    var parens = 0
    while (k < text.length) {
        val c = text[k]
        if (c == '\\' && k + 1 < text.length && isEscapable(text[k + 1])) {
            k += 2
            continue
        }
        if (c == '\n') return null
        if (c == '(') parens++
        if (c == ')') {
            if (parens == 0) break
            parens--
        }
        k++
    }
    if (k >= text.length) return null

    // 空地址不算链接：宁可显示成普通文字
    val url = extractUrl(text.substring(found + 2, k).trim())
    if (url.isEmpty() || url.any { it.isWhitespace() }) return null
    return Span(InlineNode.Link(parseInline(text.substring(at + 1, found)), url), k + 1)
}

private fun extractUrl(dest: String): String {
    if (dest.isEmpty()) return ""
    val titled = TitledLinkTarget.find(dest)
    return if (titled != null) titled.groupValues[1] else dest
}

/** `<https://x.com>` 与 `<a@b.com>`。其它尖括号内容原样保留。 */
private fun autolinkAt(text: String, at: Int): Span? {
    val close = text.indexOf('>', at + 1)
    if (close < 0) return null
    val nl = text.indexOf('\n', at)
    if (nl in 0 until close) return null
    val inner = text.substring(at + 1, close)
    if (inner.isEmpty() || inner.any { it.isWhitespace() }) return null

    val email = EmailAutolink.matches(inner)
    val scheme = SchemeAutolink.containsMatchIn(inner)
    if (!email && !scheme) return null
    val url = if (email) "mailto:$inner" else inner
    return Span(InlineNode.Link(listOf(InlineNode.Text(inner)), url), close + 1)
}

/** 裸链接：`https://x.com`、`www.x.com`。 */
private fun bareUrlAt(text: String, at: Int): Span? {
    if (at > 0 && (text[at - 1].isLetterOrDigit() || text[at - 1] == '_')) return null
    val scheme = when {
        text.startsWith("https://", at) -> "https://"
        text.startsWith("http://", at) -> "http://"
        text.startsWith("ftp://", at) -> "ftp://"
        text.startsWith("www.", at) -> "https://www."
        else -> return null
    }
    var e = at
    while (e < text.length) {
        val c = text[e]
        if (c.isWhitespace() || c == '<' || c == '"' || c == '、') break
        e++
    }
    // 句末标点不算 URL 的一部分
    while (e > at && text[e - 1] in ".,;:!?。，；：！？、") e--
    // 收尾的 ) 只在没有配对左括号时才算外壳
    if (e > at && text[e - 1] == ')' && !text.substring(at, e).contains('(')) e--
    if (e <= at) return null
    val raw = text.substring(at, e)
    val url = if (raw.startsWith("www.")) "https://$raw" else raw
    return Span(InlineNode.Link(listOf(InlineNode.Text(raw)), url), e)
}
