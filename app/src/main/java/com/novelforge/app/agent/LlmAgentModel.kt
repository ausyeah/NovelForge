package com.novelforge.app.agent

import com.novelforge.app.data.security.ApiKeyStore
import com.novelforge.app.data.settings.AppSettingsStore
import com.novelforge.app.infrastructure.llm.ChatMessage
import com.novelforge.app.infrastructure.llm.ChatOptions
import com.novelforge.app.infrastructure.llm.ChatRequest
import com.novelforge.app.infrastructure.llm.ChatRole
import com.novelforge.app.infrastructure.llm.JsonResponseValidator
import com.novelforge.app.infrastructure.llm.LLMClient
import com.novelforge.app.infrastructure.llm.LLMConnectionConfig
import com.novelforge.app.infrastructure.llm.OpenAiCompatibleClient
import com.novelforge.app.infrastructure.llm.ProviderCapabilities
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LlmAgentModel(
    private val settingsStore: AppSettingsStore,
    private val apiKeyStore: ApiKeyStore,
    private val client: LLMClient = OpenAiCompatibleClient()
) : AgentModel {
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

    override suspend fun decide(request: String, steps: List<AgentStep>, specs: List<ToolSpec>): AgentDecision {
        val settings = settingsStore.settings.first()
        val key = apiKeyStore.read()?.takeIf { it.isNotBlank() } ?: return AgentDecision.Reply("请先在模型设置里保存 API Key。")
        if (settings.baseUrl.isBlank() || settings.model.isBlank()) {
            return AgentDecision.Reply("请先填写 Base URL 和模型名。")
        }
        val toolList = specs.joinToString("\n") { "- ${it.name}（${it.argumentsHint}）：${it.description}" }
        val history = steps.takeLast(8).joinToString("\n") { "${it.title}：${it.detail.take(300)}" }
        val response = client.chat(
            ChatRequest(
                messages = listOf(
                    ChatMessage(
                        ChatRole.SYSTEM,
                        "你是这本小说的查书助手。只能通过工具读这本书，不能编造已经写过的情节。" +
                            "每一步只输出一个 JSON 对象，不要解释。" +
                            "调用工具：{\"action\":\"tool\",\"name\":\"工具名\",\"arguments\":{}}。" +
                            "可以回答时：{\"action\":\"reply\",\"text\":\"...\"}。"
                    ),
                    ChatMessage(
                        ChatRole.USER,
                        """
                        可用工具：
                        $toolList

                        已有轨迹：
                        ${history.ifBlank { "无" }}

                        用户要求：$request
                        """.trimIndent()
                    )
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
                    requestId = "agent-${System.currentTimeMillis()}",
                    timeoutMs = 60_000L
                )
            )
        )
        return parseAgentDecision(response.content)
    }
}

fun parseAgentDecision(raw: String): AgentDecision {
    val jsonText = JsonResponseValidator.extractJsonValue(raw) ?: return AgentDecision.Reply(raw.trim().ifBlank { "没有返回内容。" })
    val root = runCatching { Json.parseToJsonElement(jsonText).jsonObject }.getOrNull()
        ?: return AgentDecision.Reply(raw.trim())
    val action = root.text("action")
    if (action == "tool") {
        val name = root.text("name")
        if (name.isBlank()) return AgentDecision.Reply(raw.trim())
        val arguments = root["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        return AgentDecision.CallTool(name, arguments)
    }
    return AgentDecision.Reply(root.text("text").ifBlank { raw.trim() })
}

private fun JsonObject.text(key: String): String =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim().orEmpty()
