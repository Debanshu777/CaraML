package com.debanshu777.caraml.core.download

import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.storage.EncodedModelEvidence
import com.debanshu777.caraml.core.recommendation.storage.PersistedModelEvidenceCodec
import com.debanshu777.caraml.core.storage.catalog.InstalledCatalogRecord
import com.debanshu777.caraml.core.storage.catalog.InstalledModelCatalogDao
import com.debanshu777.caraml.core.storage.component.DownloadedComponentEntity
import com.debanshu777.caraml.core.storage.evidence.toEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.huggingfacemanager.download.ArtifactVerificationException
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadManager
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.model.DIFFUSERS_BUNDLE_DB_FILENAME
import kotlin.time.Clock

interface BundlePublisher {
    suspend fun publish(ownerModelId: String, artifacts: List<DownloadMetadataDTO>): Boolean
    suspend fun validate(ownerModelId: String, artifacts: List<DownloadMetadataDTO>): Boolean
}

class DownloadManagerBundlePublisher(
    private val downloadManager: DownloadManager,
) : BundlePublisher {
    override suspend fun publish(ownerModelId: String, artifacts: List<DownloadMetadataDTO>): Boolean =
        downloadManager.publishBundle(ownerModelId, artifacts)

    override suspend fun validate(ownerModelId: String, artifacts: List<DownloadMetadataDTO>): Boolean =
        downloadManager.validateBundle(ownerModelId, artifacts)
}

fun interface ModelCatalogPublisher {
    suspend fun publish(batch: DownloadBatchSnapshot, evidence: EncodedModelEvidence)
}

class RepositoryModelCatalogPublisher(
    private val catalog: InstalledModelCatalogDao,
    private val paths: StoragePathProvider,
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ModelCatalogPublisher {
    override suspend fun publish(batch: DownloadBatchSnapshot, evidence: EncodedModelEvidence) {
        require(evidence == batch.evidence) { "Catalog evidence does not belong to the download batch" }
        val primary = batch.artifacts.filter { it.request.primary }
        require(primary.isNotEmpty())
        val representative = primary.singleOrNull { it.request.metadata.logicalRole == "model" }
            ?: primary.singleOrNull()
            ?: primary.first()
        val metadata = representative.request.metadata
        val directoryBundle = primary.size > 1
        val publishedAt = nowEpochMs()
        catalog.replaceReady(
            InstalledCatalogRecord(
                model = LocalModelEntity(
                    modelId = batch.ownerModelId,
                    filename = if (directoryBundle) {
                        DIFFUSERS_BUNDLE_DB_FILENAME
                    } else {
                        metadata.destinationRelativePath.substringAfterLast('/')
                    },
                    localPath = if (directoryBundle) {
                        paths.getModelsStorageDirectory(batch.ownerModelId)
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
                    )
                },
                evidence = evidence.toEntity(batch.ownerModelId, publishedAt),
            ),
        )
    }

    private fun localPath(metadata: DownloadMetadataDTO): String =
        "${paths.getModelsStorageDirectory(metadata.artifact.repositoryId)}/${metadata.destinationRelativePath}"
}

class ModelDownloadFinalizer(
    private val store: DownloadTaskStore,
    private val bundlePublisher: BundlePublisher,
    private val catalogPublisher: ModelCatalogPublisher,
    private val evidenceCodec: PersistedModelEvidenceCodec = PersistedModelEvidenceCodec(),
) : BatchFinalizer {
    override suspend fun finalize(batchId: String) {
        val batch = store.getBatch(batchId) ?: throw ArtifactVerificationException()
        val artifacts = batch.artifacts.map { it.request.metadata }
        if (!bundlePublisher.publish(batch.ownerModelId, artifacts) ||
            !bundlePublisher.validate(batch.ownerModelId, artifacts)
        ) {
            throw ArtifactVerificationException()
        }
        val evidence = validateEvidence(batch)
        catalogPublisher.publish(batch, evidence)
    }

    private fun validateEvidence(batch: DownloadBatchSnapshot): EncodedModelEvidence {
        val decoded = try {
            evidenceCodec.decode(batch.evidence)
        } catch (_: IllegalArgumentException) {
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

private val modelIdentityOrder = compareBy<ModelFileIdentity>({ it.repositoryId }, { it.revision.lowercase() }, { it.path })

private val downloadIdentityOrder = compareBy<DownloadArtifactIdentity>(
    { it.repositoryId },
    { it.immutableRevision.lowercase() },
    { it.relativePath },
)

private fun ModelFileIdentity.matches(artifact: DownloadArtifactIdentity): Boolean {
    val remoteObjectId = artifact.remoteObjectId ?: return false
    return repositoryId == artifact.repositoryId &&
        revision.equals(artifact.immutableRevision, ignoreCase = true) &&
        path == artifact.relativePath &&
        sizeBytes == artifact.expectedBytes &&
        canonicalObjectIds().any { it.equals(remoteObjectId, ignoreCase = true) }
}

private fun ModelFileIdentity.canonicalObjectIds(): List<String> = buildList(3) {
    gitOid?.let(::add)
    lfsOid?.let { add(if (it.startsWith("sha256:")) it else "sha256:$it") }
    xetHash?.let(::add)
}
