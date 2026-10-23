package com.novelforge.app.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.novelforge.app.MainActivity
import com.novelforge.app.presentation.navigation.TopLevelDestination
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 启动冒烟：确认 app 起得来、起点是书架、三个一级目的地都在、关键入口点得进去。
 *
 * ## 上一版错在哪
 *
 * 上一版断言的三条文案在 app 里全都不存在，而且**这份测试从来没被执行过**——
 * 这台机器上没有模拟器也没有设备，`connectedAndroidTest` 跑不了，
 * 源码就静悄悄地烂掉了。
 *
 * * `"NovelForge"`：只存在于 `res/values/strings.xml` 的 `app_name`、
 *   剪贴板标签（`ChatScreen.kt:1001`）和通知标题（`GenerationWorker.kt:78`）。
 *   全 app 没有任何一处 `stringResource(R.string.app_name)`，而 Activity 用的是
 *   `Theme.Material.Light.NoActionBar`（`themes.xml:2`），没有 ActionBar 会去渲染
 *   应用名。所以这条断言**从来就不可能通过**，它指的不是屏幕上的文字。
 *   替代物是书架顶栏标题「书架」。
 * * `"新建项目"`：改名成「新建小说」，见 `LibraryScreen.kt:761`（空书架）
 *   和 `LibraryScreen.kt:864`（有书）。
 * * `"模型设置"`：整个 app 已经没有叫这个名字的界面或控件了。设置现在是
 *   底栏的「设置」tab（`Routes.kt:79`），模型连接表单收在它的「连接」小节
 *   （`SettingsScreen.kt:283`）加 API Key 输入框（`SettingsScreen.kt:411`）。
 *
 * 旧版断言的 HomeScreen 这个 composable 已经不存在：全仓库搜 `HomeScreen`
 * 只剩 `HomeViewModel.kt:16` 的一个 class 声明，而它现在只负责 `createProject`，
 * 不再有自己的界面。起点换成了 `Routes.BOOKS`（`NovelForgeNavGraph.kt:156`）。
 *
 * ## 前提
 *
 * 装完即跑的干净安装：一本小说都没有、灵感助手没有历史对话。
 * 底栏三个目的地和「新建小说」按钮在空书架和有书两种状态下都渲染，
 * 所以这几条断言与数据库状态无关；只有灵感助手那条空状态文案依赖干净安装。
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /**
     * 起点是书架，底栏三个一级目的地都在、都可点。
     *
     * 文案不写死，取自 `TopLevelDestination`（`Routes.kt:76-79`）。
     * 旧版把三个导航文案硬编码在断言里，导航改一次名就得回来改测试，
     * 而这份测试又不会被执行，于是没人会发现要改。
     *
     * 匹配条件加了 `hasClickAction()`：在书架页「书架」有**两个**节点 ——
     * 顶栏标题（`LibraryScreen.kt:752`）和底栏标签（`Routes.kt:77`），
     * 光按文字找会撞上 "Expected exactly '1' node"。顶栏标题是纯 `Text`，
     * 没有 clickable，底栏的 `NavigationBarItem` 有，所以这一条既能消歧，
     * 又顺带把「这是个真的能点的 tab」一起测了。
     */
    @Test
    fun startDestinationShowsAllThreeTopLevelTabs() {
        TopLevelDestination.entries.forEach { destination ->
            composeRule.onNode(hasText(destination.label) and hasClickAction())
                .assertIsDisplayed()
        }
    }

    /** 「新建项目」现在的真身：书架上的「新建小说」入口，点了进新建表单。 */
    @Test
    fun bookshelfCreateEntryPointOpensCreateForm() {
        // LibraryScreen.kt:761（空书架）/ :864（有书），两个分支里都恰好一个节点
        composeRule.onNodeWithText("新建小说").assertIsDisplayed()
        composeRule.onNodeWithText("新建小说").performClick()

        // navigate(Routes.CREATE)（`NovelForgeNavGraph.kt:183`）之后书架那一屏已经
        // 从 NavHost 卸掉，"create" 不在 BOTTOM_BAR_ROOTS 里（`Routes.kt:110`）
        // 底栏也一起收走了，所以这里的「新建小说」只剩顶栏标题那一个
        // （`CreateProjectScreen.kt:36`）。
        composeRule.waitForIdle()
        composeRule.onNodeWithText("新建小说").assertIsDisplayed()
        // 顶栏副标题：确认真的落在新建表单上，而不是只看到同名按钮还留在原地
        composeRule.onNodeWithText("先填写名称，题材可在下一步设置").assertIsDisplayed()
        // 主按钮（CreateProjectScreen.kt:50）。精确匹配：副标题里也含「下一步」
        // 三个字，但整段文案不同，substring 匹配不会命中
        composeRule.onNodeWithText("下一步").assertIsDisplayed()
    }

    /**
     * 「模型设置」现在的真身：底栏「设置」tab，模型连接表单在它的「连接」小节里。
     *
     * 书架页上「设置」只有底栏那一个节点（顶栏是「书架」），所以可以直接点。
     */
    @Test
    fun settingsTabHostsModelConnectionForm() {
        composeRule.onNodeWithText(TopLevelDestination.Settings.label).performClick()

        // 落地页确实是 SettingsScreen：副标题只有它有（SettingsScreen.kt:143）
        composeRule.onNodeWithText("模型连接、壁纸与预设配置").assertIsDisplayed()
        // 两个二级页入口，都在这一屏里（SettingsScreen.kt:147-185）
        composeRule.onNodeWithText("用量账本").assertIsDisplayed()
        composeRule.onNodeWithText("备份与导出").assertIsDisplayed()
        // 模型连接表单整段在可滚动 Column 里（SettingsScreen.kt:138），
        // API Key 在 SettingsScreen.kt:411，裸屏看不到，得先滚
        composeRule.onNodeWithText("API Key").performScrollTo().assertIsDisplayed()
    }

    /** 第三个一级目的地：灵感助手。 */
    @Test
    fun inspirationTabOpensChat() {
        composeRule.onNodeWithText(TopLevelDestination.Chat.label).performClick()

        // 顶栏副标题（ChatScreen.kt:1090）证明路由确实落到了 ChatScreen
        composeRule.onNodeWithText("设定、段落与情节走向").assertIsDisplayed()
        // 空状态（ChatScreen.kt:1175）。这一条依赖干净安装：
        // 有过对话历史之后这句就不再渲染
        composeRule.onNodeWithText("还没有灵感？说说你的想法").assertIsDisplayed()
    }
}
