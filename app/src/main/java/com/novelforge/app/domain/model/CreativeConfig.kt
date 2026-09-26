package com.novelforge.app.domain.model

import kotlinx.serialization.Serializable

// 超长篇支持：章节数上限统一在此定义（1200 章目标 + 余量）
const val MIN_CHAPTER_COUNT = 1
const val MAX_CHAPTER_COUNT = 1_500

@Serializable
enum class WritingStyle {
    LITERARY,
    ACCESSIBLE,
    CLASSICAL,
    WEB_NOVEL,
    CUSTOM
}

@Serializable
enum class Tone {
    LIGHT,
    SERIOUS,
    HUMOROUS,
    OPPRESSIVE,
    CUSTOM
}

@Serializable
enum class ThrillFrequency {
    EVERY_CHAPTER,
    EVERY_THREE_CHAPTERS,
    SLOW_BURN,
    CUSTOM
}

@Serializable
data class CreativeConfig(
    val writingStyle: WritingStyle = WritingStyle.WEB_NOVEL,
    val customWritingStyle: String? = null,
    val writingStyleIntensity: Int = 3,
    val tone: Tone = Tone.SERIOUS,
    val customTone: String? = null,
    val toneIntensity: Int = 3,
    val thrillFrequency: ThrillFrequency = ThrillFrequency.EVERY_THREE_CHAPTERS,
    val customThrillFrequency: String? = null,
    val genreTags: List<String> = emptyList(),
    val chapterCount: Int = 1,
    val targetLength: Int = 4_000,
    val inputBudget: Int = 8_000,
    val outputBudget: Int = 5_000,
    val safetyMargin: Int = 512
)

// 这两个 label 同时喂给界面和模型提示词，所以必须和 CreativeSetupScreen
// 里的选项名一致 —— 不然用户选了「文艺」，提示词里却写着「文青」。
// 界面上这一对轴改叫「叙事风格」/「基调」：原来的「文笔风格 / 笔风类型」
// 在用户看来是同一件事，分不出两个维度。
fun CreativeConfig.writingStylePromptLabel(): String = when (writingStyle) {
    WritingStyle.LITERARY -> "文艺"
    WritingStyle.ACCESSIBLE -> "通俗"
    WritingStyle.CLASSICAL -> "古典"
    WritingStyle.WEB_NOVEL -> "网文风"
    WritingStyle.CUSTOM -> customWritingStyle?.trim().orEmpty().ifBlank { "自定义叙事风格" }
}

fun CreativeConfig.tonePromptLabel(): String = when (tone) {
    Tone.LIGHT -> "轻松"
    Tone.SERIOUS -> "严肃"
    Tone.HUMOROUS -> "幽默"
    Tone.OPPRESSIVE -> "压抑"
    Tone.CUSTOM -> customTone?.trim().orEmpty().ifBlank { "自定义基调" }
}

fun CreativeConfig.thrillFrequencyPromptLabel(): String = when (thrillFrequency) {
    ThrillFrequency.EVERY_CHAPTER -> "每章高潮"
    ThrillFrequency.EVERY_THREE_CHAPTERS -> "每 3 章高潮"
    ThrillFrequency.SLOW_BURN -> "慢热铺垫"
    ThrillFrequency.CUSTOM -> customThrillFrequency?.trim().orEmpty().ifBlank { "自定义爽感频率" }
}
