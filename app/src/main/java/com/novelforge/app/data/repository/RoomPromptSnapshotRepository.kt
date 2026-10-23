package com.novelforge.app.data.repository

import com.novelforge.app.data.local.PromptSnapshotDao
import com.novelforge.app.data.local.toDomain
import com.novelforge.app.data.local.toEntity
import com.novelforge.app.domain.model.PromptSnapshot
import com.novelforge.app.domain.repository.PromptSnapshotRepository

class RoomPromptSnapshotRepository(private val dao: PromptSnapshotDao) : PromptSnapshotRepository {
    override suspend fun findById(id: String): PromptSnapshot? = dao.findById(id)?.toDomain()

    override suspend fun save(snapshot: PromptSnapshot) {
        dao.insert(snapshot.toEntity())
    }
}
