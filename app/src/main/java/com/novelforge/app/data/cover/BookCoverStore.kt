package com.novelforge.app.data.cover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.novelforge.app.ui.theme.decodeSampledBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private val Context.bookCoverDataStore by preferencesDataStore(name = "book_covers")

/** 一本书的封面：要么是预设颜色，要么是用户自己挑的图。 */
sealed interface BookCover {
    /** 没设置过：按 projectId 的哈希取一个稳定的颜色，保持书架原有观感。 */
    data object Default : BookCover
    data class Preset(val index: Int) : BookCover
    data class Image(val fileName: String) : BookCover
}

/**
 * 封面存哪。
 *
 * 刻意**不动 Room**：`Project` 加字段就要一次 schema 迁移，而封面只是装饰。
 * 所以用「按 projectId 的 DataStore 条目 + 文件名由 projectId 推导」这一套，
 * 和 ReadingPositionStore / AutoRunStore 是同一个路子。
 *
 * 文件名是推导出来的（`cover-<projectId>.jpg`），不是单独存的字符串 ——
 * 少一处会不同步的状态，删书时也只需要按 id 删一次。
 */
class BookCoverStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "covers").apply { if (!exists()) mkdirs() }

    fun observe(projectId: String): Flow<BookCover> = context.bookCoverDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences -> decode(preferences[key(projectId)]) }

    suspend fun setPreset(projectId: String, index: Int) {
        context.bookCoverDataStore.edit { it[key(projectId)] = "c:$index" }
    }

    suspend fun setImageFrom(
        projectId: String,
        uri: Uri,
        maxSide: Int = COVER_MAX_SIDE
    ): Boolean = withContext(Dispatchers.IO) {
        val decoded = decodeSampledBitmap(context, uri) ?: return@withContext false
        // 原图可能是横的。封面是竖版（0.72），这里居中裁一下再存，
        // 否则书架上会出现被压扁的书封。
        val cropped = centerCropToPortrait(decoded, maxSide)
        if (cropped !== decoded) decoded.recycle()
        val target = fileFor(projectId)
        // 先写临时文件再改名：写到一半被杀不会留下一个半张的封面，
        // 而书架会把它当有效封面加载出来。
        val temp = File(target.parentFile, target.name + ".tmp")
        val ok = runCatching {
            temp.outputStream().use { out ->
                cropped.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
        }.isSuccess
        cropped.recycle()
        if (ok) {
            runCatching { temp.renameTo(target) }
            context.bookCoverDataStore.edit { it[key(projectId)] = "i:${target.name}" }
        } else {
            runCatching { temp.delete() }
        }
        ok
    }

    /** 删书时一并清掉封面，否则 files/covers 会越积越多，而项目 id 是会复用的。 */
    suspend fun clear(projectId: String) {
        withContext(Dispatchers.IO) { runCatching { fileFor(projectId).delete() } }
        context.bookCoverDataStore.edit { it.remove(key(projectId)) }
    }

    fun fileFor(projectId: String): File = File(dir, "cover-$projectId.jpg")

    /** 书架网格用的缩略图。解码在 IO 上，绝不在组合期读整张图。 */
    suspend fun thumbnail(projectId: String, maxSide: Int = THUMB_MAX_SIDE): Bitmap? =
        withContext(Dispatchers.IO) {
            val file = fileFor(projectId)
            if (!file.exists()) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0) return@withContext null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) {
                sample *= 2
            }
            runCatching {
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                )
            }.getOrNull()
        }

    private fun decode(raw: String?): BookCover = when {
        raw.isNullOrBlank() -> BookCover.Default
        raw.startsWith("c:") -> raw.removePrefix("c:").toIntOrNull()
            ?.let { BookCover.Preset(it) } ?: BookCover.Default
        raw.startsWith("i:") -> BookCover.Image(raw.removePrefix("i:"))
        else -> BookCover.Default
    }

    private fun key(projectId: String) = stringPreferencesKey("cover_$projectId")

    private companion object {
        const val COVER_MAX_SIDE = 900
        const val THUMB_MAX_SIDE = 360
    }
}

/**
 * 裁剪方案：从 source 里取哪一块、缩到多大。
 *
 * 单独拆出来是因为这部分是纯算术，可以在 JVM 上直接测 —— 而
 * `centerCropToPortrait` 要 Bitmap，得上设备。测试必须测**同一份**计算，
 * 所以这里是唯一的真相来源。
 */
internal data class PortraitCropPlan(
    val left: Int,
    val top: Int,
    val cropWidth: Int,
    val cropHeight: Int,
    val outWidth: Int,
    val outHeight: Int
)

/** 封面比例：宽 : 高。0.72 接近常见开本，也和书架格子的 aspectRatio 一致。 */
internal const val COVER_ASPECT = 0.72f

internal fun portraitCropPlan(
    sourceWidth: Int,
    sourceHeight: Int,
    maxSide: Int
): PortraitCropPlan {
    // 退化输入兜底：0 宽高会让 createBitmap/createScaledBitmap 直接抛
    val safeWidth = sourceWidth.coerceAtLeast(1)
    val safeHeight = sourceHeight.coerceAtLeast(1)
    val sourceRatio = safeWidth.toFloat() / safeHeight
    val cropWidth: Int
    val cropHeight: Int
    if (sourceRatio > COVER_ASPECT) {
        // 源图相对太宽：裁掉左右
        cropHeight = safeHeight
        // 必须四舍五入而不是截断：0.72 在二进制浮点里是 0.7199999...，
        // 1000 * 0.72 截断成 719，720 / 0.72f 截断成 999 ——
        // 一张刚好 0.72 的竖图会被白裁掉一个像素，而且比例会随尺寸漂移。
        cropWidth = (cropHeight * COVER_ASPECT).roundToInt().coerceIn(1, safeWidth)
    } else {
        // 源图相对太高：裁掉上下
        cropWidth = safeWidth
        cropHeight = (cropWidth / COVER_ASPECT).roundToInt().coerceIn(1, safeHeight)
    }
    val outWidth: Int
    val outHeight: Int
    val longest = maxOf(cropWidth, cropHeight)
    if (longest <= maxSide) {
        // 只缩不放。放大一张 180px 的图只会糊，还白占空间
        outWidth = cropWidth
        outHeight = cropHeight
    } else {
        val scale = maxSide.toFloat() / longest
        outWidth = (cropWidth * scale).roundToInt().coerceAtLeast(1)
        outHeight = (cropHeight * scale).roundToInt().coerceAtLeast(1)
    }
    return PortraitCropPlan(
        left = (safeWidth - cropWidth) / 2,
        top = (safeHeight - cropHeight) / 2,
        cropWidth = cropWidth,
        cropHeight = cropHeight,
        outWidth = outWidth,
        outHeight = outHeight
    )
}

/** 按 [portraitCropPlan] 裁剪并缩放。返回的 bitmap 由调用方回收。 */
internal fun centerCropToPortrait(source: Bitmap, maxSide: Int): Bitmap {
    val plan = portraitCropPlan(source.width, source.height, maxSide)
    val cropped = Bitmap.createBitmap(source, plan.left, plan.top, plan.cropWidth, plan.cropHeight)
    if (plan.outWidth == plan.cropWidth && plan.outHeight == plan.cropHeight) return cropped
    val scaled = Bitmap.createScaledBitmap(cropped, plan.outWidth, plan.outHeight, true)
    if (scaled !== cropped) cropped.recycle()
    return scaled
}
