package com.novelforge.app.data.local

import com.novelforge.app.domain.model.FlowState
import com.novelforge.app.domain.model.GenerationJob
import com.novelforge.app.domain.model.GenerationJobStatus
import com.novelforge.app.domain.model.GenerationPurpose
import com.novelforge.app.domain.model.ProjectStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityMappingTest {
    @Test
    fun generationJobMapping_keepsStableTargetAndCheckpoint() {
        val domain = GenerationJob(
            id = "job-1",
            projectId = "project-1",
            targetId = "outline-item-1",
            purpose = GenerationPurpose.CHAPTER,
            status = GenerationJobStatus.RECOVERABLE_PARTIAL,
            clientRequestId = "request-1",
            partialContent = "部分正文",
            promptSnapshotId = "prompt-1",
            lastCheckpointAt = 10L,
            createdAt = 1L,
            updatedAt = 10L
        )

        val restored = domain.toEntity().toDomain()

        assertEquals(domain, restored)
    }

    @Test
    fun corruptStoredRows_degradeInsteadOfThrowing() {
        val project = ProjectEntity(
            id = "p",
            title = "坏数据",
            questionnaireSchemaVersion = 1,
            flowState = "NOT_A_STATE",
            questDataJson = "{",
            creativeConfigJson = "[]",
            continuityStateJson = "",
            activeOutlineVersionId = null,
            status = "NOPE",
            createdAt = 1L,
            updatedAt = 2L
        ).toDomain()
        assertEquals(FlowState.INIT, project.flowState)
        assertEquals(ProjectStatus.DRAFT, project.status)
        assertNull(project.creativeConfig)

        val outline = OutlineVersionEntity(
            id = "o",
            projectId = "p",
            version = 1,
            chaptersJson = "not-json",
            diffSummary = null,
            createdAt = 1L
        ).toDomain()
        assertTrue(outline.chapters.isEmpty())

        val job = GenerationJobEntity(
            id = "j",
            projectId = "p",
            targetId = null,
            purpose = "??",
            status = "??",
            clientRequestId = "r",
            attempt = 0,
            partialContent = "",
            promptSnapshotId = "s",
            lastCheckpointAt = null,
            errorType = null,
            errorMessage = null,
            createdAt = 1L,
            updatedAt = 1L
        ).toDomain()
        assertEquals(GenerationPurpose.OUTLINE, job.purpose)
        assertEquals(GenerationJobStatus.FAILED, job.status)
    }
}
