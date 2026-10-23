package com.novelforge.app.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真正的迁移测试：用 MigrationTestHelper 按 app/schemas 里的历史 schema 造出旧版库，
 * 再让 Room 自己跑迁移，并拿导出的 schema JSON 逐列/逐索引/逐外键校验迁移结果。
 *
 * 之前的版本用的是 Room.inMemoryDatabaseBuilder：它直接按当前实体建一张空库，
 * 迁移代码一行都没执行过，把 DatabaseMigrations.all 整个清空这个测试照样绿。
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    /** 装机用户（v1 升上来）：老数据留住，v3 的索引/外键/两列都到位 */
    @Test
    fun migrateFrom1To3_keepsOldRowsAndAddsColumnsIndicesAndCascades() {
        seedV1(helper.createDatabase(TEST_DB, 1))

        // runMigrationsAndValidate 会跑 1->2、2->3，再拿 app/schemas/.../3.json 逐项对账，
        // 对不上直接抛异常——这就是「迁移后的库 == 当前实体定义」的强校验
        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, *DatabaseMigrations.all)
        helper.closeWhenFinished(db)

        // 老数据一行不能少
        assertEquals("正文", db.single("SELECT content FROM chapter_revisions WHERE id = 'rev-1'"))
        assertEquals("{}", db.single("SELECT reportJson FROM quality_runs WHERE id = 'qr-1'"))
        assertEquals("1", db.single("SELECT attempt FROM generation_jobs WHERE id = 'job-1'"))
        assertEquals("10", db.single("SELECT inputTokens FROM llm_calls WHERE id = 'call-1'"))
        // 对话记账（projectId 为空）也得留着
        assertEquals("2", db.single("SELECT COUNT(*) FROM llm_calls"))
        // v1->2 补的两列在老行上是 NULL，不是 0
        assertNull(db.single("SELECT cachedInputTokens FROM llm_calls WHERE id = 'call-1'"))
        assertNull(db.single("SELECT reasoningTokens FROM llm_calls WHERE id = 'call-1'"))

        // v3 补的三个索引
        assertTrue(db.indexNames("chapter_revisions").contains("index_chapter_revisions_promptSnapshotId"))
        assertTrue(db.indexNames("llm_calls").contains("index_llm_calls_createdAt"))
        assertTrue(db.indexNames("quality_runs").contains("index_quality_runs_chapterRevisionId"))
        // 重建过的表索引不能丢
        assertTrue(db.indexNames("chapter_revisions").contains("index_chapter_revisions_projectId_outlineVersionId"))
        assertTrue(db.indexNames("generation_jobs").contains("index_generation_jobs_clientRequestId"))
        assertTrue(db.indexNames("outline_versions").contains("index_outline_versions_projectId_version"))

        // 整表重建不能留下临时表
        assertTrue(db.rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'temp_%'").isEmpty())

        // 外键真挂上了：删正文 -> 质检记录跟着没；删项目 -> 子表清空
        // Room 只在自己的 onOpen 里打开外键（迁移期间必须关着，否则 DROP TABLE 会级联误删），
        // 所以这里要显式打开，不然测的是「没开外键」的假象
        db.execSQL("PRAGMA foreign_keys = ON")
        assertEquals("1", db.single("PRAGMA foreign_keys"))
        db.execSQL("DELETE FROM chapter_revisions WHERE id = 'rev-1'")
        assertEquals("0", db.single("SELECT COUNT(*) FROM quality_runs"))
        db.execSQL("DELETE FROM projects WHERE id = 'project-1'")
        assertEquals("0", db.single("SELECT COUNT(*) FROM outline_versions"))
        assertEquals("0", db.single("SELECT COUNT(*) FROM generation_jobs"))
        // 账本留痕不受项目删除影响（llm_calls 故意没挂外键）
        assertEquals("1", db.single("SELECT COUNT(*) FROM llm_calls"))
    }

    /** 已经升到 v2 的老用户走的那条路：2->3 不能要求他们重装 */
    @Test
    fun migrateFrom2To3_keepsRowsAndRebuildsTables() {
        seedV2(helper.createDatabase(TEST_DB, 2))

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, *DatabaseMigrations.all)
        helper.closeWhenFinished(db)

        assertEquals("正文", db.single("SELECT content FROM chapter_revisions WHERE id = 'rev-1'"))
        assertEquals("7", db.single("SELECT cachedInputTokens FROM llm_calls WHERE id = 'call-1'"))
        assertEquals("11", db.single("SELECT reasoningTokens FROM llm_calls WHERE id = 'call-1'"))
        assertTrue(db.cascadeRules("chapter_revisions").contains("CASCADE"))
        assertTrue(db.cascadeRules("quality_runs").contains("CASCADE"))
    }

    /** 1->2 是老用户已经在跑的那条迁移，谁都不能在改版本号时把它弄丢 */
    @Test
    fun migrateFrom1To2_onlyAddsTheTwoTokenColumns() {
        seedV1(helper.createDatabase(TEST_DB, 1))

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, *DatabaseMigrations.all)
        helper.closeWhenFinished(db)

        assertTrue(db.hasColumn("llm_calls", "cachedInputTokens"))
        assertTrue(db.hasColumn("llm_calls", "reasoningTokens"))
        assertTrue(db.indexNames("llm_calls").none { it == "index_llm_calls_createdAt" })
        assertTrue(db.cascadeRules("chapter_revisions").isEmpty())
    }

    private fun seedV1(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO projects VALUES ('project-1', '旧书', 1, 'INIT', '{}', NULL, '{}', NULL, 'DRAFT', 1, 1)"
        )
        db.execSQL("INSERT INTO outline_versions VALUES ('ov-1', 'project-1', 1, '[]', NULL, 1)")
        db.execSQL("INSERT INTO character_snapshots VALUES ('cs-1', 'project-1', 1, '{}', 1)")
        db.execSQL(
            "INSERT INTO chapter_revisions VALUES " +
                "('rev-1', 'project-1', 'item-1', 'ov-1', 1, '第一章', '正文', NULL, 'DONE', 'snap-1', 1)"
        )
        db.execSQL(
            "INSERT INTO generation_jobs VALUES " +
                "('job-1', 'project-1', 'item-1', 'CHAPTER', 'COMPLETED', 'req-1', 1, '', 'snap-1', " +
                "NULL, NULL, NULL, 1, 1)"
        )
        db.execSQL(
            "INSERT INTO llm_calls VALUES " +
                "('call-1', 'project-1', 'job-1', 'CHAPTER', 'openai', 'gpt-x', 10, 20, 30, 0, 500, 1, 1)"
        )
        db.execSQL(
            "INSERT INTO llm_calls VALUES " +
                "('call-2', '', 'chat-1', 'CHAT', 'openai', 'gpt-x', 1, 1, 2, 0, 300, 1, 1)"
        )
        db.execSQL("INSERT INTO quality_runs VALUES ('qr-1', 'project-1', 'rev-1', '{}', 1)")
        db.close()
    }

    private fun seedV2(db: SupportSQLiteDatabase) {
        seedV1(db)
        db.execSQL("UPDATE llm_calls SET cachedInputTokens = 7, reasoningTokens = 11 WHERE id = 'call-1'")
        db.close()
    }

    private fun SupportSQLiteDatabase.rows(sql: String): List<List<String?>> =
        query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
                }
            }
        }

    private fun SupportSQLiteDatabase.single(sql: String): String? = rows(sql).firstOrNull()?.firstOrNull()

    private fun SupportSQLiteDatabase.indexNames(table: String): List<String> =
        rows("PRAGMA index_list(`$table`)").mapNotNull { it.getOrNull(1) }
            .filterNot { it.startsWith("sqlite_autoindex") }

    private fun SupportSQLiteDatabase.cascadeRules(table: String): List<String> =
        rows("PRAGMA foreign_key_list(`$table`)").mapNotNull { it.getOrNull(6) }

    private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean =
        rows("PRAGMA table_info(`$table`)").any { it.getOrNull(1) == column }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
