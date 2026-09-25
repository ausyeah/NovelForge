package com.novelforge.app.infrastructure.backup

import com.novelforge.app.domain.model.ChapterRevision
import com.novelforge.app.domain.model.OutlineVersion
import androidx.room.withTransaction
import com.novelforge.app.data.local.ChapterRevisionDao
import com.novelforge.app.data.local.OutlineVersionDao
import com.novelforge.app.data.local.ProjectDao
import com.novelforge.app.data.local.toEntity
import com.novelforge.app.domain.model.Project
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

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
    suspend fun export(projectId: String, output: OutputStream) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val project = requireNotNull(projectRepository.getProject(projectId)) { "项目不存在" }
        val backup = NovelBackup(
            exportedAt = System.currentTimeMillis(),
            project = project,
            outlineVersions = outlineRepository.allForProject(projectId),
            chapterRevisions = chapterRepository.allForProject(projectId)
        )
        encodeBackup(backup, output)
    }

    suspend fun import(input: InputStream): Project = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val backup = decodeBackup(input)
        require(backup.format == 1) { "备份格式版本 ${backup.format} 不被支持（当前仅支持 v1）" }
        // 书名先在整排书架上错开：同一份备份导两次，旧代码会得到两本同名书，
        // 书架、导出文件名、分享出去全都分不清哪本是哪本
        val plan = planRestore(
            backup = backup,
            takenTitles = projectRepository.observeProjects().first().map { it.title }
        )
        // 单事务落库：中途失败整体回滚，绝不留"项目有、大纲半本"的残书
        database.withTransaction {
            writeRestoredBook(
                book = plan,
                projectDao = database.projectDao(),
                outlineDao = database.outlineVersionDao(),
                chapterDao = database.chapterRevisionDao()
            )
        }
        requireNotNull(projectRepository.getProject(plan.project.id)) { "导入后回读失败" }
    }

    fun suggestedFileName(title: String): String {
        val safe = title.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').ifBlank { "未命名" }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT)
            .format(java.util.Date())
        return "NovelForge备份-$safe-$stamp.json"
    }
}

/** 一次导入算好的落库清单：事务里只按它原样写，不再做任何判断。 */
internal data class RestoredBook(
    val project: Project,
    val outlineVersions: List<OutlineVersion>,
    val chapterRevisions: List<ChapterRevision>
)

// 存档要向后兼容：未知字段忽略、缺省字段可用
private val backupJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

/**
 * 直接把 JSON 写进目标流。encodeToString 会先拼出一个全量 UTF-16 String、再 toByteArray
 * 复制一份字节数组，300 章 / 90 万字的书光这两份临时对象就是全书的两三倍，
 * 2GB 机器上很容易在写盘时 OOM；kotlinx 的流式编码器自己按 UTF-8 分块写出
 * （不依赖平台默认编码），峰值只比对象本身多一块缓冲区。
 * 落盘格式与旧的 encodeToString 完全一致，旧备份照样能导入。
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun encodeBackup(backup: NovelBackup, output: OutputStream) {
    backupJson.encodeToStream(NovelBackup.serializer(), backup, output)
}

/** 导入同样走流式解析：readBytes().toString() 会让整份备份在堆里同时存在 bytes 和 String 两份。 */
@OptIn(ExperimentalSerializationApi::class)
internal fun decodeBackup(input: InputStream): NovelBackup =
    backupJson.decodeFromStream(NovelBackup.serializer(), input)

/**
 * 导入前的重映射计划：纯函数，不碰数据库。
 *
 * id 全部重发、引用完整性校验、书名去重都在事务之前做完，事务里只负责把算好的清单原样落库。
 * newId 可注入，方便测试断言 id 重映射结果。
 */
