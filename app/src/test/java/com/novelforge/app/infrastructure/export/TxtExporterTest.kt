package com.novelforge.app.infrastructure.export

import java.io.ByteArrayOutputStream
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
}
