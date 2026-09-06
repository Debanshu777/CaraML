package com.debanshu777.huggingfacemanager.download

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.HashingSink
import okio.Path
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
): Flow<DownloadProgressDTO> = flow {
    val request = validateDownloadArguments(modelId, path, metadata)
    val identity = metadata.artifact

    ModelRootLocks.withLock(modelRoot.toString()) {
        val fileSystem = FileSystem.SYSTEM
        val manifestStore = ArtifactManifestStore(modelRoot, fileSystem)
        manifestStore.recover()
        fileSystem.createDirectories(target.parent ?: throw IllegalArgumentException("Invalid model file path"))
        val temporary = target.siblingPart()
        require(canonicalParentIsInsideRoot(fileSystem, modelRoot, target)) { "Invalid model file path" }
        require(fileSystem.metadataOrNull(target)?.symlinkTarget == null) { "Invalid model file path" }
        require(fileSystem.metadataOrNull(temporary)?.symlinkTarget == null) { "Invalid model file path" }
        fileSystem.delete(temporary, mustExist = false)
        val availableBytes = pathProvider.getAvailableStorageBytes()
        if (availableBytes < identity.expectedBytes) {
            throw InsufficientStorageException(identity.expectedBytes, availableBytes)
        }
        val url = URLBuilder(baseUrl).apply {
            appendPathSegments(identity.repositoryId.split('/'), encodeSlash = true)
            appendPathSegments("resolve", identity.immutableRevision)
            appendPathSegments(identity.relativePath.split('/'), encodeSlash = true)
            parameters.append("download", "true")
        }.build()

        try {
            httpClient.prepareGet(url).execute { response ->
                if (response.status.value !in 200..299) throw DownloadHttpException(response.status.value)
                val responseLength = response.headers["Content-Length"]?.toLongOrNull()?.takeIf { it > 0L }
                if (responseLength != null && responseLength != identity.expectedBytes) {
                    throw IncompleteDownloadException(responseLength, identity.expectedBytes)
                }
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesReceived = 0L
                val progressTracker = DownloadProgressTracker(identity.expectedBytes)
                val hashingSink = HashingSink.sha256(fileSystem.sink(temporary, mustCreate = true))
                val sink = hashingSink.buffer()
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
                val handle = fileSystem.openReadWrite(temporary, mustCreate = false, mustExist = true)
                try {
                    handle.flush()
                } finally {
                    handle.close()
                }
                if (bytesReceived == 0L) throw EmptyDownloadException()
                if (bytesReceived != identity.expectedBytes) {
                    throw IncompleteDownloadException(bytesReceived, identity.expectedBytes)
                }
                val contentSha256 = hashingSink.hash.hex()
                if (identity.expectedSha256OrNull()?.let { it != contentSha256 } == true) {
                    throw ArtifactVerificationException()
                }
                val entry = ArtifactManifestEntry.create(
                    logicalRole = metadata.logicalRole,
                    identity = identity,
                    byteCount = bytesReceived,
                    contentSha256 = contentSha256,
                ) ?: throw ArtifactVerificationException()
                manifestStore.commit(identity.relativePath, entry)
                check(!fileSystem.exists(modelRoot / ArtifactManifestStore.JOURNAL_FILE_NAME)) {
                    "Artifact transaction did not complete"
                }
                emit(
                    DownloadProgressDTO(
                        bytesReceived = bytesReceived,
                        contentLength = identity.expectedBytes,
                        percentage = 100f,
                        localPath = localPath,
                        contentSha256 = contentSha256,
                    ),
                )
            }
        } catch (error: CancellationException) {
            recoverOrDiscard(manifestStore, fileSystem, modelRoot, temporary)
            throw error
        } catch (error: Exception) {
            recoverOrDiscard(manifestStore, fileSystem, modelRoot, temporary)
            throw error
        }
    }
}

private fun recoverOrDiscard(
    store: ArtifactManifestStore,
    fileSystem: FileSystem,
    root: Path,
    temporary: Path,
) {
    if (fileSystem.exists(root / ArtifactManifestStore.JOURNAL_FILE_NAME)) {
        runCatching(store::recover)
    } else {
        fileSystem.delete(temporary, mustExist = false)
    }
}

private object ModelRootLocks {
    private data class Entry(val mutex: Mutex, var references: Int)

    private val guard = Mutex()
    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> withLock(key: String, block: suspend () -> T): T {
        val entry = guard.withLock {
            entries.getOrPut(key) { Entry(Mutex(), 0) }.also { it.references++ }
        }
        return try {
            entry.mutex.withLock { block() }
        } finally {
            guard.withLock {
                entry.references--
                if (entry.references == 0 && entries[key] === entry) entries.remove(key)
            }
        }
    }
}

private const val BUFFER_SIZE = 256 * 1024
private fun Path.siblingPart(): Path = parent!! / "$name.part"

private fun canonicalParentIsInsideRoot(fileSystem: FileSystem, root: Path, target: Path): Boolean {
    val canonicalRoot = runCatching { fileSystem.canonicalize(root) }.getOrNull() ?: return false
    val canonicalParent = runCatching { fileSystem.canonicalize(target.parent!!) }.getOrNull() ?: return false
    return canonicalParent == canonicalRoot || canonicalParent.toString().startsWith("$canonicalRoot/")
}
