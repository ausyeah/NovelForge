package com.novelforge.app.presentation.chapter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novelforge.app.domain.model.ChapterRevision
import com.novelforge.app.domain.model.GenerationJob
import com.novelforge.app.domain.model.GenerationJobStatus
import com.novelforge.app.domain.model.OutlineItem
import com.novelforge.app.presentation.common.GenerationStatusCard
import com.novelforge.app.presentation.common.chapterLabel
import com.novelforge.app.presentation.common.cleanChapterTitle
import kotlinx.coroutines.launch

@Composable
fun ChapterScreen(
    projectTitle: String,
    chapter: OutlineItem?,
    revision: ChapterRevision?,
    job: GenerationJob?,
    error: String?,
    onGenerate: (OutlineItem) -> Unit,
    onCancel: () -> Unit,
    onRetry: (OutlineItem) -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onNextChapter: (() -> Unit)?,
    onBackHome: () -> Unit,
    onClearError: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("《$projectTitle》章节工作台")
        if (chapter == null) {
            Text("还没有可写的章节，请先生成并确认大纲。")
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("返回大纲")
            }
            return
        }

        Text("${chapterLabel(chapter.orderIndex)}：${cleanChapterTitle(chapter.title)}")
        GenerationStatusCard(
            status = statusText(job, revision),
            job = job,
            phase = if (job?.status == GenerationJobStatus.RUNNING && job.partialContent.isNotEmpty()) {
                "正在接收章节正文，已生成的内容会持续保存"
            } else {
                null
            }
        )

        error?.let {
            Text("提示：$it")
            Button(onClick = onClearError) { Text("关闭提示") }
        }

        when (job?.status) {
            GenerationJobStatus.QUEUED,
            GenerationJobStatus.RUNNING -> Button(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth()
            ) { Text("取消生成") }

            GenerationJobStatus.FAILED,
            GenerationJobStatus.RECOVERABLE_PARTIAL,
            GenerationJobStatus.NEEDS_USER -> Button(
                onClick = { onRetry(chapter) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("重试这一章") }

            else -> Button(
                onClick = { onGenerate(chapter) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (revision == null) "生成这一章" else "重新生成修订") }
        }

        val visibleContent = revision?.content
            ?: job?.partialContent?.takeIf { it.isNotBlank() }
        if (visibleContent != null) {
            // 中间生成框固定占满剩余空间，上下 UI 不随之移动；
            // 内容增长时自动滚动跟随，用户上滑即暂停跟随，可用悬浮箭头恢复
            val contentScroll = rememberScrollState()
            var autoFollow by remember(job?.id) { mutableStateOf(true) }
            var lastOffset by remember(job?.id) { mutableIntStateOf(0) }
            val scope = rememberCoroutineScope()
            LaunchedEffect(contentScroll) {
                snapshotFlow { contentScroll.value }.collect { value ->
                    if (contentScroll.isScrollInProgress && value < lastOffset - 8) {
                        autoFollow = false
                    }
                    lastOffset = value
                }
            }
            LaunchedEffect(visibleContent, autoFollow) {
                if (autoFollow) contentScroll.scrollTo(contentScroll.maxValue)
            }
            Text("正文${if (revision == null) "（任务中间结果）" else "（修订 ${revision.revision}）"}")
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Text(
                    visibleContent,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(contentScroll)
                        .padding(bottom = 48.dp)
                )
                if (contentScroll.value < contentScroll.maxValue - 120) {
                    SmallFloatingActionButton(
                        onClick = {
                            autoFollow = true
                            scope.launch { contentScroll.animateScrollTo(contentScroll.maxValue) }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                    ) { Text("↓") }
                }
            }
        }
        if (revision != null) {
            Text(
                "导出的 TXT 会直接保存到手机「下载/NovelForge/」文件夹，可在主页「导出文件」里离线查看。",
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                Text("保存到文件夹")
            }
            OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                Text("分享")
            }
        }
        val busy = job?.status == GenerationJobStatus.RUNNING || job?.status == GenerationJobStatus.QUEUED
        if (!busy && onNextChapter != null) {
            Button(onClick = onNextChapter, modifier = Modifier.fillMaxWidth()) {
                Text("生成下一章")
            }
        }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("返回大纲")
        }
        OutlinedButton(onClick = onBackHome, modifier = Modifier.fillMaxWidth()) {
            Text("返回首页")
        }
    }
}

private fun statusText(job: GenerationJob?, revision: ChapterRevision?): String = when {
    job == null && revision == null -> "生成任务：尚未开始"
    job?.status == GenerationJobStatus.QUEUED -> "生成任务：排队中"
    job?.status == GenerationJobStatus.RUNNING -> "生成任务：生成中"
    job?.status == GenerationJobStatus.RECOVERABLE_PARTIAL -> "生成任务：已保存部分结果，可重试"
    job?.status == GenerationJobStatus.CANCELLED -> "生成任务：已取消"
    job?.status == GenerationJobStatus.FAILED -> "生成任务：失败 · ${job.errorMessage.orEmpty()}"
    job?.status == GenerationJobStatus.NEEDS_USER -> "生成任务：需要处理 · ${job.errorMessage.orEmpty()}"
    revision != null -> "章节已保存 · 修订 ${revision.revision}"
    else -> "生成任务：已完成"
}
