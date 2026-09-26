package com.novelforge.app.presentation.library

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novelforge.app.data.cover.BookCover
import com.novelforge.app.domain.model.Project
import com.novelforge.app.domain.model.ProjectStatus
import com.novelforge.app.domain.model.label
import com.novelforge.app.domain.repository.ChapterRepository
import com.novelforge.app.domain.repository.OutlineRepository
import com.novelforge.app.domain.repository.ProjectRepository
import com.novelforge.app.presentation.common.PaperTopBar
import com.novelforge.app.presentation.common.chapterLabel
import com.novelforge.app.presentation.common.cleanChapterTitle
import com.novelforge.app.ui.theme.PaperButton
import com.novelforge.app.ui.theme.PaperSurface
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
    private val readingPositionStore: com.novelforge.app.data.settings.ReadingPositionStore,
    private val coverStore: com.novelforge.app.data.cover.BookCoverStore,
    /**
     * 删书时要回收的按书开关。默认从 app 容器取同一个单例
     * （就是 GenerationRuntime 用的那一个），所以导航层不必改；
     * 要显式传 `application.autoRunStore` 也可以。
     */
    private val autoRunStore: com.novelforge.app.data.settings.AutoRunStore =
        com.novelforge.app.infrastructure.backup.NovelForgeRefs.application.autoRunStore
) : ViewModel() {
    val projects: StateFlow<List<Project>> = projectRepository.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _novel = MutableStateFlow<LibraryNovel?>(null)
    val novel: StateFlow<LibraryNovel?> = _novel.asStateFlow()

    private val _lastReadIndex = MutableStateFlow<Map<String, Int>>(emptyMap())

    /**
     * 书架顶部「继续读」的燃料：projectId -> 上次读到的 orderIndex。
     * 刻意只缓存 Int：正文不进这里，所以这张表进 Bundle 也不会炸。
     * LibraryNovel 里的 lastReadOrderIndex 只在「书已打开」时才有值，
     * 而书架没打开书时也要知道该显示续读入口，所以另存一份。
     */
    val lastReadIndex: StateFlow<Map<String, Int>> = _lastReadIndex.asStateFlow()

    /**
     * 哪些书写过正文：projectId -> 有正文的章数。
     *
     * 点封面要分流（点了去读），而 `Project` 自己不知道自己有没有正文 ——
     * 它只有 status 和 creativeConfig，正文存在另一张表里。所以书架得自己
     * 备一份计数。
     *
     * 只存 Int 不存正文：这张表要进 `rememberSaveable` 的 Bundle，
     * 正文进去必炸（`lastReadIndex` 上面那个注释是同一个道理）。
     */
    private val _writtenCounts = MutableStateFlow<Map<String, Int>>(emptyMap())

    /**
     * 给封面格显示「N 章」用的只读视图。
     *
     * 单开一个 Flow 而不是塞进书单：合流的话每写完一章都要重建整份书单，
     * 而重建 `projects` 会连带把整个网格的 key 换掉。
     */
    val writtenCounts: StateFlow<Map<String, Int>> = _writtenCounts.asStateFlow()

    /** 有正文的章数；没有记录的书按 0 算（没正文）。 */
    fun writtenCount(projectId: String): Int = _writtenCounts.value[projectId] ?: 0

    /**
     * 订阅那张计数表。
     *
     * 单独一个 Flow 而不是并进 `projects`：正文写完会让计数变，书架得跟着
     * 变（写完一章之后同一个封面应该开始「点进去是读」）。合流的话每次
     * 写正文都要重建整份书单。
     */
    fun observeWrittenCounts() {
        viewModelScope.launch {
            projectRepository.observeWrittenChapterCounts().collect { counts ->
                _writtenCounts.value = counts
            }
        }
    }

    /**
     * 点封面分流：有正文的去读，没正文的去写。
     *
     * 空书也点「阅读」的话会进目录页，而那里每张卡都是「未生成正文」——
     * 一片死胡同，用户得自己退出来去找写作。开始写是所有书的必经一步，
     * 所以空书直接送去写作。
     *
     * 规则本体在 [LibraryRouting]（纯函数，可 JVM 测），这里只做转发。
     */
    fun tapCoverGoesToReading(writtenChapters: Int): Boolean =
        LibraryRouting.tapGoesToReading(writtenChapters)

    /** 书架停留期间按需拉一次阅读位置（DataStore 没有 Flow 可订阅） */
    fun refreshLastRead(projectId: String) {
        viewModelScope.launch {
            val index = readingPositionStore.lastRead(projectId) ?: return@launch
            _lastReadIndex.update { it + (projectId to index) }
        }
    }

    fun open(project: Project) {
        viewModelScope.launch {
            val built = buildNovel(project.id, project.title, project.status.label())
            _novel.value = built
            // 顺带把阅读位置补进书架那张表：顶部「继续读」不该只在
            // refreshLastRead 恰好跑过的那本书上才亮
            built.lastReadOrderIndex?.let { index ->
                _lastReadIndex.update { it + (project.id to index) }
            }
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
            // 书架顶部的「继续读」跟着走，否则从书里退回来还会指向上一次的位置
            _lastReadIndex.update { it + (projectId to orderIndex) }
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
            val title = newTitle.trim()
            projectRepository.getProject(projectId)?.let { project ->
                projectRepository.saveProject(
                    project.copy(title = title, updatedAt = System.currentTimeMillis())
                )
                // 原来这里是 `_novel.value?.takeIf { it.projectId != projectId }`：
                // 改 A 开着 B 时确实不动，但改的正开着的那本时 takeIf 返回 null，
                // 等于「改个名把人从书里踢回书架」。改名不动内容，把标题刷新过来就行。
                if (_novel.value?.projectId == projectId) {
                    _novel.value = _novel.value?.copy(title = title, status = project.status.label())
                }
            }
        }
    }

    fun delete(projectId: String) {
        viewModelScope.launch {
            projectRepository.deleteProject(projectId)
            // 封面文件和数据条目都得跟着走：项目 id 会复用，
            // 留着的话新书一建出来就顶着上一本书的封面
            runCatching { coverStore.clear(projectId) }
            // 一键全自动的开关是同一个理由，而且后果更贵：它是按 projectId 存的
            // 单个 DataStore 条目，不清的话 id 复用后新书一开箱就是「已开全自动」，
            // 第一章生成成功就自动往下写 —— 直接开始计费，用户完全没按过开关
            // （见 AutoRunStore 的类注释里那个「A 书的开关驱动 B 书」的旧事故）。
            // AutoRunStore.clear() 以前是死代码：写进去了，没有任何地方回收。
            runCatching { autoRunStore.clear(projectId) }
            _novel.value = _novel.value?.takeIf { it.projectId != projectId }
        }
    }

    // —— 封面 ————————————————————————————————————————

    /**
     * projectId -> 封面。存的是「预设第几号」还是「有没有自定义图」这种小整数/布尔，
     * 不是 Bitmap —— 书架网格里的图是按需解码的缩略图，不进这里，也绝不能进 Bundle。
     */
    private val _covers = MutableStateFlow<Map<String, BookCover>>(emptyMap())
    val covers: StateFlow<Map<String, BookCover>> = _covers.asStateFlow()

    fun refreshCovers(projectIds: Collection<String>) {
        viewModelScope.launch {
            val loaded = projectIds.associateWith { id ->
                runCatching { coverStore.observe(id).first() }.getOrDefault(BookCover.Default)
            }
            _covers.update { current -> current + loaded }
        }
    }

    fun setPresetCover(projectId: String, index: Int) {
        viewModelScope.launch {
            runCatching { coverStore.setPreset(projectId, index) }
                .onSuccess { _covers.update { it + (projectId to BookCover.Preset(index)) } }
        }
    }

    fun setImageCover(projectId: String, uri: android.net.Uri, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching { coverStore.setImageFrom(projectId, uri) }
                .getOrDefault(false)
            if (ok) {
                _covers.update { it + (projectId to BookCover.Image(coverStore.fileFor(projectId).name)) }
            }
            onResult(ok)
        }
    }

    fun resetCover(projectId: String) {
        viewModelScope.launch {
            runCatching { coverStore.clear(projectId) }
            _covers.update { it + (projectId to BookCover.Default) }
        }
    }

    suspend fun coverThumbnail(projectId: String) = coverStore.thumbnail(projectId)

    class Factory(
        private val projectRepository: ProjectRepository,
        private val outlineRepository: OutlineRepository,
        private val chapterRepository: ChapterRepository,
        private val generationArtifactRepository: com.novelforge.app.domain.repository.GenerationArtifactRepository,
        private val readingPositionStore: com.novelforge.app.data.settings.ReadingPositionStore,
        private val coverStore: com.novelforge.app.data.cover.BookCoverStore,
        private val autoRunStore: com.novelforge.app.data.settings.AutoRunStore =
            com.novelforge.app.infrastructure.backup.NovelForgeRefs.application.autoRunStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(
            projectRepository,
            outlineRepository,
            chapterRepository,
            generationArtifactRepository,
            readingPositionStore,
            coverStore,
            autoRunStore
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

private val COVER_COLORS = listOf(
    Color(0xFF5B4B8A), Color(0xFF2E6E65), Color(0xFF8A5B4B),
    Color(0xFF3E5C8A), Color(0xFF7A3E5C), Color(0xFF5C7A3E)
)

/**
 * 网格的三个尺寸常量。**它们必须相等：间距 == 圆角。**
 *
 * 12dp 不是随便挑的：M3 的三种卡片（filled / elevated / outlined）
 * ContainerShape 全都是 `CornerMedium` = 12dp，而 10dp 根本不在 M3 的
 * 形状刻度（4 / 8 / 12 / 16 / 28）上。原来间距和圆角都是 10dp ——
 * 数值上相等纯属碰巧，不是因为它在任何刻度上。
 *
 * 「间距 == 圆角」是 M3 最容易被认出来的观感：相邻两块之间的缝和它们
 * 各自的转角一样宽，看起来才像同一套东西。
 */
private val COVER_RADIUS = 12.dp
private val COVER_GUTTER = 12.dp

/** 左右留白取 16dp —— M3 列表的 leading/trailing space，让这个网格和 App 里其他列表对齐。 */
private val SHELF_PADDING = 16.dp

/** 封面内文字离边的距离。上下都留这么多，文字块在光学上居中。 */
private val COVER_INSET = 12.dp

private fun abs(value: Int): Int = if (value == Int.MIN_VALUE) 0 else if (value < 0) -value else value

/**
 * 书架上的一本书。
 *
 * 自定义封面用 produceState + IO 线程解码缩略图，**不在组合期读文件** ——
 * 书架一次能列几十本，每格同步解一张图会直接卡住首屏。
 * 读不出来就落回颜色，绝不留一块空白。
 */
@OptIn(ExperimentalFoundationApi::class)
/**
 * 封面格。
 *
 * **一格就是一张卡，一种解剖。** 整个网格里不允许出现第二种卡片长相 ——
 * 之前那个米色「继续写作」大卡和这个封面格除了 10dp 圆角以外没有任何共同属性，
 * 一屏看着像两个 App 拼起来的（NN/g 一致性准则：同一集合里的项必须看起来同族）。
 *
 * ## 三个具体的取舍
 *
 * **1. 书名在底部，不在顶部。** 之前书名在顶、状态在底，中间一大块是空的，
 * 而为了在**顶部**放白字，还得给整张封面上半部分压一层 `alpha=0.45` 的暗色 ——
 * 那是全屏最大的一块无谓损耗，封面最该露出来的上半部分反而被盖住了。
 * 标题挪到底之后，一层自下而上的渐变就够，上面 40% 干干净净全是画面。
 * 起点读书自己的复盘也是这个结论：网封色块对比弱，解法是**缩封面、加大图与留白的对比**。
 *
 * **2. 状态行永远占位 16dp。** 状态是从「筹备中」变成「生成大纲中」再变成「连载中」，
 * 如果只在有状态时才画那一行，整列卡片会随状态变高变低，网格会抖。
 * 永远占位 = 卡片高度只由封面比例决定，状态变化不引起重排。
 *
 * **3. 只有 `OUTLINING` 才是真在跑。** 五个状态里「生成大纲中」是唯一有活儿在干的，
 * 其余四个（筹备中/连载中/已完结/已归档）只是状态，不该跟它一样重。
 * 所以只有它在底部画一条 2dp 的进度线 —— 一格 158dp 宽的卡上，
 * 一条会动的 2dp 线是全部的动态预算。卡片本身不许动。
 */
@Composable
private fun BookCoverTile(
    project: Project,
    cover: BookCover,
    fallbackColor: Color,
    writtenCount: Int?,
    onThumbnail: suspend () -> android.graphics.Bitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, project.id, cover) {
        if (cover is BookCover.Image) {
            value = runCatching { onThumbnail() }.getOrNull()
        }
    }
    val running = project.status == ProjectStatus.OUTLINING
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .clip(RoundedCornerShape(COVER_RADIUS))
            .background(fallbackColor)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        // **只压底部。** 之前是三段渐变、顶部 0.45 alpha，用来在顶部放白字；
        // 标题挪到底之后顶部不需要任何遮罩。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.35f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.62f)
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(COVER_INSET),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                project.title,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                // Material 的 tracking 是给拉丁字母调的。中文是密排 ——
                // 「字间距大的文章，阅读速度会变慢」（中文排印三原则·原则一）。
                // 16sp 下 M3 titleMedium 默认带 0.2sp，必须显式清零。
                letterSpacing = TextUnit.Unspecified
            )
            // 永远占位，状态变化不引起卡片重排
            Text(
                project.status.label(),
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = TextUnit.Unspecified
            )
            if (writtenCount != null && writtenCount > 0) {
                Text(
                    "$writtenCount 章",
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1,
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = TextUnit.Unspecified
                )
            }
        }
        if (running) {
            // 2dp 细线，贴在卡片底边内侧（跟着 12dp 圆角裁）。
            // M3 的 TrackThickness 是 4dp；158dp 宽的卡上 4dp 太重，2dp 刚好。
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Color.Black.copy(alpha = 0.28f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.34f)
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = 0.92f))
                )
            }
        }
    }
}

