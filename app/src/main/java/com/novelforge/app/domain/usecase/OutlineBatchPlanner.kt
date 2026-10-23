package com.novelforge.app.domain.usecase

/**
 * 大纲「分批滚动推进」的唯一规则来源。
 *
 * 超长篇（几百上千章）绝不能让模型一次把全书大纲写完：上下文会爆、后面的章
 * 会越写越空。这里的规则是：
 *
 * - 一次只请求/接受 [BATCH_SIZE] 章（默认 10 章）；
 * - 本批完成后任务置为 PAUSED，先把这批正文写完，再继续下一批；
 * - 模型超量返回时，多出来的部分直接丢弃（剩下的章留给后续批次，
 *   丢掉的只是尚未请求的未来章节，不会丢已生成内容）。
 *
 * 抽成纯函数是为了能直接单测：GenerationRuntime 需要 Android Context，测不了。
 */
object OutlineBatchPlanner {

    /** 一个批次的章节数。改这里就等于改「一次生成多少章大纲」。 */
    const val BATCH_SIZE = 10

    /**
     * 批大小的合法区间与用户设置的夹取。
     *
     * 「每批几章大纲」现在是设置项（设置 → 写作节奏），但上限不能放开到
     * 全书章数：一批 50 章概要在默认 5000 token 的输出预算下几乎必被截断
     * （截断的 JSON 整单作废），所以给个 30 的硬顶，用户要更长就多跑几批。
     */
    const val MIN_BATCH_SIZE = 1
    const val MAX_BATCH_SIZE = 30

    /** 默认批大小：与 [BATCH_SIZE] 同值，供设置存储等不依赖本对象常量的地方引用 */
    const val DEFAULT_BATCH_SIZE = BATCH_SIZE

    fun clampBatchSize(value: Int): Int = value.coerceIn(MIN_BATCH_SIZE, MAX_BATCH_SIZE)

    /**
     * 本次请求覆盖的章节区间（闭区间，章号从 1 开始）。
     *
     * @param chapterNumber 本次起始章号（= 已生成到的下一章）
     * @param chapterCount 全书计划章数
     */
    fun window(
        chapterNumber: Int,
        chapterCount: Int,
        batchSize: Int = BATCH_SIZE
    ): IntRange {
        if (chapterCount <= 0) return IntRange.EMPTY
        val start = chapterNumber.coerceIn(1, chapterCount)
        val end = minOf(start + batchSize.coerceAtLeast(1) - 1, chapterCount)
        return start..end
    }

    /**
     * 模型返回 [returned] 项时，本批真正接受多少项。
     *
     * 双重限制：不超过批大小（保证滚动），也不超过全书剩余章数（不越界）。
     */
    fun acceptedCount(
        returned: Int,
        remainingInBook: Int,
        batchSize: Int = BATCH_SIZE
    ): Int {
        val limit = minOf(batchSize.coerceAtLeast(1), remainingInBook.coerceAtLeast(0))
        return returned.coerceAtLeast(0).coerceAtMost(limit)
    }

    /**
     * 本批落库后是否应当暂停（等正文写完再继续下一批）。
     *
     * @param nextOrderIndex 本批开始前的进度（= 已生成章节数）
     * @param accepted 本批实际接受并落库的章节数
     */
    fun shouldPause(
        nextOrderIndex: Int,
        accepted: Int,
        totalChapterCount: Int
    ): Boolean = nextOrderIndex + accepted < totalChapterCount
}
