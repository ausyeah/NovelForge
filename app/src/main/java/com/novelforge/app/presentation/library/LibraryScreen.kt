package com.novelforge.app.presentation.library

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novelforge.app.domain.model.Project
import com.novelforge.app.domain.model.label
import com.novelforge.app.domain.repository.ChapterRepository
import com.novelforge.app.domain.repository.OutlineRepository
import com.novelforge.app.domain.repository.ProjectRepository
import com.novelforge.app.presentation.common.PaperTopBar
import com.novelforge.app.presentation.common.chapterLabel
import com.novelforge.app.presentation.common.cleanChapterTitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
data class LibraryChapter(
    val orderIndex: Int,
    val title: String,
    val content: String?
)

data class LibraryNovel(
    val projectId: String,
    val title: String,
    val status: String,
    val chapters: List<LibraryChapter>,
    val lastReadOrderIndex: Int? = null
)

class LibraryViewModel(
    private val projectRepository: ProjectRepository,
    private val outlineRepository: OutlineRepository,
    private val chapterRepository: ChapterRepository,
    private val generationArtifactRepository: com.novelforge.app.domain.repository.GenerationArtifactRepository,
    private val readingPositionStore: com.novelforge.app.data.settings.ReadingPositionStore
) : ViewModel() {
    val projects: StateFlow<List<Project>> = projectRepository.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _novel = MutableStateFlow<LibraryNovel?>(null)
    val novel: StateFlow<LibraryNovel?> = _novel.asStateFlow()

    fun open(project: Project) {
        viewModelScope.launch {
            _novel.value = buildNovel(project.id, project.title, project.status.label())
        }
    }

    private suspend fun buildNovel(projectId: String, title: String, status: String): LibraryNovel {
        val outline = outlineRepository.latest(projectId)
        val revisions = chapterRepository.observeRevisions(projectId).first()
        val latestByItem = revisions.groupBy { it.outlineItemId }
            .mapValues { (_, values) -> values.maxBy { it.revision } }
        val chapters = outline?.chapters
            ?.sortedBy { it.orderIndex }
            ?.map { item ->
                LibraryChapter(
                    orderIndex = item.orderIndex,
                    title = item.title,
                    content = latestByItem[item.id]?.content
                )
            }
            .orEmpty()
        val lastRead = readingPositionStore.lastRead(projectId)
        return LibraryNovel(projectId, title, status, chapters, lastRead)
    }

    fun recordRead(projectId: String, orderIndex: Int) {
        viewModelScope.launch {
            readingPositionStore.record(projectId, orderIndex)
            _novel.update { n -> n?.takeIf { it.projectId == projectId }?.copy(lastReadOrderIndex = orderIndex) }
        }
    }

    /** 书架目录内重命名章节标题（产生新的大纲版本） */
    fun renameChapter(projectId: String, orderIndex: Int, newTitle: String) {
        viewModelScope.launch {
            val outline = outlineRepository.latest(projectId) ?: return@launch
            val updated = outline.chapters.map {
                if (it.orderIndex == orderIndex) it.copy(title = newTitle.trim()) else it
            }
            saveNewOutlineVersion(projectId, updated, "书架目录内重命名章节")
        }
    }

    /** 书架目录内删除章节（产生新的大纲版本；已生成的正文保留在数据库但不在此书显示） */
    fun deleteChapter(projectId: String, orderIndex: Int) {
        viewModelScope.launch {
            val outline = outlineRepository.latest(projectId) ?: return@launch
            // 保留原有 orderIndex（留洞）：重排会让 "chapter-N" id 与显示序号错位，
            // 已写正文整本串章；新批次由生成端按最大序号续编
            val updated = outline.chapters.filter { it.orderIndex != orderIndex }
            saveNewOutlineVersion(projectId, updated, "书架目录内删除章节")
        }
    }

    private suspend fun saveNewOutlineVersion(
        projectId: String,
        chapters: List<com.novelforge.app.domain.model.OutlineItem>,
        diffSummary: String
    ) {
        val outline = outlineRepository.latest(projectId) ?: return
        val project = projectRepository.getProject(projectId) ?: return
        val next = outline.copy(
            id = java.util.UUID.randomUUID().toString(),
            version = outline.version + 1,
            chapters = chapters,
            diffSummary = diffSummary,
            createdAt = System.currentTimeMillis()
        )
        outlineRepository.save(next)
        projectRepository.saveProject(
            project.copy(
                activeOutlineVersionId = next.id,
                updatedAt = System.currentTimeMillis()
            )
        )
        _novel.value = buildNovel(projectId, project.title, project.status.label())
    }

    fun close() {
        _novel.value = null
    }

    fun rename(projectId: String, newTitle: String) {
        viewModelScope.launch {
            projectRepository.getProject(projectId)?.let { project ->
                projectRepository.saveProject(
                    project.copy(title = newTitle.trim(), updatedAt = System.currentTimeMillis())
                )
            }
            _novel.value = _novel.value?.takeIf { it.projectId != projectId }
        }
    }

    fun delete(projectId: String) {
        viewModelScope.launch {
            projectRepository.deleteProject(projectId)
            _novel.value = _novel.value?.takeIf { it.projectId != projectId }
        }
    }

    class Factory(
        private val projectRepository: ProjectRepository,
        private val outlineRepository: OutlineRepository,
        private val chapterRepository: ChapterRepository,
        private val generationArtifactRepository: com.novelforge.app.domain.repository.GenerationArtifactRepository,
        private val readingPositionStore: com.novelforge.app.data.settings.ReadingPositionStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(
            projectRepository,
            outlineRepository,
            chapterRepository,
            generationArtifactRepository,
            readingPositionStore
        ) as T
    }
}

