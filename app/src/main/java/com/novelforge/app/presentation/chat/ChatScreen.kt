package com.novelforge.app.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import com.novelforge.app.infrastructure.llm.StreamEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiChatMessage(
    val role: ChatRole,
    val text: String,
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
                val config = com.novelforge.app.infrastructure.llm.LLMConnectionConfig(
                    baseUrl = settings.baseUrl.trim(),
                    apiKey = apiKey,
                    model = settings.model.trim(),
                    capabilities = com.novelforge.app.infrastructure.llm.ProviderCapabilities(
                        supportsStreaming = true,
                        supportsJsonObject = false,
                        supportsUsageInStream = false
                    ),
                    disableThinking = settings.disableThinking
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
                        outputTokenBudget = 2_048,
                        requestId = "chat-${System.currentTimeMillis()}",
                        stream = true
                    )
                )
                var received = false
                client.streamChat(request).collect { event ->
                    when (event) {
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
                            if (index == list.lastIndex) m.copy(text = "（模型没有返回内容，可重试）")
                            else m
                        }
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Throwable) {
                _error.value = e.message ?: "请求失败"
                _messages.update { list ->
                    list.mapIndexed { index, m ->
                        if (index == list.lastIndex && m.streaming) {
                            m.copy(text = m.text.ifEmpty { "（生成失败：${e.message ?: "未知错误"}）" }, streaming = false)
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

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }

    fun submit() {
        val text = input
        input = ""
        viewModel.send(text)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("AI 助手", style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(onClick = { viewModel.clear() }, enabled = messages.isNotEmpty()) {
                Text("清空")
            }
        }
        Text(
            "和已配置的模型自由对话：构思剧情、查设定、头脑风暴。上下文仅在本次会话内。",
            style = MaterialTheme.typography.bodySmall
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (messages.isEmpty()) {
                items(listOf("试试：帮我想一个反转结局", "给主角设计一个隐藏身份", "这段大纲的节奏有什么问题")) { hint ->
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
            items(messages) { message ->
                val isUser = message.role == ChatRole.USER
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isUser) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                        shape = RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp
                        ),
                        modifier = Modifier.fillMaxWidth(0.86f)
                    ) {
                        Text(
                            message.text,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        error?.let { Text("提示：$it", color = MaterialTheme.colorScheme.error) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(if (busy) "生成中…" else "问点什么…") },
                modifier = Modifier.weight(1f),
                enabled = !busy,
                minLines = 1,
                maxLines = 4
            )
            Button(
                onClick = { submit() },
                enabled = !busy && input.isNotBlank(),
                modifier = Modifier.padding(bottom = 4.dp)
            ) { Text("发送") }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("返回主页")
        }
    }
}
