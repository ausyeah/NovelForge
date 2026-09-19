package com.novelforge.app.data.local

import com.novelforge.app.domain.model.CreativeConfig
import com.novelforge.app.domain.model.ChapterRevision
import com.novelforge.app.domain.model.ChapterStatus
import com.novelforge.app.domain.model.FlowState
import com.novelforge.app.domain.model.GenerationJob
import com.novelforge.app.domain.model.GenerationJobStatus
import com.novelforge.app.domain.model.GenerationPurpose
import com.novelforge.app.domain.model.Project
import com.novelforge.app.domain.model.ProjectStatus
import com.novelforge.app.domain.model.QuestData
import com.novelforge.app.domain.model.ContinuityState
import com.novelforge.app.domain.model.LlmCall
import com.novelforge.app.domain.model.LlmUsage
import com.novelforge.app.domain.model.OutlineItem
import com.novelforge.app.domain.model.OutlineVersion
import com.novelforge.app.domain.model.PromptSnapshot
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val mapperJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun ProjectEntity.toDomain(): Project = Project(
    id = id,
    title = title,
    questionnaireSchemaVersion = questionnaireSchemaVersion,
    flowState = FlowState.valueOf(flowState),
    questData = mapperJson.decodeFromString<QuestData>(questDataJson),
    creativeConfig = creativeConfigJson?.let { mapperJson.decodeFromString<CreativeConfig>(it) },
    continuityState = mapperJson.decodeFromString<ContinuityState>(continuityStateJson),
    activeOutlineVersionId = activeOutlineVersionId,
    status = ProjectStatus.valueOf(status),
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Project.toEntity(): ProjectEntity = ProjectEntity(
    id = id,
    title = title,
    questionnaireSchemaVersion = questionnaireSchemaVersion,
    flowState = flowState.name,
    questDataJson = mapperJson.encodeToString(questData),
    creativeConfigJson = creativeConfig?.let { mapperJson.encodeToString(it) },
    continuityStateJson = mapperJson.encodeToString(continuityState),
    activeOutlineVersionId = activeOutlineVersionId,
    status = status.name,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun GenerationJobEntity.toDomain(): GenerationJob = GenerationJob(
    id = id,
    projectId = projectId,
    targetId = targetId,
    purpose = GenerationPurpose.valueOf(purpose),
    status = GenerationJobStatus.valueOf(status),
    clientRequestId = clientRequestId,
    attempt = attempt,
    partialContent = partialContent,
    promptSnapshotId = promptSnapshotId,
    lastCheckpointAt = lastCheckpointAt,
    errorType = errorType,
    errorMessage = errorMessage,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun GenerationJob.toEntity(): GenerationJobEntity = GenerationJobEntity(
    id = id,
    projectId = projectId,
    targetId = targetId,
    purpose = purpose.name,
    status = status.name,
    clientRequestId = clientRequestId,
    attempt = attempt,
    partialContent = partialContent,
    promptSnapshotId = promptSnapshotId,
    lastCheckpointAt = lastCheckpointAt,
    errorType = errorType,
    errorMessage = errorMessage,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun OutlineVersionEntity.toDomain(): OutlineVersion = OutlineVersion(
    id = id,
    projectId = projectId,
    version = version,
    chapters = mapperJson.decodeFromString<List<OutlineItem>>(chaptersJson),
    diffSummary = diffSummary,
    createdAt = createdAt
)

fun OutlineVersion.toEntity(): OutlineVersionEntity = OutlineVersionEntity(
    id = id,
    projectId = projectId,
    version = version,
    chaptersJson = mapperJson.encodeToString(chapters),
    diffSummary = diffSummary,
    createdAt = createdAt
)

fun ChapterRevisionEntity.toDomain(): ChapterRevision = ChapterRevision(
    id = id,
    projectId = projectId,
    outlineItemId = outlineItemId,
    outlineVersionId = outlineVersionId,
    revision = revision,
    title = title,
    content = content,
    summary = summary,
    status = ChapterStatus.valueOf(status),
    promptSnapshotId = promptSnapshotId,
    createdAt = createdAt
)

fun ChapterRevision.toEntity(): ChapterRevisionEntity = ChapterRevisionEntity(
    id = id,
    projectId = projectId,
    outlineItemId = outlineItemId,
    outlineVersionId = outlineVersionId,
    revision = revision,
    title = title,
    content = content,
    summary = summary,
    status = status.name,
    promptSnapshotId = promptSnapshotId,
    createdAt = createdAt
)

fun PromptSnapshotEntity.toDomain(): PromptSnapshot = PromptSnapshot(
    id = id,
    systemPrompt = systemPrompt,
    messagesJson = messagesJson,
    model = model,
    temperature = temperature,
    createdAt = createdAt
)

fun PromptSnapshot.toEntity(): PromptSnapshotEntity = PromptSnapshotEntity(
    id = id,
    systemPrompt = systemPrompt,
    messagesJson = messagesJson,
    model = model,
    temperature = temperature,
    createdAt = createdAt
)

fun LlmCallEntity.toDomain(): LlmCall = LlmCall(
    id = id,
    projectId = projectId,
    jobId = jobId,
    purpose = GenerationPurpose.valueOf(purpose),
    provider = provider,
    model = model,
    usage = LlmUsage(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        totalTokens = totalTokens,
        estimated = estimated
    ),
    durationMs = durationMs,
    success = success,
    createdAt = createdAt
)

fun LlmCall.toEntity(): LlmCallEntity = LlmCallEntity(
    id = id,
    projectId = projectId,
    jobId = jobId,
    purpose = purpose.name,
    provider = provider,
    model = model,
    inputTokens = usage.inputTokens,
    outputTokens = usage.outputTokens,
    totalTokens = usage.totalTokens,
    estimated = usage.estimated,
    durationMs = durationMs,
    success = success,
    createdAt = createdAt
)
