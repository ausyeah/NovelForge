package com.novelforge.app.infrastructure.jobs

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.novelforge.app.data.security.ApiKeyStore
import com.novelforge.app.data.settings.AppSettings
import com.novelforge.app.data.settings.AppSettingsStore
import com.novelforge.app.domain.model.ChapterRevision
import com.novelforge.app.domain.model.ChapterStatus
import com.novelforge.app.domain.model.FlowState
import com.novelforge.app.domain.model.GenerationJob
import com.novelforge.app.domain.model.GenerationJobStatus
import com.novelforge.app.domain.model.GenerationPurpose
import com.novelforge.app.domain.model.LlmCall
import com.novelforge.app.domain.model.LlmUsage
import com.novelforge.app.domain.model.OutlineItem
import com.novelforge.app.domain.model.OutlineVersion
import com.novelforge.app.domain.model.ProjectStatus
import com.novelforge.app.domain.model.PromptSnapshot
import com.novelforge.app.domain.repository.ChapterRepository
import com.novelforge.app.domain.repository.GenerationArtifactRepository
import com.novelforge.app.domain.repository.GenerationRepository
import com.novelforge.app.domain.repository.OutlineRepository
import com.novelforge.app.domain.repository.PromptSnapshotRepository
import com.novelforge.app.domain.repository.ProjectRepository
import com.novelforge.app.domain.usecase.GenerateChapterUseCase
import com.novelforge.app.domain.usecase.GenerateOutlineUseCase
import com.novelforge.app.domain.usecase.QueuedGeneration
import com.novelforge.app.infrastructure.llm.ChatMessage
import com.novelforge.app.infrastructure.llm.ChatOptions
import com.novelforge.app.infrastructure.llm.ChatRequest
import com.novelforge.app.infrastructure.llm.ChatRole
import com.novelforge.app.infrastructure.llm.ChapterContext
import com.novelforge.app.infrastructure.llm.LLMClient
import com.novelforge.app.infrastructure.llm.LLMConnectionConfig
import com.novelforge.app.infrastructure.llm.JsonResponseValidator
import com.novelforge.app.infrastructure.llm.OutlineEnvelope
import com.novelforge.app.infrastructure.llm.OpenAiCompatibleClient
import com.novelforge.app.infrastructure.llm.ProviderCapabilities
import com.novelforge.app.infrastructure.llm.StreamEvent
import com.novelforge.app.infrastructure.llm.stripInlineReasoning
import com.novelforge.app.infrastructure.llm.ProviderHttpException
import com.novelforge.app.infrastructure.llm.PromptBuilder
import com.novelforge.app.infrastructure.llm.ResponseFormat
import com.novelforge.app.infrastructure.llm.ResponseFormatKind
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ConnectionTestResult(
    val firstTokenMs: Long,
    val totalMs: Long,
    val replyPreview: String
)

/**
 * Application-scoped boundary between Compose, WorkManager and the local
 * repositories. UI queues a durable job; this class is also what a Worker
 * uses after the Activity has gone away.
 */
