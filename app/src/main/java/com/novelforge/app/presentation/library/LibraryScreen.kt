package com.novelforge.app.presentation.library

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.novelforge.app.domain.repository.ChapterRepository
import com.novelforge.app.domain.repository.OutlineRepository
import com.novelforge.app.domain.repository.ProjectRepository
import com.novelforge.app.presentation.common.chapterLabel
import com.novelforge.app.presentation.common.cleanChapterTitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
            _novel.value = buildNovel(project.id, project.title, project.status.name)
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
        viewModelScope.launch { readingPositionStore.record(projectId, orderIndex) }
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
            val updated = outline.chapters
                .filter { it.orderIndex != orderIndex }
                .mapIndexed { index, item -> item.copy(orderIndex = index) }
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
        _novel.value = buildNovel(projectId, project.title, project.status.name)
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

    // 返回逻辑：阅读 → 章节列表 → 书架 → 主页，一次只退一步
    BackHandler(enabled = reading != null) { reading = null }
    BackHandler(enabled = reading == null && novel != null) { viewModel.close() }

    val readingTheme = if (reading != null) READER_THEMES[themeIndex] else null

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
                val theme = READER_THEMES[themeIndex]
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
                Text(
                    chapter.content ?: "",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    color = theme.text,
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * 1.7).sp
                )
                // 底部：主题与字体控制
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { themeIndex = (themeIndex + 1) % READER_THEMES.size }) {
                        Text("主题：${theme.name}")
                    }
                    OutlinedButton(onClick = { if (fontSize > 12) fontSize -= 2 }) { Text("A-") }
                    Text(
                        "${fontSize}",
                        modifier = Modifier.padding(horizontal = 8.dp),
                        color = theme.text
                    )
                    OutlinedButton(onClick = { if (fontSize < 30) fontSize += 2 }) { Text("A+") }
                }
            }
            current != null -> {
                Text("《${current.title}》章节", style = MaterialTheme.typography.headlineSmall)
                val generated = current.chapters.count { it.content != null }
                Text(
                    "已生成 $generated/${current.chapters.size} 章 · 点章节即可阅读",
                    style = MaterialTheme.typography.bodySmall
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
                    Text("还没有大纲章节。")
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    current.chapters.forEach { chapter ->
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
                OutlinedButton(onClick = { viewModel.close() }, modifier = Modifier.fillMaxWidth()) {
                    Text("返回书架")
                }
            }
            projects.isEmpty() -> {
                Text("我的书架", style = MaterialTheme.typography.headlineSmall)
                Text("还没有作品。先回主页新建一本小说吧。")
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("返回主页")
                }
            }
            else -> {
                Text("我的书架", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "点封面进入阅读；长按封面可重命名或删除。",
                    style = MaterialTheme.typography.bodySmall
                )
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
                                "状态：${project.status}",
                                color = Color.White.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.align(Alignment.BottomStart)
                            )
                        }
                    }
                }
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("返回主页")
                }
            }
        }
    }

    actionTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text("《${target.title}》") },
            text = { Text("选择要执行的操作：") },
            confirmButton = {
                TextButton(onClick = {
                    renameTarget = target
                    renameTitle = target.title
                    actionTarget = null
                }) { Text("重命名") }
            },
            dismissButton = {
                TextButton(onClick = {
                    deleteTarget = target
                    actionTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
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
                        "后续章节编号会自动前移，已生成的该章正文将不再显示。"
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

    actionTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text("《${target.title}》") },
            text = { Text("选择要执行的操作：") },
            confirmButton = {
                TextButton(onClick = {
                    renameTarget = target
                    renameTitle = target.title
                    actionTarget = null
                }) { Text("重命名") }
            },
            dismissButton = {
                TextButton(onClick = {
                    deleteTarget = target
                    actionTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
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
