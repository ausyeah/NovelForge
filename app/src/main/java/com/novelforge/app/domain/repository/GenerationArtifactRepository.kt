package com.novelforge.app.domain.repository

import com.novelforge.app.domain.model.ChapterRevision
import com.novelforge.app.domain.model.GenerationJob
import com.novelforge.app.domain.model.LlmCall
import com.novelforge.app.domain.model.OutlineVersion
import com.novelforge.app.domain.model.Project

interface GenerationArtifactRepository {
    suspend fun saveOutlineAndProject(version: OutlineVersion, project: Project)

    /** 落新版本的同时删除指定章节及其正文与任务，并回收孤儿提示词快照（单事务） */
    suspend fun saveOutlineAndPrune(
        version: OutlineVersion,
        project: Project,
        pruneOutlineItemIds: Collection<String>,
        pruneJobTargetIds: Collection<String>
    )

    suspend fun saveOutlineResult(
        version: OutlineVersion,
        project: Project,
        job: GenerationJob,
        call: LlmCall
    )

    suspend fun saveChapterResult(
        revision: ChapterRevision,
        project: Project,
        job: GenerationJob,
        call: LlmCall
    )

    suspend fun saveJobAndLlmCall(job: GenerationJob, call: LlmCall)

    /**
     * 全新重生成大纲时清掉这本书的全部正文与生成任务（单事务）。
     * 删正文和删任务必须是同一次提交：分两次提交时进程被杀会留下
     * 「正文没了、旧任务还在」的裂状态，旧任务又会把 findActive 挡住。
     * 调用前必须先取消在跑的 WorkManager 工作，否则飞行中的 worker
     * 结束后会用 REPLACE 把已删除的任务行复活，并把旧正文挂回新目录。
     */
    suspend fun wipeChapterArtifacts(projectId: String)
}
