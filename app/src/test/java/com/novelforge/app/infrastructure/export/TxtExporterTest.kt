package com.novelforge.app.infrastructure.export

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtExporterTest {
    @Test
    fun writeToSortsByOutlineOrderAndKeepsChineseContent() {
        val stream = ByteArrayOutputStream()
        TxtExporter().writeTo(
            stream = stream,
            title = "我的小说",
            chapters = listOf(
                ExportChapter(1, "后续", "第二段"),
                ExportChapter(0, "开端", "第一段")
            )
        )
        val text = stream.toString(Charsets.UTF_8.name())

        assertTrue(text.indexOf("第一段") < text.indexOf("第二段"))
        assertTrue(text.contains("引子 开端"))
        assertTrue(text.contains("第 1 章 后续"))
        assertTrue(text.contains("我的小说"))
    }

    @Test
    fun writeToStripsNumberingTheModelAlreadyPutInTheTitle() {
        val stream = ByteArrayOutputStream()
        TxtExporter().writeTo(
            stream = stream,
            title = "我的小说",
            chapters = listOf(
                ExportChapter(0, "引子：开端", "第一段"),
                ExportChapter(1, "第1章 后续", "第二段")
            )
        )
        val text = stream.toString(Charsets.UTF_8.name())

        assertTrue(text.contains("引子 开端"))
        assertTrue(text.contains("第 1 章 后续"))
        assertTrue(!text.contains("引子 引子"))
        assertTrue(!text.contains("第 1 章 第1章"))
    }

    /**
     * 必须直接查原始字节：测试里现有的 `toString(UTF_8)` 不会把 BOM 吃掉，
     * 读出来的只是「正文前面多了一个 U+FEFF」，正好证明 BOM 真的写进了文件。
     */
    @Test
    fun writeToStartsWithUtf8BomSoWindowsNotepadReadsChinese() {
        val stream = ByteArrayOutputStream()
        TxtExporter().writeTo(
            stream = stream,
            title = "我的小说",
            chapters = listOf(ExportChapter(0, "开端", "第一段"))
        )
        val bytes = stream.toByteArray()

        assertArrayEquals(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()),
            bytes.copyOfRange(0, 3)
        )
        // BOM 之后紧跟书名，说明 BOM 没有把开头内容顶掉
        val afterBom = String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        assertTrue(afterBom.startsWith("我的小说"))
        assertTrue(afterBom.contains("第一段"))
    }

    /** 只有一个 BOM：writer 换一次不能写出两遍 */
    @Test
    fun writeToWritesBomOnlyOnceForEmptyChapterList() {
        val stream = ByteArrayOutputStream()
        TxtExporter().writeTo(stream = stream, title = "空书", chapters = emptyList())
        val bytes = stream.toByteArray()

        assertArrayEquals(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()),
            bytes.copyOfRange(0, 3)
        )
        assertEquals(1, bytes.count { it == 0xEF.toByte() })
    }
}
