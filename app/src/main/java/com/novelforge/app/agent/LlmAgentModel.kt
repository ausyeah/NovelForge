package com.novelforge.app.agent

import com.novelforge.app.data.security.ApiKeyStore
import com.novelforge.app.data.settings.AppSettingsStore
import com.novelforge.app.infrastructure.llm.ChatMessage
import com.novelforge.app.infrastructure.llm.ChatOptions
import com.novelforge.app.infrastructure.llm.ChatRequest
import com.novelforge.app.infrastructure.llm.ChatRole
import com.novelforge.app.infrastructure.llm.LLMClient
import com.novelforge.app.infrastructure.llm.LLMConnectionConfig
import com.novelforge.app.infrastructure.llm.OpenAiCompatibleClient
import com.novelforge.app.infrastructure.llm.ProviderCapabilities
import kotlinx.coroutines.flow.first

class LlmAgentModel(
    private val settingsStore: AppSettingsStore,
    private val apiKeyStore: ApiKeyStore,
    private val client: LLMClient = OpenAiCompatibleClient()
) {
    suspend fun answerAboutBook(context: String, question: String): String {
        val settings = settingsStore.settings.first()
        val key = apiKeyStore.read()?.takeIf { it.isNotBlank() }
            ?: return "请先在模型设置里保存 API Key。"
        if (settings.baseUrl.isBlank() || settings.model.isBlank()) {
            return "请先填写 Base URL 和模型名。"
        }
        val response = client.chat(
            ChatRequest(
                messages = listOf(
                    ChatMessage(
                        ChatRole.SYSTEM,
                        "你在回答关于这一本书的问题。材料只有大纲和相关片段，不是全文。" +
                            "没写到的就说不确定，不要把片段扩写成整章。"
                    ),
                    ChatMessage(ChatRole.USER, "$context\n\n问题：$question")
                ),
                config = LLMConnectionConfig(
                    baseUrl = settings.baseUrl.trim(),
                    apiKey = key,
                    model = settings.model.trim(),
                    capabilities = ProviderCapabilities(
                        supportsStreaming = false,
                        supportsJsonObject = false,
                        supportsUsageInStream = false
                    ),
                    disableThinking = true
                ),
                options = ChatOptions(
                    outputTokenBudget = 2_048,
                    requestId = "ask-${System.currentTimeMillis()}",
                    timeoutMs = 60_000L
                )
            )
        )
        return response.content.trim().ifBlank { "没有返回内容。" }
    }
}
