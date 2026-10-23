package com.novelforge.app.presentation.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.novelforge.app.presentation.common.PaperTopBar
import com.novelforge.app.domain.model.CreativeConfig
import com.novelforge.app.domain.model.MAX_CHAPTER_COUNT
import com.novelforge.app.domain.model.MIN_CHAPTER_COUNT
import com.novelforge.app.domain.model.Project
import com.novelforge.app.domain.model.QuestData
import com.novelforge.app.domain.model.ThrillFrequency
import com.novelforge.app.domain.model.Tone
import com.novelforge.app.domain.model.WritingStyle

private val genreOptions = listOf("玄幻", "都市", "悬疑", "科幻", "历史", "言情", "现实", "奇幻")
private val styleOptions = listOf(
    WritingStyle.LITERARY to "文艺",
    WritingStyle.ACCESSIBLE to "通俗",
    WritingStyle.CLASSICAL to "古典",
    WritingStyle.WEB_NOVEL to "网文风",
    WritingStyle.CUSTOM to "自定义"
)
private val toneOptions = listOf(
    Tone.LIGHT to "轻松",
    Tone.SERIOUS to "严肃",
    Tone.HUMOROUS to "幽默",
    Tone.OPPRESSIVE to "压抑",
    Tone.CUSTOM to "自定义"
)
private val thrillOptions = listOf(
    ThrillFrequency.EVERY_CHAPTER to "每章高潮",
    ThrillFrequency.EVERY_THREE_CHAPTERS to "每 3 章高潮",
    ThrillFrequency.SLOW_BURN to "慢热铺垫",
    ThrillFrequency.CUSTOM to "自定义"
)
private val chapterOptions = listOf(1, 3, 6, 12, 20, 30, 50, 80, 100, 150, 200, 300, 500, 1000, 1200)
private val targetLengthOptions = listOf(2_000, 4_000, 6_000, 8_000)

