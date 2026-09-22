package com.novelforge.app.presentation.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novelforge.app.agent.AgentStep
import com.novelforge.app.ui.theme.PaperButton
import com.novelforge.app.ui.theme.PaperSurface

@Composable
fun AgentAssistCard(
    steps: List<AgentStep>,
    busy: Boolean,
    error: String?,
    onAsk: (String) -> Unit,
    onContinue: () -> Unit
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    val canContinue = steps.lastOrNull()?.kind == "tool" && !busy
    PaperSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (steps.isEmpty()) "查书助手" else "查书助手 · ${steps.size} 步",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { open = !open }) { Text(if (open) "收起" else "展开") }
            }
            if (open) {
                Text(
                    "最多查 6 步。每一步都会记下，划掉应用后可以继续。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                steps.takeLast(8).forEach { step ->
                    Text("${step.title}：${step.detail.take(180)}", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("想查什么") },
                    singleLine = true
                )
                PaperButton(
                    if (busy) "正在查…" else "开始查",
                    onClick = {
                        onAsk(input)
                    },
                    enabled = !busy && input.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    accent = true
                )
                if (canContinue) {
                    PaperButton("继续上次", onContinue, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
