package com.debanshu777.caraml.core.download

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.debanshu777.caraml.core.platform.AppLogger
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.canonicalDownloadRemoteObjectId
import com.debanshu777.caraml.core.recommendation.storage.EncodedModelEvidence
import com.debanshu777.caraml.core.recommendation.storage.InstalledEvidenceState
import com.debanshu777.caraml.core.recommendation.storage.PersistedModelEvidenceCodec
import com.debanshu777.caraml.core.storage.catalog.InstalledModelPublicationCoordinator
import com.debanshu777.caraml.core.storage.catalog.artifactStorageCoordinationKey
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One exact durable observation, including the monotonic command version. */
data class PublishedDownloadSnapshot(val batch: DownloadBatchSnapshot, val observedVersion: Long)

/** Last raw key advances the scan even when a persisted row cannot be decoded safely. */
data class PublishedDownloadScanPage(val candidates: List<PublishedDownloadSnapshot>, val lastScannedBatchId: String?)

/** Repairs status only after proving that the exact bytes are already installed. */
class PublishedDownloadReconciler(
    private val store: DownloadTaskStore,
    private val bundlePublisher: BundlePublisher,
    private val catalogPublisher: ModelCatalogPublisher,
    private val paths: StoragePathProvider,
    private val publicationCoordinator: InstalledModelPublicationCoordinator,
    private val scheduler: PlatformDownloadScheduler,
    private val clock: () -> Long,
    private val cursorPreferences: DataStore<Preferences>? = null,
) {
    private val codec = PersistedModelEvidenceCodec()
    private val scanMutex = Mutex()
    private var memoryCursor: String? = null
    private val cursorKey = stringPreferencesKey("published_download_scan_batch_id_v1")

    suspend fun reconcile() {
        val page = try {
            selectScanPage()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AppLogger.i("Download") { "stage=published_queue_reconciliation outcome=unchanged reason=SCAN_UNAVAILABLE" }
            return
        }
        // One page and its groups are bounded by Room; identical immutable bytes are hashed once.
        val groups = page.candidates.take(64)
            .filter { it.batch.isPublishedDownloadCandidate() }
            .groupBy { it.batch.ownerModelId to downloadArtifactTaskId(it.batch.artifacts.single().request) }
        groups.values.forEach { observations ->
            try {
                reconcileGroup(observations)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AppLogger.i("Download") { "stage=published_queue_reconciliation outcome=unchanged reason=VALIDATION_FAILED" }
            }
        }
    }

    private suspend fun selectScanPage(): PublishedDownloadScanPage = scanMutex.withLock {
        val cursor = (cursorPreferences?.data?.first()?.get(cursorKey) ?: memoryCursor)
            ?.takeIf(::isOpaqueBatchId)
        var page = store.scanPublishedDownloads(clock(), cursor)
        if (page.lastScannedBatchId == null && cursor != null) {
            page = store.scanPublishedDownloads(clock(), null)
        }
        val next = page.lastScannedBatchId
        check(next == null || isOpaqueBatchId(next)) { "Invalid scan cursor" }
        // Persist before file proof so cancellation/process death cannot monopolize one window.
        cursorPreferences?.edit { preferences ->
            if (next == null) preferences.remove(cursorKey) else preferences[cursorKey] = next
        }
        memoryCursor = next
        page
    }

    private suspend fun reconcileGroup(observations: List<PublishedDownloadSnapshot>) {
        val batch = observations.first().batch
        val metadata = batch.artifacts.single().request.metadata
        val key = artifactStorageCoordinationKey(metadata.artifact.repositoryId, metadata.destinationRelativePath)
        publicationCoordinator.withArtifactPublication(batch.ownerModelId, listOf(key)) {
            if (observations.any { scheduler.isActive(it.batch.batchId) }) return@withArtifactPublication
            // Check cheap catalog identity before scanning the file. Neither catalog nor evidence is rewritten.
            val installed = catalogPublisher.snapshot(batch.ownerModelId) ?: return@withArtifactPublication
            val model = installed.model
            val expectedPath = paths.getModelsStorageDirectory(metadata.artifact.repositoryId).trimEnd('/') +
                "/" + metadata.destinationRelativePath
            if (model.modelId != batch.ownerModelId || !model.isMainModel || model.modelType != batch.modelType ||
                model.componentStatus != LocalModelEntity.STATUS_READY || model.localPath != expectedPath ||
                model.filename != metadata.layoutRelativePath.substringAfterLast('/') ||
                model.sizeBytes != metadata.artifact.expectedBytes || installed.components.isNotEmpty()) return@withArtifactPublication
            val evidence = installed.evidence ?: return@withArtifactPublication
            if (evidence.modelId != batch.ownerModelId) return@withArtifactPublication
            val encoded = EncodedModelEvidence(InstalledEvidenceState.valueOf(evidence.evidenceState),
                evidence.schemaVersion, evidence.payload, evidence.sha256)
            if (!evidenceMatches(encoded, metadata)) return@withArtifactPublication
            val current = bundlePublisher.current(batch.ownerModelId) ?: return@withArtifactPublication
            val entry = current.entries.singleOrNull() ?: return@withArtifactPublication
            val expectedSha = metadata.artifact.remoteObjectId?.removePrefix("sha256:") ?: return@withArtifactPublication
            if (expectedSha.length != 64 || entry.identity != metadata.artifact ||
                entry.logicalRole != metadata.logicalRole || entry.byteCount != metadata.artifact.expectedBytes ||
                !entry.contentSha256.equals(expectedSha, ignoreCase = true) || entry.bundleId != metadata.bundleId ||
                entry.localRelativePath != metadata.destinationRelativePath || entry.layoutRelativePath != metadata.layoutRelativePath) return@withArtifactPublication
            // Room compares the full persisted observation and live leases atomically; PAUSE remains PAUSE.
            observations.forEach { observed ->
                if (evidenceMatches(observed.batch.evidence, metadata)) {
                    val completed = store.completePublishedDownload(observed, clock())
                    AppLogger.i("Download") { "stage=published_queue_reconciliation outcome=${if (completed) "completed" else "unchanged"} reason=${if (completed) "EXACT_INSTALLED_PUBLICATION" else "STALE_OBSERVATION"}" }
                }
            }
        }
    }

    private fun evidenceMatches(encoded: EncodedModelEvidence, metadata: DownloadMetadataDTO): Boolean {
        val decoded = codec.decode(encoded)
        if (decoded.descriptor?.repositoryId?.let { it != metadata.artifact.repositoryId } == true) return false
        return decoded.artifactIdentities.singleOrNull()?.matchesPublication(metadata.artifact) == true
    }
}