internal fun planRestore(
    backup: NovelBackup,
    takenTitles: Collection<String> = emptyList(),
    newId: () -> String = { UUID.randomUUID().toString() }
): RestoredBook {
    require(backup.project.id.isNotBlank() && backup.outlineVersions.isNotEmpty()) {
        "备份文件不完整：没有大纲版本"
    }
    val newProjectId = newId()
    val versionMap = backup.outlineVersions.associate { it.id to newId() }
    val importedOutlines = backup.outlineVersions.map { version ->
        version.copy(id = versionMap.getValue(version.id), projectId = newProjectId)
    }
    val activeNewId = versionMap[backup.project.activeOutlineVersionId]
        ?: importedOutlines.maxByOrNull { it.version }?.id
    // 悬空引用必须在这里拦下来，而不是落库后再说：
    // 指向不存在的大纲版本的修订，在任何章节列表里都点不出来，却占着
    // (projectId, outlineItemId, revision) 唯一索引的一个槽位；而 GenerationRuntime
    // 给新章节发的是 "chapter-N" 这类可推导的 id，一条野生的 chapter-7 迟早和新生成的
    // chapter-7 撞号，upsertAll 是 REPLACE —— 真正的正文会被那份陈旧数据悄悄顶掉。
    val itemIdsByVersion = backup.outlineVersions.associate { version ->
        version.id to version.chapters.mapTo(HashSet()) { it.id }
    }
    val danglingVersionCount = backup.chapterRevisions.count { versionMap[it.outlineVersionId] == null }
    require(danglingVersionCount == 0) {
        "备份文件有 $danglingVersionCount 条正文指向备份里不存在的大纲版本，已中止导入"
    }
    val danglingItemCount = backup.chapterRevisions.count { revision ->
        revision.outlineItemId !in (itemIdsByVersion[revision.outlineVersionId] ?: emptySet())
    }
    require(danglingItemCount == 0) {
        "备份文件有 $danglingItemCount 条正文指向大纲里不存在的章节，已中止导入"
    }
    val importedProject = backup.project.copy(
        id = newProjectId,
        title = distinctImportTitle(backup.project.title, takenTitles),
        activeOutlineVersionId = activeNewId,
        updatedAt = System.currentTimeMillis(),
        // 角色档案自带 projectId，而且会随 continuityState 序列化进每一次提示词：
        // 不跟着改的话，导入的书会把原作者的 projectId 发给模型服务。
        // 只改 projectId，不动角色自己的 id —— relationships / aliases 里引用的还是它。
        continuityState = backup.project.continuityState.copy(
            characters = backup.project.continuityState.characters.map { it.copy(projectId = newProjectId) }
        )
    )
    val revisions = backup.chapterRevisions.map { revision ->
        revision.copy(
            id = newId(),
            projectId = newProjectId,
            // 校验已保证命中，绝不再退化成空串
            outlineVersionId = versionMap.getValue(revision.outlineVersionId)
        )
    }
    return RestoredBook(
        project = importedProject,
        outlineVersions = importedOutlines,
        chapterRevisions = revisions
    )
}

/**
 * 同名书在书架上没法区分，所以导入时把标题错开：第二本叫「原名（导入副本 2）」，
 * 第三本「（导入副本 3）」……序号一直往上跳到没被占用为止，同一份输入的结果是确定的。
 * 不冲突时原样保留书名。
 */
internal fun distinctImportTitle(title: String, taken: Collection<String>): String {
    val used = taken.toHashSet()
    val base = title.trim().ifBlank { "未命名" }
    if (base !in used) return title
    var copy = 2
    while (true) {
        val candidate = "$base（导入副本 $copy）"
        if (candidate !in used) return candidate
        copy++
    }
}

/**
 * 事务内只做纯写入。抽成独立函数是为了能在没有 Room 的单元测试里核对落库清单，
 * 生产路径外面依旧包着 withTransaction。
 */
internal suspend fun writeRestoredBook(
    book: RestoredBook,
    projectDao: ProjectDao,
    outlineDao: OutlineVersionDao,
    chapterDao: ChapterRevisionDao
) {
    projectDao.upsert(book.project.toEntity())
    outlineDao.upsertAll(book.outlineVersions.map { it.toEntity() })
    chapterDao.upsertAll(book.chapterRevisions.map { it.toEntity() })
}
