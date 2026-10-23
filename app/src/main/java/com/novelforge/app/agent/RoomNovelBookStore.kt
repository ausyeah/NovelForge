package com.novelforge.app.agent

import com.novelforge.app.domain.model.ChapterRevision
import com.novelforge.app.domain.model.OutlineItem
import com.novelforge.app.domain.model.OutlineVersion
import com.novelforge.app.domain.model.Project
import com.novelforge.app.domain.repository.ChapterRepository
import com.novelforge.app.domain.repository.GenerationArtifactRepository
import com.novelforge.app.domain.repository.OutlineRepository
import com.novelforge.app.domain.repository.ProjectRepository
import com.novelforge.app.infrastructure.llm.ChapterContext
import com.novelforge.app.infrastructure.llm.MemorySelector
import com.novelforge.app.infrastructure.llm.chapterMemoryHint

class RoomNovelBookStore(
    private val projectRepository: ProjectRepository,
    private val outlineRepository: OutlineRepository,
    private val chapterRepository: ChapterRepository,
    private val artifacts: GenerationArtifactRepository,
    private val enqueue: suspend (projectId: String, chapter: OutlineItem, context: ChapterContext) -> String
) : NovelBookStore {
    override suspend fun project(projectId: String): Project? = projectRepository.getProject(projectId)

    override suspend fun saveProject(project: Project) {
        projectRepository.saveProject(project)
    }

    override suspend fun latestOutline(projectId: String): OutlineVersion? = outlineRepository.latest(projectId)

    override suspend fun saveOutline(version: OutlineVersion, project: Project) {
        artifacts.saveOutlineAndProject(version, project)
    }

    override suspend fun revisions(projectId: String): List<ChapterRevision> =
        chapterRepository.allForProject(projectId)

    override suspend fun queueChapter(projectId: String, outlineItemId: String): String {
        val project = projectRepository.getProject(projectId) ?: error("项目不存在")
        val outline = outlineRepository.latest(projectId) ?: error("没有大纲")
        val chapter = outline.chapters.firstOrNull { it.id == outlineItemId } ?: error("章节不存在")
        // chapterHint 不能省。少了它，MemorySelector 完全没有排序依据：
        // 规则按存储顺序取前 8 条（也就是最旧、最可能过时的 8 条），
        // 角色取前 6 个存储顺序。GenerationRuntime 那条路径传了 hint，
        // 两条入口进来的记忆内容会明显不一样 —— 用户只会读成「记忆功能不可靠」。
        val memory = MemorySelector.select(
            project.continuityState,
            inputBudget = project.creativeConfig?.inputBudget ?: 8_000,
            chapterHint = chapterMemoryHint(chapter.title, chapter.summary, chapter.characterChanges)
        )
        val previous = outline.chapters
            .filter { it.orderIndex < chapter.orderIndex }
            .maxByOrNull { it.orderIndex }
        val previousRevision = previous?.let { chapterRepository.latest(projectId, it.id) }
        return enqueue(
            projectId,
            chapter,
            ChapterContext(
                continuityState = memory.continuity,
                characters = memory.characters,
                previousSummary = previousRevision?.summary,
                previousTail = previousRevision?.content?.takeLast(1_500)
            )
        )
    }
}