data class ReaderTheme(val name: String, val background: Color, val text: Color)

private val READER_THEMES = listOf(
    ReaderTheme("白纸", Color(0xFFFFFFFF), Color(0xFF1A1A1A)),
    ReaderTheme("护眼", Color(0xFFE3EDD8), Color(0xFF2B3A26)),
    ReaderTheme("夜间", Color(0xFF16181D), Color(0xFFC9CDD4))
)

/** 默认「跟随」主题：与 App 深浅色模式绑定（浅色=纸面，深色=墨纸） */
@Composable
private fun followReaderTheme(): ReaderTheme {
    val dark = com.novelforge.app.ui.theme.LocalNovelForgeDark.current
    return if (dark) {
        ReaderTheme("跟随", Color(0xFF171614), Color(0xFFDAD5CB))
    } else {
        ReaderTheme("跟随", Color(0xFFF7F4EC), Color(0xFF2B2620))
    }
}

/** 阅读器正文 + 底部控制条；orderIndex 变化时整块重建，滚动位置自动归零 */
@Composable
private fun ReaderBody(
    chapter: LibraryChapter,
    textColor: Color,
    fontSize: Int,
    modifier: Modifier = Modifier,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    themeName: String,
    onCycleTheme: () -> Unit,
    onShrinkFont: () -> Unit,
    onGrowFont: () -> Unit
) {
    key(chapter.orderIndex) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                chapter.content ?: "",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                color = textColor,
                fontSize = fontSize.sp,
                lineHeight = (fontSize * 1.7).sp
            )
            // 底部控制条压成一排：上一章｜主题｜A- 号数 A+｜下一章，尽量少占阅读空间
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onPrev, enabled = hasPrev) { Text("〈上一章") }
                TextButton(onClick = onCycleTheme) {
                    Text(themeName, style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    TextButton(onClick = onShrinkFont) { Text("A-") }
                    Text("$fontSize", style = MaterialTheme.typography.bodySmall, color = textColor)
                    TextButton(onClick = onGrowFont) { Text("A+") }
                }
                TextButton(onClick = onNext, enabled = hasNext) { Text("下一章〉") }
            }
        }
    }
}

private object LibraryPendingStore {
    var target: Project? = null
}

private val COVER_COLORS = listOf(
    Color(0xFF5B4B8A), Color(0xFF2E6E65), Color(0xFF8A5B4B),
    Color(0xFF3E5C8A), Color(0xFF7A3E5C), Color(0xFF5C7A3E)
)

