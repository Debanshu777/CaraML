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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import com.debanshu777.caraml.core.di.DownloadRuntimeScope
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.mp.KoinPlatform
import java.util.concurrent.TimeUnit

private const val CHANNEL_ID = "model_downloads"
private const val KEY_BATCH_ID = "batch_id"
private const val KEY_UIDT_GENERATION = "uidt_generation"
private const val UIDT_STOP_MARKERS = "caraml_uidt_stop_markers"
private const val ACTION_PAUSE = "com.debanshu777.caraml.download.PAUSE"
private const val ACTION_RESUME = "com.debanshu777.caraml.download.RESUME"
private const val ACTION_CANCEL = "com.debanshu777.caraml.download.CANCEL"
private val BATCH_ID = Regex("^[a-f0-9]{64}$")

internal interface AndroidUidtBackend {
    fun hasOwner(batchId: String): Boolean
    suspend fun schedule(batchId: String): Boolean
    fun cancel(batchId: String)
    fun reconcile(liveBatchIds: Set<String>)
}

internal interface AndroidWorkBackend {
    suspend fun isActive(batchId: String): Boolean
    fun enqueue(batchId: String)
    suspend fun cancel(batchId: String)
    fun reconcile(liveBatchIds: Set<String>)
}

internal interface AndroidPlatformTaskStore {
    suspend fun bind(batchId: String, generation: String): Boolean
    suspend fun pause(batchId: String, generation: String): Boolean
}

internal interface AndroidUidtStopMarkerStore {
    fun record(batchId: String, generation: String): Boolean
    fun generation(batchId: String): String?
    fun clear(batchId: String, generation: String): Boolean
}

private object NoOpAndroidPlatformTaskStore : AndroidPlatformTaskStore {
    override suspend fun bind(batchId: String, generation: String) = true
    override suspend fun pause(batchId: String, generation: String) = false
}

private object NoOpAndroidUidtStopMarkerStore : AndroidUidtStopMarkerStore {
    override fun record(batchId: String, generation: String) = false
    override fun generation(batchId: String): String? = null
    override fun clear(batchId: String, generation: String) = false
}

internal class AndroidDownloadOwnershipController(
    private val apiLevel: Int,
    private val uidt: AndroidUidtBackend,
    private val work: AndroidWorkBackend,
    private val platformTasks: AndroidPlatformTaskStore = NoOpAndroidPlatformTaskStore,
    private val stopMarkers: AndroidUidtStopMarkerStore = NoOpAndroidUidtStopMarkerStore,
) {
    private val ownershipMutex = Mutex()

    suspend fun enqueue(batchId: String) = ownershipMutex.withLock {
        require(BATCH_ID.matches(batchId))
        if (apiLevel < 34) {
            work.enqueue(batchId)
            return@withLock
        }
        if (consumeUserStopMarker(batchId)) return@withLock
        if (uidt.hasOwner(batchId) || work.isActive(batchId)) return@withLock
        if (uidt.schedule(batchId)) return@withLock
        // schedule() may report failure after the framework accepted or restored
        // the exact job. Re-query before creating the fallback owner.
        if (uidt.hasOwner(batchId) || work.isActive(batchId)) return@withLock
        work.enqueue(batchId)
    }

    suspend fun isActive(batchId: String): Boolean = ownershipMutex.withLock {
        require(BATCH_ID.matches(batchId))
        if (apiLevel >= 34) uidt.hasOwner(batchId) || work.isActive(batchId)
        else work.isActive(batchId)
    }

    suspend fun cancel(batchId: String) = ownershipMutex.withLock {
        require(BATCH_ID.matches(batchId))
        if (apiLevel >= 34) uidt.cancel(batchId)
        work.cancel(batchId)
    }

    suspend fun reconcile(liveBatchIds: Set<String>) = ownershipMutex.withLock {
        if (apiLevel >= 34) {
            liveBatchIds.forEach { consumeUserStopMarker(it) }
            uidt.reconcile(liveBatchIds)
            liveBatchIds.forEach { batchId ->
                if (uidt.hasOwner(batchId) && work.isActive(batchId)) {
                    work.cancel(batchId)
                }
            }
        }
        work.reconcile(liveBatchIds)
    }

    fun recordUserStop(batchId: String, generation: String): Boolean {
        require(BATCH_ID.matches(batchId))
        require(generation.isValidGeneration())
        return stopMarkers.record(batchId, generation)
    }

    suspend fun persistUserStop(batchId: String, generation: String) = ownershipMutex.withLock {
        require(BATCH_ID.matches(batchId))
        require(generation.isValidGeneration())
        pauseGeneration(batchId, generation)
    }

    private suspend fun consumeUserStopMarker(batchId: String, expectedGeneration: String? = null): Boolean {
        val generation = stopMarkers.generation(batchId) ?: return false
        if (!generation.isValidGeneration()) {
            stopMarkers.clear(batchId, generation)
            return false
        }
        if (expectedGeneration != null && generation != expectedGeneration) return false
        return pauseGeneration(batchId, generation)
    }

    private suspend fun pauseGeneration(batchId: String, generation: String): Boolean {
        val paused = platformTasks.pause(batchId, generation)
        if (paused) {
            uidt.cancel(batchId)
            work.cancel(batchId)
        }
        stopMarkers.clear(batchId, generation)
        return paused
    }
}

