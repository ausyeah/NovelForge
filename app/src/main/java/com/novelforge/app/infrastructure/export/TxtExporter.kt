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
    fun render(title: String, chapters: List<ExportChapter>): String = buildString {
        appendLine(title)
        appendLine()
        chapters.sortedBy { it.orderIndex }.forEach { chapter ->
            appendLine("第${chapter.orderIndex + 1}章 ${chapter.title}")
            appendLine()
            appendLine(chapter.content)
            appendLine()
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
        val text = render(title, chapters)
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
                stream.write(text.toByteArray(Charsets.UTF_8))
            } ?: throw IOException("无法写入下载目录")
            SavedTxt("$name.txt", "下载/NovelForge", uri)
        } else {
            val dir = File(context.getExternalFilesDir(null), "NovelForge").apply { mkdirs() }
            val file = File(dir, "$name.txt").apply {
                writeText(text, Charsets.UTF_8)
            }
            SavedTxt(file.name, "应用导出目录/NovelForge", fileUri(context, file))
        }
    }

    fun fileUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun writeToCache(context: Context, title: String, chapters: List<ExportChapter>): File {
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        return File(directory, "${safeFileName(title)}.txt").apply {
            writeText(render(title, chapters), Charsets.UTF_8)
        }
    }

    fun shareUri(context: Context, file: File) = fileUri(context, file)
}
