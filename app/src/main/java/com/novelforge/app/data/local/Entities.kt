package com.novelforge.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    val questionnaireSchemaVersion: Int,
    val flowState: String,
    val questDataJson: String,
    val creativeConfigJson: String?,
    val continuityStateJson: String,
    val activeOutlineVersionId: String?,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "outline_versions",
    indices = [Index(value = ["projectId", "version"], unique = true)]
)
data class OutlineVersionEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val version: Int,
    val chaptersJson: String,
    val diffSummary: String?,
    val createdAt: Long
)

@Entity(
    tableName = "character_snapshots",
    indices = [Index(value = ["projectId", "version"], unique = true)]
)
data class CharacterSnapshotEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val version: Int,
    val contentJson: String,
    val createdAt: Long
)

@Entity(
    tableName = "chapter_revisions",
    indices = [
        Index(value = ["projectId", "outlineItemId", "revision"], unique = true),
        Index(value = ["projectId", "outlineVersionId"])
    ]
)
data class ChapterRevisionEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val outlineItemId: String,
    val outlineVersionId: String,
    val revision: Int,
    val title: String,
    val content: String,
    val summary: String?,
    val status: String,
    val promptSnapshotId: String?,
    val createdAt: Long
)

@Entity(
    tableName = "generation_jobs",
    indices = [
        Index(value = ["projectId", "purpose", "targetId"]),
        Index(value = ["clientRequestId"], unique = true)
    ]
)
data class GenerationJobEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val targetId: String?,
    val purpose: String,
    val status: String,
    val clientRequestId: String,
    val attempt: Int,
    val partialContent: String,
    val promptSnapshotId: String,
    val lastCheckpointAt: Long?,
    val errorType: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "prompt_snapshots")
data class PromptSnapshotEntity(
    @PrimaryKey val id: String,
    val systemPrompt: String,
    val messagesJson: String,
    val model: String,
    val temperature: Float?,
    val createdAt: Long
)

@Entity(
    tableName = "llm_calls",
    indices = [Index(value = ["jobId"]), Index(value = ["projectId", "purpose"])]
)
data class LlmCallEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val jobId: String,
    val purpose: String,
    val provider: String,
    val model: String,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val totalTokens: Long?,
    val estimated: Boolean,
    val cachedInputTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val durationMs: Long?,
    val success: Boolean,
    val createdAt: Long
)

@Entity(
    tableName = "quality_runs",
    indices = [Index(value = ["projectId", "chapterRevisionId"])]
)
data class QualityRunEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val chapterRevisionId: String,
    val reportJson: String,
    val createdAt: Long
)
