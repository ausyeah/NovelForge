package com.novelforge.app.infrastructure.llm

import com.novelforge.app.domain.model.LlmUsage
import java.util.Base64
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiCompatibleClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun chatParsesUsageAndDoesNotFollowRedirects() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"choices":[{"message":{"content":"你好"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}"""
                )
        )
        val client = client()
        val response = client.chat(request())

        assertEquals("你好", response.content)
        assertEquals(LlmUsage(10, 20, 30, false), response.usage)
        assertEquals("Bearer unit-test-placeholder-key", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun chatParsesContentPartsAndTextFallback() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"choices":[{"message":{"content":[{"type":"text","text":"你好"}]}}]}"""
                )
        )
        assertEquals("你好", client().chat(request()).content)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{" + "\"choices\":[{\"text\":\"备用文本\"}]}")
        )
        assertEquals("备用文本", client().chat(request()).content)
    }

    @Test
    fun streamParsesSseDeltaAndDone() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n"
                )
        )
        val events = client().streamChat(request()).toList()

        assertEquals(listOf("你", "好"), events.filterIsInstance<StreamEvent.Delta>().map { it.text })
        assertTrue(events.any { it is StreamEvent.Finished && it.finishReason == "stop" })
    }

    @Test
    fun streamParsesArrayDeltaContent() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":[{\"type\":\"text\",\"text\":\"结构化\"}]}}]}\n\n" +
                        "data: [DONE]\n\n"
                )
        )

        val events = client().streamChat(request()).toList()

        assertEquals(listOf("结构化"), events.filterIsInstance<StreamEvent.Delta>().map { it.text })
    }

    @Test
    fun streamParsesDataLinesEvenWhenProviderOmitsBlankSeparators() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"首\"}}]}\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"段\"}}]}\n" +
                        "data: [DONE]\n"
                )
        )

        val events = client().streamChat(request(stream = true)).toList()

        assertEquals(listOf("首", "段"), events.filterIsInstance<StreamEvent.Delta>().map { it.text })
    }

    @Test
    fun streamTreatsNormalJsonBodyAsOneDelta() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    "{\"choices\":[{\"message\":{\"content\":\"完整响应\"},\"finish_reason\":\"stop\"}]}"
                )
        )

        val events = client().streamChat(request(stream = true)).toList()

        assertEquals(listOf("完整响应"), events.filterIsInstance<StreamEvent.Delta>().map { it.text })
        assertTrue(events.any { it is StreamEvent.Finished && it.finishReason == "stop" })
    }

    @Test
    fun streamDoesNotMixReasoningContentIntoFinalContent() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"思考过程\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"最终 JSON\"},\"finish_reason\":\"stop\"}]}\n\n"
                )
        )

        val events = client().streamChat(request(stream = true)).toList()

        assertEquals(listOf("最终 JSON"), events.filterIsInstance<StreamEvent.Delta>().map { it.text })
    }

    @Test
    fun retry429OnceThenSucceeds() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}"""
            )
        )

        val response = client(RetryPolicy(maxRetries = 1, initialDelayMs = 1, maxDelayMs = 1, jitter = false))
            .chat(request())

        assertEquals("ok", response.content)
        assertEquals(2, server.requestCount)
    }

    // ---------- 多模态：无附件时的字节级回归守卫 ----------

    /**
     * 这条是整组多模态改动里最重要的断言。
     * content 从 string 变成 array 会让网关的前缀缓存完全失效（账单翻倍），
     * 部分只认 string content 的 OpenAI 兼容实现还会直接 400，
     * 而大纲/正文生成路径永远不会带附件。所以整个字符串必须逐字节保持原样。
     */
    @Test
    fun requestBodyIsByteForByteUnchangedWithoutAttachments() = runBlocking {
        enqueueOk()

        client().chat(request())

        assertEquals(
            """{"model":"test-model","messages":[{"role":"user","content":"测试"}],"max_tokens":100,"stream":false}""",
            takeBody()
        )
    }

    /** 多条消息里只要有一条带附件，也不能影响其他消息的字符串形态。 */
    @Test
    fun onlyTheMessageCarryingAttachmentsSwitchesToArrayForm() = runBlocking {
        enqueueOk()

        client().chat(
            request(
                messages = listOf(
                    ChatMessage(ChatRole.SYSTEM, "你是助手"),
                    ChatMessage(ChatRole.USER, "看图", listOf(png("a.png", 1))),
                    ChatMessage(ChatRole.ASSISTANT, "看到了"),
                    ChatMessage(ChatRole.USER, "再详细点")
                )
            )
        )

        val messages = Json.parseToJsonElement(takeBody())
            .jsonObject["messages"]!!.jsonArray
        assertEquals("你是助手", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertTrue("带附件的那条必须是数组形态", messages[1].jsonObject["content"] is JsonArray)
        assertEquals("看到了", messages[2].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("再详细点", messages[3].jsonObject["content"]!!.jsonPrimitive.content)
    }

    // ---------- 多模态：数组形态的组装 ----------

    @Test
    fun imageAttachmentBecomesImageUrlPartWithDataUri() = runBlocking {
        enqueueOk()
        val bytes = byteArrayOf(1, 2, 3, 4, 5)

        client().chat(
            request(
                messages = listOf(
                    ChatMessage(
                        ChatRole.USER,
                        "这是什么？",
                        listOf(ChatAttachment.Image("image/JPEG; charset=binary", "shot.png", bytes))
                    )
                )
            )
        )

        val parts = contentParts(takeBody())
        assertEquals(2, parts.size)
        assertEquals("text", parts[0]["type"]!!.jsonPrimitive.content)
        assertEquals("这是什么？", parts[0]["text"]!!.jsonPrimitive.content)
        assertEquals("image_url", parts[1]["type"]!!.jsonPrimitive.content)
        // mediaType 的大小写和参数必须被归一化，否则 data URI 前半段是废的
        assertEquals(
            "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes),
            imageUrl(parts[1])
        )
    }

    @Test
    fun textPartsComeFirstAndTextDocumentNamesItsFile() = runBlocking {
        enqueueOk()

        client().chat(
            request(
                messages = listOf(
                    ChatMessage(
                        ChatRole.USER,
                        "看下这些",
                        listOf(
                            png("a.png", 9),
                            ChatAttachment.Document("text/markdown", "设定.md", "主角叫林晚"),
                            png("b.png", 8)
                        )
                    )
                )
            )
        )

        val parts = contentParts(takeBody())
        assertEquals(
            listOf("text", "text", "image_url", "image_url"),
            parts.map { it["type"]!!.jsonPrimitive.content }
        )
        assertEquals("看下这些", parts[0]["text"]!!.jsonPrimitive.content)
        // 文档段必须带文件名，模型才知道这段文字是用户给的资料而不是新指令
        assertEquals("【附件：设定.md】\n主角叫林晚", parts[1]["text"]!!.jsonPrimitive.content)
        assertTrue(imageUrl(parts[2]).startsWith("data:image/png;base64,"))
        assertTrue(imageUrl(parts[3]).startsWith("data:image/png;base64,"))
        // 图片之间的相对顺序要和 attachments 里给的一致
        assertEquals("data:image/png;base64,CQ==", imageUrl(parts[2]))
        assertEquals("data:image/png;base64,CA==", imageUrl(parts[3]))
    }

    @Test
    fun assistantMessageWithImageAttachmentOmitsImagePart() = runBlocking {
        enqueueOk()

        client().chat(
            request(
                messages = listOf(
                    ChatMessage(ChatRole.ASSISTANT, "我看到了", listOf(png("x.png", 1)))
                )
            )
        )

        val body = takeBody()
        assertFalse("assistant 轮绝不能出现 image_url：$body", body.contains("image_url"))
        val parts = contentParts(body)
        assertEquals(1, parts.size)
        assertEquals("我看到了", parts[0]["text"]!!.jsonPrimitive.content)
    }

    /** 顺带守着 buildRequest 确实被两条路径共用：stream=true 也得带上同一套 content 段。 */
    @Test
    fun streamChatSendsTheSameContentParts() = runBlocking {
        enqueueSse("已看到")

        client().streamChat(
            request(
                stream = true,
                messages = listOf(ChatMessage(ChatRole.USER, "看图", listOf(png("a.png", 7))))
            )
        ).toList()

        val parts = contentParts(takeBody())
        assertEquals(listOf("text", "image_url"), parts.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("data:image/png;base64,Bw==", imageUrl(parts[1]))
    }

    @Test
    fun base64PayloadContainsNoLineBreaks() = runBlocking {
        enqueueOk()
        // 300 字节远超 android.util.Base64.DEFAULT 的 76 字符换行宽度。
        // 万一有人把编码换成它（或用了带换行的 MIME 变体），这条立刻红。
        val bytes = ByteArray(300) { (it % 251).toByte() }

        client().chat(
            request(messages = listOf(ChatMessage(ChatRole.USER, "图", listOf(png("big.png", *bytes)))))
        )

        val url = imageUrl(contentParts(takeBody())[1])
        assertFalse("data URI 里混进了换行", url.contains('\n') || url.contains('\r'))
        assertTrue("300 字节应编成 400 字符，实际 ${url.length}", url.length >= 400)
        assertArrayEquals(bytes, Base64.getDecoder().decode(url.substringAfterLast(',')))
    }

    // ---------- 多模态：体积上限 ----------

    @Test
    fun oversizedImageIsRejectedBeforeAnyRequestIsSent() = runBlocking {
        val error = runCatching {
            client().chat(
                request(
                    messages = listOf(
                        ChatMessage(
                            ChatRole.USER,
                            "大图",
                            listOf(
                                ChatAttachment.Image(
                                    "image/jpeg",
                                    "photo.jpg",
                                    ByteArray((OpenAiCompatibleClient.MAX_IMAGE_BYTES + 1).toInt())
                                )
                            )
                        )
                    )
                )
            )
        }.exceptionOrNull()

        assertTrue("实际是 ${error?.javaClass?.name}", error is ProviderProtocolException)
        val message = error!!.message.orEmpty()
        assertTrue("实际提示：$message", message.contains("图片"))
        assertTrue("实际提示：$message", message.contains("2.0 MB"))
        assertTrue("实际提示：$message", message.contains("换一张小一点的"))
        // 必须在发出去之前就拦下：超限请求到了网关只会得到一句看不懂的 400
        assertEquals(0, server.requestCount)
    }

    @Test
    fun tooManyImagesExceedTotalPayloadCap() = runBlocking {
        // 3 张顶格图的 base64 是 8.4 MB，已经越过 8 MB 的总上限
        val error = runCatching {
            client().chat(
                request(
                    messages = listOf(
                        ChatMessage(
                            ChatRole.USER,
                            "一次发一堆",
                            List(3) { index ->
                                ChatAttachment.Image(
                                    "image/png",
                                    "shot$index.png",
                                    ByteArray(OpenAiCompatibleClient.MAX_IMAGE_BYTES.toInt())
                                )
                            }
                        )
                    )
                )
            )
        }.exceptionOrNull()

        assertTrue("实际是 ${error?.javaClass?.name}", error is ProviderProtocolException)
        assertTrue("实际提示：${error?.message}", error!!.message.orEmpty().contains("图片太多"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun oversizedDocumentIsRejected() = runBlocking {
        val error = runCatching {
            client().chat(
                request(
                    messages = listOf(
                        ChatMessage(
                            ChatRole.USER,
                            "读一下",
                            listOf(
                                ChatAttachment.Document(
                                    "text/plain",
                                    "长稿.txt",
                                    "字".repeat(OpenAiCompatibleClient.MAX_DOCUMENT_CHARS + 1)
                                )
                            )
                        )
                    )
                )
            )
        }.exceptionOrNull()

        assertTrue("实际是 ${error?.javaClass?.name}", error is ProviderProtocolException)
        assertTrue("实际提示：${error?.message}", error!!.message.orEmpty().contains("太长了"))
        assertEquals(0, server.requestCount)
    }

    // ---------- 多模态：能力不足的报错必须人话 ----------

    @Test
    fun unsupportedVisionModelYieldsChineseErrorButKeepsStatusCode() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"message":"This model does not support image input"}}"""
            )
        )

        val error = runCatching {
            client().chat(
                request(
                    messages = listOf(ChatMessage(ChatRole.USER, "图", listOf(png("a.png", 1))))
                )
            )
        }.exceptionOrNull()

        assertTrue("实际是 ${error?.javaClass?.name}", error is ProviderHttpException)
        val http = error as ProviderHttpException
        assertEquals(400, http.statusCode)
        // 原话仍然留在 providerMessage 里，日志和排查还能用
        assertEquals("This model does not support image input", http.providerMessage)
        val message = http.message.orEmpty()
        assertTrue("实际提示：$message", message.contains("不支持图片输入"))
        assertTrue("实际提示：$message", message.contains("test-model"))
        // 能力问题永远不会自愈，不能被当成限流反复重试
        assertEquals(1, server.requestCount)
    }

    /** 与图片无关的 400 必须保持原样，不能被这套启发式误伤。 */
    @Test
    fun unrelatedBadRequestIsNotTranslated() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"message":"context length exceeded"}}"""
            )
        )

        val error = runCatching {
            client().chat(
                request(
                    messages = listOf(ChatMessage(ChatRole.USER, "图", listOf(png("a.png", 1))))
                )
            )
        }.exceptionOrNull()

        assertTrue("实际是 ${error?.javaClass?.name}", error is ProviderHttpException)
        val http = error as ProviderHttpException
        assertEquals(400, http.statusCode)
        assertEquals("服务商返回 HTTP 400：context length exceeded", http.message)
    }

    // ---------- 附件模型自身的约束与序列化 ----------

    @Test
    fun attachmentRejectsEmptyPayloadAndFakeImageMediaType() {
        assertThrowsIllegalArgument { png("a.png") }
        assertThrowsIllegalArgument { ChatAttachment.Document("text/plain", "a.txt", "") }
        assertThrowsIllegalArgument { ChatAttachment.Image("application/pdf", "a.pdf", byteArrayOf(1)) }
        assertThrowsIllegalArgument { ChatAttachment.Image("image/png", " ", byteArrayOf(1)) }
    }

    /**
     * GenerationRuntime 每次生成都会把 messages 整串写进 prompt 快照
     * （Json { encodeDefaults = true }）。这条守着 attachments 绝不进快照：
     * 一旦 @Transient 掉了，一张 2 KB 的图就会变成几千个数字字符。
     */
    @Test
    fun chatMessageSerializationIgnoresAttachmentsAndStaysLegacyCompatible() {
        val json = Json { encodeDefaults = true }
        val plain = ChatMessage(ChatRole.USER, "你好")
        val encodedPlain = json.encodeToString(plain)

        assertEquals("""{"role":"USER","content":"你好"}""", encodedPlain)
        assertEquals(
            "带附件的请求体不得比纯文本多出任何字段",
            encodedPlain,
            json.encodeToString(plain.copy(attachments = listOf(png("a.png", *ByteArray(2_048)))))
        )
        // 旧版本写下的快照（没有 attachments 字段）必须照样解得出来
        assertEquals(plain, json.decodeFromString<ChatMessage>(encodedPlain))
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (error: IllegalArgumentException) {
            return
        }
        throw AssertionError("期望抛出 IllegalArgumentException，但没有")
    }

    private fun png(name: String, vararg bytes: Byte) =
        ChatAttachment.Image("image/png", name, bytes)

    /**
     * 取走本次请求体。注意 MockWebServer 的请求队列是一次性的：
     * 一个用例里只能调用一次，第二次会永远阻塞，所以断言要么复用
     * takeBody() 的返回值，要么就走 contentParts(body) 这一条路。
     */
    private fun takeBody(): String = server.takeRequest().body.readUtf8()

    private fun enqueueOk() = server.enqueue(
        MockResponse()
            .setResponseCode(200)
            .setBody("""{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""")
    )

    private fun enqueueSse(text: String) = server.enqueue(
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"$text\"},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n"
            )
    )

    /** 取出请求体里第 index 条消息的 content 数组（多模态形态），逐项转成 JsonObject 方便断言。 */
    private fun contentParts(body: String, messageIndex: Int = 0): List<JsonObject> =
        Json.parseToJsonElement(body)
            .jsonObject["messages"]!!.jsonArray[messageIndex]
            .jsonObject["content"]!!.jsonArray
            .map { it.jsonObject }

    private fun imageUrl(part: JsonObject): String =
        part["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content

    private fun request(
        stream: Boolean = false,
        messages: List<ChatMessage> = listOf(ChatMessage(ChatRole.USER, "测试"))
    ): ChatRequest = ChatRequest(
        messages = messages,
        config = LLMConnectionConfig(
            baseUrl = server.url("/v1").toString(),
            apiKey = System.getenv("NF_TEST_FAKE_TOKEN") ?: error("缺少测试环境变量 NF_TEST_FAKE_TOKEN"),
            model = "test-model",
            capabilities = ProviderCapabilities(
                supportsStreaming = true,
                supportsJsonObject = true,
                chatCompletionsPath = "/chat/completions"
            )
        ),
        options = ChatOptions(
            outputTokenBudget = 100,
            stream = stream,
            requestId = "request-1"
        )
    )

    private fun client(policy: RetryPolicy = RetryPolicy(maxRetries = 1, initialDelayMs = 1, maxDelayMs = 1, jitter = false)) =
        OpenAiCompatibleClient(OkHttpClient(), policy)
}
