package com.novelforge.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import com.novelforge.app.data.local.AppDatabase
import com.novelforge.app.data.local.DatabaseMigrations
import com.novelforge.app.data.repository.RoomGenerationRepository
import com.novelforge.app.data.repository.RoomGenerationArtifactRepository
import com.novelforge.app.data.repository.RoomLlmCallRepository
import com.novelforge.app.data.repository.RoomChapterRepository
import com.novelforge.app.data.repository.RoomOutlineRepository
import com.novelforge.app.data.repository.RoomPromptSnapshotRepository
import com.novelforge.app.data.repository.RoomProjectRepository
import com.novelforge.app.data.security.KeystoreApiKeyStore
import com.novelforge.app.agent.BookQuestion
import com.novelforge.app.agent.LlmAgentModel
import com.novelforge.app.agent.RoomNovelBookStore
import com.novelforge.app.data.agent.AgentTraceStore
import com.novelforge.app.data.settings.AppSettingsStore
import com.novelforge.app.data.settings.ModelPresetStore
import com.novelforge.app.ui.theme.WallpaperStore
import com.novelforge.app.infrastructure.jobs.GenerationRuntime
import com.novelforge.app.infrastructure.jobs.GenerationWorkerDependencies
import com.novelforge.app.infrastructure.jobs.GenerationWorkerDependenciesProvider
import com.novelforge.app.infrastructure.jobs.GenerationWorkerFactory
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NovelForgeApplication : Application(), GenerationWorkerDependenciesProvider {
    private val appScope = CoroutineScope(
        SupervisorJob() + kotlinx.coroutines.Dispatchers.Default +
            CoroutineExceptionHandler { _, _ ->
                // 启动清扫跑在后台；漏网异常不能把整个进程打掉
            }
    )

    val database: AppDatabase by lazy { openResilientDatabase() }

    val projectRepository by lazy { RoomProjectRepository(database) }
    val generationRepository by lazy { RoomGenerationRepository(database.generationJobDao(), database) }
    val generationArtifactRepository by lazy { RoomGenerationArtifactRepository(database) }
    val outlineRepository by lazy { RoomOutlineRepository(database.outlineVersionDao()) }
    val chapterRepository by lazy { RoomChapterRepository(database.chapterRevisionDao()) }
    val promptSnapshotRepository by lazy { RoomPromptSnapshotRepository(database.promptSnapshotDao()) }
    val llmCallRepository by lazy { RoomLlmCallRepository(database.llmCallDao()) }
    val apiKeyStore by lazy { KeystoreApiKeyStore(this) }
    val appSettingsStore by lazy { AppSettingsStore(this) }
    // 一键全自动按书存：全局单值会让 A 书的开关去驱动 B 书的自动续写
    val autoRunStore by lazy { com.novelforge.app.data.settings.AutoRunStore(this) }
    val modelPresetStore by lazy { ModelPresetStore(this, apiKeyStore) }
    val agentTraceStore by lazy { AgentTraceStore(this) }
    private val bookStore by lazy {
        RoomNovelBookStore(
            projectRepository = projectRepository,
            outlineRepository = outlineRepository,
            chapterRepository = chapterRepository,
            artifacts = generationArtifactRepository,
            enqueue = { projectId, chapter, context ->
                generationRuntime.queueChapter(projectId, chapter, context).id
            }
        )
    }
    val agentModel by lazy { LlmAgentModel(appSettingsStore, apiKeyStore) }
    val bookQuestion by lazy {
        BookQuestion(bookStore) { context, question ->
            agentModel.answerAboutBook(context, question)
        }
    }
    val wallpaperStore by lazy { WallpaperStore(this) }
    val bookCoverStore by lazy { com.novelforge.app.data.cover.BookCoverStore(this) }
    val chatHistoryStore by lazy { com.novelforge.app.data.chat.ChatHistoryStore(this) }
    val chatAttachmentStore by lazy { com.novelforge.app.data.chat.ChatAttachmentStore(this) }
    // 必须全局单例：PreferenceDataStoreFactory 每次 create 都会注册一个新 DataStore，
    // 同一文件多个实例并存会直接抛 IllegalStateException（点开书架即闪退的根因）
    val readingPositionStore by lazy { com.novelforge.app.data.settings.ReadingPositionStore(this) }

    val backupStore by lazy {
        com.novelforge.app.infrastructure.backup.BackupStore(
            database = database,
            projectRepository = projectRepository,
            outlineRepository = outlineRepository,
            chapterRepository = chapterRepository
        )
    }

    val generationRuntime by lazy {
        GenerationRuntime(
            context = this,
            projectRepository = projectRepository,
            generationRepository = generationRepository,
            generationArtifactRepository = generationArtifactRepository,
            outlineRepository = outlineRepository,
            chapterRepository = chapterRepository,
            promptSnapshotRepository = promptSnapshotRepository,
            llmCallRepository = llmCallRepository,
            settingsStore = appSettingsStore,
            autoRunStore = autoRunStore,
            apiKeyStore = apiKeyStore
        )
    }

    override fun generationWorkerDependencies(): GenerationWorkerDependencies = generationRuntime

    private val workManagerConfig by lazy {
        Configuration.Builder()
            .setWorkerFactory(GenerationWorkerFactory(generationRuntime))
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        // 手动初始化 WorkManager：清单已移除默认初始化器，自定义 WorkerFactory 必须最先就位，
        // 否则所有生成任务报 "Could not create Worker" 直接失败
        if (!WorkManager.isInitialized()) {
            WorkManager.initialize(this, workManagerConfig)
        }
        com.novelforge.app.infrastructure.backup.NovelForgeRefs.application = this
        // 通知渠道提前建好：前台任务的 createForegroundInfo 必须快，否则系统判超时杀进程
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                GENERATION_CHANNEL_ID,
                "小说生成任务",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        // 进程被杀后残留的 QUEUED/RUNNING 僵尸任务会把一键全自动卡死，启动即清扫
        appScope.launch { runCatching { wallpaperStore.loadSaved() } }
        appScope.launch {
            kotlinx.coroutines.delay(1_500)
            runCatching { generationRuntime.sweepZombieJobs() }
            // 老账没记 token 的一次性补齐（只碰 NULL 行，跑完即静默）
            runCatching { generationRuntime.backfillUsageEstimates() }
        }
    }

    private fun buildRoomDatabase(): AppDatabase =
        Room.databaseBuilder(this, AppDatabase::class.java, DB_NAME)
            .addMigrations(*DatabaseMigrations.all)
            .build()

    /**
     * 迁移缺失或文件损坏时，Room 会在第一次查询抛异常，首页的 stateIn 接着把进程打掉。
     * 打不开的库改名留在原目录，再开一个空库，避免每次启动都闪退。
     */
    private fun openResilientDatabase(): AppDatabase {
        val first = buildRoomDatabase()
        return try {
            first.openHelper.writableDatabase
            first
        } catch (error: Exception) {
            runCatching { first.close() }
            if (error is IllegalStateException || error is SQLiteException) {
                quarantineDatabaseFiles()
                buildRoomDatabase()
            } else {
                throw error
            }
        }
    }

    private fun quarantineDatabaseFiles() {
        val file = getDatabasePath(DB_NAME)
        val stamp = System.currentTimeMillis()
        val dir = file.parentFile
        runCatching {
            if (file.exists()) {
                file.renameTo(java.io.File(dir, "novelforge-unreadable-$stamp.db"))
            }
        }
        runCatching { java.io.File(file.path + "-wal").delete() }
        runCatching { java.io.File(file.path + "-shm").delete() }
    }

    companion object {
        const val GENERATION_CHANNEL_ID = "novelforge_generation"
        private const val DB_NAME = "novelforge.db"
    }
}
