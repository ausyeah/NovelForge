package com.novelforge.app.infrastructure.backup

import com.novelforge.app.data.local.ChapterRevisionDao
import com.novelforge.app.data.local.ChapterRevisionEntity
import com.novelforge.app.data.local.OutlineVersionDao
import com.novelforge.app.data.local.OutlineVersionEntity
import com.novelforge.app.data.local.ProjectDao
import com.novelforge.app.data.local.ProjectEntity
import com.novelforge.app.data.local.toDomain
import com.novelforge.app.domain.model.ChapterRevision
import com.novelforge.app.domain.model.ChapterStatus
import com.novelforge.app.domain.model.CharacterProfile
import com.novelforge.app.domain.model.ContinuityState
import com.novelforge.app.domain.model.FlowState
import com.novelforge.app.domain.model.OutlineItem
import com.novelforge.app.domain.model.OutlineVersion
import com.novelforge.app.domain.model.Project
import com.novelforge.app.domain.model.ProjectStatus
import com.novelforge.app.domain.model.QuestData
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupStoreTest {

    // ---------- 引用完整性：悬空引用必须在大纲之外被拒，而不是写空串/留野行 ----------

    @Test
    fun planRestore_rejectsRevisionsPointingAtAnOutlineVersionTheBackupDoesNotContain() {
        val backup = sampleBackup().copy(
            chapterRevisions = listOf(
                revision(id = "r-1", itemId = "chapter-1", versionId = "outline-1"),
                revision(id = "r-2", itemId = "chapter-1", versionId = "ghost-version"),
                revision(id = "r-3", itemId = "chapter-2", versionId = "ghost-version")
            )
        )

        val error = expectFailure { planRestore(backup) }

        assertTrue("报错要带上条数，实际：${error.message}", error.message!!.contains("2"))
        assertTrue("实际：${error.message}", error.message!!.contains("大纲版本"))
    }

    @Test
    fun planRestore_rejectsRevisionsPointingAtItemsMissingFromTheOutline() {
        val backup = sampleBackup().copy(
            chapterRevisions = listOf(
                revision(id = "r-1", itemId = "chapter-1", versionId = "outline-1"),
                // chapter-7 看着像 GenerationRuntime 会发的 id，但这份大纲里没有它
                revision(id = "r-2", itemId = "chapter-7", versionId = "outline-1")
            )
        )

        val error = expectFailure { planRestore(backup) }

        assertTrue("报错要带上条数，实际：${error.message}", error.message!!.contains("1"))
        assertTrue("实际：${error.message}", error.message!!.contains("章节"))
    }

    @Test
    fun planRestore_rejectsRevisionBorrowingAnItemFromAnotherOutlineVersion() {
        val backup = sampleBackup().copy(
            chapterRevisions = listOf(
                // chapter-3 只在 outline-2 里，挂在 outline-1 下就是错位的
                revision(id = "r-1", itemId = "chapter-3", versionId = "outline-1")
            )
        )

        expectFailure { planRestore(backup) }
    }

    @Test
    fun planRestore_rejectsIncompleteBackupBeforeTouchingAnything() {
        val error = expectFailure { planRestore(sampleBackup().copy(outlineVersions = emptyList())) }

        assertTrue("实际：${error.message}", error.message!!.contains("没有大纲版本"))
    }

    // ---------- id 重映射：所有引用都要跟着走到新空间 ----------

    @Test
    fun planRestore_rewritesEveryIdAndLeavesNoDanglingReference() {
        val backup = sampleBackup()
        val plan = planRestore(backup, newId = countingIds())

        assertEquals("new-1", plan.project.id)
        assertEquals("new-2", plan.outlineVersions[0].id)
        assertEquals("new-3", plan.outlineVersions[1].id)
        assertEquals("new-2", plan.project.activeOutlineVersionId)
        // 每条正文都要有自己的新 id，并且指向重发后的大纲版本
        plan.chapterRevisions.forEach { restored ->
            assertTrue("正文 id 必须重发：${restored.id}", restored.id.startsWith("new-"))
            assertEquals(plan.project.id, restored.projectId)
            assertTrue("不能落成空串：${restored.id}", restored.outlineVersionId.isNotBlank())
            assertTrue(
                "悬空的大纲版本引用：${restored.outlineVersionId}",
                plan.outlineVersions.any { it.id == restored.outlineVersionId }
            )
        }
        // 大纲里的章节 id 保持不变：正文靠它索引，重发就等于删章
        assertEquals(
            listOf("chapter-1", "chapter-2", "chapter-3"),
            plan.outlineVersions.flatMap { it.chapters }.map { it.id }
        )
        assertTrue(plan.outlineVersions.all { it.projectId == plan.project.id })
    }

    @Test
    fun planRestore_fallsBackToTheHighestOutlineVersionWhenTheActiveOneIsGone() {
        val backup = sampleBackup().copy(
            project = sampleBackup().project.copy(activeOutlineVersionId = "outline-missing")
        )

        val plan = planRestore(backup, newId = countingIds())

        assertEquals(plan.outlineVersions.maxByOrNull { it.version }!!.id, plan.project.activeOutlineVersionId)
    }

    // ---------- 角色档案里的 projectId 也要跟着换 ----------

    @Test
    fun planRestore_rewritesNestedCharacterProjectIdsSoPromptsDoNotLeakTheOldBook() {
        val backup = sampleBackup(
            project = sampleBackup().project.copy(
                continuityState = ContinuityState(
                    worldRules = listOf("灵力守恒"),
                    characters = listOf(
                        CharacterProfile(id = "c-1", projectId = "old-project", name = "林昭", aliases = listOf("昭儿")),
                        // 早期数据里角色可能被写成了别的书的 id，同样要改
                        CharacterProfile(id = "c-2", projectId = "other-project", name = "沈砚")
                    )
                )
            )
        )

        val plan = planRestore(backup, newId = countingIds())

        assertEquals(
            listOf("new-1", "new-1"),
            plan.project.continuityState.characters.map { it.projectId }
        )
        // 角色自己的 id 和其它记忆不能被顺手改掉
        assertEquals(listOf("c-1", "c-2"), plan.project.continuityState.characters.map { it.id })
        assertEquals(listOf("林昭", "沈砚"), plan.project.continuityState.characters.map { it.name })
        assertEquals(listOf("灵力守恒"), plan.project.continuityState.worldRules)
    }

    // ---------- 同一份备份导两次，书架上要能分清 ----------

    @Test
    fun distinctImportTitle_leavesFreeTitlesAloneAndNumbersTheRest() {
        assertEquals("我的小说", distinctImportTitle("我的小说", emptyList()))
        assertEquals("我的小说", distinctImportTitle("我的小说", listOf("别人的书")))

        assertEquals(
            "我的小说（导入副本 2）",
            distinctImportTitle("我的小说", listOf("别人的书", "我的小说"))
        )
        // 第三本要跳过已经被占用的序号，结果确定
        assertEquals(
            "我的小说（导入副本 3）",
            distinctImportTitle("我的小说", listOf("我的小说", "我的小说（导入副本 2）"))
        )
        assertEquals(
            "我的小说（导入副本 4）",
            distinctImportTitle(
                "我的小说",
                listOf("我的小说", "我的小说（导入副本 2）", "我的小说（导入副本 3）")
            )
        )
    }

    @Test
    fun planRestore_secondImportOfTheSameBackupGetsADistinguishableTitle() {
        val backup = sampleBackup()

        val first = planRestore(backup, newId = countingIds())
        val second = planRestore(backup, takenTitles = listOf(first.project.title), newId = countingIds(100))
        val third = planRestore(
            backup,
            takenTitles = listOf(first.project.title, second.project.title),
            newId = countingIds(200)
        )

        assertEquals("我的小说", first.project.title)
        assertEquals("我的小说（导入副本 2）", second.project.title)
        assertEquals("我的小说（导入副本 3）", third.project.title)
        assertNotEquals(first.project.id, second.project.id)
    }

    // ---------- 落库：事务里写的必须就是算好的那份清单 ----------

    @Test
    fun writeRestoredBook_persistsThePlannedProjectOutlinesAndRevisions() = runBlocking {
        val plan = planRestore(sampleBackup(), takenTitles = listOf("我的小说"), newId = countingIds())
        val projects = FakeProjectDao()
        val outlines = FakeOutlineVersionDao()
        val chapters = FakeChapterRevisionDao()

        writeRestoredBook(plan, projects, outlines, chapters)

        val storedProject = requireNotNull(projects.findById(plan.project.id)).toDomain()
        assertEquals(plan.project.title, storedProject.title)
        assertEquals(plan.project.activeOutlineVersionId, storedProject.activeOutlineVersionId)
        // 落库后读回来的记忆里也不该再有别人的 projectId
        assertTrue(storedProject.continuityState.characters.all { it.projectId == plan.project.id })
        assertEquals(
            plan.outlineVersions.map { it.id },
            outlines.findAllForProject(plan.project.id).map { it.id }
        )
        val storedRevisions = chapters.findAllForProject(plan.project.id)
        assertEquals(plan.chapterRevisions.size, storedRevisions.size)
        assertTrue(storedRevisions.all { it.outlineVersionId.isNotBlank() })
        val versionIds = plan.outlineVersions.map { it.id }.toSet()
        assertTrue(storedRevisions.all { it.outlineVersionId in versionIds })
    }

    // ---------- 落盘格式：流式写出来的 JSON 必须和旧的整串写法一模一样 ----------

    @Test
    fun encodeBackup_writesTheSameJsonTheOldStringEncodingProduced() {
        val backup = sampleBackup().copy(
            chapterRevisions = listOf(
                revision(
                    id = "r-1",
                    itemId = "chapter-1",
                    versionId = "outline-1",
                    content = "他说了句\"走吧\"，然后\\走了。\n第二行\t制表符\u0001控制符——破折号"
                )
            )
        )
        val legacyJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }.encodeToString(NovelBackup.serializer(), backup)

        val streamed = ByteArrayOutputStream()
        encodeBackup(backup, streamed)

        assertEquals(legacyJson, String(streamed.toByteArray(), Charsets.UTF_8))
    }

    @Test
    fun encodeBackup_writesIncrementallyInsteadOfOneGiantByteArray() {
        val bigContent = "长正文".repeat(1024)
        val backup = sampleBackup().copy(
            chapterRevisions = (1..100).map { index ->
                revision(
                    id = "r-$index",
                    itemId = if (index % 2 == 0) "chapter-1" else "chapter-3",
                    versionId = if (index % 2 == 0) "outline-1" else "outline-2",
                    content = bigContent
                )
            }
        )
        val recorder = RecordingOutputStream()

        encodeBackup(backup, recorder)

        assertTrue("全书有 ${recorder.total} 字节", recorder.total > 200_000)
        assertTrue("应当分多次写出，实际 ${recorder.chunks.size} 次", recorder.chunks.size > 1)
        assertTrue(
            "单次写出不该超过 64KB，实际 ${recorder.chunks.maxOrNull()}",
            recorder.chunks.max() <= 64 * 1024
        )
    }

    @Test
    fun decodeBackup_readsBackWhatEncodeBackupWrote() {
        val backup = sampleBackup()
        val bytes = ByteArrayOutputStream().also { encodeBackup(backup, it) }.toByteArray()

        assertEquals(backup, decodeBackup(bytes.inputStream()))
    }

    /** 旧版本导出的备份（整串 encodeToString 的产物）必须照样能导入 */
    @Test
    fun decodeBackup_stillReadsBackupsWrittenByTheOldEncoder() {
        val legacyJson = """
            {"format":1,"exportedAt":1700000000000,"project":{"id":"old-project","title":"我的小说",
            "questionnaireSchemaVersion":1,"flowState":"WRITING_CHAPTER","questData":{"schemaVersion":1,"answers":{}},
            "creativeConfig":null,"continuityState":{"worldRules":[],"timelineEvents":[],
            "unresolvedThreads":[],"factsWithSources":[],"characters":[],"pendingFacts":[]},
            "activeOutlineVersionId":"outline-1","status":"WRITING","createdAt":1,"updatedAt":2},
            "outlineVersions":[{"id":"outline-1","projectId":"old-project","version":1,
            "chapters":[{"id":"chapter-1","orderIndex":0,"title":"开端","summary":"起","characterChanges":null}],
            "diffSummary":null,"createdAt":1}],
            "chapterRevisions":[{"id":"r-1","projectId":"old-project","outlineItemId":"chapter-1",
            "outlineVersionId":"outline-1","revision":1,"title":"开端","content":"正文第一段","summary":null,
            "status":"FINALIZED","promptSnapshotId":null,"createdAt":3}]}
        """.trimIndent().replace("\n", "")

        val decoded = decodeBackup(legacyJson.toByteArray(Charsets.UTF_8).inputStream())

        assertEquals(1, decoded.format)
        assertEquals("我的小说", decoded.project.title)
        assertEquals("old-project", decoded.project.id)
        assertEquals("chapter-1", decoded.outlineVersions.single().chapters.single().id)
        assertEquals("正文第一段", decoded.chapterRevisions.single().content)
        assertEquals(ChapterStatus.FINALIZED, decoded.chapterRevisions.single().status)
    }

    @Test
    fun decodeBackup_readsUnicodeEscapesTheSameWayAsBefore() {
        val escaped = "{\"format\":1,\"exportedAt\":0,\"project\":{\"id\":\"p\",\"title\":\"\\u4e2d\\u6587\"," +
            "\"flowState\":\"INIT\",\"questData\":{\"schemaVersion\":1,\"answers\":{}}," +
            "\"continuityState\":{\"worldRules\":[],\"timelineEvents\":[],\"unresolvedThreads\":[]," +
            "\"factsWithSources\":[],\"characters\":[],\"pendingFacts\":[]},\"activeOutlineVersionId\":null," +
            "\"status\":\"DRAFT\",\"createdAt\":0,\"updatedAt\":0},\"outlineVersions\":[]," +
            "\"chapterRevisions\":[]}"

        assertEquals("中文", decodeBackup(escaped.toByteArray().inputStream()).project.title)
    }

    private fun expectFailure(block: () -> Unit): IllegalArgumentException = try {
        block()
        fail("应当拒绝这份备份")
        error("unreachable")
    } catch (error: IllegalArgumentException) {
        error
    }

    /** 固定顺序的假 id：断言里可以直接写 new-1 / new-2 / new-3 */
    private fun countingIds(startAt: Int = 0): () -> String {
        var next = startAt
        return { "new-${++next}" }
    }

    private fun sampleBackup(project: Project = sampleProject()): NovelBackup {
        val outlineOne = OutlineVersion(
            id = "outline-1",
            projectId = "old-project",
            version = 1,
            chapters = listOf(
                OutlineItem(id = "chapter-1", orderIndex = 0, title = "开端", summary = "起"),
                OutlineItem(id = "chapter-2", orderIndex = 1, title = "转折", summary = "转")
            ),
            createdAt = 1L
        )
        val outlineTwo = outlineOne.copy(
            id = "outline-2",
            version = 2,
            // 用户重编了大纲：新版本里是另一个章节 id，旧版本的章节不被搬动
            chapters = listOf(OutlineItem(id = "chapter-3", orderIndex = 0, title = "重写", summary = "改")),
            createdAt = 2L
        )
        return NovelBackup(
            exportedAt = 100L,
            project = project,
            outlineVersions = listOf(outlineOne, outlineTwo),
            chapterRevisions = listOf(
                revision(id = "r-1", itemId = "chapter-1", versionId = "outline-1"),
                revision(id = "r-2", itemId = "chapter-1", versionId = "outline-1", revision = 2),
                revision(id = "r-3", itemId = "chapter-3", versionId = "outline-2", revision = 3)
            )
        )
    }

    private fun sampleProject() = Project(
        id = "old-project",
        title = "我的小说",
        flowState = FlowState.WRITING_CHAPTER,
        questData = QuestData(answers = mapOf("genre" to "玄幻")),
        continuityState = ContinuityState(
            characters = listOf(
                CharacterProfile(id = "c-1", projectId = "old-project", name = "林昭")
            )
        ),
        activeOutlineVersionId = "outline-1",
        status = ProjectStatus.WRITING,
        createdAt = 1L,
        updatedAt = 2L
    )

    private fun revision(
        id: String,
        itemId: String,
        versionId: String,
        revision: Int = 1,
        content: String = "正文"
    ) = ChapterRevision(
        id = id,
        projectId = "old-project",
        outlineItemId = itemId,
        outlineVersionId = versionId,
        revision = revision,
        title = "开端",
        content = content,
        status = ChapterStatus.FINALIZED,
        createdAt = 3L
    )

    /** 记录每次 write 的长度：用来证明 JSON 是分块写出的，而不是先拼一个大 byte[] */
    private class RecordingOutputStream : OutputStream() {
        val chunks = mutableListOf<Int>()
        val total: Int
            get() = chunks.sum()

        override fun write(b: Int) {
            chunks += 1
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            chunks += len
        }
    }

    private class FakeProjectDao : ProjectDao {
        private val rows = linkedMapOf<String, ProjectEntity>()

        override fun observeAll(): Flow<List<ProjectEntity>> = flowOf(rows.values.toList())

        override suspend fun findById(id: String): ProjectEntity? = rows[id]

        override suspend fun upsert(project: ProjectEntity) {
            rows[project.id] = project
        }

        override suspend fun updateContinuityJson(id: String, json: String, updatedAt: Long) {
            rows[id]?.let { rows[id] = it.copy(continuityStateJson = json, updatedAt = updatedAt) }
        }

        override suspend fun deleteById(id: String) {
            rows.remove(id)
        }
    }

    private class FakeOutlineVersionDao : OutlineVersionDao {
        private val rows = linkedMapOf<String, OutlineVersionEntity>()

        override fun observeForProject(projectId: String): Flow<List<OutlineVersionEntity>> =
            flowOf(rows.values.filter { it.projectId == projectId })

        override fun observeLatestVersion(projectId: String): Flow<OutlineVersionEntity?> =
            flowOf(rows.values.filter { it.projectId == projectId }.maxByOrNull { it.version })

        override suspend fun findAllForProject(projectId: String): List<OutlineVersionEntity> =
            rows.values.filter { it.projectId == projectId }

        override suspend fun findLatest(projectId: String): OutlineVersionEntity? =
            rows.values.filter { it.projectId == projectId }.maxByOrNull { it.version }

        override suspend fun upsert(version: OutlineVersionEntity) {
            rows[version.id] = version
        }

        override suspend fun upsertAll(versions: List<OutlineVersionEntity>) {
            versions.forEach { rows[it.id] = it }
        }

        override suspend fun deleteForProject(projectId: String) {
            rows.entries.removeAll { it.value.projectId == projectId }
        }
    }

    private class FakeChapterRevisionDao : ChapterRevisionDao {
        private val rows = linkedMapOf<String, ChapterRevisionEntity>()

        override fun observeForProject(projectId: String): Flow<List<ChapterRevisionEntity>> =
            flowOf(rows.values.filter { it.projectId == projectId })

        override suspend fun findAllForProject(projectId: String): List<ChapterRevisionEntity> =
            rows.values.filter { it.projectId == projectId }

        override fun observeWrittenItemIds(projectId: String): Flow<List<String>> =
            flowOf(rows.values.filter { it.projectId == projectId }.map { it.outlineItemId }.distinct())

        override suspend fun contentLengthBySnapshot(promptSnapshotId: String): Int? =
            rows.values.firstOrNull { it.promptSnapshotId == promptSnapshotId }?.content?.length

        override suspend fun findById(id: String): ChapterRevisionEntity? = rows[id]

        override suspend fun findLatest(projectId: String, outlineItemId: String): ChapterRevisionEntity? =
            rows.values.filter { it.projectId == projectId && it.outlineItemId == outlineItemId }
                .maxByOrNull { it.revision }

        override suspend fun upsert(revision: ChapterRevisionEntity) {
            rows[revision.id] = revision
        }

        override suspend fun upsertAll(revisions: List<ChapterRevisionEntity>) {
            revisions.forEach { rows[it.id] = it }
        }

        override suspend fun deleteForProject(projectId: String) {
            rows.entries.removeAll { it.value.projectId == projectId }
        }

        override suspend fun deleteForItems(projectId: String, outlineItemIds: Collection<String>) {
            rows.entries.removeAll {
                it.value.projectId == projectId && it.value.outlineItemId in outlineItemIds
            }
        }
    }
}
