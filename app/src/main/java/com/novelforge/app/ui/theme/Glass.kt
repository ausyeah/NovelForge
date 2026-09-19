package com.novelforge.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable

/** 统一圆角 */
val GlassShape = RoundedCornerShape(26.dp)

private val GlassFill = Brush.linearGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.78f),
        Color(0xFFF4F1FF).copy(alpha = 0.62f),
        Color(0xFFEDE7FF).copy(alpha = 0.55f)
    ),
    start = Offset.Zero,
    end = Offset.Infinite
)

private val GlassBorder = Brush.linearGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.95f),
        Color(0xFFD9CFFF).copy(alpha = 0.65f),
        Color.White.copy(alpha = 0.85f)
    ),
    start = Offset(0f, 0f),
    end = Offset.Infinite
)

/** 主按钮的流光描边填充 */
private val AccentFill = Brush.linearGradient(
    colors = listOf(
        Color(0xFF8B6CF0).copy(alpha = 0.92f),
        Color(0xFFA78BFA).copy(alpha = 0.86f),
        Color(0xFFC4B0FF).copy(alpha = 0.88f)
    ),
    start = Offset.Zero,
    end = Offset.Infinite
)

/**
 * 流光玻璃质感按钮：半透明渐变填充 + 高光描边 + 柔和投影，白色背景下层次分明。
 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    enabled: Boolean = true
) {
    val fill = if (accent) AccentFill else GlassFill
    val textColor = if (accent) Color.White else Color(0xFF3A3355)
    Box(
        modifier = modifier
            .shadow(elevation = 8.dp, shape = GlassShape, ambientColor = Color(0xFF8B6CF0), spotColor = Color(0xFF8B6CF0))
            .clip(GlassShape)
            .background(fill)
            .border(1.2.dp, GlassBorder, GlassShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) textColor else textColor.copy(alpha = 0.45f),
            style = MaterialTheme.typography.titleMedium
        )
    }
}

/** 玻璃卡片容器 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(GlassShape)
            .background(GlassFill)
            .border(1.dp, GlassBorder, GlassShape)
    ) {
        content()
    }
}
