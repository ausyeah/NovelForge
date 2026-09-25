package com.novelforge.app.presentation.chat.richtext

/**
 * 聊天气泡里的富文本块模型 + 块级 markdown 解析。
 *
 * 这个文件是纯 Kotlin，不碰任何 Compose 类型：解析结果是数据，
 * 渲染留给 ChatRichText。这么切是因为「解析对不对」是这里唯一
 * 能被单测证明的东西 —— 没有真机，样式好不好看只能靠肉眼，
 * 但「表格少了一列」「代码块把内容吞了」这种问题必须靠测试钉死。
 *
 * 硬性原则：任何畸形输入都不能抛异常。LLM 输出的 markdown 质量
 * 不可控，解析器一旦抛异常，整个气泡就没了 —— 用户看到的是空白，
 * 比看到一堆裸的 `|` 糟糕得多。
 */

/** 表格列对齐。DEFAULT 表示源码没写冒号，按左对齐渲染。 */
enum class ColumnAlign { DEFAULT, START, CENTER, END }

/** 块级模型。用 sealed interface 让渲染层能写出穷尽的 when。 */
sealed interface MarkdownBlock {

    /** `#`..`######`。level 已裁到 1..6。 */
    data class Heading(val level: Int, val text: String) : MarkdownBlock

    data class Paragraph(val text: String) : MarkdownBlock

    /**
     * 围栏代码块。
     *
     * [code] 逐字符保真：空行、缩进都在（只剥掉围栏自身的缩进）。
     * [unterminated] 为 true 说明模型只吐了 ``` 没收尾 —— 这时代码
     * 照样要显示给用户看，不能因为缺半个围栏就把内容丢了。
     */
    data class CodeBlock(
        val language: String?,
        val code: String,
        val unterminated: Boolean
    ) : MarkdownBlock

    /**
     * 表格。所有行都已经被「补齐」到 [columnCount] 列：
     * 少列的补空串，多列的折进最后一格（见 parseMarkdown 的说明）。
     */
    data class TableBlock(
        val header: List<String>,
        val aligns: List<ColumnAlign>,
        val rows: List<List<String>>
    ) : MarkdownBlock {
        val columnCount: Int get() = header.size
    }

    /** 有序/无序列表。[start] 是有序列表的首个序号（LLM 经常从 3. 开始）。 */
    data class ListBlock(
        val ordered: Boolean,
        val start: Int,
        val items: List<ListItem>
    ) : MarkdownBlock

    /** 引用块。[blocks] 是把 `> ` 剥掉后递归解析的结果，所以能嵌套。 */
    data class Blockquote(val blocks: List<MarkdownBlock>) : MarkdownBlock

    /** `---` / `***` / `___` */
    data object ThematicBreak : MarkdownBlock
}

/**
 * 列表项。[blocks] 是项内正文（缩进的续行、嵌套子列表都在里面）。
 *
 * [checked] 为 null 表示这不是任务项；true/false 分别是 `- [x]` / `- [ ]`。
 */
data class ListItem(
    val checked: Boolean?,
    val blocks: List<MarkdownBlock>
) {
    val isTask: Boolean get() = checked != null
}

/** 嵌套深度上限。防的是病态输入（几百层缩进的列表）把栈打爆。 */
private const val MAX_NEST_DEPTH = 12

/**
 * 表格列数上限。
 *
 * 病态输入里出现过整行都是 `| | | | ...` 的行，逐格建列会造出几千列
 * 的表 —— 解析不炸，但渲染几千个 Text 会把主线程卡死。
 * 超出的格子折进最后一格而不是丢掉：宁可显示难看，也不能让内容消失。
 */
private const val MAX_TABLE_COLUMNS = 64

// ---------------------------------------------------------------- 单元格切分

/**
 * 按 `|` 切一行表格，`\|` 是转义的竖线不算分隔。
 *
 * 首尾的空单元是「装饰性」的（`| a | b |` 的头尾管道），
 * 要去掉；但中间的空单元（`| a |  |`）是真列，必须留着。
 * 结果可能为空列表（整行只有一个 `|`）—— 调用方要能接住。
 */
private fun splitTableRow(line: String): List<String> {
    val raw = ArrayList<String>()
    val sb = StringBuilder()
    var i = 0
    while (i < line.length) {
        val c = line[i]
        if (c == '\\' && i + 1 < line.length && line[i + 1] == '|') {
            sb.append('|')
            i += 2
            continue
        }
        if (c == '|') {
            raw.add(sb.toString())
            sb.setLength(0)
            i++
            continue
        }
        sb.append(c)
        i++
    }
    raw.add(sb.toString())

    val cells = raw.map { it.trim() }.toMutableList()
    if (cells.size > 1 && cells.first().isEmpty()) cells.removeAt(0)
    if (cells.isNotEmpty() && cells.last().isEmpty()) cells.removeAt(cells.lastIndex)
    return cells
}

