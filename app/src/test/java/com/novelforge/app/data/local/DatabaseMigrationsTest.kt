package com.novelforge.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迁移链的「JVM 侧」体检：设备/模拟器不一定有，但下面三件事每次构建都必须成立——
 * 迁移不为空、版本号从 1 到当前版本没有断档、每条 SQL 至少是条像样的语句。
 * 真正跑迁移并逐列校验结构的是 androidTest 里的 DatabaseMigrationTest（需要设备）。
 */
class DatabaseMigrationsTest {
    @Test
    fun migrationsAreNotEmpty() {
        assertTrue("DatabaseMigrations.all 是空的：版本号升了却没迁移，老用户开库直接崩", DatabaseMigrations.all.isNotEmpty())
    }

    @Test
    fun everyVersionStepFrom1ToCurrentIsCovered() {
        val steps = DatabaseMigrations.all.map { it.startVersion to it.endVersion }.sortedBy { it.first }
        assertEquals("迁移不能从 1 以外的地方起步", 1, steps.first().first)
        assertEquals(
            "迁移链必须一路走到当前版本 $CURRENT_VERSION，实际 $steps",
            CURRENT_VERSION,
            steps.last().second
        )
        steps.forEachIndexed { index, (from, to) ->
            assertTrue("第 ${index + 1} 段迁移 $from->$to 起点不对（endVersion 必须等于下一段的 startVersion）", to > from)
            if (index > 0) {
                assertEquals("版本 $from 这一步没人管：缺了它老用户升不上去", steps[index - 1].second, from)
            }
        }
    }

    @Test
    fun everyStatementIsWellFormed() {
        val allowed = setOf("CREATE", "ALTER", "INSERT", "DROP", "UPDATE", "DELETE", "PRAGMA", "REPLACE")
        DatabaseMigrations.all.forEach { migration ->
            val sql = recordStatements(migration)
            assertTrue("${migration.startVersion}->${migration.endVersion} 一条 SQL 都没执行", sql.isNotEmpty())
            sql.forEach { statement ->
                val where = "${migration.startVersion}->${migration.endVersion} 的「${statement.take(60)}…」"
                assertTrue("$where 是空语句", statement.isNotBlank())
                // 末尾分号可有可无，但不能半途断在分号上（那说明一次 execSQL 塞了两条 SQL）
                assertEquals(
                    "$where 中途出现了分号：一次 execSQL 只能跑一条 SQL",
                    -1,
                    statement.trim().trimEnd(';').indexOf(';')
                )
                val keyword = statement.trim().substringBefore(' ').uppercase()
                assertTrue("$where 不是可识别的 SQL 关键字（$keyword）", keyword in allowed)
                assertEquals("$where 括号不配对", statement.count { it == '(' }, statement.count { it == ')' })
                assertBalancedQuotes(statement, where)
            }
        }
    }

    /** 1->2 是老用户已经在跑的那条迁移，谁都不能在改版本号时把它弄丢 */
    @Test
    fun v1To2MigrationIsStillIntact() {
        val first = DatabaseMigrations.all.single { it.startVersion == 1 && it.endVersion == 2 }
        assertEquals(
            listOf(
                "ALTER TABLE llm_calls ADD COLUMN cachedInputTokens INTEGER",
                "ALTER TABLE llm_calls ADD COLUMN reasoningTokens INTEGER"
            ),
            recordStatements(first).normalized()
        )
    }

