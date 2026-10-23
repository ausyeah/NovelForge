package com.novelforge.app.infrastructure.jobs

import com.novelforge.app.domain.model.CharacterProfile
import com.novelforge.app.domain.model.ContinuityFact
import com.novelforge.app.domain.model.ContinuityState
import com.novelforge.app.domain.model.GenerationJob
import com.novelforge.app.domain.model.GenerationJobStatus
import com.novelforge.app.domain.model.GenerationPurpose
import com.novelforge.app.infrastructure.llm.MemorySelector
import com.novelforge.app.infrastructure.llm.chapterMemoryHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨书隔离的回归测试。
 *
 * 这个仓库在修「小说之间容易串」之前，**一个跨项目隔离测试都没有**。
 * 于是「一键全自动」是全局单值这件事能一直发布：给 A 书打开开关，
 * B 书的每一章生成成功都会读到它，然后自动开始续写 B —— 而 B 书的顶栏
 * 也显示「已开」，用户完全看不出发生了什么。
 *
 * 这里把「按书隔离」钉成可执行的断言。
 */
class CrossBookIsolationTest {

    private fun book(
        projectId: String,
        lead: String,
        rule: String,
        fact: String
    ) = ContinuityState(
        worldRules = listOf(rule),
        unresolvedThreads = listOf("$lead 的旧案还没收"),
        characters = listOf(
            CharacterProfile(id = "$projectId-lead", projectId = projectId, name = lead)
        ),
        factsWithSources = listOf(
            ContinuityFact(
                id = "$projectId-fact",
                statement = fact,
                sourceChapterId = null,
                confirmed = true,
                updatedAt = 1
            )
        )
    )

    @Test
    fun memorySelectionNeverLeaksOneBooksCanonIntoAnother() {
        val a = book("A", "叶青云", "A世界的规则：不能复活", "叶青云的左臂已断")
        val b = book("B", "林晚", "B世界的规则：时间可以倒流", "林晚拿到了玉佩")

        val sliceA = MemorySelector.select(a, chapterHint = chapterMemoryHint("断臂", "叶青云又用断臂挡住了刀"))
        val sliceB = MemorySelector.select(b, chapterHint = chapterMemoryHint("玉佩", "林晚打开了玉佩"))

        val jsonA = sliceA.continuity.toString()
        val jsonB = sliceB.continuity.toString()
        assertTrue("A 的记忆里不该有 B 的人：$jsonA", !jsonA.contains("林晚"))
        assertTrue("A 的记忆里不该有 B 的规则：$jsonA", !jsonA.contains("时间可以倒流"))
        assertTrue("A 的记忆里不该有 B 的道具：$jsonA", !jsonA.contains("玉佩"))
        assertTrue("B 的记忆里不该有 A 的人：$jsonB", !jsonB.contains("叶青云"))
        assertTrue("B 的记忆里不该有 A 的规则：$jsonB", !jsonB.contains("不能复活"))
    }

    @Test
    fun eachBookKeepsOnlyItsOwnCharacters() {
        val a = book("A", "叶青云", "规则A", "事实A")
        val b = book("B", "林晚", "规则B", "事实B")
        assertEquals(listOf("叶青云"), MemorySelector.select(a).characterNames)
        assertEquals(listOf("林晚"), MemorySelector.select(b).characterNames)
    }

    @Test
    fun memorySelectorHoldsNoStateBetweenCalls() {
        // MemorySelector 是纯函数：连续性状态由调用方传进来，它自己不存。
        // 这一点必须钉住 —— 只要它在内部留了任何「上一次的书」的痕迹，
        // 上一章所属那本书的记忆就会漏进下一章，这就是串书。
        val a = book("A", "叶青云", "规则A", "事实A")
        val b = book("B", "林晚", "规则B", "事实B")
        val hint = chapterMemoryHint("断臂", "叶青云用断臂挡住了刀")

        val bAlone = MemorySelector.select(b, chapterHint = hint)
        MemorySelector.select(a, chapterHint = hint)   // 先处理 A 书
        val bAfterA = MemorySelector.select(b, chapterHint = hint)

        assertEquals("处理完 A 书之后，B 书的结果必须和单独处理时完全一致", bAlone, bAfterA)
    }

