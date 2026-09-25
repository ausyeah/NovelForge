package com.novelforge.app.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.util.DisplayMetrics
import androidx.annotation.WorkerThread
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val WALLPAPER_FILE_NAME = "wallpaper.jpg"

/**
 * renderWallpaper 输出的最长边上限。壁纸只是铺在手机屏幕后面的底图，
 * 2.5K 足够，再大只是白烧内存和压缩时间。
 */
const val WALLPAPER_RENDER_MAX_SIDE = 2400

fun wallpaperFile(context: Context): File = File(context.filesDir, WALLPAPER_FILE_NAME)

/**
 * 读回壁纸时允许的最大边长，从真实屏幕尺寸推导。
 *
 * 旧代码写死 `> 1440 || > 2560`：在 1440×3120 这类高密度机上，存进去的 1080×2400
 * 壁纸会因为 3120 > 2560 被再降一次采样（inSampleSize=2 → 720×1560），
 * 满屏铺开就是糊的。下限取 [WALLPAPER_RENDER_MAX_SIDE]，保证自己写出去的文件
 * 永远按原分辨率读回来；屏幕比它还小时也不能再降。
 */
fun wallpaperDecodeMaxSide(metrics: DisplayMetrics): Int =
    max(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(WALLPAPER_RENDER_MAX_SIDE)

fun wallpaperDecodeMaxSide(context: Context): Int =
    wallpaperDecodeMaxSide(context.resources.displayMetrics)

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

/**
 * 按取景框裁剪并缩放成最终壁纸。**必须在后台线程调用**：
 * 1080×2400 的 ARGB_8888 分配加上软件 Canvas 整块 blit，实测几百毫秒，
 * 放在点击回调里就是整屏冻结。
 */
@WorkerThread
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
    val outHeight = min(frameHeight.roundToInt(), WALLPAPER_RENDER_MAX_SIDE).coerceAtLeast(1)
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

@WorkerThread
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
 *
 * 这里的 current 是进程级常驻（Application 的 by lazy 单例），所以塞进去的位图能有多大，
 * 就等于常驻内存有多大。
 */
class WallpaperStore(private val context: Context) {
    private val _current = MutableStateFlow<ImageBitmap?>(null)
    val current: StateFlow<ImageBitmap?> = _current.asStateFlow()

    /** 冷启动读盘。调用方（Application 的 appScope）已经不在主线程。 */
    fun loadSaved() {
        _current.value = decodeWallpaper(wallpaperFile(context), wallpaperDecodeMaxSide(context))
    }

    /**
     * 存盘：压一张 10MB 的 JPEG 再写文件是几百毫秒的纯 IO + CPU 活，
     * 必须在 [Dispatchers.IO] 上做，否则每次换壁纸都整屏卡一下。
     *
     * 先写临时文件再改名：同卷 rename 是原子的，中途被杀/被旋转取消都不会留下半张坏图。
     */
    suspend fun publish(bitmap: Bitmap) {
        val decoded = withContext(Dispatchers.IO) {
            val file = wallpaperFile(context)
            val temp = File(file.parentFile, "$WALLPAPER_FILE_NAME.tmp")
            runCatching {
                temp.outputStream().use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                }
                temp.renameTo(file)
            }.onFailure { runCatching { temp.delete() } }
            // 内存里只留按屏幕尺寸重解的那份（RGB_565，约 5MB 而不是 10MB），
            // 而且和冷启动读盘的结果完全一致：不会出现「刚设好是清晰图，重启后变糊」
            decodeWallpaper(file, wallpaperDecodeMaxSide(context))
        }
        _current.value = decoded
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) { runCatching { wallpaperFile(context).delete() } }
        _current.value = null
    }
}

@WorkerThread
private fun decodeWallpaper(file: File, maxSide: Int): ImageBitmap? = runCatching {
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        // 壁纸永远整屏铺在不透明纸色遮罩后面，一个 alpha 通道都用不上。
        // 不指定就是 ARGB_8888：1080×2400 白占 10.3MB，而这是进程级常驻的单例，
        // RGB_565 直接砍到 5.2MB。同理 inScaled 交给渲染层处理，不在这里缩。
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
}.getOrNull()
