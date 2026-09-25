package com.novelforge.app.presentation.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 统计区间选项固定不变，放顶层避免每次重组重新分配这 4 个 Pair。 */
private val RANGE_OPTIONS = listOf(-1 to "今天", 0 to "全部", 7 to "近 7 天", 30 to "近 30 天")

/** 使用日志一页 20 条，翻页按钮切换。 */
private const val LOG_PAGE_SIZE = 20

/**
 * 日志一屏 20 行，timeFormat 每次重组都被调 20 次；SimpleDateFormat 每次都要新建（且非线程安全），
 * 换成可复用、线程安全的 DateTimeFormatter，只在类加载时建一次。
 */
private val TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault())

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

    // stateIn 的初值是空列表，和「这段时间真的没有调用」在界面上长得一模一样；
    // 单独一个标志把「还在读库」和「确实没有记录」分开，进页面不再先闪一句空态。
    // 切换区间不重置：那时旧数据仍在屏上，重置只会闪一下。
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

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
            .onEach { _loaded.value = true }
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
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val palette = MaterialTheme.colorScheme

    // 十几次 sumOf/maxOf 原本每次重组都重跑一遍，父级任意状态变化都要白算；remember 住这些派生值
    val totals = remember(models, projects, purposes) { LedgerTotals(models, projects, purposes) }
    val pageCount = remember(usageLog.size) {
        ((usageLog.size + LOG_PAGE_SIZE - 1) / LOG_PAGE_SIZE).coerceAtLeast(1)
    }
    // 翻页状态放页面级：LazyColumn 会回收滑出视口的 item，留在日志卡片里滑走再回来就被重置回第 1 页
    var page by remember(range) { mutableIntStateOf(0) }
    val safePage = page.coerceAtMost(pageCount - 1)

    // 整页只保留一个竖向滚动容器：外层 verticalScroll 叠内层滚动框会互相抢手势，
    // 内层吃掉 fling 后整页卡住滑不动（同 SettingsScreen 踩过的坑），改成单层 LazyColumn
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            PaperTopBar(title = "用量账本", subtitle = "只统计 Token，不换算费用", onBack = onBack)
        }
        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RANGE_OPTIONS.forEach { (days, label) ->
                    // FilterChip 视觉高度只有 32dp，低于 48dp 最小可点区域；
                    // 外层 Box 撑到 48dp 并居中，避免文字贴到胶囊顶边
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            // 单读「今天」不知道筛的是什么，补一句读屏文案
                            .semantics { contentDescription = "统计范围：$label" },
                        contentAlignment = Alignment.Center
                    ) {
                        FilterChip(
                            selected = range == days,
                            onClick = { viewModel.setRange(days) },
                            label = { Text(label, style = MaterialTheme.typography.bodySmall) }
                        )
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!loaded) {
                        // 读库完成前不摆 0 和「—」：那不是统计结果，是还没算出来
                        Text(
                            "正在统计这段时间的用量…",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.onSurfaceVariant
                        )
                    } else {
                        Column(modifier = Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("总 Tokens", style = MaterialTheme.typography.bodySmall)
                            Text(formatTokens(totals.totalTokens), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
                            Text("输入 ${formatTokens(totals.totalInput)}（净 ${formatTokens(totals.totalInput - totals.totalCached)} + 缓存 ${formatTokens(totals.totalCached)}）", style = MaterialTheme.typography.bodySmall)
                            Text("输出 ${formatTokens(totals.totalOutput)}（含思考 ${formatTokens(totals.totalReasoning)}）", style = MaterialTheme.typography.bodySmall)
                            if (totals.totalEstimated > 0) {
                                Text("${totals.totalEstimated}/${totals.totalCalls} 次为估算值（≈）", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("调用", style = MaterialTheme.typography.bodySmall)
                            Text("${totals.allCalls} 次", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "成功率 ${if (totals.allCalls > 0) "${totals.allSuccess * 100 / totals.allCalls}%" else "—"}（成功 ${totals.allSuccess}）",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
        if (models.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("模型使用排行 · 共 ${models.size} 个", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        models.forEach { row -> ModelUsageRow(row, totals.maxModelTokens) }
                    }
                }
            }
        }
        if (projects.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("项目分布 · 共 ${projects.size} 本", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        projects.forEach { row -> ProjectUsageRow(row, totals.maxProjectTokens) }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        // 读库完成前不报「共 0 条 · 第 1/1 页」：那会先闪一个假的空态
                        if (loaded) "使用日志 · 共 ${usageLog.size} 条 · 第 ${safePage + 1}/$pageCount 页" else "使用日志",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (!loaded) {
                        Text("正在读取调用记录…", style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
                    } else if (usageLog.isEmpty()) {
                        Text("该时间段没有调用记录", style = MaterialTheme.typography.bodySmall)
                    } else {
                        // 固定高度框内滚动，一页 20 条，翻页按钮切换
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(420.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            usageLog.drop(safePage * LOG_PAGE_SIZE).take(LOG_PAGE_SIZE).forEach { row ->
                                UsageLogRow(row)
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
}

/** 模型排行的一行：拆成独立 composable，输入不变时这一行可以跳过重组。 */
@Composable
private fun ModelUsageRow(row: LlmCallModelSummaryRow, maxTokens: Long) {
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
    BarFraction(fraction = total.toFloat() / maxTokens, color = MaterialTheme.colorScheme.primary)
}

/** 项目分布的一行。 */
@Composable
private fun ProjectUsageRow(row: LlmCallProjectSummaryRow, maxTokens: Long) {
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
    BarFraction(fraction = total.toFloat() / maxTokens, color = MaterialTheme.colorScheme.tertiary)
}

/** 使用日志的一行；耗时与 Token 缺失时显示「—」，不暴露底层异常文本。 */
@Composable
private fun UsageLogRow(row: LlmCallRecentRow) {
    val palette = MaterialTheme.colorScheme
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

/** 汇总与排行分母：一次遍历算完，替代散在页面里的十几次 sumOf/maxOf。 */
private class LedgerTotals(
    models: List<LlmCallModelSummaryRow>,
    projects: List<LlmCallProjectSummaryRow>,
    purposes: List<LlmCallPurposeSummaryRow>
) {
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
    // 条形图分母取最大行；空列表兜 1 避免除零
    val maxModelTokens = models.maxOfOrNull { it.inputTokens + it.outputTokens }?.coerceAtLeast(1L) ?: 1L
    val maxProjectTokens = projects.maxOfOrNull { it.inputTokens + it.outputTokens }?.coerceAtLeast(1L) ?: 1L
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

private fun timeFormat(epochMs: Long): String = TIME_FORMATTER.format(Instant.ofEpochMilli(epochMs))
