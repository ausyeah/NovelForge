package com.novelforge.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProjectEntity::class,
        OutlineVersionEntity::class,
        CharacterSnapshotEntity::class,
        ChapterRevisionEntity::class,
        GenerationJobEntity::class,
        PromptSnapshotEntity::class,
        LlmCallEntity::class,
        QualityRunEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun outlineVersionDao(): OutlineVersionDao
    abstract fun characterSnapshotDao(): CharacterSnapshotDao
    abstract fun chapterRevisionDao(): ChapterRevisionDao
    abstract fun generationJobDao(): GenerationJobDao
    abstract fun promptSnapshotDao(): PromptSnapshotDao
    abstract fun llmCallDao(): LlmCallDao
    abstract fun qualityRunDao(): QualityRunDao
}
