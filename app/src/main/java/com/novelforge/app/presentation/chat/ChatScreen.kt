package com.novelforge.app.presentation.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novelforge.app.data.chat.ChatHistoryStore
import com.novelforge.app.data.chat.StoredAttachment
import com.novelforge.app.data.chat.StoredChatMessage
import com.novelforge.app.data.chat.StoredConversation
import com.novelforge.app.presentation.chat.richtext.ChatRichText
import com.novelforge.app.data.security.ApiKeyStore
import com.novelforge.app.data.settings.AppSettingsStore
import com.novelforge.app.infrastructure.llm.ChatMessage
import com.novelforge.app.infrastructure.llm.ChatOptions
import com.novelforge.app.infrastructure.llm.ChatRequest
import com.novelforge.app.infrastructure.llm.ChatRole
import com.novelforge.app.infrastructure.llm.LLMConnectionConfig
import com.novelforge.app.infrastructure.llm.OpenAiCompatibleClient
import com.novelforge.app.infrastructure.llm.ProviderCapabilities
import com.novelforge.app.infrastructure.llm.StreamEvent
import com.novelforge.app.presentation.common.PaperTopBar
import com.novelforge.app.ui.theme.PaperButton
import com.novelforge.app.ui.theme.PaperShape
import com.novelforge.app.ui.theme.PaperSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class UiChatMessage(
    val role: ChatRole,
    val text: String,
    val reasoning: String = "",
    val streaming: Boolean = false,
    /**
     * 稳定 id。LazyColumn 的 key、思考折叠的 remember key 都依赖它：
     * 按位置复用槽位时，流式刷新会把整棵子树重建，折叠状态也会串到别的气泡上。
     * 放在最后且带默认值，老的构造调用（不传 id）一处都不用改。
     */
    val id: String = newMessageId(),
    /**
     * 这条消息带的附件。存的是路径不是字节 —— 见 ChatAttachmentStore 的注释。
     * 气泡上要显示缩略图、请求时要重新读文件，两处都只拿得到路径。
     */
    val attachments: List<StoredAttachment> = emptyList()
)

/** 消息 id：每个新气泡一个，copy() 会原样带过去。 */
private fun newMessageId(): String = "m-${System.nanoTime()}-${(1000..9999).random()}"

private const val INSPIRATION_SYSTEM_PROMPT =
    "你是「小说灵感启发助手」，一位陪伴网文作者的创作顾问。\n" +
        "- 帮作者头脑风暴剧情、人物、世界观与开篇钩子，给点子时一次给出 2-4 个方向不同的方案，突出新颖性和可延展性，避免俗套。\n" +
        "- 每个方案用一句话点明核心爽点/亮点，以及可能踩的坑。\n" +
        "- 作者犹豫或卡壳时，用一两个尖锐但友好的提问引导他往下想，不空谈创作理论。\n" +
        "- 回答简洁实用、可直接落地。必须用中文。"

