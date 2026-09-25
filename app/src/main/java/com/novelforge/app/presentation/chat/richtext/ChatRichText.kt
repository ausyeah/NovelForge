package com.novelforge.app.presentation.chat.richtext

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
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
 * 两条必须守住的底线：
 * - 表格和代码块横向可滚。10 列的表塞进 300dp 的气泡，不滚就溢出。
 * - 任何输入都不能渲染成空白。解析器全部降级到「显示原文」。
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
    val blocks = remember(text) { parseMarkdown(text) }
    if (blocks.isEmpty()) return

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        blocks.forEach { block ->
            MarkdownBlockView(block, color, onLinkClick)
        }
    }
}

@Composable
private fun MarkdownBlockView(
    block: MarkdownBlock,
    color: Color,
    onLinkClick: ((String) -> Unit)?
) {
    when (block) {
        is MarkdownBlock.Heading -> {
            val style = when (block.level) {
                1 -> MaterialTheme.typography.titleMedium
                2 -> MaterialTheme.typography.titleSmall
                else -> MaterialTheme.typography.bodyMedium
            }
            val rich = rememberInlineRich(block.text, nativeLinks = onLinkClick == null)
            RichInline(rich, style.copy(fontWeight = FontWeight.Bold), color, onLinkClick)
        }

        is MarkdownBlock.Paragraph -> ParagraphView(block.text, color, onLinkClick)

        is MarkdownBlock.CodeBlock -> CodeBlockView(block, color)

        is MarkdownBlock.TableBlock -> TableView(block, color, onLinkClick)

        is MarkdownBlock.ListBlock -> ListView(block, color, onLinkClick)

        is MarkdownBlock.Blockquote -> {
            // 左侧一条竖线 + 内容缩进。嵌套引用会自然画成多条竖线
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                Column(
                    modifier = Modifier.padding(start = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    block.blocks.forEach { MarkdownBlockView(it, color, onLinkClick) }
                }
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
private fun ParagraphView(source: String, color: Color, onLinkClick: ((String) -> Unit)?) {
    val baseStyle = MaterialTheme.typography.bodyMedium
    val mathStyle = mathStyleFor(baseStyle, color)
    // 接了 onLinkClick 就只挂 stringAnnotation（点击自己接管）；
    // 否则挂 LinkAnnotation，让系统按普通链接处理（TalkBack 念得出「链接」）
    val nativeLinks = onLinkClick == null
    // display 数学（$$...$$）要自己占一行居中，所以先把它切出来
    val segments = remember(source) { splitDisplayMath(source) }

    if (segments.size == 1 && segments.first() is MathSegment.Text) {
        RichInline(
            rememberInlineRich(source, mathStyle, nativeLinks),
            baseStyle,
            color,
            onLinkClick
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        segments.forEach { segment ->
            when (segment) {
                is MathSegment.Text -> if (segment.text.isNotEmpty()) {
                    RichInline(
                        rememberInlineRich(segment.text, mathStyle, nativeLinks),
                        baseStyle,
                        color,
                        onLinkClick
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
    mathStyle: MathStyle? = null,
    nativeLinks: Boolean = false
): InlineRich {
    val scheme = MaterialTheme.colorScheme
    val options = remember(scheme) {
        InlineStyleOptions(
            codeBackground = scheme.surfaceVariant.copy(alpha = 0.7f),
            link = SpanStyle(color = scheme.primary)
        )
    }
    return remember(source, options, mathStyle, nativeLinks) {
        val parts = LinkedHashMap<String, MathPart>()
        val style = mathStyle ?: MathStyle()
        val sink = MathInlineSink { builder, mathSource ->
            appendMathExpression(builder, mathSource, style, parts)
        }
        val text = buildMarkdownInline(source, sink, options, useLinkAnnotations = nativeLinks)
        InlineRich(text, parts)
    }
}

/** 真正的 Text 出口。链接点击、公式占位都在这一层收口。 */
@Composable
private fun RichInline(
    rich: InlineRich,
    style: TextStyle,
    color: Color,
    onLinkClick: ((String) -> Unit)?
) {
    if (rich.text.text.isEmpty()) {
        // 空内容不留一个 0 高度的 Text：调用方该自己画占位
        return
    }
    InlineText(
        text = rich.text,
        parts = rich.parts,
        style = style,
        color = color,
        onLinkClick = onLinkClick
    )
}

@Composable
private fun MathInline(expr: MathExpression, color: Color) {
    val style = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 16.sp,
        fontStyle = FontStyle.Normal
    )
    InlineText(expr.text, expr.parts, style, color, null)
}

@Composable
private fun InlineText(
    text: AnnotatedString,
    parts: Map<String, MathPart>,
    style: TextStyle,
    color: Color,
    onLinkClick: ((String) -> Unit)?
) {
    val inline = mathInlineContent(parts, color, style.fontSize)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val handler by rememberUpdatedState(onLinkClick)

    // 只在真的要接管点击时才装手势。默认 handler == null 时完全不碰指针
    // 事件，SelectionContainer 的长按选词一点不受影响
    val clickModifier = if (onLinkClick == null) {
        Modifier
    } else {
        Modifier.pointerInput(Unit) {
            detectTapGestures { position ->
                val result = layout ?: return@detectTapGestures
                val offset = result.getOffsetForPosition(position)
                // 用自己 build 出来的 AnnotatedString 查标注：
                // TextLayoutResult 不把原文暴露出来
                val hit = text
                    .getStringAnnotations(URL_TAG, offset, offset)
                    .firstOrNull()
                if (hit != null) handler?.invoke(hit.item)
            }
        }
    }

    Text(
        text = text,
        modifier = clickModifier,
        color = color,
        inlineContent = inline,
        onTextLayout = { layout = it },
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
        color = color,
        onLinkClick = null
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
    color: Color,
    onLinkClick: ((String) -> Unit)?
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
            onLinkClick = onLinkClick,
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
            TableRow(row, block.aligns, color, onLinkClick, bold = false, background = Color.Transparent)
        }
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    aligns: List<ColumnAlign>,
    color: Color,
    onLinkClick: ((String) -> Unit)?,
    bold: Boolean,
    background: Color
) {
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        cells.forEachIndexed { index, cell ->
            if (index > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
            TableCell(
                source = cell,
                align = aligns.getOrNull(index) ?: ColumnAlign.DEFAULT,
                color = color,
                onLinkClick = onLinkClick,
                bold = bold,
                background = background
            )
        }
    }
}

@Composable
private fun TableCell(
    source: String,
    align: ColumnAlign,
    color: Color,
    onLinkClick: ((String) -> Unit)?,
    bold: Boolean,
    background: Color
) {
    val style = MaterialTheme.typography.bodySmall.copy(
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = alignToTextAlign(align)
    )
    val rich = rememberInlineRich(source, nativeLinks = onLinkClick == null)
    Box(
        modifier = Modifier
            .widthIn(min = 72.dp)
            .background(background)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        if (rich.text.text.isEmpty()) {
            // 空单元也要有高度，否则行会塌
            Box(Modifier.height(18.dp))
        } else {
            RichInline(rich, style, color, onLinkClick)
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
    color: Color,
    onLinkClick: ((String) -> Unit)?
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
                        item.blocks.forEach { MarkdownBlockView(it, color, onLinkClick) }
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
