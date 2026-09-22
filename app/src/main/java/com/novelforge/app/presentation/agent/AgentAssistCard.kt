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
import androidx.compose.runtime.LaunchedEffect
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
    val toolCount = steps.count { it.kind == "tool" }
    val canContinue = steps.lastOrNull()?.kind == "tool" && !busy
    LaunchedEffect(busy, steps.size) {
        if (busy || steps.isNotEmpty()) open = true
    }
    PaperSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (toolCount == 0) "查书助手" else "查书助手 · $toolCount/6 步",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { open = !open }) { Text(if (open) "收起" else "展开") }
            }
            if (open) {
                Text(
                    "只查这本书，最多 6 步。每记下一步，重新打开后可以继续。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                steps.takeLast(8).forEach { step ->
                    Text(step.readable(), style = MaterialTheme.typography.bodySmall)
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("想查什么") },
                    singleLine = true,
                    enabled = !busy
                )
                PaperButton(
                    if (busy) "正在查…" else "开始查",
                    onClick = { onAsk(input) },
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

private fun AgentStep.readable(): String {
    val body = if (tool == "search_chapters") readableSearch(detail) else detail.take(180)
    return "$title：$body"
}

private fun readableSearch(detail: String): String {
    if (!detail.contains('|')) return detail.take(180)
    return detail.lines().joinToString("；") { line ->
        val parts = line.split('|', limit = 3)
        if (parts.size < 3) line else "「${parts[1]}」${parts[2]}"
    }.take(180)
}