class ChatViewModel(
    private val settingsStore: AppSettingsStore,
    private val apiKeyStore: ApiKeyStore,
    private val client: OpenAiCompatibleClient,
    private val historyStore: ChatHistoryStore,
    private val llmCallRepository: com.novelforge.app.domain.repository.LlmCallRepository,
    private val attachmentStore: com.novelforge.app.data.chat.ChatAttachmentStore
) : ViewModel() {
    private val _messages = MutableStateFlow<List<UiChatMessage>>(emptyList())
    val messages: StateFlow<List<UiChatMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _conversations = MutableStateFlow<List<StoredConversation>>(emptyList())
    val conversations: StateFlow<List<StoredConversation>> = _conversations.asStateFlow()

    private val _activeId = MutableStateFlow("")
    val activeId: StateFlow<String> = _activeId.asStateFlow()

    // 冻结策略：视口不在绝对底部（reverseLayout 的 (0,0)）、列表在滚、或手指
    // 按着时，增量照常攒进 builder，但**一个字都不发**。
    //
    // 以前这里只写不读 —— publishStream 只看节流窗口，压根没查 frozen，
    // 于是"翻上去别动"是句空话：列表在 20Hz 长高，reverseLayout 下最后一条
    // 变高会把上面所有内容顶走，用户看到的就是"自己没动，页面自己在抽"。
    private var frozen = false

    /**
     * 增量只往 builder 里追加，不再每来一个 SSE token 就
     * `m.text + event.text` 复制一遍不断变长的整段正文（那是 O(n²)）。
     * 发布节流由 publishStream 按正文长度自适应控制。
     */
    private val streamReasoning = StringBuilder()
    private val streamText = StringBuilder()
    private var lastPublishAt = 0L
    private var streamJob: kotlinx.coroutines.Job? = null

    /**
     * 会话作用域（书 id），null/空 = 全局「灵感」桶。
     * 默认 null 是有意的：导航图当前还是无参的 "chat" 路由，进不来 projectId，
     * 此刻的行为与老版本完全一致。路由补上参数后调用 bindProjectScope 即可。
     */
    private val _projectScope = MutableStateFlow<String?>(null)
    private var scopeJob: kotlinx.coroutines.Job? = null

    /** 生成中点"停止"：掐掉流式请求，已收到的部分保留 */
    fun stop() {
        streamJob?.cancel()
    }

    fun setFrozen(value: Boolean) {
        if (frozen == value) return
        frozen = value
        if (!frozen) flushPending()
    }

    /** 解冻：节流窗口里攒下的增量一次性落账（最多也就几十毫秒的 token） */
    private fun flushPending() {
        publishStream(force = true)
    }

    /**
     * 无条件把缓冲落进最后一条，**绕过冻结**。
     *
     * 只在一个地方用：用户又发新消息、而上一条还冻着的时候。
     * 不先落账就 resetStreamBuffer 的话，缓冲里那截正文会被直接丢掉 ——
     * 存档里是完整的（persistCurrent 读缓冲），屏幕上却短一截，
     * 同一个会话在历史面板和在聊天气泡里长得不一样。
     */
    private fun commitStreamBuffer() {
        if (streamText.isEmpty() && streamReasoning.isEmpty()) return
        val reasoning = streamReasoning.toString()
        val text = streamText.toString()
        lastPublishAt = System.currentTimeMillis()
        replaceLast { m -> m.copy(reasoning = reasoning, text = text) }
    }

    private fun resetStreamBuffer() {
        streamReasoning.setLength(0)
        streamText.setLength(0)
        lastPublishAt = 0L
    }

    /** 收到增量：先攒进 builder，再按自适应节流发布（见 streamPublishIntervalMs） */
    private fun appendStream(reasoning: String = "", text: String = "") {
        if (reasoning.isNotEmpty()) streamReasoning.append(reasoning)
        if (text.isNotEmpty()) streamText.append(text)
        publishStream(force = false)
    }

    /**
     * 把 builder 里的内容拍快照发布到最后一条消息上。
     *
     * 两道闸，顺序不能反：
     * 1. **冻结闸**（frozen）—— 用户不在看最新内容时一个字都不发。
     * 2. **节流闸** —— force = true（解冻）时无视节流立刻落账。
     *
     * 冻结闸连 force 也不放行，这是有意的：解冻路径自己会调 flushPending，
     * 而生成结束时那一次 force 不发也没关系，因为存档已经改成直接读缓冲
     * （见 currentMessagesForPersistence），不依赖渲染层追上来。
     */
    private fun publishStream(force: Boolean) {
        if (streamReasoning.isEmpty() && streamText.isEmpty()) return
        // 冻结期间一律不发，连 force 也不发（理由见上面的 KDoc）。
        if (frozen) return
        val now = System.currentTimeMillis()
        if (!force && now - lastPublishAt < streamPublishIntervalMs(streamText.length)) return
        lastPublishAt = now
        val reasoning = streamReasoning.toString()
        val text = streamText.toString()
        replaceLast { m -> m.copy(reasoning = reasoning, text = text) }
    }

    /**
     * 只替换最后一条消息对象：前面的实例按引用原样带走，
     * Compose 的 == 短路生效，命中缓存的子树不重组。
     * 自适应节流让这次 N 元素拷贝的开销可以忽略。
     */
    private fun replaceLast(transform: (UiChatMessage) -> UiChatMessage) {
        _messages.update { list ->
            val index = list.lastIndex
            if (index < 0) return@update list
            val next = transform(list[index])
            if (next == list[index]) list else list.subList(0, index) + next
        }
    }

    init {
        startScopeCollection()
    }

    /**
     * 绑定会话归属的书。
     *
     * 导航图要补的一行（在 `composable("chat") { ... }` 里，viewModel 之后）：
     * ```
     * composable("chat?projectId={projectId}", arguments = listOf(
     *     navArgument("projectId") { type = NavType.StringType; defaultValue = "" }
     * )) { entry ->
     *     val chatViewModel: ChatViewModel = viewModel(factory = ChatViewModel.Factory(...))
     *     chatViewModel.bindProjectScope(entry.arguments?.getString("projectId"))
     * }
     * ```
     * 不传 projectId（首页现在的入口）就是全局「灵感」桶，老行为不变。
     * 生成中不切作用域：正在写的那条会话会落错桶，宁可这次不生效。
     */
    fun bindProjectScope(projectId: String?) {
        val next = projectId?.trim()?.takeIf { it.isNotEmpty() }
        if (next == _projectScope.value || _busy.value) return
        _projectScope.value = next
        startScopeCollection()
    }

    /** 重建作用域：丢掉上一个桶的会话与列表，改收新桶的。 */
    private fun startScopeCollection() {
        scopeJob?.cancel()
        val scope = _projectScope.value
        _conversations.value = emptyList()
        _messages.value = emptyList()
        _error.value = null
        _activeId.value = ""
        scopeJob = viewModelScope.launch {
            launch {
                historyStore.conversationsIn(scope).collect { _conversations.value = it }
            }
            launch {
                val latest = historyStore.conversationsIn(scope).first().firstOrNull { it.messages.isNotEmpty() }
                if (latest != null) {
                    _activeId.value = latest.id
                    _messages.value = latest.messages.map { m -> m.toUiMessage() }
                    _thinkingEnabled.value = latest.thinkingEnabled
                } else {
                    _activeId.value = newConversationId()
                }
            }
        }
    }

    private fun newConversationId(): String =
        "c-${System.currentTimeMillis()}-${(1000..9999).random()}"

    /**
     * 要存档的会话内容。
     *
     * 关键：最后一条要用 **buffer 里的全文**，不能直接用 `_messages`。
     * 用户翻到上面去时渲染层是冻结的（publishStream 直接 return），
     * `_messages` 里的最后一条会比真实内容短一截 —— 拿它存档就是
     * **静默把一段没写完的回答存进历史**，而且没有任何报错。
     * 所以存档和渲染必须解耦：渲染可以为了手感冻结，存档永远取全文。
     */
    private fun currentMessagesForPersistence(): List<UiChatMessage> {
        val messages = _messages.value
        if (streamText.isEmpty() && streamReasoning.isEmpty()) return messages
        val last = messages.lastOrNull() ?: return messages
        if (last.role != ChatRole.ASSISTANT) return messages
        return messages.dropLast(1) + last.copy(
            text = streamText.toString(),
            reasoning = streamReasoning.toString()
        )
    }

    private fun persistCurrent() {
        val id = _activeId.value
        if (id.isEmpty()) return
        val snapshot = currentMessagesForPersistence()
            .filter { it.text.isNotBlank() || it.reasoning.isNotBlank() }
        if (snapshot.isEmpty()) return
        val title = snapshot.firstOrNull { it.role == ChatRole.USER }?.text?.trim()?.take(24)
            ?: "新对话"
        val conversation = StoredConversation(
            id = id,
            title = title,
            updatedAt = System.currentTimeMillis(),
            messages = snapshot.map { m ->
                StoredChatMessage(
                    role = if (m.role == ChatRole.USER) "user" else "assistant",
                    text = m.text,
                    reasoning = m.reasoning,
                    attachments = m.attachments
                )
            },
            // 落进当前作用域的桶：没有书上下文就是全局「灵感」桶
            projectId = _projectScope.value,
            // 必须带上：persistCurrent 每次发消息都会整段重写会话，
            // 不带这个字段的话，用户刚关掉的「思考」会被下一次发送悄悄改回开着
            thinkingEnabled = _thinkingEnabled.value
        )
        viewModelScope.launch { runCatching { historyStore.save(conversation) } }
    }

    /**
     * 输入框上方待发送的附件。发送成功才落到消息上；用户中途删掉或退出，
     * 走 clearPendingAttachments 时要把落盘的文件一并删掉，否则攒一堆孤儿图。
     */
    private val _pendingAttachments = MutableStateFlow<List<StoredAttachment>>(emptyList())
    val pendingAttachments: StateFlow<List<StoredAttachment>> = _pendingAttachments.asStateFlow()

    private val _attachmentNotice = MutableStateFlow<String?>(null)
    val attachmentNotice: StateFlow<String?> = _attachmentNotice.asStateFlow()

    fun addImageAttachment(uri: android.net.Uri, displayName: String) {
        viewModelScope.launch {
            _attachmentNotice.value = null
            attachmentStore.saveImage(uri, displayName)
                .onSuccess { _pendingAttachments.update { it + it } }
                .onFailure { _attachmentNotice.value = it.message ?: "这张图加不进来" }
        }
    }

    fun addDocumentAttachment(uri: android.net.Uri, displayName: String) {
        viewModelScope.launch {
            _attachmentNotice.value = null
            attachmentStore.saveDocument(uri, displayName)
                .onSuccess { _pendingAttachments.update { it + it } }
                .onFailure { _attachmentNotice.value = it.message ?: "这个文件加不进来" }
        }
    }

    fun removePendingAttachment(stored: StoredAttachment) {
        _pendingAttachments.update { it - stored }
        viewModelScope.launch { attachmentStore.delete(stored) }
    }

    fun clearPendingAttachments() {
        val current = _pendingAttachments.value
        _pendingAttachments.value = emptyList()
        viewModelScope.launch { current.forEach { attachmentStore.delete(it) } }
    }

    fun clearAttachmentNotice() {
        _attachmentNotice.value = null
    }

    fun send(input: String) {
        val text = input.trim()
        val pending = _pendingAttachments.value
        val thinkingOn = _thinkingEnabled.value
        // 光图没字也是一次有效提问（「这是什么？」不必打字），所以不能只判 text
        if ((text.isEmpty() && pending.isEmpty()) || _busy.value) return
        _error.value = null
        _attachmentNotice.value = null
        _busy.value = true
        _pendingAttachments.value = emptyList()
        // 上一条如果还冻着（用户翻上去看了），缓冲里可能还有没发布的正文。
        // 必须先绕过冻结落账再清缓冲，否则那截正文就此消失 ——
        // 存档里是全的、气泡里是短的，同一段话两个样子。
        commitStreamBuffer()
        resetStreamBuffer()
        _messages.update {
            it + UiChatMessage(ChatRole.USER, text, attachments = pending) +
                UiChatMessage(ChatRole.ASSISTANT, "", streaming = true)
        }
        persistCurrent()
        val startedAt = System.currentTimeMillis()
        streamJob = viewModelScope.launch {
            var providerName: String? = null
            var modelName: String? = null
            try {
                val settings = settingsStore.settings.first()
                val apiKey = apiKeyStore.read()
                    ?: throw IllegalStateException("请先在模型设置中保存 API Key")
                // 灵感助手强制带思考：不受全局“关闭思考模式”影响，
                // 否则服务端根本不返回 reasoning_content，无从展示
                val config = LLMConnectionConfig(
                    baseUrl = settings.baseUrl.trim(),
                    apiKey = apiKey,
                    model = settings.model.trim(),
                    capabilities = ProviderCapabilities(
                        supportsStreaming = true,
                        supportsJsonObject = false,
                        supportsUsageInStream = false
                    ),
                    // 关掉思考时要**明确告诉服务商别思考**（disableThinking）。
                    // 只把 includeReasoning 设为 false 是不够的：不少模型照样先
                    // thinking 很久，只是不把过程返回给你，表现就是「关了还是慢」。
                    disableThinking = !thinkingOn
                )
                providerName = settings.providerName
                modelName = settings.model
                val history = _messages.value
                    .dropLast(1)
                    .filter { it.text.isNotBlank() || it.attachments.isNotEmpty() }
                // 请求每轮都重发整段会话。图片如果全带，几轮之后请求体就是几 MB，
                // 网关上限普遍 4–10 MB，超了只回一个没有信息量的 invalid request。
                // 所以只带最近若干条消息的附件，更早的图明确告诉用户没带。
                val attachmentCutoff = (history.size - ATTACHMENT_HISTORY_MESSAGES).coerceAtLeast(0)
                var omittedAttachments = 0
                val messages = history.mapIndexed { index, message ->
                    val include = index >= attachmentCutoff
                    if (!include) omittedAttachments += message.attachments.size
                    val wire = if (include) {
                        message.attachments.mapNotNull { attachmentStore.toChatAttachment(it) }
                    } else {
                        emptyList()
                    }
                    if (message.attachments.isNotEmpty() && wire.size < message.attachments.size) {
                        omittedAttachments += message.attachments.size - wire.size
                    }
                    ChatMessage(message.role, message.text, wire)
                }
                if (omittedAttachments > 0) {
                    _attachmentNotice.value =
                        "更早的 $omittedAttachments 个附件没有包含在这次请求里（历史太长时会自动省略）"
                }
                val request = ChatRequest(
                    messages = listOf(ChatMessage(ChatRole.SYSTEM, INSPIRATION_SYSTEM_PROMPT)) + messages,
                    config = config,
                    options = ChatOptions(
                        outputTokenBudget = 4_096,
                        requestId = "chat-${System.currentTimeMillis()}",
                        stream = true,
                        includeReasoning = thinkingOn
                    )
                )
                var received = false
                var streamUsage: com.novelforge.app.domain.model.LlmUsage? = null
                client.streamChat(request).collect { event ->
                    when (event) {
                        is StreamEvent.Reasoning -> {
                            received = true
                            appendStream(reasoning = event.text)
                        }
                        is StreamEvent.Delta -> {
                            received = true
                            appendStream(text = event.text)
                        }
                        is StreamEvent.Usage -> streamUsage = event.usage
                        is StreamEvent.Finished -> Unit
                        else -> Unit
                    }
                }
                recordCall(settings.providerName, settings.model, startedAt, streamUsage, received)
                if (!received) {
                    replaceLast { m ->
                        if (m.text.isEmpty()) m.copy(text = "（模型没有返回内容，可重试）") else m
                    }
                }
            } catch (ce: CancellationException) {
                // 用户点了停止：把缓冲的增量落账，空响应则标"已停止"，保留已生成的部分
                flushPending()
                replaceLast { m ->
                    if (m.text.isBlank() && m.reasoning.isBlank()) {
                        m.copy(text = "（已停止）")
                    } else {
                        m
                    }
                }
                throw ce
            } catch (e: Throwable) {
                recordCall(providerName, modelName, startedAt, null, false)
                _error.value = e.message ?: "请求失败"
                replaceLast { m ->
                    if (m.text.isEmpty()) {
                        m.copy(text = "（生成失败：${e.message ?: "未知错误"}）")
                    } else {
                        m
                    }
                }
            } finally {
                _busy.value = false
                streamJob = null
                // 结束/取消/失败都要无视节流补齐一次，否则最后几十毫秒的 token 全丢
                publishStream(force = true)
                replaceLast { m -> m.copy(streaming = false) }
                persistCurrent()
            }
        }
    }

    /** 对话调用也入账本（llm_calls）：失败/中断也记一条，token 拿不到就留空 */
    private fun recordCall(
        providerName: String?,
        modelName: String?,
        startedAt: Long,
        usage: com.novelforge.app.domain.model.LlmUsage?,
        success: Boolean
    ) {
        viewModelScope.launch {
            runCatching {
                llmCallRepository.save(
                    com.novelforge.app.domain.model.LlmCall(
                        id = java.util.UUID.randomUUID().toString(),
                        // 记账跟着作用域走：有书上下文时能按书筛出这次灵感对话
                        projectId = _projectScope.value.orEmpty(),
                        jobId = "chat-${System.currentTimeMillis()}",
                        purpose = com.novelforge.app.domain.model.GenerationPurpose.CHAT,
                        provider = providerName?.takeIf { it.isNotBlank() } ?: "未知",
                        model = modelName?.takeIf { it.isNotBlank() } ?: "未知",
                        usage = usage ?: com.novelforge.app.domain.model.LlmUsage(),
                        durationMs = System.currentTimeMillis() - startedAt,
                        success = success,
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    /** 清空当前对话（含其历史记录） */
    fun clear() {
        resetStreamBuffer()
        _messages.value = emptyList()
        _error.value = null
        val id = _activeId.value
        if (id.isNotEmpty()) {
            viewModelScope.launch { historyStore.delete(id, _projectScope.value) }
        }
    }

    fun newConversation() {
        if (_busy.value) return
        resetStreamBuffer()
        _messages.value = emptyList()
        _error.value = null
        _activeId.value = newConversationId()
    }

    private val _thinkingEnabled = MutableStateFlow(true)
    val thinkingEnabled: StateFlow<Boolean> = _thinkingEnabled.asStateFlow()

    /**
     * 切换「显示思考过程」。
     *
     * 关掉时两件事一起做：不给模型要 reasoning_content，同时明确告诉服务商别思考。
     * 只做前者不够 —— 有些模型照样先 thinking 很久，只是不返回给你，
     * 表现出来就是「关了还是慢」。请求上带 disableThinking 才是真关。
     */
    fun setThinking(enabled: Boolean) {
        _thinkingEnabled.value = enabled
        viewModelScope.launch {
            runCatching {
                val id = _activeId.value
                if (id.isEmpty()) return@launch
                historyStore.updateThinking(id, _projectScope.value, enabled)
            }
        }
    }

    fun openConversation(id: String) {
        if (_busy.value || id == _activeId.value) return
        val convo = _conversations.value.firstOrNull { it.id == id } ?: return
        resetStreamBuffer()
        _error.value = null
        _activeId.value = id
        // 思考开关是**按会话**记的：换一段对话就该用那段自己的设置
        _thinkingEnabled.value = convo.thinkingEnabled
        _messages.value = convo.messages.map { m -> m.toUiMessage() }
    }

    fun deleteConversation(id: String) {
        if (_busy.value) return
        viewModelScope.launch {
            historyStore.delete(id, _projectScope.value)
            if (id == _activeId.value) {
                _messages.value = emptyList()
                _activeId.value = newConversationId()
            }
            collectAttachmentGarbage()
        }
    }

    /**
     * 清掉不再被任何会话引用的附件文件。
     * 会话被删、消息被 trim 到只剩 40 条之后，孤儿图会一直堆着 ——
     * 一次多图对话就能塞进几十 MB，而它们再也不会被读到。
     */
    private suspend fun collectAttachmentGarbage() {
        runCatching {
            val referenced = historyStore.conversationsIn(_projectScope.value).first()
                .flatMap { conversation -> conversation.messages }
                .flatMap { message -> message.attachments }
                .map { it.path } + _pendingAttachments.value.map { it.path }
            attachmentStore.collectGarbage(referenced)
        }
    }

    override fun onCleared() {
        // ViewModel 消失时把还没发出去的附件清掉：它们永远不会进任何消息
        clearPendingAttachments()
        super.onCleared()
    }

    class Factory(
        private val settingsStore: AppSettingsStore,
        private val apiKeyStore: ApiKeyStore,
        private val client: OpenAiCompatibleClient,
        private val historyStore: ChatHistoryStore,
        private val llmCallRepository: com.novelforge.app.domain.repository.LlmCallRepository,
        private val attachmentStore: com.novelforge.app.data.chat.ChatAttachmentStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(
            settingsStore,
            apiKeyStore,
            client,
            historyStore,
            llmCallRepository,
            attachmentStore
        ) as T
    }
}

/**
 * 历史里最多带多少条消息的附件。
 *
 * 请求每轮重发整段会话，图片全带的话几轮之后请求体就是几 MB，
 * 而 OpenAI 兼容网关的请求体上限普遍在 4–10 MB，超了只回一个
 * 没有信息量的 invalid request。留最近 4 条既能维持"你刚发的那张图我还记得"，
 * 又把最坏情况钉住。省略了多少会在界面上明说，不静默。
 */
private const val ATTACHMENT_HISTORY_MESSAGES = 4

/**
 * 发布节流：按正文长度自适应，而不是固定 20Hz。
 *
 * 每发布一次，Compose 要给最后一条重排一遍 —— 换行、CJK 断字、整段
 * MultiParagraph 重建。**解析本身不贵**（实测整篇 5000 字只要 0.1-0.3ms，
 * 见 StreamingParseBenchmarkTest），真正贵的是排版，而排版开销随正文变长而变大。
 * 固定 50ms 在长输出上就是每帧重排几千字，模型越快掉得越狠。
 *
 * 但也不能一刀切降到 4Hz —— 短回答就该保持跟手。所以给一条随长度增长的
 * 间隔：几百字以内仍是 50ms（20Hz，跟手），到几千字自动退到 ~210ms（~5Hz），
 * 一次多吐几十个字，视觉上仍是连续的。
 *
 * 250ms 是上限。再慢就明显能看出"一跳一跳"了。
 */
internal fun streamPublishIntervalMs(chars: Int): Long = when {
    chars <= 400 -> 50L
    else -> (50L + chars / 25L).coerceAtMost(250L)
}

private fun StoredChatMessage.toUiMessage(): UiChatMessage = UiChatMessage(
    role = if (role == "user") ChatRole.USER else ChatRole.ASSISTANT,
    text = text,
    reasoning = reasoning,
    attachments = attachments
)

private val UserBubbleShape = RoundedCornerShape(20.dp, 6.dp, 20.dp, 20.dp)
private val AssistantBubbleShape = RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp)

/** 思考链默认只铺这几行，超出部分给省略号 + "展开全部"。 */
private const val REASONING_PREVIEW_LINES = 8

/** 短思考直接全展示，只有超长才需要额外的展开档位。 */
private const val REASONING_PREVIEW_CHARS = 300

private val chatTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

/**
 * 一行附件。图片显示缩略图，文档显示文件名。
 *
 * 缩略图**按需在 IO 线程解码**并按显示尺寸采样：一张 1568px 的图按原尺寸塞进气泡
 * 就是 1.5 MB 乘以列表长度，而在组合期解会直接卡住滚动。
 * 文件已经被删（清理过、或历史从别处导入）时只显示占位，不崩。
 * onRemove 非空时右上角有删除键；用户气泡里传 null —— 已经发出去的消息不该能删附件。
 */
@Composable
private fun AttachmentStrip(
    attachments: List<StoredAttachment>,
    tint: Color,
    onRemove: ((StoredAttachment) -> Unit)?,
    modifier: Modifier = Modifier
) {
    if (attachments.isEmpty()) return
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        attachments.forEach { stored ->
            val isImage = stored.mediaType.startsWith("image/")
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(tint.copy(alpha = 0.10f))
                        .clickable(enabled = isImage) { if (isImage) openImage(context, stored.path) },
                    contentAlignment = Alignment.Center
                ) {
                    if (isImage) {
                        val bitmap by produceState<android.graphics.Bitmap?>(null, stored.path) {
                            value = withContext(Dispatchers.IO) {
                                runCatching { decodeAttachmentThumbnail(stored.path) }.getOrNull()
                            }
                        }
                        val image = bitmap
                        if (image != null) {
                            Image(
                                bitmap = image.asImageBitmap(),
                                contentDescription = "附件 ${stored.name}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text("▨", color = tint.copy(alpha = 0.6f))
                        }
                    } else {
                        Text("📄", fontSize = 20.sp)
                    }
                    if (onRemove != null) {
                        Text(
                            "✕",
                            color = tint,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .clip(CircleShape)
                                .background(tint.copy(alpha = 0.18f))
                                .clickable { onRemove(stored) }
                                .defaultMinSize(minWidth = 24.dp, minHeight = 24.dp)
                                .wrapContentSize(Alignment.Center)
                        )
                    }
                }
                if (!isImage) {
                    Text(
                        stored.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(72.dp)
                    )
                }
            }
        }
    }
}

/** 附件缩略图：按 128px 采样，RGB_565。 */
private fun decodeAttachmentThumbnail(path: String): android.graphics.Bitmap? {
    val file = java.io.File(path)
    if (!file.exists()) return null
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 128) sample *= 2
    return runCatching {
        android.graphics.BitmapFactory.decodeFile(
            path,
            android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }
        )
    }.getOrNull()
}

