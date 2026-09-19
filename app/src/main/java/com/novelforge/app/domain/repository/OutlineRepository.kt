package com.novelforge.app.domain.repository

import com.novelforge.app.domain.model.OutlineVersion
import kotlinx.coroutines.flow.Flow

interface OutlineRepository {
    fun observeVersions(projectId: String): Flow<List<OutlineVersion>>
    suspend fun latest(projectId: String): OutlineVersion?
    suspend fun save(version: OutlineVersion)
}
