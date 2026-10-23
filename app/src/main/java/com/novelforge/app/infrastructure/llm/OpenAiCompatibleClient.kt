package com.novelforge.app.infrastructure.llm

import com.novelforge.app.domain.model.LlmUsage
import java.io.IOException
import java.net.InetAddress
import java.util.Base64
import java.util.Locale
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
        // readTimeout 是「两次读之间」的间隔，不是整个请求的时限。
        // 一个每 60 秒吐一个 `data: {}` 或 `: keepalive` 的代理（或某些网关的保活）
        // 能让读永远不超时，生成任务就永远不结束，前台通知也撤不掉。
        // callTimeout 才是整次调用的硬上限。
        .callTimeout(DEFAULT_CALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
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
        // buildRequest 内部会切到 Dispatchers.IO 做 base64 编码：
        // channelFlow 的生产者跑在调用方上下文（ChatScreen 里就是 Main），
        // 不切线程的话两张满尺寸图能把主线程卡出 ANR。
        val call = httpClient.newCall(buildRequest(request.copy(options = request.options.copy(stream = true))))
        request.options.timeoutMs?.let { timeoutMs ->
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        }
        val readerJob = launch(Dispatchers.IO) {
            var response: Response? = null
            try {
                response = call.execute()
                if (!response.isSuccessful) throw httpError(response, request)
                val body = response.body ?: throw ProviderProtocolException("响应没有 body")
                // 两个终止标记要分开记：
                //  - sawFinishReason：真的收到了 finish_reason
                //  - sawDone：收到了 [DONE]
                // 有些网关只发 [DONE] 不发 finish_reason，把它当截断会白白丢掉整章。
                // 而「提前中断」的特征是两者都没有。
                var sawFinish = false
                var sawDone = false
                body.source().use { source ->
                    val dataLines = mutableListOf<String>()
                    val rawBody = StringBuilder()
                    var emittedSseData = false
                    while (!source.exhausted()) {
                        currentCoroutineContext().ensureActive()
                        val line = source.readUtf8Line() ?: break
                        rawBody.append(line).append('\n')
                        when {
                            line.isEmpty() -> {
                                if (dataLines.isNotEmpty()) {
                                    emittedSseData = true
                                    val outcome = emitSseData(dataLines, includeReasoning)
                                    sawFinish = sawFinish || outcome.sawFinishReason
                                    sawDone = sawDone || outcome.sawDone
                                    dataLines.clear()
                                    if (outcome.sawDone) break
                                }
                            }
                            line.startsWith("data:") -> {
                                // SSE 规定只去掉一个前导空格；空载荷是合法的心跳，
                                // 不能当成一条待解析的 JSON（否则整条流以
                                // 「LLM 响应不是合法 JSON」中断，正在生成的章节全丢）
                                val data = line.removePrefix("data:").removePrefix(" ")
                                if (data.isNotEmpty()) {
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
                                        val outcome = emitSseData(dataLines, includeReasoning)
                                        sawFinish = sawFinish || outcome.sawFinishReason
                                        sawDone = sawDone || outcome.sawDone
                                        dataLines.clear()
                                        // 收到 finish_reason 之后不能停：usage 统计块紧跟在它后面，
                                        // 提前 break 会把「缓存命中 / 思考 token」永远丢掉，
                                        // 账本只能退化成按字数估算。真正该停的是 [DONE]。
                                        if (outcome.sawDone) break
                                    }
                                }
                            }
                            line.startsWith(":") -> Unit
                        }
                    }
                    if (dataLines.isNotEmpty()) {
                        emittedSseData = true
                        val outcome = emitSseData(dataLines, includeReasoning)
                        sawFinish = sawFinish || outcome.sawFinishReason
                        sawDone = sawDone || outcome.sawDone
                    }
                    // A few OpenAI-compatible gateways ignore stream=true and
                    // return one normal JSON response. Treat it as one delta
                    // instead of silently completing an empty generation.
                    if (!emittedSseData) {
                        val bodyText = rawBody.toString().trim()
                        if (bodyText.isNotEmpty()) {
                            val root = parseResponseObject(bodyText)
                            sawFinish = emitResponseObject(root, includeReasoning).sawFinishReason || sawFinish
                        }
                    }
                    // 流式输出结束却两个终止标记都没收到：多半是服务端限流/异常
                    // 提前掐断了连接。绝不能把半截内容当成功结果。
                    if (emittedSseData && !sawFinish && !sawDone) {
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

    /** SSE 一次事件的处理结果。区分「收到了真正的 finish_reason」和「收到了 [DONE]」。 */
    private data class SseOutcome(val sawFinishReason: Boolean, val sawDone: Boolean)

    private suspend fun kotlinx.coroutines.channels.ProducerScope<StreamEvent>.emitSseData(
        dataLines: List<String>,
        includeReasoning: Boolean
    ): SseOutcome {
        if (dataLines.isEmpty()) return SseOutcome(sawFinishReason = false, sawDone = false)
        val data = dataLines.joinToString("\n")
        if (data.trim() == "[DONE]") return SseOutcome(sawFinishReason = false, sawDone = true)
        val root = parseObjectOrNull(data) ?: return SseOutcome(sawFinishReason = false, sawDone = false)
        val outcome = emitResponseObject(root, includeReasoning)
        return SseOutcome(sawFinishReason = outcome.sawFinishReason, sawDone = false)
    }

    private suspend fun kotlinx.coroutines.channels.ProducerScope<StreamEvent>.emitResponseObject(
        root: JsonObject,
        includeReasoning: Boolean
    ): SseOutcome {
        // 部分网关限流/出错时不返回 HTTP 错误码，而是 HTTP 200 + 响应体内嵌 error 对象，
        // 必须显式识别，否则会被当成“空响应”静默吞掉
        root["error"]?.let { error ->
            val message = (error as? JsonObject)?.get("message")?.let(::extractText) ?: "未知错误"
            throw ProviderProtocolException("服务端返回错误：$message")
        }
        val choice = root["choices"]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.firstOrNull()
            ?.let { runCatching { it.jsonObject }.getOrNull() }
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
        root["usage"]?.let { node ->
            runCatching { node.jsonObject }.getOrNull()?.let { send(StreamEvent.Usage(parseUsage(it))) }
        }
        return SseOutcome(sawFinishReason = finishReason != null, sawDone = false)
    }

    private fun parseObjectOrNull(body: String): JsonObject? = runCatching {
        json.parseToJsonElement(body).jsonObject
    }.getOrNull()

    private suspend fun executeChat(request: ChatRequest): LlmResponse {
        val call = httpClient.newCall(buildRequest(request))
        request.options.timeoutMs?.let { timeoutMs ->
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        }
        val response = call.execute()
        response.use {
            if (!it.isSuccessful) throw httpError(it, request)
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

    /**
     * 组装请求。chat() 与 streamChat() 共用这一个方法（唯一差别是 options.stream），
     * 所以多模态的序列化只需要在这里做一次。
     *
     * 挂成 suspend + withContext(IO)：base64 编码和整段 JSON 字符串拼接都是纯 CPU 的重活，
     * 2 MB 的图编出来接近 3 MB 文本。chat() 本身已经在 IO 上，但 streamChat() 的
     * channelFlow 生产者跑在调用方上下文（聊天页是 Main），必须自己切。
     */
    private suspend fun buildRequest(request: ChatRequest): Request = withContext(Dispatchers.IO) {
        val endpoint = endpointUrl(request.config.baseUrl, request.config.capabilities.chatCompletionsPath)
        val body = buildJsonObject {
            put("model", request.config.model)
            put("messages", buildJsonArray {
                request.messages.forEach { message ->
                    add(buildJsonObject {
                        put("role", message.role.wireValue)
                        // 无附件时 content 保持纯字符串，形状一个字节都不变。
                        // 这条是硬约束：大纲/正文生成整轮共用同一段系统提示，
                        // 一旦 content 从 string 变成 array，前缀缓存的命中边界就全废了
                        // （账单直接翻倍）；而且只认 string content 的
                        // OpenAI 兼容实现会直接 400。OpenAiCompatibleClientTest 里有断言守着它。
                        val parts = contentParts(message)
                        if (parts.isEmpty()) {
                            put("content", message.content)
                        } else {
                            put("content", parts)
                        }
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
            if (request.options.stream && request.config.capabilities.supportsUsageInStream) {
                // 不显式要，OpenAI 兼容网关就不会在流末尾补 usage 块，
                // 账本里的输入/输出 token 只能退化成按字数估算
                put("stream_options", buildJsonObject { put("include_usage", true) })
            }
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
        Request.Builder()
            .url(endpoint)
            .headers(authHeaders(request.config))
            .header("Accept", if (request.options.stream) "text/event-stream" else "application/json")
            .header("X-Client-Request-Id", request.options.requestId)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    /**
     * 组装多模态 content 数组。没有附件（或附件全被规则滤掉）时返回空数组，
     * 调用方据此退回字符串形态的 content。
     *
     * 顺序刻意定为「正文 → 文档 → 图片」：文本在前能让「同一个问题 + 不同配图」
     * 复用同一段前缀，换图只失效最后几段；反过来则每次都要重算整段提示词。
     */
    private fun contentParts(message: ChatMessage): JsonArray {
        if (message.attachments.isEmpty()) return JsonArray(emptyList())
        val documents = message.attachments.filterIsInstance<ChatAttachment.Document>()
        documents.forEach { document ->
            if (document.text.length > MAX_DOCUMENT_CHARS) {
                throw ProviderProtocolException(
                    "文档「${document.name}」太长了（${document.text.length} 字），" +
                        "换一个小一点的文件（上限 $MAX_DOCUMENT_CHARS 字）"
                )
            }
        }
        val images = if (message.role == ChatRole.USER) {
            message.attachments.filterIsInstance<ChatAttachment.Image>()
        } else {
            // 协议规定 image_url 只能出现在 user 轮。历史消息里误挂的图片
            // （例如日后加「基于上一条重问」）会被网关判成非法请求直接 400，
            // 整轮对话一起完蛋。宁可少一张图，也不要整次请求失败，所以这里静默丢弃。
            emptyList()
        }
        return buildJsonArray {
            if (message.content.isNotEmpty()) add(textPart(message.content))
            documents.forEach { document ->
                // 头部点名文件：模型才知道这段文字是用户给的资料而不是新的指令，
                // 也能在回答里正确引用「你上传的那个 txt」。
                add(textPart("【附件：${document.name}】\n${document.text}"))
            }
            encodeImages(images).forEach { (image, base64) ->
                add(
                    buildJsonObject {
                        put("type", "image_url")
                        put(
                            "image_url",
                            buildJsonObject { put("url", "data:${image.wireMediaType};base64,$base64") }
                        )
                    }
                )
            }
        }
    }

    private fun textPart(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    /**
     * 先按 4/3 的膨胀率预估长度、确认不超限，再真的编码。
     * 反过来做的话，一张超限的几 MB 图片要白白编一遍才发现要抛异常。
     */
    private fun encodeImages(
        images: List<ChatAttachment.Image>
    ): List<Pair<ChatAttachment.Image, String>> {
        var totalChars = 0L
        return images.map { image ->
            if (image.bytes.size > MAX_IMAGE_BYTES) {
                throw ProviderProtocolException(
                    "图片「${image.name}」太大了（${formatMb(image.bytes.size.toLong())} MB），" +
                        "换一张小一点的（单张上限 ${formatMb(MAX_IMAGE_BYTES)} MB）"
                )
            }
            val predicted = base64Length(image.bytes.size)
            if (totalChars + predicted > MAX_TOTAL_BASE64_CHARS) {
                throw ProviderProtocolException(
                    "这次带的图片太多了（base64 之后超过 ${formatMb(MAX_TOTAL_BASE64_CHARS)} MB），" +
                        "删掉或缩小一部分再发（单张上限 ${formatMb(MAX_IMAGE_BYTES)} MB）"
                )
            }
            totalChars += predicted
            // java.util.Base64 而不是 android.util.Base64：
            //  - 前者从不插换行（等价于 NO_WRAP），后者默认每 76 字符一个 \n，
            //    而 data URI 里混进换行，部分网关会直接判定请求体损坏；
            //  - 前者在 JVM 上可用，于是这段编码逻辑能被单元测试真跑一遍。
            image to Base64.getEncoder().encodeToString(image.bytes)
        }
    }

    /** base64 之后的长度：每 3 字节变 4 字符，末尾补 '='。 */
    private fun base64Length(rawSize: Int): Long = ((rawSize + 2L) / 3L) * 4L

    /** 固定 Locale.ROOT，避免某些语言环境下小数点变逗号混进中文提示。 */
    private fun formatMb(bytes: Long): String =
        String.format(Locale.ROOT, "%.1f", bytes / 1024.0 / 1024.0)

    private fun sendsImages(request: ChatRequest): Boolean = request.messages.any { message ->
        message.role == ChatRole.USER && message.attachments.any { it is ChatAttachment.Image }
    }

    private fun authHeaders(config: LLMConnectionConfig): Headers {
        return when (config.capabilities.authStyle) {
            AuthStyle.BEARER -> Headers.headersOf("Authorization", "Bearer ${config.apiKey}")
            AuthStyle.API_KEY_HEADER -> Headers.headersOf("x-api-key", config.apiKey)
            AuthStyle.CUSTOM -> Headers.headersOf("Authorization", config.apiKey)
        }
    }

    /**
     * 把非 2xx 响应变成异常。
     *
     * 绝大多数情况就是 ProviderHttpException，providerMessage 里带着服务商原话。
     * 唯一的特判是「模型不支持图片」：这类 400 的错误体往往只有一句
     * "model does not support image input"，原样透传的话用户看到的是一句看不懂的英文，
     * 根本不知道该换模型还是该删图。仍然返回 ProviderHttpException（而不是
     * ProviderProtocolException），因为后者在 GenerationRuntime.isRetryable 里被
     * 当成「多半是限流，值得重试」，会让一个永远不会自愈的能力问题白白重试三次。
     */
    private fun httpError(response: Response, request: ChatRequest): RuntimeException {
        // Retry-After 既可能是秒数也可能是 HTTP 日期，后者现在会被静默丢掉，
        // 于是限流退避只按本地指数退避走，必然再撞一次 429。
        val retryAfter = response.header("Retry-After")?.let { raw ->
            raw.trim().toLongOrNull()?.takeIf { it >= 0 }
                ?: runCatching {
                    val millis = java.time.ZonedDateTime
                        .parse(raw.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli()
                    ((millis - System.currentTimeMillis()) / 1_000L).coerceAtLeast(0L)
                }.getOrNull()
        }
        // 错误体里的 message 是唯一能让人自己解决问题的信息
        // （「Incorrect API key provided」「context length exceeded」…），
        // 丢掉它就只剩一个 HTTP 401。
        val detail = runCatching { response.peekBody(8_192).string() }.getOrNull()
            ?.let { raw ->
                runCatching { json.parseToJsonElement(raw).jsonObject["error"] }.getOrNull()
            }
            ?.let { node ->
                runCatching { node.jsonObject["message"] }.getOrNull()?.let(::extractText)
                    ?: extractText(node)
            }
            ?.take(200)
        if (response.code == 400 && sendsImages(request) && detail.looksLikeUnsupportedVision()) {
            return ProviderHttpException(
                statusCode = response.code,
                retryAfterSeconds = retryAfter,
                providerMessage = detail,
                friendlyMessage = "当前模型「${request.config.model}」不支持图片输入。" +
                    "换个支持视觉的模型，或者去掉图片只用文字提问。服务商原话：$detail"
            )
        }
        return ProviderHttpException(response.code, retryAfter, detail)
    }

    /**
     * 粗判服务商原话是不是「这个模型/网关不收图片」。
     * 要求同时命中「提到图像」和「表示不支持」两组关键词，
     * 单独命中任一组都不足以定性（400 的原因太杂，宁可原样透传）。
     */
    private fun String?.looksLikeUnsupportedVision(): Boolean {
        if (this.isNullOrBlank()) return false
        val text = lowercase()
        return VISION_SUBJECT_HINTS.any { it in text } && VISION_REFUSAL_HINTS.any { it in text }
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
        private const val DEFAULT_CALL_TIMEOUT_MINUTES = 15L

        private val VISION_SUBJECT_HINTS = listOf(
            "image", "vision", "multi-modal", "multimodal", "picture", "图片", "图像", "视觉", "多模态"
        )
        private val VISION_REFUSAL_HINTS = listOf(
            "does not support", "doesn't support", "not support", "unsupported",
            "cannot support", "no support", "不支持", "无法支持", "未提供"
        )

        /**
         * 单张图片原始字节上限 2 MB，base64 之后约 2.8 MB。
         *
         * 数字怎么来的：现代手机原图普遍 3–5 MB，base64 按 4/3 膨胀之后是 4–6.7 MB，
         * 而 OpenAI 兼容网关的请求体上限普遍落在 4–10 MB 区间。超限时它们几乎
         * 只回一句 "invalid request"（有的连 message 都没有），用户完全无从判断
         * 是自己图太大还是 key 有问题。2 MB 编完还剩得下提示词和历史的预算。
         *
         * 想放宽请先确认目标网关的请求体上限；真要支持更大的原图，
         * 正确做法是在调用方（UI 层）先等比缩放，而不是调这个常量。
         */
        const val MAX_IMAGE_BYTES = 2L * 1024 * 1024

        /**
         * 一次请求内所有图片 base64 之后的总字符上限 8 MB（约 6 MB 原图）。
         * 8 MB 的 JSON 加上提示词仍在主流网关 10 MB 量级之内，留出了余量。
         *
         * 单独设总上限是为了堵「每张都刚好卡在 MAX_IMAGE_BYTES」这种绕过单张限制的组合。
         * 注意它比 3×MAX_IMAGE_BYTES 略小一点（顶格三张的 base64 是 8.4 MB），
         * 所以多图场景真正的瓶颈是这条线：顶格图最多两张。
         */
        const val MAX_TOTAL_BASE64_CHARS = 8L * 1024 * 1024

        /**
         * 文本类文档内联进提示词的字符上限。文档不走上传，是整段拼进 text 段的，
         * 没有这道闸的话一份几百 KB 的导出稿能把模型上下文一次吃光。
         */
        const val MAX_DOCUMENT_CHARS = 100_000
    }
}
