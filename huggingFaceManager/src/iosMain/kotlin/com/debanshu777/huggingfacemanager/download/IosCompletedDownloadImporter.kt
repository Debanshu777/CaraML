package com.debanshu777.huggingfacemanager.download

import okio.Buffer
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

/** Moves a native URLSession result through CaraML's existing verified publication boundary. */
class IosCompletedDownloadImporter internal constructor(
    private val pathProvider: StoragePathProvider,
    private val manifestStoreFactory: (Path) -> ArtifactManifestStore,
) {
    constructor(pathProvider: StoragePathProvider) : this(
        pathProvider = pathProvider,
        manifestStoreFactory = { modelRoot -> ArtifactManifestStore(modelRoot) },
    )

    suspend fun isPublished(metadata: DownloadMetadataDTO): Boolean =
        isArtifactPublished(pathProvider, metadata)

    suspend fun import(
        modelId: String,
        path: String,
        metadata: DownloadMetadataDTO,
        temporaryFilePath: String,
        response: DownloadResponseProvenance,
    ): DownloadProgressDTO {
        requireImmutableArtifactWriteMetadata(metadata)
        validateDownloadArguments(modelId, path, metadata)
        require(DownloadResponseProvenance.validate(response.origin, response.statusCode) == response) {
            "Unexpected download response"
        }

        val identity = metadata.artifact
        val modelRoot = pathProvider.getModelsStorageDirectory(modelId).toPath(normalize = true)
        val localPath = (modelRoot / metadata.destinationRelativePath).normalized().toString()
        return ArtifactRootLockCoordinator.withRoots(listOf(modelRoot.toString())) {
            val store = manifestStoreFactory(modelRoot)
            var stagedMutationStarted = false
            try {
                if (store.recover() == ArtifactManifestRecoveryResult.QUARANTINED) {
                    throw ArtifactVerificationException()
                }
                stagedMutationStarted = true
                store.discardStaged(metadata.destinationRelativePath)
                val source = secureRegularFileSource(temporaryFilePath, identity.expectedBytes).buffer()
                val sink = store.prepareStaged(metadata.destinationRelativePath).buffer()
                try {
                    val buffer = Buffer()
                    var copied = 0L
                    while (copied <= identity.expectedBytes) {
                        val read = source.read(buffer, minOf(256L * 1024L, identity.expectedBytes + 1L - copied))
                        if (read == -1L) break
                        sink.write(buffer, read)
                        copied += read
                    }
                    if (copied != identity.expectedBytes || !source.exhausted()) {
                        throw ArtifactVerificationException()
                    }
                    sink.flush()
                } finally {
                    runCatching { source.close() }
                    runCatching { sink.close() }
                }
                store.syncStaged(metadata.destinationRelativePath)
                val contentSha256 = store.stagedSha256(metadata.destinationRelativePath, identity.expectedBytes)
                    ?: throw ArtifactVerificationException()
                if (identity.expectedSha256OrNull()?.let { it != contentSha256 } == true) {
                    throw ArtifactVerificationException()
                }
                val entry = ArtifactManifestEntry.create(
                    logicalRole = metadata.logicalRole,
                    identity = identity,
                    byteCount = identity.expectedBytes,
                    contentSha256 = contentSha256,
                    bundleId = metadata.bundleId,
                    localRelativePath = metadata.destinationRelativePath,
                    layoutRelativePath = metadata.layoutRelativePath,
                ) ?: throw ArtifactVerificationException()
                store.commit(metadata.destinationRelativePath, entry)
                check(!store.hasPendingTransaction()) { "Artifact transaction did not complete" }
                store.revalidateRoot()
                DownloadProgressDTO(
                    bytesReceived = identity.expectedBytes,
                    contentLength = identity.expectedBytes,
                    percentage = 100f,
                    localPath = localPath,
                    contentSha256 = contentSha256,
                )
            } catch (error: Exception) {
                if (stagedMutationStarted) {
                    runCatching { store.discardStaged(metadata.destinationRelativePath) }
                }
                throw error
            } finally {
                store.close()
            }
        }
    }

}
