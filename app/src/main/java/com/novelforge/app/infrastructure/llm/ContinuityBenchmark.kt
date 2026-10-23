package com.novelforge.app.infrastructure.llm

import com.novelforge.app.domain.model.CharacterProfile
import com.novelforge.app.domain.model.ContinuityFact
import com.novelforge.app.domain.model.ContinuityState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 不调用模型的记忆选择对照。
 * 度量的是「下一章 prompt 里有没有留下必须记住的旧设定」，不是正文写得好不好。
 */
object ContinuityBenchmark {
    const val REQUIRED_CHARACTER = "沈砚"
    const val REQUIRED_FACT = "沈砚的左臂已断，不能再持剑"
    const val REQUIRED_THREAD = "断臂能不能接上"
    const val POLLUTED_FACT = "沈砚的断臂其实是假的"
    const val CHAPTER_HINT = "沈砚用断臂挡住了刀。断臂能不能接上"

    private val json = Json { encodeDefaults = true }

    fun run(): List<MemoryStrategyScore> {
        val state = fixture()
        return listOf(
            score("不带记忆", MemorySelector.select(ContinuityState())),
            score("只留最近", MemorySelector.select(state)),
            score("按本章捞回", MemorySelector.select(state, chapterHint = CHAPTER_HINT)),
            score("全量塞入", dump(state))
        )
    }

    fun fixture(): ContinuityState {
        val bystanders = (1..6).map { index ->
            CharacterProfile(id = "walker-$index", projectId = "bench", name = "路人$index")
        }
        val lead = CharacterProfile(
            id = "shen",
            projectId = "bench",
            name = REQUIRED_CHARACTER,
            abilities = "左臂已断"
        )
        val facts = listOf(
            ContinuityFact("old", REQUIRED_FACT, "c1", confirmed = true, updatedAt = 1)
        ) + (2..20).map { index ->
            ContinuityFact("new-$index", "街市第${index}天有人卖饼", null, confirmed = true, updatedAt = index.toLong())
        }
        val threads = (1..12).map { "旧案线索$it" } + REQUIRED_THREAD
        return ContinuityState(
            characters = bystanders + lead,
            factsWithSources = facts,
            unresolvedThreads = threads,
            pendingFacts = listOf(
                ContinuityFact("pending", POLLUTED_FACT, "c2", confirmed = false, updatedAt = 99)
            )
        )
    }

    private fun dump(state: ContinuityState) = MemorySlice(
        continuity = state,
        characters = state.characters,
        characterNames = state.characters.map { it.name },
        threads = state.unresolvedThreads,
        factCount = state.factsWithSources.size + state.pendingFacts.size,
        omittedCharacters = 0,
        omittedThreads = 0
    )

    private fun score(name: String, slice: MemorySlice): MemoryStrategyScore {
        val required = listOf(
            slice.characterNames.contains(REQUIRED_CHARACTER),
            slice.continuity.factsWithSources.any { it.statement == REQUIRED_FACT },
            slice.threads.contains(REQUIRED_THREAD)
        )
        val polluted = listOf(
            slice.continuity.pendingFacts.any { it.statement == POLLUTED_FACT },
            slice.continuity.factsWithSources.any { it.statement == POLLUTED_FACT }
        ).count { it }
        val promptChars = json.encodeToString(slice.continuity).length +
            json.encodeToString(slice.characters).length
        return MemoryStrategyScore(
            name = name,
            requiredKept = required.count { it },
            requiredTotal = required.size,
            polluted = polluted,
            promptChars = promptChars
        )
    }
}

data class MemoryStrategyScore(
    val name: String,
    val requiredKept: Int,
    val requiredTotal: Int,
    val polluted: Int,
    val promptChars: Int
)
