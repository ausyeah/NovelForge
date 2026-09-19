package com.novelforge.app.presentation.chat

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novelforge.app.infrastructure.llm.ChatRole
import com.novelforge.app.ui.theme.GlassSurface

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

    // 新内容到达时自动滚动到底部（正文与思考都会触发）
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
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        message.reasoning,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
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
            Text("提示：$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
                    placeholder = { Text(if (busy) "生成中…" else "问点什么…", fontSize = 15.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    minLines = 1,
                    maxLines = 4,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )
            }
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(SendGradient)
                    .let { m ->
                        if (busy || input.isBlank()) m else m.clickableBordered(onClick = {
                            val text = input
                            input = ""
                            viewModel.send(text)
                        })
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "➤",
                    color = Color.White.copy(alpha = if (busy || input.isBlank()) 0.5f else 1f),
                    fontSize = 18.sp
                )
            }
        }
    }
}

private fun Modifier.clickableBordered(onClick: () -> Unit): Modifier =
    this.then(
        Modifier.then(
            androidx.compose.ui.Modifier
        )
    ).let { base ->
        base.then(
            Modifier
        )
    }.then(
        Modifier
    ).clickableInternal(onClick)

private fun Modifier.clickableInternal(onClick: () -> Unit): Modifier =
    this.then(Modifier)
        .then(
            Modifier
        )
        .then(Modifier.clickableNoop(onClick))

private fun Modifier.clickableNoop(onClick: () -> Unit): Modifier =
    this.then(
        Modifier
    ).then(
        androidx.compose.ui.Modifier.clickableFake(onClick)
    )

private fun Modifier.clickableFake(onClick: () -> Unit): Modifier = this
