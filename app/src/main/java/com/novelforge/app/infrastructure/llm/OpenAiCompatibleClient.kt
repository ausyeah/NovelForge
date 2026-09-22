package com.novelforge.app.infrastructure.llm

import com.novelforge.app.domain.model.LlmUsage
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class OpenAiCompatibleClient(
    baseHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) : LLMClient {
    private val httpClient = baseHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun chat(request: ChatRequest): LlmResponse = withContext(Dispatchers.IO) {
        validateBaseUrl(request.config.baseUrl)
        executeChatWithRetry(request)
    }

    private suspend fun executeChatWithRetry(request: ChatRequest): LlmResponse {
        var retryCount = 0
        while (true) {
            try {
                return executeChat(request)
            } catch (error: ProviderHttpException) {
                if (!retryPolicy.shouldRetry(error.statusCode, retryCount)) throw error
                delay(retryPolicy.delayMs(retryCount, error.retryAfterSeconds))
                retryCount++
            } catch (error: IOException) {
                if (retryCount >= retryPolicy.maxRetries) throw error
                delay(retryPolicy.delayMs(retryCount))
                retryCount++
            }
        }
    }

    override fun streamChat(request: ChatRequest): Flow<StreamEvent> = channelFlow {
        validateBaseUrl(request.config.baseUrl)
        val includeReasoning = request.options.includeReasoning
        val call = httpClient.newCall(buildRequest(request.copy(options = request.options.copy(stream = true))))
        request.options.timeoutMs?.let { timeoutMs ->
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        }
        val readerJob = launch(Dispatchers.IO) {
            var response: Response? = null
            try {
                response = call.execute()
                if (!response.isSuccessful) throw httpException(response)
                val body = response.body ?: throw ProviderProtocolException("响应没有 body")
                var sawFinish = false
                body.source().use { source ->
                    val dataLines = mutableListOf<String>()
                    val rawBody = StringBuilder()
                    var emittedSseData = false
                    var streamFinished = false
                    while (!source.exhausted()) {
                        currentCoroutineContext().ensureActive()
                        val line = source.readUtf8Line() ?: break
                        rawBody.append(line).append('\n')
                        when {
                            line.isEmpty() -> {
                                if (dataLines.isNotEmpty()) {
                                    emittedSseData = true
                                    streamFinished = emitSseData(dataLines, includeReasoning) || streamFinished
                                    sawFinish = streamFinished
                                    dataLines.clear()
                                    if (streamFinished) break
                                }
                            }
                            line.startsWith("data:") -> {
                                val data = line.removePrefix("data:").trimStart()
                                dataLines += data
                                // OpenAI-compatible providers normally put one
                                // complete JSON object in each data line. Flush
                                // it immediately as well as on a blank line so
                                // proxies that omit the SSE separator do not
                                // leave the UI at zero characters until EOF.
                                if (data == "[DONE]" ||
                                    (dataLines.size == 1 && parseObjectOrNull(data) != null)
                                ) {
                                    emittedSseData = true
                                    streamFinished = emitSseData(dataLines, includeReasoning) || streamFinished
                                    sawFinish = streamFinished
                                    dataLines.clear()
                                    if (streamFinished) break
                                }
                            }
                            line.startsWith(":") -> Unit
                        }
                    }
                    if (dataLines.isNotEmpty()) {
                        emittedSseData = true
                        sawFinish = emitSseData(dataLines, includeReasoning) || sawFinish
                    }
                    // A few OpenAI-compatible gateways ignore stream=true and
                    // return one normal JSON response. Treat it as one delta
                    // instead of silently completing an empty generation.
                    if (!emittedSseData) {
                        val bodyText = rawBody.toString().trim()
                        if (bodyText.isNotEmpty()) {
                            val root = parseResponseObject(bodyText)
                            sawFinish = emitResponseObject(root, includeReasoning)
                        }
                    }
                    // 流式输出结束却从没收到 finish_reason：多半是服务端限流/异常
                    // 提前掐断了连接。绝不能把半截内容当成功结果。
                    if (emittedSseData && !sawFinish) {
                        throw ProviderProtocolException(
                            "流式响应被服务端提前中断（未收到结束标记），多为服务端限流，请稍后重试"
                        )
                    }
                }
                close()
            } catch (error: Throwable) {
                close(error)
            } finally {
                response?.close()
            }
        }
        awaitClose { call.cancel(); readerJob.cancel() }
    }

    private suspend fun kotlinx.coroutines.channels.ProducerScope<StreamEvent>.emitSseData(
        dataLines: List<String>,
        includeReasoning: Boolean
    ): Boolean {
        if (dataLines.isEmpty()) return false
        val data = dataLines.joinToString("\n")
        if (data == "[DONE]") return true
        val root = parseResponseObject(data)
        return emitResponseObject(root, includeReasoning)
    }

    private suspend fun kotlinx.coroutines.channels.ProducerScope<StreamEvent>.emitResponseObject(
        root: JsonObject,
        includeReasoning: Boolean
    ): Boolean {
        // 部分网关限流/出错时不返回 HTTP 错误码，而是 HTTP 200 + 响应体内嵌 error 对象，
        // 必须显式识别，否则会被当成“空响应”静默吞掉
        root["error"]?.let { error ->
            val message = (error as? JsonObject)?.get("message")?.let(::extractText) ?: "未知错误"
            throw ProviderProtocolException("服务端返回错误：$message")
        }
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        // 思考内容独立流出（AI 助手场景展示用），不混入正文；
        // 大纲/正文流程 includeReasoning=false，保持原有过滤行为
        if (includeReasoning) {
            val reasoningDelta = choice?.let { c ->
                val delta = c["delta"] as? JsonObject
                val message = c["message"] as? JsonObject
                sequenceOf(
                    delta?.get("reasoning_content"),
                    delta?.get("reasoningContent"),
                    message?.get("reasoning_content"),
                    message?.get("reasoningContent")
                ).mapNotNull { it?.let(::extractText) }.firstOrNull { it.isNotEmpty() }
            }
            if (!reasoningDelta.isNullOrEmpty()) send(StreamEvent.Reasoning(reasoningDelta))
        }
        // Reasoning chunks are intentionally excluded from streaming output.
        // Some providers send a long reasoning_content before the actual JSON
        // content; mixing the two makes the final outline impossible to parse.
        val delta = choice?.let { extractChoiceContent(it, includeReasoning = false) }
            ?: extractResponseContent(root)
        if (!delta.isNullOrEmpty()) send(StreamEvent.Delta(delta))
        // 部分网关（如 SenseNova）在非结束块用空字符串 "" 而非 null 表示
        // 未结束；必须把空白视为“未结束”，否则读到第一个内容块就会中断流
        val finishReason = choice?.get("finish_reason")?.let(::extractText)?.takeIf { it.isNotBlank() }
        if (finishReason != null) send(StreamEvent.Finished(finishReason))
        root["usage"]?.jsonObject?.let { send(StreamEvent.Usage(parseUsage(it))) }
        return finishReason != null
    }

    private fun parseObjectOrNull(body: String): JsonObject? = runCatching {
        json.parseToJsonElement(body).jsonObject
    }.getOrNull()

    private fun executeChat(request: ChatRequest): LlmResponse {
        val call = httpClient.newCall(buildRequest(request))
        request.options.timeoutMs?.let { timeoutMs ->
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        }
        val response = call.execute()
        response.use {
            if (!it.isSuccessful) throw httpException(it)
            val body = it.body?.string().orEmpty()
            val root = parseResponseObject(body)
            root["error"]?.let { error ->
                val message = (error as? JsonObject)?.get("message")?.let(::extractText) ?: "未知错误"
                throw ProviderProtocolException("服务端返回错误：$message")
            }
            val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw ProviderProtocolException("响应缺少 choices")
            val content = extractChoiceContent(choice, includeReasoning = true)
                ?: extractResponseContent(root)
                ?: throw ProviderProtocolException(
                    "响应缺少可用文本，choice字段：${choice.keys.sorted().joinToString(",")}，" +
                        "message字段：${(choice["message"] as? JsonObject)?.keys?.sorted()?.joinToString(",").orEmpty()}，" +
                        "顶层字段：${root.keys.sorted().joinToString(",")}"
                )
            return LlmResponse(
                content = content,
                usage = root["usage"]?.jsonObject?.let(::parseUsage) ?: LlmUsage(estimated = true),
                finishReason = choice["finish_reason"]?.let(::extractText)?.takeIf { it.isNotBlank() },
                requestId = request.options.requestId
            )
        }
    }

    private fun extractChoiceContent(
        choice: JsonObject,
        includeReasoning: Boolean
    ): String? {
        val message = choice["message"] as? JsonObject
        val delta = choice["delta"] as? JsonObject
        val candidates = sequenceOf(
            message?.get("content"),
            message?.get("text"),
            choice["text"],
            choice["content"],
            delta?.get("content"),
            delta?.get("text"),
            if (includeReasoning) message?.get("reasoning_content") else null,
            if (includeReasoning) message?.get("reasoningContent") else null,
            if (includeReasoning) delta?.get("reasoning_content") else null,
            if (includeReasoning) delta?.get("reasoningContent") else null
        )
        return candidates
            .mapNotNull { candidate -> candidate?.let(::extractText) }
            .firstOrNull { it.isNotBlank() }
    }

    private fun extractResponseContent(root: JsonObject): String? =
        sequenceOf("output_text", "output", "response", "result", "content", "text", "data")
            .mapNotNull { key -> root[key] }
            .mapNotNull(::extractText)
            .firstOrNull { it.isNotBlank() }

    private fun extractText(value: JsonElement): String? = when (value) {
        is JsonPrimitive -> value.contentOrNull
        is JsonArray -> value.mapNotNull(::extractText).joinToString("").takeIf { it.isNotBlank() }
        is JsonObject -> sequenceOf(
            "text",
            "content",
            "value",
            "output_text",
            "answer",
            "response",
            "result",
            "data",
            "message",
            "reasoning_content",
            "reasoningContent"
        )
            .mapNotNull { key -> value[key] }
            .mapNotNull(::extractText)
            .firstOrNull { it.isNotBlank() }
        else -> null
    }

    private fun buildRequest(request: ChatRequest): Request {
        val endpoint = endpointUrl(request.config.baseUrl, request.config.capabilities.chatCompletionsPath)
        val body = buildJsonObject {
            put("model", request.config.model)
            put("messages", buildJsonArray {
                request.messages.forEach { message ->
                    add(buildJsonObject {
                        put("role", message.role.wireValue)
                        put("content", message.content)
                    })
                }
            })
            request.options.temperature?.let { put("temperature", it) }
            put(
                when (request.config.capabilities.parameterStyle) {
                    ParameterStyle.MAX_TOKENS -> "max_tokens"
                    ParameterStyle.MAX_COMPLETION_TOKENS -> "max_completion_tokens"
                },
                request.options.outputTokenBudget
            )
            put("stream", request.options.stream)
            if (request.config.disableThinking && !request.options.includeReasoning) {
                // 国内 OpenAI 兼容网关常用的两种关闭思考写法（Qwen 系 / 智谱系），
                // 不识别这些字段的网关通常会直接忽略，不影响请求
                put("enable_thinking", false)
                put("thinking", buildJsonObject { put("type", "disabled") })
            }
            when (request.options.responseFormat.kind) {
                ResponseFormatKind.NONE -> Unit
                ResponseFormatKind.JSON_OBJECT -> if (request.config.capabilities.supportsJsonObject) {
                    put("response_format", buildJsonObject { put("type", "json_object") })
                }
                ResponseFormatKind.JSON_SCHEMA -> if (request.config.capabilities.supportsJsonSchema) {
                    val schema = request.options.responseFormat.schemaJson
                        ?.let { json.parseToJsonElement(it).jsonObject }
                        ?: throw ProviderProtocolException("JSON Schema 不能为空")
                    put("response_format", buildJsonObject {
                        put("type", "json_schema")
                        put("json_schema", schema)
                    })
                }
            }
        }
        return Request.Builder()
            .url(endpoint)
            .headers(authHeaders(request.config))
            .header("Accept", if (request.options.stream) "text/event-stream" else "application/json")
            .header("X-Client-Request-Id", request.options.requestId)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun authHeaders(config: LLMConnectionConfig): Headers {
        return when (config.capabilities.authStyle) {
            AuthStyle.BEARER -> Headers.headersOf("Authorization", "Bearer ${config.apiKey}")
            AuthStyle.API_KEY_HEADER -> Headers.headersOf("x-api-key", config.apiKey)
            AuthStyle.CUSTOM -> Headers.headersOf("Authorization", config.apiKey)
        }
    }

    private fun httpException(response: Response): ProviderHttpException {
        val retryAfter = response.header("Retry-After")?.toLongOrNull()
        return ProviderHttpException(response.code, retryAfter)
    }

    private fun parseResponseObject(body: String): JsonObject = try {
        json.parseToJsonElement(body).jsonObject
    } catch (error: Exception) {
        throw ProviderProtocolException("LLM 响应不是合法 JSON")
    }

    private fun parseUsage(usage: JsonObject): LlmUsage {
        fun number(name: String): Long? = usage[name]?.jsonPrimitive?.longOrNull
        val details = usage["completion_tokens_details"]?.jsonObject
        val promptDetails = usage["input_tokens_details"]?.jsonObject ?: usage["prompt_tokens_details"]?.jsonObject
        return LlmUsage(
            inputTokens = number("prompt_tokens") ?: number("input_tokens"),
            outputTokens = number("completion_tokens") ?: number("output_tokens"),
            totalTokens = number("total_tokens"),
            estimated = false,
            cachedInputTokens = number("prompt_cache_hit_tokens")
                ?: number("cached_tokens")
                ?: promptDetails?.let { d -> d["cached_tokens"]?.jsonPrimitive?.longOrNull },
            reasoningTokens = details?.let { d -> d["reasoning_tokens"]?.jsonPrimitive?.longOrNull }
                ?: number("reasoning_tokens")
        )
    }

    private fun validateBaseUrl(baseUrl: String) {
        val url = baseUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Base URL 不是合法 URL")
        val localHost = url.host == "localhost" || runCatching {
            InetAddress.getByName(url.host).isLoopbackAddress
        }.getOrDefault(false)
        require(url.scheme == "https" || (url.scheme == "http" && localHost)) {
            "Base URL 必须使用 HTTPS；本地 localhost 可使用 HTTP"
        }
    }

    private fun endpointUrl(baseUrl: String, path: String): String =
        baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
