package com.novelforge.app.infrastructure.jobs

import com.novelforge.app.domain.model.GenerationJob
import com.novelforge.app.domain.model.GenerationJobStatus
import com.novelforge.app.domain.repository.GenerationRepository
import com.novelforge.app.infrastructure.llm.ChatRequest
import com.novelforge.app.infrastructure.llm.LLMClient
import com.novelforge.app.infrastructure.llm.ResponseFormatKind
import com.novelforge.app.infrastructure.llm.StreamEvent
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed interface GenerationEvent {
    data class Status(val status: GenerationJobStatus) : GenerationEvent
    data class Delta(val text: String) : GenerationEvent
    data class Usage(
        val inputTokens: Long?,
        val outputTokens: Long?,
        val estimated: Boolean,
        val cachedInputTokens: Long? = null,
        val reasoningTokens: Long? = null
    ) : GenerationEvent
    data class Completed(val content: String, val finishReason: String?) : GenerationEvent

    /**
     * 任务行已经不在库里了：「全新重生成大纲」清库、用户手术删章、删项目。
     * 收尾必须就此打住 —— 既不能把行插回来，也不能再发 [Completed]，
     * 否则上游会把这一次的旧输出当成新结果落进新目录。
     */
    data object Superseded : GenerationEvent
}

