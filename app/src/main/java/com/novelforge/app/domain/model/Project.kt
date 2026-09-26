package com.novelforge.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class ProjectStatus {
    DRAFT,
    OUTLINING,
    WRITING,
    COMPLETED,
    ARCHIVED
}

/** UI 展示用中文名；枚举名只留给存储层 */
fun ProjectStatus.label(): String = when (this) {
    ProjectStatus.DRAFT -> "筹备中"
    // 「生成大纲中」而不是「搭建大纲中」：其余六处界面都写「生成大纲」，
    // 只有这里换了动词，同一件事两种说法
    ProjectStatus.OUTLINING -> "生成大纲中"
    ProjectStatus.WRITING -> "连载中"
    ProjectStatus.COMPLETED -> "已完结"
    ProjectStatus.ARCHIVED -> "已归档"
}

@Serializable
data class Project(
    val id: String,
    val title: String,
    val questionnaireSchemaVersion: Int = 1,
    val flowState: FlowState = FlowState.INIT,
    val questData: QuestData = QuestData(),
    val creativeConfig: CreativeConfig? = null,
    val continuityState: ContinuityState = ContinuityState(),
    val activeOutlineVersionId: String? = null,
    val status: ProjectStatus = ProjectStatus.DRAFT,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
enum class FlowState {
    INIT,
    QUEST_LAYER1,
    QUEST_LAYER2,
    QUEST_LAYER3,
    OUTLINE_GENERATE,
    OUTLINE_EDIT,
    OUTLINE_CONFIRM,
    WRITING_CHAPTER,
    CHAPTER_REVIEW,
    NEEDS_USER,
    COMPLETE
}

@Serializable
data class QuestData(
    val schemaVersion: Int = 1,
    val answers: Map<String, String> = emptyMap()
)