/**
 * 网格上方那一行「继续写」。
 *
 * 一行，不是一张卡。刻意做成**没有嵌套控件**的样子：整行就是一个点区。
 * 之前那张卡是可点的卡里套了一个实心红按钮，按钮上还重复了一遍书名 ——
 * 卡里套控件是「这看起来像个表单」最强的信号，而重复的书名让这一屏
 * 出现了三处同一个标题。
 *
 * 高度 72dp = M3 双行列表的高度（56/72/88 三档里的中间那档）。
 */
@Composable
private fun ResumeRow(
    title: String,
    chapter: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(COVER_RADIUS))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "继续写作",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = TextUnit.Unspecified
            )
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                letterSpacing = TextUnit.Unspecified
            )
            if (chapter != null) {
                Text(
                    "续读：$chapter",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    letterSpacing = TextUnit.Unspecified
                )
            }
        }
        Icon(
            // AutoMirrored：RTL 布局下箭头要跟着翻。Material 现在会把
            // 非 automirrored 版本标成 deprecated。
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 网格里「新建小说」那一格：跟封面格同尺寸同圆角，虚线边。 */
@Composable
private fun NewBookTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .clip(RoundedCornerShape(COVER_RADIUS))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            // 虚线边框。foundation 的 `Modifier.border` 只有实线，虚线得自己画 ——
            // 零新依赖的做法是 drawBehind + Stroke + dashPathEffect。
            .drawBehind {
                drawRoundRect(
                    color = outline,
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(7.dp.toPx(), 6.dp.toPx())
                        )
                    ),
                    cornerRadius = CornerRadius(COVER_RADIUS.toPx())
                )
            }
            .semantics { contentDescription = "新建小说" },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Text(
                "新建小说",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = TextUnit.Unspecified
            )
        }
    }
}

