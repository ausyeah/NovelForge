package com.novelforge.app.presentation.exports

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novelforge.app.presentation.common.PaperTopBar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ExportedTxt(
    val displayName: String,
    val uri: Uri,
    val sizeBytes: Long,
    val lastModifiedSeconds: Long
)

/** 读取「下载/NovelForge/」下的导出 TXT（Android 10+ 走 MediaStore，旧系统走应用专属目录） */
object ExportStore {
    private const val SUB_DIR = "NovelForge"

    fun list(context: Context): List<ExportedTxt> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val result = mutableListOf<ExportedTxt>()
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED
            )
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(Environment.DIRECTORY_DOWNLOADS + "/$SUB_DIR/"),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    result += ExportedTxt(
                        displayName = cursor.getString(1) ?: "未命名.txt",
                        uri = android.content.ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            id
                        ),
                        sizeBytes = cursor.getLong(2),
                        lastModifiedSeconds = cursor.getLong(3)
                    )
                }
            }
            result
        } else {
            val dir = File(context.getExternalFilesDir(null), SUB_DIR)
            dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }
                ?.map {
                    ExportedTxt(it.name, Uri.fromFile(it), it.length(), it.lastModified() / 1000)
                }
                .orEmpty()
        }
    }

    fun read(context: Context, file: ExportedTxt): String =
        runCatching {
            context.contentResolver.openInputStream(file.uri)?.use { stream ->
                // 导出会写 UTF-8 BOM（Windows 记事本/WordPad 需要它才不乱码），
                // 预览里要摘掉，否则正文前面多一个零宽字符
                stream.readBytes().toString(Charsets.UTF_8).removePrefix("\uFEFF")
            }.orEmpty()
        }.getOrDefault("（文件读取失败）")

    fun share(context: Context, file: ExportedTxt) {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            file.uri
        } else {
            // file:// 在新系统上不能直接分享，走 FileProvider
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.files",
                File(file.uri.path ?: "/")
            )
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "分享 TXT"))
    }

    fun delete(context: Context, file: ExportedTxt): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.delete(file.uri, null, null) > 0
        } else {
            file.uri.path?.let { path -> File(path).delete() } ?: false
        }
    }.getOrDefault(false)
}

@Composable
fun ExportsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<ExportedTxt>?>(null) }
    var viewing by remember { mutableStateOf<Pair<ExportedTxt, String>?>(null) }
    val app = com.novelforge.app.infrastructure.backup.NovelForgeRefs.application
    val projects by app.projectRepository.observeProjects()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var pickBackupProject by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    // SAF 选择期间 Activity 可能被回收，所以要存住「导出哪本书」。
    // 用 rememberSaveable 存 id，而不是进程级单槽：
    // 单槽无 key、不清理，SAF 期间再触发一次导出就会写进同一个槽，
    // 于是「A 书的文件名 + B 书的内容」。而且它是整进程唯一一份，
    // 书架页刚修掉的就是同一个模式。
    var pendingBackupProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val wantedId = pendingBackupProjectId
        pendingBackupProjectId = null
        val target = projects.firstOrNull { it.id == wantedId }
        if (uri != null && target != null) {
            scope.launch {
                backupMessage = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        app.backupStore.export(target.id, stream)
                    } ?: error("无法写入所选文件")
                    "已导出《${target.title}》完整备份（大纲 + 全部正文）"
                }.getOrElse { "导出失败：${it.message}" }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                backupMessage = runCatching {
                    val imported = context.contentResolver.openInputStream(uri)
                        ?.use { app.backupStore.import(it) } ?: error("无法读取所选文件")
                    "已导入为新书《${imported.title}》，回书架或项目列表可见"
                }.getOrElse { "导入失败：${it.message}" }
            }
        }
    }

    fun refresh() {
        scope.launch {
            files = withContext(Dispatchers.IO) { ExportStore.list(context) }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val current = viewing
        if (current == null) {
            PaperTopBar(title = "备份与导出", subtitle = "不含 API Key", onBack = onBack)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("整书备份（JSON）", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "备份包含项目设定、全部大纲版本和全部正文修订，可换机/重装后导入恢复；不含 API Key。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    backupMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { pickBackupProject = true },
                            modifier = Modifier.weight(1f),
                            enabled = projects.isNotEmpty()
                        ) { Text("导出备份") }
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("application/json")) },
                            modifier = Modifier.weight(1f)
                        ) { Text("导入备份") }
                    }
                }
            }
            if (pickBackupProject) {
                AlertDialog(
                    onDismissRequest = { pickBackupProject = false },
                    title = { Text("选择要导出的书") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            projects.forEach { project ->
                                TextButton(onClick = {
                                    pickBackupProject = false
                                    pendingBackupProjectId = project.id
                                    exportLauncher.launch(
                                        app.backupStore.suggestedFileName(project.title)
                                    )
                                }) { Text("《${project.title}》") }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { pickBackupProject = false }) { Text("取消") }
                    }
                )
            }
            Text(
                "TXT 保存在手机「下载/NovelForge/」文件夹，可离线直接查看。",
                style = MaterialTheme.typography.bodySmall
            )
            when (val list = files) {
                null -> Text("正在加载…", style = MaterialTheme.typography.bodySmall)
                emptyList<ExportedTxt>() -> Text(
                    "还没有 TXT。在章节页点「保存到文件夹」后，会出现在这里。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        list.forEach { file ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                ) {
                                    Text(file.displayName, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${formatSize(file.sizeBytes)} · ${formatTime(file.lastModifiedSeconds)}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    val content = withContext(Dispatchers.IO) {
                                                        ExportStore.read(context, file)
                                                    }
                                                    viewing = file to content
                                                }
                                            }
                                        ) { Text("查看") }
                                        TextButton(onClick = { ExportStore.share(context, file) }) {
                                            Text("分享")
                                        }
                                        TextButton(onClick = {
                                            scope.launch {
                                                val deleted = withContext(Dispatchers.IO) {
                                                    ExportStore.delete(context, file)
                                                }
                                                if (deleted) refresh()
                                            }
                                        }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val (file, content) = current
            PaperTopBar(title = file.displayName, onBack = { viewing = null })
            Text(
                content,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 1000.dp)
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewing = null },
                    modifier = Modifier.weight(1f)
                ) { Text("返回列表") }
                OutlinedButton(
                    onClick = { ExportStore.share(context, file) },
                    modifier = Modifier.weight(1f)
                ) { Text("分享") }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
    else -> "%.1f KB".format(bytes / 1024f)
}

private fun formatTime(seconds: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(seconds * 1000))

