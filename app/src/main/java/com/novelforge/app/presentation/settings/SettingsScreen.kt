package com.novelforge.app.presentation.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.novelforge.app.presentation.common.PaperTopBar
import com.novelforge.app.ui.theme.WALLPAPER_FILE_NAME
import com.novelforge.app.ui.theme.clearWallpaper
import com.novelforge.app.ui.theme.copyWallpaper
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
    onFetchModels: suspend () -> Result<List<String>>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(AppSettings()) }
    var apiKey by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var connectionMessage by remember { mutableStateOf<String?>(null) }
    val wallpaperPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val copied = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                copyWallpaper(context, uri)
            }
            if (!copied) {
                connectionMessage = "这张图片没有放进去，换一张试试"
                return@launch
            }
            settings = settings.copy(wallpaperFileName = WALLPAPER_FILE_NAME)
            settingsStore.update { current -> current.copy(wallpaperFileName = WALLPAPER_FILE_NAME) }
        }
    }
    var latencySummary by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<String>?>(null) }
    var fetchingModels by remember { mutableStateOf(false) }
    var loadedOk by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val settingsResult = runCatching { settingsStore.settings.first() }
        settingsResult.onSuccess { loaded ->
            settings = loaded
            apiKey = runCatching { apiKeyStore.read() }.getOrNull().orEmpty()
            if (settingsResult.isSuccess) loadedOk = true
        }.onFailure {
            // 读不到现配置时严禁保存：否则一次保存就把全部设置刷成默认并清掉 API Key
            connectionMessage = "读取已保存的设置失败（${it.message}）。请重启应用再试；修复前保存按钮已禁用，避免覆盖配置"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PaperTopBar(title = "模型设置", subtitle = "密钥只存在这台手机上", onBack = onBack)
        Text("外观", style = MaterialTheme.typography.titleSmall)
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
        Text("壁纸", style = MaterialTheme.typography.titleSmall)
        Text(
            if (settings.wallpaperFileName.isBlank()) "用一张自己的图当纸面背景，文字下面会留一层纸色。"
            else "已使用自定义壁纸。遮罩越深，字越清楚。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                wallpaperPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }) { Text(if (settings.wallpaperFileName.isBlank()) "选择图片" else "更换图片") }
            if (settings.wallpaperFileName.isNotBlank()) {
                OutlinedButton(onClick = {
                    clearWallpaper(context)
                    settings = settings.copy(wallpaperFileName = "")
                    scope.launch {
                        settingsStore.update { current -> current.copy(wallpaperFileName = "") }
                    }
                }) { Text("恢复纸色") }
            }
        }
        if (settings.wallpaperFileName.isNotBlank()) {
            Text("纸色遮罩 ${settings.wallpaperDim}", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = settings.wallpaperDim.toFloat(),
                onValueChange = { settings = settings.copy(wallpaperDim = it.toInt()) },
                onValueChangeFinished = {
                    scope.launch {
                        val dim = settings.wallpaperDim
                        settingsStore.update { current -> current.copy(wallpaperDim = dim) }
                    }
                },
                valueRange = 35f..90f
            )
        }
        Text("连接", style = MaterialTheme.typography.titleSmall)
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
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        // 模型列表：从服务端 /models 拉取后点选，免去手敲
        OutlinedButton(
            onClick = {
                scope.launch {
                    fetchingModels = true
                    connectionMessage = null
                    val result = runCatching {
                        persistSettings(settingsStore, apiKeyStore, settings, apiKey)
                        onFetchModels().getOrThrow()
                    }
                    fetchingModels = false
                    result.onSuccess { list ->
                        models = list
                        connectionMessage = "拉到 ${list.size} 个模型，点选即填入"
                    }.onFailure {
                        connectionMessage = "拉取模型列表失败：${userMessage(it)}"
                    }
                }
            },
            enabled = !busy && !fetchingModels && loadedOk,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (fetchingModels) "拉取中…" else "拉取模型列表")
        }
        models?.let { list ->
            if (list.isEmpty()) {
                Text("该服务没有返回任何模型", style = MaterialTheme.typography.bodySmall)
            } else {
                // 不能内嵌 verticalScroll：会和页面外层滚动打架，导致整页卡住滑不动
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    list.forEach { name ->
                        FilterChip(
                            selected = settings.model == name,
                            onClick = {
                                settings = settings.copy(model = name)
                                saved = false
                                // 点选即持久化，和主题一样即时生效
                                scope.launch { settingsStore.update { current -> current.copy(model = name) } }
                            },
                            label = { Text(name, style = MaterialTheme.typography.bodySmall) }
                        )
                    }
                }
            }
        }
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
            enabled = !busy && loadedOk,
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
            enabled = !busy && loadedOk,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (testing) "测试中…" else "测试模型连接")
        }
        connectionMessage?.let { Text(it) }
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

