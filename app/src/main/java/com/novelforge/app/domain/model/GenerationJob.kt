package com.novelforge.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class GenerationPurpose {
    OUTLINE,
    CHAPTER,
    QUALITY_CHECK,
    REPAIR,
    CHAT
}

@Serializable
enum class GenerationJobStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    CANCELLED,
    FAILED,
    COMPLETED,
    NEEDS_USER,
    RECOVERABLE_PARTIAL
}

@Serializable
data class GenerationJob(
    val id: String,
    val projectId: String,
    val targetId: String? = null,
    val purpose: GenerationPurpose,
    val status: GenerationJobStatus = GenerationJobStatus.QUEUED,
    val clientRequestId: String,
    val attempt: Int = 0,
    val partialContent: String = "",
    val promptSnapshotId: String,
    val lastCheckpointAt: Long? = null,
    val errorType: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)
