package com.novelforge.app.presentation.navigation

import android.net.Uri
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.WindowInsets
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.novelforge.app.NovelForgeApplication
import com.novelforge.app.presentation.chapter.ChapterNeighbor
import com.novelforge.app.presentation.chapter.ChapterScreen
import com.novelforge.app.presentation.chat.ChatScreen
import com.novelforge.app.presentation.chat.ChatViewModel
import com.novelforge.app.presentation.chapter.ChapterViewModel
import com.novelforge.app.presentation.common.chapterLabel
import com.novelforge.app.presentation.exports.ExportsScreen
import com.novelforge.app.presentation.home.HomeViewModel
import com.novelforge.app.presentation.library.LibraryScreen
import com.novelforge.app.presentation.library.LibraryViewModel
import com.novelforge.app.presentation.ledger.LedgerScreen
import com.novelforge.app.presentation.ledger.LedgerViewModel
import com.novelforge.app.presentation.outline.OutlineScreen
import com.novelforge.app.presentation.outline.OutlineViewModel
import com.novelforge.app.presentation.project.CreateProjectScreen
import com.novelforge.app.presentation.project.CreativeSetupScreen
import com.novelforge.app.presentation.project.CreativeSetupViewModel
import com.novelforge.app.presentation.project.isCreativeSetupComplete
import com.novelforge.app.presentation.agent.AgentAssistViewModel
import com.novelforge.app.presentation.settings.SettingsScreen
import com.novelforge.app.presentation.story.StoryBibleScreen
import com.novelforge.app.presentation.story.StoryBibleViewModel
import com.novelforge.app.infrastructure.llm.MemorySelector
import com.novelforge.app.infrastructure.llm.chapterMemoryHint
import com.novelforge.app.domain.model.Project
import com.novelforge.app.infrastructure.export.ExportChapter
import com.novelforge.app.infrastructure.export.TxtExporter
import com.novelforge.app.infrastructure.llm.OpenAiCompatibleClient
import com.novelforge.app.domain.model.ChapterRevision
import com.novelforge.app.domain.model.OutlineItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一路剥到 Activity。
 *
 * Compose 里拿到的 Context 常常还包着 ContextThemeWrapper（Dialog、弹窗、
 * 以及带主题的 Activity 都可能），直接 cast 会炸，所以循环剥。
 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 灵感助手专用的 ViewModelStoreOwner。
 *
 * 取不到 Activity 时（只可能在测试或异常的宿主里）退回
 * `LocalViewModelStoreOwner.current`，也就是原来的 NavBackStackEntry ——
 * 行为退回"退出即中断"，不会崩。这是个降级，不该是常态。
 *
 * 两者都拿不到就抛：宁可明确崩在开发期，也不要静悄悄退回"退出一丢"。
 */
@Composable
private fun chatViewModelStoreOwner(): ViewModelStoreOwner =
    LocalContext.current.findActivity() as? ViewModelStoreOwner
        ?: LocalViewModelStoreOwner.current
        ?: error(
            "灵感助手拿不到 ViewModelStoreOwner：Activity 不是 ViewModelStoreOwner，" +
                "且 LocalViewModelStoreOwner 也是 null"
        )

// 导出前把大纲与最新修订拼成章节列表；超长篇下这是 O(N) 大对象操作，放在 IO 线程执行
private fun buildExportChapters(
    chapters: List<OutlineItem>,
    revisions: List<ChapterRevision>
): List<ExportChapter> {
    val latestByItem = revisions.groupBy { it.outlineItemId }
        .mapValues { (_, values) -> values.maxBy { it.revision } }
    return chapters.mapNotNull { item ->
        latestByItem[item.id]?.let { revision ->
            ExportChapter(item.orderIndex, item.title, revision.content)
        }
    }.sortedBy { it.orderIndex }
}

/**
 * 底栏「书架」够得着 LibraryViewModel 的唯一通路。
 *
 * 书内目录不是路由，是 `books` 这一页里的内部状态；而 `books` 是 startDestination，
 * `popUpTo` / `launchSingleTop` / `restoreState` 那套多返回栈参数在书内**关不掉它**
 * （逐条依据见 `onSelectTopLevel` 上面的注释）。所以切 tab 时得直接叫 `close()`。
 */
private class BooksTabHolder {
    var viewModel: com.novelforge.app.presentation.library.LibraryViewModel? = null
}

