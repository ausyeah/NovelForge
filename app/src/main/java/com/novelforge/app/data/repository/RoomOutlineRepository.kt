package com.novelforge.app.data.repository

import com.novelforge.app.data.local.OutlineVersionDao
import com.novelforge.app.data.local.orFallback
import com.novelforge.app.data.local.toDomain
import com.novelforge.app.data.local.toEntity
import com.novelforge.app.domain.model.OutlineVersion
import com.novelforge.app.domain.repository.OutlineRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class RoomOutlineRepository(private val dao: OutlineVersionDao) : OutlineRepository {
    override fun observeVersions(projectId: String): Flow<List<OutlineVersion>> =
        dao.observeForProject(projectId).map { entities -> entities.map { it.toDomain() } }
            .flowOn(Dispatchers.Default)
            .orFallback(emptyList())

    override fun observeLatestVersion(projectId: String): Flow<OutlineVersion?> =
        dao.observeLatestVersion(projectId).map { it?.toDomain() }
            .flowOn(Dispatchers.Default)
            .orFallback(null)

    override suspend fun latest(projectId: String): OutlineVersion? =
        dao.findLatest(projectId)?.toDomain()

    override suspend fun allForProject(projectId: String): List<OutlineVersion> =
        dao.findAllForProject(projectId).map { it.toDomain() }

    override suspend fun save(version: OutlineVersion) {
        dao.upsert(version.toEntity())
    }
}