class GenerationCoordinator(
    private val generationRepository: GenerationRepository,
    private val llmClient: LLMClient,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun createOrReuseJob(
        projectId: String,
        purpose: String,
        targetId: String?,
        promptSnapshotId: String,
        clientRequestId: String = UUID.randomUUID().toString()
    ): GenerationJob {
        generationRepository.findByClientRequestId(projectId, clientRequestId)?.let { return it }
        generationRepository.findActiveJob(projectId, purpose, targetId)?.let { return it }
        val timestamp = now()
        return GenerationJob(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            targetId = targetId,
            purpose = com.novelforge.app.domain.model.GenerationPurpose.valueOf(purpose),
            clientRequestId = clientRequestId,
            promptSnapshotId = promptSnapshotId,
            createdAt = timestamp,
            updatedAt = timestamp
        ).also { generationRepository.createJob(it) }
    }

    fun execute(jobId: String, request: ChatRequest): Flow<GenerationEvent> = flow {
        val initial = requireNotNull(generationRepository.findById(jobId)) { "生成任务不存在" }
        val running = initial.copy(
            status = GenerationJobStatus.RUNNING,
            attempt = initial.attempt + 1,
            // 每次开跑重置创建时间：UI 的“已等待”以本次启动计时，跨轮次不累计
            createdAt = now(),
            updatedAt = now()
        )
        // 必须是条件更新，不能用 upsert（REPLACE）。REPLACE 落在已删除的行上等于 INSERT：
        // 「全新重生成大纲」清掉的旧任务会被复活，worker 再把旧正文挂回新目录。
        // 行没了 = 这次生成已经被作废，直接放弃，别白烧一次 LLM 调用。
        if (!generationRepository.updateJobIfExists(running)) {
            emit(GenerationEvent.Superseded)
            return@flow
        }
        emit(GenerationEvent.Status(GenerationJobStatus.RUNNING))

        // Structured responses must be regenerated from the saved prompt when
        // a Worker restarts; appending a second JSON document to a partial one
        // would make both documents invalid. Plain-text providers can continue
        // from the checkpoint instead.
        val checkpointPrefix = request.options.checkpointPrefix
        val canResumeRawPartial = request.options.responseFormat.kind == ResponseFormatKind.NONE &&
            checkpointPrefix.isBlank()
        val content = if (canResumeRawPartial) {
            StringBuilder(initial.partialContent)
        } else {
            StringBuilder()
        }
        var finishReason: String? = null
        var lastCheckpointLength = content.length
        var lastCheckpointAt = running.lastCheckpointAt ?: running.updatedAt
        try {
            llmClient.streamChat(request).collect { event ->
                currentCoroutineContext().ensureActive()
                when (event) {
                    is StreamEvent.Delta -> {
                        content.append(event.text)
                        val currentTime = now()
                        val hasNewContent = content.length > lastCheckpointLength
                        // 纯时间基节流：checkpoint 是整行重写 + Room 表失效风暴，
                        // 400ms 一次在超长篇下把主线程压满；2 秒足够断点恢复用
                        val checkpointDue = hasNewContent &&
                            currentTime - lastCheckpointAt >= CHECKPOINT_INTERVAL_MS
                        if (checkpointDue) {
                            // A checkpoint is still live output. Keep it RUNNING;
                            // RECOVERABLE_PARTIAL is reserved for an interrupted
                            // or truncated request.
                            checkpoint(
                                running,
                                checkpointContent(checkpointPrefix, content.toString()),
                                GenerationJobStatus.RUNNING
                            )
                            lastCheckpointLength = content.length
                            lastCheckpointAt = currentTime
                        }
                    }
                    is StreamEvent.Usage -> emit(
                        GenerationEvent.Usage(
                            inputTokens = event.usage.inputTokens,
                            outputTokens = event.usage.outputTokens,
                            estimated = event.usage.estimated,
                            // 这两个字段解析了、落库了、界面上也画出来了，却在这里被丢掉，
                            // 于是「缓存命中」和「思考 token」两列永远是 0。必须透传。
                            cachedInputTokens = event.usage.cachedInputTokens,
                            reasoningTokens = event.usage.reasoningTokens
                        )
                    )
                    is StreamEvent.Finished -> finishReason = event.finishReason
                    // 思考事件仅 AI 助手使用；大纲/正文请求不会开启，忽略即可
                    is StreamEvent.Reasoning -> Unit
                }
            }
            if (generationRepository.findById(jobId)?.status == GenerationJobStatus.CANCELLED) {
                emit(GenerationEvent.Status(GenerationJobStatus.CANCELLED))
                return@flow
            }
            if (content.isBlank()) {
                // 走到这里说明 HTTP 层“成功”但一个字都没收到：
                // 多为网关限流返回空体，绝不能当完成的空结果处理
                throw com.novelforge.app.infrastructure.llm.ProviderProtocolException(
                    "服务端返回了空内容（可能被限流或中断），请稍后重试"
                )
            }
            val finalStatus = if (finishReason == "length") {
                GenerationJobStatus.RECOVERABLE_PARTIAL
            } else {
                GenerationJobStatus.COMPLETED
            }
            val completed = running.copy(
                status = finalStatus,
                partialContent = checkpointContent(checkpointPrefix, content.toString()),
                lastCheckpointAt = now(),
                updatedAt = now()
            )
            // 收尾同样必须落空。这里是「飞行中的 worker 写回已删除任务」的唯一出口：
            // 写不进去就绝不能发 Completed —— 上游只认 Completed 才落内容，
            // 发了就等于把旧大纲/旧正文当成新结果写进新目录。
            if (!generationRepository.updateJobIfExists(completed)) {
                emit(GenerationEvent.Superseded)
                return@flow
            }
            if (finalStatus == GenerationJobStatus.COMPLETED) {
                emit(GenerationEvent.Completed(content.toString(), finishReason))
            } else {
                emit(GenerationEvent.Status(finalStatus))
            }
        } catch (cancelled: CancellationException) {
            if (generationRepository.findById(jobId)?.status != GenerationJobStatus.CANCELLED) {
                checkpoint(
                    running,
                    checkpointContent(checkpointPrefix, content.toString()),
                    GenerationJobStatus.RECOVERABLE_PARTIAL
                )
            }
            throw cancelled
        } catch (error: Throwable) {
            val failed = running.copy(
                status = GenerationJobStatus.FAILED,
                partialContent = checkpointContent(checkpointPrefix, content.toString()),
                errorType = error::class.simpleName,
                errorMessage = error.message,
                lastCheckpointAt = now(),
                updatedAt = now()
            )
            generationRepository.updateJobIfNotCancelled(failed)
            throw error
        }
    }

    suspend fun cancel(jobId: String) {
        val job = generationRepository.getRequired(jobId) ?: return
        generationRepository.updateJob(
            job.copy(status = GenerationJobStatus.CANCELLED, updatedAt = now())
        )
    }

    private suspend fun checkpoint(
        job: GenerationJob,
        content: String,
        status: GenerationJobStatus = GenerationJobStatus.RECOVERABLE_PARTIAL
    ) {
        generationRepository.updateJobIfNotCancelled(
            job.copy(
                status = status,
                partialContent = content,
                lastCheckpointAt = now(),
                updatedAt = now()
            )
        )
    }

    private fun checkpointContent(prefix: String, current: String): String =
        if (prefix.isBlank()) current else prefix + current

    private suspend fun GenerationRepository.getRequired(id: String): GenerationJob? = findById(id)

    companion object {
        private const val CHECKPOINT_INTERVAL_MS = 2_000L
    }
}
