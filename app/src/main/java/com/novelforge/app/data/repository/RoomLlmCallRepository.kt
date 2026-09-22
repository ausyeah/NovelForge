package com.novelforge.app.data.repository

import com.novelforge.app.data.local.LlmCallDao
import com.novelforge.app.data.local.toEntity
import com.novelforge.app.domain.model.LlmCall
import com.novelforge.app.domain.repository.LlmCallRepository

class RoomLlmCallRepository(private val dao: LlmCallDao) : LlmCallRepository {
    override suspend fun save(call: LlmCall) {
        dao.insert(call.toEntity())
    }

    override suspend fun backfillEstimates(
        estimate: suspend (jobId: String, purpose: String, durationMs: Long?) -> Pair<Long, Long>
    ): Int {
        val rows = dao.findNullUsage()
        rows.forEach { row ->
            val (input, output) = estimate(row.jobId, row.purpose, row.durationMs)
            if (input > 0 || output > 0) dao.estimateTokensIfMissing(row.id, input, output)
        }
        return rows.size
    }
}
