package com.novelforge.app.presentation.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novelforge.app.domain.model.GenerationJob
import com.novelforge.app.domain.model.GenerationJobStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun GenerationStatusCard(
    status: String,
    job: GenerationJob? = null,
    phase: String? = null,
    progress: String? = null
) {
    val active = job?.status == GenerationJobStatus.QUEUED ||
        job?.status == GenerationJobStatus.RUNNING
    var currentTime by remember(job?.id) { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(job?.id, active) {
        if (!active) return@LaunchedEffect
        while (isActive) {
            currentTime = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(status)
            progress?.let { Text(it) }
            if (active) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                val elapsedSeconds = job?.let {
                    ((currentTime - it.createdAt).coerceAtLeast(0L) / 1_000L)
                } ?: 0L
                val savedCharacters = job?.partialContent?.length ?: 0
                Text("已接收并保存 $savedCharacters 个字符 · 已等待 ${elapsedSeconds} 秒")
                Text(
                    phase ?: when {
                        job?.status == GenerationJobStatus.QUEUED ->
                            "任务已创建，正在等待生成引擎启动"
                        savedCharacters == 0 && elapsedSeconds < 3 ->
                            "正在连接模型，等待首段响应…"
                        savedCharacters == 0 && elapsedSeconds < 20 ->
                            "模型正在思考中；思考过程不计入正文，最终内容到达后会实时更新"
                        savedCharacters == 0 ->
                            "模型仍在思考或等待响应，已等待 ${elapsedSeconds} 秒；可取消后检查网络和模型设置"
                        else ->
                            "正在接收模型输出，已生成的内容会持续保存"
                    }
                )
            }
        }
    }
}
