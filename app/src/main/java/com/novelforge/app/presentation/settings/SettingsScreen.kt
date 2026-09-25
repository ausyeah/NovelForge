package com.novelforge.app.presentation.settings

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novelforge.app.presentation.common.PaperTopBar
import com.novelforge.app.ui.theme.LocalNovelForgeDark
import com.novelforge.app.ui.theme.WALLPAPER_FILE_NAME
import com.novelforge.app.ui.theme.WallpaperStore
import com.novelforge.app.ui.theme.decodeSampledBitmap
import com.novelforge.app.ui.theme.wallpaperDimRange
import com.novelforge.app.data.security.ApiKeyStore
import com.novelforge.app.data.settings.AppSettings
import com.novelforge.app.data.settings.AppSettingsStore
import com.novelforge.app.data.settings.ModelPreset
import com.novelforge.app.data.settings.ModelPresetStore
import com.novelforge.app.data.settings.upsertModelPreset
import com.novelforge.app.ui.theme.PaperSurface
import com.novelforge.app.infrastructure.jobs.ConnectionTestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    settingsStore: AppSettingsStore,
    presetStore: ModelPresetStore,
    wallpaperStore: WallpaperStore,
    apiKeyStore: ApiKeyStore,
    onTestConnection: suspend () -> Result<ConnectionTestResult>,
    onFetchModels: suspend () -> Result<List<String>>,
    onOpenLedger: () -> Unit,
    onOpenExports: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 取景原图（最大约 26MB）挂在这个 ViewModel 上，见类注释
    val wallpaperViewModel: SettingsWallpaperViewModel = viewModel()
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
            val decoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                decodeSampledBitmap(context, uri)
            }
            if (decoded == null) {
                connectionMessage = "这张图片没有读出来，换一张试试"
            } else {
                wallpaperViewModel.openCrop(decoded)
            }
        }
    }
    var latencySummary by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<String>?>(null) }
    var fetchingModels by remember { mutableStateOf(false) }
    var loadedOk by remember { mutableStateOf(false) }
    var presets by remember { mutableStateOf<List<ModelPreset>>(emptyList()) }
    var editingPresetId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<ModelPreset?>(null) }

    LaunchedEffect(Unit) {
        val settingsResult = runCatching { settingsStore.settings.first() }
        settingsResult.onSuccess { loaded ->
            settings = loaded
            apiKey = runCatching { apiKeyStore.read() }.getOrNull().orEmpty()
            presets = runCatching { presetStore.read() }.getOrDefault(emptyList())
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
        PaperTopBar(title = "设置", subtitle = "密钥只存在这台手机上", onBack = onBack)
        // 账本和备份导出以前是首页上的两个平级磁贴，和「模型设置」摆在一起，
        // 但它们都不是设置项 —— 一个是用量事实，一个是数据搬运。
        // 收进设置当二级页之后，首页只剩「书架 / 灵感 / 设置」三个一级入口。
        PaperSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                TextButton(
                    onClick = onOpenLedger,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("用量账本")
                        Text(
                            "Token 与调用记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(
                    onClick = onOpenExports,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("备份与导出")
                        Text(
                            "JSON 备份、TXT",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
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
                    settings = settings.copy(wallpaperFileName = "")
                    scope.launch {
                        wallpaperStore.clear()
                        settingsStore.update { current -> current.copy(wallpaperFileName = "") }
                    }
                }) { Text("恢复纸色") }
            }
        }
        if (settings.wallpaperFileName.isNotBlank()) {
            // 主题里 clamp 过的是同一份区间，滑块和实际渲染不会各说各话。
            // 老的 35–62 落在新区间之外，直接抬进来当显示值（不自动改写用户配置）
            val dimRange = wallpaperDimRange(LocalNovelForgeDark.current)
            val dimValue = settings.wallpaperDim.toFloat().coerceIn(dimRange)
            Text("纸色遮罩 ${dimValue.roundToInt()}", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = dimValue,
                onValueChange = { settings = settings.copy(wallpaperDim = it.roundToInt()) },
                onValueChangeFinished = {
                    scope.launch {
                        val dim = settings.wallpaperDim
                        settingsStore.update { current -> current.copy(wallpaperDim = dim) }
                    }
                },
                valueRange = dimRange
            )
        }
        Text("已保存的配置", style = MaterialTheme.typography.titleSmall)
        Text(
            if (presets.isEmpty()) "保存设置时会记住这一套接口。点按拉回来改，长按删除。"
            else "点按拉回表单再改，改完重新保存。长按删除。同名服务商会自动标成（2）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        presets.forEach { preset ->
            PaperSurface(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                settings = settings.copy(
                                    providerName = preset.providerName,
                                    baseUrl = preset.baseUrl,
                                    model = preset.model,
                                    disableThinking = preset.disableThinking
                                )
                                apiKey = preset.apiKey
                                editingPresetId = preset.id
                                saved = false
                                connectionMessage = "已拉回「${preset.label}」，改完点保存"
                            },
                            onLongClick = { pendingDelete = preset }
                        )
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(preset.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOf(preset.model, preset.baseUrl).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
                    runCatching {
                        persistSettings(settingsStore, apiKeyStore, settings, apiKey)
                        if (apiKey.isNotBlank()) {
                            presets = upsertModelPreset(
                                existing = presets,
                                editingId = editingPresetId,
                                providerName = settings.providerName,
                                baseUrl = settings.baseUrl,
                                model = settings.model,
                                disableThinking = settings.disableThinking,
                                apiKey = apiKey,
                                newId = { java.util.UUID.randomUUID().toString() }
                            )
                            presetStore.write(presets)
                            editingPresetId = null
                        }
                    }
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
    pendingDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除「${preset.label}」？") },
            text = { Text("只删除这份保存的接口，当前正在填写的内容还在。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    val next = presets.filterNot { it.id == preset.id }
                    presets = next
                    if (editingPresetId == preset.id) editingPresetId = null
                    scope.launch { runCatching { presetStore.write(next) } }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
    wallpaperViewModel.publishError?.let { message ->
        AlertDialog(
            onDismissRequest = { wallpaperViewModel.consumePublishError() },
            title = { Text("壁纸没存上") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { wallpaperViewModel.consumePublishError() }) { Text("知道了") }
            }
        )
    }
    wallpaperViewModel.cropSource?.let { source ->
        WallpaperCropDialog(
            source = source,
            busy = wallpaperViewModel.publishing,
            onCancel = { wallpaperViewModel.closeCrop() },
            onConfirm = { cropped ->
                wallpaperViewModel.publish(cropped) {
                    wallpaperStore.publish(cropped)
                    settings = settings.copy(wallpaperFileName = WALLPAPER_FILE_NAME)
                    settingsStore.update { current -> current.copy(wallpaperFileName = WALLPAPER_FILE_NAME) }
                }
            }
        )
    }
}

/**
 * 只为「转屏别把取景框关掉」而存在的一个极小 ViewModel。
 *
 * 为什么不用 remember：普通 remember 在配置变更后重置，用户选好图正在拖动时转个屏，
 * 取景框当场消失，这张图等于白选。
 * 为什么不用 rememberSaveable：Bitmap 不是 Parcelable，塞不进 Bundle；硬塞就是
 * 几十 MB 的进程状态，Activity 回收时直接 TransactionTooLarge。
 * 为什么不用「rememberSaveable 存个标记 + 全局 Map 缓存位图」：那张 Map 谁也回收不掉，
 * 等于把 26MB 泄漏搬到进程级，还得手写淘汰逻辑。
 * ViewModel 由 NavBackStackEntry 持有，转屏活、离开本页即释放，生命周期正好对上。
 */
class SettingsWallpaperViewModel : ViewModel() {
    /** 待裁剪的原图；null 表示取景框不显示。裁完就置空，26MB 立刻可回收。 */
    var cropSource by mutableStateOf<Bitmap?>(null)
        private set

    /** 存盘进行中，用来锁住确认按钮。 */
    var publishing by mutableStateOf(false)
        private set

    /** 存盘失败文案。以前 publish 是同步调用、异常被 runCatching 吃掉，搬进协程后必须自己接住。 */
    var publishError by mutableStateOf<String?>(null)
        private set

    fun openCrop(bitmap: Bitmap) {
        cropSource = bitmap
    }

    fun closeCrop() {
        cropSource = null
    }

    fun consumePublishError() {
        publishError = null
    }

    /**
     * 存盘挂 [viewModelScope] 而不是 composable 的 rememberCoroutineScope：
     * 写文件途中转屏不会把这次设置腰斩掉（store.publish 内部写临时文件再改名，
     * 就算真被取消也不会留下半张坏图，但配置就丢了）。
     */
    fun publish(bitmap: Bitmap, block: suspend () -> Unit) {
        if (publishing) return
        publishing = true
        publishError = null
        viewModelScope.launch {
            try {
                block()
                cropSource = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // viewModelScope 里未捕获的异常会直接把进程带走，写盘失败必须在这里落地
                publishError = "壁纸保存失败：${error.message ?: "未知原因"}"
            } finally {
                publishing = false
            }
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

