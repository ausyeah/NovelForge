package com.novelforge.app.agent

import com.novelforge.app.domain.model.ChapterRevision
import com.novelforge.app.domain.model.OutlineVersion
import com.novelforge.app.domain.model.Project

interface NovelBookStore {
    suspend fun project(projectId: String): Project?
    suspend fun saveProject(project: Project)
    suspend fun latestOutline(projectId: String): OutlineVersion?
    suspend fun saveOutline(version: OutlineVersion, project: Project)
    suspend fun revisions(projectId: String): List<ChapterRevision>
    suspend fun queueChapter(projectId: String, outlineItemId: String): String
}
