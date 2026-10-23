package com.novelforge.app.data.repository

import androidx.room.withTransaction
import com.novelforge.app.data.local.AppDatabase
import com.novelforge.app.data.local.GenerationJobDao
import com.novelforge.app.data.local.orFallback
import com.novelforge.app.data.local.toDomain
import com.novelforge.app.data.local.toEntity
import com.novelforge.app.domain.model.GenerationJob
import com.novelforge.app.domain.model.GenerationJobStatus
import com.novelforge.app.domain.repository.GenerationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class RoomGenerationRepository(
    private val dao: GenerationJobDao,
    private val database: AppDatabase
) : GenerationRepository {
    override fun observeJob(id: String): Flow<GenerationJob?> =
        dao.observeById(id).map { it?.toDomain() }.flowOn(Dispatchers.Default).orFallback(null)

    override fun observeLatestJob(
        projectId: String,
        purpose: String,
        @Suppress("UNUSED_PARAMETER") targetId: String?
    ): Flow<GenerationJob?> =
        dao.observeLatest(projectId, purpose).map { it?.toDomain() }.flowOn(Dispatchers.Default).orFallback(null)

    override suspend fun findById(id: String): GenerationJob? = dao.findById(id)?.toDomain()

    override suspend fun findByClientRequestId(projectId: String, clientRequestId: String): GenerationJob? =
        dao.findByClientRequestId(projectId, clientRequestId)?.toDomain()

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

    override suspend fun updateJobIfNotCancelled(job: GenerationJob): Boolean =
        database.withTransaction {
            val current = dao.findById(job.id) ?: return@withTransaction false
            if (current.status == GenerationJobStatus.CANCELLED.name) return@withTransaction false
            dao.upsert(job.toEntity())
            true
        }

    /**
     * 落库时如果这行已经不存在就什么都不做。
     * 「全新重生成大纲」会先删掉这本书的旧任务；此时还在飞行中的 worker
     * 如果用 upsert 收尾，REPLACE 会把已删除的行重新插回来，
     * 旧正文就会挂回新目录 —— 这就是「小说之间串」最直接的一种形态。
     */
    override suspend fun updateJobIfExists(job: GenerationJob): Boolean {
        val row = job.toEntity()
        return dao.updateIfExists(
            id = row.id,
            targetId = row.targetId,
            purpose = row.purpose,
            status = row.status,
            clientRequestId = row.clientRequestId,
            attempt = row.attempt,
            partialContent = row.partialContent,
            promptSnapshotId = row.promptSnapshotId,
            lastCheckpointAt = row.lastCheckpointAt,
            errorType = row.errorType,
            errorMessage = row.errorMessage,
            createdAt = row.createdAt,
            updatedAt = row.updatedAt
        ) > 0
    }

    override suspend fun deleteAllJobs(projectId: String) {
        dao.deleteForProject(projectId)
    }

    override suspend fun deleteJobsForTargets(
        projectId: String,
        purpose: String,
        targetIds: Collection<String>
    ) {
        if (targetIds.isNotEmpty()) dao.deleteForTargets(projectId, purpose, targetIds)
    }
}
