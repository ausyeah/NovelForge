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
import com.novelforge.app.data.settings.AutoRunStore
import com.novelforge.app.domain.model.ChapterRevision
import com.novelforge.app.domain.model.ChapterStatus
import com.novelforge.app.domain.model.FlowState
import com.novelforge.app.domain.model.GenerationJob
import com.novelforge.app.domain.model.GenerationJobStatus
import com.novelforge.app.domain.model.GenerationPurpose
import com.novelforge.app.domain.model.LlmCall
import com.novelforge.app.domain.model.LlmUsage
import com.novelforge.app.domain.model.MAX_CHAPTER_COUNT
import com.novelforge.app.domain.model.MIN_CHAPTER_COUNT
import com.novelforge.app.domain.model.OutlineItem
import com.novelforge.app.domain.model.OutlineVersion
import com.novelforge.app.domain.model.Project
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
import com.novelforge.app.infrastructure.llm.LlmResponse
import com.novelforge.app.infrastructure.llm.JsonResponseValidator
import com.novelforge.app.infrastructure.llm.OutlineEnvelope
import com.novelforge.app.infrastructure.llm.MemorySelector
import com.novelforge.app.infrastructure.llm.excerptForExtraction
import com.novelforge.app.infrastructure.llm.chapterMemoryHint
import com.novelforge.app.infrastructure.llm.OpenAiCompatibleClient
import com.novelforge.app.infrastructure.llm.parseMemoryNotes
import com.novelforge.app.infrastructure.llm.preserveOutlineIndexes
import com.novelforge.app.infrastructure.llm.ProviderCapabilities
import com.novelforge.app.infrastructure.llm.StreamEvent
import com.novelforge.app.infrastructure.llm.stripInlineReasoning
import com.novelforge.app.infrastructure.llm.ProviderHttpException
import com.novelforge.app.infrastructure.llm.ProviderProtocolException
import com.novelforge.app.infrastructure.llm.PromptBuilder
import com.novelforge.app.infrastructure.llm.ResponseFormat
import com.novelforge.app.infrastructure.llm.ResponseFormatKind
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    private val llmCallRepository: com.novelforge.app.domain.repository.LlmCallRepository,
    private val settingsStore: AppSettingsStore,
    private val autoRunStore: AutoRunStore,
    private val apiKeyStore: ApiKeyStore,
    private val llmClient: LLMClient = OpenAiCompatibleClient(),
    private val promptBuilder: PromptBuilder = PromptBuilder(),
    private val now: () -> Long = { System.currentTimeMillis() }
) : GenerationWorkerDependencies {
    // 必须 lazy：构造期调 WorkManager.getInstance() 会在 workManagerConfiguration getter
    // 尚未返回时再次触发初始化 → 递归 → 自定义 WorkerFactory 丢失、worker 无法创建
    private val workManager by lazy { WorkManager.getInstance(context.applicationContext) }
    private val coordinator = GenerationCoordinator(generationRepository, llmClient, now)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val validator = JsonResponseValidator()
    // 每本书一把锁：以前是全局一把，A 书的自动流转会卡住 B 书的调度。
    private val autoNextLocks = ConcurrentHashMap<String, Mutex>()
    private fun autoNextLock(projectId: String) = autoNextLocks.getOrPut(projectId) { Mutex() }
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
        /** 下一批新章节应占用的 0 基序号（= 已有最大 orderIndex + 1，容忍删章留洞） */
        val nextOrderIndex: Int,
        val currentChapterNumber: Int
    )

    private data class PreparedExecution(
        val job: GenerationJob,
        val request: ChatRequest?,
        val outlineProgress: OutlineProgress?
    )

    fun observeJob(id: String): Flow<GenerationJob?> = generationRepository.observeJob(id)

    /** 全自动模式开关（轮次流转：大纲一批 → 本批正文逐章 → 下一批）。按书存，不跨书串联。 */
    fun autoRunEnabled(projectId: String): Flow<Boolean> = autoRunStore.observe(projectId)

    suspend fun setAutoRun(projectId: String, enabled: Boolean) {
        autoRunStore.set(projectId, enabled)
    }

    /**
     * 启动清扫：进程被杀（force-stop / 系统回收）后 WorkManager 的 UniqueWork 已消失，
     * 但库里还挂着 QUEUED/RUNNING——driveAutoNext 会把这些僵尸任务当成"正在跑"，
     * 整条全自动流水线就此卡死。逐个核对 WorkManager，已终结的直接收尸为 FAILED。
     */
    suspend fun sweepZombieJobs() {
        val zombies = generationRepository.findJobsWithStatuses(
            listOf(GenerationJobStatus.QUEUED.name, GenerationJobStatus.RUNNING.name)
        ).filter { now() - it.createdAt > 90_000L }   // 给刚建还没 enqueue 的任务留窗口
        if (zombies.isEmpty()) return
        for (job in zombies) {
            val states = runCatching {
                withContext(Dispatchers.IO) {
                    workManager.getWorkInfosForUniqueWork(uniqueWorkName(job.id)).get()
                }
            }.getOrNull()
            // 只要还有任何未终结的 WorkSpec（含 BLOCKED 等网络）就是活任务，不能收尸；
            // states 为空（工作链已完结/被取消）才算僵尸
            val alive = states?.any { !it.state.isFinished } == true
            if (alive) continue
            // 收尸前重读：状态已变（刚被 worker 更新/已完成）就不动它
            val fresh = generationRepository.findById(job.id) ?: continue
            if (fresh.status != GenerationJobStatus.QUEUED && fresh.status != GenerationJobStatus.RUNNING) continue
            // 有检查点内容的正文/大纲任务收尸为 RECOVERABLE_PARTIAL 会被重试覆盖旧流，
            // 统一记 FAILED：UI 出现「重试」，重试路径自带 checkpoint 回填
            generationRepository.updateJob(
                fresh.copy(
                    status = GenerationJobStatus.FAILED,
                    errorType = "ZombieJob",
                    errorMessage = "生成进程曾被中断（应用被关闭或重启），任务已停止。点击「重试」即可继续。",
                    updatedAt = now()
                )
            )
        }
    }

    /**
     * 一键全自动：打开开关并从当前进度直接开跑。
     * 全新项目 → 生成本批大纲；大纲已就绪 → 从第一个没正文的章节续写；
     * 本批正文写完 → 自动出下一批。失败时停下等用户处理。
     */
    suspend fun startAutoRun(projectId: String) {
        setAutoRun(projectId, true)
        driveAutoNext(projectId)
    }

    /**
     * 全自动流转的调度中枢。在每一章正文/每一批大纲成功落库后调用：
     * - 本轮还有未写正文的章节 → 续写下一章
     * - 本轮 10 章全部写完且全书未完 → 生下一批大纲（R+1）
     * - 全书完成 → 停止
     */
    private suspend fun driveAutoNext(projectId: String): Unit = autoNextLock(projectId).withLock {
        if (!autoRunStore.observe(projectId).first()) return
        val project = projectRepository.getProject(projectId) ?: return
        val total = project.creativeConfig?.chapterCount?.coerceIn(MIN_CHAPTER_COUNT, MAX_CHAPTER_COUNT) ?: return
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
        // 一条 DISTINCT 查询拿到全部已写章节，替代逐章 latest()（335 章时是 335 次全文读取）
        val writtenIds = chapterRepository.observeWrittenItemIds(projectId).first().toSet()
        val nextChapter = chapters.firstOrNull { chapter ->
            chapter.id !in writtenIds
        }
        if (nextChapter != null) {
            // 同一章失败自动重试的次数上限：3 次失败任务则显式停下等用户处理
            val attempts = generationRepository.countJobs(
                projectId, GenerationPurpose.CHAPTER.name, nextChapter.id
            )
            if (attempts >= MAX_AUTO_ATTEMPTS_PER_CHAPTER) {
                // 不再静默停摆：把最新失败任务升级为“需要处理”，章节页会出现重试按钮和说明
                val stalled = generationRepository.findLatestJob(
                    projectId, GenerationPurpose.CHAPTER.name, nextChapter.id
                )
                if (stalled != null && stalled.status != GenerationJobStatus.NEEDS_USER &&
                    stalled.status != GenerationJobStatus.QUEUED &&
                    stalled.status != GenerationJobStatus.RUNNING) {
                    generationRepository.updateJob(
                        stalled.copy(
                            status = GenerationJobStatus.NEEDS_USER,
                            errorMessage = "这一章已连续失败 $attempts 次，自动续写已停止。" +
                                "请在章节页点「重试这一章」单独再试；若总是报空内容，多半是思考过程耗尽了输出额度，" +
                                "请在模型设置中开启「关闭思考模式」或提高输出预算。",
                            updatedAt = now()
                        )
                    )
                }
                return
            }
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
            val memory = MemorySelector.select(
                project.continuityState,
                inputBudget = project.creativeConfig?.inputBudget ?: 8_000,
                chapterHint = chapterMemoryHint(
                    nextChapter.title,
                    nextChapter.summary,
                    nextChapter.characterChanges
                )
            )
            val context = ChapterContext(
                continuityState = memory.continuity,
                characters = memory.characters,
                previousSummary = previous?.summary,
                previousTail = previous?.content?.takeLast(1_500)
            )
            queueChapter(
                projectId = projectId,
                chapter = nextChapter,
                context = context,
                clientRequestId = "auto-${projectId}-${nextChapter.id}-${System.currentTimeMillis()}"
            )
        } else if ((chapters.maxOfOrNull { it.orderIndex } ?: -1) + 1 < total) {
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
        if (!continueFromExisting) {
            // 全新重生成：旧正文的 id 空间(chapter-N)会被新批次完整复用，
            // 不清掉就是"新目录配旧稿"的静默串书事故（用户已在确认框知情同意）
            //
            // 顺序很关键：必须先把在跑的工作停掉。只删库里的行是不够的 ——
            // 飞行中的 worker 结束时 updateJob 是 REPLACE，作用在已删除的行上
            // 等于 INSERT，会把任务复活成 COMPLETED，并把旧正文挂回新目录，
            // 于是新大纲配旧稿，还凭空多出一章「已写正文」。
            generationRepository.findJobsWithStatuses(
                listOf(
                    GenerationJobStatus.QUEUED.name,
                    GenerationJobStatus.RUNNING.name,
                    GenerationJobStatus.PAUSED.name,
                    GenerationJobStatus.RECOVERABLE_PARTIAL.name
                )
            ).filter { it.projectId == projectId }
                .forEach { cancel(it.id) }
            generationArtifactRepository.wipeChapterArtifacts(projectId)
        }
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

    /**
     * 历史回填：早期版本没带 usage 的调用 token 全是 NULL，账本严重低估。
     * 正文按已存正文长度估算、大纲按提示词快照估算、其余按时长粗估；幂等。
     */
    suspend fun backfillUsageEstimates() {
        val jobsById = generationRepository.findJobsWithStatuses(
            listOf(
                GenerationJobStatus.COMPLETED.name,
                GenerationJobStatus.RECOVERABLE_PARTIAL.name,
                GenerationJobStatus.FAILED.name,
                GenerationJobStatus.NEEDS_USER.name,
                GenerationJobStatus.PAUSED.name,
                GenerationJobStatus.CANCELLED.name
            )
        ).associateBy { it.id }
        llmCallRepository.backfillEstimates { jobId, purpose, durationMs ->
            when (purpose) {
                "CHAPTER" -> {
                    val chars = jobsById[jobId]?.promptSnapshotId
                        ?.let { chapterRepository.contentLengthForSnapshot(it) }
                    2_500L to ((chars ?: 0) * 1.5).toLong()
                }
                "OUTLINE" -> {
                    val job = jobsById[jobId]
                    val snapshot = job?.promptSnapshotId?.let { promptSnapshotRepository.findById(it) }
                    val inChars = snapshot?.messagesJson?.length ?: 6_000
                    val outChars = (job?.partialContent?.length ?: 0).coerceAtMost(60_000) / 3
                    (inChars * 1.5).toLong() to (outChars * 1.5).toLong()
                }
                else -> 1_500L to ((durationMs ?: 0L) / 100L).coerceIn(0L, 4_000L)
            }
        }
    }

    /** 前台通知的一句话进度："正在写《xx》正文" / "正在生成下一批大纲" */
    override suspend fun describeJob(jobId: String): String? {
        val job = generationRepository.findById(jobId) ?: return null
        return when (job.purpose) {
            GenerationPurpose.CHAPTER -> {
                val targetId = job.targetId ?: return "正在生成本章正文"
                val chapter = outlineRepository.latest(job.projectId)?.chapters
                    ?.firstOrNull { it.id == targetId }
                // 「正在生成」而不是「正在写」：同一函数的上一行和下一行都写
                // 「正在生成本章正文」，一件事两种说法。也不要写成
                // 「正在写 ${chapterLabel(...)}」—— chapterLabel 返回的
                // 「第 3 章」本身没有前导空格，那样会渲染成「正在写 第 3 章」。
                chapter?.let {
                    "正在生成${com.novelforge.app.presentation.common.chapterLabel(it.orderIndex)}" +
                        "《${it.title}》的正文"
                }
                    ?: "正在生成本章正文"
            }
            GenerationPurpose.OUTLINE -> "正在生成下一批大纲"
            else -> null
        }
    }

    /**
     * 魔法棒：以作者手改过的概要为准，结合前后章与设定润色本章大纲。
     * 一次性调用，不进任务管线：失败只回报错误，不产生任何持久状态。
     */
    suspend fun optimizeOutlineDraft(
        project: Project,
        current: OutlineItem,
        previous: OutlineItem?,
        next: OutlineItem?
    ): OutlineItem {
        val settings = settingsStore.settings.first()
        val creative = requireNotNull(project.creativeConfig) { "请先完成创作设置" }
        // 强制关思考：润色任务要的是快和稳，思考模型会把大量 token 烧在 reasoning 上
        val config = connection(settings).copy(
            capabilities = ProviderCapabilities(
                supportsStreaming = false,
                supportsJsonObject = false,
                supportsUsageInStream = false
            ),
            disableThinking = true
        )
        val response = llmClient.chat(
            ChatRequest(
                messages = listOf(
                    ChatMessage(
                        ChatRole.SYSTEM,
                        "你是网络小说大纲编辑，负责把作者手写的章节走向润色成完整可执行的章节大纲。" +
                            "标题和概要都只写内容本身，不要带\"第X章\"\"引子\"等编号前缀——编号由应用统一生成。" +
                            "只输出一个 JSON 对象，不要解释、不要代码围栏。"
                    ),
                    ChatMessage(
                        ChatRole.USER,
                        """
                        《${project.title}》「${com.novelforge.app.presentation.common.chapterLabel(current.orderIndex)}」的大纲刚被作者手动改写，这个方向是作者定的剧情走向，必须严格遵守，禁止改回原走向或引入新的重大事件。
                        你的任务：把它润色成完整章节大纲——写清本章故事如何发展、冲突如何变化、人物或局势发生什么变化、为下一章留下什么方向；与前后章自然衔接。只润色，不写正文。

                        【本章标题】${current.title}
                        【本章概要（作者手改，剧情以此为准）】${current.summary}
                        【上一章】${previous?.let { "《${it.title}》：${it.summary}" } ?: "无（本章是开篇）"}
                        【下一章】${next?.let { "《${it.title}》：${it.summary}" } ?: "暂无（本章是目前最后一章，自由留方向）"}
                        【创作设定】题材标签：${creative.genreTags.joinToString("、").ifBlank { "未指定" }}；每章正文目标 ${creative.targetLength} 字
                        【世界观资料】${json.encodeToString(project.questData.answers)}

                        只返回如下 JSON：{"title":"润色后的章节标题（不带任何编号前缀）","summary":"优化后的概要（纯内容，不带编号）"}
                        """.trimIndent()
                    )
                ),
                config = config,
                options = ChatOptions(
                    outputTokenBudget = 1_024,
                    requestId = "wand-${jobIdTag()}",
                    timeoutMs = 90_000L
                )
            )
        )
        val raw = stripInlineReasoning(response.content)
        JsonResponseValidator.extractJsonValue(raw)?.let { candidate ->
            when (val parsed = validator.parseOutline(candidate)) {
                is com.novelforge.app.infrastructure.llm.JsonValidationResult.Success -> {
                    parsed.value.firstOrNull()?.takeIf { it.summary.isNotBlank() }?.let { item ->
                        return current.copy(
                            title = item.title.trim().ifBlank { current.title },
                            summary = item.summary.trim()
                        )
                    }
                }
                is com.novelforge.app.infrastructure.llm.JsonValidationResult.Failure -> Unit
            }
        }
        // 模型没给 JSON 但给了像样的文字：直接当新概要用，不让一次好润色白费
        val plain = raw.trim()
        if (plain.length >= 8 && !plain.startsWith("{") && !plain.startsWith("[")) {
            return current.copy(summary = plain)
        }
        error("模型没有返回可用的润色结果，请重试")
    }


    /**
     * 用户手术「从此章重生成」：删除本章及之后的全部大纲与已写正文，
     * 把大纲任务检查点回拨到截断前缀（PAUSED），之后走正常的继续生成流程。
     * 硬删是故意的：保留旧正文会让重生成的同号章节直接复用写烂的内容。
     */
    suspend fun regenerateFromChapter(projectId: String, outlineItemId: String) {
        val outline = outlineRepository.latest(projectId) ?: return
        val ordered = outline.chapters.sortedBy { it.orderIndex }
        val cut = ordered.indexOfFirst { it.id == outlineItemId }
        require(cut >= 0) { "章节已变动，请返回大纲页刷新后重试" }
        val doomed = ordered.drop(cut)
        val doomedIds = doomed.map { it.id }

        generationRepository.findActiveJob(projectId, GenerationPurpose.OUTLINE.name, null)
            ?.let { cancel(it.id) }
        doomedIds.forEach { id ->
            generationRepository.findActiveJob(projectId, GenerationPurpose.CHAPTER.name, id)
                ?.let { cancel(it.id) }
        }


        val prefix = ordered.take(cut)
        val truncated = outline.copy(
            id = UUID.randomUUID().toString(),
            version = outline.version + 1,
            chapters = prefix,
            diffSummary = "用户手术：从 ${com.novelforge.app.presentation.common.chapterLabel(doomed.first().orderIndex)} 起重写",
            createdAt = now()
        )
        val project = requireNotNull(projectRepository.getProject(projectId)) { "项目不存在" }
        // 单事务：删正文/删任务/落新版本一起提交，中途被杀不会留下"正文没了目录还全"的裂状态
        generationArtifactRepository.saveOutlineAndPrune(
            truncated,
            project.copy(flowState = FlowState.OUTLINE_EDIT, updatedAt = now()),
            pruneOutlineItemIds = doomedIds,
            pruneJobTargetIds = doomedIds
        )
        generationRepository.findLatestJob(projectId, GenerationPurpose.OUTLINE.name, null)?.let { job ->
            generationRepository.updateJob(
                job.copy(
                    status = GenerationJobStatus.PAUSED,
                    partialContent = if (prefix.isEmpty()) {
                        ""
                    } else {
                        json.encodeToString(OutlineEnvelope(prefix))
                    },
                    errorType = null,
                    errorMessage = null,
                    updatedAt = now()
                )
            )
        }
    }

    private fun jobIdTag(): String = UUID.randomUUID().toString().take(8)

    suspend fun cancel(jobId: String) {
        generationRepository.findById(jobId)?.let { job ->
            generationRepository.updateJob(
                job.copy(status = GenerationJobStatus.CANCELLED, updatedAt = now())
            )
        }
        workManager.cancelUniqueWork(uniqueWorkName(jobId))
    }

    /** 从服务端 GET /models 拉可用模型列表（OpenAI 兼容约定），按字母序返回 */
    suspend fun fetchModels(): List<String> {
        val settings = settingsStore.settings.first()
        val base = settings.baseUrl.trim().trimEnd('/')
        require(base.isNotBlank()) { "请先填写 Base URL" }
        val key = apiKeyStore.read()?.takeIf { it.isNotBlank() } ?: error("请先填写 API Key")
        val url = java.net.URL("$base/models")
        if (!url.protocol.equals("https", ignoreCase = true) && !url.protocol.equals("http", ignoreCase = true)) {
            error("Base URL 协议不支持")
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Authorization", "Bearer $key")
                try {
                    val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                    val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (conn.responseCode !in 200..299) {
                        error("服务端返回 ${conn.responseCode}，该服务可能不支持模型列表接口")
                    }
                    json.parseToJsonElement(body).let { root ->
                        val data = root.jsonObject["data"]?.jsonArray ?: error("返回里没有 data 字段")
                        data.mapNotNull { element ->
                            element.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                        }.sortedBy { it.lowercase() }
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrElse { throw if (it is IllegalArgumentException) it else IllegalStateException(userReason(it), it) }
        }
    }

    private fun userReason(error: Throwable): String = when {
        error is java.net.SocketTimeoutException -> "拉取超时，请检查网络或稍后再试"
        error is java.io.IOException -> "网络不可达：${error.message ?: "未知错误"}"
        else -> error.message ?: "拉取模型列表失败"
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
                        estimated = event.estimated,
                        cachedInputTokens = event.cachedInputTokens,
                        reasoningTokens = event.reasoningTokens
                    )
                    else -> Unit
                }
            }
            val finalJob = requireNotNull(generationRepository.findById(prepared.job.id))
            if (usage.inputTokens == null && usage.outputTokens == null) {
                // 服务端流里没带 usage：按字符量估算（中文约 1.5 token/字），
                // 否则账本会把真实消耗记成 0，越用越不准
                val promptChars = prepared.request?.messages?.sumOf { it.content.length } ?: 0
                val prefixLen = prepared.request?.options?.checkpointPrefix?.length ?: 0
                val outChars = (finalJob.partialContent.length - prefixLen).coerceAtLeast(0)
                usage = LlmUsage(
                    inputTokens = (promptChars * 1.5).toLong(),
                    outputTokens = (outChars.coerceAtLeast(0) * 1.5).toLong(),
                    totalTokens = null,
                    estimated = true
                )
            }
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
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            try {
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
                    errorMessage = "大纲批次（从 ${com.novelforge.app.presentation.common.chapterLabel(
                        readOutlineProgress(
                            currentJob.partialContent,
                            projectRepository.getProject(currentJob.projectId)
                                ?.creativeConfig?.chapterCount
                                ?.coerceIn(MIN_CHAPTER_COUNT, MAX_CHAPTER_COUNT)
                                ?: MAX_CHAPTER_COUNT
                        ).currentChapterNumber - 1
                    )} 起）请求失败，可重试继续生成",
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
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
        val totalChapterCount = creativeConfig.chapterCount.coerceIn(MIN_CHAPTER_COUNT, MAX_CHAPTER_COUNT)
        val progress = readOutlineProgress(job.partialContent, totalChapterCount)
        if (progress.nextOrderIndex >= totalChapterCount) {
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
                nextOrderIndex = 0,
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
                nextOrderIndex = 0,
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
                    nextOrderIndex = 0,
                    currentChapterNumber = 1
                )

            is com.novelforge.app.infrastructure.llm.JsonValidationResult.Success -> {
                val start = raw.indexOf(normalized)
                val suffix = if (start >= 0) {
                    raw.substring(start + normalized.length).trim()
                } else {
                    ""
                }
                // 不按位置重编 orderIndex/id：检查点里存的就是权威序号，
                // 重编会抹平删章留洞，让新批次 "chapter-N" 与既有章节撞号串章
                val chapters = parsed.value
                val nextOrderIndex = (chapters.maxOfOrNull { it.orderIndex }?.plus(1)) ?: 0
                OutlineProgress(
                    chapters = chapters,
                    canonicalContent = json.encodeToString(OutlineEnvelope(chapters)),
                    hasPendingOutput = suffix.isNotBlank(),
                    totalChapterCount = totalChapterCount,
                    nextOrderIndex = nextOrderIndex,
                    currentChapterNumber = (nextOrderIndex + 1).coerceAtMost(totalChapterCount)
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
        } else {
            driveAutoNext(completedJob.projectId)
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
                        // 用「」而不是弯引号“”：全 App 的用户可见文案里，
                        // 引用界面上的按钮名一律用「」，只有这两行是例外。
                        errorMessage = "${com.novelforge.app.presentation.common.chapterLabel(progress.currentChapterNumber - 1)} 大纲未通过结构校验：${parsed.reason}。请点击「修复」手动修改，或点击「重试」重新生成",
                        updatedAt = now()
                    ),
                    call
                )
                GenerationExecutionResult.Success
            }

            is com.novelforge.app.infrastructure.llm.JsonValidationResult.Success -> {
                // 分批生成：一批可能返回多个推进段；按 nextOrderIndex（已有最大序号+1）续编，
                // 删章留洞也不会让新 id 与既有 "chapter-N" 撞号
                val start = progress.currentChapterNumber
                val remaining = (progress.totalChapterCount - progress.nextOrderIndex).coerceAtLeast(0)
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
                            // 「推进段」是提示词里的内部说法，界面上其他地方一律叫「章」
                            errorMessage = "本批大纲没有返回可用章节，请点「重试」或「修复」",
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
                if (progress.nextOrderIndex + newItems.size < progress.totalChapterCount) {
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
                errorMessage = "模型输出未通过结构校验：${artifactResult.reason ?: "未返回完整内容"}。请点击「修复」手动修改，或点击「重试」重新生成",
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
        if (artifactSaved && finalJob.purpose == GenerationPurpose.CHAPTER) {
            // 一章写完（含 length 截断但已落库的 RECOVERABLE_PARTIAL）→ 继续流转，
            // 否则全自动夜跑在这里静默停摆
            driveAutoNext(finalJob.projectId)
        } else if (!artifactSaved && finalJob.purpose == GenerationPurpose.CHAPTER) {
            // 全自动模式下正文失败自动重试（每章最多 3 次任务，超过交给用户处理）
            driveAutoNext(finalJob.projectId)
        }
        return GenerationExecutionResult.Success
    }

    private suspend fun savePromptSnapshot(queued: QueuedGeneration) {
        // 不能在这里按 id 短路。queueChapter 复用了上一轮失败任务时 job 带着旧的
        // promptSnapshotId，而 worker 发送的是快照里的 messagesJson —— 短路就等于
        // 把「改设定之前」的连续性状态、角色快照、前章片段再喂一遍模型，
        // 表现为「我明明把这个角色删了，他还在正文里」「改了设定不生效」。
        savePromptSnapshot(queued.job, queued.request)
    }

    private suspend fun savePromptSnapshot(job: GenerationJob, request: ChatRequest) {
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
                // parseOutline 已保留删章留洞的 orderIndex。这里再压成 0..N-1 会让
                // chapter-N 与既有章节撞号。序号重复时才兜底重排。
                val chapters = preserveOutlineIndexes(parsed.value)
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
                noteChapter(job.projectId, itemId, parsed.value.content)
                ArtifactPersistResult(saved = true)
            }
        }
    }

    /**
     * 正文已经落库之后才抽记忆。失败、没钥匙、格式不对都忽略，不能把已写好的章节打成失败。
     */
    private suspend fun noteChapter(projectId: String, chapterId: String, content: String) {
        try {
            val settings = settingsStore.settings.first()
            val key = apiKeyStore.read()?.takeIf { it.isNotBlank() } ?: return
            if (settings.baseUrl.isBlank() || settings.model.isBlank()) return
            val excerpt = excerptForExtraction(content)
            if (excerpt.isBlank()) return
            val startedAt = now()
            val response = llmClient.chat(
                ChatRequest(
                    messages = listOf(
                        ChatMessage(
                            ChatRole.SYSTEM,
                            "你从刚写完的小说章节里抽取会影响到后文的事实。只输出 JSON，不要解释。"
                        ),
                        ChatMessage(
                            ChatRole.USER,
                            """
                            阅读下面的章节节选，抽出后文必须记住的信息。每条不超过 40 字，没有就给空数组。
                            不要复述剧情过程，只留状态变化。

                            $excerpt

                            只返回：
                            {"facts":[{"statement":"人物或世界的新状态","subject":"这条讲的是谁（人名/地点/物件）","predicate":"讲的是他的什么（一个短名词，如 左臂状态/身份/持有物）"}],
                             "threads":[{"statement":"新埋下、还没收的伏笔","subject":"","predicate":""}],
                             "resolved":[{"statement":"这一章已经解决的旧线索","subject":"","predicate":""}]}

                            subject 和 predicate 很关键：同一个 subject + 同一个 predicate
                            的两条设定会被当成同一件事的新旧两个状态，后写的顶掉先写的。
                            不要凭空编 subject；确实是全局设定就留空字符串。
                            """.trimIndent()
                        )
                    ),
                    config = connection(settings).copy(
                        apiKey = key,
                        capabilities = ProviderCapabilities(
                            supportsStreaming = false,
                            supportsJsonObject = false,
                            supportsUsageInStream = false
                        ),
                        disableThinking = true
                    ),
                    options = ChatOptions(
                        outputTokenBudget = 512,
                        requestId = "memory-${jobIdTag()}",
                        timeoutMs = 60_000L
                    )
                )
            )
            val notes = parseMemoryNotes(response.content, now())
            // 抽记忆本身就是一次真实开销（输入是整章正文），必须入账。
            // 以前这里直接 return，账本每章都少算一次调用和约 6k 输入 token。
            recordMemoryExtractionCall(projectId, chapterId, settings, response, startedAt)
            if (notes.isEmpty()) return
            val stamped = notes.map {
                it.copy(
                    id = UUID.randomUUID().toString(),
                    sourceChapterId = chapterId
                )
            }
            // 事务内读-改-写：这里和用户在「本书记忆」点确认是并发的两条路径，
            // 先取一份 project 快照再整行 REPLACE 会把对方刚写的记忆抹掉。
            projectRepository.mutateContinuity(projectId) { state ->
                state.copy(pendingFacts = (state.pendingFacts + stamped).takeLast(MAX_PENDING_FACTS))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 记忆没抽出来不影响正文
        }
    }

    /**
     * 抽记忆的调用没有对应的 generation_jobs 行（它不产生正文），
     * 所以直接写一条合成 jobId 的账本记录，让「每一次调用的 Token 都记账」成立。
     */
    private suspend fun recordMemoryExtractionCall(
        projectId: String,
        chapterId: String,
        settings: AppSettings,
        response: LlmResponse,
        startedAt: Long
    ) {
        runCatching {
            llmCallRepository.save(
                LlmCall(
                    id = UUID.randomUUID().toString(),
                    projectId = projectId,
                    jobId = "memory-notes-$chapterId-${jobIdTag()}",
                    purpose = GenerationPurpose.MEMORY_NOTES,
                    provider = settings.providerName.ifBlank { "OpenAI-compatible" },
                    model = settings.model,
                    usage = response.usage,
                    durationMs = (now() - startedAt).coerceAtLeast(0L),
                    success = true,
                    createdAt = now()
                )
            )
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
        // 限流/截断在 OpenAI 兼容网关上是协议层错误而不是 HTTP 错误：
        // 提前中断、空内容、HTTP 200 里带 error 体，全走 ProviderProtocolException，
        // 而它自己的提示语就写着「多为服务端限流，请稍后重试」。归到不可重试等于
        // 第一次抖动就让整夜的任务直接 FAILED，退避配置一次都用不上。
        is ProviderProtocolException -> true
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
        private const val MAX_AUTO_ATTEMPTS_PER_CHAPTER = 3
        private const val PREVIOUS_CONTEXT_LIMIT = 3
        /** 待确认记忆的存量上限。UI 必须能看全这么多条，否则超出的部分用户永远处理不掉。 */
        const val MAX_PENDING_FACTS = 40
        private val RETRYABLE_HTTP_CODES = setOf(408, 425, 429, 500, 502, 503, 504)
    }
}
