package com.novelforge.app.presentation.chapter

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.novelforge.app.domain.model.ChapterRevision
import com.novelforge.app.domain.model.ChapterStatus
import com.novelforge.app.domain.model.GenerationJob
import com.novelforge.app.domain.model.Project
import com.novelforge.app.domain.model.GenerationJobStatus
import com.novelforge.app.domain.model.GenerationPurpose
import com.novelforge.app.domain.model.OutlineItem
import com.novelforge.app.domain.repository.ChapterRepository
import com.novelforge.app.domain.repository.GenerationRepository
import com.novelforge.app.domain.repository.OutlineRepository
import com.novelforge.app.domain.repository.ProjectRepository
import com.novelforge.app.infrastructure.jobs.GenerationRuntime
import com.novelforge.app.infrastructure.llm.ChapterContext
import com.novelforge.app.infrastructure.llm.MemorySelector
import com.novelforge.app.infrastructure.llm.chapterMemoryHint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 记忆排除集的存档键。
 *
 * 为什么必须存档：这两个集合以前只躺在 ViewModel 的字段里，而这个 ViewModel 属于
 * 本目的地的 NavBackStackEntry —— 条目一被弹出（换章、离开这本书、进程被杀重建）就跟着清空。
 * 用户看到的样子是"取消了几个角色 → 整理本书记忆 → 返回 → 裁剪悄悄没了"，
 * 而正文和任务都还在，没有任何信号告诉他刚才那次裁剪丢了。
 * 这些开关存在的意义就是让作家决定模型这一章看得到什么，静默丢掉是正确性问题，不只是体验问题。
 */
internal const val EXCLUDED_CHARACTERS_KEY = "chapter.excludedCharacterIds"
internal const val EXCLUDED_THREADS_KEY = "chapter.excludedThreadIds"

/**
 * 从存档里读回排除集。刻意按 [List] 而不是 [Set] 取，再逐个过滤出 String：
 * 存档是可能来自旧版本的裸数据，强转成 Set<String> 会在第一次使用时才炸。
 */
internal fun exclusionFromSavedState(raw: List<*>?): Set<String> =
    raw.orEmpty().filterIsInstance<String>().toSet()

/**
 * 写回时统一存 [ArrayList] 的 String：这是 Bundle 的原生类型，进程被杀后能原样读回。
 * 不用 StringSet —— Bundle 的 getStringSet 要求 put 和 get 拿到同一个 Set 实例，
 * 旧系统上还会对返回的集合直接抛异常，集合语义在这里一点收益都没有。
 */
internal fun exclusionToSavedState(ids: Set<String>): ArrayList<String> = ArrayList(ids)

/** 相邻章节判定里的"上一章"：留洞后 orderIndex 不连续，取序号更小的最近一章，不做 -1 精确匹配。 */
internal fun previousChapterIn(chapters: List<OutlineItem>, orderIndex: Int): OutlineItem? =
    chapters.filter { it.orderIndex < orderIndex }.maxByOrNull { it.orderIndex }

