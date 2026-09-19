package com.novelforge.app.data.repository

import com.novelforge.app.data.local.LlmCallDao
import com.novelforge.app.data.local.toEntity
import com.novelforge.app.domain.model.LlmCall
import com.novelforge.app.domain.repository.LlmCallRepository

class RoomLlmCallRepository(private val dao: LlmCallDao) : LlmCallRepository {
    override suspend fun save(call: LlmCall) {
        dao.insert(call.toEntity())
    }
}
