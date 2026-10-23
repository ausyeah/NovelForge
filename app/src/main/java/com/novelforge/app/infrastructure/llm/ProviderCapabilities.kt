package com.novelforge.app.infrastructure.llm

enum class AuthStyle {
    BEARER,
    API_KEY_HEADER,
    CUSTOM
}

enum class ParameterStyle {
    MAX_TOKENS,
    MAX_COMPLETION_TOKENS
}

data class ProviderCapabilities(
    val supportsStreaming: Boolean = true,
    val supportsJsonObject: Boolean = false,
    val supportsJsonSchema: Boolean = false,
    val supportsSystemMessage: Boolean = true,
    val supportsUsageInStream: Boolean = false,
    val authStyle: AuthStyle = AuthStyle.BEARER,
    val parameterStyle: ParameterStyle = ParameterStyle.MAX_TOKENS,
    val chatCompletionsPath: String = "/chat/completions"
)

data class ModelPreset(
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val contextLimit: Int,
    val inputBudget: Int,
    val outputBudget: Int,
    val safetyMargin: Int,
    val capabilities: ProviderCapabilities
)

data class ContextBudget(
    val inputBudget: Int,
    val outputBudget: Int,
    val safetyMargin: Int
)

enum class ResponseFormatKind {
    NONE,
    JSON_OBJECT,
    JSON_SCHEMA
}

data class ResponseFormat(
    val kind: ResponseFormatKind = ResponseFormatKind.NONE,
    val schemaJson: String? = null
)
