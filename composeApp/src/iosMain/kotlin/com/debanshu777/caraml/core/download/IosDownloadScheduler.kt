@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.debanshu777.caraml.core.download

import com.debanshu777.huggingfacemanager.download.ArtifactFileAccessException
import com.debanshu777.huggingfacemanager.download.ArtifactVerificationException
import com.debanshu777.huggingfacemanager.download.IosCompletedDownloadImporter
import com.debanshu777.huggingfacemanager.download.artifactDownloadUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSLock
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDownloadDelegateProtocol
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskDelegateProtocol
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.time.Clock

private const val BACKGROUND_SESSION_ID = "com.debanshu777.caraml.model-downloads"
private val taskDescriptionPattern = Regex("^[0-9a-f]{64}:[0-9a-f]{64}$")

/** iOS-native durable driver backed by one stable background URLSession. */
class IosDownloadScheduler(
    private val store: DownloadTaskStore,
    private val finalizer: BatchFinalizer,
    private val importer: IosCompletedDownloadImporter,
    private val resumeDataStore: IosResumeDataStore,
    private val completedFileStore: IosCompletedFileStore,
    private val scope: CoroutineScope,
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : PlatformDownloadScheduler {

    private var backgroundEventsCompletionHandler: (() -> Unit)? = null
    private val backgroundEventsLock = NSLock()
    private var backgroundEventsFinished = false
    private var pendingFinalizations = 0
    private val progressCheckpoints = mutableMapOf<String, Pair<Long, Long>>()
    private val progressCheckpointsLock = NSLock()
    private val delegate = IosDownloadDelegate(this)
    private val delegateQueue = NSOperationQueue().apply { maxConcurrentOperationCount = 1 }
    private val session: NSURLSession by lazy {
        val configuration = NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(
            BACKGROUND_SESSION_ID,
        ).apply {
            sessionSendsLaunchEvents = true
            discretionary = false
            waitsForConnectivity = true
            timeoutIntervalForResource = 7.0 * 24.0 * 60.0 * 60.0
        }
        NSURLSession.sessionWithConfiguration(configuration, delegate, delegateQueue)
    }

    override suspend fun enqueue(batchId: String) {
        enqueue(batchId, ignoredTaskId = null)
    }

    private suspend fun enqueue(batchId: String, ignoredTaskId: ULong?) {
        requireBatchId(batchId)
        if (activeTasks().any { it.taskIdentifier != ignoredTaskId && it.taskKey()?.first == batchId }) return
        val batch = store.getBatch(batchId) ?: return
        if (batch.userIntent != DownloadUserIntent.RUN) return
        val artifact = batch.artifacts.firstOrNull {
            it.state in setOf(
                DownloadArtifactState.QUEUED,
                DownloadArtifactState.FAILED_RETRYABLE,
                DownloadArtifactState.WAITING_FOR_NETWORK,
            )
        } ?: run {
            finishBatchIfReady(batchId)
            return
        }
        if (runCatching { importer.isPublished(artifact.request.metadata) }.getOrDefault(false)) {
            val owner = "ios-published-recovery"
            if (store.claim(artifact.artifactId, owner, nowEpochMs(), Long.MAX_VALUE)) {
                try {
                    store.updateProgress(
                        artifact.artifactId,
                        artifact.expectedBytes,
                        artifact.entityTag,
                        artifact.lastModified,
                        nowEpochMs(),
                    )
                    store.transitionArtifact(
                        artifact.artifactId,
                        DownloadArtifactState.VERIFYING,
                        null,
                        nowEpochMs(),
                    )
                } finally {
                    store.releaseLease(artifact.artifactId, owner, nowEpochMs())
                }
                enqueue(batchId)
            }
            return
        }
        completedFileStore.existingPath(artifact.artifactId)?.let { capturedPath ->
            scope.launch { recoverCapturedFile(batch.batchId, artifact.artifactId, capturedPath) }
            return
        }

        val task = resumeDataStore.read(artifact.artifactId)?.let(session::downloadTaskWithResumeData)
            ?: run {
                val url = NSURL.URLWithString(artifactDownloadUrl(artifact.request.metadata.artifact))
                    ?: throw IllegalArgumentException("Invalid artifact URL")
                session.downloadTaskWithRequest(NSMutableURLRequest.requestWithURL(url))
            }
        task.taskDescription = "${batch.batchId}:${artifact.artifactId}"
        val platformTaskId = task.taskIdentifier.toString()
        store.setPlatformTaskId(artifact.artifactId, platformTaskId, nowEpochMs())
        val claimed = store.claim(
            artifactId = artifact.artifactId,
            owner = "ios-urlsession-$platformTaskId",
            nowEpochMs = nowEpochMs(),
            expiresAtEpochMs = Long.MAX_VALUE,
        )
        if (!claimed) {
            task.cancel()
            store.setPlatformTaskId(artifact.artifactId, null, nowEpochMs())
            return
        }
        resumeDataStore.delete(artifact.artifactId)
        task.resume()
    }

    override suspend fun pause(batchId: String) {
        requireBatchId(batchId)
        activeTasks().filter { it.taskKey()?.first == batchId }.forEach { task ->
            val artifactId = task.taskKey()?.second ?: return@forEach
            suspendCancellableCoroutine { continuation ->
                task.cancelByProducingResumeData { data ->
                    if (data != null) runCatching { resumeDataStore.write(artifactId, data) }
                    if (continuation.isActive) continuation.resume(Unit)
                }
                continuation.invokeOnCancellation { task.cancel() }
            }
            store.setPlatformTaskId(artifactId, null, nowEpochMs())
        }
    }

    override suspend fun cancel(batchId: String) {
        requireBatchId(batchId)
        activeTasks().filter { it.taskKey()?.first == batchId }.forEach { task ->
            task.taskKey()?.second?.let(resumeDataStore::delete)
            task.cancel()
        }
        store.getBatch(batchId)?.artifacts?.forEach { artifact ->
            resumeDataStore.delete(artifact.artifactId)
            completedFileStore.delete(artifact.artifactId)
        }
    }

    override suspend fun reconcile(liveBatchIds: Set<String>) {
        liveBatchIds.forEach(::requireBatchId)
        activeTasks().forEach { task ->
            val key = task.taskKey()
            if (key == null || key.first !in liveBatchIds) {
                task.cancel()
            } else {
                store.setPlatformTaskId(key.second, task.taskIdentifier.toString(), nowEpochMs())
            }
        }
    }

    override suspend fun isActive(batchId: String): Boolean {
        requireBatchId(batchId)
        return activeTasks().any { it.taskKey()?.first == batchId }
    }

    fun handleBackgroundEvents(identifier: String, completionHandler: () -> Unit) {
        if (identifier != BACKGROUND_SESSION_ID) {
            completionHandler()
            return
        }
        backgroundEventsLock.lock()
        backgroundEventsCompletionHandler = completionHandler
        backgroundEventsFinished = false
        backgroundEventsLock.unlock()
        session
    }

    internal fun didWriteData(
        downloadTask: NSURLSessionDownloadTask,
        totalBytesWritten: Long,
        totalBytesExpectedToWrite: Long,
    ) {
        val artifactId = downloadTask.taskKey()?.second ?: return
        if (totalBytesWritten < 0L || totalBytesExpectedToWrite <= 0L) return
        val now = nowEpochMs()
        progressCheckpointsLock.lock()
        val shouldPersist = try {
            val previous = progressCheckpoints[artifactId]
            if (previous != null && totalBytesWritten - previous.first < 1024L * 1024L && now - previous.second < 500L) {
                false
            } else {
                progressCheckpoints[artifactId] = totalBytesWritten to now
                true
            }
        } finally {
            progressCheckpointsLock.unlock()
        }
        if (!shouldPersist) return
        scope.launch {
            val batchId = downloadTask.taskKey()?.first ?: return@launch
            val batch = store.getBatch(batchId) ?: return@launch
            val artifact = batch.artifacts.firstOrNull { it.artifactId == artifactId } ?: return@launch
            if (totalBytesWritten <= artifact.expectedBytes) {
                store.updateProgress(artifactId, totalBytesWritten, null, null, now)
            }
        }
    }

    internal fun didFinishDownloading(
        downloadTask: NSURLSessionDownloadTask,
        temporaryUrl: NSURL,
    ) {
        val key = downloadTask.taskKey() ?: run {
            downloadTask.cancel()
            return
        }
        val temporaryPath = temporaryUrl.path ?: return
        val response = downloadTask.response as? NSHTTPURLResponse
        val finalUrl = response?.URL?.absoluteString
        val status = response?.statusCode?.toInt()
        if (finalUrl == null || status == null || !importer.acceptsResponse(finalUrl, status)) {
            launchTracked {
                failArtifact(
                    key.second,
                    DownloadFailureCode.HTTP,
                    terminal = status == null || status in 400..499,
                )
            }
            return
        }
        val capturedPath = runCatching { completedFileStore.capture(key.second, temporaryPath) }
            .getOrElse {
                launchTracked { failArtifact(key.second, DownloadFailureCode.SECURE_PATH, terminal = true) }
                return
            }
        launchTracked tracked@{
            val batch = store.getBatch(key.first) ?: return@tracked
            val artifact = batch.artifacts.firstOrNull { it.artifactId == key.second } ?: return@tracked
            try {
                importer.import(
                    modelId = artifact.request.metadata.artifact.repositoryId,
                    path = artifact.request.metadata.artifact.relativePath,
                    metadata = artifact.request.metadata,
                    temporaryFilePath = capturedPath,
                    finalResponseUrl = finalUrl,
                    statusCode = status,
                )
                store.updateProgress(artifact.artifactId, artifact.expectedBytes, null, null, nowEpochMs())
                store.setPlatformTaskId(artifact.artifactId, null, nowEpochMs())
                store.transitionArtifact(
                    artifact.artifactId,
                    DownloadArtifactState.VERIFYING,
                    null,
                    nowEpochMs(),
                )
                progressCheckpointsLock.lock()
                try {
                    progressCheckpoints.remove(artifact.artifactId)
                } finally {
                    progressCheckpointsLock.unlock()
                }
                enqueue(batch.batchId, downloadTask.taskIdentifier)
            } catch (_: ArtifactVerificationException) {
                failArtifact(artifact.artifactId, DownloadFailureCode.INTEGRITY, terminal = true)
            } catch (_: ArtifactFileAccessException) {
                failArtifact(artifact.artifactId, DownloadFailureCode.SECURE_PATH, terminal = true)
            } catch (_: Exception) {
                failArtifact(artifact.artifactId, DownloadFailureCode.PLATFORM, terminal = false)
            } finally {
                completedFileStore.delete(artifact.artifactId)
            }
        }
    }

    private suspend fun recoverCapturedFile(batchId: String, artifactId: String, capturedPath: String) {
        val batch = store.getBatch(batchId) ?: return
        val artifact = batch.artifacts.firstOrNull { it.artifactId == artifactId } ?: return
        try {
            importer.import(
                modelId = artifact.request.metadata.artifact.repositoryId,
                path = artifact.request.metadata.artifact.relativePath,
                metadata = artifact.request.metadata,
                temporaryFilePath = capturedPath,
                finalResponseUrl = "https://huggingface.co",
                statusCode = 200,
            )
            store.updateProgress(artifactId, artifact.expectedBytes, null, null, nowEpochMs())
            store.setPlatformTaskId(artifactId, null, nowEpochMs())
            store.transitionArtifact(artifactId, DownloadArtifactState.VERIFYING, null, nowEpochMs())
            completedFileStore.delete(artifactId)
            enqueue(batchId)
        } catch (_: ArtifactVerificationException) {
            completedFileStore.delete(artifactId)
            failArtifact(artifactId, DownloadFailureCode.INTEGRITY, terminal = true)
        } catch (_: ArtifactFileAccessException) {
            completedFileStore.delete(artifactId)
            failArtifact(artifactId, DownloadFailureCode.SECURE_PATH, terminal = true)
        } catch (_: Exception) {
            failArtifact(artifactId, DownloadFailureCode.PLATFORM, terminal = false)
        }
    }

    internal fun didComplete(
        task: NSURLSessionTask,
        error: platform.Foundation.NSError?,
    ) {
        val key = (task as? NSURLSessionDownloadTask)?.taskKey() ?: return
        if (error == null) return
        launchTracked tracked@{
            val batch = store.getBatch(key.first) ?: return@tracked
            val artifact = batch.artifacts.firstOrNull { it.artifactId == key.second } ?: return@tracked
            store.setPlatformTaskId(artifact.artifactId, null, nowEpochMs())
            when (batch.userIntent) {
                DownloadUserIntent.PAUSE, DownloadUserIntent.CANCEL -> Unit
                DownloadUserIntent.RUN -> if (artifact.state == DownloadArtifactState.RUNNING) {
                    failArtifact(artifact.artifactId, DownloadFailureCode.NETWORK, terminal = false)
                }
            }
        }
    }

    internal fun didFinishBackgroundEvents() {
        backgroundEventsLock.lock()
        backgroundEventsFinished = true
        val completion = takeBackgroundCompletionIfReady()
        backgroundEventsLock.unlock()
        completion?.let { platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue(), it) }
    }

    private fun launchTracked(block: suspend () -> Unit) {
        backgroundEventsLock.lock()
        pendingFinalizations += 1
        backgroundEventsLock.unlock()
        scope.launch {
            try {
                block()
            } finally {
                backgroundEventsLock.lock()
                pendingFinalizations -= 1
                val completion = takeBackgroundCompletionIfReady()
                backgroundEventsLock.unlock()
                completion?.let { platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue(), it) }
            }
        }
    }

    /** Must be called with [backgroundEventsLock] held. */
    private fun takeBackgroundCompletionIfReady(): (() -> Unit)? {
        if (!backgroundEventsFinished || pendingFinalizations != 0) return null
        return backgroundEventsCompletionHandler.also { backgroundEventsCompletionHandler = null }
    }

    private suspend fun finishBatchIfReady(batchId: String) {
        val batch = store.getBatch(batchId) ?: return
        if (batch.artifacts.any { it.state !in setOf(DownloadArtifactState.VERIFYING, DownloadArtifactState.COMPLETED) }) {
            return
        }
        try {
            finalizer.finalize(batchId)
            batch.artifacts.filter { it.state == DownloadArtifactState.VERIFYING }.forEach { artifact ->
                store.transitionArtifact(artifact.artifactId, DownloadArtifactState.COMPLETED, null, nowEpochMs())
            }
            notifyCompleted(batchId)
        } catch (_: ArtifactVerificationException) {
            batch.artifacts.filter { it.state == DownloadArtifactState.VERIFYING }.forEach { artifact ->
                failArtifact(artifact.artifactId, DownloadFailureCode.INTEGRITY, terminal = true)
            }
        } catch (_: Exception) {
            batch.artifacts.filter { it.state == DownloadArtifactState.VERIFYING }.forEach { artifact ->
                failArtifact(artifact.artifactId, DownloadFailureCode.PLATFORM, terminal = false)
            }
        }
    }

    private suspend fun failArtifact(
        artifactId: String,
        code: DownloadFailureCode,
        terminal: Boolean,
    ) {
        store.setPlatformTaskId(artifactId, null, nowEpochMs())
        store.transitionArtifact(
            artifactId,
            if (terminal) DownloadArtifactState.FAILED_TERMINAL else DownloadArtifactState.FAILED_RETRYABLE,
            code,
            nowEpochMs(),
        )
    }

    private suspend fun activeTasks(): List<NSURLSessionDownloadTask> = suspendCancellableCoroutine { continuation ->
        session.getAllTasksWithCompletionHandler { tasks ->
            if (continuation.isActive) continuation.resume(tasks.orEmpty().filterIsInstance<NSURLSessionDownloadTask>())
        }
    }

    private fun NSURLSessionDownloadTask.taskKey(): Pair<String, String>? {
        val description = taskDescription ?: return null
        if (!taskDescriptionPattern.matches(description)) return null
        val (batchId, artifactId) = description.split(':', limit = 2)
        return batchId to artifactId
    }

    private fun requireBatchId(batchId: String) {
        require(batchId.length == 64 && batchId.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Invalid download batch"
        }
    }

    private fun notifyCompleted(batchId: String) {
        val content = UNMutableNotificationContent().apply {
            setTitle("Model download complete")
            setBody("Your model is ready to use.")
        }
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = "model-download-$batchId",
            content = content,
            trigger = null,
        )
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, null)
    }
}

