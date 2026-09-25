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
    val omittedThreads: Int,
    /** 这次没带上的规则条数。作家自己写的规则被截断，必须让他知道。 */
    val omittedRules: Int = 0,
    val omittedFacts: Int = 0
)

/**
 * 下一章只带预算内的记忆：待确认的条目不进 prompt，角色和伏笔可以被本次生成排除。
 *
 * 名额不够时按「和本章的相关度」排序，不按写入顺序截断：
 * - 伏笔是往后追加的，所以从最新的一条开始保（否则用户刚加的伏笔永远发不出去）；
 * - 规则按提示词命中排，作家写的第 9 条不会再被无声丢掉；
 * - 事实先看有没有点名的角色，再看和本章概要有没有共同的 distinguishing 词，最后才看新旧；
 * - 置顶的事实永远在名额里，且排在最前面。
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
        val mentionedNames = mentionedNames(eligibleCharacters, hint)
        val characters = prioritize(eligibleCharacters, hint) { profileKeys(it) }
            .take(MAX_CHARACTERS)
            .map { it.clipped(fieldLimit) }

        // 伏笔按追加顺序存，所以同分时优先较新的那条：用户刚加的伏笔
        // 不该因为排在第 11 位以后就永远发不出去。
        val eligibleThreads = state.unresolvedThreads
            .map { it.trim() }
            .filter { it.isNotEmpty() && it !in excludedThreads }
            .distinct()
        val threads = rankByRelevance(eligibleThreads, hint, { listOf(it) }, preferNewerOnTie = true)
            .take(MAX_THREADS)
            .asReversed()   // 送进 prompt 时按从旧到新读，和事实列表一致

        val eligibleRules = state.worldRules.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        // 规则是作家手写的，书写顺序本身就是优先级，所以同分时保持原顺序。
        val rules = rankByRelevance(eligibleRules, hint, { listOf(it) }, preferNewerOnTie = false)
            .take(MAX_RULES)

        val facts = selectFacts(state.factsWithSources, hint, mentionedNames)

        val continuity = state.copy(
            worldRules = rules,
            timelineEvents = state.timelineEvents.takeLast(6),
            unresolvedThreads = threads,
            factsWithSources = facts,
            characters = characters,
            pendingFacts = emptyList()
        )
        val confirmedFacts = state.factsWithSources.count { it.confirmed && it.statement.isNotBlank() }
        return MemorySlice(
            continuity = continuity,
            characters = characters,
            characterNames = characters.map { it.name },
            threads = threads,
            factCount = facts.size,
            omittedCharacters = (eligibleCharacters.size - characters.size).coerceAtLeast(0),
            omittedThreads = (eligibleThreads.size - threads.size).coerceAtLeast(0),
            omittedRules = (eligibleRules.size - rules.size).coerceAtLeast(0),
            omittedFacts = (confirmedFacts - facts.size).coerceAtLeast(0)
        )
    }

    /**
     * 事实名额的分配。旧实现只把「含有被点名角色全名」的事实提到前面，
     * 于是地点、法则、物品、债务这类一条角色名都没有的设定永远排在淘汰区，
     * 只要新事实攒够 12 条就再也回不来。这里补一层与本章概要的词面重合度。
     */
    private fun selectFacts(
        facts: List<ContinuityFact>,
        hint: String,
        mentionedNames: List<String>
    ): List<ContinuityFact> {
        // 先按时间倒序再去重，同一句话被重复写入时保留最新那一份。
        val eligible = facts.filter { it.confirmed && it.statement.isNotBlank() }
            .sortedByDescending { it.updatedAt }
            .distinctBy { it.statement.trim() }
        if (eligible.isEmpty()) return emptyList()

        // 提示词里的二字组。中文没有空格，二字组是最省事的切分方式。
        val hintGrams = grams(hint)
        if (hintGrams.isEmpty()) {
            return eligible
                .sortedWith(compareByDescending<ContinuityFact> { it.pinned }.thenByDescending { it.updatedAt })
                .take(MAX_FACTS)
                .sortedBy { it.updatedAt }
        }

        // 只有「在这批事实里不算常见」的二字组才算有区分度。
        // 「的」「了一」这种到处都有的组合如果也算命中，会把真正相关的旧设定挤掉。
        val frequency = HashMap<String, Int>()
        eligible.forEach { fact ->
            grams(fact.statement).forEach { gram -> frequency[gram] = (frequency[gram] ?: 0) + 1 }
        }
        val distinctive = hintGrams.filterTo(HashSet()) { gram ->
            val seen = frequency[gram] ?: 0
            seen > 0 && seen * 2 <= eligible.size
        }
        val relevant = distinctive.isNotEmpty()

        return eligible
            .sortedWith(
                compareByDescending<ContinuityFact> { it.pinned }
                    .thenByDescending { fact -> relevance(fact, mentionedNames, relevant, distinctive) }
                    .thenByDescending { it.updatedAt }
            )
            .take(MAX_FACTS)
            .sortedBy { it.updatedAt }
    }

    private fun relevance(
        fact: ContinuityFact,
        mentionedNames: List<String>,
        useGrams: Boolean,
        distinctive: Set<String>
    ): Int = when {
        mentionedNames.any { name -> fact.statement.contains(name) } -> 2
        useGrams && grams(fact.statement).any { it in distinctive } -> 1
        else -> 0
    }

    /** 角色名 + 别号/称号。单字名也保留：只有一字的主角原本永远无法被本章捞回。 */
    private fun mentionedNames(characters: List<CharacterProfile>, hint: String): List<String> {
        if (hint.isEmpty()) return emptyList()
        return characters
            .flatMap { profileKeys(it) }
            .filter { it.isNotEmpty() && hint.contains(it) }
            .distinct()
    }

    private fun profileKeys(profile: CharacterProfile): List<String> =
        (listOf(profile.name) + profile.aliases).map { it.trim() }.filter { it.isNotEmpty() }

    private fun grams(text: String): Set<String> {
        if (text.length < 2) return emptySet()
        val out = HashSet<String>()
        for (index in 0..text.length - 2) {
            val gram = text.substring(index, index + 2)
            if (gram.any { it.isDigit() }) continue
            out += gram
        }
        return out
    }

    /**
     * 按「和本章概要有多相关」排序；同分时靠后的（也就是更新的）优先。
     *
     * 判据是「最长的公共连续片段」，不是「整段包含」也不是数共享二字组：
     * - 整段包含对规则几乎不成立。规则写「规则12：不能复活」，概要写
     *   「这一章要处理规则12的代价」，包含关系永远为假，于是 15 条规则里
     *   第 12 条照样被无声丢掉 —— 而它恰好是这一章要用的那条。
     * - 数共享二字组也区分不开：「规则12：不能复活」和「规则1：不能复活」
     *   都只共享「规则」一个二字组（grams 会丢掉带数字的组合），
     *   15 条规则全部同分，还是按存储顺序取前 8 条。
     * 最长公共连续片段能分辨：概要里的「规则12」完整出现，长度 4；
     * 「规则1」只能匹配到「规则1」，长度 3。
     */
    private fun <T> rankByRelevance(
        items: List<T>,
        hint: String,
        keys: (T) -> List<String>,
        preferNewerOnTie: Boolean
    ): List<T> {
        // 概要为空时也要走同一条排序：伏笔的同分要偏向新的那条，
        // 否则会退回「取最旧的 10 条」，用户刚加的伏笔又永远发不出去。
        return items
            .mapIndexed { index, item -> Triple(item, relevanceScore(keys(item), hint), index) }
            .sortedWith(
                if (preferNewerOnTie) {
                    compareByDescending<Triple<T, Int, Int>> { it.second }.thenByDescending { it.third }
                } else {
                    compareByDescending<Triple<T, Int, Int>> { it.second }.thenBy { it.third }
                }
            )
            .map { it.first }
    }

    private fun relevanceScore(keys: List<String>, hint: String): Int {
        // 整段命中（角色名/别名走的就是这条）最强
        if (keys.any { it.isNotEmpty() && hint.contains(it) }) return 1_000_000 + hint.length
        return keys.maxOfOrNull { longestCommonRun(it, hint) } ?: 0
    }

    /** 最长的、在 hint 里连续出现的片段长度。 */
    private fun longestCommonRun(text: String, hint: String): Int {
        if (text.isEmpty() || hint.isEmpty()) return 0
        var best = 0
        for (start in text.indices) {
            var length = 0
            while (start + length < text.length &&
                hint.contains(text.substring(start, start + length + 1))
            ) {
                length++
            }
            if (length > best) best = length
        }
        return best
    }

    private fun <T> prioritize(items: List<T>, hint: String, keys: (T) -> List<String>): List<T> {
        if (hint.isEmpty()) return items
        val (hit, miss) = items.partition { item ->
            keys(item).any { key -> key.isNotEmpty() && hint.contains(key) }
        }
        return hit + miss
    }

    private fun CharacterProfile.clipped(limit: Int) = copy(
        appearance = appearance.take(limit),
        personality = personality.take(limit),
        motivation = motivation.take(limit),
        abilities = abilities.take(limit),
        aliases = aliases.take(MAX_ALIASES),
        relationships = relationships.entries.take(4).associate { it.key.take(12) to it.value.take(limit) }
    )

    const val MAX_ALIASES = 6
}

