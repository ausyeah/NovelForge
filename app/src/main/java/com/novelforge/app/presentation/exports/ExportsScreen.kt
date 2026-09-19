package com.novelforge.app.presentation.exports

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
                stream.readBytes().toString(Charsets.UTF_8)
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
            Text("导出文件", style = MaterialTheme.typography.headlineSmall)
            Text(
                "TXT 保存在手机「下载/NovelForge/」文件夹，可离线直接查看。",
                style = MaterialTheme.typography.bodySmall
            )
            when (val list = files) {
                null -> Text("正在加载…")
                emptyList<ExportedTxt>() -> Text(
                    "还没有导出文件。\n在章节工作台点「保存到文件夹」后，会出现在这里。"
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
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("返回")
            }
        } else {
            val (file, content) = current
            Text(file.displayName, style = MaterialTheme.typography.titleMedium)
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
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("返回主页")
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
