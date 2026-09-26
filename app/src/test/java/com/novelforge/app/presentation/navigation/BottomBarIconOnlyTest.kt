package com.novelforge.app.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「底部一排只要三个图标，不要文字」（回归护栏）。
 *
 * 用户原话：「最下面一排只需要有三个图标就行了，不要文字」
 *
 * ## 改的是什么
 *
 * `NavigationBarItem` 之前同时给了两样东西：
 * ```
 * icon  = { Text(destination.glyph) }   // ▤ / ✎ / ⚙
 * label = { Text(destination.label) }   // 书架 / 灵感 / 设置
 * ```
 * 现在 `label = null` —— 图标留着，文字去掉。**目的地还是三个，一个没少。**
 *
 * ## 文字去哪了
 *
 * 变成 `contentDescription`（`Routes.kt` 的 `NovelForgeBottomBar` 里）。
 * 图标是 `▤` / `✎` / `⚙` 这种排版符号，对 TalkBack 来说是没有意义的一串笔画；
 * 去掉可见文字又不给描述，等于把这一排对读屏用户变成三块空白。
 *
 * 所以 `TopLevelDestination.label` **一个字都没删**，只是从「画出来」降级成
 * 「读出来」。这条测试就是钉住它还在、而且还接在图标上。
 *
 * ## 这类改动为什么需要护栏
 *
 * Material 3 的 `NavigationBarItem` 的 `label` **有默认值**（M3 1.2 起是
 * `label: @Composable (() -> Unit)? = null`），所以把文字加回去是「补一个参数」
 * 就能编译通过的普通改动，任何测试、任何 lint 都不会提醒。它还特别符合直觉 ——
 * Material 的设计规范本来就是「底栏要有文字标签」。这正是它会被悄悄加回来的原因。
 *
 * **已知不覆盖**：真机上那三个图标点下去的行为、以及视觉上留白是否可接受，
 * JVM 测不了。这里只钉住「文字没有被画出来」和「描述还在」。
 */
class BottomBarIconOnlyTest {

    /**
     * 断言前先剥掉注释 —— 否则这条测试会匹配到**它自己的解释性注释**。
     *
     * 底部栏那段注释里就写着「原来是 `Text("▤")` / `Text("✎")` / `Text("⚙")`」，
     * 于是「不许再出现这三个字符」这条规则被自己的注释判死。
     * 这和「断言去比对被 sabotage 改的那个常量」是同一类错误：断言匹配到的
     * 不是它声称在匹配的东西，于是它对什么都会红、或者对什么都不红。
     */
    private val routesSrc = stripComments(
        File("src/main/java/com/novelforge/app/presentation/navigation/Routes.kt")
            .readText(Charsets.UTF_8)
    )

