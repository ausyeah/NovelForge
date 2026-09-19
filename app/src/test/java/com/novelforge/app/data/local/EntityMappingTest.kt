package com.novelforge.app.data.local

import com.novelforge.app.domain.model.GenerationJob
import com.novelforge.app.domain.model.GenerationJobStatus
import com.novelforge.app.domain.model.GenerationPurpose
import org.junit.Assert.assertEquals
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
}
