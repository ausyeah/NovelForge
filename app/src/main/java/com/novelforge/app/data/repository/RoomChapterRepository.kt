package com.novelforge.app.data.repository

import com.novelforge.app.data.local.ChapterRevisionDao
import com.novelforge.app.data.local.toDomain
import com.novelforge.app.data.local.toEntity
import com.novelforge.app.domain.model.ChapterRevision
import com.novelforge.app.domain.repository.ChapterRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomChapterRepository(private val dao: ChapterRevisionDao) : ChapterRepository {
    override fun observeRevisions(projectId: String): Flow<List<ChapterRevision>> =
        dao.observeForProject(projectId).map { entities -> entities.map { it.toDomain() } }

    override suspend fun latest(projectId: String, outlineItemId: String): ChapterRevision? =
        dao.findLatest(projectId, outlineItemId)?.toDomain()

    override suspend fun save(revision: ChapterRevision) {
        dao.upsert(revision.toEntity())
    }

    override suspend fun deleteRevisionsForItems(projectId: String, outlineItemIds: Collection<String>) {
        if (outlineItemIds.isNotEmpty()) dao.deleteForItems(projectId, outlineItemIds)
    }
}
