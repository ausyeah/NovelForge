package com.novelforge.app.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.novelforge.app.data.security.ApiKeyStore
import com.novelforge.app.data.settings.AppSettings
import com.novelforge.app.data.settings.AppSettingsStore
import com.novelforge.app.infrastructure.jobs.ConnectionTestResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    settingsStore: AppSettingsStore,
    apiKeyStore: ApiKeyStore,
    onTestConnection: suspend () -> Result<ConnectionTestResult>,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(AppSettings()) }
    var apiKey by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var connectionMessage by remember { mutableStateOf<String?>(null) }
    var latencySummary by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching {
            settings = settingsStore.settings.first()
            apiKey = apiKeyStore.read().orEmpty()
        }.onFailure {
            connectionMessage = "无法读取已保存的 API Key，请重新输入并保存"
            apiKey = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("外观")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
                FilterChip(
                    selected = settings.themeMode == value,
                    onClick = {
                        settings = settings.copy(themeMode = value)
                        saved = false
                        // 主题即时生效，不等你点保存
                        scope.launch {
                            settingsStore.update { current -> current.copy(themeMode = value) }
                        }
                    },
                    label = { Text(label) }
                )
            }
        }
        Text("模型设置")
        OutlinedTextField(
            value = settings.providerName,
            onValueChange = { settings = settings.copy(providerName = it); saved = false },
            label = { Text("Provider 名称") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = settings.baseUrl,
            onValueChange = { settings = settings.copy(baseUrl = it); saved = false },
            label = { Text("Base URL（HTTPS）") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = settings.model,
            onValueChange = { settings = settings.copy(model = it); saved = false },
            label = { Text("模型名") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; saved = false },
            label = { Text("API Key") },
            singleLine = true,
            visualTransformation = if (apiKeyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                TextButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                    Text(if (apiKeyVisible) "隐藏" else "显示")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("关闭思考模式", modifier = Modifier.weight(1f))
            Switch(
                checked = settings.disableThinking,
                onCheckedChange = {
                    settings = settings.copy(disableThinking = it)
                    saved = false
                }
            )
        }
        Text(
            "请求会附带 enable_thinking=false 与 thinking.type=disabled，防止思考型模型把输出额度耗尽在思考上；已验证当前服务端支持。若个别服务端报参数错误，可关闭此开关。",
            style = MaterialTheme.typography.bodySmall
        )
        Button(
            onClick = {
                scope.launch {
                    busy = true
                    connectionMessage = null
                    runCatching { persistSettings(settingsStore, apiKeyStore, settings, apiKey) }
                        .onSuccess {
                            saved = true
                            connectionMessage = "设置已保存"
                        }
                        .onFailure {
                            saved = false
                            connectionMessage = "保存失败：${userMessage(it)}"
                        }
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (saved) "已保存" else "保存设置")
        }
        Button(
            onClick = {
                scope.launch {
                    busy = true
                    testing = true
                    connectionMessage = null
                    val result = runCatching {
                        persistSettings(settingsStore, apiKeyStore, settings, apiKey)
                        onTestConnection().getOrThrow()
                    }
                    testing = false
                    busy = false
                    connectionMessage = result.fold(
                        onSuccess = { testResult ->
                            "连接成功 · 首字延迟 ${testResult.firstTokenMs} ms · 总延迟 ${testResult.totalMs} ms"
                        },
                        onFailure = { "连接失败：${userMessage(it)}" }
                    )
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (testing) "测试中…" else "测试模型连接")
        }
        connectionMessage?.let { Text(it) }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("返回")
        }
    }
}

private suspend fun persistSettings(
    settingsStore: AppSettingsStore,
    apiKeyStore: ApiKeyStore,
    settings: AppSettings,
    apiKey: String
) {
    settingsStore.update { settings }
    val normalizedKey = apiKey.trim()
    if (normalizedKey.isBlank()) apiKeyStore.clear() else apiKeyStore.write(normalizedKey)
}

private fun userMessage(error: Throwable): String =
    error.message?.takeIf { it.isNotBlank() } ?: "设备安全存储或网络配置不可用"