    /**
     * 剥掉注释（手写扫描，不用正则）。
     *
     * 不用正则有两个原因：`RegexOption.DOT_MATCH_ALL` 在这个工程的
     * Kotlin 版本下解析不了；而且 `/* */` 跨行、`//` 到行尾这两条规则
     * 手写扫描更不容易写错 —— 断言工具本身出错的话，失败信息会指向
     * 完全无关的方向。
     */
    private fun stripComments(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        var inString = false
        while (i < text.length) {
            val c = text[i]
            val n = if (i + 1 < text.length) text[i + 1] else ' '
            when {
                inString -> {
                    sb.append(c)
                    if (c == '\\') {
                        if (i + 1 < text.length) sb.append(text[i + 1])
                        i += 2
                        continue
                    }
                    if (c == '"') inString = false
                    i++
                }
                // 字符串字面量：注释标记在引号里不算注释
                c == '"' -> {
                    inString = true
                    sb.append(c)
                    i++
                }
                c == '/' && n == '*' -> {
                    val end = text.indexOf("*/", i + 2)
                    i = if (end < 0) text.length else end + 2
                    sb.append(' ')
                }
                c == '/' && n == '/' -> {
                    val end = text.indexOf('\n', i)
                    i = if (end < 0) text.length else end
                    sb.append(' ')
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    private fun bottomBar(): String {
        val start = routesSrc.indexOf("fun NovelForgeBottomBar(")
        assertTrue("没找到 NovelForgeBottomBar", start >= 0)
        val end = routesSrc.indexOf("\n}", start)
        assertTrue("NovelForgeBottomBar 没有正常结束", end > start)
        return routesSrc.substring(start, end)
    }

    @Test
    fun theBottomBarDrawsNoTextLabels() {
        val bar = bottomBar()
        // `label = null` 是唯一的合法写法。`label = {` 说明文字又被加回来了。
        assertTrue(
            "底部栏的 label 必须显式为 null：\n$bar",
            bar.contains("label = null")
        )
        assertFalse(
            "底部栏又出现了 label = { ... }，文字被加回来了：\n$bar",
            Regex("label\\s*=\\s*\\{").containsMatchIn(bar)
        )
    }

    @Test
    fun theThreeDestinationsAreStillThere() {
        // 「只要三个图标」说的是**不要文字**，不是「少一个入口」。
        // 这条钉住数量：三个目的地、三个图标。
        assertEquals("底部栏应当正好三个目的地", 3, TopLevelDestination.entries.size)
        val bar = bottomBar()
        assertTrue(
            "底部栏应当遍历全部三个目的地：\n$bar",
            bar.contains("TopLevelDestination.entries.forEach")
        )
        // 图标必须是**矢量**，不是文字。
        //
        // 原来是 `Text("▤")` / `Text("✎")` / `Text("⚙")` —— 这三个字符都在
        // Unicode 的杂项符号区，**不是所有 OEM 字体都收**。缺字形时 Android
        // 画一个豆腐块 □，而且没有编译期也没有运行期告警，只在用户那台机器上出现。
        // 而且按 bodyLarge = 16sp 画进 M3 的 24dp 图标槽，字形撑不满也居不准。
        val glyphs = listOf("▤", "✎", "⚙")
        for (g in glyphs) {
            assertFalse(
                "底栏又用回文字符号 `$g` 当图标了（可能缺字形变豆腐块）",
                bar.contains("Text(destination.glyph)") || routesSrc.contains("\"$g\"")
            )
        }
        assertTrue(
            "底栏应当用 Icon(imageVector = destination.icon)",
            bar.contains("imageVector = destination.icon")
        )
    }

    @Test
    fun everyIconStillCarriesItsNameForScreenReaders() {
        // 文字从「画出来」降级成「读出来」，一个字都不能少。
        // 少了这一条，TalkBack 念出来的是三声「未知按钮」。
        val bar = bottomBar()
        assertTrue(
            "图标上没有挂 contentDescription，读屏用户会听到三个无意义的符号：\n$bar",
            bar.contains("contentDescription = destination.label")
        )
        // 而且 label 字段本身必须还在 —— 那是描述的来源。
        for (d in TopLevelDestination.entries) {
            assertTrue("目的地 ${d.name} 的可读名称被清空了", d.label.isNotBlank())
        }
    }

    @Test
    fun everyDestinationHasADistinctVectorIcon() {
        // 图标是底栏唯一可见的东西了。三个目的地给同一个图标，
        // 用户只能靠位置分辨 —— 而三个 tab 的顺序不是约定俗成的。
        val icons = TopLevelDestination.entries.map { it.icon }
        assertEquals(
            "三个目的地的图标必须各不相同，实际：$icons",
            icons.size,
            icons.toSet().size
        )
    }

    private fun sourceOf(relative: String): String {
        val f = File("src/main/java/com/novelforge/app/$relative")
        assertTrue("找不到源文件 ${f.path}（工作目录应为 app/）", f.isFile)
        return f.readText(Charsets.UTF_8)
    }
}
