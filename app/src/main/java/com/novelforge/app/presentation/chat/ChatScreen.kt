package com.novelforge.app.presentation.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
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
import com.novelforge.app.data.chat.StoredChatMessage
import com.novelforge.app.data.chat.StoredConversation
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val id: String = newMessageId()
)

/** 消息 id：每个新气泡一个，copy() 会原样带过去。 */
private fun newMessageId(): String = "m-${System.nanoTime()}-${(1000..9999).random()}"

/** 流式正文的发布节流：20Hz。低于人眼阅读速度，但把每 token 一次的重组压到 1/20。 */
private const val PUBLISH_INTERVAL_MS = 50L

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
    private val llmCallRepository: com.novelforge.app.domain.repository.LlmCallRepository
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

    // 冻结策略：视口不在绝对底部（reverseLayout 的 (0,0)）或手指按住时，
    // 增量照常累积，但发布要等节流窗口/解冻，避免刷新和手势抢锚点。
    private var frozen = false

    /**
     * 增量只往 builder 里追加，不再每来一个 SSE token 就
     * `m.text + event.text` 复制一遍不断变长的整段正文（那是 O(n²)）。
     * 20Hz 的发布节流由 publishStream 控制。
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

    private fun resetStreamBuffer() {
        streamReasoning.setLength(0)
        streamText.setLength(0)
        lastPublishAt = 0L
    }

    /** 收到增量：先攒进 builder，再按 20Hz 节流发布 */
    private fun appendStream(reasoning: String = "", text: String = "") {
        if (reasoning.isNotEmpty()) streamReasoning.append(reasoning)
        if (text.isNotEmpty()) streamText.append(text)
        publishStream(force = false)
    }

    /**
     * 把 builder 里的内容拍快照发布到最后一条消息上。
     * force = true（解冻/流结束/取消/失败）时无视节流立刻落账，
     * 免得最后几个字卡在窗口里不显示。
     */
    private fun publishStream(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublishAt < PUBLISH_INTERVAL_MS) return
        if (streamReasoning.isEmpty() && streamText.isEmpty()) return
        lastPublishAt = now
        val reasoning = streamReasoning.toString()
        val text = streamText.toString()
        replaceLast { m -> m.copy(reasoning = reasoning, text = text) }
    }

    /**
     * 只替换最后一条消息对象：前面的实例按引用原样带走，
     * Compose 的 == 短路生效，命中缓存的子树不重组。
     * 写完的 20Hz 节流让这次 N 元素拷贝的开销可以忽略。
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
                } else {
                    _activeId.value = newConversationId()
                }
            }
        }
    }

    private fun newConversationId(): String =
        "c-${System.currentTimeMillis()}-${(1000..9999).random()}"

    private fun persistCurrent() {
        val id = _activeId.value
        if (id.isEmpty()) return
        val snapshot = _messages.value.filter { it.text.isNotBlank() || it.reasoning.isNotBlank() }
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
                    reasoning = m.reasoning
                )
            },
            // 落进当前作用域的桶：没有书上下文就是全局「灵感」桶
            projectId = _projectScope.value
        )
        viewModelScope.launch { runCatching { historyStore.save(conversation) } }
    }

    fun send(input: String) {
        val text = input.trim()
        if (text.isEmpty() || _busy.value) return
        _error.value = null
        _busy.value = true
        resetStreamBuffer()
        _messages.update {
            it + UiChatMessage(ChatRole.USER, text) +
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
                    disableThinking = false
                )
                providerName = settings.providerName
                modelName = settings.model
                val history = _messages.value
                    .dropLast(1)
                    .filter { it.text.isNotBlank() }
                    .map { ChatMessage(it.role, it.text) }
                val request = ChatRequest(
                    messages = listOf(ChatMessage(ChatRole.SYSTEM, INSPIRATION_SYSTEM_PROMPT)) + history,
                    config = config,
                    options = ChatOptions(
                        outputTokenBudget = 4_096,
                        requestId = "chat-${System.currentTimeMillis()}",
                        stream = true,
                        includeReasoning = true
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

    fun openConversation(id: String) {
        if (_busy.value || id == _activeId.value) return
        val convo = _conversations.value.firstOrNull { it.id == id } ?: return
        resetStreamBuffer()
        _error.value = null
        _activeId.value = id
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
        }
    }

    class Factory(
        private val settingsStore: AppSettingsStore,
        private val apiKeyStore: ApiKeyStore,
        private val client: OpenAiCompatibleClient,
        private val historyStore: ChatHistoryStore,
        private val llmCallRepository: com.novelforge.app.domain.repository.LlmCallRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(
            settingsStore,
            apiKeyStore,
            client,
            historyStore,
            llmCallRepository
        ) as T
    }
}

private fun StoredChatMessage.toUiMessage(): UiChatMessage = UiChatMessage(
    role = if (role == "user") ChatRole.USER else ChatRole.ASSISTANT,
    text = text,
    reasoning = reasoning
)

private val UserBubbleShape = RoundedCornerShape(20.dp, 6.dp, 20.dp, 20.dp)
private val AssistantBubbleShape = RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp)

/** 思考链默认只铺这几行，超出部分给省略号 + "展开全部"。 */
private const val REASONING_PREVIEW_LINES = 8

/** 短思考直接全展示，只有超长才需要额外的展开档位。 */
private const val REASONING_PREVIEW_CHARS = 300

private val chatTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var historyOpen by remember { mutableStateOf(false) }
    var touching by remember { mutableStateOf(false) }
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
    LaunchedEffect(atBottom, touching) {
        viewModel.setFrozen(!atBottom || touching)
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
                TextButton(onClick = { historyOpen = !historyOpen }) {
                    Text(if (historyOpen) "收起" else "历史")
                }
                TextButton(onClick = { confirmClear = true }, enabled = messages.isNotEmpty()) {
                    Text("清空")
                }
            }
        )

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
                        // 长按可选中复制模型生成的正文/思考片段
                        SelectionContainer {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                            ) {
                                if (isUser) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(0.82f)
                                            .clip(UserBubbleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                            .padding(horizontal = 14.dp, vertical = 10.dp)
                                    ) {
                                        Text(
                                            message.text,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                lineHeight = 22.sp
                                            )
                                        )
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
                                                                    onValueChange = { reasoningFull = it }
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
                                                Text(
                                                    message.text,
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        lineHeight = 23.sp
                                                    )
                                                )
                                            } else if (message.streaming && message.reasoning.isBlank()) {
                                                Text(
                                                    "…",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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

        // 输入行：纸质输入框 + 圆形发送键
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
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
            val canSend = !busy && input.isNotBlank()
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