/** 分隔行单元：至少一个 `-`，两端可以各带一个 `:`。 */
private val DelimiterCell = Regex("^:?-+:?$")

private fun alignOf(cell: String): ColumnAlign = when {
    cell.startsWith(':') && cell.endsWith(':') -> ColumnAlign.CENTER
    cell.startsWith(':') -> ColumnAlign.START
    cell.endsWith(':') -> ColumnAlign.END
    else -> ColumnAlign.DEFAULT
}

/** 把一行塞进固定的列宽：短了补空，长了折进最后一格。 */
private fun fitRow(cells: List<String>, columns: Int): List<String> {
    if (columns <= 0) return emptyList()
    if (cells.size <= columns) return cells + List(columns - cells.size) { "" }
    val head = cells.take(columns - 1)
    // 超出的格子不丢：拼进最后一格，肉眼还能看出这里被折过
    val tail = cells.drop(columns - 1).joinToString(" | ")
    return head + tail
}

// ---------------------------------------------------------------- 各类行的识别

/** 围栏行。返回 null 表示不是围栏。 */
private data class Fence(val marker: Char, val length: Int, val info: String, val indent: Int)

private fun fenceOf(line: String): Fence? {
    var i = 0
    while (i < line.length && i < 4 && line[i] == ' ') i++
    val indent = i
    if (i >= line.length) return null
    val marker = line[i]
    if (marker != '`' && marker != '~') return null
    var j = i
    while (j < line.length && line[j] == marker) j++
    val n = j - i
    if (n < 3) return null
    val info = line.substring(j).trim()
    // 反引号围栏的 info 里不能有反引号，否则 ```a`b``` 这种写法会被误判成行内代码
    if (marker == '`' && info.contains('`')) return null
    return Fence(marker, n, info, indent)
}

private fun thematicBreakOf(line: String): Boolean {
    val t = line.trim()
    if (t.length < 3) return false
    val c = t[0]
    if (c != '-' && c != '*' && c != '_') return false
    var dashes = 0
    for (ch in t) {
        if (ch == c) dashes++ else if (!ch.isWhitespace()) return false
    }
    return dashes >= 3
}

private fun headingOf(line: String): MarkdownBlock.Heading? {
    var i = 0
    while (i < line.length && i < 4 && line[i] == ' ') i++
    var level = 0
    while (i + level < line.length && line[i + level] == '#') level++
    // 7 个以上井号不是标题，是普通文本
    if (level !in 1..6) return null
    val rest = line.substring(i + level)
    // `#标题`（井号后没空格）不是标题。空的 `#` 也不是 —— 渲染出来是空白，
    // 不如当普通文本把井号露出来
    if (rest.isEmpty() || !rest[0].isWhitespace()) return null
    return MarkdownBlock.Heading(level, rest.trim())
}

/** `> ` 剥一层，返回剩下的内容；不是引用行则返回 null。 */
private fun quoteOf(line: String): String? {
    var i = 0
    while (i < line.length && i < 4 && line[i] == ' ') i++
    if (i >= line.length || line[i] != '>') return null
    var j = i + 1
    if (j < line.length && line[j] == ' ') j++
    return line.substring(j)
}

private fun indentWidth(line: String): Int {
    var w = 0
    for (c in line) {
        when (c) {
            ' ' -> w += 1
            '\t' -> w += 4
            else -> return w
        }
    }
    return w
}

/** 列表项标记行。 */
private data class ListMarker(
    val indent: Int,
    val ordered: Boolean,
    val number: Int,
    val content: String
)

/** 把行首的 tab 展成空格，缩进语义才统一（tab 记 4 格）。 */
private fun normalizeLeadingTabs(line: String): String {
    if (!line.startsWith(" ") && !line.startsWith("\t")) return line
    val sb = StringBuilder()
    var i = 0
    while (i < line.length && (line[i] == ' ' || line[i] == '\t')) {
        sb.append(if (line[i] == '\t') "    " else " ")
        i++
    }
    sb.append(line, i, line.length)
    return sb.toString()
}

private fun listMarkerOf(line: String): ListMarker? {
    val norm = normalizeLeadingTabs(line)
    var i = 0
    while (i < norm.length && norm[i] == ' ') i++
    if (i >= norm.length) return null
    val c = norm[i]

    if (c == '-' || c == '*' || c == '+') {
        val after = norm.getOrNull(i + 1)
        if (after != null && !after.isWhitespace()) return null
        return ListMarker(i, ordered = false, number = 0, content = norm.substring(i + 1).trimStart())
    }

    var j = i
    while (j < norm.length && norm[j] in '0'..'9') j++
    val digits = j - i
    // 序号最多 9 位，再长就不是列表而是正文里的数字了
    if (digits in 1..9 && j < norm.length && (norm[j] == '.' || norm[j] == ')')) {
        val after = norm.getOrNull(j + 1)
        if (after == null || after.isWhitespace()) {
            val num = norm.substring(i, j)
            return ListMarker(
                indent = i,
                ordered = true,
                number = num.toIntOrNull() ?: 1,
                content = norm.substring(j + 1).trimStart()
            )
        }
    }
    return null
}

