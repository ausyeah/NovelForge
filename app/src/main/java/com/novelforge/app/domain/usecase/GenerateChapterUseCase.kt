package com.novelforge.app.domain.usecase

import com.novelforge.app.domain.model.GenerationJob
import com.novelforge.app.domain.model.GenerationPurpose
import com.novelforge.app.domain.model.OutlineItem
import com.novelforge.app.domain.repository.GenerationRepository
import com.novelforge.app.domain.repository.ProjectRepository
import com.novelforge.app.infrastructure.llm.ChatOptions
import com.novelforge.app.infrastructure.llm.ChatMessage
import com.novelforge.app.infrastructure.llm.ChatRole
import com.novelforge.app.infrastructure.llm.ChatRequest
import com.novelforge.app.infrastructure.llm.ChapterContext
import com.novelforge.app.infrastructure.llm.LLMConnectionConfig
import com.novelforge.app.infrastructure.llm.PromptBuilder
import com.novelforge.app.infrastructure.llm.ResponseFormat
import com.novelforge.app.infrastructure.llm.ResponseFormatKind
import java.util.UUID

class GenerateChapterUseCase(
    private val projectRepository: ProjectRepository,
    private val generationRepository: GenerationRepository,
    private val promptBuilder: PromptBuilder,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    suspend operator fun invoke(
        projectId: String,
        chapter: OutlineItem,
        context: ChapterContext,
        connection: LLMConnectionConfig,
        outputTokenBudget: Int,
        clientRequestId: String = UUID.randomUUID().toString()
    ): QueuedGeneration {
        val project = requireNotNull(projectRepository.getProject(projectId)) { "项目不存在" }
        val existing = generationRepository.findByClientRequestId(clientRequestId)
        val job = if (existing != null) {
            existing
        } else {
            val active = generationRepository.findActiveJob(
                projectId = project.id,
                purpose = GenerationPurpose.CHAPTER.name,
                targetId = chapter.id
            )
            if (active != null) {
                return QueuedGeneration(
                    active,
                    buildRequest(project, chapter, context, connection, outputTokenBudget, clientRequestId)
                )
            }
            val newJob = GenerationJob(
                id = UUID.randomUUID().toString(),
                projectId = project.id,
                targetId = chapter.id,
                purpose = GenerationPurpose.CHAPTER,
                clientRequestId = clientRequestId,
                promptSnapshotId = UUID.randomUUID().toString(),
                createdAt = now(),
                updatedAt = now()
            )
            generationRepository.createJob(newJob)
            newJob
        }
        return QueuedGeneration(
            job,
            buildRequest(project, chapter, context, connection, outputTokenBudget, clientRequestId)
        )
    }

    private fun buildRequest(
        project: com.novelforge.app.domain.model.Project,
        chapter: OutlineItem,
        context: ChapterContext,
        connection: LLMConnectionConfig,
        outputTokenBudget: Int,
        requestId: String
    ): ChatRequest {
        val budget = com.novelforge.app.infrastructure.llm.ContextBudget(
            inputBudget = project.creativeConfig?.inputBudget ?: 8_000,
            outputBudget = outputTokenBudget,
            safetyMargin = project.creativeConfig?.safetyMargin ?: 512
        )
        val messages = listOf(
            ChatMessage(
                ChatRole.SYSTEM,
                promptBuilder.buildSystemPrompt(
                    project.creativeConfig ?: com.novelforge.app.domain.model.CreativeConfig(),
                    context.characters
                )
            )
        ) + promptBuilder.buildChapterPrompt(chapter, context, budget)
        return ChatRequest(
            messages = messages,
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
}
