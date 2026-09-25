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
    /**
     * 系统提示词必须逐章完全一致，否则前缀缓存永远命中不了。
     *
     * 角色档案以前放在这里，而 characters 是 MemorySelector 每章重新选、重新排序、
     * 重新截断的结果 —— 换一个角色就作废从它往后的全部前缀。在 DeepSeek 这类
     * 未命中按 10 倍计费的网关上，这是全 app 最贵的一行。
     * 所以这里只放不变量；按章变化的部分全部下沉到 user 消息。
     */
    fun buildSystemPrompt(config: CreativeConfig, projectTitle: String = ""): String = buildString {
        appendLine("你是一位专业的中文小说创作者。")
        // 作品身份：给模型一个明确的命名空间锚点。没有它时，模型会把
        // 提示词之外的东西（上一本书的残留、通用套路）也当成这本书的设定。
        if (projectTitle.isNotBlank()) {
            appendLine("【作品】《$projectTitle》")
            appendLine("只依据下面给出的设定写作，不要引入不属于这本书的人物、地名、法则或道具。")
        }
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
    }

    fun buildChapterPrompt(
        chapter: OutlineItem,
        context: ChapterContext,
        budget: ContextBudget
    ): List<ChatMessage> {
        val previousTail = context.previousTail?.takeLastByCodePoint(budget.inputBudget / 3)
        val feedback = context.userFeedback?.takeLastByCodePoint(budget.inputBudget / 10)
        // continuityState 里已经内嵌了同一份 characters，【角色快照】又序列化一次，
        // 同一段角色档案在这一个请求里出现两遍。6 个角色 × 4 个字段 × 120 字
        // 大约是 2200 个重复 token／章。留一份，放在【角色快照】里。
        val continuity = context.continuityState.copy(characters = emptyList())
        val userPrompt = buildString {
            // 顺序有意为之：先给低优先级的前情，再给本章指令，
            // 最重要的记忆块放在靠近结尾的位置。
            // 实证结论是 LLM 对长上下文的开头和结尾注意力最强、中间最弱
            // （Lost in the Middle, arXiv:2307.03172），记忆块原先正好埋在正中间。
            appendLine("请创作《${com.novelforge.app.presentation.common.chapterLabel(chapter.orderIndex)}》：${chapter.title}")
            appendLine("正文开头不要重复书写章节标题或编号，直接进入正文内容。")
            appendLine("【前章摘要】${context.previousSummary.orEmpty()}")
            appendLine("【前章结尾片段】$previousTail")
            appendLine("【本章概要】${chapter.summary}")
            appendLine("【角色变化】${chapter.characterChanges.orEmpty()}")
            appendLine("【用户反馈】$feedback")
            appendLine("【角色快照】${json.encodeToString(context.characters)}")
            appendLine("【连续性状态】${json.encodeToString(continuity)}")
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