/** `- [ ] xxx` / `- [x] xxx` 的勾选态与去掉标记后的正文。 */
private fun taskOf(content: String): Pair<Boolean, String>? {
    if (content.length < 3) return null
    if (content[0] != '[' || content[2] != ']') return null
    return when (content[1]) {
        // pair 的第一项是 ListItem.checked（做完了为 true），别弄反
        ' ' -> false to content.substring(3).trimStart()
        'x', 'X' -> true to content.substring(3).trimStart()
        else -> null
    }
}

/**
 * 表格起始判定：当前行含 `|`，且下一行是整行都对得上的分隔行。
 *
 * 两条额外要求都是为了不误伤普通文本：
 * 分隔行本身必须也含 `|`（否则 `标题\n---` 这种 setext 标题会被当成表格），
 * 且它的每个格都匹配 `:?-+:?`（否则一整行 `---` 的水平线会变成表格）。
 */
private fun isTableStart(lines: List<String>, index: Int): Boolean {
    if (index + 1 >= lines.size) return false
    val head = lines[index]
    if (head.isBlank() || !head.contains('|')) return false
    val delim = lines[index + 1]
    if (delim.isBlank() || !delim.contains('|') || !delim.contains('-')) return false
    val cells = splitTableRow(delim)
    if (cells.isEmpty()) return false
    return cells.all { DelimiterCell.matches(it) }
}

/** 这行是否打断了当前段落（段落里不能藏块级结构）。 */
private fun breaksParagraph(line: String, lines: List<String>, index: Int): Boolean =
    line.isBlank() ||
        fenceOf(line) != null ||
        thematicBreakOf(line) ||
        headingOf(line) != null ||
        quoteOf(line) != null ||
        listMarkerOf(line) != null ||
        isTableStart(lines, index)

// ---------------------------------------------------------------- 主入口

/**
 * 把模型输出的 markdown 拆成块。
 *
 * 对畸形输入的取舍（都是「不丢内容」优先）：
 * - 围栏没闭合：剩余全文当代码块，[MarkdownBlock.CodeBlock.unterminated] 为 true。
 * - 表格缺分隔行：整段当普通段落（`| a | b |` 只是带竖线的文字，不猜它是表）。
 * - 表格行列不齐：**按最宽的行撑出列数**，短的补空串。没有一列被丢掉。
 * - 表格前面没有空行：照样识别（段落累积时一旦发现表格起始就断开）。
 */
fun parseMarkdown(source: String): List<MarkdownBlock> = parseBlocks(source, 0)

private fun parseBlocks(source: String, depth: Int): List<MarkdownBlock> {
    val lines = splitLines(source)
    val out = ArrayList<MarkdownBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.isBlank() -> i++

            fenceOf(line) != null -> {
                val (block, next) = parseFencedCode(lines, i)
                out.add(block)
                i = next
            }

            thematicBreakOf(line) -> {
                out.add(MarkdownBlock.ThematicBreak)
                i++
            }

            headingOf(line) != null -> {
                out.add(headingOf(line)!!)
                i++
            }

            isTableStart(lines, i) -> {
                val (block, next) = parseTable(lines, i)
                out.add(block)
                i = next
            }

            quoteOf(line) != null -> {
                val (block, next) = parseBlockquote(lines, i, depth)
                out.add(block)
                i = next
            }

            listMarkerOf(line) != null -> {
                val (block, next) = parseListRun(lines, i, depth)
                out.add(block)
                i = next
            }

            else -> {
                val buf = ArrayList<String>()
                while (i < lines.size && !breaksParagraph(lines[i], lines, i)) {
                    buf.add(lines[i])
                    i++
                }
                // 走到块尾但没真正推进（理论上不会，breaksParagraph 保证会推进）时的兜底
                if (buf.isEmpty()) buf.add(line)
                if (depth >= MAX_NEST_DEPTH) {
                    buf.forEach { out.add(MarkdownBlock.Paragraph(it)) }
                } else {
                    out.add(MarkdownBlock.Paragraph(buf.joinToString("\n")))
                }
            }
        }
    }
    return out
}

/** `\r\n` / `\r` 统一成 `\n`；末尾那一个换行不算内容。 */
private fun splitLines(source: String): List<String> {
    val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
    val lines = normalized.split('\n')
    return if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
}

