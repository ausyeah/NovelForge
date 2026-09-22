package com.novelforge.app.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val WALLPAPER_FILE_NAME = "wallpaper.jpg"

fun wallpaperFile(context: Context): File = File(context.filesDir, WALLPAPER_FILE_NAME)

/** 取景框在原图上的像素范围。宽高比与手机画面一致，调用方不能改这个比例。 */
data class WallpaperCropRect(val x: Int, val y: Int, val width: Int, val height: Int)

fun wallpaperCropRect(
    imageWidth: Int,
    imageHeight: Int,
    frameWidth: Float,
    frameHeight: Float,
    userScale: Float,
    offsetX: Float,
    offsetY: Float
): WallpaperCropRect {
    val safeScale = userScale.coerceAtLeast(1f)
    val cover = max(frameWidth / imageWidth, frameHeight / imageHeight) * safeScale
    val displayedWidth = imageWidth * cover
    val displayedHeight = imageHeight * cover
    val maxX = ((displayedWidth - frameWidth) / 2f).coerceAtLeast(0f)
    val maxY = ((displayedHeight - frameHeight) / 2f).coerceAtLeast(0f)
    val clampedX = offsetX.coerceIn(-maxX, maxX)
    val clampedY = offsetY.coerceIn(-maxY, maxY)
    val left = (frameWidth - displayedWidth) / 2f + clampedX
    val top = (frameHeight - displayedHeight) / 2f + clampedY
    val srcX = ((0f - left) / cover).roundToInt().coerceIn(0, imageWidth - 1)
    val srcY = ((0f - top) / cover).roundToInt().coerceIn(0, imageHeight - 1)
    val srcW = (frameWidth / cover).roundToInt().coerceAtLeast(1)
    val srcH = (frameHeight / cover).roundToInt().coerceAtLeast(1)
    val width = min(srcW, imageWidth - srcX)
    val height = min(srcH, imageHeight - srcY)
    return WallpaperCropRect(srcX, srcY, width, height)
}

fun renderWallpaper(
    source: Bitmap,
    frameWidth: Float,
    frameHeight: Float,
    userScale: Float,
    offsetX: Float,
    offsetY: Float
): Bitmap {
    val crop = wallpaperCropRect(
        source.width, source.height, frameWidth, frameHeight, userScale, offsetX, offsetY
    )
    val ratio = frameWidth / frameHeight
    val outHeight = min(frameHeight.roundToInt(), 2400).coerceAtLeast(1)
    val outWidth = (outHeight * ratio).roundToInt().coerceAtLeast(1)
    val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
    Canvas(output).drawBitmap(
        source,
        Rect(crop.x, crop.y, crop.x + crop.width, crop.y + crop.height),
        Rect(0, 0, outWidth, outHeight),
        null
    )
    return output
}

fun decodeSampledBitmap(context: Context, uri: Uri, maxSide: Int = 2560): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}.getOrNull()

/**
 * 壁纸位图的唯一来源。文件名始终是 wallpaper.jpg，不能靠文件名变化来刷新界面。
 */
class WallpaperStore(private val context: Context) {
    private val _current = MutableStateFlow<ImageBitmap?>(null)
    val current: StateFlow<ImageBitmap?> = _current.asStateFlow()

    fun loadSaved() {
        _current.value = decodeWallpaper(wallpaperFile(context))
    }

    fun publish(bitmap: Bitmap) {
        runCatching {
            wallpaperFile(context).outputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            }
        }
        _current.value = bitmap.asImageBitmap()
    }

    fun clear() {
        runCatching { wallpaperFile(context).delete() }
        _current.value = null
    }
}

private fun decodeWallpaper(file: File): ImageBitmap? = runCatching {
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 1440 || bounds.outHeight / sample > 2560) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
}.getOrNull()
