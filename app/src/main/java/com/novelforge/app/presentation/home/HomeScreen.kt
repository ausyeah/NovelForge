package com.novelforge.app.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novelforge.app.ui.theme.PaperButton

@Composable
fun HomeScreen(
    projectCount: Int,
    onCreateProject: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenExports: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenChat: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 36.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("NovelForge", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(
            text = "把一个想法，慢慢写成一部小说。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp, bottom = 28.dp)
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PaperButton(
                "新建项目",
                onCreateProject,
                modifier = Modifier.fillMaxWidth(),
                accent = true
            )
            PaperButton(
                if (projectCount > 0) "我的项目（$projectCount）" else "我的项目",
                onOpenProjects,
                modifier = Modifier.fillMaxWidth()
            )
            PaperButton("我的书架", onOpenLibrary, modifier = Modifier.fillMaxWidth())
            PaperButton("导出文件", onOpenExports, modifier = Modifier.fillMaxWidth())
            PaperButton("小说灵感启发助手", onOpenChat, modifier = Modifier.fillMaxWidth())
            PaperButton("模型设置", onOpenSettings, modifier = Modifier.fillMaxWidth())
        }
    }
}
