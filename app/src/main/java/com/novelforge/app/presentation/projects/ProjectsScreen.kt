package com.novelforge.app.presentation.projects

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novelforge.app.domain.model.Project
import com.novelforge.app.domain.model.label
import com.novelforge.app.presentation.common.PaperTopBar
import com.novelforge.app.presentation.common.StatusChip
import com.novelforge.app.presentation.common.formatUpdatedAgo
import com.novelforge.app.ui.theme.PaperButton
import com.novelforge.app.ui.theme.PaperSurface

private enum class ProjectDialog {
    MENU,
    RENAME,
    DELETE
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProjectsScreen(
    projects: List<Project>,
    operationError: String?,
    onOpenProject: (Project) -> Unit,
    onRenameProject: (String, String, () -> Unit) -> Unit,
    onDeleteProject: (String, () -> Unit) -> Unit,
    onClearOperationError: () -> Unit,
    onCreateProject: () -> Unit,
    onBack: () -> Unit
) {
    var selectedProject by remember { mutableStateOf<Project?>(null) }
    var dialog by remember { mutableStateOf<ProjectDialog?>(null) }
    var renameTitle by remember { mutableStateOf("") }

    fun closeDialog() {
        selectedProject = null
        dialog = null
        renameTitle = ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PaperTopBar(title = "全部项目", subtitle = "${projects.size} 本", onBack = onBack)

        if (projects.isEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("还没有作品", style = MaterialTheme.typography.titleMedium)
                Text(
                    "先起一个名字，题材和大纲可以下一步再定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 16.dp)
                )
                PaperButton("写第一本", onCreateProject, accent = true)
            }
        } else {
            Text(
                "点进作品继续写，长按可以重命名或删除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(projects, key = { it.id }) { project ->
                    PaperSurface(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onOpenProject(project) },
                                    onLongClick = {
                                        selectedProject = project
                                        dialog = ProjectDialog.MENU
                                    }
                                )
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(project.title, style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                StatusChip(project.status.label())
                                Text(
                                    formatUpdatedAgo(project.updatedAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            PaperButton("新建一本", onCreateProject, modifier = Modifier.fillMaxWidth(), accent = true)
        }
    }

    operationError?.let { message ->
        AlertDialog(
            onDismissRequest = onClearOperationError,
            title = { Text("操作失败") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onClearOperationError) { Text("知道了") }
            }
        )
    }

    val project = selectedProject
    when (dialog) {
        ProjectDialog.MENU -> if (project != null) {
            AlertDialog(
                onDismissRequest = ::closeDialog,
                title = { Text("项目操作") },
                text = { Text(project.title) },
                confirmButton = {
                    TextButton(onClick = {
                        renameTitle = project.title
                        dialog = ProjectDialog.RENAME
                    }) { Text("重命名") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { dialog = ProjectDialog.DELETE }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = ::closeDialog) { Text("取消") }
                    }
                }
            )
        }

        ProjectDialog.RENAME -> if (project != null) {
            AlertDialog(
                onDismissRequest = ::closeDialog,
                title = { Text("重命名项目") },
                text = {
                    OutlinedTextField(
                        value = renameTitle,
                        onValueChange = { renameTitle = it },
                        label = { Text("项目名称") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onRenameProject(project.id, renameTitle, ::closeDialog)
                        },
                        enabled = renameTitle.trim().isNotEmpty()
                    ) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = ::closeDialog) { Text("取消") }
                }
            )
        }

        ProjectDialog.DELETE -> if (project != null) {
            AlertDialog(
                onDismissRequest = ::closeDialog,
                title = { Text("删除项目？") },
                text = {
                    Text("将删除“${project.title}”及其大纲、章节和生成记录，删除后无法恢复。")
                },
                confirmButton = {
                    TextButton(onClick = {
                        onDeleteProject(project.id, ::closeDialog)
                    }) {
                        Text("确认删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = ::closeDialog) { Text("取消") }
                }
            )
        }

        null -> Unit
    }
}
