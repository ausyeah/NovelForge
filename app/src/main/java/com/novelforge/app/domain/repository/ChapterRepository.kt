package com.novelforge.app.domain.repository

import com.novelforge.app.domain.model.ChapterRevision
import kotlinx.coroutines.flow.Flow

interface ChapterRepository {
    fun observeRevisions(projectId: String): Flow<List<ChapterRevision>>
    suspend fun latest(projectId: String, outlineItemId: String): ChapterRevision?
    suspend fun save(revision: ChapterRevision)
}
