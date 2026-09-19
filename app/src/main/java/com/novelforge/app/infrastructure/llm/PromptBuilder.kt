package com.novelforge.app.infrastructure.llm

import com.novelforge.app.domain.model.CharacterProfile
import com.novelforge.app.domain.model.ContinuityState
import com.novelforge.app.domain.model.CreativeConfig
import com.novelforge.app.domain.model.OutlineItem
import com.novelforge.app.domain.model.tonePromptLabel
import com.novelforge.app.domain.model.thrillFrequencyPromptLabel
import com.novelforge.app.domain.model.writingStylePromptLabel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ChapterContext(
    val continuityState: ContinuityState,
    val characters: List<CharacterProfile>,
    val previousSummary: String? = null,
    val previousTail: String? = null,
    val userFeedback: String? = null
)

class PromptBuilder(
    private val json: Json = Json { encodeDefaults = true }
) {
    fun buildSystemPrompt(config: CreativeConfig, characters: List<CharacterProfile>): String = buildString {
        appendLine("你是一位专业的中文小说创作者。")
        appendLine("【创作偏好】")
        appendLine("- 文笔风格：${config.writingStylePromptLabel()}，强度 ${config.writingStyleIntensity}/5")
        appendLine("- 笔风类型：${config.tonePromptLabel()}，强度 ${config.toneIntensity}/5")
        appendLine("- 爽感频率：${config.thrillFrequencyPromptLabel()}")
        appendLine("- 题材标签：${config.genreTags.joinToString("、")}")
        appendLine("【写作规则】")
        appendLine("1. 展示而非讲述，用动作、细节和对话表现。")
        appendLine("2. 每章需要有推进、冲突或新的信息。")
        appendLine("3. 结尾留下自然的悬念或未完成动作。")
        appendLine("4. 不用破折号凑字数，不重复相同段落。")
        appendLine("5. 人物行为必须符合角色档案和连续性状态。")
        appendLine("【角色档案 JSON】")
        appendLine(json.encodeToString(characters))
    }

    fun buildChapterPrompt(
        chapter: OutlineItem,
        context: ChapterContext,
        budget: ContextBudget
    ): List<ChatMessage> {
        val previousTail = context.previousTail?.takeLastByCodePoint(budget.inputBudget / 3)
        val feedback = context.userFeedback?.takeLastByCodePoint(budget.inputBudget / 10)
        val userPrompt = buildString {
            appendLine("请创作大纲项 ${chapter.id}，显示序号 ${chapter.orderIndex}：${chapter.title}")
            appendLine("【本章概要】${chapter.summary}")
            appendLine("【角色变化】${chapter.characterChanges.orEmpty()}")
            appendLine("【连续性状态】${json.encodeToString(context.continuityState)}")
            appendLine("【角色快照】${json.encodeToString(context.characters)}")
            appendLine("【前章摘要】${context.previousSummary.orEmpty()}")
            appendLine("【前章结尾片段】$previousTail")
            appendLine("【用户反馈】$feedback")
            appendLine("目标正文长度约 ${budget.outputBudget} tokens。")
            appendLine("请优先返回 JSON：{\"summary\":\"...\",\"content\":\"...\"}。")
        }
        return listOf(
            ChatMessage(ChatRole.USER, userPrompt)
        )
    }

    private fun String.takeLastByCodePoint(max: Int): String {
        val codePointCount = codePointCount(0, length)
        if (codePointCount <= max) return this
        val start = offsetByCodePoints(0, codePointCount - max)
        return substring(start)
    }
}
