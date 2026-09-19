package com.novelforge.app.presentation.outline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.novelforge.app.domain.model.FlowState
import com.novelforge.app.domain.model.GenerationJob
import com.novelforge.app.domain.model.GenerationJobStatus
import com.novelforge.app.domain.model.GenerationPurpose
import com.novelforge.app.domain.model.OutlineItem
import com.novelforge.app.domain.model.OutlineVersion
import com.novelforge.app.domain.model.ProjectStatus
import com.novelforge.app.domain.repository.ChapterRepository
import com.novelforge.app.domain.repository.GenerationArtifactRepository
import com.novelforge.app.domain.repository.GenerationRepository
import com.novelforge.app.domain.repository.OutlineRepository
import com.novelforge.app.domain.repository.ProjectRepository
import com.novelforge.app.infrastructure.jobs.GenerationRuntime
import com.novelforge.app.infrastructure.llm.JsonResponseValidator
import com.novelforge.app.infrastructure.llm.JsonValidationResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OutlineViewModel(
    private val projectId: String,
    private val projectRepository: ProjectRepository,
    private val outlineRepository: OutlineRepository,
    private val chapterRepository: ChapterRepository,
    private val generationRepository: GenerationRepository,
    private val generationArtifactRepository: GenerationArtifactRepository,
    private val generationRuntime: GenerationRuntime
) : ViewModel() {
    val versions: StateFlow<List<OutlineVersion>> = outlineRepository.observeVersions(projectId).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    /** 已保存正文的章节 id 集合（总览卡片颜色区分用） */
    val writtenChapterIds: StateFlow<Set<String>> = chapterRepository
        .observeRevisions(projectId)
        .map { revisions -> revisions.map { it.outlineItemId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val activeJobId = MutableStateFlow<String?>(null)
    val activeJob: StateFlow<GenerationJob?> = activeJobId
        .observeJob()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val validator = JsonResponseValidator()

    val autoRun: StateFlow<Boolean> = generationRuntime.autoRunEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 全自动写作时正在生成的正文任务（跨章节自动追踪最新一个） */
    val chapterJob: StateFlow<GenerationJob?> = generationRepository
        .observeLatestJob(projectId, GenerationPurpose.CHAPTER.name, null)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setAutoRun(enabled: Boolean) {
        viewModelScope.launch { generationRuntime.setAutoRun(enabled) }
    }

    /** 一键全自动：打开开关并从当前进度直接开跑 */
    fun startAutoRun() {
        _error.value = null
        viewModelScope.launch {
            runCatching { generationRuntime.startAutoRun(projectId) }
                .onSuccess {
                    activeJobId.value = generationRepository.findLatestJob(
                        projectId, GenerationPurpose.OUTLINE.name, null
                    )?.id
                }
                .onFailure { _error.value = it.message ?: "无法启动全自动生成" }
        }
    }

    init {
        viewModelScope.launch {
            activeJobId.value = generationRepository.findLatestJob(
                projectId,
                GenerationPurpose.OUTLINE.name,
                null
            )?.id
        }
    }

    fun generate(continueFromExisting: Boolean = false) {
        _error.value = null
        viewModelScope.launch {
            runCatching {
                generationRuntime.queueOutline(
                    projectId,
                    continueFromExisting = continueFromExisting
                )
            }
                .onSuccess { activeJobId.value = it.id }
                .onFailure { _error.value = it.message ?: "无法创建大纲生成任务" }
        }
    }

    fun cancel() {
        val jobId = activeJob.value?.id ?: return
        viewModelScope.launch { generationRuntime.cancel(jobId) }
    }

    fun saveEditedItems(items: List<OutlineItem>) {
        val current = versions.value.firstOrNull() ?: return
        val normalized = items.mapIndexed { index, item -> item.copy(orderIndex = index) }
        if (normalized == current.chapters) return
        viewModelScope.launch {
            runCatching {
                val next = current.copy(
                    id = UUID.randomUUID().toString(),
                    version = current.version + 1,
                    chapters = normalized,
                    diffSummary = "用户编辑大纲",
                    createdAt = System.currentTimeMillis()
                )
                projectRepository.getProject(projectId)?.let { project ->
                    generationArtifactRepository.saveOutlineAndProject(
                        next,
                        project.copy(
                            activeOutlineVersionId = next.id,
                            status = ProjectStatus.OUTLINING,
                            flowState = FlowState.OUTLINE_EDIT,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }.onFailure { _error.value = it.message ?: "保存大纲失败" }
        }
    }

    /**
     * 任务卡在“需要处理”时，允许用户手动修复模型原始输出：
     * 校验通过则直接落为新的大纲版本，并把任务标记为完成。
     */
    fun saveRawOutline(raw: String) {
        when (val result = validator.parseOutline(raw)) {
            is JsonValidationResult.Failure -> {
                _error.value = "原始输出无法解析：${result.reason}，请修改后再保存，或点击重试重新生成"
            }
            is JsonValidationResult.Success -> viewModelScope.launch {
                runCatching {
                    val current = versions.value.firstOrNull()
                    val normalized = result.value.mapIndexed { index, item ->
                        item.copy(orderIndex = index)
                    }
                    val next = OutlineVersion(
                        id = UUID.randomUUID().toString(),
                        projectId = projectId,
                        version = (current?.version ?: 0) + 1,
                        chapters = normalized,
                        diffSummary = "手动修复模型输出",
                        createdAt = System.currentTimeMillis()
                    )
                    projectRepository.getProject(projectId)?.let { project ->
                        generationArtifactRepository.saveOutlineAndProject(
                            next,
                            project.copy(
                                activeOutlineVersionId = next.id,
                                status = ProjectStatus.OUTLINING,
                                flowState = FlowState.OUTLINE_EDIT,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                    val finishedJob = activeJob.value?.takeIf {
                        it.status in setOf(
                            GenerationJobStatus.NEEDS_USER,
                            GenerationJobStatus.FAILED,
                            GenerationJobStatus.RECOVERABLE_PARTIAL,
                            GenerationJobStatus.CANCELLED
                        )
                    }
                    finishedJob?.let { job ->
                        generationRepository.updateJob(
                            job.copy(
                                status = GenerationJobStatus.COMPLETED,
                                errorType = null,
                                errorMessage = null,
                                partialContent = result.normalizedJson,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                }.onFailure { _error.value = it.message ?: "保存大纲失败" }
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    private fun MutableStateFlow<String?>.observeJob(): Flow<GenerationJob?> = flatMapLatest { id ->
        if (id == null) flowOf(null) else generationRepository.observeJob(id)
    }

    class Factory(
        private val projectId: String,
        private val projectRepository: ProjectRepository,
        private val outlineRepository: OutlineRepository,
        private val chapterRepository: ChapterRepository,
        private val generationRepository: GenerationRepository,
        private val generationArtifactRepository: GenerationArtifactRepository,
        private val generationRuntime: GenerationRuntime
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = OutlineViewModel(
            projectId,
            projectRepository,
            outlineRepository,
            chapterRepository,
            generationRepository,
            generationArtifactRepository,
            generationRuntime
        ) as T
    }
}
