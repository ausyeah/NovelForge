package com.novelforge.app.domain.repository

import com.novelforge.app.domain.model.ChapterRevision
import kotlinx.coroutines.flow.Flow

interface ChapterRepository {
    fun observeRevisions(projectId: String): Flow<List<ChapterRevision>>
    fun observeWrittenItemIds(projectId: String): Flow<List<String>>
    suspend fun latest(projectId: String, outlineItemId: String): ChapterRevision?
    suspend fun allForProject(projectId: String): List<ChapterRevision>
    suspend fun contentLengthForSnapshot(promptSnapshotId: String): Int?
    suspend fun save(revision: ChapterRevision)
    suspend fun deleteRevisionsForItems(projectId: String, outlineItemIds: Collection<String>)
    suspend fun deleteAllRevisions(projectId: String)
}
