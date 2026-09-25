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
import androidx.compose.runtime.saveable.rememberSaveable
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

/** 不冻结：正文列表跟着生成实时增长。段落条数不会是负数，用 -1 当哨兵值。 */
private const val FOLLOW_PARAGRAPHS = -1

/**
 * 相邻章节的跳转目标（写作流专用，与 LibraryScreen 的读者翻页、OutlineScreen 的详情翻页不是一回事）。
 *
 * 为什么由导航层构造而不是界面自己找顺序：「下一章是谁」和「它是不是已经写过」必须一起判断，
 * 而只有导航层同时看得见整份大纲和全部修订；两件事都是数据判断，不该在 Composable 里现算。
 *
 * @param label 目标章的展示名（[chapterLabel]），按钮上写出来，免得「下一章」是个不知道去哪的词
 * @param hasRevision 目标章已经有正文。为 true 时自动生成不会触发，按钮就只写"打开"不写"生成"
 * @param onOpen 点击后要执行的导航
 */
class ChapterNeighbor(
    val label: String,
    val hasRevision: Boolean,
    val onOpen: () -> Unit
)

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
    /** 回到这本书的正文页（大纲）——不是回 App 首页。 */
    onBackToBook: () -> Unit,
    onClearError: () -> Unit,
    onBack: () -> Unit,
    /** 上一章；null 表示当前是全书第一章，按钮置灰。 */
    previousChapter: ChapterNeighbor? = null,
    /** 下一章；null 表示当前是最后一章，按钮置灰。 */
    nextChapter: ChapterNeighbor? = null,
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
    // 正文滚动位置不用自己 saveable：rememberLazyListState() 内部就是
    // rememberSaveable(LazyState.Saver)，而 NavHost 给每个目的地套了 rememberSaveableStateHolder，
    // 去别的页面再回来时这一页会被整体卸载重组，位置由那条 holder 按 NavBackStackEntry 存取，能活下来。
    // （foundation 1.7.6 的 rememberLazyListState 确实调 RememberSaveableKt.rememberSaveable，已核对）
    // 提前到 if 外面，是为了让它的 compositeKeyHash 固定在根节点：留在 if 分支里的话，
    // 键会随分支结构漂移，以后有人在上面加一段 UI 就可能悄悄读不回旧位置。
    val contentState = rememberLazyListState()
    // 对照弹窗的开关也要活过一次往返：它是几百 dp 之外才点得到的一个状态，
    // 掉回 false 只会让人以为点错了，而"和上一稿对照"这条流程本身不该因为回退栈往返而中断
    var compareOpen by rememberSaveable { mutableStateOf(false) }
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
            //
            // 存的是"冻结在第几条"这一个 Int，列表本体由 paragraphs 现场推导。
            // 为什么不把整份 List<String> 塞进 rememberSaveable：一章正文 UTF-8 就有
            // 几十 KB，长章上百 KB，而 Bundle 走 Binder 事务（上限 1MB），
            // 越界就是 TransactionTooLargeException 崩在毫无关联的界面上；
            // 何况正文本来就是从库里重新读出来的，在状态里再存一份副本既不省事也不安全。
            // 只存条数就能在回到本页时按新正文重建同一个冻结视图；
            // take() 天然夹在长度内，万一冻结点比正文还长，也只是退化成"全显示"，不会越界。
            var frozenAt by rememberSaveable(job?.id, revision?.id) {
                mutableStateOf(FOLLOW_PARAGRAPHS)
            }
            val displayParagraphs = remember(frozenAt, paragraphs) {
                if (frozenAt == FOLLOW_PARAGRAPHS) {
                    paragraphs.reversed()
                } else {
                    paragraphs.take(frozenAt).reversed()
                }
            }
            // 规则只有一条：在绝对底部就让列表跟上（末段原地变长不跳）；
            // 不在底部就完全不写列表——内容刷新由末段替换保证，不新增条目不顶视口
            LaunchedEffect(paragraphs, atBottom) {
                if (atBottom) frozenAt = FOLLOW_PARAGRAPHS
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
        if (!busy) {
            // 上一章/下一章做成紧挨着的一对：以前只有"生成下一章"一个方向，
            // 想看第 N-1 章得退回大纲在大纲里翻，3 步。
            //
            // 按钮上写的是目标章而不是"上一章/下一章"这几个字，因为它们在
            // LibraryScreen 是读者翻页、在 OutlineScreen 是详情翻页，都不是"换一章来写"。
            // 写清目标（〈 第 3 章 / 生成第 4 章）才不会点错意思。
            // 置灰而不是藏起来：没有上一章/下一章是一个事实，藏了用户只会以为是 bug。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { previousChapter?.onOpen() },
                    enabled = previousChapter != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (previousChapter == null) "上一章" else "〈 ${previousChapter.label}",
                        maxLines = 1
                    )
                }
                Button(
                    onClick = { nextChapter?.onOpen() },
                    enabled = nextChapter != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        // 已经写过正文的下一章，导航带的 autostart 不会触发生成，
                        // 这时候还写"生成"就是在骗人：点了只会打开它
                        when {
                            nextChapter == null -> "下一章"
                            nextChapter.hasRevision -> "${nextChapter.label} 〉"
                            else -> "生成${nextChapter.label}"
                        },
                        maxLines = 1
                    )
                }
            }
        }
        // 顶栏「返回」是"退一层"（从哪儿来回哪儿去），这里是"回这本书"：深层栈里
        // 两者不是同一个地方（从 Library 或别的书跳进来时尤其明显），所以文案得自己说清去哪儿。
        // 以前这里写"回到首页"，弹的是 popBackStack("home")——把本章连同大纲一起清掉，
        // 而全 App 又没有"回到上次写到的那一章"，于是重进书只能从头找。
        OutlinedButton(onClick = onBackToBook, modifier = Modifier.fillMaxWidth()) {
            Text("回到这本书（大纲）")
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
