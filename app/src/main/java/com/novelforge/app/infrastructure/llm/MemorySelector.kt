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
 * 传入本章标题和概要时，被点名的角色及其旧事实优先占预算，而不是只留最近写入的条目。
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
        inputBudget: Int = 8_000,
        chapterHint: String = ""
    ): MemorySlice {
        val fieldLimit = (inputBudget / 40).coerceIn(40, 120)
        val hint = chapterHint.trim()
        val eligibleCharacters = state.characters.filter { it.id !in excludedCharacterIds && it.name.isNotBlank() }
        val mentionedNames = eligibleCharacters
            .map { it.name.trim() }
            .filter { it.length >= 2 && hint.contains(it) }
        val characters = prioritize(eligibleCharacters, hint) { it.name.trim() }
            .take(MAX_CHARACTERS)
            .map { it.clipped(fieldLimit) }
        val threads = prioritize(
            state.unresolvedThreads.map { it.trim() }.filter { it.isNotEmpty() && it !in excludedThreads },
            hint
        ) { it }.take(MAX_THREADS)
        val facts = selectFacts(state.factsWithSources, hint, mentionedNames)
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

    private fun selectFacts(
        facts: List<ContinuityFact>,
        hint: String,
        mentionedNames: List<String>
    ): List<ContinuityFact> {
        val eligible = facts.filter { it.confirmed && it.statement.isNotBlank() }
        val newestFirst = eligible.sortedByDescending { it.updatedAt }
        val picked = if (hint.isEmpty() || mentionedNames.isEmpty()) {
            newestFirst.take(MAX_FACTS)
        } else {
            val (hit, miss) = newestFirst.partition { fact ->
                mentionedNames.any { name -> fact.statement.contains(name) }
            }
            (hit + miss).take(MAX_FACTS)
        }
        return picked.sortedBy { it.updatedAt }
    }

    private fun <T> prioritize(items: List<T>, hint: String, text: (T) -> String): List<T> {
        if (hint.isEmpty()) return items
        val (hit, miss) = items.partition { item ->
            val value = text(item)
            value.length >= 2 && hint.contains(value)
        }
        return hit + miss
    }

    private fun CharacterProfile.clipped(limit: Int) = copy(
        appearance = appearance.take(limit),
        personality = personality.take(limit),
        motivation = motivation.take(limit),
        abilities = abilities.take(limit),
        relationships = relationships.entries.take(4).associate { it.key.take(12) to it.value.take(limit) }
    )
}

/** 本章标题、概要、角色变化。记忆选择用它把相关旧设定从预算里捞回来。 */
fun chapterMemoryHint(title: String, summary: String, characterChanges: String? = null): String =
    listOf(title, summary, characterChanges.orEmpty())
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

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