class AndroidDownloadScheduler internal constructor(
    private val controller: AndroidDownloadOwnershipController,
) : PlatformDownloadScheduler {
    constructor(context: Context, store: DownloadTaskStore) : this(createController(context, store))

    override suspend fun enqueue(batchId: String) {
        controller.enqueue(batchId)
    }

    override suspend fun pause(batchId: String) = controller.cancel(batchId)
    override suspend fun cancel(batchId: String) = controller.cancel(batchId)
    override suspend fun reconcile(liveBatchIds: Set<String>) = controller.reconcile(liveBatchIds)
    override suspend fun isActive(batchId: String): Boolean = controller.isActive(batchId)
    override suspend fun orphanedRunningDisposition(batchId: String): OrphanedDownloadDisposition =
        if (Build.VERSION.SDK_INT >= 34) OrphanedDownloadDisposition.PAUSE else OrphanedDownloadDisposition.RETRY

    internal fun recordUserStop(batchId: String, generation: String): Boolean =
        controller.recordUserStop(batchId, generation)

    internal suspend fun persistUserStop(batchId: String, generation: String): Boolean =
        controller.persistUserStop(batchId, generation)

    private companion object {
        fun createController(context: Context, store: DownloadTaskStore): AndroidDownloadOwnershipController {
            val appContext = context.applicationContext
            val platformTasks = StoreAndroidPlatformTaskStore(store)
            return AndroidDownloadOwnershipController(
                apiLevel = Build.VERSION.SDK_INT,
                uidt = FrameworkAndroidUidtBackend(appContext, platformTasks),
                work = FrameworkAndroidWorkBackend(appContext),
                platformTasks = platformTasks,
                stopMarkers = SharedPreferencesAndroidUidtStopMarkerStore(appContext),
            )
        }
    }
}

private class StoreAndroidPlatformTaskStore(private val store: DownloadTaskStore) : AndroidPlatformTaskStore {
    override suspend fun bind(batchId: String, generation: String): Boolean =
        store.bindPlatformTask(batchId, generation, System.currentTimeMillis())

    override suspend fun pause(batchId: String, generation: String): Boolean =
        store.pausePlatformTask(batchId, generation, System.currentTimeMillis())
}

private class SharedPreferencesAndroidUidtStopMarkerStore(context: Context) : AndroidUidtStopMarkerStore {
    private val preferences = context.getSharedPreferences(UIDT_STOP_MARKERS, Context.MODE_PRIVATE)

    @Synchronized
    override fun record(batchId: String, generation: String): Boolean =
        preferences.edit().putString(batchId, generation).commit()

    @Synchronized
    override fun generation(batchId: String): String? = preferences.getString(batchId, null)

    @Synchronized
    override fun clear(batchId: String, generation: String): Boolean {
        if (preferences.getString(batchId, null) != generation) return false
        return preferences.edit().remove(batchId).commit()
    }
}

