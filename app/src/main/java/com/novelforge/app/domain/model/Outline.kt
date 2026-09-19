package com.novelforge.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class OutlineVersion(
    val id: String,
    val projectId: String,
    val version: Int,
    val chapters: List<OutlineItem> = emptyList(),
    val diffSummary: String? = null,
    val createdAt: Long
)

@Serializable
data class OutlineItem(
    val id: String,
    val orderIndex: Int,
    val title: String,
    val summary: String,
    val characterChanges: String? = null
)

@Serializable
data class CharacterProfile(
    val id: String,
    val projectId: String,
    val version: Int = 1,
    val name: String,
    val appearance: String = "",
    val personality: String = "",
    val motivation: String = "",
    val abilities: String = "",
    val relationships: Map<String, String> = emptyMap()
)

@Serializable
data class ContinuityState(
    val worldRules: List<String> = emptyList(),
    val characterStates: List<String> = emptyList(),
    val timelineEvents: List<String> = emptyList(),
    val unresolvedThreads: List<String> = emptyList(),
    val factsWithSources: List<ContinuityFact> = emptyList()
)

@Serializable
data class ContinuityFact(
    val id: String,
    val statement: String,
    val sourceChapterId: String?,
    val confirmed: Boolean = true,
    val updatedAt: Long
)
