package com.novelforge.app.domain.usecase

import com.novelforge.app.domain.model.Project
import com.novelforge.app.domain.model.ProjectStatus
import com.novelforge.app.domain.model.CreativeConfig
import com.novelforge.app.domain.model.FlowState
import com.novelforge.app.domain.model.GenerationJob
import com.novelforge.app.domain.model.GenerationPurpose
import com.novelforge.app.domain.model.QuestData
import com.novelforge.app.domain.model.ThrillFrequency
import com.novelforge.app.domain.model.Tone
import com.novelforge.app.domain.model.WritingStyle
import com.novelforge.app.domain.repository.ProjectRepository
import com.novelforge.app.domain.repository.GenerationRepository
import com.novelforge.app.infrastructure.llm.ChatRole
import com.novelforge.app.infrastructure.llm.LLMConnectionConfig
import com.novelforge.app.infrastructure.llm.ProviderCapabilities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GenerationUseCaseTest {
    @Test
    fun createProject_trimsTitleAndPersistsDraft() = runBlocking {
        val repository = RecordingProjectRepository()
        val project = CreateProjectUseCase(repository, now = { 123L })("  我的小说  ")

        assertEquals("我的小说", project.title)
        assertEquals(ProjectStatus.DRAFT, project.status)
        assertEquals(project, repository.saved)
    }

    @Test
    fun generateOutline_requiresCreativeSetupBeforeCreatingJob() = runBlocking {
        val projectRepository = RecordingProjectRepository()
        val generationRepository = RecordingGenerationRepository()
        val project = CreateProjectUseCase(projectRepository, now = { 123L })("未配置的小说")
        val useCase = GenerateOutlineUseCase(
            projectRepository = projectRepository,
            generationRepository = generationRepository,
            now = { 123L }
        )

        try {
            useCase(
                projectId = project.id,
                connection = testConnection(),
                outputTokenBudget = 512,
                clientRequestId = "request-unconfigured"
            )
            fail("未完成创作设置时不应创建大纲任务")
        } catch (error: IllegalStateException) {
            assertEquals("请先完成创作设置", error.message)
        }
        assertEquals(null, generationRepository.created)
    }

    @Test
    fun generateOutline_usesSavedCreativeSetupInPrompt() = runBlocking {
        val projectRepository = RecordingProjectRepository()
        val generationRepository = RecordingGenerationRepository()
        val project = Project(
            id = "project-configured",
            title = "雾港回声",
            questData = QuestData(
                answers = mapOf(
                    "premise" to "雾港每晚会抹去一段记忆",
                    "protagonist" to "记者林澈",
                    "conflict" to "她必须找回被抹去的弟弟"
                )
            ),
            creativeConfig = CreativeConfig(
                chapterCount = 6,
                targetLength = 3_000,
                genreTags = listOf("悬疑", "都市")
            ),
            flowState = FlowState.OUTLINE_GENERATE,
            status = ProjectStatus.OUTLINING,
            createdAt = 1L,
            updatedAt = 1L
        )
        projectRepository.saved = project
        val queued = GenerateOutlineUseCase(
            projectRepository = projectRepository,
            generationRepository = generationRepository,
            now = { 123L }
        )(
            projectId = project.id,
            connection = testConnection(),
            outputTokenBudget = 512,
            clientRequestId = "request-configured"
        )

        val prompt = queued.request.messages.first { it.role == ChatRole.USER }.content
        assertTrue(prompt.contains("整理全书故事线中引子 至 第 5 章（共 6 个连续推进段）"))
        assertTrue(prompt.contains("后续生成正文时使用的故事资料"))
        assertTrue(!prompt.contains("80-180 字"))
        assertTrue(prompt.contains("不要提供 id、orderIndex 或 characterChanges"))
        assertTrue(prompt.contains("严禁生成批次之外的章节"))
        assertTrue(prompt.contains("雾港每晚会抹去一段记忆"))
        assertTrue(prompt.contains("悬疑、都市"))
        assertTrue(prompt.contains("3000 字"))
    }

    @Test
    fun generateOutline_includesCustomCreativeLabels() = runBlocking {
        val projectRepository = RecordingProjectRepository()
        val generationRepository = RecordingGenerationRepository()
        val project = Project(
            id = "project-custom",
            title = "长夜",
            questData = QuestData(
                answers = mapOf(
                    "premise" to "城市会吞掉人的影子",
                    "protagonist" to "调查员",
                    "conflict" to "找回自己的影子"
                )
            ),
            creativeConfig = CreativeConfig(
                writingStyle = WritingStyle.CUSTOM,
                customWritingStyle = "冷峻克制",
                tone = Tone.CUSTOM,
                customTone = "压迫感逐步增强",
                thrillFrequency = ThrillFrequency.CUSTOM,
                customThrillFrequency = "每 5 章一次大高潮",
                chapterCount = 150,
                genreTags = listOf("悬疑")
            ),
            flowState = FlowState.OUTLINE_GENERATE,
            status = ProjectStatus.OUTLINING,
            createdAt = 1L,
            updatedAt = 1L
        )
        projectRepository.saved = project

        val queued = GenerateOutlineUseCase(
            projectRepository = projectRepository,
            generationRepository = generationRepository,
            now = { 123L }
        )(
            projectId = project.id,
            connection = testConnection(),
            outputTokenBudget = 512,
            clientRequestId = "request-custom"
        )

        val prompt = queued.request.messages.last().content
        assertTrue(prompt.contains("整理全书故事线中引子 至 第 9 章（共 10 个连续推进段）"))
        assertTrue(!prompt.contains("至少 150 个章节"))
        assertTrue(prompt.contains("冷峻克制"))
        assertTrue(prompt.contains("每 5 章一次大高潮"))
    }

    @Test
    fun buildChapterRequest_targetsRequestedBatch() {
        val projectRepository = RecordingProjectRepository()
        val generationRepository = RecordingGenerationRepository()
        val project = Project(
            id = "project-chapter-request",
            title = "逐章请求",
            questData = QuestData(
                answers = mapOf(
                    "premise" to "每一章都会改变一条城市规则",
                    "protagonist" to "规则记录员",
                    "conflict" to "阻止城市崩坏"
                )
            ),
            creativeConfig = CreativeConfig(chapterCount = 80),
            flowState = FlowState.OUTLINE_GENERATE,
            status = ProjectStatus.OUTLINING,
            createdAt = 1L,
            updatedAt = 1L
        )
        projectRepository.saved = project
        val useCase = GenerateOutlineUseCase(projectRepository, generationRepository)

        val prompt = useCase.buildChapterRequest(
            project = project,
            connection = testConnection(),
            outputTokenBudget = 512,
            requestId = "chapter-request",
            chapterNumber = 41,
            previousChapters = emptyList()
        ).messages.last().content

        assertTrue(prompt.contains("整理全书故事线中第 40 章 至 第 49 章（共 10 个连续推进段）"))
        assertTrue(prompt.contains("严禁生成批次之外的章节"))
        assertTrue(!prompt.contains("第 51 章"))
        assertTrue(!prompt.contains("至少生成"))
    }

    private fun testConnection() = LLMConnectionConfig(
        baseUrl = "https://example.com/v1",
        apiKey = System.getenv("NF_TEST_FAKE_TOKEN") ?: error("缺少测试环境变量 NF_TEST_FAKE_TOKEN"),
        model = "test-model",
        capabilities = ProviderCapabilities()
    )

    private class RecordingProjectRepository : ProjectRepository {
        var saved: Project? = null
        override fun observeProjects(): Flow<List<Project>> = emptyFlow()
        override suspend fun getProject(id: String): Project? = saved?.takeIf { it.id == id }
        override suspend fun saveProject(project: Project) {
            saved = project
        }
        override suspend fun deleteProject(id: String) {
            if (saved?.id == id) saved = null
        }

        override suspend fun mutateContinuity(
            id: String,
            block: (com.novelforge.app.domain.model.ContinuityState) -> com.novelforge.app.domain.model.ContinuityState
        ) {
            val current = saved?.takeIf { it.id == id } ?: return
            saved = current.copy(continuityState = block(current.continuityState))
        }
    }

    private class RecordingGenerationRepository : GenerationRepository {
        var created: GenerationJob? = null

        override fun observeJob(id: String): Flow<GenerationJob?> = emptyFlow()

        override fun observeLatestJob(
            projectId: String,
            purpose: String,
            targetId: String?
        ): Flow<GenerationJob?> = emptyFlow()

        override suspend fun countJobs(projectId: String, purpose: String, targetId: String): Int = 0
        override suspend fun findJobsWithStatuses(statuses: Collection<String>): List<GenerationJob> = emptyList()
        override suspend fun updateJobIfNotCancelled(job: GenerationJob): Boolean {
            if (created?.id == job.id && created?.status == com.novelforge.app.domain.model.GenerationJobStatus.CANCELLED) return false
            created = job
            return true
        }

        /** 行已不存在时必须落空：重生成大纲清掉旧任务后，不许被飞行中的 worker 插回来。 */
        override suspend fun updateJobIfExists(job: GenerationJob): Boolean {
            if (created?.id != job.id) return false
            created = job
            return true
        }
        override suspend fun deleteAllJobs(projectId: String) {
            if (created?.projectId == projectId) created = null
        }
        override suspend fun deleteJobsForTargets(
            projectId: String,
            purpose: String,
            targetIds: Collection<String>
        ) = Unit

        override suspend fun findById(id: String): GenerationJob? = created?.takeIf { it.id == id }

        override suspend fun findByClientRequestId(projectId: String, clientRequestId: String): GenerationJob? =
            created?.takeIf { it.clientRequestId == clientRequestId }

        override suspend fun findActiveJob(
            projectId: String,
            purpose: String,
            targetId: String?
        ): GenerationJob? = created?.takeIf {
            it.projectId == projectId && it.purpose == GenerationPurpose.valueOf(purpose)
        }

        override suspend fun findLatestJob(
            projectId: String,
            purpose: String,
            targetId: String?
        ): GenerationJob? = created?.takeIf {
            it.projectId == projectId && it.purpose == GenerationPurpose.valueOf(purpose)
        }

        override suspend fun createJob(job: GenerationJob): GenerationJob {
            created = job
            return job
        }

        override suspend fun updateJob(job: GenerationJob) {
            created = job
        }
    }
}



