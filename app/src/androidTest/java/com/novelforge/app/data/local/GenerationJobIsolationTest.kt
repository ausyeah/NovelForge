package com.novelforge.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novelforge.app.domain.model.GenerationJob
import com.novelforge.app.domain.model.GenerationJobStatus
import com.novelforge.app.domain.model.GenerationPurpose
import com.novelforge.app.data.repository.RoomGenerationRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 「被删掉的任务不会被在飞的 worker 复活」—— 走**真的 Room**。
 *
 * 为什么必须是仪器测试：JVM 单测构造不了 `AppDatabase`（Room 生成的实现要
 * `SupportSQLiteOpenHelper`），也构造不了 `RoomGenerationRepository`（它吃一个
 * `AppDatabase`）。所以之前只有 `CrossBookIsolationTest` 在测这件事，而它测的是
 * 一个四行的假实现，假实现的方法体恰好就是被测的那个条件
 * （`if (!jobs.containsKey(id)) return false`）—— **把生产实现整个换成
 * `dao.upsert()`，CI 照样全绿。** 那不是覆盖，那是自证。
 *
 * 而这个性质完全取决于一行 SQL 的语义：Room 的 `upsert` 是
 * `OnConflictStrategy.REPLACE`，对**不存在的行**执行时等价于 INSERT，
 * 于是被删掉的任务会被在飞的 worker 写回来，重新混进新一轮的目录里。
 * 仓库里没有 Robolectric、没有真机，所以这道防线此前完全没有被验证过。
 *
 * 需要设备或模拟器：`.\gradlew.bat :app:connectedDebugAndroidTest`
 * `room-testing` 已在 `androidTestImplementation` 里，不需要新依赖。
 */
@RunWith(AndroidJUnit4::class)
class GenerationJobIsolationTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RoomGenerationRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = RoomGenerationRepository(db.generationJobDao(), db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun job() = GenerationJob(
        id = UUID.randomUUID().toString(),
        projectId = "book-A",
        targetId = null,
        purpose = GenerationPurpose.OUTLINE,
        status = GenerationJobStatus.RUNNING,
        clientRequestId = "req-1",
        attempt = 1,
        partialContent = "",
        promptSnapshotId = "snap-1",
        createdAt = 1_000L,
        updatedAt = 1_000L
    )

    @Test
    fun aDeletedJobIsNeverResurrected() = runBlocking {
        val job = job()
        repository.createJob(job)
        // worker 正常推进一次
        assertTrue(repository.updateJobIfExists(job.copy(status = GenerationJobStatus.RUNNING)))

        // 另一条路径把这个任务删了（queueOutline 清目录时会这么做）
        repository.deleteAllJobs("book-A")
        assertNull(db.generationJobDao().findById(job.id))

        // 在飞的 worker 此刻才回来写结果 —— 必须是空操作，不能变回 INSERT
        assertFalse(
            "UPDATE ... WHERE id=:id 竟然插进了一行：被删掉的任务会复活",
            repository.updateJobIfExists(job.copy(status = GenerationJobStatus.COMPLETED))
        )
        assertNull("被删掉的任务复活了", db.generationJobDao().findById(job.id))
    }

    /**
     * 反向对照：把 `updateJobIfExists` 的**语义前提**钉成可执行的。
     *
     * 整套修复都建立在"upsert 对不存在的行会 INSERT"这个 Room 行为上。
     * 哪天 Room 改了这一点（或者有人把 upsert 换成别的东西），上面那条测试
     * 仍然是绿的，但理由变了。这条把对照也写下来。
     */
    @Test
    fun plainUpdateJobDoesResurrect_byDesign() = runBlocking {
        val job = job()
        repository.createJob(job)
        repository.deleteAllJobs("book-A")
        assertNull(db.generationJobDao().findById(job.id))

        repository.updateJob(job.copy(status = GenerationJobStatus.COMPLETED))

        val found = db.generationJobDao().findById(job.id)
        assertNotNull("updateJob 走 upsert(REPLACE)，对缺失行会 INSERT —— 这正是复活的原因", found)
        assertEquals(GenerationJobStatus.COMPLETED.name, found!!.status)
    }

    @Test
    fun updateJobIfExistsStillWorksWhenTheRowIsThere() = runBlocking {
        val job = job()
        repository.createJob(job)
        assertTrue(repository.updateJobIfExists(job.copy(status = GenerationJobStatus.COMPLETED)))
        // DAO 层返回的是字符串化的 status，比的是名字不是枚举
        assertEquals(
            GenerationJobStatus.COMPLETED.name,
            db.generationJobDao().findById(job.id)?.status
        )
    }
}
