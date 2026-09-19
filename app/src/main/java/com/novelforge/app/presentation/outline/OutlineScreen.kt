package com.novelforge.app.presentation.outline

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novelforge.app.domain.model.GenerationJob
import com.novelforge.app.domain.model.GenerationJobStatus
import com.novelforge.app.domain.model.OutlineItem
import com.novelforge.app.domain.model.OutlineVersion
import com.novelforge.app.infrastructure.llm.JsonResponseValidator
import com.novelforge.app.infrastructure.llm.JsonValidationResult
import com.novelforge.app.presentation.common.GenerationStatusCard
import com.novelforge.app.presentation.common.chapterLabel
import com.novelforge.app.presentation.common.cleanChapterTitle

/** 存在中间产出、允许“修复 / 重试”的任务状态 */
private val REPAIRABLE_STATUSES = setOf(
    GenerationJobStatus.NEEDS_USER,
    GenerationJobStatus.FAILED,
    GenerationJobStatus.RECOVERABLE_PARTIAL,
    GenerationJobStatus.CANCELLED
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OutlineScreen(
    projectTitle: String,
    versions: List<OutlineVersion>,
    job: GenerationJob?,
    chapterJob: GenerationJob?,
    writtenChapterIds: Set<String>,
    error: String?,
    autoRun: Boolean,
    onToggleAutoRun: (Boolean) -> Unit,
    onStartAutoRun: () -> Unit,
    onGenerate: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onSave: (List<OutlineItem>) -> Unit,
    onSaveRaw: (String) -> Unit,
    onOpenChapter: (OutlineItem) -> Unit,
    onClearError: () -> Unit,
    onBack: () -> Unit,
    plannedChapterCount: Int? = null
) {
    val outline = versions.firstOrNull()
    val validator = remember { JsonResponseValidator() }
    val savedChapterCount = remember(job?.partialContent) {
        when (val result = job?.partialContent?.takeIf(String::isNotBlank)?.let(validator::parseOutline)) {
            is JsonValidationResult.Success -> result.value.size
            else -> 0
        }
    }
    val chapterProgress = if (
        job?.status == GenerationJobStatus.RUNNING || job?.status == GenerationJobStatus.QUEUED
    ) {
        plannedChapterCount?.let { total ->
            val currentChapter = (savedChapterCount + 1).coerceAtMost(total.coerceAtLeast(1))
            "正在生成本批大纲（第 $currentChapter 章起） · 已生成 $savedChapterCount/$total 章"
        }
    } else {
        null
    }
    var draftItems by remember(outline?.id) {
        mutableStateOf(outline?.chapters.orEmpty())
    }
    var detailIndex by remember(outline?.id) { mutableStateOf<Int?>(null) }
    val hasRawOutput = job?.partialContent?.isNotBlank() == true
    // 生成中不开放修复区：流式 checkpoint 会频繁改写 partialContent，用户编辑会被重置
    val repairable = job?.status in REPAIRABLE_STATUSES
    var showRawEditor by remember(job?.id) { mutableStateOf(false) }
    var fixHint by remember { mutableStateOf<String?>(null) }
    var confirmRegen by remember { mutableStateOf(false) }
    val writtenCount = writtenChapterIds.size
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val editorActive = showRawEditor && hasRawOutput && repairable
    val busy = job?.status == GenerationJobStatus.RUNNING || job?.status == GenerationJobStatus.QUEUED
    val chapterBusy = chapterJob?.status == GenerationJobStatus.RUNNING ||
        chapterJob?.status == GenerationJobStatus.QUEUED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "《$projectTitle》大纲",
                modifier = Modifier.weight(1f)
            )
            Text(
                "全自动",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
            Switch(checked = autoRun, onCheckedChange = onToggleAutoRun)
        }
        // 状态卡跟随实际活动：正文任务在跑时显示正文进度，而不是大纲任务的 PAUSED
        val chapterBusyNow = chapterBusy
        val statusJob = if (chapterBusyNow) chapterJob else job
        GenerationStatusCard(
            status = if (chapterBusyNow) "全自动写作中" else jobStatusText(job),
            job = statusJob,
            progress = if (chapterBusyNow) {
                val cj = chapterJob
                val chapterTitle = versions.firstOrNull()?.chapters
                    ?.firstOrNull { it.id == cj?.targetId }?.title
                "正在生成${chapterTitle?.let { "《$it》" } ?: "本章"}正文 · 已接收 ${cj.partialContent.length} 字符"
            } else {
                chapterProgress
            },
            phase = if (chapterBusyNow) {
                "写完本批全部正文后会自动生成下一批大纲"
            } else if (job?.status == GenerationJobStatus.RUNNING && job.partialContent.isNotEmpty()) {
                "正在接收结构化大纲，已生成的内容会持续保存，完成后自动校验"
            } else if (job?.status == GenerationJobStatus.QUEUED &&
                (outline?.chapters?.isNotEmpty() == true)
            ) {
                "本批正文已写完，正在生成下一批大纲"
            } else {
                null
            }
        )

        val anyBusy = busy || chapterBusy
        val latestChapterItem = versions.firstOrNull()?.chapters
            ?.firstOrNull { it.id == chapterJob?.targetId }
        val isRegen = outline != null &&
            outline.chapters.size >= (plannedChapterCount ?: outline.chapters.size)
        val generateLabel = when {
            outline == null -> "生成大纲"
            !isRegen -> "继续生成大纲（第 ${outline.chapters.size + 1} 章起）"
            else -> "重新生成大纲"
        }
        if (!anyBusy) {
            Button(
                onClick = onStartAutoRun,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("▶ 一键全自动生成全书（大纲 + 正文）")
            }
        }
        if (!busy) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        if (isRegen && writtenChapterIds.isNotEmpty()) {
                            confirmRegen = true
                        } else {
                            onGenerate(!isRegen)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(generateLabel) }
                OutlinedButton(
                    onClick = { latestChapterItem?.let(onOpenChapter) },
                    enabled = latestChapterItem != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (chapterBusy) "查看正在生成的正文" else "查看当前正文")
                }
            }
        }
        if (busy) {
            Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("取消生成")
            }
        } else if (job != null && job.status in REPAIRABLE_STATUSES) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        fixHint = null
                        if (hasRawOutput) showRawEditor = !showRawEditor
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (showRawEditor && hasRawOutput) "收起修复" else "修复") }
                Button(
                    onClick = {
                        fixHint = null
                        onGenerate(false)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("重试") }
            }
            if (!hasRawOutput) {
                fixHint = "本次模型没有返回任何文字（多为服务端限流或流被中断），没有内容可修复，请直接点「重试」。"
            }
        }
        if (error != null) {
            Text("提示：$error")
            Button(onClick = onClearError) { Text("关闭提示") }
        }

        when {
            // 键盘弹出：只保留下半部分的原文编辑区，避免上下分屏被键盘挤压
            editorActive && imeVisible -> {
                RawEditorPane(
                    job = job,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onSaveRaw = onSaveRaw
                )
            }
            outline == null -> {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when {
                        hasRawOutput -> Text(
                            "已保存模型输出 ${job.partialContent.length} 个字符（思考内容已剔除），但未通过结构校验。\n" +
                                "下方修复框可直接手动修改，改完点「校验并保存为大纲」；也可以点「重试」重新生成。"
                        )
                        job != null && job.status in REPAIRABLE_STATUSES -> Text(
                            "模型本次没有返回可用内容。常见原因：服务端限流（429）、思考型模型把输出额度耗尽在思考过程上。\n" +
                                "请点击「重试」再试一次；若反复出现，请在模型设置中更换模型或稍后再试。"
                        )
                        job == null -> Text("还没有结构化大纲。请先在模型设置中配置 Base URL、模型名和 API Key。")
                    }
                }
                if (editorActive) {
                    RawEditorPane(job = job, modifier = Modifier.fillMaxWidth().weight(1f), onSaveRaw = onSaveRaw)
                }
            }
            detailIndex != null -> {
                // 单章详情：编辑 + 导航 + 写这一章
                ChapterDetailPane(
                    draftItems = draftItems,
                    index = detailIndex!!,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    locked = draftItems[detailIndex!!].id in writtenChapterIds,
                    onIndexChange = { detailIndex = it },
                    onValueChange = { updated ->
                        draftItems = draftItems.map { if (it.id == updated.id) updated else it }
                    },
                    onMove = { from, to -> draftItems = move(draftItems, from, to) },
                    onSaveOutline = { onSave(draftItems) },
                    onOpenChapter = { item ->
                        detailIndex = null
                        onOpenChapter(item)
                    },
                    onClose = { detailIndex = null },
                    dirty = draftItems != outline.chapters
                )
            }
            else -> {
                // 书架式总览：章节卡片整齐排列，点卡片进入单章详情
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("版本 ${outline.version} · 共 ${draftItems.size} 章 · 点章节可编辑详情")
                    if (draftItems != outline.chapters) {
                        Text("有未保存的修改", style = MaterialTheme.typography.bodySmall)
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(draftItems, key = { it.id }) { item ->
                            val written = item.id in writtenChapterIds
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(onClick = {
                                        detailIndex = draftItems.indexOfFirst { it.id == item.id }
                                    }),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (written) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    }
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            "${chapterLabel(item.orderIndex)} · ${cleanChapterTitle(item.title)}",
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            if (written) "已写正文" else "未写",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Text(
                                        item.summary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
                if (editorActive) {
                    RawEditorPane(job = job, modifier = Modifier.fillMaxWidth().weight(1f), onSaveRaw = onSaveRaw)
                }
            }
        }

        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("返回项目列表")
        }
    }

    if (confirmRegen) {
        AlertDialog(
            onDismissRequest = { confirmRegen = false },
            title = { Text("重新生成将覆盖现有大纲") },
            text = {
                Text(
                    "当前大纲共 ${outline?.chapters?.size ?: 0} 章，其中 $writtenCount 章已写正文。" +
                        "重新生成会用新大纲覆盖现有版本，已写正文的章节将与新大纲断开（正文内容将无法从书架阅读）。" +
                        "确定要继续吗？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRegen = false
                    onGenerate(false)
                }) { Text("确认重新生成", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRegen = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun RawEditorPane(
    job: GenerationJob?,
    modifier: Modifier = Modifier,
    onSaveRaw: (String) -> Unit
) {
    var rawDraft by remember(job?.id) { mutableStateOf(job?.partialContent.orEmpty()) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "AI 原文（${job?.partialContent?.length ?: 0} 字符，思考内容已剔除）· 可直接修改",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = rawDraft,
            onValueChange = { value -> rawDraft = value },
            label = { Text("模型原始输出（可手动修复）") },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
        Button(
            onClick = { onSaveRaw(rawDraft) },
            enabled = rawDraft.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("校验并保存为大纲") }
    }
}

@Composable
private fun ChapterDetailPane(
    draftItems: List<OutlineItem>,
    index: Int,
    modifier: Modifier = Modifier,
    locked: Boolean,
    onIndexChange: (Int?) -> Unit,
    onValueChange: (OutlineItem) -> Unit,
    onMove: (Int, Int) -> Unit,
    onSaveOutline: () -> Unit,
    onOpenChapter: (OutlineItem) -> Unit,
    onClose: () -> Unit,
    dirty: Boolean
) {
    val item = draftItems.getOrNull(index) ?: return
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("${chapterLabel(item.orderIndex)} · 章节详情", fontWeight = FontWeight.Bold)
        if (locked) {
            Text(
                "本章已生成正文，大纲已锁定（防止改动破坏已保存的内容）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        OutlinedTextField(
            value = item.title,
            onValueChange = { value -> onValueChange(item.copy(title = value)) },
            label = { Text("标题") },
            readOnly = locked,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = item.summary,
            onValueChange = { value -> onValueChange(item.copy(summary = value)) },
            label = { Text("概要") },
            readOnly = locked,
            minLines = 4,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onIndexChange(index - 1) },
                enabled = index > 0,
                modifier = Modifier.weight(1f)
            ) { Text("〈 上一章") }
            OutlinedButton(
                onClick = { onIndexChange(index + 1) },
                enabled = index < draftItems.lastIndex,
                modifier = Modifier.weight(1f)
            ) { Text("下一章 〉") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onMove(index, index - 1) },
                enabled = index > 0,
                modifier = Modifier.weight(1f)
            ) { Text("上移") }
            OutlinedButton(
                onClick = { onMove(index, index + 1) },
                enabled = index < draftItems.lastIndex,
                modifier = Modifier.weight(1f)
            ) { Text("下移") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onOpenChapter(item) }, modifier = Modifier.weight(1f)) {
                Text("写这一章")
            }
            Button(
                onClick = onSaveOutline,
                enabled = dirty,
                modifier = Modifier.weight(1f)
            ) { Text("保存大纲") }
        }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("返回大纲总览")
        }
    }
}

private fun move(items: List<OutlineItem>, from: Int, to: Int): List<OutlineItem> {
    val mutable = items.toMutableList()
    val value = mutable.removeAt(from)
    mutable.add(to, value)
    return mutable.mapIndexed { index, item -> item.copy(orderIndex = index) }
}

private fun jobStatusText(job: GenerationJob?): String = when (job?.status) {
    null -> "生成任务：尚未开始"
    GenerationJobStatus.QUEUED -> "生成任务：排队中"
    GenerationJobStatus.RUNNING -> "生成任务：生成中"
    GenerationJobStatus.RECOVERABLE_PARTIAL -> "生成任务：已保存部分结果，可重试"
    GenerationJobStatus.COMPLETED -> "生成任务：已完成"
    GenerationJobStatus.CANCELLED -> "生成任务：已取消"
    GenerationJobStatus.FAILED -> "生成任务：失败 · ${job.errorMessage.orEmpty()}"
    GenerationJobStatus.NEEDS_USER -> "生成任务：需要处理 · ${job.errorMessage.orEmpty()}"
    GenerationJobStatus.PAUSED -> "生成任务：已暂停（本批大纲完成，等待写正文）"
}
