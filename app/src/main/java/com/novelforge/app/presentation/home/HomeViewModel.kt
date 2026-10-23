package com.novelforge.app.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.novelforge.app.domain.model.Project
import com.novelforge.app.domain.repository.ProjectRepository
import com.novelforge.app.domain.usecase.CreateProjectUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val repository: ProjectRepository) : ViewModel() {
    private val createProject = CreateProjectUseCase(repository)
    private val _operationError = MutableStateFlow<String?>(null)
    val operationError: StateFlow<String?> = _operationError.asStateFlow()

    val projects: StateFlow<List<Project>> = repository.observeProjects().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    fun createProject(title: String, onCreated: (Project) -> Unit) {
        viewModelScope.launch {
            runCatching { createProject(title) }
                .onSuccess(onCreated)
                .onFailure { _operationError.value = it.message ?: "创建项目失败" }
        }
    }

    fun renameProject(projectId: String, title: String, onCompleted: () -> Unit) {
        val normalizedTitle = title.trim()
        if (normalizedTitle.isBlank()) {
            _operationError.value = "项目名称不能为空"
            return
        }
        viewModelScope.launch {
            runCatching {
                val project = requireNotNull(repository.getProject(projectId)) { "项目不存在" }
                repository.saveProject(
                    project.copy(
                        title = normalizedTitle,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }.onSuccess {
                _operationError.value = null
                onCompleted()
            }.onFailure {
                _operationError.value = it.message ?: "重命名项目失败"
            }
        }
    }

    fun deleteProject(projectId: String, onCompleted: () -> Unit) {
        viewModelScope.launch {
            runCatching { repository.deleteProject(projectId) }
                .onSuccess {
                    _operationError.value = null
                    onCompleted()
                }
                .onFailure {
                    _operationError.value = it.message ?: "删除项目失败"
                }
        }
    }

    fun clearOperationError() {
        _operationError.value = null
    }

    class Factory(private val repository: ProjectRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(repository) as T
    }
}
