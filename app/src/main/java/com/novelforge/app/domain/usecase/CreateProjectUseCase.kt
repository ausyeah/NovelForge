package com.novelforge.app.domain.usecase

import com.novelforge.app.domain.model.Project
import com.novelforge.app.domain.repository.ProjectRepository
import java.util.UUID

class CreateProjectUseCase(
    private val repository: ProjectRepository,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    suspend operator fun invoke(title: String): Project {
        require(title.isNotBlank()) { "项目标题不能为空" }
        val timestamp = now()
        val project = Project(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            createdAt = timestamp,
            updatedAt = timestamp
        )
        repository.saveProject(project)
        return project
    }
}