internal fun DownloadBatchSnapshot.isPublishedDownloadCandidate(): Boolean {
    val artifact = artifacts.singleOrNull() ?: return false
    return modelType == "text" && userIntent != DownloadUserIntent.CANCEL &&
        (state == DownloadBatchState.VERIFYING || state == DownloadBatchState.FAILED_TERMINAL && failureCode == DownloadFailureCode.INTEGRITY) &&
        artifact.request.primary && artifact.request.metadata.logicalRole == "model" &&
        artifact.request.metadata.artifact.repositoryId == ownerModelId && artifact.request.metadata.usesImmutableStorageLayout &&
        artifact.request.metadata.artifact.relativePath.endsWith(".gguf", ignoreCase = true) &&
        artifact.expectedBytes > 0L && artifact.bytesReceived == artifact.expectedBytes &&
        (artifact.state == DownloadArtifactState.VERIFYING || artifact.state == DownloadArtifactState.FAILED_TERMINAL && artifact.failureCode == DownloadFailureCode.INTEGRITY)
}

private fun ModelFileIdentity.matchesPublication(artifact: DownloadArtifactIdentity): Boolean =
    repositoryId == artifact.repositoryId && revision.equals(artifact.immutableRevision, ignoreCase = true) &&
        path == artifact.relativePath && sizeBytes == artifact.expectedBytes &&
        canonicalDownloadRemoteObjectId()?.equals(artifact.remoteObjectId, ignoreCase = true) == true

private fun isOpaqueBatchId(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
