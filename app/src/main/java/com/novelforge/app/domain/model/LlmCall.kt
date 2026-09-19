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
    val estimated: Boolean = false
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
