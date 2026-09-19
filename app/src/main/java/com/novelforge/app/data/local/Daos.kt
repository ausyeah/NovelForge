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
    suspend fun findLatest(projectId: String): OutlineVersionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(version: OutlineVersionEntity)

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
}

@Dao
interface LlmCallDao {
    @Query("SELECT * FROM llm_calls WHERE projectId = :projectId ORDER BY createdAt ASC")
    fun observeForProject(projectId: String): Flow<List<LlmCallEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(call: LlmCallEntity)

    @Query("DELETE FROM llm_calls WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: String)
}

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
