package com.novelforge.app.data.repository

import androidx.room.withTransaction
import com.novelforge.app.data.local.AppDatabase
import com.novelforge.app.data.local.ProjectDao
import com.novelforge.app.data.local.orFallback
import com.novelforge.app.data.local.toDomain
import com.novelforge.app.data.local.toEntity
import com.novelforge.app.domain.model.Project
import com.novelforge.app.domain.repository.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class RoomProjectRepository(private val database: AppDatabase) : ProjectRepository {
    private val dao: ProjectDao = database.projectDao()

    override fun observeProjects(): Flow<List<Project>> = dao.observeAll().map { entities ->
        entities.map { it.toDomain() }
    }.flowOn(Dispatchers.Default).orFallback(emptyList())

    override suspend fun getProject(id: String): Project? = dao.findById(id)?.toDomain()

    override suspend fun saveProject(project: Project) {
        dao.upsert(project.toEntity())
    }

    override suspend fun deleteProject(id: String) {
        database.withTransaction {
            database.qualityRunDao().deleteForProject(id)
            database.llmCallDao().deleteForProject(id)
            database.promptSnapshotDao().deleteForProject(id)
            database.generationJobDao().deleteForProject(id)
            database.chapterRevisionDao().deleteForProject(id)
            database.outlineVersionDao().deleteForProject(id)
            database.characterSnapshotDao().deleteForProject(id)
            database.projectDao().deleteById(id)
        }
    }
}
