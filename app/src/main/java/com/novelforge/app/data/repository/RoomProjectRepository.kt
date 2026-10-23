package com.novelforge.app.data.repository

import androidx.room.withTransaction
import com.novelforge.app.data.local.AppDatabase
import com.novelforge.app.data.local.ProjectDao
import com.novelforge.app.data.local.encodeContinuityState
import com.novelforge.app.data.local.orFallback
import com.novelforge.app.data.local.toDomain
import com.novelforge.app.data.local.toEntity
import com.novelforge.app.domain.model.ContinuityState
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

    /**
     * 走 chapterRevisionDao 而不是 chapterDao：`Project` 表里没有正文信息。
     *
     * `orFallback` 是必须的：这张表对新装的应用是空的（还没有任何章节），
     * 而查询本身不会报错，只是返回空列表 —— 不加兜底的话书架会崩在一个
     * 「本该正常」的状态上。
     */
    override fun observeWrittenChapterCounts(): Flow<Map<String, Int>> =
        database.chapterRevisionDao().observeWrittenCountsByProject()
            // 合并逻辑放在 LibraryRouting 而不是这里 inline：ViewModel 也用同一份，
            // 两处各写一遍 `associate` 的话，聚合规则（多行取 max 而不是覆盖）
            // 迟早会只改一边。
            .map { rows -> com.novelforge.app.presentation.library.LibraryRouting.toCountMap(rows) }
            .flowOn(Dispatchers.Default)
            .orFallback(emptyMap())

    override suspend fun saveProject(project: Project) {
        dao.upsert(project.toEntity())
    }

    override suspend fun mutateContinuity(id: String, block: (ContinuityState) -> ContinuityState) {
        database.withTransaction {
            val current = dao.findById(id) ?: return@withTransaction
            val next = block(current.toDomain().continuityState)
            dao.updateContinuityJson(id, encodeContinuityState(next), System.currentTimeMillis())
        }
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
