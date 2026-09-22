package com.novelforge.app.presentation.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.novelforge.app.ui.theme.renderWallpaper
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 取景框铺满手机画面，比例不能改。用户只能拖动和双指缩放。
 */
@Composable
fun WallpaperCropDialog(
    source: Bitmap,
    onCancel: () -> Unit,
    onConfirm: (Bitmap) -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var frame by remember { mutableStateOf(IntSize.Zero) }
    val image = remember(source) { source.asImageBitmap() }
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { frame = it }
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(source) {
                        val input = this
                        detectTransformGestures { _, pan, zoom, _ ->
                            val frameW = input.size.width.toFloat()
                            val frameH = input.size.height.toFloat()
                            val nextScale = (scale * zoom).coerceIn(1f, 4f)
                            val cover = max(
                                frameW / source.width.toFloat(),
                                frameH / source.height.toFloat()
                            ) * nextScale
                            val maxX = ((source.width * cover - frameW) / 2f).coerceAtLeast(0f)
                            val maxY = ((source.height * cover - frameH) / 2f).coerceAtLeast(0f)
                            scale = nextScale
                            offset = Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                (offset.y + pan.y).coerceIn(-maxY, maxY)
                            )
                        }
                    }
            ) {
                val cover = max(size.width / source.width, size.height / source.height) * scale
                val displayedWidth = source.width * cover
                val displayedHeight = source.height * cover
                val left = (size.width - displayedWidth) / 2f + offset.x
                val top = (size.height - displayedHeight) / 2f + offset.y
                drawImage(
                    image = image,
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize(displayedWidth.roundToInt(), displayedHeight.roundToInt())
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    "比例已锁定为手机屏幕，只能拖动和缩放。",
                    color = Color.White
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onCancel) { Text("取消", color = Color.White) }
                    TextButton(onClick = {
                        if (frame.width > 0 && frame.height > 0) {
                            onConfirm(
                                renderWallpaper(
                                    source,
                                    frame.width.toFloat(),
                                    frame.height.toFloat(),
                                    scale,
                                    offset.x,
                                    offset.y
                                )
                            )
                        }
                    }) { Text("使用这张", color = Color.White) }
                }
            }
            Text(
                "壁纸",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 12.dp)
            )
        }
    }
}
