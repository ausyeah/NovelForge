package com.novelforge.app.presentation.navigation

import android.net.Uri
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.novelforge.app.presentation.outline.OutlineScreen
import com.novelforge.app.presentation.outline.OutlineViewModel
import com.novelforge.app.presentation.project.CreateProjectScreen
import com.novelforge.app.presentation.project.CreativeSetupScreen
import com.novelforge.app.presentation.project.CreativeSetupViewModel
import com.novelforge.app.presentation.project.isCreativeSetupComplete
import com.novelforge.app.presentation.settings.SettingsScreen
import com.novelforge.app.infrastructure.export.ExportChapter
import com.novelforge.app.infrastructure.export.TxtExporter
import com.novelforge.app.infrastructure.llm.OpenAiCompatibleClient

@Composable
fun NovelForgeApp(application: NovelForgeApplication) {
    val navController = rememberNavController()
    val homeViewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.Factory(application.projectRepository)
    )
    val projects by homeViewModel.projects.collectAsStateWithLifecycle()
    val homeOperationError by homeViewModel.operationError.collectAsStateWithLifecycle()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                projects = projects,
                onCreateProject = { navController.navigate("create") },
                onOpenSettings = { navController.navigate("settings") },
                onOpenExports = { navController.navigate("exports") },
                onOpenLibrary = { navController.navigate("library") },
                onOpenChat = { navController.navigate("chat") },
                onOpenProject = { project ->
                    val destination = if (isCreativeSetupComplete(project)) {
                        "outline/${project.id}"
                    } else {
                        "creative-setup/${project.id}"
                    }
                    navController.navigate(destination)
                },
                operationError = homeOperationError,
                onRenameProject = homeViewModel::renameProject,
                onDeleteProject = homeViewModel::deleteProject,
                onClearOperationError = homeViewModel::clearOperationError
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
                    client = OpenAiCompatibleClient()
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
                    readingPositionStore = com.novelforge.app.data.settings.ReadingPositionStore(application)
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
                error != null -> androidx.compose.material3.Text(error!!)
                else -> androidx.compose.material3.Text("正在加载项目…")
            }
        }
        composable("settings") {
            SettingsScreen(
                settingsStore = application.appSettingsStore,
                apiKeyStore = application.apiKeyStore,
                onTestConnection = { application.generationRuntime.testConnection() },
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
            val versions by viewModel.versions.collectAsStateWithLifecycle()
            val job by viewModel.activeJob.collectAsStateWithLifecycle()
            val error by viewModel.error.collectAsStateWithLifecycle()
            val autoRun by viewModel.autoRun.collectAsStateWithLifecycle()
            val chapterJob by viewModel.chapterJob.collectAsStateWithLifecycle()
            val writtenChapterIds by viewModel.writtenChapterIds.collectAsStateWithLifecycle()
            androidx.compose.runtime.LaunchedEffect(autostart, projectId) {
                if (autostart) viewModel.startAutoRun()
            }
            OutlineScreen(
                projectTitle = title,
                versions = versions,
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
            val chapter = outlines.firstOrNull()?.chapters?.firstOrNull { it.id == outlineItemId }
            val nextChapter = outlines.firstOrNull()?.chapters
                ?.sortedBy { it.orderIndex }
                ?.firstOrNull { it.orderIndex == (chapter?.orderIndex ?: -1) + 1 }
            val hasRevision = revisions.any { it.outlineItemId == outlineItemId }
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
                    val latestByItem = revisions.groupBy { it.outlineItemId }
                        .mapValues { (_, values) -> values.maxBy { it.revision } }
                    val chapters = outlines.firstOrNull()?.chapters.orEmpty().mapNotNull { item ->
                        latestByItem[item.id]?.let { revision ->
                            ExportChapter(item.orderIndex, item.title, revision.content)
                        }
                    }
                    if (chapters.isNotEmpty()) {
                        runCatching {
                            TxtExporter().saveToPublicDownloads(application, title, chapters)
                        }.onSuccess { saved ->
                            android.widget.Toast.makeText(
                                application,
                                "已保存到 ${saved.location}/${saved.displayName}",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
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
                    val latestByItem = revisions.groupBy { it.outlineItemId }
                        .mapValues { (_, values) -> values.maxBy { it.revision } }
                    val chapters = outlines.firstOrNull()?.chapters.orEmpty().mapNotNull { item ->
                        latestByItem[item.id]?.let { revision ->
                            ExportChapter(item.orderIndex, item.title, revision.content)
                        }
                    }
                    if (chapters.isNotEmpty()) {
                        val file = TxtExporter().writeToCache(application, title, chapters)
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
                },
                onClearError = viewModel::clearError,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
