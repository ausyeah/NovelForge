package com.novelforge.app

import android.app.Application
import androidx.room.Room
import com.novelforge.app.data.local.AppDatabase
import com.novelforge.app.data.local.DatabaseMigrations
import com.novelforge.app.data.repository.RoomGenerationRepository
import com.novelforge.app.data.repository.RoomGenerationArtifactRepository
import com.novelforge.app.data.repository.RoomChapterRepository
import com.novelforge.app.data.repository.RoomOutlineRepository
import com.novelforge.app.data.repository.RoomPromptSnapshotRepository
import com.novelforge.app.data.repository.RoomProjectRepository
import com.novelforge.app.data.security.KeystoreApiKeyStore
import com.novelforge.app.data.settings.AppSettingsStore
import com.novelforge.app.infrastructure.jobs.GenerationRuntime
import com.novelforge.app.infrastructure.jobs.GenerationWorkerDependencies
import com.novelforge.app.infrastructure.jobs.GenerationWorkerDependenciesProvider

class NovelForgeApplication : Application(), GenerationWorkerDependenciesProvider {
    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "novelforge.db")
            .addMigrations(*DatabaseMigrations.all)
            .build()
    }

    val projectRepository by lazy { RoomProjectRepository(database) }
    val generationRepository by lazy { RoomGenerationRepository(database.generationJobDao()) }
    val generationArtifactRepository by lazy { RoomGenerationArtifactRepository(database) }
    val outlineRepository by lazy { RoomOutlineRepository(database.outlineVersionDao()) }
    val chapterRepository by lazy { RoomChapterRepository(database.chapterRevisionDao()) }
    val promptSnapshotRepository by lazy { RoomPromptSnapshotRepository(database.promptSnapshotDao()) }
    val apiKeyStore by lazy { KeystoreApiKeyStore(this) }
    val appSettingsStore by lazy { AppSettingsStore(this) }

    val generationRuntime by lazy {
        GenerationRuntime(
            context = this,
            projectRepository = projectRepository,
            generationRepository = generationRepository,
            generationArtifactRepository = generationArtifactRepository,
            outlineRepository = outlineRepository,
            chapterRepository = chapterRepository,
            promptSnapshotRepository = promptSnapshotRepository,
            settingsStore = appSettingsStore,
            apiKeyStore = apiKeyStore
        )
    }

    override fun generationWorkerDependencies(): GenerationWorkerDependencies = generationRuntime
}
