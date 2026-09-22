package com.novelforge.app.infrastructure.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.OutputStream

data class ExportChapter(
    val orderIndex: Int,
    val title: String,
    val content: String
)

data class SavedTxt(
    val displayName: String,
    /** 展示给用户的保存位置说明 */
    val location: String,
    val uri: Uri
)

class TxtExporter {
    /**
     * 逐章流式写入：超长篇（1000+ 章、数 MB 正文）不把全文拼成单个 String，
     * 避免瞬时堆峰值导致 OOM。调用方需保证运行在 IO 线程。
     */
    fun writeTo(stream: OutputStream, title: String, chapters: List<ExportChapter>) {
        stream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine(title)
            writer.appendLine()
            chapters.sortedBy { it.orderIndex }.forEach { chapter ->
                val heading = listOf(
                    com.novelforge.app.presentation.common.chapterLabel(chapter.orderIndex),
                    com.novelforge.app.presentation.common.cleanChapterTitle(chapter.title)
                ).filter { it.isNotBlank() }.joinToString(" ")
                writer.appendLine(heading)
                writer.appendLine()
                writer.appendLine(chapter.content)
                writer.appendLine()
            }
        }
    }

    private fun safeFileName(title: String): String =
        title.trim().ifBlank { "novelforge" }
            .replace(Regex("[^\\p{L}\\p{N}._-]"), "_")

    /**
     * 默认导出：直接写入系统「下载/NovelForge/」文件夹（Android 10+），
     * 不弹出分享面板；Android 9 及以下退回应用专属外部目录。
     */
    fun saveToPublicDownloads(context: Context, title: String, chapters: List<ExportChapter>): SavedTxt {
        val name = safeFileName(title)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = Environment.DIRECTORY_DOWNLOADS + "/NovelForge"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw IOException("无法写入下载目录")
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                writeTo(stream, title, chapters)
            } ?: throw IOException("无法写入下载目录")
            SavedTxt("$name.txt", "下载/NovelForge", uri)
        } else {
            val dir = File(context.getExternalFilesDir(null), "NovelForge").apply { mkdirs() }
            val file = File(dir, "$name.txt")
            file.outputStream().use { writeTo(it, title, chapters) }
            SavedTxt(file.name, "应用导出目录/NovelForge", fileUri(context, file))
        }
    }

    fun fileUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun writeToCache(context: Context, title: String, chapters: List<ExportChapter>): File {
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        return File(directory, "${safeFileName(title)}.txt").apply {
            outputStream().use { writeTo(it, title, chapters) }
        }
    }

    fun shareUri(context: Context, file: File) = fileUri(context, file)
}
