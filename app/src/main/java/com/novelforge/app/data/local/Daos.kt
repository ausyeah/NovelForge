package com.novelforge.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface OutlineVersionDao {
    @Query("SELECT * FROM outline_versions WHERE projectId = :projectId ORDER BY version DESC")
    fun observeForProject(projectId: String): Flow<List<OutlineVersionEntity>>

    @Query("SELECT * FROM outline_versions WHERE projectId = :projectId ORDER BY version DESC LIMIT 1")
    fun observeLatestVersion(projectId: String): Flow<OutlineVersionEntity?>

    @Query("SELECT * FROM outline_versions WHERE projectId = :projectId ORDER BY version DESC")
    suspend fun findAllForProject(projectId: String): List<OutlineVersionEntity>

    @Query("SELECT * FROM outline_versions WHERE projectId = :projectId ORDER BY version DESC LIMIT 1")
    suspend fun findLatest(projectId: String): OutlineVersionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(version: OutlineVersionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(versions: List<OutlineVersionEntity>)

    @Query("DELETE FROM outline_versions WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: String)
}

@Dao
interface CharacterSnapshotDao {
    @Query("SELECT * FROM character_snapshots WHERE projectId = :projectId ORDER BY version DESC")
    fun observeForProject(projectId: String): Flow<List<CharacterSnapshotEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: CharacterSnapshotEntity)

    @Query("DELETE FROM character_snapshots WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: String)
}

@Dao
interface ChapterRevisionDao {
    @Query("SELECT * FROM chapter_revisions WHERE projectId = :projectId ORDER BY createdAt ASC")
    fun observeForProject(projectId: String): Flow<List<ChapterRevisionEntity>>

    @Query("SELECT * FROM chapter_revisions WHERE projectId = :projectId ORDER BY createdAt ASC")
    suspend fun findAllForProject(projectId: String): List<ChapterRevisionEntity>

    /** 只要"写过哪些章"的 id 集合，别把正文拉进内存 */
    @Query("SELECT DISTINCT outlineItemId FROM chapter_revisions WHERE projectId = :projectId")
    fun observeWrittenItemIds(projectId: String): Flow<List<String>>

    @Query("SELECT LENGTH(content) FROM chapter_revisions WHERE promptSnapshotId = :snapshotId LIMIT 1")
    suspend fun contentLengthBySnapshot(snapshotId: String): Int?

    @Query("SELECT * FROM chapter_revisions WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ChapterRevisionEntity?

    @Query(
        "SELECT * FROM chapter_revisions " +
            "WHERE projectId = :projectId AND outlineItemId = :outlineItemId " +
            "ORDER BY revision DESC LIMIT 1"
    )
    suspend fun findLatest(projectId: String, outlineItemId: String): ChapterRevisionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(revision: ChapterRevisionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(revisions: List<ChapterRevisionEntity>)

    @Query("DELETE FROM chapter_revisions WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: String)

    @Query(
        "DELETE FROM chapter_revisions " +
            "WHERE projectId = :projectId AND outlineItemId IN (:outlineItemIds)"
    )
    suspend fun deleteForItems(projectId: String, outlineItemIds: Collection<String>)
}

@Dao
interface GenerationJobDao {
    @Query("SELECT * FROM generation_jobs WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<GenerationJobEntity?>

    @Query("SELECT * FROM generation_jobs WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): GenerationJobEntity?

    @Query("SELECT * FROM generation_jobs WHERE clientRequestId = :clientRequestId LIMIT 1")
    suspend fun findByClientRequestId(clientRequestId: String): GenerationJobEntity?

    @Query(
        "SELECT * FROM generation_jobs " +
            "WHERE projectId = :projectId AND purpose = :purpose " +
            "AND ((targetId = :targetId) OR (targetId IS NULL AND :targetId IS NULL)) " +
            "AND status IN ('QUEUED', 'RUNNING', 'PAUSED', 'RECOVERABLE_PARTIAL') " +
            "ORDER BY createdAt DESC LIMIT 1"
    )
    suspend fun findActive(projectId: String, purpose: String, targetId: String?): GenerationJobEntity?

    @Query(
        "SELECT * FROM generation_jobs " +
            "WHERE projectId = :projectId AND purpose = :purpose " +
            "AND ((targetId = :targetId) OR (targetId IS NULL AND :targetId IS NULL)) " +
            "ORDER BY updatedAt DESC, createdAt DESC LIMIT 1"
    )
    suspend fun findLatest(projectId: String, purpose: String, targetId: String?): GenerationJobEntity?

    /** 观察某项目某用途的最新任务（不限 targetId，Room 表级失效监听会随最新任务变化自动推送） */
    @Query(
        "SELECT * FROM generation_jobs " +
            "WHERE projectId = :projectId AND purpose = :purpose " +
            "ORDER BY updatedAt DESC, createdAt DESC LIMIT 1"
    )
    fun observeLatest(projectId: String, purpose: String): Flow<GenerationJobEntity?>

    /** 只统计失败类任务：手动取消/成功历史不应吃掉自动重试预算 */
    @Query(
        "SELECT COUNT(*) FROM generation_jobs " +
            "WHERE projectId = :projectId AND purpose = :purpose AND targetId = :targetId " +
            "AND status IN ('FAILED', 'NEEDS_USER', 'RECOVERABLE_PARTIAL')"
    )
    suspend fun countJobs(projectId: String, purpose: String, targetId: String): Int

    /** 启动清扫僵尸任务用：进程被杀后残留的 QUEUED/RUNNING */
    @Query("SELECT * FROM generation_jobs WHERE status IN (:statuses)")
    suspend fun findWithStatuses(statuses: Collection<String>): List<GenerationJobEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(job: GenerationJobEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: GenerationJobEntity)

    @Query("DELETE FROM generation_jobs WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: String)

    @Query(
        "DELETE FROM generation_jobs " +
            "WHERE projectId = :projectId AND purpose = :purpose AND targetId IN (:targetIds)"
    )
    suspend fun deleteForTargets(projectId: String, purpose: String, targetIds: Collection<String>)
}

@Dao
interface PromptSnapshotDao {
    @Query("SELECT * FROM prompt_snapshots WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): PromptSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(snapshot: PromptSnapshotEntity)

    @Query(
        "DELETE FROM prompt_snapshots WHERE id IN " +
            "(SELECT promptSnapshotId FROM generation_jobs WHERE projectId = :projectId)"
    )
    suspend fun deleteForProject(projectId: String)

    /** 回收没有任何任务引用的孤儿快照（含全量提示词，泄漏即 MB 级） */
    @Query(
        "DELETE FROM prompt_snapshots WHERE id NOT IN " +
            "(SELECT promptSnapshotId FROM generation_jobs) " +
            "AND id NOT IN (SELECT promptSnapshotId FROM chapter_revisions WHERE promptSnapshotId IS NOT NULL)"
    )
    suspend fun deleteOrphans()
}

@Dao
interface LlmCallDao {
    @Query("SELECT * FROM llm_calls WHERE projectId = :projectId ORDER BY createdAt ASC")
    fun observeForProject(projectId: String): Flow<List<LlmCallEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(call: LlmCallEntity)

    @Query("DELETE FROM llm_calls WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: String)

    @Query("SELECT * FROM llm_calls WHERE inputTokens IS NULL AND outputTokens IS NULL")
    suspend fun findNullUsage(): List<LlmCallEntity>

    @Query(
        "UPDATE llm_calls SET inputTokens = :input, outputTokens = :output, " +
            "totalTokens = :input + :output, estimated = 1 " +
            "WHERE id = :id AND inputTokens IS NULL AND outputTokens IS NULL"
    )
    suspend fun estimateTokensIfMissing(id: String, input: Long, output: Long)

    // ---------- 账本聚合（SQL 侧 GROUP BY，不把全表拉进内存；0 用量的记录不入账；since=0 表示全部） ----------

    @Query(
        "SELECT provider, model, COUNT(*) AS calls, SUM(success) AS successCalls, " +
            "SUM(COALESCE(inputTokens,0)) AS inputTokens, SUM(COALESCE(outputTokens,0)) AS outputTokens, " +
            "SUM(COALESCE(cachedInputTokens,0)) AS cachedInputTokens, SUM(COALESCE(reasoningTokens,0)) AS reasoningTokens, " +
            "SUM(estimated) AS estimatedCalls " +
            "FROM llm_calls WHERE createdAt >= :since GROUP BY provider, model " +
            "ORDER BY (inputTokens + outputTokens) DESC"
    )
    fun observeModelSummariesSince(since: Long): Flow<List<LlmCallModelSummaryRow>>

    @Query(
        "SELECT COALESCE(NULLIF(p.title, ''), '（灵感助手对话）') AS projectTitle, COUNT(*) AS calls, " +
            "SUM(COALESCE(c.inputTokens,0)) AS inputTokens, SUM(COALESCE(c.outputTokens,0)) AS outputTokens " +
            "FROM llm_calls c LEFT JOIN projects p ON p.id = c.projectId " +
            "WHERE c.createdAt >= :since AND c.projectId != '' GROUP BY c.projectId " +
            "HAVING (inputTokens + outputTokens) > 0 ORDER BY (inputTokens + outputTokens) DESC"
    )
    fun observeProjectSummaries(since: Long): Flow<List<LlmCallProjectSummaryRow>>

    @Query(
        "SELECT purpose, COUNT(*) AS calls, SUM(success) AS successCalls, " +
            "SUM(COALESCE(inputTokens,0)) AS inputTokens, SUM(COALESCE(outputTokens,0)) AS outputTokens, " +
            "SUM(COALESCE(cachedInputTokens,0)) AS cachedInputTokens, SUM(COALESCE(reasoningTokens,0)) AS reasoningTokens, " +
            "SUM(estimated) AS estimatedCalls " +
            "FROM llm_calls WHERE createdAt >= :since GROUP BY purpose " +
            "HAVING (inputTokens + outputTokens) > 0"
    )
    fun observePurposeSummariesSince(since: Long): Flow<List<LlmCallPurposeSummaryRow>>

    @Query(
        "SELECT c.*, p.title AS projectTitle FROM llm_calls c " +
            "LEFT JOIN projects p ON p.id = c.projectId " +
            "WHERE c.createdAt >= :since ORDER BY c.createdAt DESC LIMIT 300"
    )
    fun observeUsageLog(since: Long): Flow<List<LlmCallRecentRow>>
}

data class LlmCallProjectSummaryRow(
    val projectTitle: String,
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long
)

data class LlmCallModelSummaryRow(
    val provider: String,
    val model: String,
    val calls: Int,
    val successCalls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val estimatedCalls: Int = 0
)

data class LlmCallPurposeSummaryRow(
    val purpose: String,
    val calls: Int,
    val successCalls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val estimatedCalls: Int = 0
)

data class LlmCallRecentRow(
    val id: String,
    val projectId: String,
    val jobId: String,
    val purpose: String,
    val provider: String,
    val model: String,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val totalTokens: Long?,
    val estimated: Boolean,
    val durationMs: Long?,
    val success: Boolean,
    val createdAt: Long,
    val projectTitle: String?,
    val cachedInputTokens: Long? = null,
    val reasoningTokens: Long? = null
)

@Dao
interface QualityRunDao {
    @Query("SELECT * FROM quality_runs WHERE chapterRevisionId = :chapterRevisionId ORDER BY createdAt DESC")
    fun observeForChapter(chapterRevisionId: String): Flow<List<QualityRunEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(run: QualityRunEntity)

    @Query(
        "DELETE FROM quality_runs WHERE chapterRevisionId IN " +
            "(SELECT id FROM chapter_revisions WHERE projectId = :projectId)"
    )
    suspend fun deleteForProject(projectId: String)
}
