package com.novelforge.app.presentation.chat.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 块级 markdown 解析的判定。
 *
 * 这里是最该钉死的地方：没有真机，样式好不好看只能靠肉眼，
 * 但「表格少了一列」「代码块把内容吞了」这类问题一旦发生就是
 * 用户看到空白或者错位，必须靠测试证明不会发生。
 */
class MarkdownBlocksTest {

    // ---------- 标题 ----------

    @Test
    fun headings_coverAllSixLevels() {
        val blocks = parseMarkdown("# 一\n## 二\n### 三\n#### 四\n##### 五\n###### 六")
        assertEquals(6, blocks.size)
        assertEquals(
            listOf(1, 2, 3, 4, 5, 6),
            blocks.map { (it as MarkdownBlock.Heading).level }
        )
        assertEquals(
            listOf("一", "二", "三", "四", "五", "六"),
            blocks.map { (it as MarkdownBlock.Heading).text }
        )
    }

    @Test
    fun sevenHashes_isNotAHeading() {
        // GFM：7 个以上井号不是标题
        val blocks = parseMarkdown("####### 七个井号")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
        assertEquals("####### 七个井号", (blocks[0] as MarkdownBlock.Paragraph).text)
    }

    @Test
    fun hashWithoutSpace_isNotAHeading() {
        val blocks = parseMarkdown("#标题")
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
    }

    @Test
    fun loneHash_isNotAHeading() {
        // 空标题渲染出来是空白，宁可把井号露出来
        val blocks = parseMarkdown("#")
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
        assertEquals("#", (blocks[0] as MarkdownBlock.Paragraph).text)
    }

    // ---------- 段落 ----------

    @Test
    fun consecutiveLines_joinIntoOneParagraph() {
        val blocks = parseMarkdown("第一行\n第二行\n第三行")
        assertEquals(1, blocks.size)
        assertEquals("第一行\n第二行\n第三行", (blocks[0] as MarkdownBlock.Paragraph).text)
    }

    @Test
    fun multipleBlankLines_collapse() {
        val blocks = parseMarkdown("上\n\n\n\n\n下")
        assertEquals(2, blocks.size)
        assertEquals("上", (blocks[0] as MarkdownBlock.Paragraph).text)
        assertEquals("下", (blocks[1] as MarkdownBlock.Paragraph).text)
    }

    // ---------- 围栏代码块 ----------

    @Test
    fun fencedCode_capturesLanguageAndPreservesBody() {
        val blocks = parseMarkdown("```kotlin\nval a = 1\n\n  val b = 2\n```")
        assertEquals(1, blocks.size)
        val code = blocks[0] as MarkdownBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertFalse(code.unterminated)
        // 空行和缩进必须原样：代码块的语义全在这上面
        assertEquals("val a = 1\n\n  val b = 2", code.code)
    }

    @Test
    fun fencedCode_keepsRestWhenUnterminated() {
        // 模型流式输出时经常只吐了 ``` 没收尾。这时候内容一个字都不能丢
        val blocks = parseMarkdown("```python\nprint(1)\nprint(2)")
        val code = blocks[0] as MarkdownBlock.CodeBlock
        assertEquals("python", code.language)
        assertTrue(code.unterminated)
        assertEquals("print(1)\nprint(2)", code.code)
    }

    @Test
    fun fencedCode_emptyBody_isStillACodeBlock() {
        val blocks = parseMarkdown("```\n```")
        val code = blocks[0] as MarkdownBlock.CodeBlock
        assertEquals(null, code.language)
        assertEquals("", code.code)
        assertFalse(code.unterminated)
    }

    @Test
    fun fencedCode_tildeFence_works() {
        val blocks = parseMarkdown("~~~\nraw\n~~~")
        val code = blocks[0] as MarkdownBlock.CodeBlock
        assertEquals("raw", code.code)
    }

    @Test
    fun fencedCode_closingFenceNeedsThreeTicks() {
        // 只有两个反引号不算闭合，内容继续往下吃
        val blocks = parseMarkdown("```\ncode\n``")
        val code = blocks[0] as MarkdownBlock.CodeBlock
        assertTrue(code.unterminated)
        assertEquals("code\n``", code.code)
    }

