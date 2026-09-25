package com.novelforge.app.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航结构的回归测试。
 *
 * 改导航层级是那种「编译通过、点起来才发现坏了」的改动：
 * `popUpTo` 路由字符串拼错不会抛异常，只是没弹栈；
 * 某个目的地漏了 `saveState`，症状是用户在另一个页面勾掉的记忆又被放回去。
 * 所以把结构本身钉成断言。
 */
class NavigationStructureTest {

    @Test
    fun bottomBarHasThreeDestinationsWithinMaterialsLimit() {
        // Material 明确写死 3–5 个：3 个是最保守的下界，5 个是硬上限。
        // 「更多」「设置」「对话框」不算一级目的地。
        val size = TopLevelDestination.entries.size
        assertTrue("一级目的地不能少于 3 个：$size", size in 3..5)
        assertEquals(
            listOf("书架", "灵感", "设置"),
            TopLevelDestination.entries.map { it.label }
        )
    }

    @Test
    fun thereIsExactlyOneBookListSurface() {
        // 以前有「全部项目」和「书架」两个门指向同一份 List<Project>，
        // 而两者互不相通：书架里一个 navigate() 都没有，于是进了一本书就回不来，
        // 也永远进不去写作界面。九个同类产品里没有一个保留第二个只读书籍列表。
        // 现在书籍只从 BOOKS 进。
        val destinations = TopLevelDestination.entries.map { it.route }
        assertEquals(1, destinations.count { it == Routes.BOOKS })
        // 旧的第二条门已经连同 ProjectsScreen 一起删掉了
        assertFalse(
            "一级目的地里不该再有独立的「全部项目」",
            destinations.contains("projects")
        )
        assertFalse("不该再有独立的「书架」路由，全部收进 BOOKS", destinations.contains("library"))
    }

    @Test
    fun everyRouteNameIsCentralized() {
        // popUpTo 手拼错了不会报错，只会静默不弹栈。
        // Routes.CHAPTER 是带占位符的 pattern，chapter()/chapterAuto() 是填好的实例，
        // 两者不能混用（拿实例去 popUpTo pattern 匹配不上）。
        assertTrue(Routes.CHAPTER.contains("{projectId}"))
        assertTrue(Routes.chapter("p1", "chapter-7").startsWith("chapter/p1/"))
        assertTrue(Routes.chapterAuto("p1", "chapter-7").endsWith("?autostart=true"))
        // outline 的 pattern 带 ?autostart，但填出来不带 —— pattern 那条是注册用的
        assertTrue(Routes.OUTLINE.contains("{projectId}"))
        assertEquals("outline/p1", Routes.outline("p1"))
        assertEquals("outline/p1?autostart=true", Routes.outlineAuto("p1"))
    }

    @Test
    fun chapterIdsAreUrlEncoded() {
        // outlineItemId 会带中文、空格和特殊字符。不编码的话路由匹配不上，
        // 症状是点「写这一章」之后白屏或者直接崩在参数解析上。
        val route = Routes.chapter("p1", "chapter-7 试读")
        assertFalse("不能出现裸空格", route.contains(' '))
        assertFalse("不能出现裸中文", route.contains('试'))
        assertTrue(route.contains("chapter-7%20%E8%AF%95%E8%AF%BB"))
    }

    @Test
    fun pathSegmentsKeepUnreservedCharacters() {
        // 编码太狠会把已经能用的 id 也改掉，popUpTo 就再也匹配不上原来的条目
        assertEquals("chapter-7_abc.A-b~c", Routes.encodeSegment("chapter-7_abc.A-b~c"))
        // 斜杠必须编码，否则会多切出一层路径
        assertEquals("a%2Fb", Routes.encodeSegment("a/b"))
        // + 在路径段里必须编码：它是 RFC 3986 的 sub-delim，只在 query 里当空格用
        assertEquals("a%2Bb", Routes.encodeSegment("a+b"))
        assertEquals("%26", Routes.encodeSegment("&"))
        assertEquals("%3F", Routes.encodeSegment("?"))
    }

    @Test
    fun perBookChatRouteCarriesTheProjectId() {
        // 分桶代码一直是对的，但整个 app 只有一个不带 projectId 的入口，
        // 于是它永远落回全局桶：A 书聊的人设会被整段重发进 B 书的提问。
        assertEquals("chat", Routes.chat(null))
        assertEquals("chat", Routes.chat(""))
        assertEquals("chat?projectId=p1", Routes.chat("p1"))
    }

    @Test
    fun singlePurposeScreensHideTheBottomBarButTheRestShowIt() {
        // 写正文和创作设置是单任务页，满屏只有字和进度，底栏是干扰。
        // 但返回键必须留着 —— 隐藏底栏又把返回藏起来是被明确禁止的失败模式。
        assertFalse(showsBottomBar("chapter/p1/chapter-7"))
        assertFalse(showsBottomBar("chapter/p1/chapter-7?autostart=true"))
        assertFalse(showsBottomBar(Routes.CREATE))
        assertFalse(showsBottomBar("creative-setup/p1"))

        // 一级目的地和书的 hub 保留底栏，父级就是书架，一步能跳回去
        assertTrue(showsBottomBar(Routes.BOOKS))
        assertTrue(showsBottomBar(Routes.CHAT))
        assertTrue(showsBottomBar(Routes.SETTINGS))
        // 设置的二级页：底栏停在「设置」
        assertTrue(showsBottomBar(Routes.LEDGER))
        assertTrue(showsBottomBar(Routes.EXPORTS))
        assertTrue(showsBottomBar("outline/p1"))
        assertTrue(showsBottomBar("outline/p1?autostart=true"))
        assertTrue(showsBottomBar("memory/p1"))
    }

    @Test
    fun everyBottomBarDestinationResolvesBackToItself() {
        // 一级目的地之间切换用 findStartDestination + saveState/restoreState。
        // 任何一个 route 和它注册的 pattern 对不上，切换就会静默失败。
        assertEquals(Routes.BOOKS, TopLevelDestination.Books.route)
        assertEquals(Routes.CHAT, TopLevelDestination.Chat.route)
        assertEquals(Routes.SETTINGS, TopLevelDestination.Settings.route)
        // chat 注册的是带可选参数的 pattern，选中态要按 query 之前的一段比
        assertEquals(TopLevelDestination.Chat, TopLevelDestination.fromRoute("chat?projectId=p1"))
        assertEquals(TopLevelDestination.Books, TopLevelDestination.fromRoute("books"))
        assertEquals(null, TopLevelDestination.fromRoute("settings/x"))
    }
}