/** 本章标题、概要、角色变化。记忆选择用它把相关旧设定从预算里捞回来。 */
fun chapterMemoryHint(title: String, summary: String, characterChanges: String? = null): String =
    listOf(title, summary, characterChanges.orEmpty())
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

/** 抽记忆时允许读进来的正文字数。 */
const val EXTRACTION_WINDOW = 6_000

/**
 * 抽记忆时给模型看哪一段。
 *
 * 以前只取 `content.takeLast(1_800)`。默认一章 4000 字，也就是只看得到最后 45%；
 * 8000 字的章只剩 22%。而「左臂在这一章断了」「他在这里说出了真实身份」这类
 * 状态变化通常写在章首或者中段 —— 抽取器根本没读过，于是它变成待确认笔记，
 * 也就永远进不了后面的章节。这就是「明明写了的事它就是记不住」。
 *
 * 所以改成头尾各取一段：状态变化多在开头，收尾多在结尾。
 */
fun excerptForExtraction(
    content: String,
    window: Int = EXTRACTION_WINDOW,
    headShare: Double = 0.5
): String {
    if (content.length <= window) return content
    val head = (window * headShare).toInt().coerceAtLeast(1)
    val tail = (window - head).coerceAtLeast(1)
    return content.take(head) + "\n……（中间略）……\n" + content.takeLast(tail)
}

