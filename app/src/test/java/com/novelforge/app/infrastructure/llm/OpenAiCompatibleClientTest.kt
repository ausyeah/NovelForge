package com.novelforge.app.infrastructure.llm

import com.novelforge.app.domain.model.LlmUsage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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

    private fun request(stream: Boolean = false): ChatRequest = ChatRequest(
        messages = listOf(ChatMessage(ChatRole.USER, "测试")),
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
