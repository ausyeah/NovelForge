package com.novelforge.app.domain.usecase

import com.novelforge.app.domain.model.GenerationJob
import com.novelforge.app.domain.model.GenerationJobStatus
import com.novelforge.app.domain.model.GenerationPurpose
import com.novelforge.app.domain.model.MAX_CHAPTER_COUNT
import com.novelforge.app.domain.model.MIN_CHAPTER_COUNT
import com.novelforge.app.domain.model.OutlineItem
import com.novelforge.app.domain.model.Project
import com.novelforge.app.domain.model.tonePromptLabel
import com.novelforge.app.domain.model.thrillFrequencyPromptLabel
import com.novelforge.app.domain.model.writingStylePromptLabel
import com.novelforge.app.domain.repository.GenerationRepository
import com.novelforge.app.domain.repository.ProjectRepository
import com.novelforge.app.infrastructure.llm.ChatMessage
import com.novelforge.app.infrastructure.llm.ChatOptions
import com.novelforge.app.infrastructure.llm.ChatRole
import com.novelforge.app.infrastructure.llm.ChatRequest
import com.novelforge.app.infrastructure.llm.LLMConnectionConfig
import com.novelforge.app.infrastructure.llm.ResponseFormat
import com.novelforge.app.infrastructure.llm.ResponseFormatKind
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class QueuedGeneration(
    val job: GenerationJob,
    val request: ChatRequest
)