private fun parseFencedCode(lines: List<String>, start: Int): Pair<MarkdownBlock.CodeBlock, Int> {
    val fence = fenceOf(lines[start])!!
    val body = ArrayList<String>()
    var i = start + 1
    var closed = false
    while (i < lines.size) {
        val candidate = fenceOf(lines[i])
        // 闭合围栏：同种字符、更长、且 info 为空
        if (candidate != null && candidate.marker == fence.marker &&
            candidate.length >= fence.length && candidate.info.isEmpty()
        ) {
            closed = true
            i++
            break
        }
        // 只剥围栏自身的缩进，代码内部的相对缩进必须原样保留
        body.add(if (fence.indent > 0) lines[i].drop(fence.indent) else lines[i])
        i++
    }
    val language = fence.info.substringBefore(' ').takeIf { it.isNotEmpty() }
    return MarkdownBlock.CodeBlock(language, body.joinToString("\n"), !closed) to i
}

private fun parseTable(lines: List<String>, start: Int): Pair<MarkdownBlock.TableBlock, Int> {
    val header = splitTableRow(lines[start])
    val delimCells = splitTableRow(lines[start + 1])

    val body = ArrayList<List<String>>()
    var i = start + 2
    while (i < lines.size) {
        val line = lines[i]
        if (line.isBlank()) break
        if (!line.contains('|')) break
        if (fenceOf(line) != null) break
        if (thematicBreakOf(line) || headingOf(line) != null) break
        if (quoteOf(line) != null || listMarkerOf(line) != null) break
        val cells = splitTableRow(line)
        // 单独一个 `|` 不是行，是没写完的表格 —— 到此为止，剩下的当段落
        if (cells.isEmpty()) break
        body.add(cells)
        i++
    }

    // 列数取所有行的最大值：短行补空、超宽行折进末格，两边都不丢内容
    var wanted = maxOf(header.size, delimCells.size)
    for (row in body) if (row.size > wanted) wanted = row.size
    val columns = wanted.coerceIn(1, MAX_TABLE_COLUMNS)

    return MarkdownBlock.TableBlock(
        header = fitRow(header, columns),
        aligns = fitRow(delimCells, columns).map { alignOf(it) },
        rows = body.map { fitRow(it, columns) }
    ) to i
}

private fun parseBlockquote(lines: List<String>, start: Int, depth: Int): Pair<MarkdownBlock.Blockquote, Int> {
    val buf = ArrayList<String>()
    var i = start
    while (i < lines.size) {
        val stripped = quoteOf(lines[i]) ?: break
        buf.add(stripped)
        i++
    }
    val blocks = if (depth + 1 >= MAX_NEST_DEPTH) {
        buf.map { MarkdownBlock.Paragraph(it) }
    } else {
        parseBlocks(buf.joinToString("\n"), depth + 1)
    }
    return MarkdownBlock.Blockquote(blocks) to i
}

/**
 * 解析一「段」同缩进的列表项，嵌套交给递归。
 *
 * 每项的正文 = 标记后面的第一行 + 后面所有比标记缩进更深的行（去掉标记宽度）。
 * 递归解析这些正文，缩进的子列表自然就变成了嵌套的 ListBlock。
 */
private fun parseListRun(lines: List<String>, start: Int, depth: Int): Pair<MarkdownBlock.ListBlock, Int> {
    val first = listMarkerOf(lines[start])!!
    val baseIndent = first.indent
    val items = ArrayList<ListItem>()
    var i = start

    while (i < lines.size) {
        val marker = listMarkerOf(lines[i]) ?: break
        if (marker.indent != baseIndent) break

        val task = taskOf(marker.content)
        val content = ArrayList<String>()
        if (task != null) content.add(task.second) else if (marker.content.isNotEmpty()) content.add(marker.content)
        i++

        // 续行：缩进比标记深。空行只有在后面还跟着缩进内容时才留在项内（松散列表）
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                val next = lines.getOrNull(i + 1)
                if (next != null && next.isNotBlank() && indentWidth(next) > baseIndent) {
                    content.add("")
                    i++
                    continue
                }
                i++
                break
            }
            val nextMarker = listMarkerOf(line)
            if (nextMarker != null && indentWidth(line) <= baseIndent) break
            if (indentWidth(line) > baseIndent) {
                content.add(line.drop(minOf(baseIndent + 2, indentWidth(line))))
                i++
                continue
            }
            break
        }

        val blocks = if (depth + 1 >= MAX_NEST_DEPTH) {
            content.map { MarkdownBlock.Paragraph(it) }
        } else {
            parseBlocks(content.joinToString("\n"), depth + 1)
        }
        items.add(ListItem(task?.first, blocks))
    }

    val list = MarkdownBlock.ListBlock(
        ordered = first.ordered,
        start = if (first.ordered) first.number else 1,
        items = items
    )
    return list to i
}