    @Test
    fun inlineBacktickLessThanThree_isNotAFence() {
        val blocks = parseMarkdown("``not a fence``")
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
    }

    // ---------- 表格 ----------

    @Test
    fun table_readsHeaderAlignsAndBody() {
        val blocks = parseMarkdown(
            """
            | 名字 | 年龄 | 城市 |
            | :--- | :---: | ---: |
            | 小明 | 18 | 北京 |
            | 小红 | 20 | 上海 |
            """.trimIndent()
        )
        val table = blocks[0] as MarkdownBlock.TableBlock
        assertEquals(listOf("名字", "年龄", "城市"), table.header)
        assertEquals(
            listOf(ColumnAlign.START, ColumnAlign.CENTER, ColumnAlign.END),
            table.aligns
        )
        assertEquals(2, table.rows.size)
        assertEquals(listOf("小明", "18", "北京"), table.rows[0])
        assertEquals(listOf("小红", "20", "上海"), table.rows[1])
        assertEquals(3, table.columnCount)
    }

    @Test
    fun table_withoutOuterPipes_stillWorks() {
        val blocks = parseMarkdown("a | b\n--- | ---\n1 | 2")
        val table = blocks[0] as MarkdownBlock.TableBlock
        assertEquals(listOf("a", "b"), table.header)
        assertEquals(listOf(listOf("1", "2")), table.rows)
    }

    @Test
    fun table_withoutDelimiterRow_fallsBackToParagraph() {
        // 缺分隔行就不猜它是表：`| a | b |` 只是带竖线的文字
        val blocks = parseMarkdown("| a | b |\n| c | d |")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
    }

    @Test
    fun raggedRow_widerThanHeader_growsTheTableWithoutLosingText() {
        // 多出来的格子不许丢：撑出列数，比静默吃掉一列强
        val blocks = parseMarkdown(
            """
            | a | b |
            | --- | --- |
            | 1 | 2 | 3 | 4 |
            """.trimIndent()
        )
        val table = blocks[0] as MarkdownBlock.TableBlock
        assertEquals(4, table.columnCount)
        assertEquals(listOf("a", "b", "", ""), table.header)
        assertEquals(listOf("1", "2", "3", "4"), table.rows[0])
    }

    @Test
    fun raggedRow_narrowerThanHeader_isPaddedWithEmptyCells() {
        val blocks = parseMarkdown(
            """
            | a | b | c |
            | --- | --- | --- |
            | 1 |
            """.trimIndent()
        )
        val table = blocks[0] as MarkdownBlock.TableBlock
        assertEquals(3, table.columnCount)
        assertEquals(listOf("1", "", ""), table.rows[0])
    }

    @Test
    fun raggedAlignRow_isPaddedToo() {
        val blocks = parseMarkdown(
            """
            | a | b | c |
            | :-- | :-: |
            | 1 | 2 | 3 |
            """.trimIndent()
        )
        val table = blocks[0] as MarkdownBlock.TableBlock
        assertEquals(3, table.columnCount)
        assertEquals(
            listOf(ColumnAlign.START, ColumnAlign.CENTER, ColumnAlign.DEFAULT),
            table.aligns
        )
    }

    @Test
    fun table_escapedPipe_staysInsideTheCell() {
        val blocks = parseMarkdown(
            """
            | a | b |
            | --- | --- |
            | x \| y | 2 |
            """.trimIndent()
        )
        val table = blocks[0] as MarkdownBlock.TableBlock
        assertEquals(2, table.columnCount)
        assertEquals(listOf("x | y", "2"), table.rows[0])
    }

    @Test
    fun table_notPrecededByBlankLine_isStillATable() {
        // LLM 经常忘了空行。段落累积到一半发现分隔行就地开表
        val blocks = parseMarkdown("前言文字\n| a | b |\n| --- | --- |\n| 1 | 2 |")
        assertEquals(2, blocks.size)
        assertEquals("前言文字", (blocks[0] as MarkdownBlock.Paragraph).text)
        assertEquals(1, (blocks[1] as MarkdownBlock.TableBlock).rows.size)
    }