/**
 * 点开附件大图。
 *
 * 复用已有的那个 FileProvider（authority `${applicationId}.files`），
 * 并且必须落在它暴露的 `cache/exports/` 下面 —— 应用私有目录外部程序读不到，
 * 而 file_paths.xml 只声明了 exports/ 和 external-files/NovelForge/，
 * 写到别处会直接抛 IllegalArgumentException。
 */
private fun openImage(context: android.content.Context, path: String) {
    runCatching {
        val file = java.io.File(path)
        if (!file.exists()) return
        val shareDir = java.io.File(context.cacheDir, "exports").apply { if (!exists()) mkdirs() }
        val shared = java.io.File(shareDir, "share-${file.name}")
        if (!shared.exists() || shared.length() == 0L) file.copyTo(shared, overwrite = true)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            shared
        )
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(uri, "image/jpeg")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }
}

/** 取附件显示名，拿不到就用末段文件名。 */private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String {
    val fromProvider = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
    return fromProvider?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "附件"
}

/** 整段复制到剪贴板。Android 13+ 系统会自己弹「已复制」提示，不要重复弹。 */
private fun copyToClipboard(context: android.content.Context, label: String, text: String) {
    runCatching {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager ?: return
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    }
}

/**
 * 气泡下方的复制按钮行。
 *
 * 摆在正文下面的正常竖向流里，不叠在文字上 —— 叠上去的点击手势会和
 * SelectionContainer 的长按选词抢同一个按下事件，二选一。
 * 这里选了选词（系统工具栏自带复制/全选/分享），把"整段拿走"用按钮补上。
 */
