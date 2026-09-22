package com.novelforge.app.presentation.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.novelforge.app.presentation.common.PaperTopBar
import com.novelforge.app.data.local.LlmCallDao
import com.novelforge.app.data.local.LlmCallModelSummaryRow
import com.novelforge.app.data.local.LlmCallProjectSummaryRow
import com.novelforge.app.data.local.LlmCallPurposeSummaryRow
import com.novelforge.app.data.local.LlmCallRecentRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerViewModel(dao: LlmCallDao) : ViewModel() {
    private val _rangeDays = MutableStateFlow(0)
    val rangeDays: StateFlow<Int> = _rangeDays.asStateFlow()

    fun setRange(days: Int) { _rangeDays.value = days }

    private val since: StateFlow<Long> = _rangeDays
        .map { days ->
            when {
                days == -1 -> {
                    val cal = java.util.Calendar.getInstance()
                    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                    cal.set(java.util.Calendar.MINUTE, 0)
                    cal.set(java.util.Calendar.SECOND, 0)
                    cal.set(java.util.Calendar.MILLISECOND, 0)
                    cal.timeInMillis
                }
                days <= 0 -> 0L
                else -> System.currentTimeMillis() - days * 86_400_000L
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val models: StateFlow<List<LlmCallModelSummaryRow>> =
        since.flatMapLatest { dao.observeModelSummariesSince(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val projects: StateFlow<List<LlmCallProjectSummaryRow>> =
        since.flatMapLatest { dao.observeProjectSummaries(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val purposes: StateFlow<List<LlmCallPurposeSummaryRow>> =
        since.flatMapLatest { dao.observePurposeSummariesSince(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val usageLog: StateFlow<List<LlmCallRecentRow>> =
        since.flatMapLatest { dao.observeUsageLog(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    class Factory(private val dao: LlmCallDao) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LedgerViewModel(dao) as T
    }
}

@Composable
fun LedgerScreen(viewModel: LedgerViewModel, onBack: () -> Unit) {
    val range by viewModel.rangeDays.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val purposes by viewModel.purposes.collectAsStateWithLifecycle()
    val usageLog by viewModel.usageLog.collectAsStateWithLifecycle()
    val palette = MaterialTheme.colorScheme

    val totalCalls = models.sumOf { it.calls }
    val totalSuccess = models.sumOf { it.successCalls }
    val totalInput = models.sumOf { it.inputTokens }
    val totalOutput = models.sumOf { it.outputTokens }
    val totalCached = models.sumOf { it.cachedInputTokens }
    val totalReasoning = models.sumOf { it.reasoningTokens }
    val totalEstimated = models.sumOf { it.estimatedCalls }
    val totalTokens = totalInput + totalOutput
    val allCalls = purposes.sumOf { it.calls }
    val allSuccess = purposes.sumOf { it.successCalls }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PaperTopBar(title = "用量账本", subtitle = "只统计 Token，不换算费用", onBack = onBack)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(-1 to "今天", 0 to "全部", 7 to "近 7 天", 30 to "近 30 天").forEach { (days, label) ->
                FilterChip(
                    selected = range == days,
                    onClick = { viewModel.setRange(days) },
                    label = { Text(label, style = MaterialTheme.typography.bodySmall) }
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("总 Tokens", style = MaterialTheme.typography.bodySmall)
                    Text(formatTokens(totalTokens), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
                    Text("输入 ${formatTokens(totalInput)}（净 ${formatTokens(totalInput - totalCached)} + 缓存 ${formatTokens(totalCached)}）", style = MaterialTheme.typography.bodySmall)
                    Text("输出 ${formatTokens(totalOutput)}（含思考 ${formatTokens(totalReasoning)}）", style = MaterialTheme.typography.bodySmall)
                    if (totalEstimated > 0) {
                        Text("${totalEstimated}/${totalCalls} 次为估算值（≈）", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("调用", style = MaterialTheme.typography.bodySmall)
                    Text("$allCalls 次", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "成功率 ${if (allCalls > 0) "${allSuccess * 100 / allCalls}%" else "—"}（成功 $allSuccess）",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (models.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("模型使用排行 · 共 ${models.size} 个", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    val maxModel = models.maxOf { it.inputTokens + it.outputTokens }.coerceAtLeast(1L)
                    models.forEach { row ->
                        val total = row.inputTokens + row.outputTokens
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${row.model}（${row.provider}）",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${row.calls} 次 · ${formatTokens(total)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            "入 ${formatTokens(row.inputTokens)}（缓存 ${formatTokens(row.cachedInputTokens)}） · " +
                                "出 ${formatTokens(row.outputTokens)}（思考 ${formatTokens(row.reasoningTokens)}） · " +
                                "成功 ${row.successCalls}/${row.calls}" + if (row.estimatedCalls > 0) " · ≈${row.estimatedCalls}次" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        BarFraction(fraction = total.toFloat() / maxModel, color = palette.primary)
                    }
                }
            }
        }

        if (projects.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("项目分布 · 共 ${projects.size} 本", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    val maxProject = projects.maxOf { it.inputTokens + it.outputTokens }.coerceAtLeast(1L)
                    projects.forEach { row ->
                        val total = row.inputTokens + row.outputTokens
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                row.projectTitle,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${row.calls} 次 · ${formatTokens(total)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        BarFraction(fraction = total.toFloat() / maxProject, color = palette.tertiary)
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val pageSize = 20
                val pageCount = ((usageLog.size + pageSize - 1) / pageSize).coerceAtLeast(1)
                var page by remember(range) { mutableIntStateOf(0) }
                val safePage = page.coerceAtMost(pageCount - 1)
                Text(
                    "使用日志 · 共 ${usageLog.size} 条 · 第 ${safePage + 1}/$pageCount 页",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodySmall
                )
                if (usageLog.isEmpty()) {
                    Text("该时间段没有调用记录", style = MaterialTheme.typography.bodySmall)
                } else {
                    // 固定高度框内滚动，一页 20 条，翻页按钮切换
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        usageLog.drop(safePage * pageSize).take(pageSize).forEach { row ->
                            val total = (row.inputTokens ?: 0) + (row.outputTokens ?: 0)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        timeFormat(row.createdAt),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (row.success) palette.onSurface else palette.error
                                    )
                                    Text(
                                        if (total > 0) formatTokens(total) + if (row.estimated) "≈" else "" else "—",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        row.durationMs?.let { "${it / 1000}s" } ?: "—",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Text(
                                    "${if (row.success) "消耗" else "错误"} · ${purposeLabel(row.purpose)} · " +
                                        "${row.projectTitle.orEmpty()} · ${row.model}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (total > 0) {
                                    val cached = row.cachedInputTokens ?: 0
                                    val reasoning = row.reasoningTokens ?: 0
                                    Text(
                                        "入 ${formatTokens(row.inputTokens ?: 0)}" +
                                            if (cached > 0) "（缓存 ${formatTokens(cached)}）" else "" +
                                            " · 出 ${formatTokens(row.outputTokens ?: 0)}" +
                                            if (reasoning > 0) "（思考 ${formatTokens(reasoning)}）" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { page = (safePage - 1).coerceAtLeast(0) },
                            enabled = safePage > 0
                        ) { Text("〈 上一页") }
                        OutlinedButton(
                            onClick = { page = (safePage + 1).coerceAtMost(pageCount - 1) },
                            enabled = safePage < pageCount - 1
                        ) { Text("下一页 〉") }
                    }
                }
            }
        }

    }
}

@Composable
private fun BarFraction(fraction: Float, color: Color) {
    val f = fraction.coerceIn(0.002f, 0.998f)
    Row(modifier = Modifier.fillMaxWidth().height(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight(f)
                .background(color, RoundedCornerShape(4.dp))
        )
        Box(modifier = Modifier.fillMaxHeight().weight(1f - f))
    }
}

private fun formatTokens(value: Long): String = when {
    value >= 100_000_000 -> "%.1f亿".format(value / 1e8)
    value >= 10_000 -> "%.1f万".format(value / 10_000.0)
    else -> "$value"
}

private fun purposeLabel(purpose: String): String = when (purpose) {
    "OUTLINE" -> "大纲"
    "CHAPTER" -> "正文"
    "QUALITY_CHECK" -> "质检"
    "REPAIR" -> "修复"
    "CHAT" -> "对话"
    else -> purpose
}

private fun timeFormat(epochMs: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(epochMs))