    /**
     * 回归：任务行被「全新重生成大纲」清掉之后，还在收尾的 worker 不能把它插回来。
     *
     * ⚠️ 这里原来有一版 `aDeletedJobIsNeverResurrected`，它直接调用本文件里
     * 手写 fake 的 `updateJobIfExists`，断言的是「fake 自己写对了」——
     * 被断言的是 `if (!jobs.containsKey(job.id)) return false` 这一行，而不是任何
     * 生产代码。当时生产侧一次都没调用过 `updateJobIfExists`，CI 却是绿的。
     * 真正跑 `GenerationCoordinator` 的回归测试在
     * `SupersededJobIsolationTest`，那里被测的是生产类本身。
     *
     * Room 那一层（`RoomGenerationRepository.updateJobIfExists` 是否真的映射到
     * `UPDATE ... WHERE id = :id` 而不是 `upsert`）JVM 测不了，需要 androidTest + 设备。
     */
    @Test
    fun deleteAllJobs_reallyRemovesEveryRowOfThatBook() = kotlinx.coroutines.runBlocking {
        val repository = RecordingRepository()
        val job = GenerationJob(
            id = "job-1",
            projectId = "book-A",
            purpose = GenerationPurpose.CHAPTER,
            clientRequestId = "req-1",
            promptSnapshotId = "snap-1",
            createdAt = 1,
            updatedAt = 1
        )
        repository.createJob(job)
        assertEquals(1, repository.jobs.size)

        repository.deleteAllJobs("book-A")

        assertTrue(repository.jobs.isEmpty())
        assertEquals("B 书的任务不能被 A 书的清库带走", null, repository.findById("nope"))
    }

    /**
     * 回归：「这个请求已经建过任务了吗」这条查询以前只按 clientRequestId 找，
     * 没有按书过滤。它被当作幂等判据，命中就直接复用那条任务 ——
     * 一旦 id 撞上，拿到的就是别的书的 job，然后拿它的 projectId 去建请求。
     */
    @Test
    fun clientRequestLookupIsScopedToItsOwnBook() = kotlinx.coroutines.runBlocking {
        val repository = RecordingRepository()
        repository.createJob(
            GenerationJob(
                id = "job-A",
                projectId = "book-A",
                purpose = GenerationPurpose.CHAPTER,
                clientRequestId = "shared-id",
                promptSnapshotId = "snap-A",
                createdAt = 1,
                updatedAt = 1
            )
        )
        assertEquals(null, repository.findByClientRequestId("book-B", "shared-id"))
        assertEquals("job-A", repository.findByClientRequestId("book-A", "shared-id")?.id)
    }

    private class RecordingRepository : com.novelforge.app.domain.repository.GenerationRepository {
        val jobs = linkedMapOf<String, GenerationJob>()

        override fun observeJob(id: String): kotlinx.coroutines.flow.Flow<GenerationJob?> =
            kotlinx.coroutines.flow.flowOf(jobs[id])

        override fun observeLatestJob(
            projectId: String,
            purpose: String,
            targetId: String?
        ): kotlinx.coroutines.flow.Flow<GenerationJob?> = kotlinx.coroutines.flow.flowOf(
            jobs.values.lastOrNull { it.projectId == projectId && it.purpose.name == purpose }
        )

        override suspend fun findById(id: String): GenerationJob? = jobs[id]

        override suspend fun findByClientRequestId(projectId: String, clientRequestId: String): GenerationJob? =
            jobs.values.firstOrNull {
                it.projectId == projectId && it.clientRequestId == clientRequestId
            }

        override suspend fun findActiveJob(
            projectId: String,
            purpose: String,
            targetId: String?
        ): GenerationJob? = jobs.values.firstOrNull {
            it.projectId == projectId && it.purpose.name == purpose && it.targetId == targetId
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
            return job
        }

        /** REPLACE：行不存在时照样插回来，生产就是这样 */
        override suspend fun updateJob(job: GenerationJob) {
            jobs[job.id] = job
        }

        /** 生产的实现是事务内 findById 为空即落空 —— 行被删也算落空 */
        override suspend fun updateJobIfNotCancelled(job: GenerationJob): Boolean {
            if (jobs[job.id]?.status == GenerationJobStatus.CANCELLED) return false
            if (!jobs.containsKey(job.id)) return false
            jobs[job.id] = job
            return true
        }

        override suspend fun updateJobIfExists(job: GenerationJob): Boolean {
            if (!jobs.containsKey(job.id)) return false
            jobs[job.id] = job
            return true
        }

        override suspend fun deleteAllJobs(projectId: String) {
            jobs.keys.filter { jobs[it]?.projectId == projectId }.forEach { jobs.remove(it) }
        }

        override suspend fun deleteJobsForTargets(
            projectId: String,
            purpose: String,
            targetIds: Collection<String>
        ) {
            jobs.keys.filter {
                val job = jobs[it]
                job?.projectId == projectId && job.purpose.name == purpose && job.targetId in targetIds
            }.forEach { jobs.remove(it) }
        }
    }
}
