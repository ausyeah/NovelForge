package com.novelforge.app.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 各页统一顶栏：返回在左，标题左对齐，避免「〈 返回」把标题挤出屏幕。 */
@Composable
fun PaperTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            Text(
                text = "返回",
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    // 全应用每一页都在用的返回入口，原来只有约 30×36dp，
                    // 两个方向都低于 48dp 最小点击区，胖手指/单手握持基本点不中。
                    // 补到 48dp 见方；代价只是标题左边多空出十几 dp。
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable(onClick = onBack)
                    // Text + clickable 默认没有 button 角色，TalkBack 只念一句「返回」
                    // 像是静态文字；contentDescription 保留可见文案再补足语义（WCAG 2.5.3）
                    .semantics {
                        role = Role.Button
                        contentDescription = "返回上一页"
                    }
                    .padding(vertical = 8.dp, horizontal = 2.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (onBack != null) 12.dp else 0.dp, end = 8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun StatusChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        maxLines = 1
    )
}

@Composable
fun PaperMessage(
    text: String,
    modifier: Modifier = Modifier,
    detail: String? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        if (!detail.isNullOrBlank()) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

fun formatUpdatedAgo(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val delta = (now - epochMillis).coerceAtLeast(0L)
    return when {
        delta < 60_000L -> "刚刚更新"
        delta < 3_600_000L -> "${delta / 60_000L} 分钟前更新"
        delta < 86_400_000L -> "${delta / 3_600_000L} 小时前更新"
        delta < 30L * 86_400_000L -> "${delta / 86_400_000L} 天前更新"
        else -> "较早前更新"
    }
}
