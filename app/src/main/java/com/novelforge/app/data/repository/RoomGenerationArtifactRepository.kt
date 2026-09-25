package com.novelforge.app.data.repository

import androidx.room.withTransaction
import com.novelforge.app.data.local.AppDatabase
import com.novelforge.app.data.local.toEntity
import com.novelforge.app.domain.model.ChapterRevision
import com.novelforge.app.domain.model.GenerationJob
import com.novelforge.app.domain.model.LlmCall
import com.novelforge.app.domain.model.OutlineVersion
import com.novelforge.app.domain.model.Project
import com.novelforge.app.domain.repository.GenerationArtifactRepository

class RoomGenerationArtifactRepository(
    private val database: AppDatabase
) : GenerationArtifactRepository {
    override suspend fun saveOutlineAndProject(version: OutlineVersion, project: Project) {
        database.withTransaction {
            database.outlineVersionDao().upsert(renumbered(version).toEntity())
            database.projectDao().upsert(project.toEntity())
        }
    }

    override suspend fun saveOutlineAndPrune(
        version: OutlineVersion,
        project: Project,
        pruneOutlineItemIds: Collection<String>,
        pruneJobTargetIds: Collection<String>
    ) {
        database.withTransaction {
            if (pruneOutlineItemIds.isNotEmpty()) {
                database.chapterRevisionDao().deleteForItems(project.id, pruneOutlineItemIds)
            }
            if (pruneJobTargetIds.isNotEmpty()) {
                database.generationJobDao().deleteForTargets(
                    project.id,
                    com.novelforge.app.domain.model.GenerationPurpose.CHAPTER.name,
                    pruneJobTargetIds
                )
            }
            database.outlineVersionDao().upsert(renumbered(version).toEntity())
            database.projectDao().upsert(project.toEntity())
            database.promptSnapshotDao().deleteOrphans()
        }
    }

    /** 事务内重算版本号：并发保存撞 (projectId,version) 唯一索引时 REPLACE 会静默删掉对方整版 */
    private suspend fun renumbered(version: OutlineVersion): OutlineVersion {
        val latest = database.outlineVersionDao().findLatest(version.projectId)
        return version.copy(
            version = (latest?.version ?: 0) + 1,
            createdAt = if (version.createdAt == 0L) System.currentTimeMillis() else version.createdAt
        )
    }

    override suspend fun saveOutlineResult(
        version: OutlineVersion,
        project: Project,
        job: GenerationJob,
        call: LlmCall
    ) {
        database.withTransaction {
            database.outlineVersionDao().upsert(renumbered(version).toEntity())
            database.projectDao().upsert(project.toEntity())
            database.generationJobDao().upsert(job.toEntity())
            database.llmCallDao().insert(call.toEntity())
        }
    }

    override suspend fun saveChapterResult(
        revision: ChapterRevision,
        project: Project,
        job: GenerationJob,
        call: LlmCall
    ) {
        database.withTransaction {
            // 事务内重算 revision：撞 (projectId,outlineItemId,revision) 唯一索引会整行顶掉旧正文
            val latest = database.chapterRevisionDao()
                .findLatest(revision.projectId, revision.outlineItemId)
            val safe = revision.copy(revision = (latest?.revision ?: 0) + 1)
            database.chapterRevisionDao().upsert(safe.toEntity())
            database.projectDao().upsert(project.toEntity())
            database.generationJobDao().upsert(job.toEntity())
            database.llmCallDao().insert(call.toEntity())
        }
    }

    override suspend fun saveJobAndLlmCall(job: GenerationJob, call: LlmCall) {
        database.withTransaction {
            database.generationJobDao().upsert(job.toEntity())
            database.llmCallDao().insert(call.toEntity())
        }
    }

    override suspend fun wipeChapterArtifacts(projectId: String) {
        database.withTransaction {
            database.chapterRevisionDao().deleteForProject(projectId)
            database.generationJobDao().deleteForProject(projectId)
            database.promptSnapshotDao().deleteOrphans()
        }
    }
}
