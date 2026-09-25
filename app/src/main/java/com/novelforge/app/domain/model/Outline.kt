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
    val relationships: Map<String, String> = emptyMap(),
    /** 别名、称号、旧称。用于在本章概要里认出这个角色，正文里未必写全名。 */
    val aliases: List<String> = emptyList()
)

@Serializable
data class ContinuityState(
    val worldRules: List<String> = emptyList(),
    val timelineEvents: List<String> = emptyList(),
    val unresolvedThreads: List<String> = emptyList(),
    val factsWithSources: List<ContinuityFact> = emptyList(),
    /** 作家维护的角色档案。旧备份没有这个字段时按空列表读。 */
    val characters: List<CharacterProfile> = emptyList(),
    /** 章后抽出、尚未确认的记忆。确认前不进入下一章。 */
    val pendingFacts: List<ContinuityFact> = emptyList()
)

@Serializable
data class ContinuityFact(
    val id: String,
    val statement: String,
    val sourceChapterId: String?,
    val confirmed: Boolean = true,
    val updatedAt: Long,
    /** fact / thread / resolved。旧数据缺字段时当作已确认事实。 */
    val kind: String = "fact",
    /** 作家点「一定要记住」的条目，永远占用事实名额并排在最前。 */
    val pinned: Boolean = false,
    /**
     * 这条设定讲的是「谁」的「什么」。两条 (subject, predicate) 相同的设定
     * 一定是同一件事在两个时间点的状态，后写的顶掉先写的。
     *
     * 为什么需要它：纯词面相似度抓不住矛盾。实测
     * 「左臂已断，不能再持剑」vs「左臂已经接上，可以持剑了」只有 0.30，
     * 而「阿禾丢了玉佩」vs「白露在城南开了一间药铺」也有 0.10 ——
     * 词面相似度无法区分「同一件事变了」和「两件不同的事」。
     * 抽出主语和属性名之后，顶替就是精确匹配，不需要猜。
     * 旧数据没有这两个字段，按空串处理，走词面去重兜底。
     */
    val subject: String = "",
    val predicate: String = ""
)
