package com.novelforge.app.domain.repository

import com.novelforge.app.domain.model.ChapterRevision
import com.novelforge.app.domain.model.GenerationJob
import com.novelforge.app.domain.model.LlmCall
import com.novelforge.app.domain.model.OutlineVersion
import com.novelforge.app.domain.model.Project

interface GenerationArtifactRepository {
    suspend fun saveOutlineAndProject(version: OutlineVersion, project: Project)

    suspend fun saveOutlineResult(
        version: OutlineVersion,
        project: Project,
        job: GenerationJob,
        call: LlmCall
    )

    suspend fun saveChapterResult(
        revision: ChapterRevision,
        project: Project,
        job: GenerationJob,
        call: LlmCall
    )

    suspend fun saveJobAndLlmCall(job: GenerationJob, call: LlmCall)
}
