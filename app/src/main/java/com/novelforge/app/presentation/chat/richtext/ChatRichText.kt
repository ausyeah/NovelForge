package com.novelforge.app.presentation.chat.richtext

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * 聊天气泡里的富文本渲染入口。
 *
 * 分层很清楚：[MarkdownBlocks] 切块 -> [MarkdownInline] / [LatexMath] 出
 * AnnotatedString -> 这里只负责把块铺开。颜色一律取
 * [MaterialTheme.colorScheme]，浅色深色都不会写出对比度问题。
 *
 * 三条必须守住的底线：
 * - 表格和代码块横向可滚。10 列的表塞进 300dp 的气泡，不滚就溢出。
 * - 任何输入都不能渲染成空白。解析器全部降级到「显示原文」。
 * - **本文件不得再碰指针事件**（没有 pointerInput / detectTapGestures /
 *   clickable）。气泡整棵子树都活在调用方的 SelectionContainer 里，任何
 *   在这里 consume 按下事件的识别器都会把长按选词抢走。链接点击因此完全
 *   交给 Compose 自己的 LinkAnnotation 机制（见 MarkdownInline，那是链接
 *   唯一的给法），拦截打开动作靠 LocalUriHandler 覆盖，不经过指针系统。
 */
@Composable
fun ChatRichText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    onLinkClick: ((String) -> Unit)? = null
) {
    // 按文本 memo：滚动时不重解析，流式刷新时只重解析这一个气泡。
    // 颜色不在这个 AnnotatedString 里（由 Text 的 style 统一给），
    // 所以主题变化不会拿到过期样式。
    //
    // 别再拿「key 换成更便宜的东西」来救流式卡顿：前缀字符串每帧都不一样，
    // 换成 length / hash 之类只会把「重解析」换成「重比较」，一分钱都不省。
    // 真正已经省下来的是下游 —— `rememberInlineRich` 按 `block.text` 逐块
    // 缓存，块结构相等就命中（MarkdownBlock 全是 data class）。实测一次
    // 5054 字 / 54 块的流：每帧只有 1~3 个块发生变化，100 帧合计重算 154 个
    // 块（整篇重解析的话是 5400 个）。
    //
    // 而这里这 0.1~0.3ms 的整篇重解析**不是**卡顿的原因（50ms 帧预算的
    // 0.6%），尾块行内解析 0.012ms 也是。见 StreamingParseBenchmarkTest。
    val blocks = remember(text) { parseMarkdown(text) }
    if (blocks.isEmpty()) return

    // Text 命中 LinkAnnotation.Url 后会走 LocalUriHandler.openUri（见
    // TextLinkScope.handleLink）。在这里覆盖它，就能在完全不注册指针
    // 识别器的前提下接管「打开链接」——长按选词、拖拽选区都归
    // SelectionContainer 自己，不会被抢。
    val defaultUriHandler = LocalUriHandler.current
    val latestOnLinkClick = rememberUpdatedState(onLinkClick)
    val uriHandler = remember(defaultUriHandler) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val callback = latestOnLinkClick.value
                if (callback != null) callback(uri) else defaultUriHandler.openUri(uri)
            }
        }
    }

    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            blocks.forEach { block ->
                MarkdownBlockView(block, color)
            }
        }
    }
}

@Composable
private fun MarkdownBlockView(
    block: MarkdownBlock,
    color: Color
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    when (block) {
        is MarkdownBlock.Heading -> {
            val style = when (block.level) {
                1 -> MaterialTheme.typography.titleMedium
                2 -> MaterialTheme.typography.titleSmall
                else -> MaterialTheme.typography.bodyMedium
            }
            val rich = rememberInlineRich(block.text)
            RichInline(rich, style.copy(fontWeight = FontWeight.Bold), color)
        }

        is MarkdownBlock.Paragraph -> ParagraphView(block.text, color)

        is MarkdownBlock.CodeBlock -> CodeBlockView(block, color)

        is MarkdownBlock.TableBlock -> TableView(block, color)

        is MarkdownBlock.ListBlock -> ListView(block, color)

        is MarkdownBlock.Blockquote -> {
            // 左侧一条竖线 + 内容缩进。嵌套引用会自然画成多条竖线。
            //
            // 竖线用 drawBehind 画在内容上，**不用** Row + height(IntrinsicSize.Min)
            // + 竖条 fillMaxHeight 那套：IntrinsicSize 会强制 Compose 对整棵子树
            // 先跑一遍 intrinsic 测量再跑一遍真实测量，而流式输出时这段引用每帧
            // 都在长，等于每帧多测一整棵子树。drawBehind 零测量开销，画出来一样。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val lineWidth = 3.dp.toPx()
                        drawRect(
                            color = lineColor,
                            topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                            size = androidx.compose.ui.geometry.Size(lineWidth, size.height)
                        )
                    }
                    .padding(start = 13.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                block.blocks.forEach { MarkdownBlockView(it, color) }
            }
        }

        MarkdownBlock.ThematicBreak -> Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}

