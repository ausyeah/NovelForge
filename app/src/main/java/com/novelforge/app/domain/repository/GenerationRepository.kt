package com.novelforge.app.domain.repository

import com.novelforge.app.domain.model.GenerationJob
import kotlinx.coroutines.flow.Flow

interface GenerationRepository {
    fun observeJob(id: String): Flow<GenerationJob?>
    fun observeLatestJob(projectId: String, purpose: String, targetId: String?): Flow<GenerationJob?>
    suspend fun findById(id: String): GenerationJob?
    suspend fun findByClientRequestId(clientRequestId: String): GenerationJob?
    suspend fun findActiveJob(projectId: String, purpose: String, targetId: String?): GenerationJob?
    suspend fun findLatestJob(projectId: String, purpose: String, targetId: String?): GenerationJob?
    suspend fun countJobs(projectId: String, purpose: String, targetId: String): Int
    suspend fun findJobsWithStatuses(statuses: Collection<String>): List<GenerationJob>
    suspend fun createJob(job: GenerationJob): GenerationJob
    suspend fun updateJob(job: GenerationJob)
    /** 用户已取消的任务不许被心跳/失败路径复活（整行 REPLACE 会吞掉 CANCELLED） */
    suspend fun updateJobIfNotCancelled(job: GenerationJob): Boolean
    suspend fun deleteAllJobs(projectId: String)
    suspend fun deleteJobsForTargets(projectId: String, purpose: String, targetIds: Collection<String>)
}
