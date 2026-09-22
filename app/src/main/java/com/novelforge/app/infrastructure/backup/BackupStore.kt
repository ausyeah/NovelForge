package com.novelforge.app.infrastructure.backup

import com.novelforge.app.domain.model.ChapterRevision
import com.novelforge.app.domain.model.OutlineVersion
import androidx.room.withTransaction
import com.novelforge.app.data.local.toEntity
import com.novelforge.app.domain.model.Project
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 运行期把 Application 挂进来，屏幕层随处可取仓库（与 GenerationWorkerDependenciesProvider 同思路） */
object NovelForgeRefs {
    lateinit var application: com.novelforge.app.NovelForgeApplication
}

@Serializable
data class NovelBackup(
    val format: Int = 1,
    val exportedAt: Long = 0,
    val project: Project,
    val outlineVersions: List<OutlineVersion>,
    val chapterRevisions: List<ChapterRevision> = emptyList()
)

/**
 * 整书备份：项目 + 全部大纲版本 + 全部正文修订，导出为单个 JSON 文件。
 * API Key 存 Keystore、生成任务不进备份；导入时全部重新分配 id，绝不覆盖现有书。
 */
class BackupStore(
    private val database: com.novelforge.app.data.local.AppDatabase,
    private val projectRepository: com.novelforge.app.domain.repository.ProjectRepository,
    private val outlineRepository: com.novelforge.app.domain.repository.OutlineRepository,
    private val chapterRepository: com.novelforge.app.domain.repository.ChapterRepository
) {
    // 存档要向后兼容：未知字段忽略、缺省字段可用
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    suspend fun export(projectId: String, output: OutputStream) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val project = requireNotNull(projectRepository.getProject(projectId)) { "项目不存在" }
        val backup = NovelBackup(
            exportedAt = System.currentTimeMillis(),
            project = project,
            outlineVersions = outlineRepository.allForProject(projectId),
            chapterRevisions = chapterRepository.allForProject(projectId)
        )
        output.write(json.encodeToString(NovelBackup.serializer(), backup).toByteArray(Charsets.UTF_8))
    }

    suspend fun import(input: InputStream): Project = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val backup = json.decodeFromString(
            NovelBackup.serializer(),
            input.readBytes().toString(Charsets.UTF_8)
        )
        require(backup.format == 1) { "备份格式版本 ${backup.format} 不被支持（当前仅支持 v1）" }
        require(backup.project.id.isNotBlank() && backup.outlineVersions.isNotEmpty()) {
            "备份文件不完整：没有大纲版本"
        }
        val newProjectId = UUID.randomUUID().toString()
        val versionMap = backup.outlineVersions.associate { it.id to UUID.randomUUID().toString() }
        val importedOutlines = backup.outlineVersions.map { v ->
            v.copy(id = requireNotNull(versionMap[v.id]), projectId = newProjectId)
        }
        val activeNewId = versionMap[backup.project.activeOutlineVersionId]
            ?: importedOutlines.maxByOrNull { it.version }?.id
        val importedProject = backup.project.copy(
            id = newProjectId,
            activeOutlineVersionId = activeNewId,
            updatedAt = System.currentTimeMillis()
        )
        val revisions = backup.chapterRevisions.map { rev ->
            rev.copy(
                id = UUID.randomUUID().toString(),
                projectId = newProjectId,
                outlineVersionId = versionMap[rev.outlineVersionId] ?: activeNewId.orEmpty()
            )
        }
        // 单事务落库：中途失败整体回滚，绝不留"项目有、大纲半本"的残书
        database.withTransaction {
            database.projectDao().upsert(importedProject.toEntity())
            database.outlineVersionDao().upsertAll(importedOutlines.map { it.toEntity() })
            database.chapterRevisionDao().upsertAll(revisions.map { it.toEntity() })
        }
        requireNotNull(projectRepository.getProject(newProjectId)) { "导入后回读失败" }
    }

    fun suggestedFileName(title: String): String {
        val safe = title.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').ifBlank { "未命名" }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT)
            .format(java.util.Date())
        return "NovelForge备份-$safe-$stamp.json"
    }
}
