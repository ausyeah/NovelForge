package com.novelforge.app.presentation.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 排行必须真的在排（回归护栏）。
 *
 * 用户原话：「排行并没有排行」。查下来 SQL 里**有** ORDER BY，但它写的是
 * `ORDER BY (inputTokens + outputTokens) DESC`，靠的是"这个表达式能命中
 * 结果列别名"这个前提 —— 而 `llm_calls` 表**本身就有同名的真实列**
 * `inputTokens` / `outputTokens`。列与别名同名时解析顺序不是想当然，
 * 一旦解析成"取某一行自己的 token"，排序就成了随机的，看上去就正好像
 * 没排过。
 *
 * 项目分布那条查询更危险：它有 `HAVING (inputTokens + outputTokens) > 0`，
 * 同样的歧义会让过滤也失准。
 *
 * 所以两条查询都改成把 SUM 原样重写一遍，不依赖别名解析。这里从源码上
 * 钉住 —— SQL 语义在 JVM 单测里跑不出来（要 Room + 仪器测试）。
 */
class LedgerRankingOrderTest {

    @Test
    fun modelRankingOrdersByTheAggregateNotByAColumnAlias() {
        val query = queryOf("observeModelSummariesSince")
        assertTrue(
            "模型排行没有 ORDER BY：\n$query",
            query.contains("ORDER BY")
        )
        assertTrue(
            "模型排行的 ORDER BY 还在靠 `inputTokens + outputTokens` 这种别名写法，" +
                "而 llm_calls 有同名真实列，解析顺序不确定：\n$query",
            !Regex("""ORDER BY\s*\(\s*inputTokens\s*\+\s*outputTokens\s*\)""").containsMatchIn(query)
        )
        assertTrue(
            "模型排行应按两个 SUM 相加排序：\n$query",
            Regex("""ORDER BY\s*\(\s*SUM\(COALESCE\(inputTokens,0\)\)\s*\+\s*SUM\(COALESCE\(outputTokens,0\)\)\s*\)""")
                .containsMatchIn(query)
        )
    }

    @Test
    fun projectRankingOrdersByTheAggregateToo() {
        val query = queryOf("observeProjectSummaries")
        assertTrue(
            "项目分布还在靠别名排序：\n$query",
            !Regex("""ORDER BY\s*\(\s*c?inputTokens\s*\+\s*c?outputTokens\s*\)""").containsMatchIn(query)
        )
        assertTrue(
            "项目分布应按两个 SUM 相加排序：\n$query",
            Regex("""ORDER BY\s*\(\s*SUM\(COALESCE\(c\.inputTokens,0\)\)\s*\+\s*SUM\(COALESCE\(c\.outputTokens,0\)\)\s*\)""")
                .containsMatchIn(query)
        )
    }

    @Test
    fun projectRankingHavingAlsoUsesTheAggregate() {
        val query = queryOf("observeProjectSummaries")
        assertTrue(
            "项目分布的 HAVING 也在靠别名求和，0 用量的记录可能没被滤掉：\n$query",
            !Regex("""HAVING\s*\(\s*inputTokens\s*\+\s*outputTokens\s*\)""").containsMatchIn(query)
        )
    }

    /**
     * 排行最多显示 3 个。
     *
     * 而且截断只能发生在**渲染时** —— LedgerTotals 的总量是从完整列表求和的，
     * 在 ViewModel 里截成 3 行会让总量只统计前三个模型。这条测试钉住
     * 常量本身，渲染处的 take() 由代码审阅保证。
     */
    @Test
    fun rankingIsCappedAtThree() {
        assertEquals(3, RANKING_TOP_N)
    }

    // ---------------------------------------------------------------- 源码定位

    private fun queryOf(functionName: String): String {
        val source = readDaosSource()
        val anchor = source.indexOf("fun $functionName(")
        assertTrue("Daos.kt 里找不到 $functionName", anchor >= 0)
        // 往前找最近的 @Query(，往后取到 fun 那一行
        val start = source.lastIndexOf("@Query(", anchor)
        assertTrue("$functionName 前面没有 @Query", start >= 0)
        return source.substring(start, anchor)
    }

    private fun readDaosSource(): String {
        val relative = "src/main/java/com/novelforge/app/data/local/Daos.kt"
        val roots = listOfNotNull(
            File("").absoluteFile.takeIf { it.isDirectory },
            codeSourceDirectory()
        )
        for (root in roots) {
            var dir: File? = root
            while (dir != null) {
                listOf(File(dir, relative), File(dir, "app/$relative"))
                    .firstOrNull { it.isFile }
                    ?.let { return it.readText() }
                dir = dir.parentFile
            }
        }
        throw AssertionError("找不到 Daos.kt（源码不在预期位置）")
    }

    private fun codeSourceDirectory(): File? = try {
        LedgerRankingOrderTest::class.java
            .protectionDomain?.codeSource?.location
            ?.toURI()
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }
    } catch (_: Exception) {
        null
    }
}
