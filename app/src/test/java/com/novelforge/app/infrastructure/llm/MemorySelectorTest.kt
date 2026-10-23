package com.novelforge.app.infrastructure.llm

import com.novelforge.app.domain.model.CharacterProfile
import com.novelforge.app.domain.model.ContinuityFact
import com.novelforge.app.domain.model.ContinuityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySelectorTest {
    @Test
    fun select_dropsPendingAndHonorsExclusions() {
        val state = ContinuityState(
            worldRules = listOf("不能复活"),
            unresolvedThreads = listOf("玉佩", "旧案"),
            characters = listOf(
                CharacterProfile(id = "a", projectId = "p", name = "阿禾", appearance = "左眼有疤"),
                CharacterProfile(id = "b", projectId = "p", name = "老周", appearance = "白发")
            ),
            factsWithSources = listOf(
                ContinuityFact("f1", "阿禾丢了玉佩", "c1", confirmed = true, updatedAt = 1)
            ),
            pendingFacts = listOf(
                ContinuityFact("p1", "还没确认", "c1", confirmed = false, updatedAt = 2)
            )
        )
        val slice = MemorySelector.select(
            state,
            excludedCharacterIds = setOf("b"),
            excludedThreads = setOf("旧案")
        )
        assertEquals(listOf("阿禾"), slice.characterNames)
        assertEquals(listOf("玉佩"), slice.threads)
        assertEquals(1, slice.factCount)
        assertTrue(slice.continuity.pendingFacts.isEmpty())
        assertFalse(slice.continuity.factsWithSources.any { it.statement == "还没确认" })
    }

    @Test
    fun select_keepsOnlyTheNewestFacts() {
        val facts = (1..20).map {
            ContinuityFact("f$it", "事实$it", null, confirmed = true, updatedAt = it.toLong())
        }
        val slice = MemorySelector.select(ContinuityState(factsWithSources = facts))
        assertEquals(MemorySelector.MAX_FACTS, slice.factCount)
        assertEquals("事实20", slice.continuity.factsWithSources.last().statement)
        assertEquals("事实9", slice.continuity.factsWithSources.first().statement)
    }

    /**
     * 回归：伏笔是往后追加的。旧实现直接 take(10)，用户刚在「本书记忆」里
     * 加的伏笔排在第 11 位以后，就再也不会进任何一章的 prompt。
     */
    @Test
    fun newestThreadSurvivesWhenThereAreMoreThanTheBudget() {
        val threads = (1..14).map { "伏笔$it" }
        val slice = MemorySelector.select(ContinuityState(unresolvedThreads = threads))
        assertEquals(MemorySelector.MAX_THREADS, slice.threads.size)
        assertTrue("最新加入的伏笔必须留下：${slice.threads}", slice.threads.contains("伏笔14"))
        assertEquals(4, slice.omittedThreads)
    }

    /**
     * 回归：作家自己写的「不能违反的规则」第 9 条以后会被无声丢掉。
     * 命中本章概要的规则要优先，并且剩下的条数必须报出来。
     */
    @Test
    fun ruleBeyondTheCapIsPrioritizedByTheChapterHintAndTheRestIsReported() {
        val rules = (1..15).map { "规则$it：不能复活" }
        val slice = MemorySelector.select(
            ContinuityState(worldRules = rules),
            chapterHint = "这一章要处理规则12的代价"
        )
        assertEquals(MemorySelector.MAX_RULES, slice.continuity.worldRules.size)
        assertTrue(
            "本章用得上的规则要挤掉无关的：${slice.continuity.worldRules}",
            slice.continuity.worldRules.contains("规则12：不能复活")
        )
        assertEquals(7, slice.omittedRules)
    }

    /**
     * 回归：不含任何角色名的旧设定（地点、法则、物品、债务）以前永远排在淘汰区，
     * 攒够 12 条新事实就再也回不来。只要和本章概要共享一个有区分度的词就该留下。
     */
    @Test
    fun oldFactWithoutAnyCharacterNameIsNotDoomedByRecency() {
        val facts = listOf(
            ContinuityFact("old", "灵石只够买三枚，第二枚已被拿走", "c1", confirmed = true, updatedAt = 1)
        ) + (2..20).map {
            ContinuityFact("n$it", "杂事$it：今天集市照常开张", null, confirmed = true, updatedAt = it.toLong())
        }
        val slice = MemorySelector.select(
            ContinuityState(factsWithSources = facts),
            chapterHint = "主角去集市买灵石"
        )
        assertTrue(
            "没点名角色的旧设定也要能被本章捞回：${slice.continuity.factsWithSources.map { it.statement }}",
            slice.continuity.factsWithSources.any { it.id == "old" }
        )
    }

    /** 到处都是的二字组不算命中，否则会把真正相关的设定挤出名额。 */
    @Test
    fun commonFillersDoNotCountAsRelevance() {
        val facts = (1..20).map {
            ContinuityFact("f$it", "杂事$it：今天的事说完了", null, confirmed = true, updatedAt = it.toLong())
        }
        val slice = MemorySelector.select(
            ContinuityState(factsWithSources = facts),
            chapterHint = "今天说完了的事情"
        )
        // 到处都有的填充词不算命中，于是完全退回「取最新 12 条」
        assertEquals(12, slice.factCount)
        assertTrue(
            slice.continuity.factsWithSources.first().statement.startsWith("杂事9")
        )
    }

    /** 置顶的旧设定永远在名额里，哪怕它比所有事实都老。 */
    @Test
    fun pinnedFactSurvivesAnUnrelatedChapter() {
        val facts = listOf(
            ContinuityFact("old", "玉佩是沈家传了七代的东西", "c1", confirmed = true, updatedAt = 1, pinned = true)
        ) + (2..20).map {
            ContinuityFact("n$it", "杂事$it", null, confirmed = true, updatedAt = it.toLong())
        }
        val slice = MemorySelector.select(
            ContinuityState(factsWithSources = facts),
            chapterHint = "今天在集市上买了两个包子"
        )
        val kept = slice.continuity.factsWithSources
        assertTrue("置顶事实必须留下：${kept.map { it.statement }}", kept.any { it.id == "old" })
        assertEquals(MemorySelector.MAX_FACTS, kept.size)
    }

    /**
     * 回归：`pinned` 只保住了名额，「排在最前面」是假的。
     *
     * 名额排序里 `pinned` 确实是第一键，所以置顶事实一定活下来了；
     * 但收尾那一步以前是 `sortedBy { it.updatedAt }`（纯按时间升序），
     * 把刚排好的置顶顺序整个重排掉了。这里特意置顶**最新**的那条：
     * 它落在保留下来的 12 条的最后一位，旧代码会把 12 条杂事的第 9 条放在最前。
     *
     * 这个列表是原样序列化进【连续性状态】的（PromptBuilder.buildChapterPrompt），
     * 所以顺序对模型可见，不是内部实现细节。
     */
    @Test
    fun theNewestPinnedFactStillLeadsThePromptOrder() {
        val facts = (1..20).map {
            ContinuityFact("f$it", "杂事$it", null, confirmed = true, updatedAt = it.toLong())
        }.map { if (it.id == "f20") it.copy(pinned = true) else it }

        val kept = MemorySelector
            .select(ContinuityState(factsWithSources = facts))
            .continuity.factsWithSources

        assertEquals(MemorySelector.MAX_FACTS, kept.size)
        assertEquals("置顶事实必须排在事实列表最前面，它要先被读到", "f20", kept.first().id)
    }

    /**
     * 两条挑选路径都要兑现「置顶在前」：概要为空的那条，和概要有区分度词的那条。
     *
     * 同时钉住**剩下的事实仍然从旧到新**。收尾那一步按时间排本来是有用的
     * （模型读的是一条条状态变迁：先断臂、后接上），不能为了置顶把它整个删掉。
     * 一条很老的置顶 + 一条很新的置顶，顺便钉住置顶组内部也还是从旧到新。
     */
    @Test
    fun pinnedFactsLeadAndTheRestStayChronologicalOnBothSelectionPaths() {
        val facts = (1..20).map { index ->
            ContinuityFact(
                id = "f$index",
                statement = "杂事$index：今天说完了",
                sourceChapterId = null,
                confirmed = true,
                updatedAt = index.toLong(),
                pinned = index == 4 || index == 17
            )
        }
        val state = ContinuityState(factsWithSources = facts)

        for (hint in listOf("", "主角在集市上买灵石")) {
            val where = "chapterHint=<$hint>"
            val kept = MemorySelector.select(state, chapterHint = hint).continuity.factsWithSources

            assertEquals("$where 条数", MemorySelector.MAX_FACTS, kept.size)
            assertEquals(
                "$where 置顶组要在最前，且组内从旧到新",
                listOf("f4", "f17"),
                kept.take(2).map { it.id }
            )
            val rest = kept.drop(2).map { it.updatedAt }
            assertEquals("$where 其余事实仍须从旧到新", rest.sorted(), rest)
        }
    }

    /**
     * 置顶多到吃满名额时，位置也得对：活下来的全是置顶，仍然从前到后按时间排。
     *
     * 顺带把一个不好但真实的后果钉在这里：置顶超过 12 条时，
     * 未置顶的事实一条都进不了下一章。名额优先级是既有设计（`pinned` 是第一键），
     * 这里只保证它不会再被收尾那步重排打乱。
     */
    @Test
    fun whenPinnedFactsExceedTheBudgetEveryKeptFactIsPinnedAndStillOrdered() {
        val facts = (1..20).map {
            ContinuityFact("f$it", "杂事$it", null, confirmed = true, updatedAt = it.toLong(), pinned = true)
        }

        val kept = MemorySelector
            .select(ContinuityState(factsWithSources = facts))
            .continuity.factsWithSources

        assertEquals(MemorySelector.MAX_FACTS, kept.size)
        assertTrue(kept.all { it.pinned })
        // 名额排序取的是最新的 12 条置顶，呈现顺序再把它们按从旧到新摊开
        assertEquals(listOf("f9", "f10", "f11", "f12"), kept.take(4).map { it.id })
        assertEquals(kept.map { it.updatedAt }.sorted(), kept.map { it.updatedAt })
    }

    /** 单字名主角以前结构上无法被捞回。 */
    @Test
    fun singleCharacterNameIsStillMatched() {
        val state = ContinuityState(
            characters = listOf(CharacterProfile(id = "a", projectId = "p", name = "墨")),
            factsWithSources = (1..20).map {
                ContinuityFact("f$it", "杂事$it", null, confirmed = true, updatedAt = it.toLong())
            } + listOf(
                ContinuityFact("mo", "墨的左手有旧伤", "c1", confirmed = true, updatedAt = 1)
            )
        )
        val slice = MemorySelector.select(state, chapterHint = "墨又在发呆了")
        assertTrue(slice.continuity.factsWithSources.any { it.id == "mo" })
    }

    /** 称号/别名也要能把角色捞回来，正文里未必写全名。 */
    @Test
    fun aliasInTheHintCountsAsAMention() {
        val state = ContinuityState(
            characters = listOf(
                CharacterProfile(id = "a", projectId = "p", name = "叶青云", aliases = listOf("剑尊"))
            )
        )
        val slice = MemorySelector.select(state, chapterHint = "剑尊在剑冢闭关")
        assertEquals(listOf("叶青云"), slice.characterNames)
    }

    /** 同一句话被重复写入只该占一个名额。 */
    @Test
    fun duplicateStatementsCostOneSlot() {
        val facts = listOf(
            ContinuityFact("a", "阿禾丢了玉佩", "c1", confirmed = true, updatedAt = 1),
            ContinuityFact("b", "阿禾丢了玉佩", "c2", confirmed = true, updatedAt = 9)
        ) + (1..20).map {
            ContinuityFact("n$it", "杂事$it", null, confirmed = true, updatedAt = it.toLong())
        }
        val slice = MemorySelector.select(ContinuityState(factsWithSources = facts))
        val statements = slice.continuity.factsWithSources.map { it.statement }
        assertEquals(1, statements.count { it == "阿禾丢了玉佩" })
    }

    /**
     * 回归：抽记忆以前只看章节最后 1800 字。默认一章 4000 字，
     * 也就是 55% 的正文从来没被读过；而「左臂在这一章断了」这类状态变化
     * 通常写在章首，于是它永远不会变成待确认笔记，也就永远进不了后面的章节。
     */
    @Test
    fun extractionWindowCoversTheHeadNotJustTheTail() {
        val chapter = "章首：沈砚的左臂在这一章被齐渊斩断，再也拿不起剑。" + "中段铺垫。".repeat(400) +
            "章尾：雨停了。"
        assertTrue("章首必须能被读到", excerptForExtraction(chapter).contains("左臂"))
        assertTrue("章尾必须能被读到", excerptForExtraction(chapter).contains("雨停了"))
    }

    @Test
    fun extractionWindowLeavesShortChaptersAlone() {
        val short = "很短的一章。"
        assertEquals(short, excerptForExtraction(short))
    }

    /** 短窗口下也要同时保住头尾，否则会漏掉中段。 */
    @Test
    fun extractionWindowSplicesHeadAndTail() {
        val chapter = "H" + "x".repeat(200) + "T"
        val excerpt = excerptForExtraction(chapter, window = 40)
        assertTrue(excerpt.startsWith("H"))
        assertTrue(excerpt.endsWith("T"))
    }

    /**
     * 词面相似度**抓不住矛盾**，这不是 bug 是事实。
     * 下面两个数字钉住了实测值：矛盾对 0.30，无关对 0.00。
     * 能抓矛盾的阈值（≤0.30）同样会抓走大量只是用词相近的无关设定，
     * 所以矛盾判定必须靠 subject+predicate 精确匹配，不能靠调低这个阈值。
     */
    @Test
    fun statementSimilarityCannotDistinguishContradictionFromUnrelated() {
        val contradiction = statementSimilarity("左臂已断，不能再持剑", "左臂已经接上，可以持剑了")
        val unrelated = statementSimilarity("阿禾丢了玉佩", "白露在城南开了一间药铺")
        assertEquals("矛盾对的词面相似度", 0.3, contradiction, 0.05)
        assertEquals("无关对的词面相似度", 0.0, unrelated, 0.05)
    }

    /**
     * 去重只需要抓「同一条设定被重复抽出来」这一种情况，
     * 差别通常只是空格和标点 —— 那些不算字符bigram，所以能被抓住。
     */
    @Test
    fun statementSimilarityCatchesRepeatedExtractionOfTheSameFact() {
        val original = "阿禾的玉佩掉在井边了"
        val respelled = "阿禾的玉佩，掉在井边了。"
        assertTrue(
            "同一条设定换个标点再抽一次必须能被认出来：${statementSimilarity(original, respelled)}",
            statementSimilarity(original, respelled) >= 0.6
        )
        assertEquals(
            "忽略空白和标点后应当完全一致",
            1.0,
            statementSimilarity("阿禾 丢了 玉佩", "阿禾丢了玉佩"),
            0.001
        )
    }

    /** 抽记忆的两种输出形状都要认：旧版纯字符串、新版带主体和属性名。 */
    @Test
    fun parseMemoryNotesAcceptsBothStringAndStructuredForms() {
        val raw = """
            {"facts":["阿禾左眼有疤",
                      {"statement":"沈砚的左臂已接上","subject":"沈砚","predicate":"左臂状态"}],
             "threads":["玉佩还没找回"],"resolved":[]}
        """.trimIndent()
        val notes = parseMemoryNotes(raw, now = 5)
        assertEquals(3, notes.size)
        val structured = notes.first { it.statement == "沈砚的左臂已接上" }
        assertEquals("沈砚", structured.subject)
        assertEquals("左臂状态", structured.predicate)
        // 旧格式必须仍能解析，只是没有主体/属性名
        assertEquals("", notes.first { it.statement == "阿禾左眼有疤" }.subject)
    }

    @Test
    fun omittedCountsReflectWhatWasActuallyDropped() {
        val slice = MemorySelector.select(
            ContinuityState(
                worldRules = (1..11).map { "规则$it" },
                unresolvedThreads = (1..13).map { "伏笔$it" },
                characters = (1..9).map {
                    CharacterProfile(id = "c$it", projectId = "p", name = "角色$it")
                }
            )
        )
        assertEquals(3, slice.omittedRules)
        assertEquals(3, slice.omittedThreads)
        assertEquals(3, slice.omittedCharacters)
    }

    @Test
    fun parseMemoryNotes_readsFencedJsonAndDropsGarbage() {
        val raw = """
            好的。
            ```json
            {"facts":["阿禾左眼有疤","x"],"threads":["玉佩还没找回"],"resolved":["客栈的债已还"]}
            ```
        """.trimIndent()
        val notes = parseMemoryNotes(raw, now = 9)
        assertEquals(listOf("fact", "thread", "resolved"), notes.map { it.kind })
        assertTrue(notes.none { it.statement == "x" })
        assertTrue(notes.all { !it.confirmed })
    }

    @Test
    fun parseMemoryNotes_blankOrProseReturnsEmpty() {
        assertTrue(parseMemoryNotes("模型没按格式回答").isEmpty())
        assertTrue(parseMemoryNotes("").isEmpty())
    }
}
