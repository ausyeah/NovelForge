package com.novelforge.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
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

// 下面几张子表都真挂外键：删项目/删章节时由 SQLite 级联清干净。
// 以前删除全靠各仓库手写顺序，"删子表"一旦漏掉一处就留下永久孤儿
// （quality_runs 指向已删的 chapter_revisions 之后谁都读不到，
//  backfillUsageEstimates 找不到 job 只能瞎编 token 填账本）。
@Entity(
    tableName = "outline_versions",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
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
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["projectId", "outlineItemId", "revision"], unique = true),
        Index(value = ["projectId", "outlineVersionId"]),
        // 启动回填 token 估算时会按 promptSnapshotId 查正文长度，
        // 不建索引就是每次全表扫描整本小说（一本书几百次）
        Index(value = ["promptSnapshotId"])
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
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
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

// 故意不给 llm_calls 挂 projectId 外键：对话（灵感助手）写入时 projectId = ""，
// 挂上外键会让每一次聊天记账都因"找不到项目"而写库失败。
// 账本聚合已经用 LEFT JOIN + projectId != '' 兜住这类行，所以这里保持"无外键"是有意的。
// 也不挂 jobId 外键：对话用 "chat-<时间戳>" 这种并不存在的 jobId 当占位。
@Entity(
    tableName = "llm_calls",
    indices = [
        Index(value = ["jobId"]),
        Index(value = ["projectId", "purpose"]),
        // 账本页四条聚合查询都按 createdAt 过滤/排序，而 llm_calls 没有任何清理机制、
        // 只增不减；没有这个索引，每次打开账本都是全表扫描 + 排序
        Index(value = ["createdAt"])
    ]
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
    foreignKeys = [
        ForeignKey(
            entity = ChapterRevisionEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapterRevisionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["projectId", "chapterRevisionId"]),
        // 级联删除要按 chapterRevisionId 反查子行，(projectId, chapterRevisionId)
        // 的首列是 projectId 用不上，所以单列再补一个
        Index(value = ["chapterRevisionId"])
    ]
)
data class QualityRunEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val chapterRevisionId: String,
    val reportJson: String,
    val createdAt: Long
)