/** 相邻章节判定里的"下一章"：与 [previousChapterIn] 对称，取序号更大的最近一章。 */
internal fun nextChapterIn(chapters: List<OutlineItem>, orderIndex: Int): OutlineItem? =
    chapters.filter { it.orderIndex > orderIndex }.minByOrNull { it.orderIndex }

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChapterViewModel(
    private val projectId: String,
    private val targetId: String?,
    private val savedStateHandle: SavedStateHandle,
    private val projectRepository: ProjectRepository,
    private val outlineRepository: OutlineRepository,
    private val chapterRepository: ChapterRepository,
    private val generationRepository: GenerationRepository,
    private val generationRuntime: GenerationRuntime
) : ViewModel() {
    val outlines: StateFlow<List<com.novelforge.app.domain.model.OutlineVersion>> =
        outlineRepository.observeVersions(projectId).stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )
    val revisions: StateFlow<List<ChapterRevision>> =
        chapterRepository.observeRevisions(projectId).stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    private val activeJobId = MutableStateFlow<String?>(null)
    val activeJob: StateFlow<GenerationJob?> = activeJobId
        .observeJob()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val project: StateFlow<Project?> = projectRepository.observeProjects()
        .map { list -> list.firstOrNull { it.id == projectId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // 排除集从 SavedStateHandle 起手而不是从空集起手：重建之后界面上的 MemoryStrip
    // 与喂给模型的记忆切片必须还是同一批裁剪，否则作者会看到"这次会带上 6 个角色"
    // 却不知道这 6 个是他自己刚刚取消掉的
    private val _excludedCharacters = MutableStateFlow(
        exclusionFromSavedState(savedStateHandle.get<List<*>>(EXCLUDED_CHARACTERS_KEY))
    )
    val excludedCharacters: StateFlow<Set<String>> = _excludedCharacters.asStateFlow()

    private val _excludedThreads = MutableStateFlow(
        exclusionFromSavedState(savedStateHandle.get<List<*>>(EXCLUDED_THREADS_KEY))
    )
    val excludedThreads: StateFlow<Set<String>> = _excludedThreads.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching {
                activeJobId.value = generationRepository.findLatestJob(
                    projectId,
                    GenerationPurpose.CHAPTER.name,
                    targetId ?: firstChapterId()
                )?.id
            }.onFailure { _error.value = "章节进度读取失败" }
        }
    }

    fun generate(chapter: OutlineItem) {
        _error.value = null
        viewModelScope.launch {
            val project = projectRepository.getProject(projectId)
            if (project == null) {
                _error.value = "项目不存在"
                return@launch
            }
            // 和界面上「上一章」按钮必须指向同一章：这两处曾经各算一次，
            // 留着洞的时候可能各说各话，作者点回去看的那一章和喂给本章 prompt 的上一章会对不上
            val previous = previousChapterFor(chapter)?.let { previousChapter ->
                // 必须取最新修订，否则上一章重新生成后续写会串回旧稿
                revisions.value
                    .filter { it.outlineItemId == previousChapter.id }
                    .maxByOrNull { it.revision }
            }
            val memory = MemorySelector.select(
                state = project.continuityState,
                excludedCharacterIds = _excludedCharacters.value,
                excludedThreads = _excludedThreads.value,
                inputBudget = project.creativeConfig?.inputBudget ?: 8_000,
                chapterHint = chapterMemoryHint(chapter.title, chapter.summary, chapter.characterChanges)
            )
            val context = ChapterContext(
                continuityState = memory.continuity,
                characters = memory.characters,
                previousSummary = previous?.summary,
                previousTail = previous?.content?.takeLast(1_500)
            )
            runCatching {
                generationRuntime.queueChapter(projectId, chapter, context)
            }.onSuccess { activeJobId.value = it.id }
                .onFailure { _error.value = it.message ?: "无法创建章节生成任务" }
        }
    }

    fun cancel() {
        val jobId = activeJob.value?.id ?: return
        viewModelScope.launch { runCatching { generationRuntime.cancel(jobId) } }
    }

    fun clearError() {
        _error.value = null
    }

    fun toggleCharacter(id: String) {
        _excludedCharacters.value = _excludedCharacters.value.toggle(id).also {
            savedStateHandle[EXCLUDED_CHARACTERS_KEY] = exclusionToSavedState(it)
        }
    }

    fun toggleThread(thread: String) {
        _excludedThreads.value = _excludedThreads.value.toggle(thread).also {
            savedStateHandle[EXCLUDED_THREADS_KEY] = exclusionToSavedState(it)
        }
    }

    fun revisionFor(chapter: OutlineItem): ChapterRevision? =
        revisions.value.filter { it.outlineItemId == chapter.id }.maxByOrNull { it.revision }

    /** 目标章是不是已经有正文。界面上「下一章」按钮据此决定写"生成"还是只写"打开"。 */
    fun hasRevisionFor(chapter: OutlineItem?): Boolean =
        chapter != null && revisionFor(chapter) != null

    /**
     * 写作流里的上一章。
     *
     * 留洞之后 orderIndex 不连续（大纲里删过章节），所以按"序号更小的最近一章"找，
     * 不能做 -1 精确匹配 —— 精确匹配在留过洞的书里会永远返回 null，界面上就一直显示没有上一章。
     * 第一章没有上一章，返回 null，界面据此置灰。
     * chapter 为 null（大纲里已经找不到这一项）时也给 null：不知道自己在第几章的时候，
     * 不该给出去错的方向。
     */
    fun previousChapterFor(chapter: OutlineItem?): OutlineItem? =
        chapter?.let { previousChapterIn(chapters(), it.orderIndex) }

    /** [previousChapterFor] 的对称版本：取序号更大的最近一章，最后一章为 null。 */
    fun nextChapterFor(chapter: OutlineItem?): OutlineItem? =
        chapter?.let { nextChapterIn(chapters(), it.orderIndex) }

    fun previousRevisionFor(chapter: OutlineItem): ChapterRevision? {
        val history = revisions.value.filter { it.outlineItemId == chapter.id }.sortedBy { it.revision }
        return history.dropLast(1).lastOrNull()
    }

    fun restorePrevious(chapter: OutlineItem) {
        viewModelScope.launch {
            runCatching {
                val history = revisions.value.filter { it.outlineItemId == chapter.id }.sortedBy { it.revision }
                val current = history.lastOrNull() ?: error("还没有正文")
                val previous = history.dropLast(1).lastOrNull() ?: error("没有更早的修订")
                chapterRepository.save(
                    previous.copy(
                        id = UUID.randomUUID().toString(),
                        revision = current.revision + 1,
                        status = ChapterStatus.FINALIZED,
                        createdAt = System.currentTimeMillis()
                    )
                )
            }.onFailure { _error.value = it.message ?: "无法回到上一稿" }
        }
    }

    private suspend fun firstChapterId(): String? =
        outlineRepository.latest(projectId)?.chapters?.minByOrNull { it.orderIndex }?.id

    /** 最新一版大纲的章节表（原始顺序，判定相邻章节时自行取序）。 */
    private fun chapters(): List<OutlineItem> = outlines.value.firstOrNull()?.chapters.orEmpty()

    private fun Set<String>.toggle(value: String): Set<String> =
        if (value in this) this - value else this + value

    private fun MutableStateFlow<String?>.observeJob(): Flow<GenerationJob?> = flatMapLatest { id ->
        if (id == null) flowOf(null) else generationRepository.observeJob(id)
    }

    class Factory(
        private val projectId: String,
        private val targetId: String?,
        private val projectRepository: ProjectRepository,
        private val outlineRepository: OutlineRepository,
        private val chapterRepository: ChapterRepository,
        private val generationRepository: GenerationRepository,
        private val generationRuntime: GenerationRuntime
    ) : ViewModelProvider.Factory {
        /**
         * 存档句柄从 [extras] 里取，而不是让调用方传进来。
         *
         * NavBackStackEntry 的 defaultViewModelCreationExtras 带着这一条目的 SavedStateRegistry，
         * 于是记忆排除集是"跟着这个目的地条目"存的：进程被杀、这一页被整体卸载重建之后，
         * 读到的是同一份裁剪。
         *
         * 框架只认带 CreationExtras 的这个重载 —— 不带 extras 的那个在 lifecycle 2.8 里
         * 直接抛 "not supported and considered an error"。副作用是好事：
         * 调用点一个字都不用改，还是 viewModel(factory = ChapterViewModel.Factory(...))。
         */
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras
        ): T = ChapterViewModel(
            projectId,
            targetId,
            extras.createSavedStateHandle(),
            projectRepository,
            outlineRepository,
            chapterRepository,
            generationRepository,
            generationRuntime
        ) as T
    }
}
