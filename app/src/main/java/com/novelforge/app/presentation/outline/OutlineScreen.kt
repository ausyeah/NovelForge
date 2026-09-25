package com.novelforge.app.presentation.outline

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novelforge.app.agent.AgentStep
import com.novelforge.app.domain.model.GenerationJob
import com.novelforge.app.domain.model.GenerationJobStatus
import com.novelforge.app.domain.model.OutlineItem
import com.novelforge.app.presentation.agent.AgentAssistCard
import com.novelforge.app.presentation.common.GenerationStatusCard
import com.novelforge.app.presentation.common.PaperTopBar
import com.novelforge.app.presentation.common.chapterLabel

/** 存在中间产出、允许“修复 / 重试”的任务状态 */
private val REPAIRABLE_STATUSES = setOf(
    GenerationJobStatus.NEEDS_USER,
    GenerationJobStatus.FAILED,
    GenerationJobStatus.RECOVERABLE_PARTIAL,
    GenerationJobStatus.CANCELLED
)

/**
 * 项目中枢页。
 *
 * 大纲数据、生成状态、编辑缓冲、界面焦点全部由 [viewModel] 持有，屏幕只负责渲染和发意图；
 * 导航相关的三个出口（返回、写这一章、记忆）仍然是回调，屏幕不该知道路由长什么样。
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun OutlineScreen(
    viewModel: OutlineViewModel,
    projectTitle: String,
    onBack: () -> Unit,
    onOpenChapter: (OutlineItem) -> Unit,
    onOpenMemory: () -> Unit,
    agentSteps: List<AgentStep> = emptyList(),
    agentBusy: Boolean = false,
    agentError: String? = null,
    onAskAgent: (String) -> Unit = {},
    /** 打开这本书专属桶的灵感会话（带 projectId）。 */
    onOpenChat: () -> Unit = {}
) {
    // 这三个显式取 .value 存成局部 val：分支里要写 outline.version、job.partialContent
    // 这种非空访问，而委托属性（by）不允许 smart cast
    val outline = viewModel.outline.collectAsStateWithLifecycle().value
    val job = viewModel.activeJob.collectAsStateWithLifecycle().value
    val chapterJob = viewModel.chapterJob.collectAsStateWithLifecycle().value
    val writtenChapterIds by viewModel.writtenChapterIds.collectAsStateWithLifecycle()
    val plannedChapterCount by viewModel.plannedChapterCount.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val autoRun by viewModel.autoRun.collectAsStateWithLifecycle()
    val optimizingIndex by viewModel.optimizingIndex.collectAsStateWithLifecycle()
    // 编辑缓冲和界面焦点在 ViewModel 里：这一页是用户待得最久的一页，
    // 任何一次「进章节写正文再回来」都不能把没保存的编辑弄丢
    val draftItems by viewModel.draftItems.collectAsStateWithLifecycle()
    val detailIndex by viewModel.detailIndex.collectAsStateWithLifecycle()
    val hasUnsavedEdits by viewModel.hasUnsavedEdits.collectAsStateWithLifecycle()
    val undoSlot by viewModel.undoSlot.collectAsStateWithLifecycle()
    val rawEditorOpen by viewModel.rawEditorOpen.collectAsStateWithLifecycle()
    val rawDraft by viewModel.rawDraft.collectAsStateWithLifecycle()
    val reverseOrder by viewModel.reverseOrder.collectAsStateWithLifecycle()
    val editNotice by viewModel.editNotice.collectAsStateWithLifecycle()
    val draftExit by viewModel.draftExit.collectAsStateWithLifecycle()

    // 进度计数不做全量 JSON 解析：checkpoint 每 2 秒触发一次，335 章 blob 解析会卡主线程
    val savedChapterCount = remember(job?.partialContent) {
        val text = job?.partialContent.orEmpty()
        var count = 0
        var idx = text.indexOf("orderIndex")
        while (idx >= 0) {
            count++
            idx = text.indexOf("orderIndex", idx + 10)
        }
        count
    }
    val chapterProgress = if (
        job?.status == GenerationJobStatus.RUNNING || job?.status == GenerationJobStatus.QUEUED
    ) {
        plannedChapterCount?.let { total ->
            val currentChapter = chapterLabel(savedChapterCount)
            "正在生成本批大纲（$currentChapter 起） · 已生成 $savedChapterCount/$total 段"
        }
    } else {
        null
    }
    val hasRawOutput = job?.partialContent?.isNotBlank() == true
    // 生成中不开放修复区：流式 checkpoint 会频繁改写 partialContent，用户编辑会被重置
    val repairable = job?.status in REPAIRABLE_STATUSES
    var fixHint by remember { mutableStateOf<String?>(null) }
    var confirmRegen by remember { mutableStateOf(false) }
    var regenTarget by remember { mutableStateOf<OutlineItem?>(null) }
    // 「问问这本书」降级到这里：按需工具，不占主循环的位置
    var toolsOpen by rememberSaveable { mutableStateOf(false) }
    val writtenCount = writtenChapterIds.size
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val editorActive = rawEditorOpen && hasRawOutput && repairable
    val busy = job?.status == GenerationJobStatus.RUNNING || job?.status == GenerationJobStatus.QUEUED
    val chapterBusy = chapterJob?.status == GenerationJobStatus.RUNNING ||
        chapterJob?.status == GenerationJobStatus.QUEUED
    // 单章详情打开时收起总览区的开关/生成按钮，把整页让给详情（一键全自动下沉到详情顶部）
    val detailIndexNow = detailIndex
    val detailOpen = detailIndexNow != null
    // 魔法棒是全局单飞：不管当前停在第几章，页面都得诚实显示「停止」
    val wandBusy = optimizingIndex != null

    val anyBusy = busy || chapterBusy
    val nextFreeIndex = ((outline?.chapters?.maxOfOrNull { it.orderIndex } ?: -1) + 1)
    val isRegen = outline != null &&
        nextFreeIndex >= (plannedChapterCount ?: nextFreeIndex)
    val generateLabel = when {
        outline == null -> "生成大纲"
        !isRegen -> "继续生成大纲（${chapterLabel(nextFreeIndex)} 起）"
        else -> "重新生成大纲"
    }

    // 唯一一处返回判定：系统手势和顶栏「返回」都走它，行为不许分叉。
    // 规则见 OutlineViewModel.requestBack：先关详情 → 有未保存的先问 → 才允许离开本页。
    BackHandler { viewModel.requestBack() }
    LaunchedEffect(draftExit) {
        if (draftExit is DraftExit.Leave) {
            viewModel.consumeDraftExit()
            onBack()
        }
    }

    // 总览区的按钮组（详情模式下不渲染，一键全自动由详情页顶部提供）
    val overviewButtons = @Composable {
        AutoRunControls(
            autoRun = autoRun,
            anyBusy = anyBusy,
            onStartAutoRun = viewModel::startAutoRun,
            onToggleAutoRun = viewModel::setAutoRun
        )
        if (!busy) {
            OutlinedButton(
                onClick = {
                    if (isRegen && writtenChapterIds.isNotEmpty()) {
                        confirmRegen = true
                    } else {
                        viewModel.generate(!isRegen)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(generateLabel) }
        }
        if (busy) {
            Button(onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth()) {
                Text("取消生成")
            }
        } else if (job != null && job.status in REPAIRABLE_STATUSES) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        fixHint = null
                        if (hasRawOutput) viewModel.toggleRawEditor()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (rawEditorOpen) "收起修复" else "修复") }
                Button(
                    onClick = {
                        fixHint = null
                        // 重试 = 续跑当前批次：必须以最新大纲版本回填 checkpoint，
                        // 否则 FAILED/CANCELLED 会新建空任务并从引子重跑覆盖整书大纲
                        viewModel.generate(true)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("重试") }
            }
            if (!hasRawOutput) {
                fixHint = "本次模型没有返回任何文字（多为服务端限流或流被中断），没有内容可修复，请直接点「重试」。"
            }
            // 这条以前只写不显示：用户看不到「为什么修复框不出来」
            fixHint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PaperTopBar(
            title = "《$projectTitle》",
            subtitle = "大纲",
            // 这一页是项目中枢，用户靠它回书架；没有返回键就只能靠手势，
            // 在有导航习惯的机型上非常突兀
            onBack = { viewModel.requestBack() },
            trailing = {
                TopBarAction("记忆", "打开本书记忆", onOpenMemory)
                TopBarAction("更多", "更多工具", { toolsOpen = true })
            }
        )
        // 状态卡跟随实际活动：正文任务在跑时显示正文进度，而不是大纲任务的 PAUSED
        val chapterBusyNow = chapterBusy
        val statusJob = if (chapterBusyNow) chapterJob else job
        GenerationStatusCard(
            status = if (chapterBusyNow) "全自动写作中" else jobStatusText(job),
            job = statusJob,
            progress = if (chapterBusyNow) {
                val cj = chapterJob
                val chapterTitle = outline?.chapters
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

        if (!detailOpen) {
            overviewButtons()
        }
        if (error != null) {
            Text("提示：$error")
            Button(onClick = viewModel::clearError) { Text("关闭提示") }
        }

        when {
            // 键盘弹出：只保留下半部分的原文编辑区，避免上下分屏被键盘挤压
            editorActive && imeVisible -> {
                RawEditorPane(
                    job = job,
                    rawDraft = rawDraft,
                    onRawDraftChange = viewModel::setRawDraft,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onSaveRaw = viewModel::saveRawOutline
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
                    RawEditorPane(
                        job = job,
                        rawDraft = rawDraft,
                        onRawDraftChange = viewModel::setRawDraft,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        onSaveRaw = viewModel::saveRawOutline
                    )
                }
            }
            detailIndexNow != null -> {
                // 单章详情：一键全自动 + 标题 + 概要 + 魔法棒 + 编辑翻页 + 从此章重生成 + 保存
                val index = detailIndexNow
                val detailItem = draftItems.getOrNull(index)
                val canUndo = detailItem != null && undoSlot?.first == detailItem.id
                ChapterDetailPane(
                    draftItems = draftItems,
                    index = index,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    locked = detailItem != null && detailItem.id in writtenChapterIds,
                    wandBusy = wandBusy,
                    canUndo = canUndo,
                    dirty = hasUnsavedEdits,
                    anyBusy = anyBusy,
                    autoRun = autoRun,
                    notice = editNotice,
                    onStartAutoRun = viewModel::startAutoRun,
                    onToggleAutoRun = viewModel::setAutoRun,
                    onWandPrimary = {
                        // 撤回和润色共用一个入口是因为「撤回」只是「上一次的润色还没被手改过」
                        // 的同义反复；跑起来时的「停止」不在这里，它有自己的按钮
                        if (canUndo && detailItem != null) {
                            viewModel.undoOptimize(detailItem.id)
                        } else {
                            viewModel.optimizeChapter(index)
                        }
                    },
                    onWandStop = viewModel::stopOptimize,
                    onRegenerate = { detailItem?.let { regenTarget = it } },
                    onMove = viewModel::moveDetail,
                    onValueChange = viewModel::updateDraftItem,
                    onSaveOutline = viewModel::saveDraft,
                    onOpenChapter = { item -> onOpenChapter(item) }
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "版本 ${outline.version} · 共 ${draftItems.size} 章 · 点章节可编辑详情",
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = viewModel::toggleReverseOrder) {
                            Text(if (reverseOrder) "↓ 倒序中" else "↑ 正序中")
                        }
                    }
                    if (hasUnsavedEdits) {
                        Text("有未保存的修改", style = MaterialTheme.typography.bodySmall)
                    }
                    // 以前这条提示只写不显示：润色被丢弃时用户完全收不到消息
                    editNotice?.let { notice ->
                        Text(
                            notice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(if (reverseOrder) draftItems.reversed() else draftItems, key = { it.id }) { item ->
                            val written = item.id in writtenChapterIds
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(onClick = {
                                        viewModel.openChapterDetail(item.id)
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
                                            // 魔法棒润色过的标题自带「第X章」，不再拼前缀避免重复
                                            "${chapterLabel(item.orderIndex)} ${item.title}",
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
                    RawEditorPane(
                        job = job,
                        rawDraft = rawDraft,
                        onRawDraftChange = viewModel::setRawDraft,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        onSaveRaw = viewModel::saveRawOutline
                    )
                }
            }
        }
    }

    if (toolsOpen) {
        ModalBottomSheet(onDismissRequest = { toolsOpen = false }) {
            AgentAssistCard(
                steps = agentSteps,
                busy = agentBusy,
                error = agentError,
                onAsk = onAskAgent
            )
            // 灵感助手按这本书开一段会话。之前分桶的代码一直是对的，
            // 但整个 app 只有一个不带 projectId 的入口，所以它永远落回全局桶 ——
            // A 书聊的人设会被整段重发进 B 书的提问里。现在从书内进就带 projectId。
            OutlinedButton(
                onClick = onOpenChat,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                Text("就这本书聊设定")
            }
            Text(
                "《${projectTitle}》",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp)
            )
        }
    }

    // 退出拦截：有没保存的编辑就不许悄悄丢掉。状态机在 ViewModel 里，
    // 屏幕不需要记住「对话框是不是开着」，也就不存在两处状态对不上的可能
    if (draftExit !is DraftExit.Idle && draftExit !is DraftExit.Leave) {
        val saving = draftExit is DraftExit.Saving
        val editedCount = OutlineEditRules.countEditedChapters(
            draftItems,
            outline?.chapters.orEmpty()
        )
        AlertDialog(
            // 保存中不能划掉：划掉就等于「先不保存」，而用户点的是「先保存」
            onDismissRequest = { viewModel.dismissDraftExit() },
            title = { Text("有 $editedCount 章的修改还没保存") },
            text = {
                Text(
                    (draftExit as? DraftExit.Failed)?.reason
                        ?: "直接返回会丢掉刚才在大纲里改的标题和概要。已经写好的正文不受影响，" +
                        "丢掉的只是这批大纲改动。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmDiscardDraft,
                    enabled = !saving
                ) { Text("放弃修改", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::confirmSaveDraft,
                    enabled = !saving
                ) { Text(if (saving) "保存中…" else "先保存") }
            }
        )
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
                    viewModel.generate(false)
                }) { Text("确认重新生成", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRegen = false }) { Text("取消") }
            }
        )
    }

    regenTarget?.let { target ->
        val tail = OutlineEditRules.dropTailFrom(draftItems, target.id)
        val writtenTail = tail.count { it.id in writtenChapterIds }
        AlertDialog(
            onDismissRequest = { regenTarget = null },
            title = { Text("从 ${chapterLabel(target.orderIndex)} 起重生成？") },
            text = {
                Text(
                    "将删除本章及之后共 ${tail.size} 章的大纲，" +
                        "其中 $writtenTail 章已写好的正文也会被永久删除（不可恢复）。" +
                        "之后点「继续生成大纲 / 一键全自动」将从这一章重新起跑。确定吗？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    regenTarget = null
                    viewModel.regenerateFrom(target)
                }) { Text("确认覆盖并重生成", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { regenTarget = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 一键全自动：按钮是主动作，无人值守开关作为它的子控件并说明白自己在做什么。
 *
 * 开关以前常驻顶栏，紧挨着「记忆」和返回键，看上去像个随手能碰到的旋钮，
 * 而它和这个按钮本来就是同一个意图的两个控件。现在它和按钮挨在一起，
 * 且文字说清「关掉应用也会继续写」—— 这正是它和「一键全自动」按钮的差别。
 */
@Composable
private fun AutoRunControls(
    autoRun: Boolean,
    anyBusy: Boolean,
    onStartAutoRun: () -> Unit,
    onToggleAutoRun: (Boolean) -> Unit
) {
    if (!anyBusy) {
        Button(
            onClick = onStartAutoRun,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("▶ 一键全自动生成全书（大纲 + 正文）")
        }
    }
    // 开关常驻：跑起来时上面的按钮会消失，这时候它就是用户唯一的刹车
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Switch(checked = autoRun, onCheckedChange = onToggleAutoRun)
        Text(
            "无人值守持续生成：关掉应用也会继续写，进度自动保存",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 顶栏的次级动作：故意不做成 TextButton。
 *
 * PaperTopBar 自己渲染的返回键是 48dp 见方的可点文字。再塞两个默认最小宽 58dp 的
 * TextButton，360dp 宽的屏幕上标题那一格就只剩 120dp 出头，稍长一点的书名必然被
 * 省略号吃掉。这里套用 PaperTopBar 返回键的同一套写法（最小点击区 + 语义），
 * 三个入口在视觉上也就成了同一族，而不是按钮里夹着一个开关。
 */
@Composable
private fun TopBarAction(label: String, description: String, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(onClick = onClick)
            // 裸可点文字没有 button 角色，TalkBack 只会念一句「记忆」，像静态文字
            .semantics {
                role = Role.Button
                contentDescription = description
            }
            .padding(horizontal = 6.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge
    )
}

@Composable
private fun RawEditorPane(
    job: GenerationJob?,
    rawDraft: String,
    onRawDraftChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSaveRaw: (String) -> Unit
) {
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
            onValueChange = onRawDraftChange,
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
    wandBusy: Boolean,
    canUndo: Boolean,
    dirty: Boolean,
    anyBusy: Boolean,
    autoRun: Boolean,
    notice: String?,
    onStartAutoRun: () -> Unit,
    onToggleAutoRun: (Boolean) -> Unit,
    onWandPrimary: () -> Unit,
    onWandStop: () -> Unit,
    onRegenerate: () -> Unit,
    onMove: (Int) -> Unit,
    onValueChange: (OutlineItem) -> Unit,
    onSaveOutline: () -> Unit,
    onOpenChapter: (OutlineItem) -> Unit
) {
    val item = draftItems.getOrNull(index) ?: return
    // 返回键/顶栏返回由屏幕顶层统一处理（见 OutlineViewModel.requestBack）：
    // 这里原来藏着一个 BackHandler，靠「后注册的先拿到事件」压过屏幕级的那个，
    // 面板位置一变行为就静悄悄变掉
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AutoRunControls(
            autoRun = autoRun,
            anyBusy = anyBusy,
            onStartAutoRun = onStartAutoRun,
            onToggleAutoRun = onToggleAutoRun
        )
        Text("${chapterLabel(item.orderIndex)} ${item.title}", fontWeight = FontWeight.Bold)
        if (locked) {
            Text(
                "本章已生成正文：改大纲不会自动改正文；觉得写坏了用下方「从此章重生成」连正文一起重写",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (notice != null) {
            Text(
                notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedTextField(
            value = item.title,
            onValueChange = { value -> onValueChange(item.copy(title = value)) },
            label = { Text("标题") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        // 概要框固定占满剩余高度，长文在框内滚动，避免整页滚动影响翻页
        OutlinedTextField(
            value = item.summary,
            onValueChange = { value -> onValueChange(item.copy(summary = value)) },
            label = { Text("概要") },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        )
        // 魔法棒：按上面手改的方向润色本章大纲；跑完变「撤回」，再手动编辑即失效。
        // 「停止」独占一块：它和「撤回」挤在一个按钮里时，用户想撤回上一次润色，
        // 就只能先取消正在跑的那次 —— 停下来才发现并不想停
        if (wandBusy) {
            Button(
                onClick = onWandStop,
                modifier = Modifier.fillMaxWidth()
            ) { Text("■ 停止优化") }
        } else {
            Button(
                onClick = onWandPrimary,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (canUndo) "↺ 撤回优化" else "✦ 魔法棒优化") }
        }
        Text(
            "编辑翻页：移动的是草稿里的章节位置，和正文页的翻页不是一回事",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onMove(-1) },
                enabled = index > 0,
                modifier = Modifier.weight(1f)
            ) { Text("〈 上一章（编辑）") }
            OutlinedButton(
                onClick = { onMove(1) },
                enabled = index < draftItems.lastIndex,
                modifier = Modifier.weight(1f)
            ) { Text("下一章（编辑）〉") }
        }
        // 破坏性操作单独占一行、用 error 配色：它会删掉本章及之后所有章的大纲和正文，
        // 不可恢复。以前它和「上一章/下一章」并排在同款 OutlinedButton 里，
        // 和无损翻页长得一模一样，误触的代价是整本书的后半段
        OutlinedButton(
            onClick = onRegenerate,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) { Text("↻ 从此章重生成（会删掉本章及之后的正文）") }
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
    }
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
