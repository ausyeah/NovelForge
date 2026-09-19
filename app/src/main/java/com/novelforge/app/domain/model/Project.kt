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