/** 顶部「继续写」挑哪本：最近更新的那本。空表返回 null，正好对应「没有书就不显示这张卡」。 */
internal fun pickContinueWritingProject(projects: List<Project>): Project? =
    projects.maxByOrNull { it.updatedAt }

/**
 * 转屏后重新落到正在读的那一章。
 * 只认 orderIndex，不认存下来的章节对象：章节可能已被删除、或大纲已经换过版本，
 * 解析不到就退回目录页 —— 好过把读者留在一个没有正文的空阅读界面里。
 */
internal fun resolveReadingChapter(
    chapters: List<LibraryChapter>,
    orderIndex: Int?
): LibraryChapter? = orderIndex?.let { index -> chapters.firstOrNull { it.orderIndex == index } }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onContinueWriting: (Project) -> Unit,
    onOpenCreate: () -> Unit
) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val novel by viewModel.novel.collectAsStateWithLifecycle()
    val lastReadIndex by viewModel.lastReadIndex.collectAsStateWithLifecycle()
    // 封面格上的「N 章」。单独订阅而不是每次 onClick 现查 ——
    // 现查的话封面上的数字不会随写完一章而更新。
    val writtenCounts by viewModel.writtenCounts.collectAsStateWithLifecycle()
    val current = novel
    // 只记序号，不记章节对象：LibraryChapter 带着整章正文，进 Bundle 在长章节上会撞
    // Binder 的 1MB 上限（TransactionTooLarge）；正文本来就能从 chapters 里按序号再取一次。
    var readingOrderIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val reading: LibraryChapter? = resolveReadingChapter(current?.chapters.orEmpty(), readingOrderIndex)
    var dirReverse by rememberSaveable { mutableStateOf(false) }
    var chapterAction by remember { mutableStateOf<LibraryChapter?>(null) }
    var chapterRename by remember { mutableStateOf<LibraryChapter?>(null) }
    var chapterRenameTitle by remember { mutableStateOf("") }
    var chapterDelete by remember { mutableStateOf<LibraryChapter?>(null) }
    var actionTarget by remember { mutableStateOf<Project?>(null) }
    var renameTarget by remember { mutableStateOf<Project?>(null) }
    var renameTitle by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Project?>(null) }
    var themeIndex by rememberSaveable { mutableIntStateOf(0) }
    var fontSize by rememberSaveable { mutableIntStateOf(18) }
    // 换封面
    var coverTarget by remember { mutableStateOf<Project?>(null) }
    var coverBusy by remember { mutableStateOf(false) }
    var coverMessage by remember { mutableStateOf<String?>(null) }
    val covers by viewModel.covers.collectAsStateWithLifecycle()
    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val target = coverTarget ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        coverBusy = true
        viewModel.setImageCover(target.id, uri) { ok ->
            coverBusy = false
            coverMessage = if (ok) "封面已更新" else "无法读取该图片，请重新选择"
        }
    }
    LaunchedEffect(projects.map { it.id }) {
        viewModel.refreshCovers(projects.map { it.id })
    }
    // 点封面分流要用的「写了几章」计数。写完一章后这张表会自己变，
    // 同一个封面就从「点进去是写」变成「点进去是读」。
    LaunchedEffect(Unit) { viewModel.observeWrittenCounts() }
    val readerThemes = listOf(followReaderTheme()) + READER_THEMES
    // 顶部「继续读」按下的那本书：书要现打开，等目录到齐后自己落到上次那一章
    var pendingReadId by rememberSaveable { mutableStateOf<String?>(null) }
    val hero = pickContinueWritingProject(projects)
    LaunchedEffect(hero?.id) { hero?.let { viewModel.refreshLastRead(it.id) } }
    LaunchedEffect(current?.projectId, pendingReadId) {
        val id = pendingReadId
        val book = current
        if (id != null && book != null && book.projectId == id) {
            pendingReadId = null
            readingOrderIndex = book.lastReadOrderIndex ?: lastReadIndex[id]
        }
    }

    // 存 id 而不是 Project 对象，更不能放进程级单槽。
    // 以前是 `object LibraryPendingStore { var target: Project? }`：单槽、无 key、
    // 存的是整本书（含完整连续性状态）。SAF 弹窗期间只要再触发一次导出，
    // 槽里就是最后写入的那本书，而文件名用的是上一次读到的书名 ——
    // 结果是「B 书的内容写进了名为 A 的文件」。rememberSaveable 还能扛住转屏。
    var backupProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val app = com.novelforge.app.infrastructure.backup.NovelForgeRefs.application
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val wantedId = backupProjectId
        backupProjectId = null
        // 按 id 重新解析，而不是信任上一次存下来的对象
        val target = projects.firstOrNull { it.id == wantedId }
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
    // 整书 JSON 导入只有一处：ExportsScreen（设置中心的「备份与导出」）。
    // 以前这里也放一个「导入」，同一个功能两个入口两套文案，用户看到的是哪个不确定，
    // 而且顶栏按钮越多越挤 —— 书架只留「导出」：它知道是哪本书，导入是全局动作。

    // 返回逻辑：阅读 → 章节列表 → 书架 → 主页，一次只退一步
    BackHandler(enabled = reading != null) { readingOrderIndex = null }
    BackHandler(enabled = reading == null && current != null) { viewModel.close() }

    val readingTheme = if (reading != null) readerThemes[themeIndex] else null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(readingTheme?.background ?: Color.Transparent)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when {
            reading != null && current != null -> {
                val chapter = reading
                val theme = readerThemes[themeIndex]
                val writingProject = current.projectId.let { id -> projects.firstOrNull { it.id == id } }
                // 顶栏：左「目录」回本书目录 · 右「写」进写作界面 · 再右「回书架」退出这本书。
                // 以前中间只写章节名：读第 40 章时完全看不出是哪一本，而 App 里三个界面
                // 的 H1 都长成「《书名》」，只能靠副标题区分 —— 补一行书名。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { readingOrderIndex = null }) { Text("〈 目录") }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            current.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = theme.text.copy(alpha = 0.7f)
                        )
                        Text(
                            "${chapterLabel(chapter.orderIndex)} ${cleanChapterTitle(chapter.title)}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                            color = theme.text
                        )
                    }
                    // 读写两半在这里交汇：读完想改一句，直接进写作界面，
                    // 不用先退回书架再点一次封面
                    TextButton(
                        onClick = { writingProject?.let(onContinueWriting) },
                        enabled = writingProject != null
                    ) { Text("写") }
                    // 原来叫「书架 〉」：它退出的是「这本书」而不是某一屏，
                    // 顶在另一个书架按钮旁边容易被读成「去书架页」
                    TextButton(onClick = { viewModel.close(); readingOrderIndex = null }) { Text("回书架 〉") }
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
                            readingOrderIndex = it.orderIndex
                        }
                    },
                    onNext = {
                        current.chapters.firstOrNull { it.content != null && it.orderIndex > chapter.orderIndex }?.let {
                            viewModel.recordRead(current.projectId, it.orderIndex)
                            readingOrderIndex = it.orderIndex
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
                        TextButton(
                            onClick = { dirReverse = !dirReverse },
                            // 与大纲页同一种写法：「排序：」前缀让标签读起来是
                            // **状态**而不是动作，光写「↑ 正序」会被理解成
                            // "点了就变正序"，而它其实显示的就是正序。
                            modifier = Modifier.semantics {
                                stateDescription = if (dirReverse) "当前为倒序" else "当前为正序"
                            }
                        ) {
                            Text(if (dirReverse) "排序：↓ 倒序" else "排序：↑ 正序")
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
                            readingOrderIndex = resumeChapter.orderIndex
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
                        "尚未生成大纲。请先在小说内生成大纲，再返回阅读。",
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
                                            readingOrderIndex = chapter.orderIndex
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
                                // 这里原来每张卡片都跟一句「点击阅读 · 长按重命名/删除」，
                                // 三十张章节就是同一句话重复三十遍 —— 而长按是 Android
                                // 的通用手势，不需要逐个卡片教。删掉之后卡片从两行变一行，
                                // 一屏能多看不少章。
                                //
                                // 只给**没写正文**的章节留一行状态说明：那种卡片点下去
                                // 什么也不会发生（onClick 里判了 content != null），
                                // 不标出来就是个死区域。但只写「未生成正文」，
                                // 不再附带「长按可删除」——那半句才是冗余的来源。
                                if (chapter.content == null) {
                                    Text(
                                        "未生成正文",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            projects.isEmpty() -> {
                // 书架是 startDestination，这里**不能**有返回。
                //
                // 原来这里传的是 `navController.popBackStack()`，看着像"没东西可弹、
                // 按了等于没按"—— 我之前也是这么说的，**错的**。查了 Navigation 2.8.5
                // 的源码：`popBackStack()` 走的是 `inclusive = true`
                // （NavController.kt:450-457），所以它会先弹掉 books，再把仅剩的
                // 根图也弹掉（NavController.kt:1068-1070），backQueue 直接变空。
                // 于是 `NavHost` 的 `visibleEntries.lastOrNull()` 是 null，整个
                // NavHost 一个节点都不发射 —— **白屏，底栏还在，导航图已销毁**，
                // 只能杀掉应用重开。
                //
                // 而且这是 app 里用得最多的屏幕上一次点击就能触发的路径。
                // 现在没有返回可点；系统返回手势仍然能退出应用，那本来就是对的。
                PaperTopBar(title = "书架")
                // 空书架原来只有两行字加一个全宽按钮，**顶在屏幕最上面**，
                // 下面是一整片空白。空状态的意义是「这里该有东西」，
                // 所以它得先占住视觉重心。
                //
                // 但**不预置一个虚线封面格**：那是「已经有一本书、只是没设封面」，
                // 跟「一本书都没有」不是一回事，空书架假装有一本会更误导。
                //
                // 按 M3 的说法（列表引导 16dp），按钮也收窄 —— 全宽按钮在空屏上
                // 读起来像个等着被填的输入框，160dp 居中的那个才读起来像动作。
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "书架还空着",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = TextUnit.Unspecified
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "写下第一本",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = TextUnit.Unspecified
                    )
                    Spacer(Modifier.height(20.dp))
                    PaperButton(
                        "新建小说",
                        onOpenCreate,
                        modifier = Modifier.width(160.dp),
                        accent = true
                    )
                }
            }
            else -> {
                // 同上：startDestination 不能有返回（popBackStack inclusive=true 会
                // 把 backQueue 弹空 → 白屏 + 导航图销毁）。理由见空书架那一处。
                //
                // **不挂 subtitle。** 原来那句「点封面阅读，长按可写作、换封面、
                // 重命名、删除」有两个问题：它**是错的**（点封面现在只打开目录，
                // 不跳阅读器，见下面 onClick 的注释），它教用户去做一件不会发生的事；
                // 而且这就是我自己在正文页清掉的「每页一条重复操作提示」，
                // 在页面尺度上又长回来一次。长按是平台惯例，不需要教。
                PaperTopBar(title = "书架")
                // 「继续写」从网格里搬出来了。
                //
                // 之前它是 `item(key = "hero-continue")`，也就是 **2 列网格里的
                // 单独一格** —— 一格占半行，右边空一半。而且那张卡上的书名
                // 出现了三次（小标签「继续写作」/ 标题 / 按钮「继续写作《书名》」），
                // 底下还嵌了一个实心红按钮：一个可点的卡里面再套一个可点的控件，
                // 这是「这看起来像个表单」最强的信号。
                //
                // 现在是一行 72dp 的整行条（= M3 双行列表的高度），放在网格**外面**。
                // 一屏只有一个续读入口，不在网格里，不重复书名，也没有嵌套控件。
                // Kindle / Apple Books 的「继续读」也是这么摆的：网格是**认得出书**
                // 的地方，续读是**回到刚才那一本**的动作，两件事分开。
                hero?.let { target ->
                    ResumeRow(
                        title = target.title,
                        chapter = lastReadIndex[target.id]?.let { chapterLabel(it) },
                        onClick = {
                            if (lastReadIndex[target.id] != null) {
                                pendingReadId = target.id
                            }
                            viewModel.open(target)
                        },
                        modifier = Modifier.padding(
                            start = SHELF_PADDING,
                            end = SHELF_PADDING,
                            top = 4.dp,
                            bottom = 4.dp
                        )
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    // M3 的招牌观感：**间距 == 圆角**。原来间距 10dp、圆角 10dp
                    // 是「碰巧相等」，而 10dp 根本不在 M3 的形状刻度
                    // （4/8/12/16/28）上。12dp 是 M3 三种卡片（filled/elevated/
                    // outlined）共同的 ContainerShape = CornerMedium。
                    horizontalArrangement = Arrangement.spacedBy(COVER_GUTTER),
                    verticalArrangement = Arrangement.spacedBy(COVER_GUTTER),
                    contentPadding = PaddingValues(
                        start = SHELF_PADDING,
                        end = SHELF_PADDING,
                        top = 8.dp,
                        // 底部留白：让最后一排卡片不贴着系统导航条
                        bottom = 24.dp
                    )
                ) {
                    // 「新建小说」是网格里的**第一格**，不是底部一个全宽按钮。
                    // 原来那个按钮浮在一大片死空间里（网格拿 weight(1f)，
                    // 只有两行内容，剩下的全空，按钮被推到最底下一个描边长条，
                    // 看着像没填完的表单）。放进网格第一格之后：位置 0 在
                    // 3–8 本书的量级下永远不用滚就能看见，而且它和封面格
                    // 同尺寸同圆角，视觉上属于这一屏。
                    item(key = "new-book") {
                        NewBookTile(onClick = onOpenCreate)
                    }
                    items(projects, key = { it.id }) { project ->
                        val cover = covers[project.id] ?: BookCover.Default
                        val fallback = COVER_COLORS[
                            (cover as? BookCover.Preset)?.index
                                ?: abs(project.id.hashCode()) % COVER_COLORS.size
                        ]
                        BookCoverTile(
                            project = project,
                            cover = cover,
                            fallbackColor = fallback,
                            writtenCount = writtenCounts[project.id],
                            onThumbnail = { viewModel.coverThumbnail(project.id) },
                            // 点封面 = 进这本书的**目录**。不自动跳进阅读器。
                            //
                            // 这里原来还多接了一句 `pendingReadId = project.id`，
                            // 于是点封面会直接进阅读器并落在上次读到的那一章。
                            // 那是我自己加的 —— 用户只说了「点封面可以阅读」，
                            // 没说要点完就跳：「自作主张」。
                            //
                            // 想要「读上次那一章」是另一个动作，书内目录顶部就有
                            // 「▶ 续读：第 N 章」那个按钮（见下面的 resumeChapter），
                            // 上面那行 ResumeRow 是同一个动作。
                            //
                            // 一次都没写过的书仍然送去写作 —— 空书进目录是一片
                            // 全「未生成正文」的死胡同，而开始写是所有书的必经一步。
                            onClick = {
                                if (viewModel.tapCoverGoesToReading(viewModel.writtenCount(project.id))) {
                                    viewModel.open(project)
                                } else {
                                    onContinueWriting(project)
                                }
                            },
                            // 长按不变，还是那个管理菜单：换封面 / 重命名 / 删除 /
                            // 导出备份 / 继续写作。菜单里第一项是「继续写作」，
                            // 所以「长按去写书」这条路也还在。
                            onLongClick = { actionTarget = project }
                        )
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
                    Text("选择操作：")
                    // 前两项是「去干什么」，后四项是「管这本书」。
                    // 「继续写作」排第一：点封面已经改成去阅读（空书才去写），
                    // 所以菜单是**唯一**能主动进写作的入口，不写这行菜单就只剩管理动作。
                    // 顶栏副标题已经把这两条手势说清楚了，这里不重复。
                    Button(
                        onClick = {
                            actionTarget = null
                            onContinueWriting(target)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("继续写作") }
                    Button(
                        onClick = {
                            actionTarget = null
                            viewModel.open(target)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("阅读") }
                    TextButton(onClick = {
                        backupProjectId = target.id
                        exportLauncher.launch(app.backupStore.suggestedFileName(target.title))
                    }) {
                        Text("导出整本备份（大纲 + 正文）")
                    }
                    TextButton(onClick = {
                        actionTarget = null
                        coverTarget = target
                        coverMessage = null
                    }) { Text("换封面") }
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

    coverTarget?.let { target ->
        val current = covers[target.id] ?: BookCover.Default
        AlertDialog(
            onDismissRequest = { if (!coverBusy) coverTarget = null },
            title = { Text("《${target.title}》封面") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "选择一种底色，或使用自己的图片。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 底色：和书架上原来的观感同一组色
                    for ((rowIndex, row) in COVER_COLORS.chunked(3).withIndex()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEachIndexed { columnIndex, color ->
                                val index = rowIndex * 3 + columnIndex
                                val selected = current is BookCover.Preset && current.index == index
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(color)
                                        .then(
                                            if (selected) {
                                                Modifier.border(
                                                    3.dp,
                                                    MaterialTheme.colorScheme.primary,
                                                    RoundedCornerShape(8.dp)
                                                )
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .clickable(enabled = !coverBusy) {
                                            viewModel.setPresetCover(target.id, index)
                                        }
                                )
                            }
                            repeat(3 - row.size) { Box(Modifier.size(52.dp)) }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            coverPicker.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        enabled = !coverBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (coverBusy) "处理中…" else "从相册选择") }
                    if (current != BookCover.Default) {
                        TextButton(
                            onClick = { viewModel.resetCover(target.id) },
                            enabled = !coverBusy
                        ) { Text("恢复默认") }
                    }
                    coverMessage?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { coverTarget = null }) { Text("完成") }
            }
        )
    }

    chapterAction?.let { chapter ->
        val novelTitle = novel?.title.orEmpty()
        AlertDialog(
            onDismissRequest = { chapterAction = null },
            title = { Text("${chapterLabel(chapter.orderIndex)} · ${cleanChapterTitle(chapter.title)}") },
            text = { Text("《$novelTitle》· 选择操作：") },
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
                    if (reading?.orderIndex == chapter.orderIndex) readingOrderIndex = null
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
            title = { Text("重命名小说") },
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
            title = { Text("删除小说") },
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
