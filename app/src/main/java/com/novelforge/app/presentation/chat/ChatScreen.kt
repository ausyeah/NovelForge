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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val streaming: Boolean = false
)

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
    private val historyStore: ChatHistoryStore
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
    // 流式增量只进缓冲区不刷新列表；只有滑到最底端/抬手落回底部才一次性落账。
    // 这样列表内容在脱离底部时完全不变，布局不可能与手势抢锚点。
    private var frozen = false
    private val pendingReasoning = StringBuilder()
    private val pendingText = StringBuilder()
    private var streamJob: kotlinx.coroutines.Job? = null

    /** 生成中点"停止"：掐掉流式请求，已收到的部分保留 */
    fun stop() {
        streamJob?.cancel()
    }

    fun setFrozen(value: Boolean) {
        if (frozen == value) return
        frozen = value
        if (!frozen) flushPending()
    }

    private fun flushPending() {
        val r = pendingReasoning.toString()
        val t = pendingText.toString()
        pendingReasoning.setLength(0)
        pendingText.setLength(0)
        if (r.isEmpty() && t.isEmpty()) return
        appendToLast { m -> m.copy(reasoning = m.reasoning + r, text = m.text + t) }
    }

    private fun appendToLast(transform: (UiChatMessage) -> UiChatMessage) {
        _messages.update { list ->
            list.mapIndexed { index, m ->
                if (index == list.lastIndex) transform(m) else m
            }
        }
    }

    init {
        viewModelScope.launch {
            historyStore.conversations.collect { _conversations.value = it }
        }
        viewModelScope.launch {
            val latest = historyStore.conversations.first().firstOrNull { it.messages.isNotEmpty() }
            if (latest != null) {
                _activeId.value = latest.id
                _messages.value = latest.messages.map { m ->
                    UiChatMessage(
                        role = if (m.role == "user") ChatRole.USER else ChatRole.ASSISTANT,
                        text = m.text,
                        reasoning = m.reasoning
                    )
                }
            } else {
                _activeId.value = newConversationId()
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
            }
        )
        viewModelScope.launch { runCatching { historyStore.save(conversation) } }
    }

    fun send(input: String) {
        val text = input.trim()
        if (text.isEmpty() || _busy.value) return
        _error.value = null
        _busy.value = true
        _messages.update {
            it + UiChatMessage(ChatRole.USER, text) +
                UiChatMessage(ChatRole.ASSISTANT, "", streaming = true)
        }
        persistCurrent()
        streamJob = viewModelScope.launch {
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
                client.streamChat(request).collect { event ->
                    when (event) {
                        is StreamEvent.Reasoning -> {
                            received = true
                            if (frozen) pendingReasoning.append(event.text)
                            else appendToLast { m -> m.copy(reasoning = m.reasoning + event.text) }
                        }
                        is StreamEvent.Delta -> {
                            received = true
                            if (frozen) pendingText.append(event.text)
                            else appendToLast { m -> m.copy(text = m.text + event.text) }
                        }
                        is StreamEvent.Finished -> Unit
                        else -> Unit
                    }
                }
                if (!received) {
                    _messages.update { list ->
                        list.mapIndexed { index, m ->
                            if (index == list.lastIndex) {
                                m.copy(text = "（模型没有返回内容，可重试）")
                            } else {
                                m
                            }
                        }
                    }
                }
            } catch (ce: CancellationException) {
                // 用户点了停止：把缓冲的增量落账，空响应则标"已停止"，保留已生成的部分
                flushPending()
                _messages.update { list ->
                    list.mapIndexed { index, m ->
                        if (index == list.lastIndex && m.text.isBlank() && m.reasoning.isBlank()) {
                            m.copy(text = "（已停止）")
                        } else {
                            m
                        }
                    }
                }
                throw ce
            } catch (e: Throwable) {
                _error.value = e.message ?: "请求失败"
                _messages.update { list ->
                    list.mapIndexed { index, m ->
                        if (index == list.lastIndex && m.text.isEmpty()) {
                            m.copy(text = "（生成失败：${e.message ?: "未知错误"}）")
                        } else {
                            m
                        }
                    }
                }
            } finally {
                _busy.value = false
                streamJob = null
                _messages.update { list ->
                    list.mapIndexed { index, m ->
                        if (index == list.lastIndex) m.copy(streaming = false) else m
                    }
                }
                persistCurrent()
            }
        }
    }

    /** 清空当前对话（含其历史记录） */
    fun clear() {
        pendingReasoning.setLength(0)
        pendingText.setLength(0)
        _messages.value = emptyList()
        _error.value = null
        val id = _activeId.value
        if (id.isNotEmpty()) {
            viewModelScope.launch { historyStore.delete(id) }
        }
    }

    fun newConversation() {
        if (_busy.value) return
        pendingReasoning.setLength(0)
        pendingText.setLength(0)
        _messages.value = emptyList()
        _error.value = null
        _activeId.value = newConversationId()
    }

    fun openConversation(id: String) {
        if (_busy.value || id == _activeId.value) return
        val convo = _conversations.value.firstOrNull { it.id == id } ?: return
        pendingReasoning.setLength(0)
        pendingText.setLength(0)
        _error.value = null
        _activeId.value = id
        _messages.value = convo.messages.map { m ->
            UiChatMessage(
                role = if (m.role == "user") ChatRole.USER else ChatRole.ASSISTANT,
                text = m.text,
                reasoning = m.reasoning
            )
        }
    }

    fun deleteConversation(id: String) {
        if (_busy.value) return
        viewModelScope.launch {
            historyStore.delete(id)
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
        private val historyStore: ChatHistoryStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(
            settingsStore,
            apiKeyStore,
            client,
            historyStore
        ) as T
    }
}

