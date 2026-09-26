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

    private val routesSrc = sourceOf("presentation/navigation/Routes.kt")

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
        for (d in TopLevelDestination.entries) {
            assertTrue("目的地 ${d.name} 没有图标字符", d.glyph.isNotBlank())
        }
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
    fun noDestinationGlyphIsEmptyOrWhitespace() {
        // 图标是唯一可见的东西了，空图标 = 底栏出现一个点不开的空格。
        for (d in TopLevelDestination.entries) {
            assertFalse("目的地 ${d.name} 的图标是空白", d.glyph.isBlank())
        }
    }

    private fun sourceOf(relative: String): String {
        val f = File("src/main/java/com/novelforge/app/$relative")
        assertTrue("找不到源文件 ${f.path}（工作目录应为 app/）", f.isFile)
        return f.readText(Charsets.UTF_8)
    }
}
