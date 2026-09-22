package com.novelforge.app.presentation.chapter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChapterViewModel(
    private val projectId: String,
    private val targetId: String?,
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

    private val _excludedCharacters = MutableStateFlow<Set<String>>(emptySet())
    val excludedCharacters: StateFlow<Set<String>> = _excludedCharacters.asStateFlow()

    private val _excludedThreads = MutableStateFlow<Set<String>>(emptySet())
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
            val previous = outlines.value.firstOrNull()?.chapters
                ?.filter { it.orderIndex < chapter.orderIndex }
                ?.maxByOrNull { it.orderIndex }
                ?.let { previousChapter ->
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
        _excludedCharacters.value = _excludedCharacters.value.toggle(id)
    }

    fun toggleThread(thread: String) {
        _excludedThreads.value = _excludedThreads.value.toggle(thread)
    }

    fun revisionFor(chapter: OutlineItem): ChapterRevision? =
        revisions.value.filter { it.outlineItemId == chapter.id }.maxByOrNull { it.revision }

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
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChapterViewModel(
            projectId,
            targetId,
            projectRepository,
            outlineRepository,
            chapterRepository,
            generationRepository,
            generationRuntime
        ) as T
    }
}