private class IosDownloadDelegate(
    private val scheduler: IosDownloadScheduler,
) : NSObject(), NSURLSessionDownloadDelegateProtocol, NSURLSessionTaskDelegateProtocol {
    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didWriteData: Long,
        totalBytesWritten: Long,
        totalBytesExpectedToWrite: Long,
    ) = scheduler.didWriteData(downloadTask, totalBytesWritten, totalBytesExpectedToWrite)

    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didFinishDownloadingToURL: NSURL,
    ) = scheduler.didFinishDownloading(downloadTask, didFinishDownloadingToURL)

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: platform.Foundation.NSError?,
    ) = scheduler.didComplete(task, didCompleteWithError)

    override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) =
        scheduler.didFinishBackgroundEvents()
}

class IosResumeDataStore(
    private val directory: String,
) {
    init {
        FileSystem.SYSTEM.createDirectories(directory.toPath(normalize = true))
    }

    fun read(artifactId: String): NSData? = validatedPath(artifactId)
        .takeIf { FileSystem.SYSTEM.exists(it.toPath(normalize = true)) }
        ?.let(NSData::dataWithContentsOfFile)

    fun write(artifactId: String, data: NSData) {
        check(data.writeToFile(validatedPath(artifactId), atomically = true))
    }

    fun delete(artifactId: String) {
        runCatching { FileSystem.SYSTEM.delete(validatedPath(artifactId).toPath(normalize = true), mustExist = false) }
    }

    private fun validatedPath(artifactId: String): String {
        require(artifactId.length == 64 && artifactId.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Invalid download artifact"
        }
        return "${directory.trimEnd('/')}/$artifactId.resume"
    }
}

