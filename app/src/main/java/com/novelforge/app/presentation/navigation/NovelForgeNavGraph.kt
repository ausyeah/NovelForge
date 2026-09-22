package com.novelforge.app.presentation.navigation

import android.net.Uri
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.novelforge.app.NovelForgeApplication
import com.novelforge.app.presentation.chapter.ChapterScreen
import com.novelforge.app.presentation.chat.ChatScreen
import com.novelforge.app.presentation.chat.ChatViewModel
import com.novelforge.app.presentation.chapter.ChapterViewModel
import com.novelforge.app.presentation.exports.ExportsScreen
import com.novelforge.app.presentation.home.HomeScreen
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
import com.novelforge.app.presentation.projects.ProjectsScreen
import com.novelforge.app.presentation.settings.SettingsScreen
import com.novelforge.app.presentation.story.StoryBibleScreen
import com.novelforge.app.presentation.story.StoryBibleViewModel
import com.novelforge.app.infrastructure.llm.MemorySelector
import com.novelforge.app.domain.model.Project
import com.novelforge.app.infrastructure.export.ExportChapter
import com.novelforge.app.infrastructure.export.TxtExporter
import com.novelforge.app.infrastructure.llm.OpenAiCompatibleClient
import com.novelforge.app.domain.model.ChapterRevision
import com.novelforge.app.domain.model.OutlineItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    }
}

