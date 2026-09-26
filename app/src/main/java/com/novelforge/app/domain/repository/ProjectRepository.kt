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

    /**
     * 每本书「有正文的章数」，按 projectId 索引。
     *
     * 点封面分流要用（有点去读，没点去写），而 `Project` 自己不知道自己有没有
     * 正文 —— 正文在 `chapter_revisions` 里。所以书架得单独问一次。
     *
     * **刻意不并进 `observeProjects()`**：`Project` 是整行读出来的，正文更是
     * 每本书几 MB。书架每次刷新把所有书的正文拉一遍，是它这个页面最不必要
     * 的一笔开销（而且这个 Flow 还进 `rememberSaveable`）。
     *
     * `content != ''` 这个条件不能省：`chapter_revisions` 里存在空正文的行
     * （写了一半被杀、或正文被清空），那种不能算「写过了」，否则点进去
     * 又是一片空目录。
     */
    fun observeWrittenChapterCounts(): Flow<Map<String, Int>>
}
