package com.debanshu777.caraml.core.download

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import org.koin.mp.KoinPlatform
import java.util.concurrent.TimeUnit

private const val CHANNEL_ID = "model_downloads"
private const val KEY_BATCH_ID = "batch_id"
private const val ACTION_PAUSE = "com.debanshu777.caraml.download.PAUSE"
private const val ACTION_RESUME = "com.debanshu777.caraml.download.RESUME"
private const val ACTION_CANCEL = "com.debanshu777.caraml.download.CANCEL"
private val BATCH_ID = Regex("^[a-f0-9]{64}$")

class AndroidDownloadScheduler(private val context: Context) : PlatformDownloadScheduler {
    private val workManager get() = WorkManager.getInstance(context)

    override suspend fun enqueue(batchId: String) {
        require(BATCH_ID.matches(batchId))
        if (Build.VERSION.SDK_INT >= 34 && enqueueUserInitiatedJob(batchId)) return
        enqueueWorker(batchId)
    }

    private fun enqueueWorker(batchId: String) {
        val request = OneTimeWorkRequestBuilder<AndroidDownloadWorker>()
            .setInputData(Data.Builder().putString(KEY_BATCH_ID, batchId).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(workName(batchId))
            .build()
        workManager.enqueueUniqueWork(workName(batchId), ExistingWorkPolicy.KEEP, request)
    }

    override suspend fun pause(batchId: String) = cancelWork(batchId)
    override suspend fun cancel(batchId: String) = cancelWork(batchId)
    override suspend fun reconcile(liveBatchIds: Set<String>) = Unit

    private fun cancelWork(batchId: String) {
        require(BATCH_ID.matches(batchId))
        if (Build.VERSION.SDK_INT >= 34) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            scheduler.allPendingJobs
                .firstOrNull { it.service.className == AndroidUidtDownloadService::class.java.name && it.extras.getString(KEY_BATCH_ID) == batchId }
                ?.let { scheduler.cancel(it.id) }
        }
        workManager.cancelUniqueWork(workName(batchId))
    }

