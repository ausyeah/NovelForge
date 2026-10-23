package com.novelforge.app.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SerializationTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun generationJob_roundTripsStableFields() {
        val job = GenerationJob(
            id = "job-1",
            projectId = "project-1",
            targetId = "outline-item-1",
            purpose = GenerationPurpose.CHAPTER,
            status = GenerationJobStatus.RECOVERABLE_PARTIAL,
            clientRequestId = "request-1",
            partialContent = "已接收的正文",
            promptSnapshotId = "prompt-1",
            errorType = "STREAM_INTERRUPTED",
            createdAt = 1L,
            updatedAt = 2L
        )

        val restored = json.decodeFromString<GenerationJob>(json.encodeToString(job))

        assertEquals(job, restored)
    }

    @Test
    fun outlineItem_keepsStableIdWhenOrderChanges() {
        val item = OutlineItem(
            id = "outline-item-1",
            orderIndex = 2,
            title = "第二章",
            summary = "冲突升级"
        )

        val restored = json.decodeFromString<OutlineItem>(json.encodeToString(item))

        assertEquals("outline-item-1", restored.id)
        assertTrue(restored.orderIndex == 2)
    }

    @Test
    fun qualityIssue_exposesRepairLocation() {
        val issue = QualityIssue(
            type = "character_consistency",
            severity = IssueSeverity.HIGH,
            paragraphId = "p-018",
            quote = "原文片段",
            details = "人物动机与档案冲突"
        )

        val restored = json.decodeFromString<QualityIssue>(json.encodeToString(issue))

        assertEquals("p-018", restored.paragraphId)
        assertEquals(IssueSeverity.HIGH, restored.severity)
    }
}
