package com.novelforge.app.infrastructure.jobs

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.novelforge.app.NovelForgeApplication

interface GenerationWorkerDependencies {
    suspend fun runGeneration(jobId: String, runAttemptCount: Int): GenerationExecutionResult
    /** 通知栏标题用的一句人话（"正在写《xx》正文"）；查不到返回 null */
    suspend fun describeJob(jobId: String): String?
}

class GenerationWorkerFactory(
    private val deps: GenerationWorkerDependencies
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? =
        // 宽松匹配：历史 workSpec 里可能存的是简名/旧名，本应用只有这一种 worker
        if (workerClassName.endsWith("GenerationWorker")) {
            GenerationWorker(appContext, workerParameters, deps)
        } else {
            null
        }
}

sealed interface GenerationExecutionResult {
    data object Success : GenerationExecutionResult
    data object Retry : GenerationExecutionResult
    data object Failure : GenerationExecutionResult
}

interface GenerationWorkerDependenciesProvider {
    fun generationWorkerDependencies(): GenerationWorkerDependencies
}

class GenerationWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val injectedDeps: GenerationWorkerDependencies? = null
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
            val deps = injectedDeps
                ?: (applicationContext as? GenerationWorkerDependenciesProvider)
                    ?.generationWorkerDependencies()
                ?: return Result.failure()
            // 前台服务常驻通知：用户划掉应用时进程不被回收，长时间生成不中断。
            // 必须在做任何耗时工作之前调用；部分系统后台禁止前台服务，失败时退化为普通后台任务。
            currentLabel = runCatching { deps.describeJob(jobId) }.getOrNull()
            runCatching { setForeground(createForegroundInfo()) }
            when (deps.runGeneration(jobId, runAttemptCount)) {
                GenerationExecutionResult.Success -> Result.success()
                GenerationExecutionResult.Retry -> Result.retry()
                GenerationExecutionResult.Failure -> Result.failure()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val context = applicationContext
        val notification: Notification = NotificationCompat.Builder(context, NovelForgeApplication.GENERATION_CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("NovelForge 正在生成")
            .setContentText(currentLabel ?: "正在生成大纲与正文，可切换到其他应用，完成后自动保存")
            .setOngoing(true)
            .build()
        // 必须三参并声明与清单一致的 dataSync 类型，否则 targetSdk 35 直接崩溃
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    @Volatile
    private var currentLabel: String? = null

    companion object {
        const val KEY_JOB_ID = "generation_job_id"
        private const val NOTIFICATION_ID = 47
    }
}
