package com.novelforge.app.domain.repository

import com.novelforge.app.domain.model.ContinuityState
import com.novelforge.app.domain.model.Project
import kotlinx.coroutines.flow.Flow

interface ProjectRepository {
    fun observeProjects(): Flow<List<Project>>
    suspend fun getProject(id: String): Project?
    suspend fun saveProject(project: Project)
    suspend fun deleteProject(id: String)

    /**
     * 在事务里读-改-写这本书的记忆。
     *
     * 不能用「先 getProject 拿到一份快照，改完再 saveProject 整行覆盖」：
     * 章后抽记忆（noteChapter）和用户在「本书记忆」点确认是两条并发路径，
     * 整行 REPLACE 会让后写的一方把先写的一方刚存的记忆整段抹掉 ——
     * 表现为「我刚确认的事实又变回待确认」「抽出来的事莫名其妙少了」。
     * block 收到的永远是库里的最新状态。
     */
    suspend fun mutateContinuity(id: String, block: (ContinuityState) -> ContinuityState)
}
