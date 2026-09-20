package com.debanshu777.huggingfacemanager.download

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

internal fun downloadArtifact(
    httpClient: HttpClient,
    pathProvider: StoragePathProvider,
    baseUrl: String,
    modelId: String,
    path: String,
    metadata: DownloadMetadataDTO,
    modelRoot: Path,
    target: Path,
    localPath: String,
    resumeMetadata: DownloadResumeMetadata?,
): Flow<DownloadProgressDTO> = flow {
    requireImmutableArtifactWriteMetadata(metadata)
    val request = validateDownloadArguments(modelId, path, metadata)
    val identity = metadata.artifact

    ArtifactRootLockCoordinator.withRoots(listOf(modelRoot.toString())) {
        val destination = metadata.destinationRelativePath
        val expectedTarget = (modelRoot / destination).normalized()
        require(target.normalized() == expectedTarget) { "Invalid model file path" }
        val manifestStore = ArtifactManifestStore(modelRoot)

        var preserveCheckpoint = false
        try {
            if (manifestStore.recover() == ArtifactManifestRecoveryResult.QUARANTINED) {
                throw ArtifactVerificationException()
            }
            val stagedBytes = manifestStore.stagedSize(destination)
            val requestedResume = resumeMetadata?.takeIf {
                it.bytesReceived < identity.expectedBytes && stagedBytes == it.bytesReceived
            }
            if (requestedResume == null && stagedBytes != null) manifestStore.discardStaged(destination)
            val requestedOffset = requestedResume?.bytesReceived ?: 0L
            val availableBytes = pathProvider.getAvailableStorageBytes()
            val remainingBytes = identity.expectedBytes - requestedOffset
            if (availableBytes < remainingBytes) {
                throw InsufficientStorageException(remainingBytes, availableBytes)
            }
            val url = URLBuilder(buildArtifactDownloadUrl(identity, baseUrl)).build()
            httpClient.prepareGet(url) {
                if (requestedResume != null) {
                    headers {
                        append(HttpHeaders.Range, "bytes=$requestedOffset-")
                        append(HttpHeaders.IfRange, requestedResume.entityTag ?: requireNotNull(requestedResume.lastModified))
                    }
                }
            }.execute { response ->
                val status = response.status.value
                if (status !in 200..299) throw DownloadHttpException(status)
                val responseEntityTag = response.headers[HttpHeaders.ETag]
                val responseLastModified = response.headers[HttpHeaders.LastModified]
                val responseLength = response.headers["Content-Length"]?.toLongOrNull()?.takeIf { it > 0L }
                val append = requestedResume != null && status == 206
                val startOffset = if (append) {
                    val range = ContentRange.parse(response.headers[HttpHeaders.ContentRange])
                        ?: throw IncompleteDownloadException(requestedOffset, identity.expectedBytes)
                    if (range.start != requestedOffset || range.total != identity.expectedBytes ||
                        range.endInclusive - range.start + 1L != responseLength ||
                        requestedResume.entityTag != null && responseEntityTag != null &&
                        requestedResume.entityTag != responseEntityTag
                    ) {
                        throw IncompleteDownloadException(requestedOffset, identity.expectedBytes)
                    }
                    requestedOffset
                } else {
                    if (requestedResume != null && status != 200) throw DownloadHttpException(status)
                    0L
                }
                val expectedResponseBytes = identity.expectedBytes - startOffset
                if (responseLength != null && responseLength != expectedResponseBytes) {
                    throw IncompleteDownloadException(responseLength, expectedResponseBytes)
                }
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesReceived = startOffset
                val progressTracker = DownloadProgressTracker(identity.expectedBytes)
                val sink = manifestStore.prepareStaged(destination, startOffset).buffer()
                preserveCheckpoint = responseEntityTag != null || responseLastModified != null || requestedResume != null
                try {
                    while (true) {
                        val count = channel.readAvailable(buffer)
                        if (count <= 0) break
                        if (count.toLong() > identity.expectedBytes - bytesReceived) {
                            throw IncompleteDownloadException(identity.expectedBytes + 1L, identity.expectedBytes)
                        }
                        sink.write(buffer, 0, count)
                        bytesReceived += count
                        progressTracker.next(bytesReceived)?.let { emit(it) }
                    }
                    sink.flush()
                } finally {
                    sink.close()
                }
                manifestStore.syncStaged(destination)
                if (bytesReceived == 0L) throw EmptyDownloadException()
                if (bytesReceived != identity.expectedBytes) {
                    throw IncompleteDownloadException(bytesReceived, identity.expectedBytes)
                }
                val contentSha256 = manifestStore.stagedSha256(destination, identity.expectedBytes)
                    ?: throw ArtifactVerificationException()
                if (identity.expectedSha256OrNull()?.let { it != contentSha256 } == true) {
                    throw ArtifactVerificationException()
                }
                val entry = ArtifactManifestEntry.create(
                    logicalRole = metadata.logicalRole,
                    identity = identity,
                    byteCount = bytesReceived,
                    contentSha256 = contentSha256,
                    bundleId = metadata.bundleId,
                    localRelativePath = destination,
                    layoutRelativePath = metadata.layoutRelativePath,
                ) ?: throw ArtifactVerificationException()
                manifestStore.commit(destination, entry)
                check(!manifestStore.hasPendingTransaction()) {
                    "Artifact transaction did not complete"
                }
                manifestStore.revalidateRoot()
                emit(
                    DownloadProgressDTO(
                        bytesReceived = bytesReceived,
                        contentLength = identity.expectedBytes,
                        percentage = 100f,
                        localPath = localPath,
                        contentSha256 = contentSha256,
                        entityTag = responseEntityTag ?: requestedResume?.entityTag,
                        lastModified = responseLastModified ?: requestedResume?.lastModified,
                    ),
                )
            }
        } catch (error: CancellationException) {
            recoverOrDiscard(manifestStore, destination, preserveCheckpoint)
            throw error
        } catch (error: Exception) {
            recoverOrDiscard(manifestStore, destination, preserveCheckpoint)
            throw error
        } finally {
            manifestStore.close()
        }
    }
}

