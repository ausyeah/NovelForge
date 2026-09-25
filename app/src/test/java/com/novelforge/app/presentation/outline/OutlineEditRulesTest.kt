package com.novelforge.app.presentation.outline

import com.novelforge.app.domain.model.OutlineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑缓冲的纯判定规则。
 *
 * 这些规则以前散在 OutlineScreen 的 remember 和 LaunchedEffect 里，
 * 既没法在不启动 Compose 的情况下测，测不到就等于把「不许丢用户编辑」
 * 「不许用 AI 结果覆盖手打内容」这两条承诺写在了注释里。
 */
class OutlineEditRulesTest {
    private fun chapter(id: String, title: String = "标题$id", summary: String = "概要$id") =
        OutlineItem(id = id, orderIndex = 0, title = title, summary = summary)

    // ---------- 返回键：三层判定不许分叉 ----------

    @Test
    fun back_closesDetailBeforeAnythingElse() {
        // 详情开着就算有未保存的编辑、就算润色在跑之外的一切都成立，
        // 返回也只该收起详情，绝不能顺手离开这一页
        assertEquals(
            BackDecision.CLOSE_DETAIL,
            OutlineEditRules.decideBack(detailOpen = true, wandBusy = false, hasUnsavedEdits = true)
        )
    }

    @Test
    fun back_whileWandRunning_isIgnored() {
        // 润色在跑时返回不响应：那一下多半是想取消，去按「停止优化」更明确
        assertEquals(
            BackDecision.IGNORE,
            OutlineEditRules.decideBack(detailOpen = true, wandBusy = true, hasUnsavedEdits = true)
        )
    }

    @Test
    fun back_withUnsavedEdits_asksBeforeLeaving() {
        assertEquals(
            BackDecision.ASK_BEFORE_LEAVE,
            OutlineEditRules.decideBack(detailOpen = false, wandBusy = false, hasUnsavedEdits = true)
        )
    }

    @Test
    fun back_withoutAnythingToLose_leaves() {
        assertEquals(
            BackDecision.LEAVE,
            OutlineEditRules.decideBack(detailOpen = false, wandBusy = false, hasUnsavedEdits = false)
        )
    }

    // ---------- 魔法棒：结果能不能落进草稿 ----------

    @Test
    fun optimize_appliedWhenChapterUnchangedSinceRequest() {
        val before = listOf(chapter("a"), chapter("b"))
        val optimized = chapter("b", title = "润色后的标题")

        val outcome = OutlineEditRules.applyOptimize(before, chapter("b"), optimized)

        assertTrue(outcome is OptimizeOutcome.Applied)
        val applied = outcome as OptimizeOutcome.Applied
        assertEquals(optimized, applied.items[1])
        // 撤回槽要拿优化前的原版本，否则「撤回」撤不回用户原来的措辞
        assertEquals(chapter("b"), applied.original)
    }

    @Test
    fun optimize_discardedWhenUserHandEditedWhileWaiting() {
        // 润色要等模型返回，这期间用户可能已经在概要里改了字。
        // 拿「请求时的快照」去覆盖，等于用 AI 结果抹掉用户刚打的字
        val live = listOf(chapter("a"), chapter("b", summary = "我自己改过的概要"))
        val requested = chapter("b")

        val outcome = OutlineEditRules.applyOptimize(live, requested, chapter("b", title = "润色"))

        assertEquals(OptimizeOutcome.Discarded, outcome)
    }

    @Test
    fun optimize_missingWhenChapterIsGoneFromDraft() {
        val outcome = OutlineEditRules.applyOptimize(
            listOf(chapter("a")),
            chapter("b"),
            chapter("b", title = "润色")
        )

        assertEquals(OptimizeOutcome.Missing, outcome)
    }

    // ---------- 重生成 / 未保存计数 ----------

    @Test
    fun dropTail_keepsChaptersBeforeTarget() {
        val live = listOf(chapter("a"), chapter("b"), chapter("c"), chapter("d"))

        val tail = OutlineEditRules.dropTailFrom(live, "c")

        assertEquals(listOf("c", "d"), tail.map { it.id })
    }

    @Test
    fun countEditedChapters_comparesByIdNotByIndex() {
        // 草稿和已保存版本的下标对不上（重排过），按 id 比才不会数错章
        val saved = listOf(chapter("a"), chapter("b"), chapter("c"))
        val draft = listOf(
            chapter("c", title = "改过的标题"),
            chapter("b"),
            chapter("a", summary = "改过的概要")
        )

        assertEquals(2, OutlineEditRules.countEditedChapters(draft, saved))
    }

    @Test
    fun countEditedChapters_isZeroWhenIdentical() {
        val saved = listOf(chapter("a"), chapter("b"))
        assertEquals(0, OutlineEditRules.countEditedChapters(saved, saved))
    }
}