/** Captures URLSession's ephemeral completion file before the delegate callback returns. */
class IosCompletedFileStore(
    private val directory: String,
) {
    init {
        FileSystem.SYSTEM.createDirectories(directory.toPath(normalize = true))
    }

    fun capture(artifactId: String, temporaryPath: String): String {
        val target = validatedPath(artifactId).toPath(normalize = true)
        FileSystem.SYSTEM.delete(target, mustExist = false)
        FileSystem.SYSTEM.atomicMove(temporaryPath.toPath(normalize = true), target)
        return target.toString()
    }

    fun delete(artifactId: String) {
        runCatching { FileSystem.SYSTEM.delete(validatedPath(artifactId).toPath(normalize = true), mustExist = false) }
    }

    fun existingPath(artifactId: String): String? = validatedPath(artifactId)
        .takeIf { FileSystem.SYSTEM.exists(it.toPath(normalize = true)) }

    private fun validatedPath(artifactId: String): String {
        require(artifactId.length == 64 && artifactId.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Invalid download artifact"
        }
        return "${directory.trimEnd('/')}/$artifactId.download"
    }
}

object IosDownloadNotificationPermissionController : DownloadNotificationPermissionController {
    override fun requestIfNeeded() {
        UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionSound,
        ) { _, _ -> }
    }
}
