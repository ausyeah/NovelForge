package com.novelforge.app.infrastructure.llm

import com.novelforge.app.domain.model.LlmUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
enum class ChatRole(val wireValue: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant")
}

@Serializable
data class ChatMessage(
    val role: ChatRole,
    val content: String
)

data class ChatOptions(
    val temperature: Float? = null,
    val outputTokenBudget: Int,
    val responseFormat: ResponseFormat = ResponseFormat(),
    val stream: Boolean = false,
    val requestId: String,
    val timeoutMs: Long? = null,
    /**
     * Local-only text kept in front of a structured streaming response.
     * It is never sent to the provider; it protects durable checkpoints when
     * a request is interrupted after earlier chapters were already saved.
     */
    val checkpointPrefix: String = ""
)

data class LLMConnectionConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val capabilities: ProviderCapabilities,
    /** 请求附带 enable_thinking=false / thinking.type=disabled，尝试让服务端关闭思考模式 */
    val disableThinking: Boolean = false
)

data class ChatRequest(
    val messages: List<ChatMessage>,
    val config: LLMConnectionConfig,
    val options: ChatOptions
)

data class LlmResponse(
    val content: String,
    val usage: LlmUsage,
    val finishReason: String?,
    val requestId: String
)

sealed interface StreamEvent {
    data class Delta(val text: String) : StreamEvent
    data class Usage(val usage: LlmUsage) : StreamEvent
    data class Finished(val finishReason: String?) : StreamEvent
}

interface LLMClient {
    suspend fun chat(request: ChatRequest): LlmResponse
    fun streamChat(request: ChatRequest): Flow<StreamEvent>
}

class ProviderHttpException(
    val statusCode: Int,
    val retryAfterSeconds: Long? = null
) : RuntimeException("LLM provider returned HTTP $statusCode")

class ProviderProtocolException(message: String) : RuntimeException(message)
