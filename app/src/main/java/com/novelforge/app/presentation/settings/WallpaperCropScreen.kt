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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 取景框铺满手机画面，比例不能改。用户只能拖动和双指缩放。
 *
 * @param busy 调用方还在处理（存盘写文件），这段时间按钮继续锁着。
 * @param onConfirm 已经是后台上下文，存盘会自己切到 IO 线程。
 */
@Composable
fun WallpaperCropDialog(
    source: Bitmap,
    busy: Boolean = false,
    onCancel: () -> Unit,
    onConfirm: suspend (Bitmap) -> Unit
) {
    val scope = rememberCoroutineScope()
    // 缩放/位移是纯数字，旋转后要接着用；位图由调用方的 ViewModel 扛住旋转
    var scale by rememberSaveable { mutableFloatStateOf(1f) }
    var offsetX by rememberSaveable { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable { mutableFloatStateOf(0f) }
    var frame by remember { mutableStateOf(IntSize.Zero) }
    // 裁剪+存盘是一串几百毫秒的重活，处理期间两个按钮都锁掉，防止连点重复提交
    var cropping by remember { mutableStateOf(false) }
    val processing = cropping || busy
    val offset = remember(offsetX, offsetY) { Offset(offsetX, offsetY) }
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
                            offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                            offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
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
                    TextButton(onClick = onCancel, enabled = !processing) { Text("取消", color = Color.White) }
                    TextButton(
                        onClick = {
                            if (processing || frame.width <= 0 || frame.height <= 0) return@TextButton
                            cropping = true
                            scope.launch {
                                try {
                                    // 分配 1080×2400 ARGB_8888 + 软件 Canvas 整块 blit，
                                    // 放主线程就是几百毫秒的整屏冻结，交给 Default 线程池
                                    val cropped = withContext(Dispatchers.Default) {
                                        renderWallpaper(
                                            source,
                                            frame.width.toFloat(),
                                            frame.height.toFloat(),
                                            scale,
                                            offsetX,
                                            offsetY
                                        )
                                    }
                                    onConfirm(cropped)
                                } finally {
                                    // 协程被旋转取消时也要解锁，否则按钮永久灰着
                                    cropping = false
                                }
                            }
                        },
                        enabled = !processing
                    ) {
                        Text(if (processing) "处理中…" else "使用这张", color = Color.White)
                    }
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