private fun abs(value: Int): Int = if (value == Int.MIN_VALUE) 0 else if (value < 0) -value else value

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onBack: () -> Unit
) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val novel by viewModel.novel.collectAsStateWithLifecycle()
    var reading by remember { mutableStateOf<LibraryChapter?>(null) }
    var dirReverse by rememberSaveable { mutableStateOf(false) }
    var chapterAction by remember { mutableStateOf<LibraryChapter?>(null) }
    var chapterRename by remember { mutableStateOf<LibraryChapter?>(null) }
    var chapterRenameTitle by remember { mutableStateOf("") }
    var chapterDelete by remember { mutableStateOf<LibraryChapter?>(null) }
    var actionTarget by remember { mutableStateOf<Project?>(null) }
    var renameTarget by remember { mutableStateOf<Project?>(null) }
    var renameTitle by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Project?>(null) }
    var themeIndex by remember { mutableIntStateOf(0) }
    var fontSize by remember { mutableIntStateOf(18) }
    val readerThemes = listOf(followReaderTheme()) + READER_THEMES
    var backupTarget by LibraryPendingStore::target
    var backupMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val app = com.novelforge.app.infrastructure.backup.NovelForgeRefs.application
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val target = backupTarget
        backupTarget = null
        if (uri != null && target != null) {
            scope.launch {
                backupMessage = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        app.backupStore.export(target.id, stream)
                    } ?: error("无法写入所选文件")
                    "已导出《${target.title}》的完整备份"
                }.getOrElse { "导出失败：${it.message}" }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                backupMessage = runCatching {
                    val imported = context.contentResolver.openInputStream(uri)
                        ?.use { app.backupStore.import(it) } ?: error("无法读取所选文件")
                    "已导入《${imported.title}》，见书架/项目列表"
                }.getOrElse { "导入失败：${it.message}" }
            }
        }
    }

    // 返回逻辑：阅读 → 章节列表 → 书架 → 主页，一次只退一步
    BackHandler(enabled = reading != null) { reading = null }
    BackHandler(enabled = reading == null && novel != null) { viewModel.close() }

    val readingTheme = if (reading != null) readerThemes[themeIndex] else null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(readingTheme?.background ?: Color.Transparent)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val current = novel
        when {
            reading != null && current != null -> {
                val chapter = reading!!
                val theme = readerThemes[themeIndex]
                // 顶栏：左「目录」· 中章节名 · 右「书架」
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { reading = null }) { Text("〈 目录") }
                    Text(
                        "${chapterLabel(chapter.orderIndex)} ${cleanChapterTitle(chapter.title)}",
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        color = theme.text
                    )
                    TextButton(onClick = { viewModel.close(); reading = null }) { Text("书架 〉") }
                }
                val writtenChapters = current.chapters.filter { it.content != null }
                ReaderBody(
                    chapter = chapter,
                    textColor = theme.text,
                    fontSize = fontSize,
                    modifier = Modifier.weight(1f),
                    hasPrev = writtenChapters.any { it.orderIndex < chapter.orderIndex },
                    hasNext = writtenChapters.any { it.orderIndex > chapter.orderIndex },
                    onPrev = {
                        current.chapters.lastOrNull { it.content != null && it.orderIndex < chapter.orderIndex }?.let {
                            viewModel.recordRead(current.projectId, it.orderIndex)
                            reading = it
                        }
                    },
                    onNext = {
                        current.chapters.firstOrNull { it.content != null && it.orderIndex > chapter.orderIndex }?.let {
                            viewModel.recordRead(current.projectId, it.orderIndex)
                            reading = it
                        }
                    },
                    themeName = theme.name,
                    onCycleTheme = { themeIndex = (themeIndex + 1) % readerThemes.size },
                    onShrinkFont = { if (fontSize > 12) fontSize -= 2 },
                    onGrowFont = { if (fontSize < 30) fontSize += 2 }
                )
            }
            current != null -> {
                val generated = current.chapters.count { it.content != null }
                PaperTopBar(
                    title = "《${current.title}》",
                    subtitle = "已生成 $generated/${current.chapters.size} 章",
                    onBack = { viewModel.close() },
                    trailing = {
                        TextButton(onClick = { dirReverse = !dirReverse }) {
                            Text(if (dirReverse) "倒序" else "正序")
                        }
                    }
                )
                // 一键续读：回到上次读到的章节
                val resumeChapter = current.lastReadOrderIndex?.let { idx ->
                    current.chapters.firstOrNull { it.orderIndex == idx && it.content != null }
                }
                if (resumeChapter != null) {
                    Button(
                        onClick = {
                            viewModel.recordRead(current.projectId, resumeChapter.orderIndex)
                            reading = resumeChapter
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "▶ 续读：${chapterLabel(resumeChapter.orderIndex)} · " +
                                cleanChapterTitle(resumeChapter.title)
                        )
                    }
                }
                if (current.chapters.isEmpty()) {
                    Text(
                        "还没有大纲。回到作品里生成后再来读。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(if (dirReverse) current.chapters.reversed() else current.chapters, key = { it.orderIndex }) { chapter ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (chapter.content != null) {
                                            viewModel.recordRead(current.projectId, chapter.orderIndex)
                                            reading = chapter
                                        }
                                    },
                                    onLongClick = { chapterAction = chapter }
                                )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp)
                            ) {
                                Text("${chapterLabel(chapter.orderIndex)} · ${cleanChapterTitle(chapter.title)}")
                                Text(
                                    if (chapter.content != null) "点击阅读 · 长按重命名/删除" else "未生成正文 · 长按可删除",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
            projects.isEmpty() -> {
                PaperTopBar(title = "书架", onBack = onBack)
                Text(
                    "还没有作品。先回主页新建一本。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> {
                PaperTopBar(
                    title = "书架",
                    subtitle = "点封面阅读，长按可备份或改名",
                    onBack = onBack,
                    trailing = {
                        TextButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                            Text("导入")
                        }
                    }
                )
                backupMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { backupMessage = null }) { Text("关闭提示") }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(projects, key = { it.id }) { project ->
                        val cover = COVER_COLORS[abs(project.id.hashCode()) % COVER_COLORS.size]
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.72f)
                                .background(cover, RoundedCornerShape(10.dp))
                                .combinedClickable(
                                    onClick = { viewModel.open(project) },
                                    onLongClick = { actionTarget = project }
                                )
                                .padding(12.dp)
                        ) {
                            Text(
                                project.title,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                project.status.label(),
                                color = Color.White.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.align(Alignment.BottomStart)
                            )
                        }
                    }
                }
            }
        }
    }

    actionTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text("《${target.title}》") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("选择要执行的操作：")
                    Button(
                        onClick = {
                            actionTarget = null
                            backupTarget = target
                            exportLauncher.launch(app.backupStore.suggestedFileName(target.title))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("导出整书备份（大纲 + 正文）") }
                    TextButton(onClick = {
                        renameTarget = target
                        renameTitle = target.title
                        actionTarget = null
                    }) { Text("重命名") }
                    TextButton(onClick = {
                        deleteTarget = target
                        actionTarget = null
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { actionTarget = null }) { Text("取消") }
            }
        )
    }

    chapterAction?.let { chapter ->
        val novelTitle = novel?.title.orEmpty()
        AlertDialog(
            onDismissRequest = { chapterAction = null },
            title = { Text("${chapterLabel(chapter.orderIndex)} · ${cleanChapterTitle(chapter.title)}") },
            text = { Text("《$novelTitle》目录内选择要执行的操作：") },
            confirmButton = {
                TextButton(onClick = {
                    chapterRename = chapter
                    chapterRenameTitle = chapter.title
                    chapterAction = null
                }) { Text("重命名") }
            },
            dismissButton = {
                TextButton(onClick = {
                    chapterDelete = chapter
                    chapterAction = null
                }) { Text("删除章节", color = MaterialTheme.colorScheme.error) }
            }
        )
    }

    chapterRename?.let { chapter ->
        AlertDialog(
            onDismissRequest = { chapterRename = null },
            title = { Text("重命名章节") },
            text = {
                OutlinedTextField(
                    value = chapterRenameTitle,
                    onValueChange = { chapterRenameTitle = it },
                    label = { Text("章节标题") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (chapterRenameTitle.isNotBlank()) {
                            viewModel.renameChapter(
                                novel?.projectId.orEmpty(),
                                chapter.orderIndex,
                                chapterRenameTitle
                            )
                        }
                        chapterRename = null
                    },
                    enabled = chapterRenameTitle.isNotBlank()
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { chapterRename = null }) { Text("取消") }
            }
        )
    }

    chapterDelete?.let { chapter ->
        AlertDialog(
            onDismissRequest = { chapterDelete = null },
            title = { Text("删除章节") },
            text = {
                Text(
                    "确定从《${novel?.title.orEmpty()}》删除 ${chapterLabel(chapter.orderIndex)}「${cleanChapterTitle(chapter.title)}」？" +
                        "删除后该序号空出，后续章节编号保持不变；已生成的该章正文将不再显示。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteChapter(novel?.projectId.orEmpty(), chapter.orderIndex)
                    if (reading?.orderIndex == chapter.orderIndex) reading = null
                    chapterDelete = null
                }) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { chapterDelete = null }) { Text("取消") }
            }
        )
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名作品") },
            text = {
                OutlinedTextField(
                    value = renameTitle,
                    onValueChange = { renameTitle = it },
                    label = { Text("书名") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameTitle.isNotBlank()) {
                            viewModel.rename(target.id, renameTitle)
                        }
                        renameTarget = null
                    },
                    enabled = renameTitle.isNotBlank()
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除作品") },
            text = { Text("确定删除《${target.title}》？其大纲和已生成章节会一并删除，且不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target.id)
                    deleteTarget = null
                }) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}
