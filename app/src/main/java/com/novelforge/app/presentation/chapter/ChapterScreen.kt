package com.novelforge.app.presentation.chapter

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novelforge.app.domain.model.ChapterRevision
import com.novelforge.app.domain.model.GenerationJob
import com.novelforge.app.domain.model.GenerationJobStatus
import com.novelforge.app.domain.model.OutlineItem
import com.novelforge.app.presentation.common.GenerationStatusCard
import com.novelforge.app.presentation.common.PaperTopBar
import com.novelforge.app.presentation.common.chapterLabel
import com.novelforge.app.presentation.common.cleanChapterTitle
import kotlinx.coroutines.launch

@Composable
fun ChapterScreen(
    projectTitle: String,
    chapter: OutlineItem?,
    revision: ChapterRevision?,
    job: GenerationJob?,
    error: String?,
    onGenerate: (OutlineItem) -> Unit,
    onCancel: () -> Unit,
    onRetry: (OutlineItem) -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onNextChapter: (() -> Unit)?,
    onBackHome: () -> Unit,
    onClearError: () -> Unit,
    onBack: () -> Unit,
    memoryCharacters: List<Pair<String, String>> = emptyList(),
    excludedCharacterIds: Set<String> = emptySet(),
    memoryThreads: List<String> = emptyList(),
    excludedThreads: Set<String> = emptySet(),
    factCount: Int = 0,
    omittedCharacters: Int = 0,
    omittedThreads: Int = 0,
    omittedRules: Int = 0,
    pendingCount: Int = 0,
    onToggleCharacter: (String) -> Unit = {},
    onToggleThread: (String) -> Unit = {},
    onOpenMemory: () -> Unit = {},
    previousRevision: ChapterRevision? = null,
    onRestorePrevious: () -> Unit = {}
) {
    var compareOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PaperTopBar(
            title = "《$projectTitle》",
            subtitle = chapter?.let { chapterLabel(it.orderIndex) } ?: "还没有可写的章节",
            onBack = onBack
        )
        if (chapter == null) {
            Text(
                "先回到大纲，生成并确认章节后再来写正文。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return
        }

        Text(
            cleanChapterTitle(chapter.title),
            style = MaterialTheme.typography.titleLarge
        )
        MemoryStrip(
            characters = memoryCharacters,
            excludedCharacterIds = excludedCharacterIds,
            threads = memoryThreads,
            excludedThreads = excludedThreads,
            factCount = factCount,
            omittedCharacters = omittedCharacters,
            omittedThreads = omittedThreads,
            omittedRules = omittedRules,
            pendingCount = pendingCount,
            onToggleCharacter = onToggleCharacter,
            onToggleThread = onToggleThread,
            onOpenMemory = onOpenMemory
        )
        if (previousRevision != null && revision != null) {
            OutlinedButton(onClick = { compareOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text("和上一稿对照")
            }
        }
        GenerationStatusCard(
            status = statusText(job, revision),
            job = job,
            phase = if (job?.status == GenerationJobStatus.RUNNING && job.partialContent.isNotEmpty()) {
                "正在接收章节正文，已生成的内容会持续保存"
            } else {
                null
            }
        )

        error?.let {
            Text("提示：$it")
            Button(onClick = onClearError) { Text("关闭提示") }
        }

        when (job?.status) {
            GenerationJobStatus.QUEUED,
            GenerationJobStatus.RUNNING -> Button(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth()
            ) { Text("取消生成") }

            GenerationJobStatus.FAILED,
            GenerationJobStatus.RECOVERABLE_PARTIAL,
            GenerationJobStatus.NEEDS_USER -> Button(
                onClick = { onRetry(chapter) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("重试这一章") }

            else -> Button(
                onClick = { onGenerate(chapter) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (revision == null) "生成这一章" else "重新生成修订") }
        }

        val visibleContent = revision?.content
            ?: job?.partialContent?.takeIf { it.isNotBlank() }
        if (visibleContent != null) {
            // 与灵感助手同款滚动策略：reverseLayout 让最新段落贴底（(0,0)=绝对底部），
            // 增长天然跟随；一旦上滑或手指按住立即冻结列表刷新，绝不做程序化滚动，
            // 彻底消除"生成中抽搐弹回"的问题
            val paragraphs = remember(visibleContent) { visibleContent.split("\n") }
            val contentState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            var touching by remember { mutableStateOf(false) }
            val atBottom by remember {
                derivedStateOf {
                    contentState.firstVisibleItemIndex == 0 &&
                        contentState.firstVisibleItemScrollOffset == 0
                }
            }
            // 手势/滚动中只暂缓"新增段落"的插位（防视口被顶跳）；
            // 正在生成的末段永远原地刷新——内容必须看得出一直在长
            var displayParagraphs by remember(job?.id, revision?.id) {
                mutableStateOf(paragraphs.reversed())
            }
            // 规则只有一条：在绝对底部就让列表跟上（末段原地变长不跳）；
            // 不在底部就完全不写列表——内容刷新由末段替换保证，不新增条目不顶视口
            LaunchedEffect(paragraphs, atBottom) {
                if (atBottom) displayParagraphs = paragraphs.reversed()
            }
            Text(
                if (revision == null) "正文 · 生成中的中间结果" else "正文 · 修订 ${revision.revision}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            touching = true
                            awaitPointerEvent(PointerEventPass.Final)
                            touching = false
                        }
                    }
            ) {
                LazyColumn(
                    state = contentState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp)
                ) {
                    itemsIndexed(displayParagraphs, key = { i, _ -> i }) { _, paragraph ->
                        Text(
                            paragraph,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 23.sp
                        )
                    }
                }
                if (!atBottom) {
                    // 上滑后画面静止是故意的冻结（防抽搐）；给明确文案说明生成仍在继续
                    ExtendedFloatingActionButton(
                        onClick = {
                            scope.launch { contentState.scrollToItem(0) }
                        },
                        icon = { Text("↓") },
                        text = {
                            Text(
                                if (revision == null &&
                                    job?.status == GenerationJobStatus.RUNNING
                                ) "回到底部继续跟随" else "回到底部"
                            )
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                    )
                }
            }
        }
        if (revision != null) {
            Text(
                "TXT 会保存到「下载/NovelForge/」，也可在「备份与导出」里离线查看。",
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                Text("保存到文件夹")
            }
            OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                Text("分享")
            }
        }
        val busy = job?.status == GenerationJobStatus.RUNNING || job?.status == GenerationJobStatus.QUEUED
        if (!busy && onNextChapter != null) {
            Button(onClick = onNextChapter, modifier = Modifier.fillMaxWidth()) {
                Text("生成下一章")
            }
        }
        OutlinedButton(onClick = onBackHome, modifier = Modifier.fillMaxWidth()) {
            Text("回到首页")
        }
    }
    if (compareOpen && previousRevision != null && revision != null) {
        AlertDialog(
            onDismissRequest = { compareOpen = false },
            title = { Text("修订 ${previousRevision.revision} 和 ${revision.revision}") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("上一稿", style = MaterialTheme.typography.labelLarge)
                    Text(previousRevision.content.take(1_200), style = MaterialTheme.typography.bodySmall)
                    Text("当前稿", style = MaterialTheme.typography.labelLarge)
                    Text(revision.content.take(1_200), style = MaterialTheme.typography.bodySmall)
                    if (previousRevision.content.length > 1_200 || revision.content.length > 1_200) {
                        Text("这里只对照开头一段。回到上一稿会把整章存成新的修订。")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    compareOpen = false
                    onRestorePrevious()
                }) { Text("回到上一稿") }
            },
            dismissButton = {
                TextButton(onClick = { compareOpen = false }) { Text("留下当前稿") }
            }
        )
    }
}

