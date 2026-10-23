package com.novelforge.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** 统一圆角：纸质风格用小圆角，接近卡片纸片 */
val PaperShape = RoundedCornerShape(10.dp)

/**
 * 纸质按钮：默认细描边卡片底；accent 用主色实底。
 * 颜色全部走 colorScheme，明暗两套纸质主题自动适配。
 */
@Composable
fun PaperButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    val fill = if (accent) scheme.primary else scheme.surface
    val line = if (accent) scheme.primary else scheme.outlineVariant
    val textColor = if (accent) scheme.onPrimary else scheme.onSurface
    Box(
        modifier = modifier
            .clip(PaperShape)
            .background(fill)
            .border(1.dp, line, PaperShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) textColor else textColor.copy(alpha = 0.45f),
            style = MaterialTheme.typography.titleMedium
        )
    }
}

/** 纸质卡片容器：卡片底 + 细描边 */
@Composable
fun PaperSurface(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val click = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Box(
        modifier = modifier
            .clip(PaperShape)
            .background(scheme.surface)
            .border(1.dp, scheme.outlineVariant, PaperShape)
            .then(click)
    ) {
        content()
    }
}
