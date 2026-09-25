package com.debanshu777.caraml.core.download

import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.canonicalDownloadRemoteObjectId
import com.debanshu777.caraml.core.recommendation.storage.EncodedModelEvidence
import com.debanshu777.caraml.core.recommendation.storage.PersistedModelEvidenceCodec
import com.debanshu777.caraml.core.storage.catalog.InstalledCatalogRecord
import com.debanshu777.caraml.core.storage.catalog.InstalledCatalogSnapshot
import com.debanshu777.caraml.core.storage.catalog.InstalledModelCatalogDao
import com.debanshu777.caraml.core.storage.catalog.InstalledModelPublicationCoordinator
import com.debanshu777.caraml.core.storage.catalog.artifactStorageCoordinationKey
import com.debanshu777.caraml.core.storage.component.DownloadedComponentEntity
import com.debanshu777.caraml.core.storage.evidence.toEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.huggingfacemanager.download.ArtifactVerificationException
import com.debanshu777.huggingfacemanager.download.ArtifactManifest
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadManager
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.download.deleteValidatedArtifactEntries
import com.debanshu777.huggingfacemanager.download.persistedArtifactStorageLocation
import com.debanshu777.huggingfacemanager.model.DIFFUSERS_BUNDLE_DB_FILENAME
import kotlin.time.Clock

interface BundlePublisher {
    suspend fun publish(ownerModelId: String, artifacts: List<DownloadMetadataDTO>): Boolean
    suspend fun validate(ownerModelId: String, artifacts: List<DownloadMetadataDTO>): Boolean
    suspend fun pendingReplacement(ownerModelId: String, artifacts: List<DownloadMetadataDTO>): ArtifactManifest? = null
    suspend fun acknowledgeReplacement(ownerModelId: String, artifacts: List<DownloadMetadataDTO>): Boolean = true
    suspend fun current(ownerModelId: String): ArtifactManifest? = null
}

class DownloadManagerBundlePublisher(
    private val downloadManager: DownloadManager,
) : BundlePublisher {
    override suspend fun publish(ownerModelId: String, artifacts: List<DownloadMetadataDTO>): Boolean =
        downloadManager.publishBundle(ownerModelId, artifacts)

    override suspend fun validate(ownerModelId: String, artifacts: List<DownloadMetadataDTO>): Boolean =
        downloadManager.validateBundle(ownerModelId, artifacts)

    override suspend fun pendingReplacement(ownerModelId: String, artifacts: List<DownloadMetadataDTO>) =
        downloadManager.pendingBundleReplacement(ownerModelId, artifacts)

    override suspend fun acknowledgeReplacement(ownerModelId: String, artifacts: List<DownloadMetadataDTO>) =
        downloadManager.acknowledgeBundleReplacement(ownerModelId, artifacts)

    override suspend fun current(ownerModelId: String): ArtifactManifest? =
        downloadManager.validatedBundle(ownerModelId)
}

fun interface ModelCatalogPublisher {
    suspend fun publish(batch: DownloadBatchSnapshot, evidence: EncodedModelEvidence)
    suspend fun snapshot(ownerModelId: String): InstalledCatalogSnapshot? = null
    suspend fun cleanupPrior(previous: ArtifactManifest, current: List<DownloadMetadataDTO>): Boolean = true
}

