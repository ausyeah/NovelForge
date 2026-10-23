package com.novelforge.app.domain.repository

import com.novelforge.app.domain.model.GenerationJob
import kotlinx.coroutines.flow.Flow

interface GenerationRepository {
    fun observeJob(id: String): Flow<GenerationJob?>
    fun observeLatestJob(projectId: String, purpose: String, targetId: String?): Flow<GenerationJob?>
    suspend fun findById(id: String): GenerationJob?
    suspend fun findByClientRequestId(projectId: String, clientRequestId: String): GenerationJob?
    suspend fun findActiveJob(projectId: String, purpose: String, targetId: String?): GenerationJob?
    suspend fun findLatestJob(projectId: String, purpose: String, targetId: String?): GenerationJob?
    suspend fun countJobs(projectId: String, purpose: String, targetId: String): Int
    suspend fun findJobsWithStatuses(statuses: Collection<String>): List<GenerationJob>
    suspend fun createJob(job: GenerationJob): GenerationJob
    suspend fun updateJob(job: GenerationJob)
    /** 用户已取消的任务不许被心跳/失败路径复活（整行 REPLACE 会吞掉 CANCELLED） */
    suspend fun updateJobIfNotCancelled(job: GenerationJob): Boolean
    /**
     * 行已被删除时必须落空。重生成大纲会先清掉这本书的旧任务，
     * 此时仍在收尾的 worker 不能靠 REPLACE 把行插回来。
     *
     * **worker 收尾一律用这个，不要用 [updateJob]**：[updateJob] 走 REPLACE，
     * 落在已删除的行上等于 INSERT，会复活任务；而 `clientRequestId` 上有唯一索引，
     * REPLACE 撞号时还会顺手删掉新任务那一行。建任务用 `createJob`，
     * 用户主动触发的重置（如「修复并重试」回写 QUEUED）用 [updateJob]。
     */
    suspend fun updateJobIfExists(job: GenerationJob): Boolean
    suspend fun deleteAllJobs(projectId: String)
    suspend fun deleteJobsForTargets(projectId: String, purpose: String, targetIds: Collection<String>)
}
