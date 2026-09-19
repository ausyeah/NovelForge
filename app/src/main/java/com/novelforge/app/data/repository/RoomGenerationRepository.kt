package com.novelforge.app.data.repository

import com.novelforge.app.data.local.GenerationJobDao
import com.novelforge.app.data.local.toDomain
import com.novelforge.app.data.local.toEntity
import com.novelforge.app.domain.model.GenerationJob
import com.novelforge.app.domain.repository.GenerationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomGenerationRepository(private val dao: GenerationJobDao) : GenerationRepository {
    override fun observeJob(id: String): Flow<GenerationJob?> = dao.observeById(id).map { it?.toDomain() }

    override fun observeLatestJob(
        projectId: String,
        purpose: String,
        @Suppress("UNUSED_PARAMETER") targetId: String?
    ): Flow<GenerationJob?> = dao.observeLatest(projectId, purpose).map { it?.toDomain() }

    override suspend fun findById(id: String): GenerationJob? = dao.findById(id)?.toDomain()

    override suspend fun findByClientRequestId(clientRequestId: String): GenerationJob? =
        dao.findByClientRequestId(clientRequestId)?.toDomain()

    override suspend fun findActiveJob(projectId: String, purpose: String, targetId: String?): GenerationJob? =
        dao.findActive(projectId, purpose, targetId)?.toDomain()

    override suspend fun findLatestJob(projectId: String, purpose: String, targetId: String?): GenerationJob? =
        dao.findLatest(projectId, purpose, targetId)?.toDomain()

    override suspend fun countJobs(projectId: String, purpose: String, targetId: String): Int =
        dao.countJobs(projectId, purpose, targetId)

    override suspend fun findJobsWithStatuses(statuses: Collection<String>): List<GenerationJob> =
        dao.findWithStatuses(statuses).map { it.toDomain() }

    override suspend fun createJob(job: GenerationJob): GenerationJob {
        dao.insert(job.toEntity())
        return job
    }

    override suspend fun updateJob(job: GenerationJob) {
        dao.upsert(job.toEntity())
    }

    override suspend fun deleteJobsForTargets(
        projectId: String,
        purpose: String,
        targetIds: Collection<String>
    ) {
        if (targetIds.isNotEmpty()) dao.deleteForTargets(projectId, purpose, targetIds)
    }
}