private class FrameworkAndroidUidtBackend(
    context: Context,
    private val platformTasks: AndroidPlatformTaskStore,
) : AndroidUidtBackend {
    private val appContext = context.applicationContext
    private val scheduler = appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

    override fun hasOwner(batchId: String): Boolean =
        AndroidUidtRuntimeOwners.registry.hasOwner(batchId) || scheduler.allPendingJobs.any { it.isUidtFor(batchId) }

    override fun cancel(batchId: String) {
        scheduler.allPendingJobs.filter { it.isUidtFor(batchId) }.forEach { scheduler.cancel(it.id) }
    }

    override fun reconcile(liveBatchIds: Set<String>) {
        scheduler.allPendingJobs
            .filter { it.service.className == AndroidUidtDownloadService::class.java.name }
            .filter { job ->
                val batchId = job.extras.getString(KEY_BATCH_ID)
                batchId !in liveBatchIds || !job.isUidtFor(requireNotNull(batchId))
            }
            .forEach { scheduler.cancel(it.id) }
    }

    override suspend fun schedule(batchId: String): Boolean {
        val generation = UUID.randomUUID().toString()
        if (!platformTasks.bind(batchId, generation)) return false
        val used = scheduler.allPendingJobs.associateBy { it.id }
        val resolvedId = (0..31).asSequence()
            .map { offset -> (jobId(batchId) + offset) and Int.MAX_VALUE }
            .firstOrNull { it !in used }
            ?: return false
        val info = JobInfo.Builder(resolvedId, ComponentName(appContext, AndroidUidtDownloadService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setRequiresStorageNotLow(true)
            .setPersisted(true)
            .setEstimatedNetworkBytes(
                JobInfo.NETWORK_BYTES_UNKNOWN.toLong(),
                JobInfo.NETWORK_BYTES_UNKNOWN.toLong(),
            )
            .setExtras(
                android.os.PersistableBundle().apply {
                    putString(KEY_BATCH_ID, batchId)
                    putString(KEY_UIDT_GENERATION, generation)
                },
            )
            .build()
        return scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
    }

    private fun JobInfo.isUidtFor(batchId: String): Boolean =
        service.className == AndroidUidtDownloadService::class.java.name &&
            isValidUidtOwnerMetadata(
                expectedBatchId = batchId,
                actualBatchId = extras.getString(KEY_BATCH_ID),
                generation = extras.getString(KEY_UIDT_GENERATION),
            )

    private fun jobId(batchId: String): Int = batchId.take(8).toLong(16).toInt() and Int.MAX_VALUE
}

private class FrameworkAndroidWorkBackend(context: Context) : AndroidWorkBackend {
    private val appContext = context.applicationContext
    private val workManager get() = WorkManager.getInstance(appContext)

    override suspend fun isActive(batchId: String): Boolean =
        workManager.getWorkInfosForUniqueWorkFlow(workName(batchId)).first().any { it.state.isActiveOwner() }

    override fun enqueue(batchId: String) {
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

    override suspend fun cancel(batchId: String) {
        workManager.cancelUniqueWork(workName(batchId)).await()
    }

    override fun reconcile(liveBatchIds: Set<String>) = Unit

    private fun WorkInfo.State.isActiveOwner(): Boolean =
        this == WorkInfo.State.ENQUEUED || this == WorkInfo.State.RUNNING || this == WorkInfo.State.BLOCKED
}

private fun workName(batchId: String) = "caraml-download-$batchId"

internal class AndroidUidtOwnerToken(
    val jobId: Int,
    val batchId: String,
    val generation: String,
    val paramsIdentity: Any,
    val replaced: AndroidUidtOwnerToken?,
)

internal class AndroidUidtOwnerRegistry {
    private val owners = mutableMapOf<Int, AndroidUidtOwnerToken>()

    @Synchronized
    fun register(
        jobId: Int,
        batchId: String,
        paramsIdentity: Any,
        generation: String = "test-generation",
    ): AndroidUidtOwnerToken {
        val token = AndroidUidtOwnerToken(jobId, batchId, generation, paramsIdentity, owners[jobId])
        owners[jobId] = token
        return token
    }

    @Synchronized
    fun current(jobId: Int): AndroidUidtOwnerToken? = owners[jobId]

    @Synchronized
    fun hasOwner(batchId: String): Boolean = owners.values.any { it.batchId == batchId }

    @Synchronized
    fun stop(jobId: Int, paramsIdentity: Any): AndroidUidtOwnerToken? {
        val current = owners[jobId]?.takeIf { it.paramsIdentity === paramsIdentity } ?: return null
        owners.remove(jobId)
        return current
    }

    @Synchronized
    fun complete(token: AndroidUidtOwnerToken): Boolean {
        if (owners[token.jobId] !== token) return false
        owners.remove(token.jobId)
        return true
    }

    @Synchronized
    fun drain(): List<AndroidUidtOwnerToken> = owners.values.toList().also { owners.clear() }
}

private object AndroidUidtRuntimeOwners {
    val registry = AndroidUidtOwnerRegistry()
    val jobs = ConcurrentHashMap<AndroidUidtOwnerToken, Job>()
}

internal enum class AndroidUidtStopDecision { PAUSE, NO_RESCHEDULE, RETRY }

internal fun androidUidtStopDecision(stopReason: Int): AndroidUidtStopDecision = when (stopReason) {
    JobParameters.STOP_REASON_USER -> AndroidUidtStopDecision.PAUSE
    JobParameters.STOP_REASON_CANCELLED_BY_APP -> AndroidUidtStopDecision.NO_RESCHEDULE
    else -> AndroidUidtStopDecision.RETRY
}

class AndroidUidtDownloadService : JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val batchId = params.extras.getString(KEY_BATCH_ID)?.takeIf(BATCH_ID::matches) ?: return false
        val generation = params.extras.getString(KEY_UIDT_GENERATION)?.takeIf(String::isValidGeneration) ?: return false
        val token = AndroidUidtRuntimeOwners.registry.register(params.jobId, batchId, params, generation)
        token.replaced?.let { replaced -> AndroidUidtRuntimeOwners.jobs.remove(replaced)?.cancel() }
        val notifications = AndroidDownloadNotifications(applicationContext)
        setNotification(
            params,
            params.jobId,
            notifications.notification(batchId, "Preparing download", 0L, 0L, true),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
        val job = serviceScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val result = try {
                KoinPlatform.getKoin().get<DownloadRuntime>().awaitStartupReconciliation()
                KoinPlatform.getKoin().get<DownloadBatchRunner>().run(batchId) { snapshot ->
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DownloadRunResult.Retry(DownloadFailureCode.PLATFORM)
            }
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                AndroidUidtRuntimeOwners.jobs.remove(token)
                if (AndroidUidtRuntimeOwners.registry.complete(token)) {
                    jobFinished(params, result is DownloadRunResult.Retry)
                }
            }
        }
        AndroidUidtRuntimeOwners.jobs[token] = job
        job.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val token = AndroidUidtRuntimeOwners.registry.stop(params.jobId, params) ?: return false
        val job = AndroidUidtRuntimeOwners.jobs.remove(token)
        return when (androidUidtStopDecision(params.stopReason)) {
            AndroidUidtStopDecision.PAUSE -> {
                val scheduler = KoinPlatform.getKoin().get<AndroidDownloadScheduler>()
                val markerRecorded = scheduler.recordUserStop(token.batchId, token.generation)
                if (!markerRecorded) {
                    runBlocking(Dispatchers.IO) {
                        withTimeoutOrNull(1_000L) {
                            scheduler.persistUserStop(token.batchId, token.generation)
                        }
                    }
                }
                KoinPlatform.getKoin().get<DownloadRuntimeScope>().scope.launch {
                    try {
                        if (markerRecorded) scheduler.persistUserStop(token.batchId, token.generation)
                    } finally {
                        job?.cancel()
                    }
                }
                false
            }
            AndroidUidtStopDecision.NO_RESCHEDULE -> {
                job?.cancel()
                false
            }
            AndroidUidtStopDecision.RETRY -> {
                job?.cancel()
                true
            }
        }
    }

    override fun onDestroy() {
        AndroidUidtRuntimeOwners.registry.drain().forEach { token ->
            AndroidUidtRuntimeOwners.jobs.remove(token)?.cancel()
        }
        serviceScope.cancel()
        super.onDestroy()
    }
}

private fun String.isValidGeneration(): Boolean =
    length in 1..128 && all { it.isLetterOrDigit() || it == '-' || it == '_' }

internal fun isValidUidtOwnerMetadata(
    expectedBatchId: String,
    actualBatchId: String?,
    generation: String?,
): Boolean = BATCH_ID.matches(expectedBatchId) &&
    actualBatchId == expectedBatchId &&
    generation?.isValidGeneration() == true

class AndroidDownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val notifications = AndroidDownloadNotifications(appContext)

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val batchId = requireBatchId()
        return notifications.foreground(batchId, "Preparing download", 0L, 0L, true)
    }

    override suspend fun doWork(): Result {
        val batchId = runCatching(::requireBatchId).getOrNull() ?: return Result.failure()
        setForeground(getForegroundInfo())
        try {
            KoinPlatform.getKoin().get<DownloadRuntime>().awaitStartupReconciliation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Result.retry()
        }
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
