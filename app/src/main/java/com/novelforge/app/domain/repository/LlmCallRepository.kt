package com.novelforge.app.domain.repository

import com.novelforge.app.domain.model.LlmCall

interface LlmCallRepository {
    suspend fun save(call: LlmCall)
    /** 返回补了几条 */
    suspend fun backfillEstimates(estimate: suspend (jobId: String, purpose: String, durationMs: Long?) -> Pair<Long, Long>): Int
}