    @Test
    fun table_endsAtTheFirstNonRowLine() {
        val blocks = parseMarkdown(
            """
            | a | b |
            | --- | --- |
            | 1 | 2 |
            表格后面这段
            """.trimIndent()
        )
        assertEquals(2, blocks.size)
        assertTrue(blocks[1] is MarkdownBlock.Paragraph)
    }

    @Test
    fun lonePipe_isAParagraphAndEndsTheTable() {
        val blocks = parseMarkdown("| a | b |\n| --- | --- |\n| 1 | 2 |\n|")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.TableBlock)
        assertEquals("|", (blocks[1] as MarkdownBlock.Paragraph).text)
    }

    @Test
    fun setextLikeDashesAfterPlainText_isNotATable() {
        // 「标题\n----」少了竖线，setext 标题不能被误认成表格
        val blocks = parseMarkdown("第一章\n-----")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
        assertEquals(MarkdownBlock.ThematicBreak, blocks[1])
    }

    @Test
    fun table_cellsKeepInlineMarkdownForLaterLayer() {
        val blocks = parseMarkdown(
            """
            | 说明 |
            | --- |
            | **重点** |
            """.trimIndent()
        )
        val table = blocks[0] as MarkdownBlock.TableBlock
        // 块层只存原文，加粗交给行内层
        assertEquals("**重点**", table.rows[0][0])
    }

    @Test
    fun table_withTenColumns_doesNotThrow() {
        val header = (1..10).joinToString("|") { "列$it" } + "|"
        val delim = (1..10).joinToString("|") { "---" } + "|"
        val row = (1..10).joinToString("|") { "值$it" } + "|"
        val table = parseMarkdown("$header\n$delim\n$row")[0] as MarkdownBlock.TableBlock
        assertEquals(10, table.columnCount)
        assertEquals(10, table.rows[0].size)
    }

    // ---------- 列表 ----------

    @Test
    fun unorderedList_acceptsAllThreeBullets() {
        val blocks = parseMarkdown("- 甲\n* 乙\n+ 丙")
        val list = blocks[0] as MarkdownBlock.ListBlock
        assertFalse(list.ordered)
        assertEquals(3, list.items.size)
        assertEquals(
            listOf("甲", "乙", "丙"),
            list.items.map { (it.blocks[0] as MarkdownBlock.Paragraph).text }
        )
    }

    @Test
    fun orderedList_keepsStartNumberAndBothDelimiters() {
        val list = parseMarkdown("3. 三\n4) 四")[0] as MarkdownBlock.ListBlock
        assertTrue(list.ordered)
        assertEquals(3, list.start)
        assertEquals(2, list.items.size)
    }

    @Test
    fun nestedList_becomesASubList() {
        val blocks = parseMarkdown(
            """
            - 一级甲
              - 二级甲
              - 二级乙
            - 一级乙
            """.trimIndent()
        )
        val list = blocks[0] as MarkdownBlock.ListBlock
        assertEquals(2, list.items.size)
        val firstBlocks = list.items[0].blocks
        assertEquals(2, firstBlocks.size)
        assertEquals("一级甲", (firstBlocks[0] as MarkdownBlock.Paragraph).text)
        val sub = firstBlocks[1] as MarkdownBlock.ListBlock
        assertEquals(2, sub.items.size)
        assertEquals("二级甲", (sub.items[0].blocks[0] as MarkdownBlock.Paragraph).text)
    }

    @Test
    fun deeplyNestedList_doesNotBlowUp() {
        val source = (1..6).joinToString("\n") { level ->
            "  ".repeat(level - 1) + "- 第${level}层"
        }
        val list = parseMarkdown(source)[0] as MarkdownBlock.ListBlock
        assertEquals(1, list.items.size)
        // 一路往下钻 5 层都应该还在
        var blocks = list.items[0].blocks
        var depth = 0
        while (blocks.any { it is MarkdownBlock.ListBlock }) {
            blocks = (blocks.first { it is MarkdownBlock.ListBlock } as MarkdownBlock.ListBlock)
                .items[0].blocks
            depth++
        }
        assertEquals(5, depth)
    }

    @Test
    fun taskList_marksCheckedState() {
        val list = parseMarkdown("- [ ] 没做\n- [x] 做了\n- [X] 也做了")[0] as MarkdownBlock.ListBlock
        assertEquals(listOf(false, true, true), list.items.map { it.checked })
        assertTrue(list.items.all { it.isTask })
        assertEquals("没做", (list.items[0].blocks[0] as MarkdownBlock.Paragraph).text)
    }

    @Test
    fun taskItem_withoutBody_stillYieldsAnItem() {
        // `- [ ]` 后面啥都没写：不能因为正文空就把这一项吞掉
        val list = parseMarkdown("- [ ]")[0] as MarkdownBlock.ListBlock
        assertEquals(1, list.items.size)
        assertEquals(false, list.items[0].checked)
        assertTrue(list.items[0].blocks.isEmpty())
    }

    @Test
    fun dashFollowedByTextWithoutSpace_isNotAList() {
        val blocks = parseMarkdown("-5 摄氏度")
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
    }

    @Test
    fun listWithBlankLineBetweenItems_staysOneList() {
        val list = parseMarkdown("- 甲\n\n- 乙")[0] as MarkdownBlock.ListBlock
        assertEquals(2, list.items.size)
    }

    // ---------- 引用 ----------

    @Test
    fun blockquote_parsesInnerBlocks() {
        val quote = parseMarkdown("> 引用一行")[0] as MarkdownBlock.Blockquote
        assertEquals("引用一行", (quote.blocks[0] as MarkdownBlock.Paragraph).text)
    }

    @Test
    fun nestedBlockquote_nests() {
        val outer = parseMarkdown("> > 深一层")[0] as MarkdownBlock.Blockquote
        val inner = outer.blocks[0] as MarkdownBlock.Blockquote
        assertEquals("深一层", (inner.blocks[0] as MarkdownBlock.Paragraph).text)
    }

    @Test
    fun blockquote_canHoldAList() {
        val quote = parseMarkdown("> - 甲\n> - 乙")[0] as MarkdownBlock.Blockquote
        assertEquals(1, quote.blocks.size)
        assertEquals(2, (quote.blocks[0] as MarkdownBlock.ListBlock).items.size)
    }

    // ---------- 水平线 ----------

    @Test
    fun thematicBreaks_acceptAllThreeMarkers() {
        val blocks = parseMarkdown("---\n***\n___")
        assertEquals(3, blocks.size)
        assertTrue(blocks.all { it is MarkdownBlock.ThematicBreak })
    }

    @Test
    fun threeDashesInAListLine_isAListNotAThematicBreak() {
        val blocks = parseMarkdown("- 项目")
        assertTrue(blocks[0] is MarkdownBlock.ListBlock)
    }

    // ---------- 退化输入 ----------

    @Test
    fun emptyDocument_yieldsNoBlocks() {
        assertEquals(emptyList<MarkdownBlock>(), parseMarkdown(""))
    }

    @Test
    fun whitespaceOnlyDocument_yieldsNoBlocks() {
        assertEquals(emptyList<MarkdownBlock>(), parseMarkdown("   \n\t\n  \n"))
    }

    @Test
    fun newlinesOnly_yieldsNoBlocks() {
        assertEquals(emptyList<MarkdownBlock>(), parseMarkdown("\n\n\n"))
    }

    @Test(timeout = 20_000L)
    fun tenThousandCharSingleLine_doesNotThrow() {
        val source = "字".repeat(10_000)
        val blocks = parseMarkdown(source)
        assertEquals(1, blocks.size)
        assertEquals(10_000, (blocks[0] as MarkdownBlock.Paragraph).text.length)
    }

    @Test(timeout = 20_000L)
    fun pathologicalPipeRow_doesNotBuildThousandsOfColumns() {
        // 整行都是 `| --- |`，列数必须封顶，否则渲染时会造出几千个 Text 卡死主线程
        val cells = (1..300).joinToString("|") { " --- " } + "|"
        val header = (1..300).joinToString("|") { " c$it " } + "|"
        val table = parseMarkdown("$header\n$cells")[0] as MarkdownBlock.TableBlock
        assertEquals(64, table.columnCount)
        // 被折掉的格子是折进最后一格，不是丢掉
        assertTrue(table.header.last().isNotEmpty())
    }

    @Test(timeout = 20_000L)
    fun manyPipeLines_doNotHang() {
        val source = (1..200).joinToString("\n") { "| a $it | b $it |" }
        assertTrue(parseMarkdown(source).isNotEmpty())
    }

    @Test(timeout = 20_000L)
    fun manyBackticks_doNotHang() {
        assertTrue(parseMarkdown("`".repeat(500)).isNotEmpty())
    }

    @Test(timeout = 20_000L)
    fun tenThousandNestedBrackets_doNotHang() {
        assertTrue(parseMarkdown("[".repeat(2_000)).isNotEmpty())
    }

    @Test
    fun crlfLineEndings_areNormalized() {
        val blocks = parseMarkdown("甲\r\n乙")
        assertEquals("甲\n乙", (blocks[0] as MarkdownBlock.Paragraph).text)
    }

    @Test
    fun unclosedBraceInFence_isPreservedVerbatim() {
        val code = parseMarkdown("```\nval x = mapOf(\n```")[0] as MarkdownBlock.CodeBlock
        assertEquals("val x = mapOf(", code.code)
    }

    // ---------- 中文与 emoji ----------

    @Test
    fun chineseParagraph_isOneBlock() {
        val source = "他推开门，屋里没有人。\n桌上那盏灯还亮着，像是特意等人回来。"
        val blocks = parseMarkdown(source)
        assertEquals(1, blocks.size)
        assertEquals(source, (blocks[0] as MarkdownBlock.Paragraph).text)
    }

    @Test
    fun fullWidthPunctuation_doesNotStartABlock() {
        val blocks = parseMarkdown("“这句是引号。”　——作者")
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
    }

    @Test
    fun chineseTableWithEmoji() {
        val blocks = parseMarkdown(
            """
            | 状态 | 备注 |
            | :--- | :--- |
            | ✅ | 很好 😀 |
            | ❌ | 失败 |
            """.trimIndent()
        )
        val table = blocks[0] as MarkdownBlock.TableBlock
        assertEquals(2, table.columnCount)
        assertEquals("✅", table.rows[0][0])
        assertEquals("很好 😀", table.rows[0][1])
    }

    @Test
    fun chineseTaskList() {
        val list = parseMarkdown("- [x] 写完第三章\n- [ ] 打磨文笔")[0] as MarkdownBlock.ListBlock
        assertEquals(listOf(true, false), list.items.map { it.checked })
    }

    @Test
    fun realisticLlmAnswer_parsesIntoExpectedShape() {
        // 一次把标题、表格、代码、列表、引用都塞进去，别只在理想输入上测
        val source = """
            # 第二章 暗线

            她把纸条折好，塞进《雾港》第七页。

            | 线索 | 出处 | 状态 |
            | :--- | :---: | ---: |
            | 铜钥匙 | 第一章 | 已回收 |
            | 船票残片 | 回忆插叙 | 埋伏笔 |

            ```kotlin
            val clue = listOf("铜钥匙", "船票残片")
            clue.forEach { println(it) }
            ```

            - 钥匙开的是灯箱
            - 灯箱里是船票
              - 票号与回忆里的对不上
            - [ ] 下一章揭晓

            > 「雾散之前，别下船。」

            ---
        """.trimIndent()
        val blocks = parseMarkdown(source)
        assertEquals(7, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Heading)
        assertTrue(blocks[1] is MarkdownBlock.Paragraph)
        assertTrue(blocks[2] is MarkdownBlock.TableBlock)
        assertEquals(2, (blocks[2] as MarkdownBlock.TableBlock).rows.size)
        assertEquals("kotlin", (blocks[3] as MarkdownBlock.CodeBlock).language)
        val list = blocks[4] as MarkdownBlock.ListBlock
        assertEquals(3, list.items.size)
        // 第二个项带一个二级子列表
        assertEquals(2, list.items[1].blocks.size)
        assertTrue(list.items[1].blocks[1] is MarkdownBlock.ListBlock)
        // 最后一个是未完成的任务项
        assertEquals(false, list.items[2].checked)
        assertTrue(blocks[5] is MarkdownBlock.Blockquote)
        assertEquals(MarkdownBlock.ThematicBreak, blocks[6])
        assertNotNull(blocks[0])
    }
}
