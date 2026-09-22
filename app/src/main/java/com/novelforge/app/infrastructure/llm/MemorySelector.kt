package com.novelforge.app.infrastructure.llm

import com.novelforge.app.domain.model.CharacterProfile
import com.novelforge.app.domain.model.ContinuityFact
import com.novelforge.app.domain.model.ContinuityState

data class MemorySlice(
    val continuity: ContinuityState,
    val characters: List<CharacterProfile>,
    val characterNames: List<String>,
    val threads: List<String>,
    val factCount: Int,
    val omittedCharacters: Int,
    val omittedThreads: Int
)

/**
 * 下一章只带预算内的记忆：待确认的条目不进 prompt，角色和伏笔可以被本次生成排除。
 */
object MemorySelector {
    const val MAX_CHARACTERS = 6
    const val MAX_RULES = 8
    const val MAX_THREADS = 10
    const val MAX_FACTS = 12

    fun select(
        state: ContinuityState,
        excludedCharacterIds: Set<String> = emptySet(),
        excludedThreads: Set<String> = emptySet(),
        inputBudget: Int = 8_000
    ): MemorySlice {
        val fieldLimit = (inputBudget / 40).coerceIn(40, 120)
        val eligibleCharacters = state.characters.filter { it.id !in excludedCharacterIds && it.name.isNotBlank() }
        val characters = eligibleCharacters.take(MAX_CHARACTERS).map { it.clipped(fieldLimit) }
        val threads = state.unresolvedThreads
            .map { it.trim() }
            .filter { it.isNotEmpty() && it !in excludedThreads }
            .take(MAX_THREADS)
        val facts = state.factsWithSources
            .filter { it.confirmed && it.statement.isNotBlank() }
            .sortedBy { it.updatedAt }
            .takeLast(MAX_FACTS)
        val continuity = state.copy(
            worldRules = state.worldRules.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_RULES),
            characterStates = emptyList(),
            timelineEvents = state.timelineEvents.takeLast(6),
            unresolvedThreads = threads,
            factsWithSources = facts,
            characters = characters,
            pendingFacts = emptyList()
        )
        return MemorySlice(
            continuity = continuity,
            characters = characters,
            characterNames = characters.map { it.name },
            threads = threads,
            factCount = facts.size,
            omittedCharacters = (eligibleCharacters.size - characters.size).coerceAtLeast(0),
            omittedThreads = (state.unresolvedThreads.count { it.isNotBlank() && it !in excludedThreads } - threads.size)
                .coerceAtLeast(0)
        )
    }

    private fun CharacterProfile.clipped(limit: Int) = copy(
        appearance = appearance.take(limit),
        personality = personality.take(limit),
        motivation = motivation.take(limit),
        abilities = abilities.take(limit),
        relationships = relationships.entries.take(4).associate { it.key.take(12) to it.value.take(limit) }
    )
}

@kotlinx.serialization.Serializable
private data class MemoryNotePayload(
    val facts: List<String> = emptyList(),
    val threads: List<String> = emptyList(),
    val resolved: List<String> = emptyList()
)

/** 把章后抽记忆的模型输出收成待确认条目；解析失败返回空，不抛。 */
fun parseMemoryNotes(raw: String, now: Long = 0L): List<ContinuityFact> {
    val jsonText = JsonResponseValidator.extractJsonValue(raw) ?: return emptyList()
    val payload = runCatching {
        kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<MemoryNotePayload>(jsonText)
    }.getOrNull() ?: return emptyList()
    return notes(payload.facts, "fact", now) +
        notes(payload.threads, "thread", now) +
        notes(payload.resolved, "resolved", now)
}

private fun notes(items: List<String>, kind: String, now: Long): List<ContinuityFact> =
    items.map { it.trim() }
        .filter { it.length in 2..80 }
        .distinct()
        .take(6)
        .map { statement ->
            ContinuityFact(
                id = "",
                statement = statement,
                sourceChapterId = null,
                confirmed = false,
                updatedAt = now,
                kind = kind
            )
        }
