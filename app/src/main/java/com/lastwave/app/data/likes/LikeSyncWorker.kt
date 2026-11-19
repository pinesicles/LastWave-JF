package com.lastwave.app.data.likes

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LikeMirrorEntryPoint {
    fun mirror(): LikeMirror
}

/** Runs a likes sync as background work, with a foreground notification so Android keeps it alive. */
class LikeSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private fun info(text: String, progress: Pair<Int, Int>? = null): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.createNotificationChannel(
            android.app.NotificationChannel("like_sync", "Likes sync", android.app.NotificationManager.IMPORTANCE_LOW),
        )
        val n = androidx.core.app.NotificationCompat.Builder(applicationContext, "like_sync")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Syncing likes")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { if (progress != null) setProgress(progress.second, progress.first, false) }
            .build()
        return ForegroundInfo(4712, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = info("Starting…")

    override suspend fun doWork(): Result {
        val mirror = EntryPointAccessors.fromApplication(applicationContext, LikeMirrorEntryPoint::class.java).mirror()
        runCatching { setForeground(info("Reading your likes…")) }
        return try {
            mirror.runJob(SyncMode.decode(inputData.getString("mode"))) { done, total ->
                if (done % 10 == 0 || done == total) {
                    runCatching { setForeground(info("$done of $total", done to total)) }
                }
            }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
