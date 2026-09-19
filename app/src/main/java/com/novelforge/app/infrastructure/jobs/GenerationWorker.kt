package com.novelforge.app.infrastructure.jobs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters

interface GenerationWorkerDependencies {
    suspend fun runGeneration(jobId: String, runAttemptCount: Int): GenerationExecutionResult
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
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        // 前台服务常驻通知：用户划掉应用时进程不被回收，长时间生成不中断。
        // 部分系统在后台禁止启动前台服务，失败时退化为普通后台任务。
        runCatching { setForeground(createForegroundInfo()) }
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val provider = applicationContext as? GenerationWorkerDependenciesProvider
            ?: return Result.failure()
        return when (
            provider.generationWorkerDependencies()
                .runGeneration(jobId, runAttemptCount)
        ) {
            GenerationExecutionResult.Success -> Result.success()
            GenerationExecutionResult.Retry -> Result.retry()
            GenerationExecutionResult.Failure -> Result.failure()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val context = applicationContext
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "小说生成任务",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("NovelForge 正在生成")
            .setContentText("AI 正在生成大纲与正文，可离开应用，完成后自动保存")
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

    companion object {
        const val KEY_JOB_ID = "generation_job_id"
        private const val CHANNEL_ID = "novelforge_generation"
        private const val NOTIFICATION_ID = 47
    }
}
