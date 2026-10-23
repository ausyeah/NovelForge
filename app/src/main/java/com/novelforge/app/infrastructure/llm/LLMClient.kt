package com.novelforge.app.infrastructure.llm

import com.novelforge.app.domain.model.LlmUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class ChatRole(val wireValue: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant")
}

/**
 * 聊天附件：图片以 base64 data URI 上行，文本类文档直接内联成 text 段。
 *
 * 刻意**不**加 @Serializable。原因不是洁癖，是真的会炸：
 * ChatMessage 是 @Serializable 的，而 GenerationRuntime 每次发起生成都会用
 * `Json { encodeDefaults = true }` 把整个 messages 列表 encodeToString 存进
 * prompt 快照（见 savePromptSnapshot / restoreRequest）。
 * ByteArray 的默认序列化形式是一长串十进制数字（0,255,12,…），
 * 一张 2 MB 的图会变成约七百万个数字字符，Room 的一次写入和内存分配同时被打爆。
 * 所以 ChatMessage.attachments 标了 @Transient：附件只活到请求体组装完成的那一刻，
 * 既不进快照也不进备份。
 */
sealed interface ChatAttachment {
    val mediaType: String

    /** 展示名，也会进入 text 段的头部（模型要知道自己看的是哪个文件）。 */
    val name: String

    /**
     * 去掉 `; charset=utf-8` 之类参数并小写后的纯类型。
     * data URI 里只允许出现 `image/jpeg` 这种裸类型，带参数会让部分网关解析失败。
     */
    val wireMediaType: String
        get() = mediaType.substringBefore(';').trim().lowercase()

    /** 图片：保留原始字节，编码交给 OpenAiCompatibleClient 在 IO 线程做。 */
    data class Image(
        override val mediaType: String,
        override val name: String,
        val bytes: ByteArray
    ) : ChatAttachment {
        init {
            require(name.isNotBlank()) { "图片附件缺少文件名" }
            // 挡住「把 pdf/txt 当图片塞进来」：那类请求到网关才报错，
            // 而网关给的往往是「invalid content」这种没法自查的 400。
            require(wireMediaType.startsWith("image/")) {
                "图片附件「$name」的 mediaType 必须是 image/*，当前是「$mediaType」"
            }
            require(bytes.isNotEmpty()) { "图片附件「$name」内容为空" }
        }

        // ByteArray 的 equals 是引用比较，data class 默认生成的那版会把
        // 「两张内容相同的图」判成不相等，测试和后续去重都会踩坑。
        override fun equals(other: Any?): Boolean =
            this === other ||
                (
                    other is Image &&
                        mediaType == other.mediaType &&
                        name == other.name &&
                        bytes.contentEquals(other.bytes)
                    )

        override fun hashCode(): Int =
            (mediaType.hashCode() * 31 + name.hashCode()) * 31 + bytes.contentHashCode()
    }

    /** 文本类文档（txt / md / json / csv…）：不做任何转码，整段当 text 内联。 */
    data class Document(
        override val mediaType: String,
        override val name: String,
        val text: String
    ) : ChatAttachment {
        init {
            require(name.isNotBlank()) { "文档附件缺少文件名" }
            require(text.isNotEmpty()) { "文档附件「$name」内容为空" }
        }
    }
}

@Serializable
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    /**
     * 图片/文档附件，默认空。
     *
     * @Transient + 默认值一起才是向后兼容的关键：
     *  - @Transient：请求体由 OpenAiCompatibleClient 手写 JSON 组装，
     *    序列化器永远碰不到附件字节（原因见 ChatAttachment 的注释）；
     *  - 默认值：旧版本写下的 prompt 快照解码出来就是空 attachments，
     *    而且大纲 / 正文 / 助手等既有构造点一行都不用改。
     */
    @Transient val attachments: List<ChatAttachment> = emptyList()
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
    val checkpointPrefix: String = "",
    /** 请求开启思考并把 reasoning_content 作为独立事件流出（AI 助手用） */
    val includeReasoning: Boolean = false
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
    /** 思考过程增量（仅 ChatOptions.includeReasoning = true 时产生） */
    data class Reasoning(val text: String) : StreamEvent
    data class Usage(val usage: LlmUsage) : StreamEvent
    data class Finished(val finishReason: String?) : StreamEvent
}

interface LLMClient {
    suspend fun chat(request: ChatRequest): LlmResponse
    fun streamChat(request: ChatRequest): Flow<StreamEvent>
}

class ProviderHttpException(
    val statusCode: Int,
    val retryAfterSeconds: Long? = null,
    /** 服务商在错误体里给的原因，例如「Incorrect API key provided」。 */
    val providerMessage: String? = null,
    /**
     * 覆盖展示给用户的中文说明，只影响 message，不影响 statusCode / providerMessage，
     * 所以重试与限流判断（GenerationRuntime.isRetryable、RetryPolicy）完全不受影响。
     * 目前只有「模型不支持图片」这一种情况会设置它。
     */
    val friendlyMessage: String? = null
) : RuntimeException(
    friendlyMessage?.takeIf { it.isNotBlank() }
        ?: buildString {
            append("服务商返回 HTTP ").append(statusCode)
            providerMessage?.takeIf { it.isNotBlank() }?.let { append("：").append(it) }
        }
)

class ProviderProtocolException(message: String) : RuntimeException(message)