// ---------------------------------------------------------------- 段落

@Composable
private fun ParagraphView(source: String, color: Color) {
    val baseStyle = MaterialTheme.typography.bodyMedium
    val mathStyle = mathStyleFor(baseStyle, color)
    // display 数学（$$...$$）要自己占一行居中，所以先把它切出来
    val segments = remember(source) { splitDisplayMath(source) }

    if (segments.size == 1 && segments.first() is MathSegment.Text) {
        RichInline(rememberInlineRich(source, mathStyle), baseStyle, color)
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        segments.forEach { segment ->
            when (segment) {
                is MathSegment.Text -> if (segment.text.isNotEmpty()) {
                    RichInline(
                        rememberInlineRich(segment.text, mathStyle),
                        baseStyle,
                        color
                    )
                }

                is MathSegment.Display -> DisplayMathView(segment.source, mathStyle, color)
            }
        }
    }
}

@Composable
private fun DisplayMathView(source: String, mathStyle: MathStyle, color: Color) {
    val expr = remember(source, mathStyle) { buildMathExpression(source, mathStyle) }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (expr.text.text.isBlank()) {
            // 空公式不能让整行塌成空白
            Text(
                text = "\$\$ $source \$\$",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            MathInline(expr, color)
        }
    }
}

@Composable
private fun mathStyleFor(baseStyle: TextStyle, color: Color): MathStyle {
    val fontSize = baseStyle.fontSize.takeIf { it != TextUnit.Unspecified } ?: 15.sp
    return MathStyle(color = color, fontSize = fontSize)
}

/** 一段行内渲染结果：正文 + 需要 inlineContent 的公式部件。 */
private class InlineRich(
    val text: AnnotatedString,
    val parts: Map<String, MathPart>
)

@Composable
private fun rememberInlineRich(
    source: String,
    mathStyle: MathStyle? = null
): InlineRich {
    val scheme = MaterialTheme.colorScheme
    val options = remember(scheme) {
        InlineStyleOptions(
            codeBackground = scheme.surfaceVariant.copy(alpha = 0.7f),
            link = SpanStyle(color = scheme.primary)
        )
    }
    return remember(source, options, mathStyle) {
        val parts = LinkedHashMap<String, MathPart>()
        val style = mathStyle ?: MathStyle()
        val sink = MathInlineSink { builder, mathSource ->
            appendMathExpression(builder, mathSource, style, parts)
        }
        // 链接命中和打开都交给 Compose 自己（它挂在 Text 的子节点上，
        // 不在 Text 的手势路径上），而自定义的 pointerInput 一旦装上
        // 就会和 SelectionContainer 抢长按。
        val text = buildMarkdownInline(source, sink, options)
        InlineRich(text, parts)
    }
}

/** 真正的 Text 出口。公式占位在这一层收口；链接点击不经过这里。 */
@Composable
private fun RichInline(
    rich: InlineRich,
    style: TextStyle,
    color: Color
) {
    if (rich.text.text.isEmpty()) {
        // 空内容不留一个 0 高度的 Text：调用方该自己画占位
        return
    }
    InlineText(
        text = rich.text,
        parts = rich.parts,
        style = style,
        color = color
    )
}

@Composable
private fun MathInline(expr: MathExpression, color: Color) {
    val style = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 16.sp,
        fontStyle = FontStyle.Normal
    )
    InlineText(expr.text, expr.parts, style, color)
}

