package com.novelforge.app.infrastructure.jobs

import com.novelforge.app.domain.model.GenerationJob
import com.novelforge.app.domain.model.GenerationJobStatus
import com.novelforge.app.domain.model.GenerationPurpose
import com.novelforge.app.domain.repository.GenerationRepository
import com.novelforge.app.infrastructure.llm.ChatMessage
import com.novelforge.app.infrastructure.llm.ChatOptions
import com.novelforge.app.infrastructure.llm.ChatRequest
import com.novelforge.app.infrastructure.llm.ChatRole
import com.novelforge.app.infrastructure.llm.LLMClient
import com.novelforge.app.infrastructure.llm.LLMConnectionConfig
import com.novelforge.app.infrastructure.llm.ProviderCapabilities
import com.novelforge.app.infrastructure.llm.ResponseFormat
import com.novelforge.app.infrastructure.llm.ResponseFormatKind
import com.novelforge.app.infrastructure.llm.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationRecoveryTest {
    @Test
    fun createOrReuseJob_isIdempotentForSameRequest() = runBlocking {
        val repository = FakeGenerationRepository()
        val coordinator = GenerationCoordinator(repository, FakeLlmClient())

        val first = coordinator.createOrReuseJob(
            projectId = "project-1",
            purpose = GenerationPurpose.CHAPTER.name,
            targetId = "outline-1",
            promptSnapshotId = "prompt-1",
            clientRequestId = "request-1"
        )
        val second = coordinator.createOrReuseJob(
            projectId = "project-1",
            purpose = GenerationPurpose.CHAPTER.name,
            targetId = "outline-1",
            promptSnapshotId = "prompt-1",
            clientRequestId = "request-1"
        )

        assertSame(first, second)
        assertEquals(1, repository.jobs.size)
    }

    @Test
    fun execute_persistsCompletedContent() = runBlocking {
        val repository = FakeGenerationRepository()
        val job = GenerationJob(
            id = "job-1",
            projectId = "project-1",
            purpose = GenerationPurpose.CHAPTER,
            clientRequestId = "request-1",
            promptSnapshotId = "prompt-1",
            createdAt = 1L,
            updatedAt = 1L
        )
        repository.jobs[job.id] = job
        val coordinator = GenerationCoordinator(repository, FakeLlmClient())

        val events = coordinator.execute("job-1", testRequest()).toList()

        assertEquals(GenerationJobStatus.COMPLETED, repository.jobs[job.id]?.status)
        assertEquals("生成内容", repository.jobs[job.id]?.partialContent)
        assertEquals("生成内容", (events.last() as GenerationEvent.Completed).content)
    }

    @Test
    fun execute_checkpointsFirstDeltaWhileStillRunning() = runBlocking {
        val repository = FakeGenerationRepository()
        val job = GenerationJob(
            id = "job-1",
            projectId = "project-1",
            purpose = GenerationPurpose.OUTLINE,
            clientRequestId = "request-1",
            promptSnapshotId = "prompt-1",
            createdAt = 1L,
            updatedAt = 1L
        )
        repository.jobs[job.id] = job
        val coordinator = GenerationCoordinator(repository, FakeLlmClient())

        coordinator.execute("job-1", testRequest()).toList()

        val firstContentUpdate = repository.updates.first { it.partialContent == "生成" }
        assertEquals(GenerationJobStatus.RUNNING, firstContentUpdate.status)
    }

    @Test
    fun execute_structuredCheckpointPreservesCompletedPrefix() = runBlocking {
        val repository = FakeGenerationRepository()
        val job = GenerationJob(
            id = "job-structured",
            projectId = "project-1",
            purpose = GenerationPurpose.OUTLINE,
            clientRequestId = "request-structured",
            promptSnapshotId = "prompt-structured",
            createdAt = 1L,
            updatedAt = 1L
        )
        repository.jobs[job.id] = job
        val coordinator = GenerationCoordinator(repository, FakeLlmClient())

        coordinator.execute(
            job.id,
            testRequest().copy(
                options = testRequest().options.copy(
                    responseFormat = ResponseFormat(ResponseFormatKind.JSON_OBJECT),
                    checkpointPrefix = "{\"chapters\":[]}" 
                )
            )
        ).toList()

        val firstContentUpdate = repository.updates.first {
            it.status == GenerationJobStatus.RUNNING && it.partialContent.isNotEmpty()
        }
        assertTrue(firstContentUpdate.partialContent.startsWith("{\"chapters\":[]}"))
        assertTrue(firstContentUpdate.partialContent.endsWith("生成"))
    }

    private fun testRequest() = ChatRequest(
        messages = listOf(ChatMessage(ChatRole.USER, "测试")),
        config = LLMConnectionConfig(
            baseUrl = "https://example.com/v1",
            apiKey = System.getenv("NF_TEST_FAKE_TOKEN") ?: error("缺少测试环境变量 NF_TEST_FAKE_TOKEN"),
            model = "test-model",
            capabilities = ProviderCapabilities()
        ),
        options = ChatOptions(outputTokenBudget = 100, requestId = "request-1")
    )

    private class FakeLlmClient : LLMClient {
        override suspend fun chat(request: ChatRequest) = error("not used")
        override fun streamChat(request: ChatRequest): Flow<StreamEvent> = flowOf(
            StreamEvent.Delta("生成"),
            StreamEvent.Delta("内容"),
            StreamEvent.Finished("stop")
        )
    }

    private class FakeGenerationRepository : GenerationRepository {
        val jobs = linkedMapOf<String, GenerationJob>()
        val updates = mutableListOf<GenerationJob>()
        private val states = mutableMapOf<String, MutableStateFlow<GenerationJob?>>()

        override fun observeJob(id: String): Flow<GenerationJob?> =
            states.getOrPut(id) { MutableStateFlow(jobs[id]) }

        override fun observeLatestJob(
            projectId: String,
            purpose: String,
            targetId: String?
        ): Flow<GenerationJob?> = MutableStateFlow(
            jobs.values.lastOrNull { it.projectId == projectId && it.purpose.name == purpose }
        )

        override suspend fun countJobs(projectId: String, purpose: String, targetId: String): Int =
            jobs.values.count {
                it.projectId == projectId && it.purpose == GenerationPurpose.valueOf(purpose) &&
                    it.targetId == targetId
            }

        override suspend fun findById(id: String): GenerationJob? = jobs[id]

        override suspend fun findByClientRequestId(clientRequestId: String): GenerationJob? =
            jobs.values.firstOrNull { it.clientRequestId == clientRequestId }

        override suspend fun findActiveJob(projectId: String, purpose: String, targetId: String?): GenerationJob? =
            jobs.values.firstOrNull {
                it.projectId == projectId && it.purpose.name == purpose && it.targetId == targetId &&
                    it.status in setOf(GenerationJobStatus.QUEUED, GenerationJobStatus.RUNNING)
            }

        override suspend fun findLatestJob(projectId: String, purpose: String, targetId: String?): GenerationJob? =
            jobs.values.lastOrNull {
                it.projectId == projectId && it.purpose.name == purpose && it.targetId == targetId
            }

        override suspend fun createJob(job: GenerationJob): GenerationJob {
            jobs[job.id] = job
            states.getOrPut(job.id) { MutableStateFlow(job) }.value = job
            return job
        }

        override suspend fun updateJob(job: GenerationJob) {
            jobs[job.id] = job
            updates += job
            states.getOrPut(job.id) { MutableStateFlow(job) }.value = job
        }
    }
}
