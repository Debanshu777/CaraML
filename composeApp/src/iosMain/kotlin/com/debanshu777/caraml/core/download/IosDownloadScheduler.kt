@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.debanshu777.caraml.core.download

import com.debanshu777.huggingfacemanager.download.ArtifactFileAccessException
import com.debanshu777.huggingfacemanager.download.ArtifactVerificationException
import com.debanshu777.huggingfacemanager.download.DownloadResponseProvenance
import com.debanshu777.huggingfacemanager.download.IosCompletedDownloadImporter
import com.debanshu777.huggingfacemanager.download.artifactDownloadUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** iOS-native durable driver backed by one stable background URLSession. */
class IosDownloadScheduler internal constructor(
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
    private val responseBound = IosDownloadResponseBound()
    private val responseBoundLock = NSLock()
    private val importFlights = IosCompletionSingleFlight()
    private val finalizationMutex = Mutex()
    private val restorationReady = CompletableDeferred<Unit>()
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
        restorationReady.await()
        enqueueAfterRestoration(batchId, ignoredTaskId = null)
    }

    private suspend fun enqueueAfterRestoration(batchId: String, ignoredTaskId: ULong?) {
        requireBatchId(batchId)
        val batchAlreadyOwned = activeTasks().any { task ->
            task.taskIdentifier != ignoredTaskId && task.activeTaskKey()?.batchId == batchId
        }
        if (batchAlreadyOwned) return
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
        val descriptor = IosBackgroundTaskDescriptor(
            batchId = batch.batchId,
            artifactId = artifact.artifactId,
            expectedBytes = artifact.expectedBytes,
        )
        iosPersistedTaskBindingFailure(batch, descriptor)?.let { failure ->
            failArtifact(artifact.artifactId, failure, terminal = true)
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
                enqueueAfterRestoration(batchId, ignoredTaskId = null)
            }
            return
        }
        completedFileStore.read(artifact.artifactId)?.let { completion ->
            launchCapturedImport(completion)
            return
        }

        val task = resumeDataStore.read(artifact.artifactId)?.let(session::downloadTaskWithResumeData)
            ?: run {
                val url = NSURL.URLWithString(artifactDownloadUrl(artifact.request.metadata.artifact))
                    ?: throw IllegalArgumentException("Invalid artifact URL")
                session.downloadTaskWithRequest(NSMutableURLRequest.requestWithURL(url))
            }
        val platformTaskId = task.taskIdentifier.toString()
        task.taskDescription = descriptor.encode()
        withResponseBoundLock { responseBound.registerNewTask(platformTaskId, task.taskDescription) }
        store.setPlatformTaskId(artifact.artifactId, platformTaskId, nowEpochMs())
        val claimed = store.claim(
            artifactId = artifact.artifactId,
            owner = "ios-urlsession-$platformTaskId",
            nowEpochMs = nowEpochMs(),
            expiresAtEpochMs = Long.MAX_VALUE,
        )
        if (!claimed) {
            persistStoppedDisposition(task)
            task.cancel()
            store.setPlatformTaskId(artifact.artifactId, null, nowEpochMs())
            return
        }
        resumeDataStore.delete(artifact.artifactId)
        task.resume()
    }

    override suspend fun pause(batchId: String) {
        requireBatchId(batchId)
        activeTasks().filter { it.activeTaskKey()?.batchId == batchId }.forEach { task ->
            val artifactId = task.activeTaskKey()?.artifactId ?: return@forEach
            persistStoppedDisposition(task)
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
        activeTasks().filter { it.activeTaskKey()?.batchId == batchId }.forEach { task ->
            task.activeTaskKey()?.artifactId?.let(resumeDataStore::delete)
            persistStoppedDisposition(task)
            task.cancel()
        }
        store.getBatch(batchId)?.artifacts?.forEach { artifact ->
            resumeDataStore.delete(artifact.artifactId)
            completedFileStore.delete(artifact.artifactId)
        }
    }

    override suspend fun reconcile(liveBatchIds: Set<String>) {
        try {
            reconcileRestoredTasks(liveBatchIds)
            liveBatchIds.forEach batchLoop@{ batchId ->
                val batch = store.getBatch(batchId) ?: return@batchLoop
                batch.artifacts.forEach artifactLoop@{ artifact ->
                    val completion = completedFileStore.read(artifact.artifactId) ?: return@artifactLoop
                    if (completion.matches(batch, artifact)) {
                        launchCapturedImport(completion)
                    } else {
                        completedFileStore.delete(artifact.artifactId)
                    }
                }
            }
        } finally {
            restorationReady.complete(Unit)
        }
    }

    private suspend fun reconcileRestoredTasks(liveBatchIds: Set<String>) {
        liveBatchIds.forEach(::requireBatchId)
        val restoreGeneration = withResponseBoundLock {
            responseBound.captureRestoreRegistrationGeneration()
        }
        activeTasks().forEach { task ->
            val descriptor = task.taskDescriptor()
            val taskId = task.taskIdentifier.toString()
            if (descriptor == null) {
                val recoverableKey = IosBackgroundTaskDescriptor.recoverKeyForTerminalFailure(task.taskDescription)
                task.cancel()
                recoverableKey?.let { failBoundedResponse(it) }
                return@forEach
            }
            when (descriptor.disposition) {
                IosBackgroundTaskDisposition.REJECTED -> {
                    task.cancel()
                    failBoundedResponse(descriptor.key)
                    return@forEach
                }
                IosBackgroundTaskDisposition.STOPPED -> {
                    task.cancel()
                    return@forEach
                }
                IosBackgroundTaskDisposition.ACTIVE -> Unit
            }
            if (descriptor.batchId !in liveBatchIds) {
                persistStoppedDisposition(task)
                task.cancel()
                return@forEach
            }
            val batch = store.getBatch(descriptor.batchId)
            val artifact = batch?.artifacts?.firstOrNull { it.artifactId == descriptor.artifactId }
            val bindingFailure = if (batch == null) {
                DownloadFailureCode.INTEGRITY
            } else {
                iosPersistedTaskBindingFailure(batch, descriptor)
            }
            if (artifact == null || bindingFailure != null) {
                val rejection = withResponseBoundLock {
                    responseBound.inspect(taskId, task.taskDescription, -1L, -1L)
                }
                if (rejection is IosDownloadBoundDecision.Reject) {
                    rejectAndCancel(task, rejection.descriptor)
                } else {
                    task.cancel()
                }
                if (artifact != null && artifact.state !in TERMINAL_ARTIFACT_STATES) {
                    failBoundedResponse(
                        descriptor.key,
                        bindingFailure ?: DownloadFailureCode.INTEGRITY,
                    )
                }
            } else {
                val registered = withResponseBoundLock {
                    responseBound.registerRestored(
                        taskId,
                        task.taskDescription,
                        restoreGeneration,
                    )
                }
                if (registered) {
                    store.setPlatformTaskId(descriptor.artifactId, taskId, nowEpochMs())
                }
            }
        }
    }

    override suspend fun isActive(batchId: String): Boolean {
        requireBatchId(batchId)
        restorationReady.await()
        if (activeTasks().any { it.activeTaskKey()?.batchId == batchId }) return true
        val batch = store.getBatch(batchId) ?: return false
        return batch.artifacts.any { artifact ->
            val completion = completedFileStore.read(artifact.artifactId)
            completion?.matches(batch, artifact) == true
        }
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
        val taskId = downloadTask.taskIdentifier.toString()
        when (
            val decision = withResponseBoundLock {
                responseBound.inspect(
                    taskId = taskId,
                    persistedTaskDescription = downloadTask.taskDescription,
                    totalBytesWritten = totalBytesWritten,
                    declaredExpectedBytes = totalBytesExpectedToWrite,
                )
            }
        ) {
            is IosDownloadBoundDecision.Invalid -> {
                downloadTask.cancel()
                decision.recoverableKey?.let { key ->
                    launchTracked { failBoundedResponse(key) }
                }
                return
            }
            IosDownloadBoundDecision.Inactive -> {
                downloadTask.cancel()
                return
            }
            is IosDownloadBoundDecision.Reject -> {
                rejectAndCancel(downloadTask, decision.descriptor)
                launchTracked { failBoundedResponse(decision.descriptor.key) }
                return
            }
            IosDownloadBoundDecision.Duplicate -> return
            is IosDownloadBoundDecision.Progress -> persistProgress(decision, nowEpochMs())
        }
    }

    private fun persistProgress(decision: IosDownloadBoundDecision.Progress, now: Long) {
        val artifactId = decision.descriptor.artifactId
        progressCheckpointsLock.lock()
        val shouldPersist = try {
            val previous = progressCheckpoints[artifactId]
            if (previous != null &&
                decision.bytesWritten - previous.first < 1024L * 1024L &&
                now - previous.second < 500L
            ) {
                false
            } else {
                progressCheckpoints[artifactId] = decision.bytesWritten to now
                true
            }
        } finally {
            progressCheckpointsLock.unlock()
        }
        if (!shouldPersist) return
        scope.launch {
            val batch = store.getBatch(decision.descriptor.batchId) ?: return@launch
            val artifact = batch.artifacts.firstOrNull { it.artifactId == artifactId } ?: return@launch
            iosPersistedTaskBindingFailure(batch, decision.descriptor)?.let { failure ->
                failBoundedResponse(decision.descriptor.key, failure)
                return@launch
            }
            if (decision.bytesWritten <= artifact.expectedBytes) {
                store.updateProgress(artifactId, decision.bytesWritten, null, null, now)
            }
        }
    }

    internal fun didFinishDownloading(
        downloadTask: NSURLSessionDownloadTask,
        temporaryUrl: NSURL,
    ) {
        val descriptor = downloadTask.taskDescriptor() ?: run {
            val recoverableKey = IosBackgroundTaskDescriptor.recoverKeyForTerminalFailure(
                downloadTask.taskDescription,
            )
            downloadTask.cancel()
            recoverableKey?.let { key -> launchTracked { failBoundedResponse(key) } }
            return
        }
        when (descriptor.disposition) {
            IosBackgroundTaskDisposition.REJECTED -> {
                downloadTask.cancel()
                launchTracked { failBoundedResponse(descriptor.key) }
                return
            }
            IosBackgroundTaskDisposition.STOPPED -> {
                downloadTask.cancel()
                return
            }
            IosBackgroundTaskDisposition.ACTIVE -> Unit
        }
        val temporaryPath = temporaryUrl.path ?: run {
            downloadTask.cancel()
            launchTracked { failBoundedResponse(descriptor.key, DownloadFailureCode.SECURE_PATH) }
            return
        }
        val response = downloadTask.response as? NSHTTPURLResponse
        val completedBytes = runCatching {
            FileSystem.SYSTEM.metadata(temporaryPath.toPath(normalize = true)).size
        }.getOrNull()
        if (completedBytes == null) {
            downloadTask.cancel()
            launchTracked {
                failBoundedResponse(descriptor.key, DownloadFailureCode.SECURE_PATH)
            }
            return
        }
        when (
            val decision = withResponseBoundLock {
                responseBound.inspect(
                    taskId = downloadTask.taskIdentifier.toString(),
                    persistedTaskDescription = downloadTask.taskDescription,
                    totalBytesWritten = completedBytes,
                    declaredExpectedBytes = response?.expectedContentLength ?: -1L,
                )
            }
        ) {
            is IosDownloadBoundDecision.Invalid -> {
                downloadTask.cancel()
                decision.recoverableKey?.let { key ->
                    launchTracked { failBoundedResponse(key) }
                }
                return
            }
            IosDownloadBoundDecision.Inactive -> {
                downloadTask.cancel()
                return
            }
            is IosDownloadBoundDecision.Reject -> {
                rejectAndCancel(downloadTask, decision.descriptor)
                launchTracked { failBoundedResponse(decision.descriptor.key) }
                return
            }
            IosDownloadBoundDecision.Duplicate,
            is IosDownloadBoundDecision.Progress,
            -> Unit
        }
        val finalUrl = response?.URL?.absoluteString
        val status = response?.statusCode?.toInt()
        val provenance = if (finalUrl != null && status != null) {
            DownloadResponseProvenance.validate(finalUrl, status)
        } else {
            null
        }
        if (provenance == null) {
            downloadTask.cancel()
            launchTracked {
                transitionCurrentTaskFailure(
                    descriptor = descriptor,
                    platformTaskId = downloadTask.taskIdentifier.toString(),
                    code = DownloadFailureCode.HTTP,
                    terminal = status == null || status in 400..499,
                )
            }
            return
        }
        val completion = runCatching {
            IosValidatedCompletionEnvelope.create(
                taskDescription = requireNotNull(downloadTask.taskDescription),
                platformTaskId = downloadTask.taskIdentifier.toString(),
                completedBytes = completedBytes,
                response = provenance,
            )
        }.getOrElse {
            downloadTask.cancel()
            launchTracked { failBoundedResponse(descriptor.key) }
            return
        }
        val captured = runCatching { completedFileStore.capture(completion, temporaryPath) }
            .getOrElse {
                launchTracked {
                    transitionCurrentTaskFailure(
                        descriptor = descriptor,
                        platformTaskId = downloadTask.taskIdentifier.toString(),
                        code = DownloadFailureCode.SECURE_PATH,
                        terminal = true,
                    )
                }
                return
            }
        launchCapturedImport(captured)
    }

    private fun launchCapturedImport(completion: IosValidatedCompletionEnvelope) {
        launchTracked {
            importFlights.runOrJoin(completion.key) {
                importCapturedCompletion(completion)
            }
        }
    }

    private suspend fun importCapturedCompletion(expected: IosValidatedCompletionEnvelope) {
        val completion = completedFileStore.read(expected.descriptor.artifactId)
            ?.takeIf { it == expected }
            ?: return
        val descriptor = completion.descriptor
        val batch = store.getBatch(descriptor.batchId) ?: run {
            completedFileStore.delete(descriptor.artifactId)
            return
        }
        val artifact = batch.artifacts.firstOrNull { it.artifactId == descriptor.artifactId } ?: run {
            completedFileStore.delete(descriptor.artifactId)
            return
        }
        if (!completion.matches(batch, artifact)) {
            completedFileStore.delete(artifact.artifactId)
            return
        }
        if (batch.userIntent != DownloadUserIntent.RUN) return
        when (artifact.state) {
            DownloadArtifactState.VERIFYING,
            DownloadArtifactState.COMPLETED,
            -> {
                completedFileStore.delete(artifact.artifactId)
                finishBatchIfReady(batch.batchId)
                return
            }
            DownloadArtifactState.PAUSED,
            DownloadArtifactState.WAITING_FOR_NETWORK,
            -> return
            DownloadArtifactState.CANCELLED,
            DownloadArtifactState.FAILED_TERMINAL,
            -> {
                completedFileStore.delete(artifact.artifactId)
                return
            }
            DownloadArtifactState.QUEUED,
            DownloadArtifactState.FAILED_RETRYABLE,
            -> if (!store.claim(
                    artifact.artifactId,
                    "ios-import-${completion.platformTaskId}",
                    nowEpochMs(),
                    Long.MAX_VALUE,
                )
            ) {
                return
            }
            DownloadArtifactState.RUNNING -> Unit
        }
        try {
            if (!importer.isPublished(artifact.request.metadata)) {
                importer.import(
                    modelId = artifact.request.metadata.artifact.repositoryId,
                    path = artifact.request.metadata.artifact.relativePath,
                    metadata = artifact.request.metadata,
                    temporaryFilePath = completedFileStore.capturedPath(completion),
                    response = completion.response,
                )
            }
            val currentBatch = store.getBatch(batch.batchId) ?: run {
                completedFileStore.delete(artifact.artifactId)
                return
            }
            val currentArtifact = currentBatch.artifacts.firstOrNull { it.artifactId == artifact.artifactId }
            if (currentArtifact == null || !completion.matches(currentBatch, currentArtifact)) {
                completedFileStore.delete(artifact.artifactId)
                return
            }
            if (currentBatch.userIntent != DownloadUserIntent.RUN ||
                currentArtifact.state != DownloadArtifactState.RUNNING
            ) {
                return
            }
            val transitioned = store.transitionPlatformTask(
                artifactId = artifact.artifactId,
                platformTaskId = completion.platformTaskId,
                state = DownloadArtifactState.VERIFYING,
                failureCode = null,
                completedBytes = artifact.expectedBytes,
                nowEpochMs = nowEpochMs(),
            )
            if (!transitioned) {
                completedFileStore.delete(artifact.artifactId)
                return
            }
            clearProgressCheckpoint(artifact.artifactId)
            completedFileStore.delete(artifact.artifactId)
            enqueueAfterRestoration(batch.batchId, completion.platformTaskId.toULongOrNull())
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: ArtifactVerificationException) {
            completedFileStore.delete(artifact.artifactId)
            transitionCurrentTaskFailure(
                descriptor,
                completion.platformTaskId,
                DownloadFailureCode.INTEGRITY,
                terminal = true,
            )
        } catch (_: ArtifactFileAccessException) {
            completedFileStore.delete(artifact.artifactId)
            transitionCurrentTaskFailure(
                descriptor,
                completion.platformTaskId,
                DownloadFailureCode.SECURE_PATH,
                terminal = true,
            )
        } catch (_: Exception) {
            completedFileStore.delete(artifact.artifactId)
            transitionCurrentTaskFailure(
                descriptor,
                completion.platformTaskId,
                DownloadFailureCode.PLATFORM,
                terminal = false,
            )
        }
    }

    private suspend fun transitionCurrentTaskFailure(
        descriptor: IosBackgroundTaskDescriptor,
        platformTaskId: String,
        code: DownloadFailureCode,
        terminal: Boolean,
    ): Boolean {
        val batch = store.getBatch(descriptor.batchId) ?: return false
        val artifact = batch.artifacts.firstOrNull { it.artifactId == descriptor.artifactId } ?: return false
        if (artifact.platformTaskId != platformTaskId ||
            iosPersistedTaskBindingFailure(batch, descriptor) != null ||
            artifact.state in TERMINAL_ARTIFACT_STATES
        ) {
            return false
        }
        return store.transitionPlatformTask(
            artifactId = artifact.artifactId,
            platformTaskId = platformTaskId,
            state = if (terminal) DownloadArtifactState.FAILED_TERMINAL else DownloadArtifactState.FAILED_RETRYABLE,
            failureCode = code,
            completedBytes = null,
            nowEpochMs = nowEpochMs(),
        )
    }

    internal fun didComplete(
        task: NSURLSessionTask,
        error: platform.Foundation.NSError?,
    ) {
        val downloadTask = task as? NSURLSessionDownloadTask ?: return
        val completion = withResponseBoundLock {
            responseBound.complete(
                downloadTask.taskIdentifier.toString(),
                downloadTask.taskDescription,
            )
        }
        when (completion) {
            IosDownloadBoundCompletion.REJECTED,
            IosDownloadBoundCompletion.INVALID,
            -> {
                val key = downloadTask.taskDescriptor()?.key
                    ?: IosBackgroundTaskDescriptor.recoverKeyForTerminalFailure(downloadTask.taskDescription)
                key?.let { recovered -> launchTracked { failBoundedResponse(recovered) } }
                return
            }
            IosDownloadBoundCompletion.STOPPED -> return
            IosDownloadBoundCompletion.ACTIVE,
            IosDownloadBoundCompletion.MISSING,
            -> Unit
        }
        val descriptor = downloadTask.taskDescriptor() ?: return
        launchTracked tracked@{
            val batch = store.getBatch(descriptor.batchId) ?: return@tracked
            val artifact = batch.artifacts.firstOrNull { it.artifactId == descriptor.artifactId } ?: return@tracked
            iosPersistedTaskBindingFailure(batch, descriptor)?.let { failure ->
                failBoundedResponse(descriptor.key, failure)
                return@tracked
            }
            val captured = completedFileStore.read(artifact.artifactId)
            if (captured?.matches(batch, artifact) == true) {
                importFlights.runOrJoin(captured.key) { importCapturedCompletion(captured) }
                return@tracked
            }
            if (error == null) return@tracked
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
        finalizationMutex.withLock {
            val batch = store.getBatch(batchId) ?: return@withLock
            if (batch.artifacts.none { it.state == DownloadArtifactState.VERIFYING } ||
                batch.artifacts.any {
                    it.state !in setOf(DownloadArtifactState.VERIFYING, DownloadArtifactState.COMPLETED)
                }
            ) {
                return@withLock
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

    private suspend fun failBoundedResponse(
        key: IosBackgroundTaskKey,
        code: DownloadFailureCode = DownloadFailureCode.INTEGRITY,
    ) {
        val artifact = store.getBatch(key.batchId)
            ?.artifacts
            ?.firstOrNull { it.artifactId == key.artifactId }
            ?: return
        if (artifact.state in TERMINAL_ARTIFACT_STATES) return
        resumeDataStore.delete(artifact.artifactId)
        completedFileStore.delete(artifact.artifactId)
        clearProgressCheckpoint(artifact.artifactId)
        try {
            if (artifact.state in REQUEUE_BEFORE_TERMINAL_STATES) {
                store.transitionArtifact(
                    artifact.artifactId,
                    DownloadArtifactState.QUEUED,
                    null,
                    nowEpochMs(),
                )
            }
            failArtifact(artifact.artifactId, code, terminal = true)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: IllegalArgumentException) {
            val currentState = store.getBatch(key.batchId)
                ?.artifacts
                ?.firstOrNull { it.artifactId == key.artifactId }
                ?.state
            if (currentState !in TERMINAL_ARTIFACT_STATES) throw cause
        }
    }

    private fun rejectAndCancel(
        task: NSURLSessionDownloadTask,
        descriptor: IosBackgroundTaskDescriptor,
    ) {
        persistIosRejectionBeforeCancellation(
            descriptor = descriptor,
            persistTaskDescription = { task.taskDescription = it },
            cancel = { task.cancel() },
        )
    }

    private fun persistStoppedDisposition(task: NSURLSessionDownloadTask) {
        val stopped = withResponseBoundLock {
            responseBound.stop(task.taskIdentifier.toString(), task.taskDescription)
        }
        if (stopped != null) task.taskDescription = stopped.encode()
    }

    private suspend fun activeTasks(): List<NSURLSessionDownloadTask> = suspendCancellableCoroutine { continuation ->
        session.getAllTasksWithCompletionHandler { tasks ->
            if (continuation.isActive) continuation.resume(tasks.orEmpty().filterIsInstance<NSURLSessionDownloadTask>())
        }
    }

    private fun NSURLSessionDownloadTask.taskDescriptor(): IosBackgroundTaskDescriptor? =
        IosBackgroundTaskDescriptor.decode(taskDescription)

    private fun NSURLSessionDownloadTask.activeTaskKey(): IosBackgroundTaskKey? =
        iosActiveTaskKey(taskDescription)

    private fun IosValidatedCompletionEnvelope.matches(
        batch: DownloadBatchSnapshot,
        artifact: DownloadArtifactSnapshot,
    ): Boolean =
        descriptor.batchId == batch.batchId &&
            descriptor.artifactId == artifact.artifactId &&
            completedBytes == artifact.expectedBytes &&
            platformTaskId == artifact.platformTaskId &&
            iosPersistedTaskBindingFailure(batch, descriptor) == null

    private fun <T> withResponseBoundLock(block: () -> T): T {
        responseBoundLock.lock()
        return try {
            block()
        } finally {
            responseBoundLock.unlock()
        }
    }

    private fun clearProgressCheckpoint(artifactId: String) {
        progressCheckpointsLock.lock()
        try {
            progressCheckpoints.remove(artifactId)
        } finally {
            progressCheckpointsLock.unlock()
        }
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

    private companion object {
        val TERMINAL_ARTIFACT_STATES = setOf(
            DownloadArtifactState.COMPLETED,
            DownloadArtifactState.FAILED_TERMINAL,
            DownloadArtifactState.CANCELLED,
        )
        val REQUEUE_BEFORE_TERMINAL_STATES = setOf(
            DownloadArtifactState.PAUSED,
            DownloadArtifactState.FAILED_RETRYABLE,
        )
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
internal class IosCompletedFileStore(
    private val directory: String,
) {
    private val lock = NSLock()

    init {
        FileSystem.SYSTEM.createDirectories(directory.toPath(normalize = true))
    }

    fun capture(
        completion: IosValidatedCompletionEnvelope,
        temporaryPath: String,
    ): IosValidatedCompletionEnvelope = withLock {
        readUnlocked(completion.descriptor.artifactId)?.let { existing ->
            check(existing == completion) { "Conflicting completed download" }
            return@withLock existing
        }
        val target = payloadPath(completion.descriptor.artifactId)
        val envelope = envelopePath(completion.descriptor.artifactId)
        val envelopeTemporary = "$envelope.tmp".toPath(normalize = true)
        try {
            FileSystem.SYSTEM.delete(target, mustExist = false)
            FileSystem.SYSTEM.atomicMove(temporaryPath.toPath(normalize = true), target)
            val encoded = IosValidatedCompletionEnvelopeCodec.encode(completion)
            FileSystem.SYSTEM.delete(envelopeTemporary, mustExist = false)
            FileSystem.SYSTEM.write(envelopeTemporary) { writeUtf8(encoded) }
            FileSystem.SYSTEM.delete(envelope, mustExist = false)
            FileSystem.SYSTEM.atomicMove(envelopeTemporary, envelope)
            completion
        } catch (cause: Exception) {
            runCatching { FileSystem.SYSTEM.delete(envelopeTemporary, mustExist = false) }
            runCatching { FileSystem.SYSTEM.delete(envelope, mustExist = false) }
            runCatching { FileSystem.SYSTEM.delete(target, mustExist = false) }
            throw cause
        }
    }

    fun read(artifactId: String): IosValidatedCompletionEnvelope? = withLock {
        readUnlocked(artifactId)
    }

    fun capturedPath(completion: IosValidatedCompletionEnvelope): String = withLock {
        check(readUnlocked(completion.descriptor.artifactId) == completion) { "Invalid completed download" }
        payloadPath(completion.descriptor.artifactId).toString()
    }

    fun delete(artifactId: String) = withLock {
        runCatching { FileSystem.SYSTEM.delete(payloadPath(artifactId), mustExist = false) }
        runCatching { FileSystem.SYSTEM.delete(envelopePath(artifactId), mustExist = false) }
        Unit
    }

    private fun readUnlocked(artifactId: String): IosValidatedCompletionEnvelope? {
        val payload = payloadPath(artifactId)
        val envelope = envelopePath(artifactId)
        if (!FileSystem.SYSTEM.exists(envelope)) {
            runCatching { FileSystem.SYSTEM.delete(payload, mustExist = false) }
            return null
        }
        val envelopeMetadata = runCatching { FileSystem.SYSTEM.metadata(envelope) }.getOrNull()
        val envelopeSize = envelopeMetadata?.size
        if (envelopeMetadata?.isRegularFile != true || envelopeSize == null || envelopeSize !in 1L..2_048L) {
            deleteUnlocked(payload, envelope)
            return null
        }
        val encoded = runCatching { FileSystem.SYSTEM.read(envelope) { readUtf8() } }.getOrNull()
        val completion = encoded?.let(IosValidatedCompletionEnvelopeCodec::decode)
        val payloadMetadata = runCatching { FileSystem.SYSTEM.metadata(payload) }.getOrNull()
        if (completion == null || completion.descriptor.artifactId != artifactId ||
            completion.captureRelativePath != "$artifactId.download" ||
            payloadMetadata?.isRegularFile != true || payloadMetadata.size != completion.completedBytes
        ) {
            deleteUnlocked(payload, envelope)
            return null
        }
        return completion
    }

    private fun deleteUnlocked(payload: okio.Path, envelope: okio.Path) {
        runCatching { FileSystem.SYSTEM.delete(payload, mustExist = false) }
        runCatching { FileSystem.SYSTEM.delete(envelope, mustExist = false) }
    }

    private fun payloadPath(artifactId: String): okio.Path =
        "${directory.trimEnd('/')}/${validatedArtifactId(artifactId)}.download".toPath(normalize = true)

    private fun envelopePath(artifactId: String): okio.Path =
        "${directory.trimEnd('/')}/${validatedArtifactId(artifactId)}.completion.json".toPath(normalize = true)

    private fun validatedArtifactId(artifactId: String): String {
        require(artifactId.length == 64 && artifactId.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Invalid download artifact"
        }
        return artifactId
    }

    private fun <T> withLock(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}

object IosDownloadNotificationPermissionController : DownloadNotificationPermissionController {
    override fun requestIfNeeded() {
        UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionSound,
        ) { _, _ -> }
    }
}