private val UserBubbleShape = RoundedCornerShape(20.dp, 6.dp, 20.dp, 20.dp)
private val AssistantBubbleShape = RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp)

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
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
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
        // 顶栏：返回 · 标题 · 历史 · 清空
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("〈 返回") }
            Text(
                "小说灵感启发助手",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = { historyOpen = !historyOpen }) {
                Text("历史")
            }
            TextButton(onClick = { viewModel.clear() }, enabled = messages.isNotEmpty()) {
                Text("清空")
            }
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
                    items(messages.asReversed()) { message ->
                        val isUser = message.role == ChatRole.USER
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
                                        style = MaterialTheme.typography.bodyMedium,
                                        lineHeight = 22.sp
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
                                        var reasoningOpen by remember(message.reasoning) {
                                            mutableStateOf(message.streaming)
                                        }
                                        if (message.reasoning.isNotBlank()) {
                                            Text(
                                                if (message.streaming) "思考中…" else "已完成思考 · 点击展开/收起",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier
                                                    .padding(bottom = 4.dp)
                                                    .clickable { reasoningOpen = !reasoningOpen }
                                            )
                                            if (reasoningOpen) {
                                                Text(
                                                    message.reasoning,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                                    lineHeight = 18.sp,
                                                    modifier = Modifier.padding(bottom = 8.dp)
                                                )
                                            }
                                        }
                                        if (message.text.isNotEmpty()) {
                                            Text(
                                                message.text,
                                                style = MaterialTheme.typography.bodyMedium,
                                                lineHeight = 23.sp
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
                            TextButton(onClick = { historyOpen = false }) { Text("✕") }
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
                                    Text(
                                        "✕",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .padding(8.dp)
                                            .clickable { viewModel.deleteConversation(convo.id) }
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
                        Text(if (busy) "生成中，可以先想好下一句…" else "聊聊你的故事…", fontSize = 15.sp)
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
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (busy) "■" else "➤",
                    color = MaterialTheme.colorScheme.onError.copy(
                        alpha = if (busy || canSend) 1f else 0.45f
                    ),
                    fontSize = if (busy) 16.sp else 18.sp
                )
            }
        }
    }
}
