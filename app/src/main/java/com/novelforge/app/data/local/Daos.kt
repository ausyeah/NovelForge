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

    /** 只改记忆这一列：整行 REPLACE 会把并发写进来的其他字段一起顶掉。 */
    @Query("UPDATE projects SET continuityStateJson = :json, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateContinuityJson(id: String, json: String, updatedAt: Long)

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

    /**
     * 每本书"有正文的章数"，按 projectId 分组。
     *
     * 书架点封面分流用：写过了就去读，没写就送去写作。
     *
     * 两个条件都不能省：
     * - `content != ''` —— 存在空正文的行（写一半被杀、正文被清空），
     *   算进去的话点进去又是一片空目录；
     * - `COUNT(DISTINCT outlineItemId)` 而不是 `COUNT(*)` —— 一章可以有多次
     *   修订（`outlineItemId` + `revision` 上有唯一索引），按行数算会把
     *   改过三稿的章数成三章。
     */
    @Query(
        """
        SELECT projectId AS projectId, COUNT(DISTINCT outlineItemId) AS writtenCount
        FROM chapter_revisions
        WHERE content != ''
        GROUP BY projectId
        """
    )
    fun observeWrittenCountsByProject(): Flow<List<WrittenCountRow>>

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

    // 必须带 projectId：这个查询被当作「这个请求已经建过任务」的判据，
    // 命中就直接复用那条任务。漏掉书过滤时，一旦 id 撞上就会拿到别的书的 job，
    // 然后拿它的 projectId 去建请求、去落库。
    @Query("SELECT * FROM generation_jobs WHERE projectId = :projectId AND clientRequestId = :clientRequestId LIMIT 1")
    suspend fun findByClientRequestId(projectId: String, clientRequestId: String): GenerationJobEntity?

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

    /**
     * 只更新已存在的行。`upsert` 是 REPLACE，落在被删掉的行上等于 INSERT，
     * 会把「全新重生成大纲」已经清掉的任务复活成 COMPLETED，并连带把旧正文写回新目录。
     */
    @Query(
        "UPDATE generation_jobs SET targetId = :targetId, purpose = :purpose, status = :status, " +
            "clientRequestId = :clientRequestId, attempt = :attempt, partialContent = :partialContent, " +
            "promptSnapshotId = :promptSnapshotId, lastCheckpointAt = :lastCheckpointAt, " +
            "errorType = :errorType, errorMessage = :errorMessage, createdAt = :createdAt, " +
            "updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateIfExists(
        id: String,
        targetId: String?,
        purpose: String,
        status: String,
        clientRequestId: String,
        attempt: Int,
        partialContent: String,
        promptSnapshotId: String,
        lastCheckpointAt: Long?,
        errorType: String?,
        errorMessage: String?,
        createdAt: Long,
        updatedAt: Long
    ): Int

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
            // 把两个 SUM 原样重写一遍，而不是靠 `inputTokens + outputTokens`
            // 命中结果列别名：llm_calls 本身就有同名的真实列，别名与列同名时
            // 解析顺序并不是想当然，写全聚合式才不依赖 SQLite 的取舍。
            // 一旦解析成"取某一行自己的 token"，排序就成了随机的 ——
            // 那正是"排行没有排行"的来源。
            "ORDER BY (SUM(COALESCE(inputTokens,0)) + SUM(COALESCE(outputTokens,0))) DESC"
    )
    fun observeModelSummariesSince(since: Long): Flow<List<LlmCallModelSummaryRow>>

    @Query(
        "SELECT COALESCE(NULLIF(p.title, ''), '（灵感助手对话）') AS projectTitle, COUNT(*) AS calls, " +
            "SUM(COALESCE(c.inputTokens,0)) AS inputTokens, SUM(COALESCE(c.outputTokens,0)) AS outputTokens " +
            "FROM llm_calls c LEFT JOIN projects p ON p.id = c.projectId " +
            "WHERE c.createdAt >= :since AND c.projectId != '' GROUP BY c.projectId " +
            "HAVING (SUM(COALESCE(c.inputTokens,0)) + SUM(COALESCE(c.outputTokens,0))) > 0 " +
            // 同上：写全聚合式，不靠别名。同名列（c.inputTokens）真实存在，
            // 靠别名解析的排序可能压根没排上。
            "ORDER BY (SUM(COALESCE(c.inputTokens,0)) + SUM(COALESCE(c.outputTokens,0))) DESC"
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

/**
 * 「这本书有几章写了正文」的一行。
 *
 * 别和 `LlmCall*Row` 混在一起 —— 那些是账本聚合，这个是书架分流用的，
 * 两者生命周期和刷新时机都不一样。
 */
data class WrittenCountRow(
    val projectId: String,
    val writtenCount: Int
)

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