/**
 * 一条抽出来的记忆。
 *
 * subject/predicate 是为了解决「矛盾」：只要两条设定的主体和属性相同，
 * 它们就是同一件事在不同时刻的状态，后一条直接顶掉前一条。
 * 只有字符串时这个判断做不了 —— 词面相似度分不出「同一件事变了」
 * 和「两件不同的事」。
 */
private data class ExtractedNote(
    val statement: String,
    val subject: String,
    val predicate: String
)

/** 把章后抽记忆的模型输出收成待确认条目；解析失败返回空，不抛。 */
fun parseMemoryNotes(raw: String, now: Long = 0L): List<ContinuityFact> {
    val jsonText = JsonResponseValidator.extractJsonValue(raw) ?: return emptyList()
    val root = runCatching { memoryNotesJson.parseToJsonElement(jsonText) }.getOrNull()
        as? kotlinx.serialization.json.JsonObject ?: return emptyList()
    return notes(root.arrayOrEmpty("facts"), "fact", now) +
        notes(root.arrayOrEmpty("threads"), "thread", now) +
        notes(root.arrayOrEmpty("resolved"), "resolved", now)
}

private fun kotlinx.serialization.json.JsonObject.arrayOrEmpty(key: String): List<ExtractedNote> =
    (this[key] as? kotlinx.serialization.json.JsonArray).orEmpty()
        .mapNotNull { element ->
            when (element) {
                // 旧格式：只有一句字符串
                is kotlinx.serialization.json.JsonPrimitive -> ExtractedNote(
                    statement = element.content,
                    subject = "",
                    predicate = ""
                )
                // 新格式：带主体和属性名
                is kotlinx.serialization.json.JsonObject -> ExtractedNote(
                    statement = element.text("statement"),
                    subject = element.text("subject"),
                    predicate = element.text("predicate")
                )
                else -> null
            }
        }

private fun kotlinx.serialization.json.JsonObject.text(key: String): String =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

private val memoryNotesJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/**
 * 两条设定的词面重合度（二元组 Dice 系数）。
 *
 * **只用于去重，不用于判断矛盾。** 实测数字：
 * - 「左臂已断，不能再持剑」vs「左臂已经接上，可以持剑了」= 0.30（真实矛盾）
 * - 「阿禾丢了玉佩」vs「白露在城南开了一间药铺」= 0.10（完全无关）
 *
 * 矛盾和无关的差距只有 0.2，靠它区分就是把运气当判据。矛盾判定走
 * subject+predicate 精确匹配（见 ContinuityFact 上的说明）。
 * 这里的阈值只定在「近乎逐字重复」这个安全区间。
 */
fun statementSimilarity(a: String, b: String): Double {
    val left = bigrams(a)
    val right = bigrams(b)
    if (left.isEmpty() || right.isEmpty()) return 0.0
    val shared = left.count { it in right }
    return 2.0 * shared / (left.size + right.size)
}

private fun bigrams(text: String): Set<String> {
    val compact = text.filter { !it.isWhitespace() }
    if (compact.length < 2) return setOf(compact)
    return (0..compact.length - 2).mapTo(HashSet()) { compact.substring(it, it + 2) }
}

private fun notes(items: List<ExtractedNote>, kind: String, now: Long): List<ContinuityFact> =
    items.map { it.statement.trim() to it }
        .filter { (statement, _) -> statement.length in 2..80 }
        .distinctBy { it.first }
        .take(6)
        .map { (statement, note) ->
            ContinuityFact(
                id = "",
                statement = statement,
                sourceChapterId = null,
                confirmed = false,
                updatedAt = now,
                kind = kind,
                subject = note.subject.trim().take(24),
                predicate = note.predicate.trim().take(24)
            )
        }
