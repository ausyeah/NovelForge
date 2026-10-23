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
import com.novelforge.app.infrastructure.llm.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：任务行在 worker 飞行途中被删掉之后，worker 不能把它插回来，
 * 也不能把这一次的输出当成新结果发出去。
 *
 * ## 这里的 fake 忠实模拟了 Room 的两种冲突语义
 *
 * 这个 bug 之前「有测试」但测试是假的：`CrossBookIsolationTest` 里那个测试直接调用
 * 自己手写 fake 的 `updateJobIfExists`，断言「fake 自己写对了」—— 断言的是
 * `if (!jobs.containsKey(id)) return false` 这一行本身，而不是任何生产代码。
 * 所以哪怕把 `RoomGenerationRepository.updateJobIfExists` 的实现整个换成
 * `dao.upsert(...); return true`，CI 依然全绿，而生产已经完全没有防护。
 *
 * 下面这些测试反过来做：fake 严格复刻生产的两条 SQL 语义
 * （`upsert` = REPLACE = 行不存在就 INSERT；`updateIfExists` = UPDATE = 行不存在就落空），
 * 被测对象是**生产类** `GenerationCoordinator`，删除动作发生在 LLM 流的中途 ——
 * 也就是 `queueOutline` 清库之后、worker 收尾之前这个真实的窗口。
 * 只要有人把收尾改回 `updateJob`，这些测试立刻红。
 *
 * 仍然测不到的一层：`RoomGenerationRepository` 是否真的把 `updateJobIfExists`
 * 映射到 `UPDATE ... WHERE id = :id` 而不是 `upsert`。那需要真 Room，
 * JVM 跑不了（见 report：需要 androidTest + 设备）。
 */
class SupersededJobIsolationTest {

    private fun chapterJob(id: String = "job-1", projectId: String = "book-A") = GenerationJob(
        id = id,
        projectId = projectId,
        purpose = GenerationPurpose.CHAPTER,
        clientRequestId = "req-$id",
        promptSnapshotId = "snap-$id",
        createdAt = 1L,
        updatedAt = 1L
    )

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

    @Test
    fun workerFinishingADeletedJobDoesNotResurrectIt() = runBlocking {
        val repository = RecordingRepository()
        val job = chapterJob()
        repository.createJob(job)
        val coordinator = GenerationCoordinator(
            repository,
            InterruptingLlmClient { repository.deleteAllJobs("book-A") }
        )

        val events = coordinator.execute(job.id, testRequest()).toList()

        assertTrue(
            "「全新重生成大纲」清掉的行必须仍然是空的，收尾的 worker 不许把它插回来",
            repository.jobs.isEmpty()
        )
        assertEquals(
            "收尾必须走条件更新（UPDATE ... WHERE id=…）。用 upsert(REPLACE) 就会复活这一行",
            emptyList<GenerationJob>(),
            repository.replaceUpserts
        )
    }

    @Test
    fun workerFinishingADeletedJobNeverReportsItsContentAsCompleted() = runBlocking {
        val repository = RecordingRepository()
        val job = chapterJob()
        repository.createJob(job)
        val coordinator = GenerationCoordinator(
            repository,
            InterruptingLlmClient { repository.deleteAllJobs("book-A") }
        )

        val events = coordinator.execute(job.id, testRequest()).toList()

        // 上游只认 Completed 才落内容。发了 Completed 就等于把旧稿当成新结果写进新目录
        // （新批次完整复用 chapter-N 的 id 空间），所以这里必须不发。
        assertTrue(
            "任务行已删除时不能发出 Completed：$events",
            events.none { it is GenerationEvent.Completed }
        )
        assertEquals(
            "作废的生成必须显式告知上游放弃，而不是静默返回",
            GenerationEvent.Superseded,
            events.last()
        )
    }

    @Test
    fun workerRefusesToStartWhenTheJobRowIsAlreadyGone() = runBlocking {
        val repository = RecordingRepository()
        val job = chapterJob()
        // 没有 createJob：行从来就不存在（用户清库后 WorkManager 才开始跑这条链）
        val llm = InterruptingLlmClient { }
        val coordinator = GenerationCoordinator(repository, llm)

        // execute 在读不到任务时按既有约定直接抛错，这里只钉住「不会发出任何事件」
        val thrown = runCatching { coordinator.execute(job.id, testRequest()).toList() }
        assertTrue("读不到任务行时必须失败而不是凭空建行", thrown.isFailure)
        assertTrue("不得凭空多出一行", repository.jobs.isEmpty())
        assertTrue("一次模型调用都不该发生", !llm.firedForTest)
    }

    @Test
    fun aLiveJobStillCompletesNormally() = runBlocking {
        // 反向对照：条件更新不能把正常流程也一起弄坏
        val repository = RecordingRepository()
        val job = chapterJob()
        repository.createJob(job)
        val coordinator = GenerationCoordinator(repository, InterruptingLlmClient { })

        val events = coordinator.execute(job.id, testRequest()).toList()

        assertEquals(GenerationJobStatus.COMPLETED, repository.jobs[job.id]?.status)
        assertEquals("旧正文", (events.last() as GenerationEvent.Completed).content)
        assertFalse(
            "行还在时不能用条件更新把写入挡掉",
            repository.conditionalUpdates.isEmpty()
        )
    }