@Composable
private fun MemoryStrip(
    characters: List<Pair<String, String>>,
    excludedCharacterIds: Set<String>,
    threads: List<String>,
    excludedThreads: Set<String>,
    factCount: Int,
    omittedCharacters: Int,
    omittedThreads: Int,
    omittedRules: Int,
    pendingCount: Int,
    onToggleCharacter: (String) -> Unit,
    onToggleThread: (String) -> Unit,
    onOpenMemory: () -> Unit
) {
    // 只报「带几个」是在骗人：以前这里算的是排除之后的全长，
    // 于是界面上写着「会带上 20 个角色、30 条伏笔」，实际 prompt 里只有 6 和 10。
    // 用户按这个数字做判断，被落选的角色/伏笔就变成「我明明写了它却不用」。
    val includedCharacters = characters.count { it.first !in excludedCharacterIds }
    val includedThreads = threads.count { it !in excludedThreads }
    val overBudget = omittedCharacters + omittedThreads + omittedRules
    Text(
        buildString {
            append("这次会带上 $includedCharacters/${characters.size} 个角色")
            append("、$includedThreads/${threads.size} 条伏笔")
            append("、$factCount 条已确认事实")
            if (overBudget > 0) {
                append("。另有 $overBudget 条这次名额不够没带")
                append("（其中规则 $omittedRules 条）")
            }
            append("。点一下可以先不带。")
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (overBudget > 0) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    )
    if (characters.isNotEmpty() || threads.isNotEmpty()) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            characters.take(8).forEach { (id, name) ->
                FilterChip(
                    selected = id !in excludedCharacterIds,
                    onClick = { onToggleCharacter(id) },
                    label = { Text(name) }
                )
            }
            threads.take(8).forEach { thread ->
                FilterChip(
                    selected = thread !in excludedThreads,
                    onClick = { onToggleThread(thread) },
                    label = { Text(thread) }
                )
            }
        }
    }
    if (pendingCount > 0) {
        TextButton(onClick = onOpenMemory) { Text("$pendingCount 条新记忆还没确认") }
    } else {
        TextButton(onClick = onOpenMemory) { Text("整理本书记忆") }
    }
}

private fun statusText(job: GenerationJob?, revision: ChapterRevision?): String = when {
    job == null && revision == null -> "生成任务：尚未开始"
    job?.status == GenerationJobStatus.QUEUED -> "生成任务：排队中"
    job?.status == GenerationJobStatus.RUNNING -> "生成任务：生成中"
    job?.status == GenerationJobStatus.RECOVERABLE_PARTIAL -> "生成任务：已保存部分结果，可重试"
    job?.status == GenerationJobStatus.CANCELLED -> "生成任务：已取消"
    job?.status == GenerationJobStatus.FAILED -> "生成任务：失败 · ${job.errorMessage.orEmpty()}"
    job?.status == GenerationJobStatus.NEEDS_USER -> "生成任务：需要处理 · ${job.errorMessage.orEmpty()}"
    revision != null -> "章节已保存 · 修订 ${revision.revision}"
    else -> "生成任务：已完成"
}