/**
 * 单个 Text 出口。
 *
 * 刻意保持「零指针输入」：没有 pointerInput、没有 detectTapGestures、
 * 没有 clickable，也没有 onTextLayout —— 全都是为了做链接点击命中测试
 * 才需要的东西，而正是它抢走了 SelectionContainer 的长按。链接的打开
 * 由 [ChatRichText] 挂在树上的 [LocalUriHandler] 拦截。
 */
@Composable
private fun InlineText(
    text: AnnotatedString,
    parts: Map<String, MathPart>,
    style: TextStyle,
    color: Color
) {
    Text(
        text = text,
        color = color,
        inlineContent = mathInlineContent(parts, color, style.fontSize),
        style = style
    )
}

// ---------------------------------------------------------------- 公式占位

/**
 * 占位部件 -> inlineContent。
 *
 * 尺寸由解析层估好（[MathPart.widthEm]/[heightEm]）：宽度按分子分母的
 * 字符数算，好让分数线两侧留够地方。估得不准的后果最多是留白多一点，
 * 不会压到相邻文字 —— 占位框是写死的，绘制宽度跟它对齐。
 */
@Composable
private fun mathInlineContent(
    parts: Map<String, MathPart>,
    color: Color,
    fontSize: TextUnit
): Map<String, InlineTextContent> {
    val scheme = MaterialTheme.colorScheme
    val base = fontSize.takeIf { it != TextUnit.Unspecified } ?: 15.sp
    val script = base * 0.72f
    val density = LocalDensity.current
    val rule = scheme.onSurface

    return remember(parts, color, scheme, density) {
        val map = LinkedHashMap<String, InlineTextContent>()
        for ((tag, part) in parts) {
            map[tag] = when (part) {
                is MathPart.Fraction -> InlineTextContent(
                    placeholder = Placeholder(
                        width = part.widthEm.em,
                        height = part.heightEm.em,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                    )
                ) {
                    val width = with(density) { part.widthEm.em.toDp() }
                    Column(
                        modifier = Modifier
                            .width(width)
                            .padding(horizontal = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        MathParts(part.numerator, color, script)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(rule)
                        )
                        MathParts(part.denominator, color, script)
                    }
                }

                is MathPart.Radical -> InlineTextContent(
                    placeholder = Placeholder(
                        width = part.widthEm.em,
                        height = part.heightEm.em,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.Bottom
                    )
                ) {
                    val width = with(density) { part.widthEm.em.toDp() }
                    Row(
                        modifier = Modifier.width(width),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        part.index?.let { MathParts(it, color, script * 0.8f) }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // 根号本身要比正文大一点，才像根号
                            Text("√", color = color, fontSize = base * 1.25f)
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(rule)
                            )
                            MathParts(part.radicand, color, script)
                        }
                    }
                }
            }
        }
        map
    }
}

@Composable
private fun MathParts(expr: MathExpression, color: Color, fontSize: TextUnit) {
    if (expr.text.text.isBlank()) {
        // 空部件也要占高度，否则分数只剩一条线、根号只剩一个勾
        Text(" ", color = color, fontSize = fontSize)
        return
    }
    InlineText(
        text = expr.text,
        parts = expr.parts,
        style = MaterialTheme.typography.bodySmall.copy(
            fontSize = fontSize,
            fontStyle = FontStyle.Normal
        ),
        color = color
    )
}

// ---------------------------------------------------------------- 代码块

@Composable
private fun CodeBlockView(block: MarkdownBlock.CodeBlock, color: Color) {
    val scheme = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        if (block.language != null) {
            Text(
                text = block.language,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp)
            )
        }
        if (block.unterminated) {
            // 围栏没收尾：说一声，免得用户以为渲染坏了
            Text(
                text = "代码块未闭合",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.error,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 2.dp)
            )
        }
        Text(
            text = block.code,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = color,
            // softWrap = false + 横向滚动：超长的一行滚得动，不会把气泡撑破
            softWrap = false,
            modifier = Modifier
                .horizontalScroll(scroll)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

// ---------------------------------------------------------------- 表格

@Composable
private fun TableView(
    block: MarkdownBlock.TableBlock,
    color: Color
) {
    val scheme = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(8.dp))
            // 整张表一起横向滚动。10 列的表在 300dp 的气泡里必须能滑
            .horizontalScroll(scroll)
    ) {
        TableRow(
            cells = block.header,
            aligns = block.aligns,
            color = color,
            bold = true,
            background = scheme.surfaceVariant.copy(alpha = 0.6f)
        )
        block.rows.forEachIndexed { index, row ->
            if (index > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(scheme.outlineVariant)
                )
            }
            TableRow(row, block.aligns, color, bold = false, background = Color.Transparent)
        }
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    aligns: List<ColumnAlign>,
    color: Color,
    bold: Boolean,
    background: Color
) {
    Row {
        cells.forEachIndexed { index, cell ->
            TableCell(
                source = cell,
                align = aligns.getOrNull(index) ?: ColumnAlign.DEFAULT,
                color = color,
                bold = bold,
                background = background,
                showDivider = tableCellShowsDivider(index, cells.size)
            )
        }
    }
}

