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
        },
        // v3：补三处缺失索引，并把子表挂上真外键（ON DELETE CASCADE）
        object : Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 0) 先扫掉历史孤儿：以前「从此章重生成」删正文不删质检行，
                //    这些行指向已经不存在的 chapter_revisions，谁都读不到，
                //    留着还让 backfillUsageEstimates 找不到任务去瞎编 token 填账本。
                //    必须在重建 quality_runs（挂外键）之前做。
                db.execSQL("DELETE FROM quality_runs WHERE chapterRevisionId NOT IN (SELECT id FROM chapter_revisions)")

                // 1) 账本按时间过滤/排序，全表只增不减，没有索引就是每次开页都全扫 + 排序
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_llm_calls_createdAt` ON `llm_calls` (`createdAt`)")

                // 2) SQLite 不支持 ALTER TABLE ADD CONSTRAINT，要加外键只能整表重建：
                //    建临时表 → 拷数据 → 删旧表 → 改名 → 按 v3 定义重建索引
                //    （DROP TABLE 会连带删掉旧表上的索引，所以索引必须放在改名之后重建）。
                //    迁移跑在 SQLiteOpenHelper 自己的事务里，此时 foreign_keys 还没被 Room 打开
                //    （Room 在 onOpen 才 PRAGMA foreign_keys = ON），所以 DROP TABLE 隐含的
                //    DELETE 不会触发 ON DELETE CASCADE 把子表数据误删。
                rebuildOutlineVersions(db)
                rebuildGenerationJobs(db)
                rebuildChapterRevisions(db)
                rebuildQualityRuns(db)
            }

            private fun rebuildOutlineVersions(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `temp_outline_versions` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `version` INTEGER NOT NULL, `chaptersJson` TEXT NOT NULL, `diffSummary` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("INSERT INTO `temp_outline_versions` (`id`, `projectId`, `version`, `chaptersJson`, `diffSummary`, `createdAt`) SELECT `id`, `projectId`, `version`, `chaptersJson`, `diffSummary`, `createdAt` FROM `outline_versions`")
                db.execSQL("DROP TABLE `outline_versions`")
                db.execSQL("ALTER TABLE `temp_outline_versions` RENAME TO `outline_versions`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_outline_versions_projectId_version` ON `outline_versions` (`projectId`, `version`)")
            }

            private fun rebuildGenerationJobs(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `temp_generation_jobs` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `targetId` TEXT, `purpose` TEXT NOT NULL, `status` TEXT NOT NULL, `clientRequestId` TEXT NOT NULL, `attempt` INTEGER NOT NULL, `partialContent` TEXT NOT NULL, `promptSnapshotId` TEXT NOT NULL, `lastCheckpointAt` INTEGER, `errorType` TEXT, `errorMessage` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("INSERT INTO `temp_generation_jobs` (`id`, `projectId`, `targetId`, `purpose`, `status`, `clientRequestId`, `attempt`, `partialContent`, `promptSnapshotId`, `lastCheckpointAt`, `errorType`, `errorMessage`, `createdAt`, `updatedAt`) SELECT `id`, `projectId`, `targetId`, `purpose`, `status`, `clientRequestId`, `attempt`, `partialContent`, `promptSnapshotId`, `lastCheckpointAt`, `errorType`, `errorMessage`, `createdAt`, `updatedAt` FROM `generation_jobs`")
                db.execSQL("DROP TABLE `generation_jobs`")
                db.execSQL("ALTER TABLE `temp_generation_jobs` RENAME TO `generation_jobs`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_generation_jobs_projectId_purpose_targetId` ON `generation_jobs` (`projectId`, `purpose`, `targetId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_generation_jobs_clientRequestId` ON `generation_jobs` (`clientRequestId`)")
            }

            private fun rebuildChapterRevisions(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `temp_chapter_revisions` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `outlineItemId` TEXT NOT NULL, `outlineVersionId` TEXT NOT NULL, `revision` INTEGER NOT NULL, `title` TEXT NOT NULL, `content` TEXT NOT NULL, `summary` TEXT, `status` TEXT NOT NULL, `promptSnapshotId` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("INSERT INTO `temp_chapter_revisions` (`id`, `projectId`, `outlineItemId`, `outlineVersionId`, `revision`, `title`, `content`, `summary`, `status`, `promptSnapshotId`, `createdAt`) SELECT `id`, `projectId`, `outlineItemId`, `outlineVersionId`, `revision`, `title`, `content`, `summary`, `status`, `promptSnapshotId`, `createdAt` FROM `chapter_revisions`")
                db.execSQL("DROP TABLE `chapter_revisions`")
                db.execSQL("ALTER TABLE `temp_chapter_revisions` RENAME TO `chapter_revisions`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_chapter_revisions_projectId_outlineItemId_revision` ON `chapter_revisions` (`projectId`, `outlineItemId`, `revision`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapter_revisions_projectId_outlineVersionId` ON `chapter_revisions` (`projectId`, `outlineVersionId`)")
                // 启动回填 token 估算时按 promptSnapshotId 取正文长度，缺索引就是逐次全表扫描全书
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapter_revisions_promptSnapshotId` ON `chapter_revisions` (`promptSnapshotId`)")
            }

            /** 质检记录跟着正文修订一起走：以前删正文不删质检行，那些行永远读不到还挡着账本回填 */
            private fun rebuildQualityRuns(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `temp_quality_runs` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `chapterRevisionId` TEXT NOT NULL, `reportJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`chapterRevisionId`) REFERENCES `chapter_revisions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("INSERT INTO `temp_quality_runs` (`id`, `projectId`, `chapterRevisionId`, `reportJson`, `createdAt`) SELECT `id`, `projectId`, `chapterRevisionId`, `reportJson`, `createdAt` FROM `quality_runs`")
                db.execSQL("DROP TABLE `quality_runs`")
                db.execSQL("ALTER TABLE `temp_quality_runs` RENAME TO `quality_runs`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_quality_runs_projectId_chapterRevisionId` ON `quality_runs` (`projectId`, `chapterRevisionId`)")
                // 级联删除要按 chapterRevisionId 反查子行，(projectId, chapterRevisionId) 的首列用不上
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_quality_runs_chapterRevisionId` ON `quality_runs` (`chapterRevisionId`)")
            }
        }
    )
}
