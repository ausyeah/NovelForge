package com.novelforge.app.presentation.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novelforge.app.domain.model.Project

private enum class ProjectDialog {
    MENU,
    RENAME,
    DELETE
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    projects: List<Project>,
    onCreateProject: () -> Unit,
    onOpenProject: (Project) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenExports: () -> Unit,
    onOpenLibrary: () -> Unit,
    operationError: String?,
    onRenameProject: (String, String, () -> Unit) -> Unit,
    onDeleteProject: (String, () -> Unit) -> Unit,
    onClearOperationError: () -> Unit
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
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("NovelForge", style = MaterialTheme.typography.headlineLarge)
        Text(
            text = "把一个想法，慢慢写成一部小说。",
            modifier = Modifier.padding(top = 12.dp, bottom = 16.dp)
        )
        Button(onClick = onCreateProject, modifier = Modifier.fillMaxWidth()) {
            Text("新建项目")
        }
        Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text("模型设置")
        }
        Button(onClick = onOpenExports, modifier = Modifier.fillMaxWidth()) {
            Text("导出文件")
        }
        Button(onClick = onOpenLibrary, modifier = Modifier.fillMaxWidth()) {
            Text("我的书架")
        }
        if (projects.isNotEmpty()) {
            Text(
                "我的项目",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
            )
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(projects, key = { it.id }) { project ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .combinedClickable(
                                onClick = { onOpenProject(project) },
                                onLongClick = {
                                    selectedProject = project
                                    dialog = ProjectDialog.MENU
                                }
                            )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(project.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "状态：${project.status}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
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
                    androidx.compose.foundation.layout.Row {
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
