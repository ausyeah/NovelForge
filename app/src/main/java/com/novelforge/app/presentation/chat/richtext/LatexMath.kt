package com.novelforge.app.presentation.chat.richtext

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.TextUnit

/**
 * LaTeX 子集渲染器（手搓，零新依赖）。
 *
 * 输出是「一段 AnnotatedString + 若干需要 InlineTextContent 画的占位部件」：
 * 分数、根号这种真要上下叠排的东西塞不进一个 Text，只能靠
 * `Text(inlineContent = ...)` 让 UI 画。正文里放的是一段替代文本加一个
 * stringAnnotation，UI 拿 tag 去查部件 —— 走的是 Compose 1.7 自带的
 * appendInlineContent 机制，不另外造轮子。
 *
 * 第一原则：**认不出来就显示原文**。空白气泡是最糟的结果 ——
 * 用户看到一片空白，根本不知道发生了什么。
 */

/** 需要 InlineTextContent 画出来的部件。 */
sealed interface MathPart {
    /**
     * 分数：上下叠排，中间一条横线。
     *
     * [widthEm] / [heightEm] 是占位框尺寸，单位 em（相对正文字号的倍数）。
     * 按分子分母的字符数估出来，只为不压到相邻文字；估不准最多是多留白。
     */
    data class Fraction(
        val numerator: MathExpression,
        val denominator: MathExpression,
        val widthEm: Float,
        val heightEm: Float
    ) : MathPart

    /** 根号。[index] 是 `\sqrt[n]{}` 里的 n，null 是普通根号。 */
    data class Radical(
        val radicand: MathExpression,
        val index: MathExpression?,
        val widthEm: Float,
        val heightEm: Float
    ) : MathPart
}

/**
 * 一段公式的渲染结果。
 *
 * [text] 里带 inlineContent 标记的那段字符不会真被画出来 —— UI 要把
 * [parts] 拼成 `inlineContent` 传进 Text。裸文本本身仍然有意义
 * （选中复制、无障碍朗读都会用到）。
 */
class MathExpression internal constructor(
    val text: AnnotatedString,
    val parts: Map<String, MathPart>,
    /** 源码里有认不出来的片段（`\foo`、坏括号…），UI 可以据此给个提示色。 */
    val degraded: Boolean,
    /** 可读的替代文本（`\frac{a}{b}` -> `a/b`）。占位的朗读/复制都用它。 */
    val altText: String
) {
    val isEmpty: Boolean get() = text.text.isBlank()
}

/**
 * 公式排版参数。UI 层负责把主题色与字号填进来；
 * [fontSize] 留空时上下标只做基线偏移、不缩字号（纯单测里就是这种情况）。
 */
data class MathStyle(
    val color: Color = Color.Unspecified,
    val fontSize: TextUnit = TextUnit.Unspecified,
    /** 上下标相对正文字号的比例。 */
    val scriptScale: Float = 0.72f,
    /** 变量是否用斜体。真公式里变量是斜体，转过来会好看很多。 */
    val variableItalic: Boolean = true
)

/** 把一段 LaTeX 源码渲染成 [MathExpression]。永远不会抛异常。 */
fun buildMathExpression(source: String, style: MathStyle = MathStyle()): MathExpression =
    MathRenderer(style).render(LatexParser(source).parseAll(), source)

/**
 * 行内数学的入口：把公式追加进已有的 [AnnotatedString.Builder]，
 * 占位部件记进 [parts]。
 *
 * 返回 false 表示「不认」（目前只有内容全空白这一种），
 * 调用方应该退回显示 `$...$` 原文。
 */
fun appendMathExpression(
    builder: AnnotatedString.Builder,
    source: String,
    style: MathStyle,
    parts: MutableMap<String, MathPart>
): Boolean {
    if (source.isBlank()) return false
    val expr = buildMathExpression(source, style)
    if (expr.isEmpty) return false
    builder.append(expr.text)
    parts.putAll(expr.parts)
    return true
}

// ---------------------------------------------------------------- $$...$$ 切分