/** Builds the only remote URL shape accepted by platform download drivers. */
fun artifactDownloadUrl(
    identity: DownloadArtifactIdentity,
): String {
    val baseUrl = com.debanshu777.huggingfacemanager.HuggingFaceConstants.DEFAULT_BASE_URL
    val base = URLBuilder(baseUrl).build()
    check(base.protocol.name == "https" && base.host == "huggingface.co") { "Invalid download host" }
    return buildArtifactDownloadUrl(identity, baseUrl)
}

private fun buildArtifactDownloadUrl(identity: DownloadArtifactIdentity, baseUrl: String): String {
    validateDownloadRequest(identity.repositoryId, identity.relativePath)
    require(identity.immutableRevision.isNotBlank()) { "Invalid immutable revision" }
    val base = URLBuilder(baseUrl).build()
    return URLBuilder(base).apply {
        appendPathSegments(identity.repositoryId.split('/'), encodeSlash = true)
        appendPathSegments("resolve", identity.immutableRevision)
        appendPathSegments(identity.relativePath.split('/'), encodeSlash = true)
        parameters.append("download", "true")
    }.buildString()
}

private fun recoverOrDiscard(
    store: ArtifactManifestStore,
    relativePath: String,
    preserveCheckpoint: Boolean,
) {
    if (store.hasPendingTransaction()) {
        runCatching(store::recover)
    } else if (preserveCheckpoint) {
        runCatching { store.syncStaged(relativePath) }
    } else {
        runCatching { store.discardStaged(relativePath) }
    }
}

internal suspend fun discardArtifactCheckpoint(
    pathProvider: StoragePathProvider,
    metadata: DownloadMetadataDTO,
) {
    requireImmutableArtifactWriteMetadata(metadata)
    val identity = metadata.artifact
    validateDownloadArguments(identity.repositoryId, identity.relativePath, metadata)
    val modelRoot = pathProvider.getModelsStorageDirectory(identity.repositoryId).toPath(normalize = true)
    ArtifactRootLockCoordinator.withRoots(listOf(modelRoot.toString())) {
        val store = ArtifactManifestStore(modelRoot)
        try {
            if (store.recover() == ArtifactManifestRecoveryResult.QUARANTINED) {
                throw ArtifactVerificationException()
            }
            store.discardStaged(metadata.destinationRelativePath)
        } finally {
            store.close()
        }
    }
}

internal suspend fun isArtifactPublished(
    pathProvider: StoragePathProvider,
    metadata: DownloadMetadataDTO,
): Boolean {
    val identity = metadata.artifact
    validateDownloadArguments(identity.repositoryId, identity.relativePath, metadata)
    val manifest = readValidatedArtifactManifest(pathProvider, identity.repositoryId) ?: return false
    return manifest.entries.singleOrNull { entry ->
        entry.logicalRole == metadata.logicalRole &&
            entry.identity == identity &&
            entry.bundleId == metadata.bundleId &&
            entry.localRelativePath == metadata.destinationRelativePath &&
            entry.byteCount == identity.expectedBytes
    } != null
}

private const val BUFFER_SIZE = 256 * 1024
