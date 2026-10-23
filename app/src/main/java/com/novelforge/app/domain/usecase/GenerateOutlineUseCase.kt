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
        val existing = generationRepository.findByClientRequestId(projectId, clientRequestId)
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
        val batchWindow = OutlineBatchPlanner.window(
            chapterNumber = chapterNumber,
            chapterCount = chapterCount,
            batchSize = OUTLINE_BATCH_SIZE
        )
        val batchStart = batchWindow.first
        val batchEnd = batchWindow.last
        val batchCount = batchEnd - batchStart + 1
        val rangeLabel = if (batchStart == batchEnd) com.novelforge.app.presentation.common.chapterLabel(batchStart - 1)
            else "${com.novelforge.app.presentation.common.chapterLabel(batchStart - 1)} 至 ${com.novelforge.app.presentation.common.chapterLabel(batchEnd - 1)}"
        val genreTags = creativeConfig.genreTags.joinToString("、").ifBlank { "未指定" }
        // 输出长度锚点：默认 5000 token 的输出预算下，一批 10 章 × 无上限 summary
        // 必然触顶被截断（截断的 JSON 整单作废，全书卡死）。按目标正文长度折算
        // 概要字数（每 20 字正文配 1 字概要），夹在 120–240 字之间。
        val summaryCharLimit = (creativeConfig.targetLength / 20).coerceIn(120, 240)
        val previousContext = previousChapters
            .takeLast(PREVIOUS_CONTEXT_LIMIT)
            .joinToString("\n") { item ->
                "${com.novelforge.app.presentation.common.chapterLabel(item.orderIndex)}《${item.title}》：${item.summary}"
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
                    "你是长篇小说故事线编辑。本次任务只整理全书故事线中一批连续章节的推进段，不写正文，不要求细节描写。必须只返回合法 JSON，不要 Markdown、代码围栏、解释或额外文字。" +
                        "严格控制输出长度：每个 summary 不超过 $summaryCharLimit 字；当本批内容接近输出上限时，必须优先保证 JSON 完整闭合——可以少返回几个推进段（漏掉的由后续批次补齐），严禁输出被长度限制截断的半截 JSON。"
                ),
                ChatMessage(
                    ChatRole.USER,
                    """
                    请为作品《${project.title}》整理全书故事线中$rangeLabel（共 $batchCount 个连续推进段）。
                    ${prologueNote}这是后续生成正文时使用的故事资料；每个推进段说明该章故事如何发展、冲突如何变化、人物或局势发生什么变化，并为下一章留下方向。
                    全书计划共 $chapterCount 章；本批只生成这 $batchCount 个推进段并按章节顺序排列，严禁生成批次之外的章节、完整故事线或正文。
                    【创作设定】
                    - 叙事风格：${creativeConfig.writingStylePromptLabel()}（强度 ${creativeConfig.writingStyleIntensity}/5）
                    - 基调：${creativeConfig.tonePromptLabel()}（强度 ${creativeConfig.toneIntensity}/5）
                    - 爽感频率：${creativeConfig.thrillFrequencyPromptLabel()}
                    - 题材标签：$genreTags
                    - 每章正文目标长度：${creativeConfig.targetLength} 字（这里只写大纲，不写正文）
                    【问答资料 JSON】${json.encodeToString(project.questData.answers)}
                    【最近前文，仅用于衔接】
                    $previousContext
                    如果资料不完整，请自行补全合理内容，不能回复“无法生成”。
                    只允许返回如下 JSON 结构，chapters 数组按章节顺序排列：
                    {"chapters":[{"title":"本章标题","summary":"说明这一章故事如何发展、冲突如何推进，以及为下一章留下什么方向"}]}
                    每个 summary 控制在 $summaryCharLimit 字以内：写清故事发展、冲突变化与衔接方向即可，不展开细节描写。若整批内容接近输出上限，宁可少返回几个推进段（顺序不能乱，漏掉的由后续批次补齐），也必须保证 JSON 完整闭合，严禁半截 JSON。不要提供 id、orderIndex 或 characterChanges；应用会统一补全和存储。
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

    companion object {
        const val PREVIOUS_CONTEXT_LIMIT = 3
        /** 批大小：与 [OutlineBatchPlanner.BATCH_SIZE] 同源，避免两处各写一个数字 */
        const val OUTLINE_BATCH_SIZE = OutlineBatchPlanner.BATCH_SIZE
    }
}