/** 正文按 `$$...$$` display 数学切开的片段。 */
sealed interface MathSegment {
    data class Text(val text: String) : MathSegment

    /** 一段 display 公式的源码。 */
    data class Display(val source: String) : MathSegment
}

/**
 * 把 `$$...$$` 从正文里切出来（display 数学要自己占一行居中）。
 *
 * 切不动的都退回 [MathSegment.Text]：`$$` 没闭合、内容全空白（`$$\n\n$$`）
 * 这类情况源码原样留着给用户看，绝不留一个空段。
 */
fun splitDisplayMath(text: String): List<MathSegment> {
    if (!text.contains("\$\$")) return listOf(MathSegment.Text(text))
    val out = ArrayList<MathSegment>()
    var i = 0
    var plainStart = 0
    while (i + 1 < text.length) {
        if (text[i] == '$' && text[i + 1] == '$' && (i == 0 || text[i - 1] != '\\')) {
            val close = findDisplayClose(text, i + 2)
            if (close < 0) {
                // 没闭合就不猜边界：剩下的整段原样显示
                if (i > plainStart) out.add(MathSegment.Text(text.substring(plainStart, i)))
                out.add(MathSegment.Text(text.substring(i)))
                return out
            }
            val source = text.substring(i + 2, close)
            val consumedTo = close + 2
            if (i > plainStart) out.add(MathSegment.Text(text.substring(plainStart, i)))
            // 空公式（$$\n\n$$）没内容可渲染，原样显示总比留一段空白强
            if (source.isBlank()) {
                out.add(MathSegment.Text(text.substring(i, consumedTo)))
            } else {
                out.add(MathSegment.Display(source.trim()))
            }
            plainStart = consumedTo
            i = consumedTo
            continue
        }
        i++
    }
    if (plainStart < text.length) {
        val rest = text.substring(plainStart)
        if (rest.isNotEmpty()) out.add(MathSegment.Text(rest))
    }
    return out
}

private fun findDisplayClose(text: String, from: Int): Int {
    var j = from
    while (j + 1 < text.length) {
        if (text[j] == '$' && text[j + 1] == '$' && text[j - 1] != '\\') return j
        j++
    }
    return -1
}

// ---------------------------------------------------------------- 公式语法树（私有）

private sealed interface Fx {
    data class Row(val children: List<Fx>) : Fx

    /** 单个符号。[italic] 表示它是不是「变量」（真公式里变量才斜体）。 */
    data class Sym(val ch: Char, val italic: Boolean) : Fx

    /** `\text{}`：正体文字。 */
    data class TextRun(val text: String) : Fx

    data class Script(val base: Fx, val sup: Fx?, val sub: Fx?) : Fx
    data class Frac(val num: Fx, val den: Fx) : Fx
    data class Sqrt(val body: Fx, val index: Fx?) : Fx
    data class Delims(val left: String, val right: String, val body: Fx) : Fx

    /** display 数学里的 `\\` 换行。 */
    data object Break : Fx

    data object Empty : Fx

    /** 认不出来的原文，必须原样显示。 */
    data class Raw(val text: String) : Fx
}

/** 希腊字母。键是命令名（不含反斜杠）。 */
private val Greek = mapOf(
    "alpha" to 'α', "beta" to 'β', "gamma" to 'γ', "delta" to 'δ',
    "epsilon" to 'ε', "varepsilon" to 'ε', "zeta" to 'ζ', "eta" to 'η',
    "theta" to 'θ', "vartheta" to 'ϑ', "iota" to 'ι', "kappa" to 'κ',
    "lambda" to 'λ', "mu" to 'μ', "nu" to 'ν', "xi" to 'ξ',
    "pi" to 'π', "rho" to 'ρ', "sigma" to 'σ', "varsigma" to 'ς', "tau" to 'τ',
    "upsilon" to 'υ', "phi" to 'φ', "varphi" to 'ϕ', "chi" to 'χ',
    "psi" to 'ψ', "omega" to 'ω',
    "Gamma" to 'Γ', "Delta" to 'Δ', "Theta" to 'Θ', "Lambda" to 'Λ',
    "Xi" to 'Ξ', "Pi" to 'Π', "Sigma" to 'Σ', "Upsilon" to 'Υ',
    "Phi" to 'Φ', "Psi" to 'Ψ', "Omega" to 'Ω'
)