@Composable
fun CreativeSetupScreen(
    project: Project,
    saving: Boolean,
    error: String?,
    onSave: (CreativeConfig, QuestData) -> Unit,
    onSaveAndAutoRun: (CreativeConfig, QuestData) -> Unit,
    onClearError: () -> Unit,
    onBack: () -> Unit
) {
    val existingConfig = project.creativeConfig
    val existingAnswers = project.questData.answers
    var premise by remember(project.id) { mutableStateOf(existingAnswers["premise"].orEmpty()) }
    var protagonist by remember(project.id) { mutableStateOf(existingAnswers["protagonist"].orEmpty()) }
    var conflict by remember(project.id) { mutableStateOf(existingAnswers["conflict"].orEmpty()) }
    var genres by remember(project.id) {
        mutableStateOf(existingConfig?.genreTags.orEmpty().toSet())
    }
    var customGenre by remember(project.id) { mutableStateOf("") }
    var writingStyle by remember(project.id) { mutableStateOf(existingConfig?.writingStyle) }
    var customWritingStyle by remember(project.id) {
        mutableStateOf(existingConfig?.customWritingStyle.orEmpty())
    }
    var writingStyleIntensity by remember(project.id) {
        mutableStateOf(existingConfig?.writingStyleIntensity ?: 3)
    }
    var tone by remember(project.id) { mutableStateOf(existingConfig?.tone) }
    var customTone by remember(project.id) { mutableStateOf(existingConfig?.customTone.orEmpty()) }
    var toneIntensity by remember(project.id) {
        mutableStateOf(existingConfig?.toneIntensity ?: 3)
    }
    var thrillFrequency by remember(project.id) {
        mutableStateOf(existingConfig?.thrillFrequency)
    }
    var customThrillFrequency by remember(project.id) {
        mutableStateOf(existingConfig?.customThrillFrequency.orEmpty())
    }
    val existingChapterCount = existingConfig?.chapterCount
    var customChapterMode by remember(project.id) {
        mutableStateOf(existingChapterCount != null && existingChapterCount !in chapterOptions)
    }
    var chapterCount by remember(project.id) {
        mutableStateOf(existingChapterCount?.takeIf { it in chapterOptions })
    }
    var customChapterCount by remember(project.id) {
        mutableStateOf(existingChapterCount?.takeIf { it !in chapterOptions }?.toString().orEmpty())
    }
    val existingTargetLength = existingConfig?.targetLength
    var customTargetLengthMode by remember(project.id) {
        mutableStateOf(existingTargetLength != null && existingTargetLength !in targetLengthOptions)
    }
    var targetLength by remember(project.id) {
        mutableStateOf(existingTargetLength?.takeIf { it in targetLengthOptions } ?: 4_000)
    }
    var customTargetLength by remember(project.id) {
        mutableStateOf(existingTargetLength?.takeIf { it !in targetLengthOptions }?.toString().orEmpty())
    }

    val selectedChapterCount = if (customChapterMode) {
        customChapterCount.toIntOrNull() ?: 0
    } else {
        chapterCount ?: 0
    }
    val selectedTargetLength = if (customTargetLengthMode) {
        customTargetLength.toIntOrNull() ?: 0
    } else {
        targetLength
    }
    val chapterSelectionMade = customChapterMode || chapterCount != null
    val config = if (
        writingStyle != null && tone != null && thrillFrequency != null && chapterSelectionMade
    ) {
        CreativeConfig(
            writingStyle = writingStyle!!,
            customWritingStyle = customWritingStyle,
            writingStyleIntensity = writingStyleIntensity,
            tone = tone!!,
            customTone = customTone,
            toneIntensity = toneIntensity,
            thrillFrequency = thrillFrequency!!,
            customThrillFrequency = customThrillFrequency,
            genreTags = genres.toList(),
            chapterCount = selectedChapterCount,
            targetLength = selectedTargetLength
        )
    } else {
        null
    }
    val questData = QuestData(
        schemaVersion = project.questData.schemaVersion,
        answers = existingAnswers + mapOf(
            "premise" to premise,
            "protagonist" to protagonist,
            "conflict" to conflict
        )
    )
    val validationError = config?.let { validateCreativeSetup(it, questData) }
    val canSave = config != null && validationError == null && !saving

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PaperTopBar(
            title = "创作设置",
            subtitle = "《${project.title}》",
            onBack = onBack
        )
        Text(
            "标有 * 的项为必填，未完成前无法生成大纲。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = premise,
            onValueChange = { premise = it },
            label = { Text("* 题材创意 / 一句话设定") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = protagonist,
            onValueChange = { protagonist = it },
            label = { Text("* 主角设定") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = conflict,
            onValueChange = { conflict = it },
            label = { Text("* 核心冲突") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )

        ChoiceSection("* 题材标签（可多选）") {
            genreOptions.forEach { genre ->
                SelectChip(
                    label = genre,
                    selected = genre in genres,
                    onClick = {
                        genres = if (genre in genres) genres - genre else genres + genre
                    }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = customGenre,
                onValueChange = { customGenre = it },
                label = { Text("自定义题材") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    val value = customGenre.trim()
                    if (value.isNotBlank()) {
                        genres = genres + value
                        customGenre = ""
                    }
                },
                enabled = customGenre.trim().isNotBlank()
            ) { Text("添加") }
        }
        ChoiceSection("* 叙事风格") {
            styleOptions.forEach { (value, label) ->
                SelectChip(label, writingStyle == value) { writingStyle = value }
            }
        }
        if (writingStyle == WritingStyle.CUSTOM) {
            OutlinedTextField(
                value = customWritingStyle,
                onValueChange = { customWritingStyle = it },
                label = { Text("自定义叙事风格") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        IntensitySlider(
            title = "叙事风格强度",
            value = writingStyleIntensity,
            onValueChange = { writingStyleIntensity = it }
        )
        ChoiceSection("* 基调") {
            toneOptions.forEach { (value, label) ->
                SelectChip(label, tone == value) { tone = value }
            }
        }
        if (tone == Tone.CUSTOM) {
            OutlinedTextField(
                value = customTone,
                onValueChange = { customTone = it },
                label = { Text("自定义基调") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        IntensitySlider(
            title = "基调节奏",
            value = toneIntensity,
            onValueChange = { toneIntensity = it }
        )
        ChoiceSection("* 爽感频率") {
            thrillOptions.forEach { (value, label) ->
                SelectChip(label, thrillFrequency == value) { thrillFrequency = value }
            }
        }
        if (thrillFrequency == ThrillFrequency.CUSTOM) {
            OutlinedTextField(
                value = customThrillFrequency,
                onValueChange = { customThrillFrequency = it },
                label = { Text("自定义爽感频率，例如：每 5 章一次大高潮") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        ChoiceSection("* 计划章节数") {
            chapterOptions.forEach { count ->
                SelectChip("${count} 章", !customChapterMode && chapterCount == count) {
                    customChapterMode = false
                    chapterCount = count
                }
            }
            SelectChip("自定义", customChapterMode) {
                customChapterMode = true
                chapterCount = null
            }
        }
        if (customChapterMode) {
            OutlinedTextField(
                value = customChapterCount,
                onValueChange = { customChapterCount = it.filter(Char::isDigit) },
                label = { Text("自定义章节数（$MIN_CHAPTER_COUNT–$MAX_CHAPTER_COUNT）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        ChoiceSection("每章目标字数") {
            targetLengthOptions.forEach { length ->
                SelectChip("${length} 字", !customTargetLengthMode && targetLength == length) {
                    customTargetLengthMode = false
                    targetLength = length
                }
            }
            SelectChip("自定义", customTargetLengthMode) { customTargetLengthMode = true }
        }
        if (customTargetLengthMode) {
            OutlinedTextField(
                value = customTargetLength,
                onValueChange = { customTargetLength = it.filter(Char::isDigit) },
                label = { Text("自定义每章字数（500–10000）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Text(
            "单章字数越高，生成耗时、失败概率与额度消耗越高，建议 2000–4000 字。" +
                "章节数越多，所需输出额度越高；若生成结果不完整，可提高「设置 → 连接」中的输出预算后重试。",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (config == null) {
            Text("请完成带 * 的选择后再保存。", color = MaterialTheme.colorScheme.error)
        } else if (validationError != null) {
            Text(validationError, color = MaterialTheme.colorScheme.error)
        }
        error?.let {
            Text("保存失败：$it", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onClearError) { Text("关闭提示") }
        }

        Button(
            onClick = { if (config != null) onSave(config, questData) },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (saving) "保存中…" else "保存并进入大纲")
        }
        Button(
            onClick = { if (config != null) onSaveAndAutoRun(config, questData) },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (saving) "保存中…" else "▶ 保存并一键全自动生成全书")
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("返回")
        }
    }
}

@Composable
private fun ChoiceSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) { content() }
    }
}

@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) }
    )
}

@Composable
private fun IntensitySlider(title: String, value: Int, onValueChange: (Int) -> Unit) {
    Column {
        Text("$title：$value/5")
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt().coerceIn(1, 5)) },
            valueRange = 1f..5f,
            steps = 3,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
