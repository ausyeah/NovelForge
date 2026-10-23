package com.novelforge.app.presentation.chat.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表格分隔线画在哪一格。
 *
 * 分隔线画在单元格的右边缘（这样就不用 `height(IntrinsicSize.Min)` 给整行
 * 多跑一遍 intrinsic 测量）。代价是条件必须写成"**后面还有格子**" ——
 * 写成 `index > 0`（"前面还有格子"）就会在最后一张格子右边多画一条线，
 * 表格凭空多个右边框。第一版正是这么写错的，所以提成纯函数钉住。
 */
class TableDividerTest {

    @Test
    fun firstCellDoesHaveADivider_whenColumnsFollow() {
        // 线在格子**右**边缘，所以第一格的右边缘就是 0|1 那条列边界 ——
        // 它该有线。有线的只有"右边缘后面还有格子"的那些。
        assertTrue(tableCellShowsDivider(0, 4))
        assertFalse(tableCellShowsDivider(0, 1))
    }

    @Test
    fun lastCellHasNoDivider() {
        // 这条就是"不能写成 index > 0"的意思
        assertFalse(tableCellShowsDivider(3, 4))
    }

    @Test
    fun middleCellsHaveDividers() {
        assertTrue(tableCellShowsDivider(1, 4))
        assertTrue(tableCellShowsDivider(2, 4))
    }

    @Test
    fun exactlyColumnCountMinusOneDividersPerRow() {
        for (columns in 1..8) {
            val drawn = (0 until columns).count { tableCellShowsDivider(it, columns) }
            assertEquals(
                "columns=$columns 画了 $drawn 条分隔线，应为 ${(columns - 1).coerceAtLeast(0)}",
                (columns - 1).coerceAtLeast(0),
                drawn
            )
        }
    }

    @Test
    fun singleColumnTableHasNoDivider() {
        assertFalse(tableCellShowsDivider(0, 1))
    }

    @Test
    fun noDividerEverLandsOnTheRightOuterEdge() {
        // 每一列右边界的数量 == 列数 - 1，且最后一位永远不画
        for (columns in 2..8) {
            assertFalse(
                "columns=$columns 的最后一格居然画了分隔线",
                tableCellShowsDivider(columns - 1, columns)
            )
        }
    }

    @Test
    fun halfStreamedTableKeepsItsShape() {
        // 流式输出时表格是一行行长出来的。列数是解析时补齐的，
        // 所以这里模拟"只收到前两列"和"收到全部三列"两种情况：
        // 分隔线数量必须跟着列数走，不能因为末尾还没到就多画或漏画。
        assertFalse(tableCellShowsDivider(1, 2))
        assertTrue(tableCellShowsDivider(1, 3))
        assertFalse(tableCellShowsDivider(2, 3))
    }

    @Test
    fun outOfRangeIndexNeverDraws() {
        assertFalse(tableCellShowsDivider(-1, 4))
        assertFalse(tableCellShowsDivider(4, 4))
        assertFalse(tableCellShowsDivider(0, 0))
    }
}