@Composable
fun NovelForgeApp(application: NovelForgeApplication) {
    val navController = rememberNavController()
    val homeViewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.Factory(application.projectRepository)
    )
    val projects by homeViewModel.projects.collectAsStateWithLifecycle()
    val homeOperationError by homeViewModel.operationError.collectAsStateWithLifecycle()
    val openProject: (Project) -> Unit = { project ->
        val destination = if (isCreativeSetupComplete(project)) {
            "outline/${project.id}"
        } else {
            "creative-setup/${project.id}"
        }
        navController.navigate(destination)
    }

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                projects = projects,
                onContinue = openProject,
                onCreateProject = { navController.navigate("create") },
                onOpenProjects = { navController.navigate("projects") },
                onOpenSettings = { navController.navigate("settings") },
                onOpenExports = { navController.navigate("exports") },
                onOpenLibrary = { navController.navigate("library") },
                onOpenChat = { navController.navigate("chat") },
                onOpenLedger = { navController.navigate("ledger") }
            )
        }
        composable("ledger") {
            val ledgerViewModel: LedgerViewModel = viewModel(
                factory = LedgerViewModel.Factory(application.database.llmCallDao())
            )
            LedgerScreen(viewModel = ledgerViewModel, onBack = { navController.popBackStack() })
        }
        composable("projects") {
            ProjectsScreen(
                projects = projects,
                operationError = homeOperationError,
                onOpenProject = openProject,
                onRenameProject = homeViewModel::renameProject,
                onDeleteProject = homeViewModel::deleteProject,
                onClearOperationError = homeViewModel::clearOperationError,
                onCreateProject = { navController.navigate("create") },
                onBack = { navController.popBackStack() }
            )
        }
        composable("exports") {
            ExportsScreen(onBack = { navController.popBackStack() })
        }
        composable("chat") {
            val chatViewModel: ChatViewModel = viewModel(
                factory = ChatViewModel.Factory(
                    settingsStore = application.appSettingsStore,
                    apiKeyStore = application.apiKeyStore,
                    client = OpenAiCompatibleClient(),
                    historyStore = application.chatHistoryStore,
                    llmCallRepository = application.llmCallRepository
                )
            )
            ChatScreen(viewModel = chatViewModel, onBack = { navController.popBackStack() })
        }
        composable("library") {
            val libraryViewModel: LibraryViewModel = viewModel(
                factory = LibraryViewModel.Factory(
                    projectRepository = application.projectRepository,
                    outlineRepository = application.outlineRepository,
                    chapterRepository = application.chapterRepository,
                    generationArtifactRepository = application.generationArtifactRepository,
                    readingPositionStore = application.readingPositionStore
                )
            )
            LibraryScreen(viewModel = libraryViewModel, onBack = { navController.popBackStack() })
        }
        composable("create") {
            CreateProjectScreen(
                onCreate = { title ->
                    homeViewModel.createProject(title) { project ->
                        navController.navigate("creative-setup/${project.id}") {
                            popUpTo("create") { inclusive = true }
                        }
                    }
                },
                onCancel = { navController.popBackStack() }
            )
        }
        composable(
            route = "creative-setup/{projectId}",
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
                            navController.navigate("outline/$projectId") {
                                popUpTo("creative-setup/$projectId") { inclusive = true }
                            }
                        }
                    },
                    onSaveAndAutoRun = { config, questData ->
                        viewModel.save(config, questData) {
                            navController.navigate("outline/$projectId?autostart=true") {
                                popUpTo("creative-setup/$projectId") { inclusive = true }
                            }
                        }
                    },
                    onClearError = viewModel::clearError,
                    onBack = { navController.popBackStack() }
                )
                error != null -> com.novelforge.app.presentation.common.PaperMessage(
                    text = "项目没有打开",
                    detail = error
                )
                else -> com.novelforge.app.presentation.common.PaperMessage(text = "正在加载项目…")
            }
        }
        composable("settings") {
            SettingsScreen(
                settingsStore = application.appSettingsStore,
                apiKeyStore = application.apiKeyStore,
                onTestConnection = { application.generationRuntime.testConnection() },
                onFetchModels = { runCatching { application.generationRuntime.fetchModels() } },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = "outline/{projectId}?autostart={autostart}",
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
            val outlineVersion by viewModel.outline.collectAsStateWithLifecycle()
            val job by viewModel.activeJob.collectAsStateWithLifecycle()
            val error by viewModel.error.collectAsStateWithLifecycle()
            val autoRun by viewModel.autoRun.collectAsStateWithLifecycle()
            val chapterJob by viewModel.chapterJob.collectAsStateWithLifecycle()
            val writtenChapterIds by viewModel.writtenChapterIds.collectAsStateWithLifecycle()
            val optimizingIndex by viewModel.optimizingIndex.collectAsStateWithLifecycle()
            val wandResult by viewModel.wandResult.collectAsStateWithLifecycle()
            androidx.compose.runtime.LaunchedEffect(autostart, projectId) {
                // 进大纲页顺手收一次僵尸任务（幂等、廉价查询），长时间驻留的进程也能自愈
                runCatching { application.generationRuntime.sweepZombieJobs() }
                if (autostart) viewModel.startAutoRun()
            }
            OutlineScreen(
                projectTitle = title,
                outline = outlineVersion,
                job = job,
                chapterJob = chapterJob,
                writtenChapterIds = writtenChapterIds,
                plannedChapterCount = projects.firstOrNull { it.id == projectId }
                    ?.creativeConfig?.chapterCount,
                error = error,
                autoRun = autoRun,
                onToggleAutoRun = viewModel::setAutoRun,
                onStartAutoRun = viewModel::startAutoRun,
                onGenerate = viewModel::generate,
                onCancel = viewModel::cancel,
                onSave = viewModel::saveEditedItems,
                onSaveRaw = viewModel::saveRawOutline,
                onOpenChapter = { item ->
                    navController.navigate("chapter/$projectId/${Uri.encode(item.id)}")
                },
                onClearError = viewModel::clearError,
                optimizingIndex = optimizingIndex,
                wandResult = wandResult,
                onOptimize = viewModel::optimizeChapter,
                onStopOptimize = viewModel::stopOptimize,
                onConsumeWand = viewModel::consumeWandResult,
                onRegenerateFrom = viewModel::regenerateFrom,
                onOpenMemory = { navController.navigate("memory/$projectId") }
            )
        }
        composable(
            route = "memory/{projectId}",
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
            route = "chapter/{projectId}/{outlineItemId}?autostart={autostart}",
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
            val memory = book?.let {
                MemorySelector.select(
                    it.continuityState,
                    excludedCharacterIds = excludedCharacters,
                    excludedThreads = excludedThreads,
                    inputBudget = it.creativeConfig?.inputBudget ?: 8_000
                )
            }
            val chapter = outlines.firstOrNull()?.chapters?.firstOrNull { it.id == outlineItemId }
            // 留洞后 orderIndex 不连续：取"序号更大的下一章"而不是 +1 精确匹配
            val nextChapter = outlines.firstOrNull()?.chapters
                ?.sortedBy { it.orderIndex }
                ?.firstOrNull { it.orderIndex > (chapter?.orderIndex ?: Int.MAX_VALUE) }
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
                onNextChapter = nextChapter?.let { item ->
                    {
                        // 替换式导航：新章替换栈里的当前章，保证“返回大纲”一步到位
                        navController.navigate("chapter/$projectId/${Uri.encode(item.id)}?autostart=true") {
                            popUpTo("chapter/$projectId/${Uri.encode(outlineItemId)}") { inclusive = true }
                        }
                    }
                },
                onBackHome = { navController.popBackStack("home", false) },
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
                                Intent.createChooser(send, "分享小说 TXT")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                },
                onClearError = viewModel::clearError,
                onBack = { navController.popBackStack() },
                memoryCharacters = book?.continuityState?.characters.orEmpty()
                    .filter { it.name.isNotBlank() }
                    .map { it.id to it.name },
                excludedCharacterIds = excludedCharacters,
                memoryThreads = book?.continuityState?.unresolvedThreads.orEmpty().filter { it.isNotBlank() },
                excludedThreads = excludedThreads,
                factCount = memory?.factCount ?: 0,
                pendingCount = book?.continuityState?.pendingFacts?.size ?: 0,
                onToggleCharacter = viewModel::toggleCharacter,
                onToggleThread = viewModel::toggleThread,
                onOpenMemory = { navController.navigate("memory/$projectId") },
                previousRevision = chapter?.let(viewModel::previousRevisionFor),
                onRestorePrevious = { chapter?.let(viewModel::restorePrevious) }
            )
        }
    }
}