    private fun enqueueUserInitiatedJob(batchId: String): Boolean {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val used = scheduler.allPendingJobs.associateBy { it.id }
        val existing = used.values.firstOrNull {
            it.service.className == AndroidUidtDownloadService::class.java.name && it.extras.getString(KEY_BATCH_ID) == batchId
        }
        val resolvedId = existing?.id ?: (0..31).asSequence()
            .map { offset -> (jobId(batchId) + offset) and Int.MAX_VALUE }
            .firstOrNull { it !in used }
            ?: return false
        val info = JobInfo.Builder(resolvedId, ComponentName(context, AndroidUidtDownloadService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setRequiresStorageNotLow(true)
            .setPersisted(true)
            .setEstimatedNetworkBytes(
                JobInfo.NETWORK_BYTES_UNKNOWN.toLong(),
                JobInfo.NETWORK_BYTES_UNKNOWN.toLong(),
            )
            .setExtras(android.os.PersistableBundle().apply { putString(KEY_BATCH_ID, batchId) })
            .build()
        return scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
    }

    private fun workName(batchId: String) = "caraml-download-$batchId"

    private fun jobId(batchId: String): Int = batchId.take(8).toLong(16).toInt() and Int.MAX_VALUE
}

class AndroidUidtDownloadService : JobService() {
    private val jobs = mutableMapOf<Int, Pair<CoroutineScope, String>>()

    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val batchId = params.extras.getString(KEY_BATCH_ID)?.takeIf(BATCH_ID::matches) ?: return false
        val notifications = AndroidDownloadNotifications(applicationContext)
        setNotification(
            params,
            params.jobId,
            notifications.notification(batchId, "Preparing download", 0L, 0L, true),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        jobs[params.jobId] = scope to batchId
        scope.launch {
            val result = KoinPlatform.getKoin().get<DownloadBatchRunner>().run(batchId) { snapshot ->
                setNotification(
                    params,
                    params.jobId,
                    notifications.notification(
                        batchId,
                        snapshot.displayName,
                        snapshot.bytesReceived,
                        snapshot.expectedBytes,
                        snapshot.expectedBytes <= 0L,
                    ),
                    JOB_END_NOTIFICATION_POLICY_REMOVE,
                )
            }
            jobs.remove(params.jobId)
            jobFinished(params, result is DownloadRunResult.Retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.first?.cancel()
        return true
    }

    override fun onDestroy() {
        jobs.values.forEach { it.first.cancel() }
        jobs.clear()
        super.onDestroy()
    }
}

class AndroidDownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val notifications = AndroidDownloadNotifications(appContext)

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val batchId = requireBatchId()
        return notifications.foreground(batchId, "Preparing download", 0L, 0L, true)
    }

    override suspend fun doWork(): Result {
        val batchId = runCatching(::requireBatchId).getOrNull() ?: return Result.failure()
        setForeground(getForegroundInfo())
        val runner = KoinPlatform.getKoin().get<DownloadBatchRunner>()
        return when (runner.run(batchId) { snapshot ->
            notifications.notify(
                snapshot.batchId,
                snapshot.displayName,
                snapshot.bytesReceived,
                snapshot.expectedBytes,
                snapshot.expectedBytes <= 0L,
            )
        }) {
            DownloadRunResult.Completed,
            DownloadRunResult.Cancelled,
            DownloadRunResult.Paused,
            -> Result.success()
            is DownloadRunResult.Retry -> Result.retry()
            is DownloadRunResult.Failed -> Result.failure()
        }
    }

    private fun requireBatchId(): String = requireNotNull(inputData.getString(KEY_BATCH_ID)).also {
        require(BATCH_ID.matches(it))
    }
}

private class AndroidDownloadNotifications(private val context: Context) {
    init {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun foreground(batchId: String, title: String, received: Long, total: Long, indeterminate: Boolean) =
        ForegroundInfo(notificationId(batchId), notification(batchId, title, received, total, indeterminate))

    fun notification(batchId: String, title: String, received: Long, total: Long, indeterminate: Boolean) =
        build(batchId, title, received, total, indeterminate)

    fun notify(batchId: String, title: String, received: Long, total: Long, indeterminate: Boolean) {
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(
                notificationId(batchId),
                build(batchId, title, received, total, indeterminate),
            )
        }
    }

    fun paused(batchId: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Model download paused")
            .setContentText("Tap Resume to continue")
            .setOngoing(false)
            .addAction(0, "Resume", action(batchId, ACTION_RESUME, 3))
            .addAction(0, "Cancel", action(batchId, ACTION_CANCEL, 2))
            .build()
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) NotificationManagerCompat.from(context).notify(notificationId(batchId), notification)
    }

    fun cancel(batchId: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(batchId))
    }

    private fun build(batchId: String, title: String, received: Long, total: Long, indeterminate: Boolean) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title.take(512))
            .setContentText(if (total > 0L) "${received / 1_048_576} MB of ${total / 1_048_576} MB" else "Downloading model")
            .setProgress(100, if (total > 0L) ((received * 100L) / total).toInt().coerceIn(0, 100) else 0, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Pause", action(batchId, ACTION_PAUSE, 1))
            .addAction(0, "Cancel", action(batchId, ACTION_CANCEL, 2))
            .build()

    private fun action(batchId: String, action: String, suffix: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        notificationId(batchId) * 10 + suffix,
        Intent(context, AndroidDownloadActionReceiver::class.java).setAction(action).putExtra(KEY_BATCH_ID, batchId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notificationId(batchId: String): Int = batchId.take(8).toLong(16).toInt() and Int.MAX_VALUE
}

class AndroidDownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val batchId = intent.getStringExtra(KEY_BATCH_ID)?.takeIf(BATCH_ID::matches) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val coordinator = KoinPlatform.getKoin().get<DownloadCoordinator>()
                val notifications = AndroidDownloadNotifications(context.applicationContext)
                when (intent.action) {
                    ACTION_PAUSE -> {
                        coordinator.pause(batchId)
                        notifications.paused(batchId)
                    }
                    ACTION_RESUME -> coordinator.resume(batchId)
                    ACTION_CANCEL -> {
                        coordinator.cancel(batchId)
                        notifications.cancel(batchId)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

class AndroidDownloadNotificationPermissionController : DownloadNotificationPermissionController {
    @Volatile private var request: (() -> Unit)? = null
    fun attach(requestPermission: () -> Unit) { request = requestPermission }
    fun detach() { request = null }
    override fun requestIfNeeded() { request?.invoke() }
}