/** 运算符与其它符号。 */
private val Symbols = mapOf(
    "times" to '×', "div" to '÷', "cdot" to '·', "pm" to '±',
    "mp" to '∓', "leq" to '≤', "le" to '≤', "geq" to '≥', "ge" to '≥',
    "neq" to '≠', "ne" to '≠', "approx" to '≈', "equiv" to '≡',
    "sim" to '∼', "propto" to '∝', "ll" to '≪', "gg" to '≫',
    "to" to '→', "rightarrow" to '→', "leftarrow" to '←', "gets" to '←',
    "Rightarrow" to '⇒', "Leftarrow" to '⇐', "leftrightarrow" to '↔',
    "mapsto" to '↦', "infty" to '∞', "partial" to '∂', "nabla" to '∇',
    "forall" to '∀', "exists" to '∃', "in" to '∈', "notin" to '∉',
    "subset" to '⊂', "supset" to '⊃', "subseteq" to '⊆', "supseteq" to '⊇',
    "cup" to '∪', "cap" to '∩', "emptyset" to '∅', "varnothing" to '∅',
    "land" to '∧', "lor" to '∨', "neg" to '¬', "lnot" to '¬',
    "ldots" to '…', "dots" to '…', "cdots" to '⋯', "vdots" to '⋮',
    "angle" to '∠', "perp" to '⊥', "parallel" to '∥', "therefore" to '∴',
    "prime" to '′', "degree" to '°', "circ" to '∘', "bullet" to '•',
    "oplus" to '⊕', "otimes" to '⊗', "aleph" to 'ℵ', "hbar" to 'ℏ',
    "ell" to 'ℓ', "wp" to '℘', "Re" to 'ℜ', "Im" to 'ℑ'
)

/** 带上下限的大算符：`\sum_{i=1}^{n}` 的上下标要画在符号旁边。 */
private val BigOperators = mapOf(
    "sum" to '∑', "prod" to '∏', "int" to '∫', "iint" to '∬',
    "iiint" to '∭', "oint" to '∮', "bigcup" to '⋃', "bigcap" to '⋂',
    "bigoplus" to '⨁', "coprod" to '∐'
)

/** `\lim` 这类多字母算符，按正体文字渲染。 */
private val WordOperators = mapOf("lim" to "lim", "max" to "max", "min" to "min", "log" to "log")

/** `\left(` 后面的定界符名。 */
private val Delimiters = mapOf(
    "langle" to "⟨", "rangle" to "⟩", "lfloor" to "⌊", "rfloor" to "⌋",
    "lceil" to "⌈", "rceil" to "⌉", "lbrace" to "{", "rbrace" to "}",
    "vert" to "|", "Vert" to "‖", "backslash" to "\\", "lvert" to "|",
    "rvert" to "|", "uparrow" to "↑", "downarrow" to "↓"
)

// ---------------------------------------------------------------- 解析

private class LatexParser(private val source: String) {
    private var i = 0

    fun parseAll(): Fx = Fx.Row(parseUntil(null))

    /**
     * 读到 [stop] 为止（并吃掉它）。stop == null 表示读到结尾。
     *
     * [keepSpaces] 只给 `\text{}` 用：数学模式下空格是要忽略的，
     * 但 `\text{第一章 开始}` 里的空格是正文的一部分，不能吞。
     *
     * 没等到闭合括号也不抛异常：里面已经解析出的内容照常保留，
     * 缺的右括号用户看不出来，总比整段变空白强。
     */
    private fun parseUntil(stop: Char?, keepSpaces: Boolean = false): List<Fx> {
        val out = ArrayList<Fx>()
        while (i < source.length) {
            val c = source[i]
            if (stop != null && c == stop) {
                i++
                return out
            }
            val atom = parseAtom(keepSpaces)
            if (atom != null) out.add(attachScripts(atom))
        }
        return out
    }