class GenerateOutlineUseCase(
    private val projectRepository: ProjectRepository,
    private val generationRepository: GenerationRepository,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    private val json = Json { encodeDefaults = true }

    suspend operator fun invoke(
        projectId: String,
        connection: LLMConnectionConfig,
        outputTokenBudget: Int,
        clientRequestId: String = UUID.randomUUID().toString()
    ): QueuedGeneration {
        val project = requireNotNull(projectRepository.getProject(projectId)) { "项目不存在" }
        checkNotNull(project.creativeConfig) { "请先完成创作设置" }
        val existing = generationRepository.findByClientRequestId(clientRequestId)
        if (existing != null) {
            return QueuedGeneration(
                existing,
                buildChapterRequest(
                    project = project,
                    connection = connection,
                    outputTokenBudget = outputTokenBudget,
                    requestId = clientRequestId,
                    chapterNumber = 1,
                    previousChapters = emptyList()
                )
            )
        }
        val active = generationRepository.findActiveJob(
            projectId = project.id,
            purpose = GenerationPurpose.OUTLINE.name,
            targetId = null
        )
        if (active != null) {
            return QueuedGeneration(
                active,
                buildChapterRequest(
                    project = project,
                    connection = connection,
                    outputTokenBudget = outputTokenBudget,
                    requestId = clientRequestId,
                    chapterNumber = 1,
                    previousChapters = emptyList()
                )
            )
        }
        val needsRepair = generationRepository.findLatestJob(
            projectId = project.id,
            purpose = GenerationPurpose.OUTLINE.name,
            targetId = null
        )?.takeIf { it.status == GenerationJobStatus.NEEDS_USER }
        if (needsRepair != null) {
            return QueuedGeneration(
                needsRepair,
                buildChapterRequest(
                    project = project,
                    connection = connection,
                    outputTokenBudget = outputTokenBudget,
                    requestId = clientRequestId,
                    chapterNumber = 1,
                    previousChapters = emptyList()
                )
            )
        }
        val timestamp = now()
        val job = GenerationJob(
            id = UUID.randomUUID().toString(),
            projectId = project.id,
            purpose = GenerationPurpose.OUTLINE,
            clientRequestId = clientRequestId,
            promptSnapshotId = UUID.randomUUID().toString(),
            createdAt = timestamp,
            updatedAt = timestamp
        )
        generationRepository.createJob(job)
        return QueuedGeneration(
            job,
            buildChapterRequest(
                project = project,
                connection = connection,
                outputTokenBudget = outputTokenBudget,
                requestId = clientRequestId,
                chapterNumber = 1,
                previousChapters = emptyList()
            )
        )
    }

    fun buildChapterRequest(
        project: Project,
        connection: LLMConnectionConfig,
        outputTokenBudget: Int,
        requestId: String,
        chapterNumber: Int,
        previousChapters: List<OutlineItem>
    ): ChatRequest {
        val creativeConfig = checkNotNull(project.creativeConfig) { "请先完成创作设置" }
        val chapterCount = creativeConfig.chapterCount.coerceIn(MIN_CHAPTER_COUNT, MAX_CHAPTER_COUNT)
        // 分批生成：每次请求一批连续推进段，避免要求模型一次输出几百章
        val batchStart = chapterNumber.coerceIn(1, chapterCount)
        val batchEnd = minOf(batchStart + OUTLINE_BATCH_SIZE - 1, chapterCount)
        val batchCount = batchEnd - batchStart + 1
        val rangeLabel = if (batchStart == batchEnd) "第 $batchStart 章" else "第 $batchStart 至 $batchEnd 章"
        val genreTags = creativeConfig.genreTags.joinToString("、").ifBlank { "未指定" }
        val previousContext = previousChapters
            .takeLast(PREVIOUS_CONTEXT_LIMIT)
            .joinToString("\n") { item ->
                "第 ${item.orderIndex + 1} 章《${item.title}》：${item.summary}"
            }
            .ifBlank { "暂无前文大纲" }
        val prologueNote = if (batchStart == 1) {
            "第 1 个推进段是全书引子（prologue），title 里体现“引子”或贴合内容的引子标题；从第 2 个推进段起才是正文第 1 章。\n" +
                "title 只写标题本身，严禁带“引子：”“第 X 章”“第一章”这类编号前缀（编号由应用统一生成）。\n"
        } else {
            ""
        }
        return ChatRequest(
            messages = listOf(
                ChatMessage(
                    ChatRole.SYSTEM,
                    "你是长篇小说故事线编辑。本次任务只整理全书故事线中一批连续章节的推进段，不写正文，不要求细节描写。必须只返回合法 JSON，不要 Markdown、代码围栏、解释或额外文字。"
                ),
                ChatMessage(
                    ChatRole.USER,
                    """
                    请为作品《${project.title}》整理全书故事线中$rangeLabel（共 $batchCount 个连续推进段）。
                    ${prologueNote}这是后续生成正文时使用的故事资料；每个推进段说明该章故事如何发展、冲突如何变化、人物或局势发生什么变化，并为下一章留下方向。
                    全书计划共 $chapterCount 章；本批只生成这 $batchCount 个推进段并按章节顺序排列，严禁生成批次之外的章节、完整故事线或正文。
                    【创作设定】
                    - 文笔风格：${creativeConfig.writingStylePromptLabel()}（强度 ${creativeConfig.writingStyleIntensity}/5）
                    - 笔风类型：${creativeConfig.tonePromptLabel()}（强度 ${creativeConfig.toneIntensity}/5）
                    - 爽感频率：${creativeConfig.thrillFrequencyPromptLabel()}
                    - 题材标签：$genreTags
                    - 每章正文目标长度：${creativeConfig.targetLength} 字（这里只写大纲，不写正文）
                    【问答资料 JSON】${json.encodeToString(project.questData.answers)}
                    【最近前文，仅用于衔接】
                    $previousContext
                    如果资料不完整，请自行补全合理内容，不能回复“无法生成”。
                    只允许返回如下 JSON 结构，chapters 数组必须恰好包含 $batchCount 项且顺序与章节号一致：
                    {"chapters":[{"title":"本章标题","summary":"说明这一章故事如何发展、冲突如何推进，以及为下一章留下什么方向"}]}
                    summary 不限定字数，以清楚、完整、简洁为准。不要提供 id、orderIndex 或 characterChanges；应用会统一补全和存储。
                    """.trimIndent()
                )
            ),
            config = connection,
            options = ChatOptions(
                outputTokenBudget = outputTokenBudget,
                responseFormat = if (connection.capabilities.supportsJsonObject) {
                    ResponseFormat(ResponseFormatKind.JSON_OBJECT)
                } else {
                    ResponseFormat()
                },
                requestId = requestId
            )
        )
    }

    private companion object {
        const val PREVIOUS_CONTEXT_LIMIT = 3
        const val OUTLINE_BATCH_SIZE = 10
    }
}
