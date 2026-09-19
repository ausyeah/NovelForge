package com.novelforge.app.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novelforge.app.data.security.ApiKeyStore
import com.novelforge.app.data.settings.AppSettingsStore
import com.novelforge.app.infrastructure.llm.ChatMessage
import com.novelforge.app.infrastructure.llm.ChatOptions
import com.novelforge.app.infrastructure.llm.ChatRequest
import com.novelforge.app.infrastructure.llm.ChatRole
import com.novelforge.app.infrastructure.llm.OpenAiCompatibleClient
import com.novelforge.app.infrastructure.llm.ProviderCapabilities
import com.novelforge.app.infrastructure.llm.LLMConnectionConfig
import com.novelforge.app.infrastructure.llm.StreamEvent
import com.novelforge.app.ui.theme.GlassSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiChatMessage(
    val role: ChatRole,
    val text: String,
    val reasoning: String = "",
    val streaming: Boolean = false
)

class ChatViewModel(
    private val settingsStore: AppSettingsStore,
    private val apiKeyStore: ApiKeyStore,
    private val client: OpenAiCompatibleClient
) : ViewModel() {
    private val _messages = MutableStateFlow<List<UiChatMessage>>(emptyList())
    val messages: StateFlow<List<UiChatMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun send(input: String) {
        val text = input.trim()
        if (text.isEmpty() || _busy.value) return
        _error.value = null
        _busy.value = true
        _messages.update {
            it + UiChatMessage(ChatRole.USER, text) +
                UiChatMessage(ChatRole.ASSISTANT, "", streaming = true)
        }
        viewModelScope.launch {
            try {
                val settings = settingsStore.settings.first()
                val apiKey = apiKeyStore.read()
                    ?: throw IllegalStateException("请先在模型设置中保存 API Key")
                // AI 助手强制带思考：不受全局“关闭思考模式”影响，
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
                    messages = listOf(
                        ChatMessage(
                            ChatRole.SYSTEM,
                            "你是 NovelForge 的创作助手，帮作者构思剧情、人物与设定，回答保持简洁实用。必须用中文。"
                        )
                    ) + history,
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
                            _messages.update { list ->
                                list.mapIndexed { index, m ->
                                    if (index == list.lastIndex) {
                                        m.copy(reasoning = m.reasoning + event.text)
                                    } else {
                                        m
                                    }
                                }
                            }
                        }
                        is StreamEvent.Delta -> {
                            received = true
                            _messages.update { list ->
                                list.mapIndexed { index, m ->
                                    if (index == list.lastIndex) {
                                        m.copy(text = m.text + event.text)
                                    } else {
                                        m
                                    }
                                }
                            }
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
                _messages.update { list ->
                    list.mapIndexed { index, m ->
                        if (index == list.lastIndex) m.copy(streaming = false) else m
                    }
                }
            }
        }
    }

    fun clear() {
        _messages.value = emptyList()
        _error.value = null
    }

    class Factory(
        private val settingsStore: AppSettingsStore,
        private val apiKeyStore: ApiKeyStore,
        private val client: OpenAiCompatibleClient
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(
            settingsStore,
            apiKeyStore,
            client
        ) as T
    }
}

private val SendGradient = Brush.linearGradient(
    colors = listOf(Color(0xFF8B6CF0), Color(0xFFA78BFA))
)

private val UserBubbleGradient = Brush.linearGradient(
    colors = listOf(Color(0xFF8B6CF0).copy(alpha = 0.92f), Color(0xFFB49CFB).copy(alpha = 0.88f))
)

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 正文或思考有新内容时自动滚动到底部
    LaunchedEffect(
        messages.size,
        messages.lastOrNull()?.text?.length,
        messages.lastOrNull()?.reasoning?.length
    ) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 顶栏：返回 · 标题 · 清空
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("〈 返回") }
            Text(
                "AI 助手",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = { viewModel.clear() }, enabled = messages.isNotEmpty()) {
                Text("清空")
            }
        }

        // 消息区
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("和模型自由对话", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "构思剧情 · 设计人物 · 头脑风暴\n思考过程实时可见",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            items(messages) { message ->
                val isUser = message.role == ChatRole.USER
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                ) {
                    if (isUser) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.82f)
                                .clip(RoundedCornerShape(20.dp, 6.dp, 20.dp, 20.dp))
                                .background(UserBubbleGradient)
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Text(
                                message.text,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = 22.sp
                            )
                        }
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxWidth(0.9f),
                            shape = RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                if (message.reasoning.isNotBlank()) {
                                    Text(
                                        if (message.streaming) "思考中…" else "思考过程",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 4.dp)
                                    )
                                    Text(
                                        message.reasoning,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                        lineHeight = 18.sp,
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    )
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

        error?.let {
            Text(
                "提示：$it",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // 输入行：玻璃输入框 + 圆形发送键
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            GlassSurface(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = {
                        Text(if (busy) "生成中…" else "问点什么…", fontSize = 15.sp)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
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
            val canSend = !busy && input.isNotBlank()
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(SendGradient)
                    .clickable(enabled = canSend) {
                        val text = input
                        input = ""
                        viewModel.send(text)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "➤",
                    color = Color.White.copy(alpha = if (canSend) 1f else 0.45f),
                    fontSize = 18.sp
                )
            }
        }
    }
}
