package com.debanshu777.caraml.benchmark

import com.debanshu777.caraml.core.download.storage.RoomDownloadTaskStore
import com.debanshu777.caraml.core.storage.catalog.InstalledModelCatalogDao
import android.content.Context
import android.system.ErrnoException
import android.app.job.JobScheduler
import android.os.Build
import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import com.debanshu777.caraml.core.data.inference.InferenceRepository
import com.debanshu777.caraml.core.data.inference.LlamaInferenceRepository
import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.data.inference.ModelLoadResult
import com.debanshu777.caraml.core.download.*
import com.debanshu777.caraml.core.recommendation.*
import com.debanshu777.caraml.core.recommendation.storage.PersistedModelEvidenceCodec
import com.debanshu777.caraml.core.settings.initPreferencesDataStore
import com.debanshu777.caraml.core.storage.localmodel.LocalModelRepository
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.storage.component.ComponentRepository
import com.debanshu777.caraml.core.storage.localmodel.ModelType
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.modelhub.domain.ModelMetadataSource
import com.debanshu777.caraml.features.modelhub.domain.RepositoryVariantSet
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.runner.LlamaRunner
import com.debanshu777.runner.StopReason
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.koin.mp.KoinPlatform
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Fixed public artifact identities and synthetic prompts, available only in debug builds. */
class ModelRuntimeValidation : Instrumentation() {
    private var options = Bundle()
    private lateinit var evidence: File
    private val stages = ConcurrentHashMap<String, String>()
    private val outcomes = ConcurrentHashMap<String, Outcome>()
    private val validationBatches = ConcurrentHashMap<String, String>()
    private val recoveryAttempts = ConcurrentHashMap.newKeySet<String>()
    private val ownershipLock = Any()
    private val ownedIds by lazy { targetContext.getSharedPreferences("runtime-validation-owned-transfers", Context.MODE_PRIVATE) }
    private var activeInference: InferenceRepository? = null
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); options = arguments ?: Bundle(); start() }
    override fun onStart() {
        waitForIdleSync()
        evidence = File(targetContext.filesDir, "runtime-validation.jsonl")
        try {
            require(options.keySet().all { it in setOf("case", "phase", "resumeSelected") && options.get(it) is String })
            val caseName = options.getString("case") ?: "all"
            val phase = options.getString("phase") ?: "run"
            require(options.getString("resumeSelected") in setOf(null, "true", "false"))
            require(phase in setOf("download", "run"))
            val cases = when (caseName) {
                "all" -> VALIDATION_CASES
                "new" -> VALIDATION_CASES.filter { it.id in NEW_VALIDATION_CASE_IDS }
                else -> VALIDATION_CASES.filter { it.id == caseName }
            }
            require(cases.isNotEmpty())
            initPreferencesDataStore(targetContext)
            val allPassed = runBlocking {
                KoinPlatform.getKoin().get<DownloadRuntime>().awaitStartupReconciliation()
                probePublicConnectivity()
                pausePriorValidationTransfers(cases)
                supervisorScope {
                    // Up to three normal download queues run while inference stays serial.
                    val permits = Semaphore(3)
                    val downloads = cases.associate { case -> case.id to async {
                        permits.withPermit {
                            try { withTimeout(60 * 60_000L) { validate(case, false) } }
                            catch (failure: Exception) { pauseOutstandingTransfer(case.id); throw failure }
                        }
                    } }
                    for (case in cases) {
                        try {
                            val downloaded = downloads.getValue(case.id).await()
                            outcomes[case.id] = if (phase == "run" && downloaded == Outcome.PASS) withTimeout(5 * 60_000L) { validate(case, true) } else downloaded
                            report(case.id, "outcome", "result" to outcomes.getValue(case.id).name)
                        }
                        catch (failure: Exception) {
                            val failedStage = stages[case.id] ?: "UNKNOWN"
                            outcomes[case.id] = Outcome.FAILED
                            report(case.id, "outcome", "result" to Outcome.FAILED.name, "atStage" to failedStage,
                                "reason" to if (failure is TimeoutCancellationException) "TIMEOUT" else "VALIDATION_FAILED",
                                "errorClass" to failure.javaClass.simpleName.takeIf { it.length <= 64 && it.all(Char::isLetterOrDigit) })
                        }
                        finally { runCatching { activeInference?.unloadModel() }; activeInference = null }
                    }
                }
                cases.forEach { pauseOutstandingTransfer(it.id) }
                cases.all { outcomes[it.id] == Outcome.PASS }
            }
            finish(if (allPassed) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
                putString("stream", "Validation results: " + outcomes.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value.name}" } + "\n")
            })
        } catch (_: Exception) {
            runBlocking { validationBatches.keys.toList().forEach { pauseOutstandingTransfer(it) } }
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "Invalid runtime validation request\n") })
        }
    }

    private suspend fun pauseOutstandingTransfer(id: String) = withContext(NonCancellable) {
        val batchId = validationBatches[id] ?: return@withContext
        val case = VALIDATION_CASES.singleOrNull { it.id == id } ?: return@withContext
        val paused = pauseOwnedSelected(case, batchId)
        report(id, "transfer_cleanup", "paused" to paused)
        if (paused) validationBatches.remove(id, batchId)
    }

    private suspend fun pauseOwnedSelected(case: ValidationCase, batchId: String): Boolean = withContext(NonCancellable) {
        withTimeoutOrNull(5_000L) {
            try {
                val koin = KoinPlatform.getKoin()
                val store = koin.get<DownloadTaskStore>() as RoomDownloadTaskStore
                val observed = store.runningPauseSnapshot(batchId) ?: return@withTimeoutOrNull false
                if (!owns(batchId) || !validMarker(observed.first.displayName) || !matches(case, observed.first))
                    return@withTimeoutOrNull false
                val paused = store.pauseRunningSnapshot(observed.first, observed.second, System.currentTimeMillis())
                if (paused) koin.get<PlatformDownloadScheduler>().pause(batchId)
                paused
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        } ?: false
    }

    private suspend fun pausePriorValidationTransfers(cases: List<ValidationCase>) {
        val coordinator = KoinPlatform.getKoin().get<DownloadCoordinator>()
        for (case in cases) for (batch in coordinator.observeForModel(case.repository).first()) {
            if (owns(batch.batchId) && matches(case, batch) && pauseOwnedSelected(case, batch.batchId))
                report(case.id, "prior_transfer_paused")
        }
    }

    private fun validBatchId(id: String) = id.length == 64 && id.all { it in '0'..'9' || it in 'a'..'f' }
    private fun ownershipCount(): Int = synchronized(ownershipLock) {
        ownedIds.getStringSet("ids", emptySet()).orEmpty().count(::validBatchId)
    }
    private fun owns(id: String): Boolean = synchronized(ownershipLock) {
        validBatchId(id) && id in ownedIds.getStringSet("ids", emptySet()).orEmpty()
    }
    private fun validMarker(marker: String) = marker.startsWith("runtime-validation-") &&
        marker.removePrefix("runtime-validation-").let { suffix ->
            suffix.length == 36 && suffix.all { it in '0'..'9' || it in 'a'..'f' || it == '-' }
        }
    private fun pendingOwns(marker: String): Boolean = synchronized(ownershipLock) {
        validMarker(marker) && marker in ownedIds.getStringSet("pending", emptySet()).orEmpty()
    }
    private fun rememberPending(marker: String) = synchronized(ownershipLock) {
        check(validMarker(marker))
        val pending = ownedIds.getStringSet("pending", emptySet()).orEmpty().filter(::validMarker).toMutableSet()
        check(pending.size < 128)
        pending.add(marker)
        check(ownedIds.edit().putStringSet("pending", pending).commit())
    }
    private fun forgetPending(marker: String) = synchronized(ownershipLock) {
        val pending = ownedIds.getStringSet("pending", emptySet()).orEmpty().filter(::validMarker).toMutableSet()
        pending.remove(marker)
        check(ownedIds.edit().putStringSet("pending", pending).commit())
    }
    private fun rememberOwned(id: String, marker: String) = synchronized(ownershipLock) {
        check(validBatchId(id) && pendingOwns(marker))
        val ids = ownedIds.getStringSet("ids", emptySet()).orEmpty().filter(::validBatchId).toMutableSet()
        val pending = ownedIds.getStringSet("pending", emptySet()).orEmpty().filter(::validMarker).toMutableSet()
        check(ids.size < 128 || id in ids)
        ids.add(id)
        pending.remove(marker)
        check(ownedIds.edit().putStringSet("ids", ids).putStringSet("pending", pending).commit())
    }
    private fun matches(case: ValidationCase, batch: DownloadBatchSnapshot): Boolean {
        val identity = batch.artifacts.singleOrNull()?.request?.metadata?.artifact ?: return false
        return batch.ownerModelId == case.repository && identity.repositoryId == case.repository &&
            identity.immutableRevision == case.revision && identity.relativePath == case.file &&
            identity.remoteObjectId == "sha256:${case.sha256}" && identity.expectedBytes == case.bytes
    }
    /** Fixed-case diagnostics use the production read/verification APIs, with no catalog writes. */
    private suspend fun inspectPriorPublication(case: ValidationCase, batch: DownloadBatchSnapshot): Boolean {
        check(case.id == "tinyllama-1.1b" && matches(case, batch))
        val manager = KoinPlatform.getKoin().get<com.debanshu777.huggingfacemanager.download.DownloadManager>()
        val metadata = batch.artifacts.single().request.metadata
        val decoded = runCatching { PersistedModelEvidenceCodec().decode(batch.evidence) }.getOrNull()
        val primaryOwned = batch.artifacts.filter { it.request.primary }.let { primary ->
            primary.isNotEmpty() && primary.all { it.request.metadata.artifact.repositoryId == batch.ownerModelId } &&
                decoded?.descriptor?.repositoryId?.let { it == batch.ownerModelId } != false
        }
        val evidenceMatches = decoded?.artifactIdentities?.singleOrNull()?.let { identity ->
            identity.repositoryId == case.repository && identity.revision.equals(case.revision, true) &&
                identity.path == case.file && identity.sizeBytes == case.bytes &&
                identity.lfsOid?.removePrefix("sha256:")?.equals(case.sha256, true) == true
        } == true
        report(case.id, "publication_inspection", "evidenceDecoded" to (decoded != null),
            "primaryOwned" to primaryOwned, "evidenceMatches" to evidenceMatches,
            "immutableLayout" to metadata.usesImmutableStorageLayout)
        val catalog = KoinPlatform.getKoin().get<InstalledModelCatalogDao>().snapshotReady(case.repository)
        val rows = KoinPlatform.getKoin().get<LocalModelRepository>().getAllDownloadedFiles().first()
            .filter { it.modelId == case.repository }.take(64)
        report(case.id, "publication_catalog", "snapshotReady" to (catalog != null),
            "snapshotFilenameMatches" to (catalog?.model?.filename == case.file),
            "rowCount" to rows.size, "mainCount" to rows.count { it.isMainModel },
            "readyCount" to rows.count { it.componentStatus == LocalModelEntity.STATUS_READY },
            "filenameMatchCount" to rows.count { it.filename == case.file })
        val storage = manager.inspectStorage(listOf(metadata))?.singleOrNull()
        val artifacts = manager.validatedArtifacts(case.repository)
        val bundle = manager.validatedBundle(case.repository)
        val bundleMatches = bundle?.entries?.singleOrNull()?.let { entry ->
            entry.identity == metadata.artifact && entry.logicalRole == metadata.logicalRole &&
                entry.localRelativePath == metadata.destinationRelativePath && entry.bundleId == metadata.bundleId
        } == true
        val validBundle = manager.validateBundle(case.repository, listOf(metadata))
        val published = manager.isPublished(metadata)
        report(case.id, "publication_storage", "inspectReady" to (storage != null),
            "targetBytes" to storage?.targetBytes, "stagedBytes" to storage?.stagedBytes,
            "exactPublished" to storage?.exactPublished, "artifactManifestReady" to (artifacts != null),
            "bundleReady" to (bundle != null),
            "bundleExactEntryCount" to bundle?.entries?.count { entry ->
                entry.identity == metadata.artifact && entry.logicalRole == metadata.logicalRole &&
                    entry.localRelativePath == metadata.destinationRelativePath && entry.bundleId == metadata.bundleId },
            "validateBundle" to validBundle, "isPublished" to published)
        if (decoded == null || !primaryOwned || !evidenceMatches || !metadata.usesImmutableStorageLayout ||
            storage?.exactPublished != true || !published || !bundleMatches || !validBundle) return false
        // Explicitly authorized verified publication recovery: production locks and all checks remain active.
        if (!recoveryAttempts.add(case.id)) return false
        report(case.id, "verified_publication_recovery", "state" to "STARTED")
        KoinPlatform.getKoin().get<BatchFinalizer>().finalize(batch.batchId)
        val recovered = KoinPlatform.getKoin().get<InstalledModelCatalogDao>().snapshotReady(case.repository)
        report(case.id, "verified_publication_recovery", "state" to if (recovered != null) "READY" else "MISSING")
        return recovered?.model?.filename == case.file && recovered.model.isMainModel
    }

    private suspend fun probePublicConnectivity() = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        var connection: HttpURLConnection? = null
        try {
            connection = URL("https://huggingface.co").openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "HEAD"
            report("network", "public_hf_head", "httpStatus" to connection.responseCode,
                "elapsedMs" to (SystemClock.elapsedRealtime() - started))
        } catch (failure: Exception) {
            val causes = generateSequence(failure as Throwable) { it.cause }.take(8).toList()
            val errno = causes.filterIsInstance<ErrnoException>().firstOrNull()?.errno
            report("network", "public_hf_head", "httpStatus" to 0,
                "elapsedMs" to (SystemClock.elapsedRealtime() - started), "errno" to errno,
                "errorClasses" to causes.mapNotNull { it.javaClass.simpleName.takeIf { name ->
                    name.length <= 64 && name.all { it.isLetterOrDigit() || it == '_' } } }.joinToString(","))
        } finally { connection?.disconnect() }
    }

    private suspend fun validate(case: ValidationCase, run: Boolean): Outcome {
        val koin = KoinPlatform.getKoin()
        report(case.id, "start", "expectedBytes" to case.bytes)
        val artifact = requireNotNull(DownloadArtifactIdentity.create(case.repository, case.revision,
            case.file, "sha256:${case.sha256}", case.bytes))
        val request = DownloadArtifactRequest(DownloadMetadataDTO(artifact, "model", case.bytes,
            case.repository.substringBefore('/'), "gguf", "text-generation"), primary = true)
        val localModels = koin.get<LocalModelRepository>()
        var model = localModels.getMainModels().singleOrNull { it.modelId == case.repository && it.filename == case.file }
        if (model != null && !verifyPinned(case, model)) {
            report(case.id, "installed_identity_mismatch")
            model = null
        }
        if (model == null) {
            val variants = koin.get<ModelMetadataSource>().describeVariants(case.repository, ModelHubBrowseMode.LanguageModels)
            val descriptor = (variants as? RepositoryVariantSet.Ready)?.variants?.map { it.descriptor }
                ?.filterIsInstance<LlmModelDescriptor>()?.singleOrNull { candidate ->
                    candidate.files.any { it.path == case.file && it.revision == case.revision && it.sizeBytes == case.bytes }
                }
            val variantState = when (variants) {
                is RepositoryVariantSet.Ready -> "Ready"
                is RepositoryVariantSet.SelectVariant -> "SelectVariant"
                is RepositoryVariantSet.NeedsInformation -> "NeedsInformation"
            }
            val reasons = when (variants) {
                is RepositoryVariantSet.Ready -> emptyList()
                is RepositoryVariantSet.SelectVariant -> variants.reasons
                is RepositoryVariantSet.NeedsInformation -> variants.reasons
            }
            val files = (variants as? RepositoryVariantSet.Ready)?.variants?.take(65)
                ?.mapNotNull { it.descriptor as? LlmModelDescriptor }?.flatMap { it.files.take(64) }.orEmpty()
            report(case.id, "download_metadata", "descriptor" to (descriptor != null), "variantsState" to variantState,
                "reasons" to reasons.take(8).joinToString(",") { it.name }, "candidateCount" to files.size,
                "matchingFile" to files.any { it.path == case.file && it.sizeBytes == case.bytes },
                "currentRevisionMatchesPinned" to files.any { it.path == case.file && it.revision == case.revision })
            val coordinator = koin.get<DownloadCoordinator>()
            val marker = "runtime-validation-${UUID.randomUUID()}"
            val batchRequest = DownloadBatchRequest(case.repository, ModelType.TEXT, listOf(request),
                DownloadEvidenceFactory(PersistedModelEvidenceCodec()).create(listOf(request), descriptor),
                downloadForLaterConfirmed = true, displayName = marker)
            val store = koin.get<DownloadTaskStore>()
            val expectedBatchId = downloadBatchId(batchRequest)
            val prior = store.getBatch(expectedBatchId)
            val batchId = if (prior != null) expectedBatchId else {
                check(ownershipCount() < 128)
                rememberPending(marker)
                val (createdId, inserted) = (store as RoomDownloadTaskStore).createNewOnly(
                    batchRequest, System.currentTimeMillis())
                if (!inserted) forgetPending(marker)
                createdId
            }
            var existing = requireNotNull(store.getBatch(batchId))
            check(matches(case, existing))
            if (pendingOwns(existing.displayName)) rememberOwned(batchId, existing.displayName)
            val owned = owns(batchId)
            if (options.getString("resumeSelected") == "true" && case.id in
                setOf("tinyllama-1.1b", "stablelm2-zephyr-1.6b", "gemma2-2b", "phi2-2.7b")) {
                val roomStore = store as RoomDownloadTaskStore
                val paused = roomStore.pausedResumeSnapshot(batchId)
                if (paused != null) {
                    check(matches(case, paused.first))
                    val resumed = roomStore.resumePausedSnapshot(paused.first, paused.second, System.currentTimeMillis())
                    report(case.id, "explicit_selected_resume", "acknowledged" to resumed, "cleanupOwned" to false)
                    if (resumed) koin.get<PlatformDownloadScheduler>().enqueue(batchId)
                    existing = requireNotNull(store.getBatch(batchId))
                    check(matches(case, existing))
                }
            }
            val platformScheduler = koin.get<PlatformDownloadScheduler>()
            val schedulerActive = platformScheduler.isActive(batchId)
            val frameworkJobs = targetContext.getSystemService(JobScheduler::class.java)?.allPendingJobs.orEmpty()
                .filter { it.extras.getString("batch_id") == batchId }.take(4)
            val pendingReasons = if (Build.VERSION.SDK_INT >= 34) frameworkJobs.mapNotNull { job ->
                runCatching { targetContext.getSystemService(JobScheduler::class.java).getPendingJobReason(job.id) }
                    .getOrNull()?.takeIf { it in -2..128 }
            } else emptyList()
            report(case.id, "scheduler_snapshot", "state" to existing.state.name,
                "intent" to existing.userIntent.name, "active" to schedulerActive,
                "pendingCount" to frameworkJobs.size, "pendingReasons" to pendingReasons.joinToString(","))
            if (!owned && existing.state == DownloadBatchState.VERIFYING &&
                existing.userIntent == DownloadUserIntent.RUN && !schedulerActive) {
                // Mirrors normal startup reconciliation; neither queue intent nor cleanup ownership changes.
                platformScheduler.enqueue(batchId)
                report(case.id, "selected_verification_reconciled", "cleanupOwned" to false)
            }
            if (existing.state == DownloadBatchState.FAILED_RETRYABLE &&
                existing.failureCode == DownloadFailureCode.NETWORK && existing.userIntent == DownloadUserIntent.RUN) {
                // Explicit selected-case retry, not an ownership claim: concurrent user intent remains authoritative.
                val retried = (store as RoomDownloadTaskStore).retryNetworkIfRunningIntent(batchId, System.currentTimeMillis())
                report(case.id, "selected_network_retry", "acknowledged" to retried, "cleanupOwned" to false)
                if (retried) koin.get<PlatformDownloadScheduler>().enqueue(batchId)
                existing = requireNotNull(store.getBatch(batchId))
                check(matches(case, existing))
            }
            if (existing.userIntent != DownloadUserIntent.RUN || existing.state !in
                setOf(DownloadBatchState.QUEUED, DownloadBatchState.RUNNING, DownloadBatchState.VERIFYING,
                    DownloadBatchState.COMPLETED, DownloadBatchState.WAITING_FOR_NETWORK)) {
                report(case.id, "external_transfer", "reason" to "EXTERNAL_TRANSFER_PENDING")
                if (case.id == "tinyllama-1.1b" && inspectPriorPublication(case, existing)) return validate(case, run)
                return Outcome.NOT_RUN
            }
            if (owned) {
                validationBatches[case.id] = batchId
                if (existing.state in setOf(DownloadBatchState.QUEUED, DownloadBatchState.WAITING_FOR_NETWORK))
                    platformScheduler.enqueue(batchId)
            }
            report(case.id, "download_observed", "owned" to owned)
            var lastBytes = -1L
            var lastState: DownloadBatchState? = null
            val batch = coordinator.observeForModel(case.repository).mapNotNull { batches -> batches.singleOrNull { it.batchId == batchId } }
                .onEach { current ->
                    if (current.state != lastState) {
                        lastState = current.state
                        report(case.id, "download_state", "state" to current.state.name, "failure" to current.failureCode?.name)
                    }
                    if (current.bytesReceived / (64L * 1024L * 1024L) != lastBytes / (64L * 1024L * 1024L)) {
                        lastBytes = current.bytesReceived
                        report(case.id, "download_progress", "bytes" to current.bytesReceived, "state" to current.state.name)
                    }
                }.first { it.state in setOf(DownloadBatchState.COMPLETED, DownloadBatchState.FAILED_TERMINAL,
                    DownloadBatchState.CANCELLED) || (it.userIntent != DownloadUserIntent.RUN || (!owned && it.state in setOf(DownloadBatchState.PAUSED,
                        DownloadBatchState.FAILED_RETRYABLE, DownloadBatchState.WAITING_FOR_NETWORK))) }
            if (batch.state != DownloadBatchState.COMPLETED && (batch.userIntent != DownloadUserIntent.RUN || !owned)) {
                report(case.id, "external_transfer", "reason" to "EXTERNAL_TRANSFER_PENDING")
                return Outcome.NOT_RUN
            }
            report(case.id, "download_terminal", "state" to batch.state.name, "failure" to batch.failureCode?.name)
            check(batch.state == DownloadBatchState.COMPLETED)
            validationBatches.remove(case.id, batchId)
            model = localModels.getMainModels().single { it.modelId == case.repository && it.filename == case.file }
        } else report(case.id, "installed_reused")
        check(verifyPinned(case, model))
        report(case.id, "published", "bytes" to model.sizeBytes, "verifiedPinnedIdentity" to true)
        if (!run) return Outcome.PASS
        val resolver = koin.get<InstalledModelLoadRequestResolver>()
        val preparation = resolver.prepare(model, GenerationMode.Text)
        report(case.id, "prepare", "state" to preparation::class.simpleName,
            "terminal" to (preparation as? InstalledModelLoadPreparation.Terminal)?.resolution?.let(::resolutionName))
        if (preparation !is InstalledModelLoadPreparation.Ready) return Outcome.NOT_RUN
        val resolution = resolver.resolve(preparation)
        report(case.id, "resolve", "state" to resolutionName(resolution))
        val loadRequest = when (resolution) {
            is InstalledModelLoadResolution.Ready -> resolution.request
            // A suggested alternative still requires its normal admission and native fit checks.
            is InstalledModelLoadResolution.SafeAlternative -> resolution.saferRequest
            else -> return Outcome.NOT_RUN
        }
        // Use production inference with an in-memory generic prompt; never read or
        // rewrite persisted personal prompts, and keep recommendation/resource policy.
        val originalSettings = koin.get<SettingsRepository>()
        val controlled = originalSettings.getSettings().first().copy(systemPrompt = AppSettings.DEFAULT_SYSTEM_PROMPT, temperature = 0f)
        val settings = object : SettingsRepository {
            override fun getSettings() = flowOf(controlled)
            override suspend fun updateSettings(settings: AppSettings) = error("Read-only validation settings")
            override suspend fun updateRecommendationProfile(profile: RecommendationProfile) = error("Read-only validation settings")
            override suspend fun completeModelProfileOnboarding(profile: RecommendationProfile) = error("Read-only validation settings")
        }
        val inference = LlamaInferenceRepository(
            runner = koin.get(), deviceCapabilities = koin.get(), settingsRepository = settings,
            snapshotProvider = koin.get(), suitabilityEngine = koin.get(), recommendationPolicy = koin.get(),
            loadRecoveryRepository = koin.get(), artifactIdentityResolver = koin.get(), loadSessionCoordinator = koin.get(),
            observationRecorder = koin.get(),
        ).also { activeInference = it }
        val loadStarted = SystemClock.elapsedRealtime()
        val loaded = inference.loadModel(loadRequest)
        report(case.id, "load", "state" to loaded::class.simpleName, "elapsedMs" to (SystemClock.elapsedRealtime() - loadStarted),
            "context" to inference.getContextLimit())
        if (loaded !is ModelLoadResult.Success) return Outcome.NOT_RUN
        val runner = koin.get<LlamaRunner>()
        report(case.id, "loaded_config", "config" to inference.getRuntimeConfigString(), "thinking" to runner.supportsThinking())
        var naturalTurns = 0
        for ((turn, prompt) in listOf(
            1 to "The code word is violet. What is 2 + 3? Answer with one short sentence. /no_think",
            2 to "What was the code word in my previous message? Answer with only that word. /no_think",
        )) {
            val result = collectTurn(case.id, inference, prompt, turn)
            report(case.id, "turn", "turn" to turn, "tokens" to result.tokens, "elapsedMs" to result.elapsedMs,
                "ttftMs" to result.firstTokenMs, "content" to result.content.take(768), "reasoningBytes" to result.reasoning.length,
                "stop" to inference.getStopReason(), "termination" to stopName(inference.getStopReason()), "deltaMatchesFinal" to (result.content == runner.getContent() && result.reasoning == runner.getReasoning()),
                "remembersWord" to (turn != 2 || result.content.contains("violet", ignoreCase = true)))
            if (inference.getStopReason() == StopReason.EOG) naturalTurns++
            check(result.tokens > 0 && result.content.isNotBlank())
            check(result.content == runner.getContent() && result.reasoning == runner.getReasoning())
            check(inference.getStopReason() != StopReason.ERROR)
        }
        val cancelled = collectTurn(case.id, inference, "List 200 consecutive integers with explanations.", 3, cancelAt = 4)
        report(case.id, "cancel", "tokens" to cancelled.tokens, "stop" to inference.getStopReason())
        check(inference.getStopReason() == StopReason.CANCELLED)
        inference.unloadModel()
        check(inference.getContextLimit() == 0)
        report(case.id, "unload", "context" to inference.getContextLimit())
        val reloaded = inference.loadModel(loadRequest)
        report(case.id, "reload", "state" to reloaded::class.simpleName)
        check(reloaded is ModelLoadResult.Success)
        val recovery = collectTurn(case.id, inference, "Say OK. /no_think", 4)
        check(recovery.tokens > 0 && inference.getStopReason() != StopReason.ERROR)
        inference.unloadModel()
        report(case.id, "complete", "turns" to 2, "naturalTurns" to naturalTurns, "boundedTurns" to (2 - naturalTurns), "cancelVerified" to true, "reloadVerified" to true)
        return Outcome.PASS
    }

    private suspend fun verifyPinned(case: ValidationCase, model: LocalModelEntity): Boolean {
        val koin = KoinPlatform.getKoin()
        val result = koin.get<LocalArtifactIdentityResolver>().resolve(model,
            koin.get<ComponentRepository>().getComponentsForModel(model.modelId))
        val artifact = (result as? ArtifactIdentityResolution.Verified)?.artifact ?: return false
        val component = artifact.components.singleOrNull { it.logicalRole == "model" } ?: return false
        val identity = component.identity
        report(case.id, "verified_identity", "revision" to identity.revision, "file" to identity.path,
            "sha256" to component.contentSha256, "bytes" to component.byteCount)
        return identity.repositoryId == case.repository && identity.revision == case.revision &&
            identity.path == case.file && identity.sizeBytes == case.bytes && component.byteCount == case.bytes &&
            component.contentSha256 == case.sha256 && component.remoteObjectId == "sha256:${case.sha256}"
    }

    private suspend fun collectTurn(id: String, inference: InferenceRepository, prompt: String, turn: Int, cancelAt: Int? = null): Turn =
        withTimeout(120_000L) {
            val started = SystemClock.elapsedRealtime()
            val content = StringBuilder(); val reasoning = StringBuilder()
            var tokens = 0; var first = -1L
            inference.generateResponse(prompt).collect { chunk ->
                if (chunk.contentResync) content.clear()
                if (chunk.reasoningResync) reasoning.clear()
                content.append(chunk.contentDelta); reasoning.append(chunk.reasoningDelta)
                if (chunk.isTokenEvent) {
                    tokens++
                    if (first < 0) { first = SystemClock.elapsedRealtime() - started; report(id, "first_token", "turn" to turn, "elapsedMs" to first) }
                    if (tokens == cancelAt || tokens == 512) inference.cancelGeneration()
                }
                check(content.length + reasoning.length <= 262_144)
            }
            Turn(content.toString(), reasoning.toString(), tokens, SystemClock.elapsedRealtime() - started, first)
        }

    private fun stopName(stop: Int): String = when (stop) {
        StopReason.EOG -> "NATURAL_EOG"
        StopReason.MAX_TOKENS -> "TOKEN_BUDGET"
        StopReason.CONTEXT_FULL -> "CONTEXT_FULL"
        StopReason.CANCELLED -> "CANCELLED"
        StopReason.ERROR -> "ERROR"
        else -> "NONE"
    }
    private fun resolutionName(resolution: InstalledModelLoadResolution): String = when (resolution) {
        is InstalledModelLoadResolution.NotAdmissible -> "NotAdmissible:${resolution.reason.name}"
        is InstalledModelLoadResolution.Rejected -> "Rejected:${resolution.reason.name}"
        else -> resolution::class.simpleName ?: "Unknown"
    }
    @Synchronized
    private fun report(id: String, stage: String, vararg fields: Pair<String, Any?>) {
        if (stage != "outcome") stages[id] = stage
        val json = JSONObject().put("case", id).put("stage", stage)
        fields.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        val line = json.toString()
        evidence.appendText("$line\n")
        sendStatus(0, Bundle().apply { putString("stream", "$line\n") })
    }
    private enum class Outcome { PASS, FAILED, NOT_RUN }
    private data class Turn(val content: String, val reasoning: String, val tokens: Int, val elapsedMs: Long, val firstTokenMs: Long)
}
private data class ValidationCase(val id: String, val repository: String, val revision: String, val file: String, val sha256: String, val bytes: Long)

