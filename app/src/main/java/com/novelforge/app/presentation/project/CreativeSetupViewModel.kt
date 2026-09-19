package com.novelforge.app.presentation.project

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.novelforge.app.domain.model.CreativeConfig
import com.novelforge.app.domain.model.FlowState
import com.novelforge.app.domain.model.MAX_CHAPTER_COUNT
import com.novelforge.app.domain.model.MIN_CHAPTER_COUNT
import com.novelforge.app.domain.model.Project
import com.novelforge.app.domain.model.ProjectStatus
import com.novelforge.app.domain.model.QuestData
import com.novelforge.app.domain.model.ThrillFrequency
import com.novelforge.app.domain.model.Tone
import com.novelforge.app.domain.model.WritingStyle
import com.novelforge.app.domain.repository.ProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal fun validateCreativeSetup(config: CreativeConfig, questData: QuestData): String? {
    val answers = questData.answers
    if (answers["premise"].orEmpty().trim().isBlank()) {
        return "请填写题材创意或一句话设定"
    }
    if (answers["protagonist"].orEmpty().trim().isBlank()) {
        return "请填写主角设定"
    }
    if (answers["conflict"].orEmpty().trim().isBlank()) {
        return "请填写核心冲突"
    }
    if (config.genreTags.map(String::trim).filter(String::isNotBlank).isEmpty()) {
        return "请至少选择一个题材标签"
    }
    if (config.writingStyle == WritingStyle.CUSTOM && config.customWritingStyle?.trim().isNullOrBlank()) {
        return "请填写自定义文笔风格"
    }
    if (config.tone == Tone.CUSTOM && config.customTone?.trim().isNullOrBlank()) {
        return "请填写自定义笔风类型"
    }
    if (
        config.thrillFrequency == ThrillFrequency.CUSTOM &&
        config.customThrillFrequency?.trim().isNullOrBlank()
    ) {
        return "请填写自定义爽感频率"
    }
    if (config.chapterCount !in MIN_CHAPTER_COUNT..MAX_CHAPTER_COUNT) {
        return "章节数量必须在 $MIN_CHAPTER_COUNT 到 $MAX_CHAPTER_COUNT 之间"
    }
    if (config.targetLength !in MIN_TARGET_LENGTH..MAX_TARGET_LENGTH) {
        return "每章目标字数必须在 500 到 10000 之间"
    }
    if (config.writingStyleIntensity !in 1..5 || config.toneIntensity !in 1..5) {
        return "风格强度必须在 1 到 5 之间"
    }
    return null
}

private const val MIN_TARGET_LENGTH = 500
private const val MAX_TARGET_LENGTH = 10_000

internal fun isCreativeSetupComplete(project: Project): Boolean =
    project.creativeConfig?.let { validateCreativeSetup(it, project.questData) == null } == true

class CreativeSetupViewModel(
    private val projectId: String,
    private val repository: ProjectRepository,
    private val now: () -> Long = { System.currentTimeMillis() }
) : ViewModel() {
    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    init {
        viewModelScope.launch {
            _project.value = repository.getProject(projectId)
            if (_project.value == null) {
                _error.value = "项目不存在"
            }
        }
    }

    fun save(config: CreativeConfig, questData: QuestData, onSaved: () -> Unit) {
        val validationError = validateCreativeSetup(config, questData)
        if (validationError != null) {
            _error.value = validationError
            return
        }
        if (_saving.value) return

        val normalizedAnswers = questData.answers.mapValues { (_, value) -> value.trim() }
        val normalizedConfig = config.copy(
            genreTags = config.genreTags.map(String::trim).filter(String::isNotBlank).distinct(),
            customWritingStyle = config.customWritingStyle?.trim()?.takeIf(String::isNotBlank),
            writingStyleIntensity = config.writingStyleIntensity.coerceIn(1, 5),
            customTone = config.customTone?.trim()?.takeIf(String::isNotBlank),
            toneIntensity = config.toneIntensity.coerceIn(1, 5),
            customThrillFrequency = config.customThrillFrequency?.trim()?.takeIf(String::isNotBlank)
        )
        _saving.value = true
        viewModelScope.launch {
            runCatching {
                val current = requireNotNull(repository.getProject(projectId)) { "项目不存在" }
                current.copy(
                    questData = QuestData(
                        schemaVersion = current.questData.schemaVersion,
                        answers = normalizedAnswers
                    ),
                    creativeConfig = normalizedConfig,
                    flowState = FlowState.OUTLINE_GENERATE,
                    status = ProjectStatus.OUTLINING,
                    updatedAt = now()
                ).also { next ->
                    repository.saveProject(next)
                    _project.value = next
                }
            }.onSuccess {
                _error.value = null
                onSaved()
            }.onFailure {
                _error.value = it.message ?: "保存创作设置失败"
            }
            _saving.value = false
        }
    }

    fun clearError() {
        _error.value = null
    }

    class Factory(
        private val projectId: String,
        private val repository: ProjectRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CreativeSetupViewModel(projectId, repository) as T
    }
}
