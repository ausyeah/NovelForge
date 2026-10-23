package com.novelforge.app.domain.repository

import com.novelforge.app.domain.model.PromptSnapshot

interface PromptSnapshotRepository {
    suspend fun findById(id: String): PromptSnapshot?
    suspend fun save(snapshot: PromptSnapshot)
}
