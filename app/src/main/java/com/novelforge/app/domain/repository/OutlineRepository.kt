package com.novelforge.app.domain.repository

import com.novelforge.app.domain.model.OutlineVersion
import kotlinx.coroutines.flow.Flow

interface OutlineRepository {
    fun observeVersions(projectId: String): Flow<List<OutlineVersion>>
    fun observeLatestVersion(projectId: String): Flow<OutlineVersion?>
    suspend fun latest(projectId: String): OutlineVersion?
    suspend fun allForProject(projectId: String): List<OutlineVersion>
    suspend fun save(version: OutlineVersion)
}
