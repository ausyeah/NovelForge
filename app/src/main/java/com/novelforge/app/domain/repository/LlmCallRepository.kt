package com.novelforge.app.domain.repository

import com.novelforge.app.domain.model.LlmCall

interface LlmCallRepository {
    suspend fun save(call: LlmCall)
}