    private class InterruptingLlmClient(
        private val onFirstDelta: suspend () -> Unit = {}
    ) : LLMClient {
        var firedForTest: Boolean = false
            private set
        private var fired = false
        override suspend fun chat(request: ChatRequest) = error("not used")
        override fun streamChat(request: ChatRequest): Flow<StreamEvent> = flow {
            if (!fired) {
                fired = true
                firedForTest = true
                onFirstDelta()
            }
            emit(StreamEvent.Delta("旧"))
            emit(StreamEvent.Delta("正文"))
            emit(StreamEvent.Finished("stop"))
        }
    }

    /**
     * 忠实复刻生产的两条 SQL 语义：
     * - `updateJob` → `upsert` = `OnConflictStrategy.REPLACE`，行不存在就等于 INSERT
     * - `updateJobIfExists` → `UPDATE ... WHERE id = :id`，行不存在就落空并返回 false
     * - `updateJobIfNotCancelled` → 事务内 `findById` 为空同样落空（行被删也算落空）
     */
    private class RecordingRepository : GenerationRepository {
        val jobs = linkedMapOf<String, GenerationJob>()
        val replaceUpserts = mutableListOf<GenerationJob>()
        val conditionalUpdates = mutableListOf<GenerationJob>()
        private val states = mutableMapOf<String, MutableStateFlow<GenerationJob?>>()

        override fun observeJob(id: String): Flow<GenerationJob?> =
            states.getOrPut(id) { MutableStateFlow(jobs[id]) }

        override fun observeLatestJob(
            projectId: String,
            purpose: String,
            targetId: String?
        ): Flow<GenerationJob?> = flowOf(
            jobs.values.lastOrNull { it.projectId == projectId && it.purpose.name == purpose }
        )

        override suspend fun findById(id: String): GenerationJob? = jobs[id]

        override suspend fun findByClientRequestId(
            projectId: String,
            clientRequestId: String
        ): GenerationJob? = jobs.values.firstOrNull {
            it.projectId == projectId && it.clientRequestId == clientRequestId
        }

        override suspend fun findActiveJob(
            projectId: String,
            purpose: String,
            targetId: String?
        ): GenerationJob? = jobs.values.lastOrNull {
            it.projectId == projectId && it.purpose.name == purpose && it.targetId == targetId &&
                it.status in setOf(GenerationJobStatus.QUEUED, GenerationJobStatus.RUNNING)
        }

        override suspend fun findLatestJob(
            projectId: String,
            purpose: String,
            targetId: String?
        ): GenerationJob? = jobs.values.lastOrNull {
            it.projectId == projectId && it.purpose.name == purpose && it.targetId == targetId
        }

        override suspend fun countJobs(projectId: String, purpose: String, targetId: String): Int =
            jobs.values.count {
                it.projectId == projectId && it.purpose.name == purpose && it.targetId == targetId
            }

        override suspend fun findJobsWithStatuses(statuses: Collection<String>): List<GenerationJob> =
            jobs.values.filter { it.status.name in statuses }

        override suspend fun createJob(job: GenerationJob): GenerationJob {
            jobs[job.id] = job
            states.getOrPut(job.id) { MutableStateFlow(job) }.value = job
            return job
        }

        /** REPLACE：行不存在时照样插回来。生产就是这样，这是复活事故的根。 */
        override suspend fun updateJob(job: GenerationJob) {
            replaceUpserts += job
            jobs[job.id] = job
            states.getOrPut(job.id) { MutableStateFlow(job) }.value = job
        }

        override suspend fun updateJobIfNotCancelled(job: GenerationJob): Boolean {
            val current = jobs[job.id] ?: return false
            if (current.status == GenerationJobStatus.CANCELLED) return false
            conditionalUpdates += job
            jobs[job.id] = job
            states.getOrPut(job.id) { MutableStateFlow(job) }.value = job
            return true
        }

        override suspend fun updateJobIfExists(job: GenerationJob): Boolean {
            if (!jobs.containsKey(job.id)) return false
            conditionalUpdates += job
            jobs[job.id] = job
            states.getOrPut(job.id) { MutableStateFlow(job) }.value = job
            return true
        }

        override suspend fun deleteAllJobs(projectId: String) {
            jobs.keys
                .filter { jobs[it]?.projectId == projectId }
                .forEach { key ->
                    states[key]?.value = null
                    jobs.remove(key)
                }
        }

        override suspend fun deleteJobsForTargets(
            projectId: String,
            purpose: String,
            targetIds: Collection<String>
        ) {
            jobs.keys
                .filter { key ->
                    val job = jobs[key]
                    job?.projectId == projectId && job.purpose.name == purpose && job.targetId in targetIds
                }
                .forEach { key ->
                    states[key]?.value = null
                    jobs.remove(key)
                }
        }
    }
}