class GenerationRuntime(
    context: Context,
    private val projectRepository: ProjectRepository,
    private val generationRepository: GenerationRepository,
    private val generationArtifactRepository: GenerationArtifactRepository,
    private val outlineRepository: OutlineRepository,
    private val chapterRepository: ChapterRepository,
    private val promptSnapshotRepository: PromptSnapshotRepository,
    private val settingsStore: AppSettingsStore,
    private val apiKeyStore: ApiKeyStore,
    private val llmClient: LLMClient = OpenAiCompatibleClient(),
    private val promptBuilder: PromptBuilder = PromptBuilder(),
    private val now: () -> Long = { System.currentTimeMillis() }
) : GenerationWorkerDependencies {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    private val coordinator = GenerationCoordinator(generationRepository, llmClient, now)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val validator = JsonResponseValidator()
    private val generateOutline = GenerateOutlineUseCase(projectRepository, generationRepository, now)
    private val generateChapter = GenerateChapterUseCase(
        projectRepository,
        generationRepository,
        promptBuilder,
        now
    )

    private data class ArtifactPersistResult(
        val saved: Boolean,
        val reason: String? = null
    )

    private data class OutlineProgress(
        val chapters: List<OutlineItem>,
        val canonicalContent: String,
        val hasPendingOutput: Boolean,
        val totalChapterCount: Int,
        val currentChapterNumber: Int
    )

    private data class PreparedExecution(
        val job: GenerationJob,
        val request: ChatRequest?,
        val outlineProgress: OutlineProgress?
    )

    fun observeJob(id: String): Flow<GenerationJob?> = generationRepository.observeJob(id)

    /** 全自动模式开关（轮次流转：大纲一批 → 本批正文逐章 → 下一批） */
    val autoRunEnabled: Flow<Boolean> = settingsStore.settings.map { it.autoRunEnabled }

    suspend fun setAutoRun(enabled: Boolean) {
        settingsStore.update { it.copy(autoRunEnabled = enabled) }
    }

    /**
     * 一键全自动：打开开关并从当前进度直接开跑。
     * 全新项目 → 生成本批大纲；大纲已就绪 → 从第一个没正文的章节续写；
     * 本批正文写完 → 自动出下一批。失败时停下等用户处理。
     */
    suspend fun startAutoRun(projectId: String) {
        setAutoRun(true)
        driveAutoNext(projectId)
    }

    /**
     * 全自动流转的调度中枢。在每一章正文/每一批大纲成功落库后调用：
     * - 本轮还有未写正文的章节 → 续写下一章
     * - 本轮 10 章全部写完且全书未完 → 生下一批大纲（R+1）
     * - 全书完成 → 停止
     */
    private suspend fun driveAutoNext(projectId: String) {
        val settings = settingsStore.settings.first()
        if (!settings.autoRunEnabled) return
        val project = projectRepository.getProject(projectId) ?: return
        val total = project.creativeConfig?.chapterCount?.coerceIn(1, 200) ?: return
        val outline = outlineRepository.latest(projectId)
        val chapters = outline?.chapters?.sortedBy { it.orderIndex }.orEmpty()
        if (chapters.isEmpty()) {
            // 全新项目：第一步永远是生成本批大纲
            val active = generationRepository.findActiveJob(
                projectId, GenerationPurpose.OUTLINE.name, null
            )
            if (active == null) {
                queueOutline(projectId)
            } else {
                // 已有大纲任务但可能工作链已丢失（历史 KEEP 拒绝入队）→ 重新入队复活
                enqueue(active, replaceCompleted = true)
            }
            return
        }
        // 按顺序找第一个还没有正文的章节（不区分轮次，保证上一轮全部写完才推进）
        val nextChapter = chapters.firstOrNull { chapter ->
            chapterRepository.latest(projectId, chapter.id) == null
        }
        if (nextChapter != null) {
            // 同一章失败自动重试的次数上限：3 次任务都失败则停下等用户处理
            val attempts = generationRepository.countJobs(
                projectId, GenerationPurpose.CHAPTER.name, nextChapter.id
            )
            if (attempts >= 3) return
            val active = generationRepository.findActiveJob(
                projectId, GenerationPurpose.CHAPTER.name, nextChapter.id
            )
            if (active != null) {
                // 复活可能丢失工作链的僵尸章节任务
                enqueue(active, replaceCompleted = true)
                return
            }
            val previous = chapters
                .filter { it.orderIndex < nextChapter.orderIndex }
                .maxByOrNull { it.orderIndex }
                ?.let { previousChapter -> chapterRepository.latest(projectId, previousChapter.id) }
            val context = ChapterContext(
                continuityState = project.continuityState,
                characters = emptyList(),
                previousSummary = previous?.summary,
                previousTail = previous?.content?.takeLast(1_500)
            )
            queueChapter(
                projectId = projectId,
                chapter = nextChapter,
                context = context,
                clientRequestId = "auto-${projectId}-${nextChapter.id}-${System.currentTimeMillis()}"
            )
        } else if (chapters.size < total) {
            // 本轮正文全部写完 → 下一批大纲
            // （PAUSED 的旧任务会被 queueOutline 复用并以 REPLACE 重新入队）
            queueOutline(projectId, continueFromExisting = true)
        }
    }

    suspend fun queueOutline(
        projectId: String,
        clientRequestId: String = UUID.randomUUID().toString(),
        continueFromExisting: Boolean = false
    ): GenerationJob {
        val settings = settingsStore.settings.first()
        val queued = generateOutline(
            projectId = projectId,
            connection = connection(settings),
            outputTokenBudget = settings.outputBudget,
            clientRequestId = clientRequestId
        )
        savePromptSnapshot(queued)
        var enqueuedJob = resetOutlineJobForRetry(queued.job, now())
        // 继续生成：以最新大纲版本为准重置任务内容。复用的旧任务里可能残留
        // 失败批次的残缺输出（解析为 0 章就会从引子重开），必须覆盖掉
        if (continueFromExisting) {
            val latest = outlineRepository.latest(projectId)
            if (latest != null && latest.chapters.isNotEmpty()) {
                enqueuedJob = enqueuedJob.copy(
                    partialContent = json.encodeToString(OutlineEnvelope(latest.chapters))
                )
            }
        }
        // 必须把重置后的状态写回数据库；否则 Worker 读到的仍是 NEEDS_USER，
        // execute() 会直接跳过执行，用户点击“修复并重试”就毫无反应。
        generationRepository.updateJob(enqueuedJob)
        enqueue(
            enqueuedJob,
            // PAUSED（上一批大纲完成等待正文）也要 REPLACE：
            // 旧工作链已完结时 KEEP 策略会拒绝重新入队，导致下一批大纲永远不开始
            replaceCompleted = enqueuedJob.status == GenerationJobStatus.RECOVERABLE_PARTIAL ||
                enqueuedJob.status == GenerationJobStatus.QUEUED ||
                enqueuedJob.status == GenerationJobStatus.PAUSED
        )
        return enqueuedJob
    }

    suspend fun queueChapter(
        projectId: String,
        chapter: OutlineItem,
        context: ChapterContext,
        clientRequestId: String = UUID.randomUUID().toString()
    ): GenerationJob {
        val settings = settingsStore.settings.first()
        val queued = generateChapter(
            projectId = projectId,
            chapter = chapter,
            context = context,
            connection = connection(settings),
            outputTokenBudget = settings.outputBudget,
            clientRequestId = clientRequestId
        )
        savePromptSnapshot(queued)
        enqueue(
            queued.job,
            replaceCompleted = queued.job.status == GenerationJobStatus.RECOVERABLE_PARTIAL
        )
        return queued.job
    }

    suspend fun findActiveJob(
        projectId: String,
        purpose: GenerationPurpose,
        targetId: String?
    ): GenerationJob? = generationRepository.findActiveJob(projectId, purpose.name, targetId)

    suspend fun cancel(jobId: String) {
        generationRepository.findById(jobId)?.let { job ->
            generationRepository.updateJob(
                job.copy(status = GenerationJobStatus.CANCELLED, updatedAt = now())
            )
        }
        workManager.cancelUniqueWork(uniqueWorkName(jobId))
    }

    suspend fun testConnection(): Result<ConnectionTestResult> = runCatching {
        try {
            withTimeout(CONNECTION_TEST_TIMEOUT_MS) {
                val settings = settingsStore.settings.first()
                val requestId = "test-${UUID.randomUUID()}"
                val startedAt = now()
                var firstTokenMs: Long? = null
                val content = StringBuilder()
                llmClient.streamChat(
                    ChatRequest(
                        messages = listOf(
                            ChatMessage(ChatRole.SYSTEM, "你是连接测试助手。"),
                            ChatMessage(ChatRole.USER, "只回复 OK。")
                        ),
                        config = connection(settings),
                        options = ChatOptions(
                            outputTokenBudget = 1024,
                            requestId = requestId,
                            timeoutMs = CONNECTION_TEST_TIMEOUT_MS,
                            stream = true
                        )
                    )
                ).collect { event ->
                    when (event) {
                        is StreamEvent.Delta -> {
                            if (firstTokenMs == null) firstTokenMs = now() - startedAt
                            content.append(event.text)
                        }
                        else -> Unit
                    }
                }
                val cleanedReply = stripInlineReasoning(content.toString())
                require(cleanedReply.isNotBlank()) {
                    "模型返回了空响应：思考型（推理）模型可能把输出额度耗尽在思考过程上，" +
                        "请更换为非思考模型、关闭思考模式或调大输出上限后重试"
                }
                ConnectionTestResult(
                    firstTokenMs = firstTokenMs ?: (now() - startedAt),
                    totalMs = now() - startedAt,
                    replyPreview = cleanedReply.take(24)
                )
            }
        } catch (error: TimeoutCancellationException) {
            throw IOException("连接测试超时，请检查网络、Base URL 和模型名", error)
        }
    }

    override suspend fun runGeneration(
        jobId: String,
        runAttemptCount: Int
    ): GenerationExecutionResult {
        val job = generationRepository.findById(jobId) ?: return GenerationExecutionResult.Failure
        if (job.status == GenerationJobStatus.CANCELLED ||
            job.status == GenerationJobStatus.COMPLETED ||
            job.status == GenerationJobStatus.NEEDS_USER
        ) {
            return GenerationExecutionResult.Success
        }

        val settings = settingsStore.settings.first()
        val startedAt = now()
        var usage = LlmUsage(estimated = true)
        var preparedJob = job
        var model = settings.model

        return try {
            val prepared = if (job.purpose == GenerationPurpose.OUTLINE) {
                prepareOutlineExecution(job, settings)
            } else {
                val snapshot = promptSnapshotRepository.findById(job.promptSnapshotId)
                    ?: return markFailure(job, "PROMPT_SNAPSHOT_MISSING", "生成任务缺少 prompt 快照")
                PreparedExecution(
                    job = job,
                    request = restoreRequest(job, snapshot, settings),
                    outlineProgress = null
                )
            }
            preparedJob = prepared.job
            model = prepared.request?.config?.model ?: settings.model

            if (prepared.request == null) {
                return completeStoredOutline(prepared, settings, startedAt)
            }

            var completed: GenerationEvent.Completed? = null
            coordinator.execute(prepared.job.id, prepared.request).collect { event ->
                when (event) {
                    is GenerationEvent.Completed -> completed = event
                    is GenerationEvent.Usage -> usage = LlmUsage(
                        inputTokens = event.inputTokens,
                        outputTokens = event.outputTokens,
                        totalTokens = event.inputTokens?.let { input ->
                            event.outputTokens?.let { output -> input + output }
                        },
                        estimated = event.estimated
                    )
                    else -> Unit
                }
            }
            val finalJob = requireNotNull(generationRepository.findById(prepared.job.id))
            val call = buildLlmCall(
                job = finalJob,
                model = model,
                settings = settings,
                usage = usage,
                startedAt = startedAt,
                success = false
            )
            if (prepared.outlineProgress != null) {
                finishOutlineExecution(
                    prepared = prepared,
                    finalJob = finalJob,
                    completed = completed,
                    call = call
                )
            } else {
                finishStandardExecution(
                    finalJob = finalJob,
                    completed = completed,
                    call = call
                )
            }
        } catch (error: Throwable) {
            val currentJob = generationRepository.findById(preparedJob.id) ?: preparedJob
            val retry = isRetryable(error) && runAttemptCount < MAX_WORK_ATTEMPTS - 1
            val recordedJob = if (retry) {
                currentJob.copy(
                    status = GenerationJobStatus.QUEUED,
                    errorType = error::class.simpleName,
                    errorMessage = "网络请求将在稍后重试",
                    updatedAt = now()
                )
            } else if (
                currentJob.purpose == GenerationPurpose.OUTLINE &&
                    currentJob.status != GenerationJobStatus.CANCELLED
            ) {
                currentJob.copy(
                    status = GenerationJobStatus.RECOVERABLE_PARTIAL,
                    errorType = error::class.simpleName,
                    errorMessage = "第 ${readOutlineProgress(currentJob.partialContent, 200).currentChapterNumber} 章请求失败，可重试继续生成",
                    updatedAt = now()
                )
            } else {
                currentJob
            }
            val failedCall = buildLlmCall(
                job = recordedJob,
                model = model,
                settings = settings,
                usage = usage,
                startedAt = startedAt,
                success = false
            )
            generationArtifactRepository.saveJobAndLlmCall(recordedJob, failedCall)
            if (retry) {
                GenerationExecutionResult.Retry
            } else {
                GenerationExecutionResult.Failure
            }
        }
    }

    private suspend fun prepareOutlineExecution(
        job: GenerationJob,
        settings: AppSettings
    ): PreparedExecution {
        val project = requireNotNull(projectRepository.getProject(job.projectId)) { "项目不存在" }
        val creativeConfig = checkNotNull(project.creativeConfig) { "请先完成创作设置" }
        val totalChapterCount = creativeConfig.chapterCount.coerceIn(1, 200)
        val progress = readOutlineProgress(job.partialContent, totalChapterCount)
        if (progress.chapters.size >= totalChapterCount) {
            return PreparedExecution(job, request = null, outlineProgress = progress)
        }

        val requestId = "${job.clientRequestId}-outline-chapter-${progress.currentChapterNumber}"
        val request = generateOutline.buildChapterRequest(
            project = project,
            connection = connection(settings),
            outputTokenBudget = settings.outputBudget,
            requestId = requestId,
            chapterNumber = progress.currentChapterNumber,
            previousChapters = progress.chapters.takeLast(PREVIOUS_CONTEXT_LIMIT)
        ).let { baseRequest ->
            baseRequest.copy(
                options = baseRequest.options.copy(
                stream = true,
                requestId = requestId,
                checkpointPrefix = progress.canonicalContent
                )
            )
        }
        val snapshotId = UUID.randomUUID().toString()
        val updatedJob = job.copy(
            status = GenerationJobStatus.QUEUED,
            partialContent = progress.canonicalContent.ifBlank { job.partialContent },
            promptSnapshotId = snapshotId,
            errorType = null,
            errorMessage = null,
            updatedAt = now()
        )
        savePromptSnapshot(updatedJob, request.copy(options = request.options.copy(requestId = requestId)))
        generationRepository.updateJob(updatedJob)
        return PreparedExecution(updatedJob, request, progress)
    }

    private fun readOutlineProgress(raw: String, totalChapterCount: Int): OutlineProgress {
        if (raw.isBlank()) {
            return OutlineProgress(
                chapters = emptyList(),
                canonicalContent = "",
                hasPendingOutput = false,
                totalChapterCount = totalChapterCount,
                currentChapterNumber = 1
            )
        }
        val normalized = JsonResponseValidator.extractJsonValue(raw)
        if (normalized == null) {
            return OutlineProgress(
                chapters = emptyList(),
                canonicalContent = "",
                hasPendingOutput = true,
                totalChapterCount = totalChapterCount,
                currentChapterNumber = 1
            )
        }
        return when (val parsed = validator.parseOutline(normalized)) {
            is com.novelforge.app.infrastructure.llm.JsonValidationResult.Failure ->
                OutlineProgress(
                    chapters = emptyList(),
                    canonicalContent = "",
                    hasPendingOutput = true,
                    totalChapterCount = totalChapterCount,
                    currentChapterNumber = 1
                )

            is com.novelforge.app.infrastructure.llm.JsonValidationResult.Success -> {
                val start = raw.indexOf(normalized)
                val suffix = if (start >= 0) {
                    raw.substring(start + normalized.length).trim()
                } else {
                    ""
                }
                val chapters = parsed.value.mapIndexed { index, item -> item.copy(orderIndex = index) }
                OutlineProgress(
                    chapters = chapters,
                    canonicalContent = json.encodeToString(OutlineEnvelope(chapters)),
                    hasPendingOutput = suffix.isNotBlank(),
                    totalChapterCount = totalChapterCount,
                    currentChapterNumber = (chapters.size + 1).coerceAtMost(totalChapterCount)
                )
            }
        }
    }

    private suspend fun completeStoredOutline(
        prepared: PreparedExecution,
        settings: AppSettings,
        startedAt: Long
    ): GenerationExecutionResult {
        val progress = requireNotNull(prepared.outlineProgress)
        val completedJob = prepared.job.copy(
            status = GenerationJobStatus.COMPLETED,
            partialContent = progress.canonicalContent,
            errorType = null,
            errorMessage = null,
            updatedAt = now()
        )
        val call = buildLlmCall(
            job = completedJob,
            model = settings.model,
            settings = settings,
            usage = LlmUsage(estimated = true),
            startedAt = startedAt,
            success = true
        )
        val result = persistOutline(completedJob, progress.canonicalContent, call)
        if (!result.saved) {
            generationArtifactRepository.saveJobAndLlmCall(
                completedJob.copy(
                    status = GenerationJobStatus.NEEDS_USER,
                    errorType = "INVALID_JSON",
                    errorMessage = result.reason,
                    updatedAt = now()
                ),
                call.copy(success = false)
            )
        }
        return GenerationExecutionResult.Success
    }

    private suspend fun finishOutlineExecution(
        prepared: PreparedExecution,
        finalJob: GenerationJob,
        completed: GenerationEvent.Completed?,
        call: LlmCall
    ): GenerationExecutionResult {
        val progress = requireNotNull(prepared.outlineProgress)
        if (finalJob.status != GenerationJobStatus.COMPLETED || completed == null) {
            generationArtifactRepository.saveJobAndLlmCall(finalJob, call)
            return GenerationExecutionResult.Success
        }

        return when (val parsed = validator.parseOutline(completed.content)) {
            is com.novelforge.app.infrastructure.llm.JsonValidationResult.Failure -> {
                // 保存给用户手动修复的原始输出剔除思考内容，避免修复框里全是思考文字
                val preserved = checkpointContent(progress.canonicalContent, stripInlineReasoning(completed.content))
                generationArtifactRepository.saveJobAndLlmCall(
                    finalJob.copy(
                        status = GenerationJobStatus.NEEDS_USER,
                        partialContent = preserved,
                        errorType = "INVALID_JSON",
                        errorMessage = "第 ${progress.currentChapterNumber} 章大纲未通过结构校验：${parsed.reason}。请点击“修复”手动修改，或点击“重试”重新生成",
                        updatedAt = now()
                    ),
                    call
                )
                GenerationExecutionResult.Success
            }

            is com.novelforge.app.infrastructure.llm.JsonValidationResult.Success -> {
                // 分批生成：一批可能返回多个推进段；统一重编号后追加，
                // 只保留未完成章节所需数量，防止模型多给导致章节数超限
                val start = progress.currentChapterNumber
                val remaining = (progress.totalChapterCount - progress.chapters.size).coerceAtLeast(0)
                val newItems = parsed.value.take(remaining).mapIndexed { index, item ->
                    item.copy(
                        id = "chapter-${start + index}",
                        orderIndex = start - 1 + index
                    )
                }
                val item = newItems.firstOrNull()
                if (item == null) {
                    generationArtifactRepository.saveJobAndLlmCall(
                        finalJob.copy(
                            status = GenerationJobStatus.NEEDS_USER,
                            partialContent = checkpointContent(progress.canonicalContent, completed.content),
                            errorType = "EMPTY_BATCH",
                            errorMessage = "本批大纲没有返回可用的推进段，请点“重试”或“修复”",
                            updatedAt = now()
                        ),
                        call
                    )
                    return GenerationExecutionResult.Success
                }
                val chapters = progress.chapters + newItems
                val cumulativeContent = json.encodeToString(OutlineEnvelope(chapters))
                val successfulCall = call.copy(success = true)
                val savedJob = finalJob.copy(
                    partialContent = cumulativeContent,
                    errorType = null,
                    errorMessage = null,
                    updatedAt = now()
                )
                if (chapters.size < progress.totalChapterCount) {
                    // 轮次机制：每批（10 章）完成后暂停，等本批正文写完再继续下一批；
                    // 先把当前累积大纲落为版本，供用户查看/编辑
                    val result = persistOutline(
                        savedJob.copy(status = GenerationJobStatus.PAUSED),
                        cumulativeContent,
                        successfulCall
                    )
                    if (!result.saved) {
                        generationArtifactRepository.saveJobAndLlmCall(
                            savedJob.copy(
                                status = GenerationJobStatus.NEEDS_USER,
                                errorType = "OUTLINE_SAVE_FAILED",
                                errorMessage = result.reason,
                                updatedAt = now()
                            ),
                            call
                        )
                    } else {
                        driveAutoNext(savedJob.projectId)
                    }
                    GenerationExecutionResult.Success
                } else {
                    val result = persistOutline(
                        savedJob.copy(status = GenerationJobStatus.COMPLETED),
                        cumulativeContent,
                        successfulCall
                    )
                    if (!result.saved) {
                        generationArtifactRepository.saveJobAndLlmCall(
                            savedJob.copy(
                                status = GenerationJobStatus.NEEDS_USER,
                                errorType = "OUTLINE_SAVE_FAILED",
                                errorMessage = result.reason,
                                updatedAt = now()
                            ),
                            call
                        )
                    }
                    GenerationExecutionResult.Success
                }
            }
        }
    }

    private suspend fun finishStandardExecution(
        finalJob: GenerationJob,
        completed: GenerationEvent.Completed?,
        call: LlmCall
    ): GenerationExecutionResult {
        val needsArtifact = finalJob.status == GenerationJobStatus.COMPLETED &&
            finalJob.purpose == GenerationPurpose.CHAPTER
        val artifactResult = if (needsArtifact && completed != null) {
            persistArtifact(finalJob, completed.content, call)
        } else {
            ArtifactPersistResult(saved = !needsArtifact)
        }
        val artifactSaved = artifactResult.saved
        val recordedJob = if (artifactSaved) {
            finalJob
        } else {
            finalJob.copy(
                status = GenerationJobStatus.NEEDS_USER,
                errorType = "INVALID_JSON",
                errorMessage = "模型输出未通过结构校验：${artifactResult.reason ?: "未返回完整内容"}。请点击“修复”手动修改，或点击“重试”重新生成",
                updatedAt = now()
            )
        }
        val recordedCall = if (artifactSaved && finalJob.status == GenerationJobStatus.COMPLETED) {
            call.copy(success = true)
        } else {
            call
        }
        if (!needsArtifact || !artifactSaved) {
            generationArtifactRepository.saveJobAndLlmCall(recordedJob, recordedCall)
        }
        if (artifactSaved && finalJob.status == GenerationJobStatus.COMPLETED &&
            finalJob.purpose == GenerationPurpose.CHAPTER
        ) {
            // 一章写完 → 全自动模式下继续流转（下一章 / 下一批大纲）
            driveAutoNext(finalJob.projectId)
        } else if (!artifactSaved && finalJob.purpose == GenerationPurpose.CHAPTER) {
            // 全自动模式下正文失败自动重试（每章最多 3 次任务，超过交给用户处理）
            driveAutoNext(finalJob.projectId)
        }
        return GenerationExecutionResult.Success
    }

    private suspend fun savePromptSnapshot(queued: QueuedGeneration) {
        if (promptSnapshotRepository.findById(queued.job.promptSnapshotId) != null) return
        savePromptSnapshot(queued.job, queued.request)
    }

    private suspend fun savePromptSnapshot(job: GenerationJob, request: ChatRequest) {
        if (promptSnapshotRepository.findById(job.promptSnapshotId) != null) return
        val messagesJson = json.encodeToString(request.messages)
        promptSnapshotRepository.save(
            PromptSnapshot(
                id = job.promptSnapshotId,
                systemPrompt = request.messages
                    .firstOrNull { it.role == ChatRole.SYSTEM }
                    ?.content
                    .orEmpty(),
                messagesJson = messagesJson,
                model = request.config.model,
                temperature = request.options.temperature,
                createdAt = now()
            )
        )
    }

    private suspend fun restoreRequest(
        job: GenerationJob,
        snapshot: PromptSnapshot,
        settings: AppSettings
    ): ChatRequest = ChatRequest(
        messages = json.decodeFromString<List<ChatMessage>>(snapshot.messagesJson),
        config = connection(settings).copy(model = snapshot.model),
        options = ChatOptions(
            temperature = snapshot.temperature,
            outputTokenBudget = settings.outputBudget,
            responseFormat = ResponseFormat(ResponseFormatKind.JSON_OBJECT),
            stream = true,
            requestId = job.clientRequestId
        )
    )

    private suspend fun persistArtifact(
        job: GenerationJob,
        content: String,
        call: LlmCall
    ): ArtifactPersistResult {
        return when (job.purpose) {
            GenerationPurpose.OUTLINE -> persistOutline(job, content, call)
            GenerationPurpose.CHAPTER -> persistChapter(job, content, call)
            else -> ArtifactPersistResult(saved = true)
        }
    }

    private suspend fun persistOutline(
        job: GenerationJob,
        content: String,
        call: LlmCall
    ): ArtifactPersistResult {
        return when (val parsed = validator.parseOutline(content)) {
            is com.novelforge.app.infrastructure.llm.JsonValidationResult.Failure ->
                ArtifactPersistResult(saved = false, reason = parsed.reason)

            is com.novelforge.app.infrastructure.llm.JsonValidationResult.Success -> {
                val project = projectRepository.getProject(job.projectId)
                    ?: return ArtifactPersistResult(saved = false, reason = "项目不存在")
                val previous = outlineRepository.latest(job.projectId)
                val chapters = parsed.value.mapIndexed { index, item -> item.copy(orderIndex = index) }
                val version = OutlineVersion(
                    id = UUID.randomUUID().toString(),
                    projectId = job.projectId,
                    version = (previous?.version ?: 0) + 1,
                    chapters = chapters,
                    diffSummary = "模型生成初稿",
                    createdAt = now()
                )
                generationArtifactRepository.saveOutlineResult(
                    version,
                    project.copy(
                        activeOutlineVersionId = version.id,
                        status = ProjectStatus.OUTLINING,
                        flowState = FlowState.OUTLINE_EDIT,
                        updatedAt = now()
                    ),
                    job,
                    call.copy(success = true)
                )
                ArtifactPersistResult(saved = true)
            }
        }
    }

    private suspend fun persistChapter(
        job: GenerationJob,
        content: String,
        call: LlmCall
    ): ArtifactPersistResult {
        return when (val parsed = validator.parseChapter(content)) {
            is com.novelforge.app.infrastructure.llm.JsonValidationResult.Failure ->
                ArtifactPersistResult(saved = false, reason = parsed.reason)

            is com.novelforge.app.infrastructure.llm.JsonValidationResult.Success -> {
                val itemId = job.targetId
                    ?: return ArtifactPersistResult(saved = false, reason = "章节任务缺少目标章节")
                val outline = outlineRepository.latest(job.projectId)
                    ?: return ArtifactPersistResult(saved = false, reason = "项目没有可用大纲")
                val outlineItem = outline.chapters.firstOrNull { it.id == itemId }
                    ?: return ArtifactPersistResult(saved = false, reason = "目标章节不存在")
                val previous = chapterRepository.latest(job.projectId, itemId)
                val revision = ChapterRevision(
                    id = UUID.randomUUID().toString(),
                    projectId = job.projectId,
                    outlineItemId = itemId,
                    outlineVersionId = outline.id,
                    revision = (previous?.revision ?: 0) + 1,
                    title = outlineItem.title,
                    content = parsed.value.content,
                    summary = parsed.value.summary,
                    status = ChapterStatus.FINALIZED,
                    promptSnapshotId = job.promptSnapshotId,
                    createdAt = now()
                )
                val project = projectRepository.getProject(job.projectId)
                    ?: return ArtifactPersistResult(saved = false, reason = "项目不存在")
                generationArtifactRepository.saveChapterResult(
                    revision,
                    project.copy(
                        status = ProjectStatus.WRITING,
                        flowState = FlowState.CHAPTER_REVIEW,
                        updatedAt = now()
                    ),
                    job,
                    call.copy(success = true)
                )
                ArtifactPersistResult(saved = true)
            }
        }
    }

    private fun buildLlmCall(
        job: GenerationJob,
        model: String,
        settings: AppSettings,
        usage: LlmUsage,
        startedAt: Long,
        success: Boolean
    ): LlmCall = LlmCall(
        id = UUID.randomUUID().toString(),
        projectId = job.projectId,
        jobId = job.id,
        purpose = job.purpose,
        provider = settings.providerName.ifBlank { "OpenAI-compatible" },
        model = model,
        usage = usage,
        durationMs = (now() - startedAt).coerceAtLeast(0L),
        success = success,
        createdAt = now()
    )

    private suspend fun markFailure(
        job: GenerationJob,
        type: String,
        message: String
    ): GenerationExecutionResult {
        generationRepository.updateJob(
            job.copy(
                status = GenerationJobStatus.FAILED,
                errorType = type,
                errorMessage = message,
                updatedAt = now()
            )
        )
        return GenerationExecutionResult.Failure
    }

    private suspend fun connection(settings: AppSettings): LLMConnectionConfig {
        val key = requireNotNull(apiKeyStore.read()) { "请先在模型设置中保存 API Key" }
        require(settings.baseUrl.isNotBlank()) { "请先填写 Base URL" }
        require(settings.model.isNotBlank()) { "请先填写模型名" }
        return LLMConnectionConfig(
            baseUrl = settings.baseUrl.trim(),
            apiKey = key,
            model = settings.model.trim(),
            capabilities = ProviderCapabilities(
                supportsStreaming = true,
                supportsJsonObject = true,
                supportsUsageInStream = true
            ),
            disableThinking = settings.disableThinking
        )
    }
    private fun resetOutlineJobForRetry(job: GenerationJob, now: Long): GenerationJob {
        if (job.status != GenerationJobStatus.NEEDS_USER) return job
        return job.copy(
            status = GenerationJobStatus.QUEUED,
            errorType = null,
            errorMessage = null,
            // 重试视为一次全新尝试；等待时长等 UI 以 createdAt 计时，需一并刷新
            createdAt = now,
            updatedAt = now
        )
    }


    private fun isRetryable(error: Throwable): Boolean = when (error) {
        is IOException -> true
        is ProviderHttpException -> error.statusCode in RETRYABLE_HTTP_CODES
        else -> false
    }

    private fun enqueue(job: GenerationJob, replaceCompleted: Boolean = false) {
        workManager.enqueueUniqueWork(
            uniqueWorkName(job.id),
            if (replaceCompleted) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            buildWorkRequest(job.id)
        )
    }

    private fun enqueueNextOutlineWork(jobId: String) {
        workManager.enqueueUniqueWork(
            uniqueWorkName(jobId),
            ExistingWorkPolicy.APPEND,
            buildWorkRequest(jobId)
        )
    }

    private fun buildWorkRequest(jobId: String) = OneTimeWorkRequestBuilder<GenerationWorker>()
            .setInputData(workDataOf(GenerationWorker.KEY_JOB_ID to jobId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            .addTag(TAG)
            .build()

    private fun checkpointContent(prefix: String, current: String): String =
        if (prefix.isBlank()) current else prefix + current

    private fun uniqueWorkName(jobId: String) = "generation-$jobId"

    companion object {
        private const val TAG = "novelforge-generation"
        private const val CONNECTION_TEST_TIMEOUT_MS = 25_000L
        private const val MAX_WORK_ATTEMPTS = 3
        private const val PREVIOUS_CONTEXT_LIMIT = 3
        private val RETRYABLE_HTTP_CODES = setOf(408, 425, 429, 500, 502, 503, 504)
    }
}
