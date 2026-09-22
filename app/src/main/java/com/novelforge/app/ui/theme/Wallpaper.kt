package com.novelforge.app.ui.theme

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

const val WALLPAPER_FILE_NAME = "wallpaper.jpg"

fun wallpaperFile(context: Context): File = File(context.filesDir, WALLPAPER_FILE_NAME)

fun copyWallpaper(context: Context, uri: Uri): Boolean = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        wallpaperFile(context).outputStream().use { output -> input.copyTo(output) }
    } ?: error("无法读取所选图片")
    true
}.getOrDefault(false)

fun clearWallpaper(context: Context) {
    runCatching { wallpaperFile(context).delete() }
}

@Composable
fun rememberWallpaper(file: File?): ImageBitmap? {
    val existing = file?.takeIf { it.exists() }
    val path = existing?.absolutePath
    val stamp = existing?.lastModified() ?: 0L
    return remember(path, stamp) {
        if (path == null) null else decodeWallpaper(File(path))
    }
}

private fun decodeWallpaper(file: File): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 1440 || bounds.outHeight / sample > 2560) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
}.getOrNull()