    /** v3 的意义就是补索引 + 补外键，DDL 被谁改掉一条这里就红 */
    @Test
    fun v3MigrationAddsTheThreeMissingIndicesAndCascadingForeignKeys() {
        val v3 = DatabaseMigrations.all.single { it.startVersion == 2 && it.endVersion == 3 }
        val sql = recordStatements(v3).normalized()
        val joined = sql.joinToString("\n")

        // 缺陷 2/3 点名的三个索引
        listOf(
            "index_chapter_revisions_promptSnapshotId",
            "index_llm_calls_createdAt",
            "index_quality_runs_chapterRevisionId"
        ).forEach { name ->
            assertTrue("2->3 少了索引 $name 的建表语句", joined.contains("CREATE INDEX IF NOT EXISTS `$name`"))
        }

        // 缺陷 4 点名的四张表要真带 ON DELETE CASCADE（DDL 文本与 Room 导出的 schema 一字不差）
        val expectedForeignKey = mapOf(
            "outline_versions" to "FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`)",
            "chapter_revisions" to "FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`)",
            "generation_jobs" to "FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`)",
            "quality_runs" to "FOREIGN KEY(`chapterRevisionId`) REFERENCES `chapter_revisions`(`id`)"
        )
        expectedForeignKey.forEach { (table, foreignKey) ->
            val create = sql.single { it.startsWith("CREATE TABLE IF NOT EXISTS `temp_$table`") }
            assertTrue("$table 重建后没挂外键（应有 $foreignKey）", create.contains(foreignKey))
            assertTrue("$table 重建后不是级联删除", create.contains("ON DELETE CASCADE"))
        }
        assertFalse(
            "llm_calls 不能有 projectId 外键：对话记账写的是空 projectId，挂上就写不进去",
            joined.contains("CREATE TABLE IF NOT EXISTS `temp_llm_calls`")
        )

        // 整表重建的四步必须成对：DROP 会连带删掉旧索引，索引只能在改名之后重建
        expectedForeignKey.keys.forEach { table ->
            assertTrue("$table 少了改名步骤", joined.contains("ALTER TABLE `temp_$table` RENAME TO `$table`"))
            val dropAt = sql.indexOfFirst { it == "DROP TABLE `$table`" }
            val renameAt = sql.indexOfFirst { it == "ALTER TABLE `temp_$table` RENAME TO `$table`" }
            assertTrue("$table 没有先删旧表", dropAt >= 0)
            assertTrue("$table 的 DROP 与改名顺序反了", dropAt < renameAt)
            assertTrue(
                "$table 改名之后一条索引都没重建（DROP TABLE 会把索引一起删掉）",
                sql.drop(renameAt + 1).any { it.contains("ON `$table` ") }
            )
        }
        assertTrue("数据搬运语句被删了：重建完的表会是空的", joined.contains("INSERT INTO `temp_chapter_revisions`"))

        // 历史孤儿行必须顺手扫掉，否则挂上外键后它们永久读不到还挡着账本回填
        val cleanup = sql.indexOfFirst { it.startsWith("DELETE FROM quality_runs WHERE chapterRevisionId NOT IN") }
        assertTrue("迁移没有清理指向已删正文的孤儿质检行", cleanup >= 0)
        assertTrue(
            "清理孤儿必须排在重建 quality_runs（挂外键）之前",
            cleanup < sql.indexOfFirst { it.startsWith("CREATE TABLE IF NOT EXISTS `temp_quality_runs`") }
        )
    }

    /** exportSchema 曾经是关的，于是谁也没法 diff 结构；这里守住「1..当前版本 每个版本都有 schema 文件」 */
    @Test
    fun exportedSchemasCoverEveryVersion() {
        val dir = schemaDir()
        assertNotNull("找不到 app/schemas 目录，Room 的 schema 导出没配好", dir)
        val exported = dir!!.listFiles { file -> file.extension == "json" }
            .orEmpty()
            .mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }
            .toSet()
        assertEquals(
            "导出的 schema 版本不连续（缺版本就无法用 MigrationTestHelper 造旧库）",
            (1..CURRENT_VERSION).toSet(),
            exported
        )
    }

    private fun recordStatements(migration: Migration): List<String> {
        val sql = mutableListOf<String>()
        val recorder = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf<Class<*>>(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            when {
                method.name == "execSQL" -> {
                    args?.firstOrNull()?.let { sql += it as String }
                    null
                }
                method.returnType == Boolean::class.javaPrimitiveType -> false
                method.returnType == Int::class.javaPrimitiveType -> 0
                method.returnType == Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        } as SupportSQLiteDatabase
        migration.migrate(recorder)
        return sql
    }

    /** SQL 文本比对前统一去掉空白与末尾分号，免得两种写法各写一份断言 */
    private fun List<String>.normalized(): List<String> = map { it.trim().trimEnd(';') }

    private fun assertBalancedQuotes(statement: String, where: String) {
        var inSingle = false
        var inBacktick = false
        statement.forEach { ch ->
            when (ch) {
                '\'' -> if (!inBacktick) inSingle = !inSingle
                '`' -> if (!inSingle) inBacktick = !inBacktick
            }
        }
        assertFalse("$where 单引号没闭合", inSingle)
        assertFalse("$where 反引号没闭合", inBacktick)
    }

    private fun schemaDir(): File? = listOf(
        File("schemas/com.novelforge.app.data.local.AppDatabase"),
        File("app/schemas/com.novelforge.app.data.local.AppDatabase"),
        File("../app/schemas/com.novelforge.app.data.local.AppDatabase")
    ).firstOrNull { it.isDirectory }

    private companion object {
        /**
         * 必须与 AppDatabase.version 一致。@Database 是 CLASS 保留注解，JVM 侧反射读不到，
         * 只能人工同步；改版本号时这个测试会连同导出的 schema 一起提醒你。
         */
        const val CURRENT_VERSION = 3
    }
}