    private fun parseAtom(keepSpaces: Boolean = false): Fx? {
        val c = source.getOrNull(i) ?: return null
        return when {
            c == '{' -> {
                i++
                Fx.Row(parseUntil('}', keepSpaces))
            }

            // 落单的右括号/右方括号：原样留着
            c == '}' || c == ']' -> Fx.Raw(c.toString()).also { i++ }

            c == '\\' -> parseCommand(keepSpaces)

            // 落单的 ^ / _ ：原样留着
            c == '^' || c == '_' -> Fx.Raw(c.toString()).also { i++ }

            // & 是对齐标记、~ 是窄空格，都不该画出来
            c == '&' || c == '~' -> Fx.Empty.also { i++ }

            c.isWhitespace() -> {
                if (keepSpaces) Fx.Sym(' ', false).also { i++ } else Fx.Empty.also { i++ }
            }

            else -> Fx.Sym(c, italic = c.isLetter()).also { i++ }
        }
    }

    private fun parseCommand(keepSpaces: Boolean = false): Fx {
        i++ // 吃掉反斜杠
        val c = source.getOrNull(i) ?: return Fx.Raw("\\")
        if (!c.isLetter()) {
            i++
            return when (c) {
                '{' -> Fx.Sym('{', false)
                '}' -> Fx.Sym('}', false)
                '|' -> Fx.Sym('|', false)
                '%' -> Fx.Sym('%', false)
                '$' -> Fx.Sym('$', false)
                '&' -> Fx.Sym('&', false)
                '#' -> Fx.Sym('#', false)
                '_' -> Fx.Sym('_', false)
                '^' -> Fx.Sym('^', false)
                // display 数学里的换行
                '\\' -> Fx.Break.also { i++ }
                // \, \; \! \space 都是调间距的，间距做不了就当一个空格
                ',', ';', '!', ' ' -> Fx.Sym(' ', false)
                else -> Fx.Raw("\\$c")
            }
        }

        var j = i
        while (j < source.length && source[j].isLetter()) j++
        val name = source.substring(i, j)
        i = j

        return when (name) {
            "frac", "dfrac", "tfrac", "cfrac" -> {
                val num = readGroup()
                val den = readGroup()
                if (num == null || den == null) Fx.Raw("\\$name") else Fx.Frac(num, den)
            }

            "sqrt" -> {
                val index = if (source.getOrNull(i) == '[') readBracketGroup() else null
                val body = readGroup()
                if (body == null) Fx.Raw("\\sqrt") else Fx.Sqrt(body, index)
            }

            "text", "textrm", "mathrm", "operatorname", "mathsf", "mbox", "textbf",
            "mathbf", "bm", "boldsymbol", "mathit", "textit" -> {
                // 字体差异交给 UI 不好做：\text{x} 和 \mathbf{x} 都按内容渲染，
                // 前者强制正体，后者保留变量斜体
                val upright = name == "text" || name == "textrm" || name == "mathrm" ||
                    name == "operatorname" || name == "mbox"
                val g = readGroup(keepSpaces = upright) ?: Fx.Empty
                if (upright) Fx.TextRun(plain(g)) else g
            }

            "left" -> parseLeft()

            // 落单的 \right、以及不支持的环境（\begin{aligned} 等）：
            // 至少把命令本身原样露出来，别静默吞掉
            "right", "begin", "end" -> Fx.Raw("\\$name")

            else -> {
                val big = BigOperators[name]
                if (big != null) {
                    Fx.Sym(big, italic = false)
                } else {
                    val word = WordOperators[name]
                    if (word != null) {
                        Fx.TextRun(word)
                    } else {
                        val greek = Greek[name]
                        if (greek != null) {
                            Fx.Sym(greek, italic = true)
                        } else {
                            val symbol = Symbols[name]
                            if (symbol != null) {
                                Fx.Sym(symbol, italic = false)
                            } else {
                                Fx.Raw("\\$name")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun parseLeft(): Fx {
        val left = readDelimiter()
        val body = ArrayList<Fx>()
        // body 停在 \right 之前。嵌套的 \left 走 parseCommand -> parseLeft 自己处理，
        // 内层先遇到 \right 就先收工，配平的写法能还原对
        while (i < source.length && !source.startsWith("\\right", i)) {
            val atom = parseAtom()
            if (atom != null) body.add(attachScripts(atom))
        }
        var missingRight = false
        val right = if (source.startsWith("\\right", i)) {
            i += "\\right".length
            readDelimiter()
        } else {
            // 缺 \right：只显示左括号，右边已经读到的内容一律不吞
            missingRight = true
            ""
        }
        val result = Fx.Delims(left, right, Fx.Row(body))
        // 降级标记靠这个空 Raw 传上去，渲染时它什么都不输出
        return if (missingRight) Fx.Row(listOf(result, Fx.Raw(""))) else result
    }

    /** `\left` / `\right` 后面的定界符。 */
    private fun readDelimiter(): String {
        if (source.getOrNull(i) == '.') {
            i++
            return ""
        }
        if (source.getOrNull(i) == '\\') {
            i++
            val c = source.getOrNull(i)
            if (c != null && !c.isLetter()) {
                i++
                return when (c) {
                    '{' -> "{"
                    '}' -> "}"
                    else -> c.toString()
                }
            }
            var j = i
            while (j < source.length && source[j].isLetter()) j++
            if (j > i) {
                val name = source.substring(i, j)
                i = j
                return Delimiters[name] ?: ""
            }
            return ""
        }
        val c = source.getOrNull(i) ?: return ""
        i++
        return c.toString()
    }

    /**
     * `\frac` 的参数：`{组}` 或单个 token。取不到返回 null。
     *
     * 前面跳过的空白是 LaTeX 的规矩：`\frac ab` 的分子是 a、分母是 b，
     * 那个空格只是分隔命令名和参数，不能被当成一个空白 token。
     */
    private fun readGroup(keepSpaces: Boolean = false): Fx? {
        while (i < source.length && source[i].isWhitespace()) i++
        val c = source.getOrNull(i) ?: return null
        if (c == '{') {
            i++
            return Fx.Row(parseUntil('}', keepSpaces))
        }
        if (c == '\\') return parseCommand(keepSpaces)
        i++
        return Fx.Sym(c, italic = c.isLetter())
    }

    private fun readBracketGroup(): Fx? {
        if (source.getOrNull(i) != '[') return null
        i++
        val items = ArrayList<Fx>()
        while (i < source.length && source[i] != ']') {
            val atom = parseAtom()
            if (atom != null) items.add(attachScripts(atom))
        }
        if (i < source.length) i++
        return Fx.Row(items)
    }

    /** 把紧跟基数的 `^` / `_` 收进 [Fx.Script]，两种顺序都认。 */
    private fun attachScripts(base: Fx): Fx {
        var sup: Fx? = null
        var sub: Fx? = null
        while (true) {
            when (source.getOrNull(i)) {
                '^' -> {
                    if (sup != null) break
                    i++
                    sup = readGroup() ?: Fx.Raw("^")
                }

                '_' -> {
                    if (sub != null) break
                    i++
                    sub = readGroup() ?: Fx.Raw("_")
                }

                else -> break
            }
        }
        return if (sup != null || sub != null) Fx.Script(base, sup, sub) else base
    }
}

/**
 * 公式树 -> 可读纯文本。`\frac{a}{b}` 压成 `a/b`。
 *
 * 两个用处：`\text{}` 直接用；占位符的替代文本用这个，
 * 这样「选中复制」拿到的是能读的公式，而不是 `\frac` 这种源码。
 */
private fun plain(fx: Fx): String = when (fx) {
    is Fx.Row -> fx.children.joinToString("") { plain(it) }
    is Fx.Sym -> fx.ch.toString()
    is Fx.TextRun -> fx.text
    is Fx.Raw -> fx.text
    is Fx.Script -> buildString {
        append(plain(fx.base))
        fx.sub?.let { append('_').append(plain(it)) }
        fx.sup?.let { append('^').append(plain(it)) }
    }

    is Fx.Frac -> "${plain(fx.num)}/${plain(fx.den)}"
    is Fx.Sqrt -> "√${plain(fx.body)}"
    is Fx.Delims -> fx.left + plain(fx.body) + fx.right
    Fx.Break -> " "
    Fx.Empty -> ""
}

// ---------------------------------------------------------------- 渲染

/** 占位 tag 前缀。一段公式内部 tag 唯一即可，外层用 Map 查。 */
private const val PART_TAG_PREFIX = "nf-math-"

private class MathRenderer(private val style: MathStyle) {
    private val parts = LinkedHashMap<String, MathPart>()
    private var degraded = false

    fun render(fx: Fx, altText: String): MathExpression {
        val builder = AnnotatedString.Builder()
        write(builder, fx)
        return MathExpression(builder.toAnnotatedString(), LinkedHashMap(parts), degraded, altText)
    }

    private fun write(builder: AnnotatedString.Builder, fx: Fx) {
        when (fx) {
            is Fx.Row -> fx.children.forEach { write(builder, it) }

            is Fx.Sym -> {
                if (style.variableItalic && fx.italic) {
                    builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    builder.append(fx.ch)
                    builder.pop()
                } else {
                    builder.append(fx.ch)
                }
            }

            is Fx.TextRun -> {
                // \text{} 是正体，不能继承变量那个斜体
                builder.pushStyle(SpanStyle(fontStyle = FontStyle.Normal))
                builder.append(fx.text)
                builder.pop()
            }

            is Fx.Raw -> {
                degraded = true
                builder.append(fx.text)
            }

            is Fx.Script -> {
                write(builder, fx.base)
                fx.sub?.let { writeScript(builder, it, subscript = true) }
                fx.sup?.let { writeScript(builder, it, subscript = false) }
            }

            is Fx.Frac -> {
                val numerator = sub(fx.num)
                val denominator = sub(fx.den)
                val longest = maxOf(numerator.text.text.length, denominator.text.text.length)
                val tag = nextTag()
                parts[tag] = MathPart.Fraction(
                    numerator = numerator,
                    denominator = denominator,
                    // 0.55em/字 是正文字宽的粗略值，再加 1.1em 边距
                    widthEm = (0.55f * longest + 1.1f).coerceIn(1.0f, 8.0f),
                    heightEm = 2.4f
                )
                builder.appendInlineContent(tag, plain(fx))
            }

            is Fx.Sqrt -> {
                val radicand = sub(fx.body)
                val index = fx.index?.let { sub(it) }
                val tag = nextTag()
                parts[tag] = MathPart.Radical(
                    radicand = radicand,
                    index = index,
                    widthEm = (0.55f * (radicand.text.text.length + 2) + 0.9f)
                        .coerceIn(1.2f, 14.0f),
                    heightEm = 1.7f
                )
                builder.appendInlineContent(tag, plain(fx))
            }

            is Fx.Delims -> {
                builder.append(fx.left)
                write(builder, fx.body)
                builder.append(fx.right)
            }

            Fx.Break -> builder.append("\n")
            Fx.Empty -> Unit
        }
    }

    /** 子公式单独一份 parts（UI 里是独立的一个 Text），降级标记要往上带。 */
    private fun sub(fx: Fx): MathExpression {
        val renderer = MathRenderer(style)
        val expr = renderer.render(fx, plain(fx))
        if (expr.degraded) degraded = true
        return expr
    }

    private fun writeScript(builder: AnnotatedString.Builder, fx: Fx, subscript: Boolean) {
        val span = SpanStyle(
            baselineShift = if (subscript) BaselineShift.Subscript else BaselineShift.Superscript,
            // 字号没给定时只做基线偏移：算不出「小一号」是多少
            fontSize = if (style.fontSize == TextUnit.Unspecified) {
                TextUnit.Unspecified
            } else {
                style.fontSize * style.scriptScale
            }
        )
        builder.pushStyle(span)
        write(builder, fx)
        builder.pop()
    }

    private fun nextTag(): String = PART_TAG_PREFIX + parts.size
}
