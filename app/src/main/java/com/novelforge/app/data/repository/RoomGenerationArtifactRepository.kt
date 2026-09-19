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
            database.outlineVersionDao().upsert(version.toEntity())
            database.projectDao().upsert(project.toEntity())
        }
    }

    override suspend fun saveOutlineResult(
        version: OutlineVersion,
        project: Project,
        job: GenerationJob,
        call: LlmCall
    ) {
        database.withTransaction {
            database.outlineVersionDao().upsert(version.toEntity())
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
            database.chapterRevisionDao().upsert(revision.toEntity())
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
}
