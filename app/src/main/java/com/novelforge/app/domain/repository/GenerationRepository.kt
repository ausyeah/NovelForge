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
    suspend fun createJob(job: GenerationJob): GenerationJob
    suspend fun updateJob(job: GenerationJob)
}
