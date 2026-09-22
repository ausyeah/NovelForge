package com.novelforge.app.data.local

import androidx.room.migration.Migration

object DatabaseMigrations {
    val all: Array<Migration> = arrayOf(
        // v2：账本补「缓存命中 / 推理 token」两列
        object : Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE llm_calls ADD COLUMN cachedInputTokens INTEGER")
                db.execSQL("ALTER TABLE llm_calls ADD COLUMN reasoningTokens INTEGER")
            }
        }
    )
}
