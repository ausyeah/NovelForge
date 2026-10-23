package com.novelforge.app.presentation.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novelforge.app.agent.AgentStep
import com.novelforge.app.ui.theme.PaperButton
import com.novelforge.app.ui.theme.PaperSurface

/**
 * 「问问这本书」的内容体。
 *
 * 它现在只出现在大纲页顶栏「更多」拉起的底部弹层里。这是个按需查询工具，
 * 不是写作主循环的一环：以前它常驻在大纲页顶部、在一个不能滚动的 Column 里，
 * 展开后能把「一键全自动生成全书」这个主按钮整个顶出屏幕，而且它偏偏在
 * 你真正开始写某一章的时候（detailOpen）消失 —— 主次正好是反的。
 */
@Composable
fun AgentAssistCard(
    steps: List<AgentStep>,
    busy: Boolean,
    error: String?,
    onAsk: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // 弹层已经是一次性的了，再套一层「展开/收起」只是多一次点击。
    // 用 remember 而不是 rememberSaveable：问题本身会随弹层一起丢弃，
    // 转屏时也没必要保留一个半截句子。
    var input by remember { mutableStateOf("") }
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("问问这本书", style = MaterialTheme.typography.titleSmall)
        Text(
            "每次提问会附带大纲与相关片段，不会发送全文。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (steps.isNotEmpty()) {
            PaperSurface(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    steps.forEach { step ->
                        Text(step.readable(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("输入你的问题") },
            singleLine = true,
            enabled = !busy
        )
        PaperButton(
            if (busy) "查询中…" else "提交提问",
            onClick = { onAsk(input) },
            enabled = !busy && input.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            accent = true
        )
    }
}

private fun AgentStep.readable(): String {
    val body = if (tool == "search_chapters") readableSearch(detail) else detail
    return "$title：$body"
}

private fun readableSearch(detail: String): String {
    if (!detail.contains('|')) return detail
    return detail.lines().joinToString("\n") { line ->
        val parts = line.split('|', limit = 3)
        if (parts.size < 3) line else "「${parts[1]}」${parts[2]}"
    }
}