// ExperimentalLayoutApi：底栏要用 WindowInsets.isImeVisible，
// 而 Scaffold 的 contentWindowInsets 本身也在这套实验 API 里。
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun NovelForgeApp(application: NovelForgeApplication) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val homeViewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.Factory(application.projectRepository)
    )
    val projects by homeViewModel.projects.collectAsStateWithLifecycle()

    // 底栏「书架」需要够得着 LibraryViewModel 才能把书关掉（见 onSelectTopLevel）。
    // 它是在 composable(Routes.BOOKS) 内部建的，而 onSelectTopLevel 定义在外面，
    // 所以这里留一个 holder，由那一处回填。
    //
    // 不用 mutableStateOf(ViewModel)：ViewModel 本身不可变，变化不需要重组，
    // 而且往状态里塞 ViewModel 会诱使人写出「状态变了就重组」的错误预期。
    val booksTab = remember { BooksTabHolder() }

    // 打开一本书。创作设置没做完先进设置页 —— 那是这本书的必经一步。
    val openProject: (Project) -> Unit = { project ->
        val destination = if (isCreativeSetupComplete(project)) {
            Routes.outline(project.id)
        } else {
            Routes.creativeSetup(project.id)
        }
        navController.navigate(destination)
    }

    // 一级目的地之间切换用「多返回栈」：每个 tab 保留自己那一摞页面，
    // 切走再切回来不会退回到首页。之前没有这三个参数，
    // 从任意页面去另一个页面都得先退回首页，页面上又没有常驻导航告诉用户还有别的地方可去。
    // 但这套参数**关不掉书内目录**：书不是路由，是 `books` 这一页里的内部状态
    // （`LibraryViewModel._novel`），而 `books` 恰好是 startDestination。查过
    // Navigation 2.8.5 的源码之后，三件事同时成立：
    //   - `popUpTo` 默认 **非** inclusive，永远弹不到它自己那个 entry；
    //   - `restoreState` 和 `launchSingleTop` 在 `NavController.navigate` 里是
    //     **if/else**，而上面那步会把 `backStackMap[booksId]` 填上，于是
    //     `restoreStateInternal` 抢先命中，`launchSingleTop` 那条分支根本不会走；
    //   - `launchSingleTopInternal` 即便走了也只是换个 entry 壳，同 id 同 store，
    //     ViewModel 还是同一个实例，`_novel` 照样活着。
    // 结果是 `navigate("books")` 在书内是**彻底空操作**，tab 还显示已选中，
    // 用户出不去。所以切到书架时先把书关掉。
    val onSelectTopLevel: (TopLevelDestination) -> Unit = { destination ->
        if (destination == TopLevelDestination.Books) {
            booksTab.viewModel?.close()
        }
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        // 各个页面自己处理 WindowInsets（正文页要避开键盘），
        // 这里传 0 免得 Scaffold 再垫一层，把布局推上去。
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            // isImeVisible 只能在这里读：它是 @Composable 的，进不了
            // showsBottomBar 的纯逻辑（那函数有 JVM 测试，见 Routes.kt 的说明）。
            if (showsBottomBar(currentRoute, imeVisible = WindowInsets.isImeVisible)) {
                NovelForgeBottomBar(
                    current = TopLevelDestination.fromRoute(currentRoute)
                        ?: TopLevelDestination.Books,
                    onSelect = onSelectTopLevel
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.BOOKS,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ————————————————————————————————————————————
            // 一级：书架
            // 这里是全 app 唯一的书籍列表。以前有「全部项目」和「书架」两个门
            // 指向同一份 List<Project>，而两者互不相通 —— 书架里没有任何
            // navigate() 调用，于是从书架进了一本书就再也回不来，也永远进不去写作界面。
            // 现在一本书只有一个入口：点封面 = 继续写（写作是这个 app 的主动作），
            // 阅读变成这本书自己的次要动作。
            // ————————————————————————————————————————————
            composable(Routes.BOOKS) {
                val libraryViewModel: LibraryViewModel = viewModel(
                    factory = LibraryViewModel.Factory(
                        projectRepository = application.projectRepository,
                        outlineRepository = application.outlineRepository,
                        chapterRepository = application.chapterRepository,
                        generationArtifactRepository = application.generationArtifactRepository,
                        readingPositionStore = application.readingPositionStore,
                        coverStore = application.bookCoverStore
                    )
                )
                LibraryScreen(
                    viewModel = libraryViewModel,
                    onContinueWriting = openProject,
                    onOpenCreate = { navController.navigate(Routes.CREATE) }
                )
                // 回填给底栏，让「书架」tab 能把书关掉。用 SideEffect 而不是直接赋值：
                // 组合阶段写普通变量在重组顺序变化时可能读到半成品。
                SideEffect { booksTab.viewModel = libraryViewModel }
            }

            // ————————————————————————————————————————————
            // 一级：灵感
            // 带 projectId 时会话按这本书分桶，不带就落回全局「灵感」桶。
            // 分桶之前，A 书的人设讨论会被整段重发进 B 书的请求里。
            // ————————————————————————————————————————————
            composable(
                route = "${Routes.CHAT}?projectId={projectId}",
                arguments = listOf(
                    navArgument("projectId") { type = NavType.StringType; defaultValue = "" }
                )
            ) { entry ->
                // ViewModel 必须挂在 **Activity** 作用域上，不能用 composable 的默认
                // NavBackStackEntry 作用域。
                //
                // 默认作用域下按返回键，这个 entry 当场销毁 → onCleared →
                // viewModelScope 取消 → 正在跑的 SSE 流被掐断。用户体感是
                // "发完退出，回来什么都没有"，而且是真丢，不是没显示。
                //
                // 挂到 Activity 上之后：流在后台继续跑，增量持续落进 DataStore，
                // 重新进入时拿到的是同一个 ViewModel，messages 已经是最新。
                // 这就是"退出再回来还在生成"能做的最低成本做法。真正跨进程
                // 被杀后恢复要上前台服务，那是另一件事（也没必要 ——
                // 退出这个页面不等于退出应用）。
                val activityOwner = chatViewModelStoreOwner()
                val chatViewModel: ChatViewModel = viewModel(
                    viewModelStoreOwner = activityOwner,
                    // 按作用域分 key：Activity 作用域下所有 ViewModel 共用一个
                    // store，不给 key 的话 A 书的聊天界面会拿到 B 书那个实例
                    key = "chat-${entry.arguments?.getString("projectId").orEmpty()}",
                    factory = ChatViewModel.Factory(
                        settingsStore = application.appSettingsStore,
                        apiKeyStore = application.apiKeyStore,
                        client = OpenAiCompatibleClient(),
                        historyStore = application.chatHistoryStore,
                        llmCallRepository = application.llmCallRepository,
                        attachmentStore = application.chatAttachmentStore,
                        applicationScope = application.applicationScope
                    )
                )
                chatViewModel.bindProjectScope(entry.arguments?.getString("projectId"))
                ChatScreen(viewModel = chatViewModel, onBack = { navController.popBackStack() })
            }

            // ————————————————————————————————————————————
            // 一级：设置（它是一个 hub，不是设置列表本身）
            // 模型连接、外观、壁纸在这一页；账本和备份导出是它的二级页。
            // ————————————————————————————————————————————
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    settingsStore = application.appSettingsStore,
                    presetStore = application.modelPresetStore,
                    wallpaperStore = application.wallpaperStore,
                    apiKeyStore = application.apiKeyStore,
                    onTestConnection = { application.generationRuntime.testConnection() },
                    onFetchModels = { runCatching { application.generationRuntime.fetchModels() } },
                    onOpenLedger = { navController.navigate(Routes.LEDGER) },
                    onOpenExports = { navController.navigate(Routes.EXPORTS) }
                )
            }

            composable(Routes.LEDGER) {
                val ledgerViewModel: LedgerViewModel = viewModel(
                    factory = LedgerViewModel.Factory(application.database.llmCallDao())
                )
                LedgerScreen(viewModel = ledgerViewModel, onBack = { navController.popBackStack() })
            }

            composable(Routes.EXPORTS) {
                ExportsScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.CREATE) {
                CreateProjectScreen(
                    onCreate = { title ->
                        homeViewModel.createProject(title) { project ->
                            navController.navigate(Routes.creativeSetup(project.id)) {
                                popUpTo(Routes.CREATE) { inclusive = true }
                            }
                        }
                    },
                    onCancel = { navController.popBackStack() }
                )
            }

            // ————————————————————————————————————————————
            // 书内
            // ————————————————————————————————————————————
            composable(
                route = Routes.CREATIVE_SETUP,
                arguments = listOf(navArgument("projectId") { type = NavType.StringType })
            ) { entry ->
                val projectId = requireNotNull(entry.arguments?.getString("projectId"))
                val viewModel: CreativeSetupViewModel = viewModel(
                    key = "creative-setup-$projectId",
                    factory = CreativeSetupViewModel.Factory(
                        projectId = projectId,
                        repository = application.projectRepository
                    )
                )
                val project by viewModel.project.collectAsStateWithLifecycle()
                val error by viewModel.error.collectAsStateWithLifecycle()
                val saving by viewModel.saving.collectAsStateWithLifecycle()
                when {
                    project != null -> CreativeSetupScreen(
                        project = project!!,
                        saving = saving,
                        error = error,
                        onSave = { config, questData ->
                            viewModel.save(config, questData) {
                                navController.navigate(Routes.outline(projectId)) {
                                    popUpTo(Routes.creativeSetup(projectId)) { inclusive = true }
                                }
                            }
                        },
                        onSaveAndAutoRun = { config, questData ->
                            viewModel.save(config, questData) {
                                navController.navigate(Routes.outlineAuto(projectId)) {
                                    popUpTo(Routes.creativeSetup(projectId)) { inclusive = true }
                                }
                            }
                        },
                        onClearError = viewModel::clearError,
                        onBack = { navController.popBackStack() }
                    )
                    error != null -> com.novelforge.app.presentation.common.PaperMessage(
                        // 「无法打开该小说」而不是「项目没有打开」：主语是项目、
                        // 谓语是"没有打开"，语法上不通；而且界面上没有"项目"
                        // 这个东西，全 App 一律叫小说
                        text = "无法打开该小说",
                        detail = error
                    )
                    else -> com.novelforge.app.presentation.common.PaperMessage(text = "正在加载小说…")
                }
            }

            // 书的 hub。改过之后它有返回键了 —— 以前这里是全 app 唯一一个
            // 没有返回按钮的页面，恰恰也是用户停留最久、且未保存编辑会被静默
            // 销毁的那个页面。编辑缓冲已经搬进 ViewModel，退出还有未保存确认。
            composable(
                route = "${Routes.OUTLINE}?autostart={autostart}",
                arguments = listOf(
                    navArgument("projectId") { type = NavType.StringType },
                    navArgument("autostart") { type = NavType.BoolType; defaultValue = false }
                )
            ) { entry ->
                val projectId = requireNotNull(entry.arguments?.getString("projectId"))
                val autostart = entry.arguments?.getBoolean("autostart") == true
                val title = projects.firstOrNull { it.id == projectId }?.title.orEmpty()
                val viewModel: OutlineViewModel = viewModel(
                    key = "outline-$projectId",
                    factory = OutlineViewModel.Factory(
                        projectId = projectId,
                        projectRepository = application.projectRepository,
                        outlineRepository = application.outlineRepository,
                        chapterRepository = application.chapterRepository,
                        generationRepository = application.generationRepository,
                        generationArtifactRepository = application.generationArtifactRepository,
                        generationRuntime = application.generationRuntime
                    )
                )
                val agentViewModel: AgentAssistViewModel = viewModel(
                    key = "agent-$projectId",
                    factory = AgentAssistViewModel.Factory(
                        projectId = projectId,
                        bookQuestion = application.bookQuestion,
                        traceStore = application.agentTraceStore
                    )
                )
                val agentSteps by agentViewModel.steps.collectAsStateWithLifecycle()
                val agentBusy by agentViewModel.busy.collectAsStateWithLifecycle()
                val agentError by agentViewModel.error.collectAsStateWithLifecycle()
                androidx.compose.runtime.LaunchedEffect(autostart, projectId) {
                    // 进书页顺手收一次僵尸任务（幂等、廉价查询），长时间驻留的进程也能自愈
                    runCatching { application.generationRuntime.sweepZombieJobs() }
                    if (autostart) viewModel.startAutoRun()
                }
                OutlineScreen(
                    viewModel = viewModel,
                    projectTitle = title,
                    onBack = { navController.popBackStack() },
                    onOpenChapter = { item ->
                        navController.navigate(Routes.chapter(projectId, item.id))
                    },
                    onOpenMemory = { navController.navigate(Routes.memory(projectId)) },
                    agentSteps = agentSteps,
                    agentBusy = agentBusy,
                    agentError = agentError,
                    onAskAgent = agentViewModel::ask,
                    // 灵感助手从书里打开：**刻意不带** tab 那套多返回栈参数。
                    //
                    // 原来这里是照抄 `onSelectTopLevel` 的三个参数，而那一套只对
                    // 「一级目的地之间切换」成立。带上的后果是 `popUpTo(books, saveState)`
                    // 会把**当前这本书的大纲从栈里弹掉**（存进 backStackMap），于是：
                    //   - 顶栏「返回」落在**书架**，不是这本书的大纲（用户预期是回书里）；
                    //   - 之后点底栏「书架」会**把大纲恢复出来**，于是「书架」永远
                    //     显示不出来 —— 屏幕上是大纲，底栏却亮着书架；
                    //   - 而且只有第一轮能恢复，第二轮的状态直接被丢掉。
                    // 三个问题都来自同一个多余的参数。
                    //
                    // 改成普通 push：大纲留在栈里，返回正好回这本书；
                    // 「书架」tab 也回到它该做的事（显示书架）。
                    onOpenChat = { navController.navigate(Routes.chat(projectId)) }
                )
            }

            composable(
                route = Routes.MEMORY,
                arguments = listOf(navArgument("projectId") { type = NavType.StringType })
            ) { entry ->
                val projectId = requireNotNull(entry.arguments?.getString("projectId"))
                val bibleViewModel: StoryBibleViewModel = viewModel(
                    key = "memory-$projectId",
                    factory = StoryBibleViewModel.Factory(projectId, application.projectRepository)
                )
                StoryBibleScreen(
                    viewModel = bibleViewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                route = "${Routes.CHAPTER}?autostart={autostart}",
                arguments = listOf(
                    navArgument("projectId") { type = NavType.StringType },
                    navArgument("outlineItemId") { type = NavType.StringType },
                    navArgument("autostart") { type = NavType.BoolType; defaultValue = false }
                )
            ) { entry ->
                val projectId = requireNotNull(entry.arguments?.getString("projectId"))
                val outlineItemId = requireNotNull(entry.arguments?.getString("outlineItemId"))
                val autostart = entry.arguments?.getBoolean("autostart") == true
                val title = projects.firstOrNull { it.id == projectId }?.title.orEmpty()
                val viewModel: ChapterViewModel = viewModel(
                    key = "chapter-$projectId-$outlineItemId",
                    factory = ChapterViewModel.Factory(
                        projectId = projectId,
                        targetId = outlineItemId,
                        projectRepository = application.projectRepository,
                        outlineRepository = application.outlineRepository,
                        chapterRepository = application.chapterRepository,
                        generationRepository = application.generationRepository,
                        generationRuntime = application.generationRuntime
                    )
                )
                val outlines by viewModel.outlines.collectAsStateWithLifecycle()
                val revisions by viewModel.revisions.collectAsStateWithLifecycle()
                val job by viewModel.activeJob.collectAsStateWithLifecycle()
                val error by viewModel.error.collectAsStateWithLifecycle()
                val book by viewModel.project.collectAsStateWithLifecycle()
                val excludedCharacters by viewModel.excludedCharacters.collectAsStateWithLifecycle()
                val excludedThreads by viewModel.excludedThreads.collectAsStateWithLifecycle()
                val chapter = outlines.firstOrNull()?.chapters?.firstOrNull { it.id == outlineItemId }
                // 必须 remember：select 内部要把全部已确认事实排序打分，
                // 而 continuityState 每写一章就整块换新，不缓存的话每次重组都重算一遍。
                val continuity = book?.continuityState
                val inputBudget = book?.creativeConfig?.inputBudget ?: 8_000
                val chapterHint = chapter?.let { item ->
                    chapterMemoryHint(item.title, item.summary, item.characterChanges)
                }.orEmpty()
                val memory = remember(continuity, excludedCharacters, excludedThreads, inputBudget, chapterHint) {
                    continuity?.let {
                        MemorySelector.select(
                            it,
                            excludedCharacterIds = excludedCharacters,
                            excludedThreads = excludedThreads,
                            inputBudget = inputBudget,
                            chapterHint = chapterHint
                        )
                    }
                }
                val hasRevision = revisions.any { it.outlineItemId == outlineItemId }
                val exportScope = rememberCoroutineScope()
                androidx.compose.runtime.LaunchedEffect(autostart, chapter?.id, job?.id, hasRevision) {
                    if (autostart && chapter != null && job == null && !hasRevision) {
                        viewModel.generate(chapter)
                    }
                }
                ChapterScreen(
                    projectTitle = title,
                    chapter = chapter,
                    revision = chapter?.let(viewModel::revisionFor),
                    job = job,
                    error = error,
                    onGenerate = viewModel::generate,
                    onCancel = viewModel::cancel,
                    onRetry = viewModel::generate,
                    onExport = {
                        val outlineChapters = outlines.firstOrNull()?.chapters.orEmpty()
                        val revisionList = revisions
                        exportScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val chapters = buildExportChapters(outlineChapters, revisionList)
                                    if (chapters.isEmpty()) null
                                    else TxtExporter().saveToPublicDownloads(application, title, chapters)
                                }
                            }
                            result.onSuccess { saved ->
                                if (saved != null) {
                                    android.widget.Toast.makeText(
                                        application,
                                        "已保存到 ${saved.location}/${saved.displayName}",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            }.onFailure { error ->
                                android.widget.Toast.makeText(
                                    application,
                                    "保存失败：${error.message ?: "未知错误"}",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    onShare = {
                        val outlineChapters = outlines.firstOrNull()?.chapters.orEmpty()
                        val revisionList = revisions
                        exportScope.launch {
                            val file = withContext(Dispatchers.IO) {
                                val chapters = buildExportChapters(outlineChapters, revisionList)
                                if (chapters.isEmpty()) null
                                else TxtExporter().writeToCache(application, title, chapters)
                            }
                            if (file != null) {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, TxtExporter().shareUri(application, file))
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                application.startActivity(
                                    // 分享对话框的标题只说格式：目标就是 TXT 文件本身，说「分享小说 TXT」
        // 容易被理解成在分享整本小说。同一文件里另外两处也都写「分享 TXT」。
        Intent.createChooser(send, "分享 TXT")
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    },
                    // 「回到这本书」而不是「回到 App 首页」。以前这一下把大纲和正文
                    // 两层一起弹掉，而且重新进来永远落在大纲页 —— 书里读到第几章
                    // 这个信息在写作侧从来没有被记下来过。
                    onBackToBook = {
                        navController.navigate(Routes.outline(projectId)) {
                            popUpTo(Routes.OUTLINE) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onClearError = viewModel::clearError,
                    onBack = { navController.popBackStack() },
                    previousChapter = viewModel.previousChapterFor(chapter)?.let { item ->
                        ChapterNeighbor(
                            label = chapterLabel(item.orderIndex),
                            hasRevision = viewModel.hasRevisionFor(item),
                            // 往回走不要带 autostart：目标章可能已经有正文，
                            // 带了也只会什么都不做。
                            onOpen = { navController.navigate(Routes.chapter(projectId, item.id)) }
                        )
                    },
                    nextChapter = viewModel.nextChapterFor(chapter)?.let { item ->
                        ChapterNeighbor(
                            label = chapterLabel(item.orderIndex),
                            hasRevision = viewModel.hasRevisionFor(item),
                            onOpen = {
                                // 替换式导航：新章替换栈里的当前章，返回一步到大纲。
                                // saveState/restoreState 是必须的：SavedStateHandle 跟着
                                // NavBackStackEntry 一起死，而这一跳会把当前这条弹掉，
                                // 不存就等于「排除掉的角色」又被悄悄放回去。
                                navController.navigate(Routes.chapterAuto(projectId, item.id)) {
                                    popUpTo(Routes.chapter(projectId, outlineItemId)) {
                                        inclusive = true
                                        saveState = true
                                    }
                                    restoreState = true
                                    launchSingleTop = true
                                }
                            }
                        )
                    },
                    memoryCharacters = book?.continuityState?.characters.orEmpty()
                        .filter { it.name.isNotBlank() }
                        .map { it.id to it.name },
                    excludedCharacterIds = excludedCharacters,
                    memoryThreads = book?.continuityState?.unresolvedThreads.orEmpty().filter { it.isNotBlank() },
                    excludedThreads = excludedThreads,
                    factCount = memory?.factCount ?: 0,
                    omittedCharacters = memory?.omittedCharacters ?: 0,
                    omittedThreads = memory?.omittedThreads ?: 0,
                    omittedRules = memory?.omittedRules ?: 0,
                    pendingCount = book?.continuityState?.pendingFacts?.size ?: 0,
                    onToggleCharacter = viewModel::toggleCharacter,
                    onToggleThread = viewModel::toggleThread,
                    onOpenMemory = { navController.navigate(Routes.memory(projectId)) },
                    previousRevision = chapter?.let(viewModel::previousRevisionFor),
                    onRestorePrevious = { chapter?.let(viewModel::restorePrevious) }
                )
            }
        }
    }
}