private val VALIDATION_CASES = listOf(
    ValidationCase("smollm2-135m", "bartowski/SmolLM2-135M-Instruct-GGUF", "09816acd5d99df7be770d85ea30822623dab342c", "SmolLM2-135M-Instruct-Q4_K_M.gguf", "2e8040ceae7815abe0dcb3540b9995eaa1fa0d2ca9e797d0a635ae4433c68c2d", 105454432L),
    ValidationCase("gemma3-270m", "bartowski/google_gemma-3-270m-it-GGUF", "d127a4e2c6ed47fdf409a956867b604c040432f9", "google_gemma-3-270m-it-Q4_K_M.gguf", "c866c9f113f2e9aa2225c5997ede437392b8fa844ba5db9e4c77e315ffe20800", 253115168L),
    ValidationCase("qwen3-0.6b", "Qwen/Qwen3-0.6B-GGUF", "23749fefcc72300e3a2ad315e1317431b06b590a", "Qwen3-0.6B-Q8_0.gguf", "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031", 639446688L),
    ValidationCase("lfm2-350m", "unsloth/LFM2-350M-GGUF", "7ed6733192873777637b34cf3c8ba0d8380d931e", "LFM2-350M-Q4_K_M.gguf", "15ded463c01b6b6f6fe6a5f8ea6b87902aef9f7191bcc9c110c5591fe2f69282", 229309152L),
    ValidationCase("qwen2.5-0.5b", "Qwen/Qwen2.5-0.5B-Instruct-GGUF", "9217f5db79a29953eb74d5343926648285ec7e67", "qwen2.5-0.5b-instruct-q4_k_m.gguf", "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db", 491400032L),
    ValidationCase("tinyllama-1.1b", "TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF", "52e7645ba7c309695bec7ac98f4f005b139cf465", "tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf", "9fecc3b3cd76bba89d504f29b616eedf7da85b96540e490ca5824d3f7d2776a0", 668788096L),
    ValidationCase("llama3.2-1b", "bartowski/Llama-3.2-1B-Instruct-GGUF", "067b946cf014b7c697f3654f621d577a3e3afd1c", "Llama-3.2-1B-Instruct-Q4_K_M.gguf", "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83", 807694464L),
    ValidationCase("stablelm2-zephyr-1.6b", "stabilityai/stablelm-2-zephyr-1_6b", "2f275b1127d59fc31e4f7c7426d528768ada9ea4", "stablelm-2-zephyr-1_6b-Q4_0.gguf", "342f89616e40d79e57ff213d68fcdcab18a94f7370c1fb9526ff57f3e93721e3", 982781952L),
    ValidationCase("gemma2-2b", "bartowski/gemma-2-2b-it-GGUF", "855f67caed130e1befc571b52bd181be2e858883", "gemma-2-2b-it-Q4_K_M.gguf", "e0aee85060f168f0f2d8473d7ea41ce2f3230c1bc1374847505ea599288a7787", 1708582752L),
    ValidationCase("phi2-2.7b", "TheBloke/phi-2-GGUF", "5a454d977c6438bb9fb2df233c8ca70f21c87420", "phi-2.Q4_K_M.gguf", "324356668fa5ba9f4135de348447bb2bbe2467eaa1b8fcfb53719de62fbd2499", 1789239136L),
    ValidationCase("gemma3-1b", "bartowski/google_gemma-3-1b-it-GGUF", "116f76234503685a98f572982177b11d44ec8ff1", "google_gemma-3-1b-it-Q4_K_M.gguf", "12bf0fff8815d5f73a3c9b586bd8fee8e7b248c935de70dec367679873d0f29d", 806058496L),
    ValidationCase("smollm2-360m", "bartowski/SmolLM2-360M-Instruct-GGUF", "7be6f65f1db715fe5dc5a4634c0d459b4eed42ec", "SmolLM2-360M-Instruct-Q4_K_M.gguf", "2fa3f013dcdd7b99f9b237717fa0b12d75bbb89984cc1274be1471a465bac9c2", 270590880L),
    ValidationCase("qwen2.5-1.5b", "Qwen/Qwen2.5-1.5B-Instruct-GGUF", "91cad51170dc346986eccefdc2dd33a9da36ead9", "qwen2.5-1.5b-instruct-q4_k_m.gguf", "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e", 1117320736L),
    ValidationCase("qwen3-1.7b", "bartowski/Qwen_Qwen3-1.7B-GGUF", "dcb19155b962dbb6389f4691a982043a8e651022", "Qwen_Qwen3-1.7B-Q4_K_M.gguf", "72c5c3cb38fa32d5256e2fe30d03e7a64c6c79e668ad84057e3bd66e250b24fb", 1282439584L),
    ValidationCase("rwkv7-0.4b", "mradermacher/rwkv7-0.4B-world-GGUF", "44853b80d71a85105de94499bbdc5da1c08054b9", "rwkv7-0.4B-world.Q4_K_M.gguf", "25a6de3e6c99d36540b844752435256d97e082d92b389e51fcdef2deee3c88c0", 300695456L),
    ValidationCase("gpt2-medium", "mradermacher/gpt2-medium-GGUF", "3b9897d67a84e967fbcc8d7de3db4c797e386740", "gpt2-medium.Q4_K_M.gguf", "e17361859dbb9dec30c7fadc9f0a3c90b66a6bba0b777a446c37fd8e777b9a5c", 270710816L),
    ValidationCase("openelm-450m", "RichardErkhov/apple_-_OpenELM-450M-Instruct-gguf", "c1d461967f476e5bbb9a1a78d994b54edb84af6c", "OpenELM-450M-Instruct.Q4_K_M.gguf", "9c2968d7069d874cbdbd041a0dfdc510f7c759a974bb8f38493a9485db87abdc", 289467168L),
    ValidationCase("pythia-160m", "QuantFactory/pythia-160m-GGUF", "498f1c9f4c3b090e477a96ad65854d92b65e239c", "pythia-160m.Q4_K_M.gguf", "5f55bef2787f1e4b0ce46d441f42e6f302dc92a60d9ef20b0e3972942545b2fe", 109764928L),
    ValidationCase("falconh1-0.5b", "tiiuae/Falcon-H1-0.5B-Instruct-GGUF", "9bf0c2d4391cf4850aa62bfee1d8fe71afba8be2", "Falcon-H1-0.5B-Instruct-Q4_K_M.gguf", "138a37a94b9e313af4e22d4af46b8119b76a31afdd61cabbeae7010ae45d2ac6", 314806560L),
    ValidationCase("bloom-560m", "QuantFactory/bloom-560m-GGUF", "d66e7d3f402b0bfaf15de9aab775880ee09eb229", "bloom-560m.Q4_K_M.gguf", "e4cd38c73b7abb7938982f6dc9419d289aa2fa9abdf1039790b07ff82eb0b9c9", 561445696L),
    ValidationCase("olmo-1b", "mradermacher/OLMo-1B-0724-hf-GGUF", "d24d020c6d8fd24cc2129be48b5078ad9c6bef11", "OLMo-1B-0724-hf.Q4_K_M.gguf", "12ad05f4747e091ec28afa0457a67cc88e6646cc393418d0761db0b362a3e7bc", 791470976L),
    ValidationCase("bitnet-2b", "microsoft/bitnet-b1.58-2B-4T-gguf", "a1f2f1c765812aa8af3f6eda4a313707064bba15", "ggml-model-i2_s.gguf", "4221b252fdd5fd25e15847adfeb5ee88886506ba50b8a34548374492884c2162", 1187801280L),
)

private val NEW_VALIDATION_CASE_IDS = setOf("gemma3-1b", "smollm2-360m", "qwen2.5-1.5b", "qwen3-1.7b", "rwkv7-0.4b", "gpt2-medium", "openelm-450m", "pythia-160m", "falconh1-0.5b", "bloom-560m", "olmo-1b", "bitnet-2b")
