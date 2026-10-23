package com.novelforge.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class ChapterStatus {
    PENDING,
    RUNNING,
    PARTIAL,
    REVIEWING,
    FINALIZED,
    FAILED
}

@Serializable
data class ChapterRevision(
    val id: String,
    val projectId: String,
    val outlineItemId: String,
    val outlineVersionId: String,
    val revision: Int,
    val title: String,
    val content: String = "",
    val summary: String? = null,
    val status: ChapterStatus = ChapterStatus.PENDING,
    val promptSnapshotId: String? = null,
    val createdAt: Long
)