class RepositoryModelCatalogPublisher(
    private val catalog: InstalledModelCatalogDao,
    private val paths: StoragePathProvider,
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ModelCatalogPublisher {
    override suspend fun snapshot(ownerModelId: String): InstalledCatalogSnapshot? = catalog.snapshotReady(ownerModelId)

    override suspend fun publish(batch: DownloadBatchSnapshot, evidence: EncodedModelEvidence) {
        require(evidence == batch.evidence) { "Catalog evidence does not belong to the download batch" }
        val primary = batch.artifacts.filter { it.request.primary }
        require(primary.isNotEmpty())
        val representative = primary.singleOrNull { it.request.metadata.logicalRole == "model" }
            ?: primary.singleOrNull()
            ?: primary.first()
        val metadata = representative.request.metadata
        val directoryBundle = primary.size > 1
        require(primary.map { it.request.metadata.generationRootRelativePath }.distinct().size == 1) {
            "Primary artifacts span storage generations"
        }
        val publishedAt = nowEpochMs()
        catalog.replaceReady(
            InstalledCatalogRecord(
                model = LocalModelEntity(
                    modelId = batch.ownerModelId,
                    filename = if (directoryBundle) {
                        DIFFUSERS_BUNDLE_DB_FILENAME
                    } else {
                        metadata.layoutRelativePath.substringAfterLast('/')
                    },
                    localPath = if (directoryBundle) {
                        generationRoot(metadata)
                    } else {
                        localPath(metadata)
                    },
                    sizeBytes = primary.sumOf { it.expectedBytes },
                    downloadedAt = publishedAt,
                    author = metadata.author,
                    libraryName = metadata.libraryName,
                    pipelineTag = metadata.pipelineTag,
                    contextLength = metadata.contextLength,
                    modelType = batch.modelType,
                    componentStatus = LocalModelEntity.STATUS_READY,
                    isMainModel = true,
                ),
                components = batch.artifacts.filterNot { it.request.primary }.map { artifact ->
                    val component = artifact.request.metadata
                    DownloadedComponentEntity(
                        repoId = component.artifact.repositoryId,
                        filePath = component.artifact.relativePath,
                        role = component.logicalRole,
                        localPath = localPath(component),
                        sizeBytes = component.sizeBytes,
                        downloadedAt = publishedAt,
                        immutableRevision = component.artifact.immutableRevision,
                        remoteObjectId = requireNotNull(component.artifact.remoteObjectId) {
                            "Catalog component lacks remote identity"
                        },
                        bundleId = component.bundleId,
                        contentSha256 = component.artifact.remoteObjectId
                            ?.removePrefix("sha256:")
                            ?.takeIf { it.length == 64 },
                    )
                },
                evidence = evidence.toEntity(batch.ownerModelId, publishedAt),
            ),
        )
    }

    override suspend fun cleanupPrior(
        previous: ArtifactManifest,
        current: List<DownloadMetadataDTO>,
    ): Boolean {
        val retained = current.mapTo(mutableSetOf()) { metadata ->
            listOf(
                metadata.logicalRole,
                metadata.artifact.repositoryId,
                metadata.artifact.immutableRevision,
                metadata.artifact.relativePath,
                metadata.destinationRelativePath,
                metadata.bundleId,
            )
        }
        val candidates = previous.entries.filterNot { entry ->
            listOf(
                entry.logicalRole,
                entry.identity.repositoryId,
                entry.identity.immutableRevision,
                entry.identity.relativePath,
                entry.localRelativePath,
                entry.bundleId,
            ) in retained
        }
        val unreferenced = candidates.filter { entry ->
            val root = paths.getModelsStorageDirectory(entry.identity.repositoryId).trimEnd('/')
            val target = "$root/${entry.localRelativePath}"
            val generation = persistedArtifactStorageLocation(
                entry.identity,
                entry.bundleId,
                entry.localRelativePath,
            )?.generationRootRelativePath?.let { "$root/$it" }
            catalog.countCatalogStorageReferences(target) == 0L &&
                (generation == null || catalog.countCatalogStorageReferences(generation) == 0L)
        }
        return unreferenced.isEmpty() || deleteValidatedArtifactEntries(paths, unreferenced)
    }

    private fun localPath(metadata: DownloadMetadataDTO): String =
        "${paths.getModelsStorageDirectory(metadata.artifact.repositoryId)}/${metadata.destinationRelativePath}"

    private fun generationRoot(metadata: DownloadMetadataDTO): String =
        metadata.generationRootRelativePath?.let { relative ->
            "${paths.getModelsStorageDirectory(metadata.artifact.repositoryId)}/$relative"
        } ?: paths.getModelsStorageDirectory(metadata.artifact.repositoryId)
}

class ModelDownloadFinalizer(
    private val store: DownloadTaskStore,
    private val bundlePublisher: BundlePublisher,
    private val catalogPublisher: ModelCatalogPublisher,
    private val publicationCoordinator: InstalledModelPublicationCoordinator,
    private val evidenceCodec: PersistedModelEvidenceCodec = PersistedModelEvidenceCodec(),
) : BatchFinalizer {
    override suspend fun finalize(batchId: String) {
        val batch = store.getBatch(batchId) ?: throw ArtifactVerificationException()
        val evidence = validateEvidence(batch)
        val artifacts = batch.artifacts.map { it.request.metadata }
        if (artifacts.any { !it.usesImmutableStorageLayout }) throw ArtifactVerificationException()
        val observedBundle = bundlePublisher.current(batch.ownerModelId)
        val pendingBundle = bundlePublisher.pendingReplacement(batch.ownerModelId, artifacts)
        val storageKeys = (artifacts.map { metadata ->
            artifactStorageCoordinationKey(
                metadata.artifact.repositoryId,
                metadata.destinationRelativePath,
            )
        } + (observedBundle?.entries.orEmpty() + pendingBundle?.entries.orEmpty()).map { entry ->
            artifactStorageCoordinationKey(entry.identity.repositoryId, entry.localRelativePath)
        }).distinct()
        publicationCoordinator.withArtifactPublication(batch.ownerModelId, storageKeys) {
            catalogPublisher.snapshot(batch.ownerModelId)
            if (!bundlePublisher.publish(batch.ownerModelId, artifacts) ||
                !bundlePublisher.validate(batch.ownerModelId, artifacts)
            ) {
                throw ArtifactVerificationException()
            }
            val previous = bundlePublisher.pendingReplacement(batch.ownerModelId, artifacts)
            catalogPublisher.publish(batch, evidence)
            if (previous != null && !catalogPublisher.cleanupPrior(previous, artifacts)) {
                throw ReplacementCleanupException()
            }
            if (!bundlePublisher.acknowledgeReplacement(batch.ownerModelId, artifacts)) {
                throw ReplacementCleanupException()
            }
        }
    }

    private fun validateEvidence(batch: DownloadBatchSnapshot): EncodedModelEvidence {
        val decoded = try {
            evidenceCodec.decode(batch.evidence)
        } catch (_: IllegalArgumentException) {
            throw ArtifactVerificationException()
        }
        val primary = batch.artifacts.filter { it.request.primary }
        if (primary.isEmpty() || primary.any { it.request.metadata.artifact.repositoryId != batch.ownerModelId } ||
            decoded.descriptor?.repositoryId?.let { it != batch.ownerModelId } == true
        ) {
            throw ArtifactVerificationException()
        }
        val expected = decoded.artifactIdentities.sortedWith(modelIdentityOrder)
        val actual = batch.artifacts
            .map { it.request.metadata.artifact }
            .sortedWith(downloadIdentityOrder)
        if (expected.size != actual.size || expected.zip(actual).any { (left, right) -> !left.matches(right) }) {
            throw ArtifactVerificationException()
        }
        return batch.evidence
    }
}

class ReplacementCleanupException : Exception("Previous model revision cleanup is pending")

private val modelIdentityOrder = compareBy<ModelFileIdentity>({ it.repositoryId }, { it.revision.lowercase() }, { it.path })

private val downloadIdentityOrder = compareBy<DownloadArtifactIdentity>(
    { it.repositoryId },
    { it.immutableRevision.lowercase() },
    { it.relativePath },
)

private fun ModelFileIdentity.matches(artifact: DownloadArtifactIdentity): Boolean {
    val remoteObjectId = artifact.remoteObjectId ?: return false
    val canonicalRemoteObjectId = canonicalDownloadRemoteObjectId() ?: return false
    return repositoryId == artifact.repositoryId &&
        revision.equals(artifact.immutableRevision, ignoreCase = true) &&
        path == artifact.relativePath &&
        sizeBytes == artifact.expectedBytes &&
        canonicalRemoteObjectId.equals(remoteObjectId, ignoreCase = true)
}
