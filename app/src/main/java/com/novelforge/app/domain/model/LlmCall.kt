package com.novelforge.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class PromptSnapshot(
    val id: String,
    val systemPrompt: String,
    val messagesJson: String,
    val model: String,
    val temperature: Float?,
    val createdAt: Long
)

@Serializable
data class LlmUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val estimated: Boolean = false,
    /** 命中缓存的输入 token（计费按折扣价，单列出来才能算净输入） */
    val cachedInputTokens: Long? = null,
    /** 思考/推理链消耗的 output token（含在 outputTokens 里） */
    val reasoningTokens: Long? = null
)

@Serializable
data class LlmCall(
    val id: String,
    val projectId: String,
    val jobId: String,
    val purpose: GenerationPurpose,
    val provider: String,
    val model: String,
    val usage: LlmUsage = LlmUsage(),
    val durationMs: Long? = null,
    val success: Boolean,
    val createdAt: Long
)