/**
 * 这一格要不要在自己右边缘画分隔线。
 *
 * 分隔线画在**非末位**单元格的右边缘。写成 `index > 0` 就错了 ——
 * 那是"非首位"，会在最后一张格子右边多画一条线，表格凭空多个右边框。
 * 这个条件我第一版正好写反过，所以提成纯函数钉住。
 *
 * 以前分隔线是 Row(height(IntrinsicSize.Min)) 里每个非首格**之前**插的一根
 * fillMaxHeight 细条。为了那根线要对整行多跑一遍 intrinsic 测量，而流式输出时
 * 表格是一行行长出来的，等于每帧把整张表多测一遍。格子自己知道多高，
 * 画在它自己身上就够，Row 根本不需要 intrinsic。
 */
internal fun tableCellShowsDivider(index: Int, columnCount: Int): Boolean =
    columnCount > 1 && index >= 0 && index < columnCount - 1

@Composable
private fun TableCell(
    source: String,
    align: ColumnAlign,
    color: Color,
    bold: Boolean,
    background: Color,
    showDivider: Boolean
) {
    val style = MaterialTheme.typography.bodySmall.copy(
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = alignToTextAlign(align)
    )
    val rich = rememberInlineRich(source)
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .widthIn(min = 72.dp)
            .background(background)
            // 顺序要紧：drawBehind 必须在 background **之后**。
            // 链上的绘制按顺序由后往前盖，写在前面就等于画在底色底下被盖掉了。
            // 放在这里刚好压在底色上、文字下（右边有 10dp 内边距，碰不到字）。
            .drawBehind {
                if (!showDivider) return@drawBehind
                val w = 1.dp.toPx()
                drawRect(
                    color = dividerColor,
                    topLeft = androidx.compose.ui.geometry.Offset(size.width - w, 0f),
                    size = androidx.compose.ui.geometry.Size(w, size.height)
                )
            }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        if (rich.text.text.isEmpty()) {
            // 空单元也要有高度，否则行会塌
            Box(Modifier.height(18.dp))
        } else {
            RichInline(rich, style, color)
        }
    }
}

private fun alignToTextAlign(align: ColumnAlign): TextAlign = when (align) {
    ColumnAlign.START, ColumnAlign.DEFAULT -> TextAlign.Start
    ColumnAlign.CENTER -> TextAlign.Center
    ColumnAlign.END -> TextAlign.End
}

// ---------------------------------------------------------------- 列表

@Composable
private fun ListView(
    block: MarkdownBlock.ListBlock,
    color: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        block.items.forEachIndexed { index, item ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = listMarkerText(block, index, item),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (item.checked == true) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        color
                    },
                    modifier = Modifier
                        .widthIn(min = if (block.ordered) 26.dp else 18.dp)
                        .padding(end = 4.dp)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 任务项没有正文（`- [ ]` 后面啥都没写）也要占一行，
                    // 否则复选框会孤零零挂在列表符后面
                    if (item.blocks.isEmpty()) {
                        Box(Modifier.height(20.dp))
                    } else {
                        item.blocks.forEach { MarkdownBlockView(it, color) }
                    }
                }
            }
        }
    }
}

private fun listMarkerText(block: MarkdownBlock.ListBlock, index: Int, item: ListItem): String =
    when {
        item.checked == true -> "☑"
        item.checked == false -> "☐"
        block.ordered -> "${block.start + index}."
        else -> "•"
    }
