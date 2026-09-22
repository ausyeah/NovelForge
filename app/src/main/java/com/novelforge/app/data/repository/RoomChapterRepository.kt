package com.novelforge.app.data.repository

import com.novelforge.app.data.local.ChapterRevisionDao
import com.novelforge.app.data.local.orFallback
import com.novelforge.app.data.local.toDomain
import com.novelforge.app.data.local.toEntity
import com.novelforge.app.domain.model.ChapterRevision
import com.novelforge.app.domain.repository.ChapterRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class RoomChapterRepository(private val dao: ChapterRevisionDao) : ChapterRepository {
    override fun observeRevisions(projectId: String): Flow<List<ChapterRevision>> =
        dao.observeForProject(projectId).map { entities -> entities.map { it.toDomain() } }
            .flowOn(Dispatchers.Default)
            .orFallback(emptyList())

    override suspend fun contentLengthForSnapshot(promptSnapshotId: String): Int? =
        dao.contentLengthBySnapshot(promptSnapshotId)

    override fun observeWrittenItemIds(projectId: String): Flow<List<String>> =
        dao.observeWrittenItemIds(projectId).orFallback(emptyList())

    override suspend fun latest(projectId: String, outlineItemId: String): ChapterRevision? =
        dao.findLatest(projectId, outlineItemId)?.toDomain()

    override suspend fun allForProject(projectId: String): List<ChapterRevision> =
        dao.findAllForProject(projectId).map { it.toDomain() }

    override suspend fun save(revision: ChapterRevision) {
        dao.upsert(revision.toEntity())
    }

    override suspend fun deleteAllRevisions(projectId: String) {
        dao.deleteForProject(projectId)
    }

    override suspend fun deleteRevisionsForItems(projectId: String, outlineItemIds: Collection<String>) {
        if (outlineItemIds.isNotEmpty()) dao.deleteForItems(projectId, outlineItemIds)
    }
}