@Composable
private fun CopyRow(text: String, reasoning: String, tint: androidx.compose.ui.graphics.Color? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val color = tint ?: MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (text.isNotBlank()) {
            CopyButton("复制", color) {
                copyToClipboard(context, "NovelForge", text)
            }
        }
        if (reasoning.isNotBlank()) {
            CopyButton("复制思考", color) {
                copyToClipboard(context, "NovelForge 思考过程", reasoning)
            }
        }
    }
}

@Composable
private fun CopyButton(label: String, tint: androidx.compose.ui.graphics.Color, onCopy: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onCopy)
            // 内边距必须在 clickable **之后**才是真的把点击区撑大。
            // 顺序反了的话 padding 落在手势区外面，命中区只剩文字本身：
            // "复制"两个字大约 28×16dp，远低于 48dp 最小点击区。
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    // 附件显示名、点开大图都要用
    val context = LocalContext.current
    val error by viewModel.error.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var historyOpen by remember { mutableStateOf(false) }
    var touching by remember { mutableStateOf(false) }
    var pickerMenuOpen by remember { mutableStateOf(false) }
    val thinkingOn by viewModel.thinkingEnabled.collectAsStateWithLifecycle()
    val pendingAttachments by viewModel.pendingAttachments.collectAsStateWithLifecycle()
    val attachmentNotice by viewModel.attachmentNotice.collectAsStateWithLifecycle()
    // 破坏性操作先记在这里，等用户点确认才真的动手
    var confirmClear by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<StoredConversation?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // reverseLayout 下要反着喂给 items；只算一次，别在每 50ms 的一次流式重组里重排
    val reversed = remember(messages) { messages.asReversed() }
    // 绝对底部 = reverseLayout 位置恰好 (0,0)。只要偏离一丁点（哪怕生成中的
    // 气泡只露出开头一点），立即冻结列表刷新——其他时候只听手势。
    val atBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }
    // 手指抬起来之后 fling 还在继续，那段时间 touching 已经是 false。
    // 不看 isScrollInProgress 就会在惯性滚动途中解冻，页面当场抽搐一下。
    val scrolling by remember { derivedStateOf { listState.isScrollInProgress } }
    LaunchedEffect(atBottom, scrolling, touching) {
        viewModel.setFrozen(!atBottom || scrolling || touching)
    }
    // 仅在用户发出新消息的瞬间跳到底部（令牌流期间绝不主动滚动）
    val lastRole = messages.lastOrNull()?.role
    LaunchedEffect(messages.size, lastRole) {
        if (messages.isNotEmpty() && lastRole == ChatRole.USER) {
            listState.scrollToItem(0)
        }
    }
    // 切换/恢复会话后跳到底部
    LaunchedEffect(activeId, messages.isNotEmpty()) {
        if (messages.isNotEmpty()) listState.scrollToItem(0)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PaperTopBar(
            title = "灵感助手",
            subtitle = "聊聊设定、段落和走向",
            onBack = onBack,
            trailing = {
                // 思考开关放在顶栏而不是设置页：它是「这段对话要不要我动脑子」，
                // 属于使用姿势而不是全局偏好。设置页那个「关闭思考模式」管的是
                // 大纲和正文生成，两者刻意分开。
                // 「清空」搬去了历史面板：顶栏塞三样东西会把标题挤成「灵感…」，
                // 而且破坏性操作本来就该和会话管理待在一起。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    // 合并成一个语义节点：不然读屏会把旁边的"思考"两个字当静态
                    // 文字念一遍，再单独念一次开关，听起来像两样东西。
                    // 合并后 Switch 自己贡献「开关 / 已开启」，父节点给名字。
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = "显示思考过程"
                        stateDescription = if (thinkingOn) "已开启" else "已关闭"
                    }
                ) {
                    Text(
                        "思考",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(
                        checked = thinkingOn,
                        onCheckedChange = viewModel::setThinking,
                        enabled = !busy
                    )
                }
                TextButton(onClick = { historyOpen = !historyOpen }) {
                    Text(if (historyOpen) "收起" else "历史")
                }
            }
        )
        if (!thinkingOn) {
            Text(
                "思考已关闭：回答更快、更省 token，但看不到推理过程。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 消息区（reverseLayout：最新消息贴底，增长时天然跟随）
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // 横向手势：左滑呼出对话历史，右滑关闭
                .pointerInput(Unit) {
                    val swipeThreshold = with(density) { 18.dp.toPx() }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var decided = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            // 长按选中文字后往下拖，选择手势会逐帧 consume 位移。
                            // 不看 consumed 的话，**单行**内拖选时 dx 一路过 18dp、
                            // dy 约等于 0，就会被误判成横滑、把历史抽屉弹开，
                            // 抽屉盖上来顺手把这次选词也毁了。1.4 倍的竖向压制
                            // 只能救跨行选择，救不了单行 —— 判据得是"别人吃没吃"。
                            // 这个 Box 是整块消息区的祖先，选择手势在更深处，
                            // 同一帧 Main pass 上轮到它时 consume 已经发生了。
                            if (change.isConsumed) break
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            if (!decided && abs(dx) > swipeThreshold && abs(dx) > abs(dy) * 1.4f) {
                                decided = true
                                if (dx < 0) historyOpen = true else historyOpen = false
                            }
                            if (decided) change.consume()
                        }
                    }
                }
        ) {
            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("灵感卡壳了？聊聊吧", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "剧情头脑风暴 · 人物设计 · 设定破局",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "思考过程实时可见 · 对话自动存入历史",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        // 手指按下即冻结流式刷新：按住=定住，抬手且落回底部才补齐
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(pass = PointerEventPass.Initial)
                                touching = true
                                do {
                                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                } while (event.changes.any { it.pressed })
                                touching = false
                            }
                        },
                    reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // key 必须是消息 id：按位置复用槽位时，流式刷新会把整棵子树重建，
                    // 清空/换会话后思考的折叠状态还会串到别的气泡上
                    items(reversed, key = { it.id }) { message ->
                        val isUser = message.role == ChatRole.USER
                        // SelectionContainer 只管选词，**绝对不要**在气泡上再装
                        // clickable / combinedClickable / pointerInput：
                        // 那几个都会 down.consume()，把外层选择手势的按键事件吃掉，
                        // 长按拖选就彻底废了（长按复制、全选、分享全没了）。
                        // 所以「整条复制」做成气泡下方的一行按钮，
                        // 它在正常竖向流里，不盖住任何文字。
                        SelectionContainer {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                            ) {
                                if (isUser) {
                                    // 默认的选中高亮是浅色半透明，压在 primary 深底上
                                    // 几乎看不出来 —— 用户按住字以为没反应，又按一次。
                                    // 换成 onPrimary（气泡文字色）做高亮色，深底浅字才看得见。
                                    val onPrimary = MaterialTheme.colorScheme.onPrimary
                                    CompositionLocalProvider(
                                        LocalTextSelectionColors provides TextSelectionColors(
                                            handleColor = onPrimary,
                                            backgroundColor = onPrimary.copy(alpha = 0.35f)
                                        )
                                    ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(0.82f)
                                            .clip(UserBubbleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                            .padding(horizontal = 14.dp, vertical = 10.dp)
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            // 用户自己的气泡要显示图：不然发完就不知道发出去的是哪张
                                            AttachmentStrip(
                                                attachments = message.attachments,
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                onRemove = null
                                            )
                                            if (message.text.isNotEmpty()) {
                                                // 用户气泡不传 onLinkClick：传了链接会染成
                                                // primary 色，压在 primary 底上就看不见了。
                                                // 不传时链接带真正的 LinkAnnotation，朗读会说"链接"。
                                                ChatRichText(
                                                    text = message.text,
                                                    color = MaterialTheme.colorScheme.onPrimary
                                                )
                                            }
                                            // 自己的提问也常要复制走（改一改重问、贴到别处）
                                            if (message.text.isNotBlank()) {
                                                CopyRow(
                                                    text = message.text,
                                                    reasoning = "",
                                                    tint = onPrimary.copy(alpha = 0.75f)
                                                )
                                            }
                                        }
                                    }
                                    }
                                } else {
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth(0.88f)
                                            .border(
                                                1.dp,
                                                MaterialTheme.colorScheme.outlineVariant,
                                                AssistantBubbleShape
                                            ),
                                        shape = AssistantBubbleShape,
                                        color = MaterialTheme.colorScheme.surface
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                        ) {
                                            // 两个状态都挂在消息 id 上，不能按位置记
                                            var reasoningOpen by remember(message.id) {
                                                mutableStateOf(message.streaming)
                                            }
                                            var reasoningFull by remember(message.id) {
                                                mutableStateOf(false)
                                            }
                                            val reasoningLong =
                                                message.reasoning.length > REASONING_PREVIEW_CHARS
                                            if (message.reasoning.isNotBlank()) {
                                                // 这两个折叠标签是气泡里唯一"故意不让选词"的地方：
                                                // 它们自己就是手势控件，选中它们没有意义。
                                                // 但也正因为是控件，命中区得补到 48dp ——
                                                // labelSmall 一行只有 16dp 高，长得跟普通文字一样，
                                                // 会被当成正文去长按选词，然后发现点不动。
                                                Text(
                                                    if (message.streaming) "思考中…" else "已完成思考 · 点击展开/收起",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier
                                                        .padding(bottom = 4.dp)
                                                        .toggleable(
                                                            value = reasoningOpen,
                                                            onValueChange = { reasoningOpen = it },
                                                            role = Role.Button
                                                        )
                                                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                                        .semantics {
                                                            stateDescription =
                                                                if (reasoningOpen) "已展开" else "已收起"
                                                        }
                                                )
                                                if (reasoningOpen) {
                                                    // 思考链动辄几千字：默认只给几行，
                                                    // 再给一个"展开全部"，否则一个气泡能撑爆整屏
                                                    Text(
                                                        message.reasoning,
                                                        style = MaterialTheme.typography.bodySmall.copy(
                                                            lineHeight = 18.sp
                                                        ),
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                                        maxLines = if (reasoningLong && !reasoningFull) {
                                                            REASONING_PREVIEW_LINES
                                                        } else {
                                                            Int.MAX_VALUE
                                                        },
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.padding(bottom = 8.dp)
                                                    )
                                                    if (reasoningLong) {
                                                        Text(
                                                            if (reasoningFull) "收起思考 ↑" else "展开全部 ↓",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier
                                                                .padding(bottom = 8.dp)
                                                                .toggleable(
                                                                    value = reasoningFull,
                                                                    onValueChange = { reasoningFull = it },
                                                                    // 和上面那个保持一致：这是展开/收起，
                                                                    // 不是开关，说成"按钮 + 已展开"更贴切
                                                                    role = Role.Button
                                                                )
                                                                .defaultMinSize(
                                                                    minWidth = 48.dp,
                                                                    minHeight = 48.dp
                                                                )
                                                                .semantics {
                                                                    stateDescription =
                                                                        if (reasoningFull) "已展开" else "已收起"
                                                                }
                                                        )
                                                    }
                                                }
                                            }
                                            if (message.text.isNotEmpty()) {
                                                // markdown 表格 / LaTeX / 粗体这些以前全是原始文本。
                                                // 这里是唯一一处把模型回复渲染成富文本的地方，
                                                // 思考链保持纯文本 —— 它是过程日志，不需要排版。
                                                ChatRichText(
                                                    text = message.text,
                                                    onLinkClick = { url ->
                                                        runCatching {
                                                            val intent = android.content.Intent(
                                                                android.content.Intent.ACTION_VIEW,
                                                                android.net.Uri.parse(url)
                                                            ).addFlags(
                                                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                                            )
                                                            context.startActivity(intent)
                                                        }
                                                    }
                                                )
                                            } else if (message.streaming && message.reasoning.isBlank()) {
                                                Text(
                                                    "…",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }

                                            // 整条复制。放在正文**下面**而不是长按菜单里：
                                            // 长按菜单必须盖在气泡上，会把选择手势的按键事件吃掉，
                                            // 而这个 app 的产出是几千字的方案和推理，
                                            // 读者想要的是"整段拿走"，长按选词反而给不了。
                                            // 流式过程中不给按钮：半句话复制出来没意义。
                                            if (!message.streaming &&
                                                (message.text.isNotBlank() || message.reasoning.isNotBlank())
                                            ) {
                                                CopyRow(
                                                    text = message.text,
                                                    reasoning = message.reasoning
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (!atBottom) {
                    Surface(
                        onClick = {
                            scope.launch { listState.scrollToItem(0) }
                        },
                        shape = CircleShape,
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                    ) {
                        Text(
                            "↓ 回到最新",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            // 对话历史面板：从右侧滑出
            if (historyOpen) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                        .clickable { historyOpen = false }
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = historyOpen,
                enter = slideInHorizontally(initialOffsetX = { it }),
                exit = slideOutHorizontally(targetOffsetX = { it }),
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.78f),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "对话历史",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            // "✕" 对读屏软件只是个字符，必须给它一个可读的标签
                            TextButton(
                                onClick = { historyOpen = false },
                                modifier = Modifier
                                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                    .semantics {
                                        contentDescription = "关闭对话历史"
                                        role = Role.Button
                                    }
                            ) { Text("✕") }
                        }
                        PaperButton(
                            text = "＋ 添加新对话",
                            onClick = {
                                viewModel.newConversation()
                                historyOpen = false
                            },
                            accent = true,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy
                        )
                        // 清空当前对话：破坏性、不可撤销，所以从顶栏挪到这里，
                        // 而且要点两下（先按按钮，再在弹窗里确认）
                        PaperButton(
                            text = "清空当前对话",
                            onClick = { confirmClear = true },
                            accent = false,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy && messages.isNotEmpty()
                        )
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (conversations.isEmpty()) {
                                item {
                                    Text(
                                        "还没有历史对话",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            items(conversations, key = { it.id }) { convo ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(PaperShape)
                                        .background(
                                            if (convo.id == activeId) {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            } else {
                                                Color.Transparent
                                            }
                                        )
                                        .clickable {
                                            viewModel.openConversation(convo.id)
                                            historyOpen = false
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            convo.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "${chatTimeFormat.format(Date(convo.updatedAt))} · ${convo.messages.size} 条",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    // 删除是不可撤销的：先落到 deleteTarget，等用户点确认
                                    Text(
                                        "✕",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                            .wrapContentSize(Alignment.Center)
                                            .clip(CircleShape)
                                            .clickable { deleteTarget = convo }
                                            .semantics {
                                                contentDescription = "删除对话「${convo.title}」"
                                                role = Role.Button
                                            }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        error?.let {
            Text(
                "提示：$it",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        attachmentNotice?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // 待发送的附件：可删、可点开看大图
        if (pendingAttachments.isNotEmpty()) {
            AttachmentStrip(
                attachments = pendingAttachments,
                tint = MaterialTheme.colorScheme.onSurface,
                onRemove = viewModel::removePendingAttachment
            )
        }

        // 输入行：加附件按钮 + 纸质输入框 + 圆形发送键
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // 相册 / 文件。用 PhotoPicker 而不是 ACTION_GET_CONTENT：
            // 前者不需要任何存储权限，也不给用户一份「读取你所有文件」的能力。
            val photoLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.PickVisualMedia()
            ) { uri ->
                if (uri != null) {
                    viewModel.addImageAttachment(uri, queryDisplayName(context, uri))
                }
            }
            val fileLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    viewModel.addDocumentAttachment(uri, queryDisplayName(context, uri))
                }
            }
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = !busy) {
                        pickerMenuOpen = true
                    }
                    .semantics {
                        contentDescription = "添加图片或文件"
                        role = Role.Button
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("＋", style = MaterialTheme.typography.titleMedium)
            }
            if (pickerMenuOpen) {
                AlertDialog(
                    onDismissRequest = { pickerMenuOpen = false },
                    title = { Text("加个附件") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = {
                                    pickerMenuOpen = false
                                    photoLauncher.launch(
                                        androidx.activity.result.PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("图片（会压缩后发送）") }
                            TextButton(
                                onClick = {
                                    pickerMenuOpen = false
                                    fileLauncher.launch(
                                        arrayOf(
                                            "text/plain",
                                            "text/markdown",
                                            "text/csv",
                                            "application/json",
                                            "application/xml"
                                        )
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("文本文件（txt / md / csv / json）") }
                            Text(
                                "图片和文本会一起发给模型。模型不支持图片时会直接报错。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { pickerMenuOpen = false }) { Text("取消") }
                    }
                )
            }
            PaperSurface(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = {
                        Text(
                            if (busy) "生成中，可以先想好下一句…" else "聊聊你的故事…",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )
            }
            // 二选一：生成中=停止键，空闲=发送键
            // 光图没字也算一次有效提问，所以附件在时不能把发送键置灰
            val canSend = !busy && (input.isNotBlank() || pendingAttachments.isNotEmpty())
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(
                        if (busy) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                    .clickable(enabled = if (busy) true else canSend) {
                        if (busy) {
                            viewModel.stop()
                        } else {
                            val text = input
                            input = ""
                            viewModel.send(text)
                        }
                    }
                    // "■/➤" 本身读不出来：这个键到底是"发出去"还是"停下来"必须念出来
                    .semantics {
                        contentDescription = if (busy) "停止生成" else "发送"
                        role = Role.Button
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (busy) "■" else "➤",
                    color = MaterialTheme.colorScheme.onError.copy(
                        alpha = if (busy || canSend) 1f else 0.45f
                    ),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = if (busy) 16.sp else 18.sp
                    )
                )
            }
        }

        // 破坏性操作二次确认：清空会连历史记录一起删光，且不可撤销
        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("清空当前对话？") },
                text = { Text("这条对话的全部消息和它的历史记录都会被删除，且无法恢复。") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmClear = false
                        viewModel.clear()
                    }) { Text("清空", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClear = false }) { Text("取消") }
                }
            )
        }

        deleteTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("删除这条对话？") },
                text = { Text("「${target.title}」的消息和历史记录都会被删除，且无法恢复。") },
                confirmButton = {
                    TextButton(onClick = {
                        deleteTarget = null
                        viewModel.deleteConversation(target.id)
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) { Text("取消") }
                }
            )
        }
    }
}
