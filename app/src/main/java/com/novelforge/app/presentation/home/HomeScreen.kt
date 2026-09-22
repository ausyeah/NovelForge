package com.novelforge.app.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novelforge.app.domain.model.Project
import com.novelforge.app.domain.model.label
import com.novelforge.app.presentation.common.StatusChip
import com.novelforge.app.presentation.common.formatUpdatedAgo
import com.novelforge.app.ui.theme.PaperButton
import com.novelforge.app.ui.theme.PaperSurface

@Composable
fun HomeScreen(
    projects: List<Project>,
    onContinue: (Project) -> Unit,
    onCreateProject: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenExports: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenLedger: () -> Unit
) {
    val recent = projects.maxByOrNull { it.updatedAt }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "NOVELFORGE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.4.sp
            )
            Text(
                "把一个想法，写成一部小说。",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (projects.isEmpty()) "本地创作，模型密钥只留在这台手机上。"
                else "书架上有 ${projects.size} 本，从最近一本接着写。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (recent != null) {
            PaperSurface(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "继续写",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        recent.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip(recent.status.label())
                        Text(
                            formatUpdatedAgo(recent.updatedAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    PaperButton(
                        "打开这本书",
                        onClick = { onContinue(recent) },
                        modifier = Modifier.fillMaxWidth(),
                        accent = true
                    )
                }
            }
        }

        PaperButton(
            if (recent == null) "写第一本" else "新建一本",
            onCreateProject,
            modifier = Modifier.fillMaxWidth(),
            accent = recent == null
        )

        Text(
            "工作台",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val tools = listOf(
            HomeTool("全部项目", if (projects.isEmpty()) "还是空的" else "${projects.size} 本", onOpenProjects),
            HomeTool("书架", "阅读已写章节", onOpenLibrary),
            HomeTool("灵感助手", "聊设定和走向", onOpenChat),
            HomeTool("用量账本", "Token 与调用", onOpenLedger),
            HomeTool("备份导出", "JSON 与 TXT", onOpenExports),
            HomeTool("模型设置", "密钥与连接", onOpenSettings)
        )
        tools.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                row.forEach { tool ->
                    PaperSurface(
                        modifier = Modifier.weight(1f),
                        onClick = tool.onClick
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(tool.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                tool.caption,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class HomeTool(
    val title: String,
    val caption: String,
    val onClick: () -> Unit
)
