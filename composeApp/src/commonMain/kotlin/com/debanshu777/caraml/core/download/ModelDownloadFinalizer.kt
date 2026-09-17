package com.debanshu777.caraml.core.download

import com.debanshu777.caraml.core.storage.component.ComponentRepository
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelRepository
import com.debanshu777.huggingfacemanager.download.ArtifactVerificationException
import com.debanshu777.huggingfacemanager.download.DownloadManager
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.model.DIFFUSERS_BUNDLE_DB_FILENAME

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
    suspend fun publish(batch: DownloadBatchSnapshot)
}

class RepositoryModelCatalogPublisher(
    private val localModels: LocalModelRepository,
    private val components: ComponentRepository,
    private val paths: StoragePathProvider,
) : ModelCatalogPublisher {
    override suspend fun publish(batch: DownloadBatchSnapshot) {
        val primary = batch.artifacts.filter { it.request.primary }
        require(primary.isNotEmpty())

        batch.artifacts.filterNot { it.request.primary }.forEach { artifact ->
            val metadata = artifact.request.metadata
            val componentId = components.insertComponent(
                repoId = metadata.artifact.repositoryId,
                filePath = metadata.artifact.relativePath,
                role = metadata.logicalRole,
                localPath = localPath(metadata),
                sizeBytes = metadata.sizeBytes,
            )
            components.linkComponentToModel(batch.ownerModelId, componentId, metadata.logicalRole)
        }

        val representative = primary.singleOrNull { it.request.metadata.logicalRole == "model" }
            ?: primary.singleOrNull()
            ?: primary.first()
        val metadata = representative.request.metadata
        val directoryBundle = primary.size > 1
        localModels.deleteAllForModelId(batch.ownerModelId)
        localModels.insert(
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
            author = metadata.author,
            libraryName = metadata.libraryName,
            pipelineTag = metadata.pipelineTag,
            contextLength = metadata.contextLength,
            modelType = batch.modelType,
            componentStatus = LocalModelEntity.STATUS_READY,
            isMainModel = true,
        )
    }

    private fun localPath(metadata: DownloadMetadataDTO): String =
        "${paths.getModelsStorageDirectory(metadata.artifact.repositoryId)}/${metadata.destinationRelativePath}"
}

class ModelDownloadFinalizer(
    private val store: DownloadTaskStore,
    private val bundlePublisher: BundlePublisher,
    private val catalogPublisher: ModelCatalogPublisher,
) : BatchFinalizer {
    override suspend fun finalize(batchId: String) {
        val batch = store.getBatch(batchId) ?: throw ArtifactVerificationException()
        val artifacts = batch.artifacts.map { it.request.metadata }
        if (!bundlePublisher.publish(batch.ownerModelId, artifacts) ||
            !bundlePublisher.validate(batch.ownerModelId, artifacts)
        ) {
            throw ArtifactVerificationException()
        }
        catalogPublisher.publish(batch)
    }
}
