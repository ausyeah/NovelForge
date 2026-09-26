package com.novelforge.app.presentation.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novelforge.app.presentation.common.PaperTopBar
import com.novelforge.app.ui.theme.PaperButton

@Composable
fun CreateProjectScreen(
    onCreate: (String) -> Unit,
    onCancel: () -> Unit
) {
    var title by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PaperTopBar(title = "新建小说", subtitle = "先填写名称，题材可在下一步设置", onBack = onCancel)
        Text(
            "名称可在创建后修改。题材、主角与核心冲突不在此步骤填写。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("小说名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        PaperButton(
            "下一步",
            onClick = { onCreate(title) },
            enabled = title.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            accent = true
        )
    }
}
