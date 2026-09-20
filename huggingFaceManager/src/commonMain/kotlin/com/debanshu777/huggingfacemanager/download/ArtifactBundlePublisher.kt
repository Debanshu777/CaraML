package com.debanshu777.huggingfacemanager.download

import kotlinx.coroutines.CancellationException
import okio.Path
import okio.Path.Companion.toPath

internal suspend fun publishArtifactBundle(
    pathProvider: StoragePathProvider,
    ownerModelId: String,
    artifacts: List<DownloadMetadataDTO>,
): Boolean = withBundleStore(pathProvider, ownerModelId, artifacts) { store, entries ->
    store.publish(entries)
    store.readValidated()?.bundleDigest == ArtifactManifest.create(entries)?.bundleDigest
}

internal suspend fun validateArtifactBundle(
    pathProvider: StoragePathProvider,
    ownerModelId: String,
    artifacts: List<DownloadMetadataDTO>,
): Boolean = withBundleStore(pathProvider, ownerModelId, artifacts) { store, entries ->
    store.recover()
    store.readValidated()?.bundleDigest == ArtifactManifest.create(entries)?.bundleDigest
}

internal suspend fun readValidatedArtifactBundle(
    pathProvider: StoragePathProvider,
    ownerModelId: String,
): ArtifactManifest? = try {
    val ownerId = validateModelId(ownerModelId)
    val ownerRoot = pathProvider.getModelsStorageDirectory(ownerId).toPath(normalize = true)
    val candidateStore = ArtifactBundleManifestStore(ownerRoot) { true }
    val candidate = try {
        candidateStore.readManifestOnly()
    } finally {
        candidateStore.close()
    } ?: return null
    val roots = candidate.entries.map { metadataRoot(pathProvider, it.identity.repositoryId) } + ownerRoot
    val allowedRoots = roots.mapTo(mutableSetOf(), ::rootKey)
    ArtifactRootLockCoordinator.withRoots(allowedRoots) {
        val store = bundleStore(pathProvider, ownerRoot, allowedRoots)
        try {
            store.recover()
            if (store.readManifestOnly() == candidate) store.readValidated() else null
        } finally {
            store.close()
        }
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

internal suspend fun readValidatedArtifactManifest(
    pathProvider: StoragePathProvider,
    modelId: String,
): ArtifactManifest? = try {
    val root = metadataRoot(pathProvider, validateModelId(modelId))
    ArtifactRootLockCoordinator.withRoots(listOf(rootKey(root))) {
        val store = ArtifactManifestStore(root)
        try {
            store.recover()
            store.readValidated()
        } finally {
            store.close()
        }
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

private suspend fun withBundleStore(
    pathProvider: StoragePathProvider,
    ownerModelId: String,
    artifacts: List<DownloadMetadataDTO>,
    block: (ArtifactBundleManifestStore, List<ArtifactManifestEntry>) -> Boolean,
): Boolean = try {
    val ownerId = validateModelId(ownerModelId)
    if (artifacts.isEmpty() || artifacts.size > 64 || artifacts.map { it.bundleId }.toSet().size != 1) return false
    val ownerRoot = metadataRoot(pathProvider, ownerId)
    val previous = readOwnerManifest(ownerRoot)
    val roots = artifacts.map { metadataRoot(pathProvider, it.artifact.repositoryId) } +
        previous?.entries.orEmpty().map { metadataRoot(pathProvider, it.identity.repositoryId) } +
        ownerRoot
    val allowedRoots = roots.mapTo(mutableSetOf(), ::rootKey)
    ArtifactRootLockCoordinator.withRoots(allowedRoots) {
        val entries = artifacts.map { metadata ->
            val artifactStore = ArtifactManifestStore(metadataRoot(pathProvider, metadata.artifact.repositoryId))
            try {
                val installed = artifactStore.readValidated()?.entries?.singleOrNull { entry ->
                    entry.logicalRole == metadata.logicalRole && entry.identity == metadata.artifact &&
                        entry.localRelativePath == metadata.destinationRelativePath
                } ?: return@withRoots false
                ArtifactManifestEntry.create(
                    logicalRole = installed.logicalRole,
                    identity = installed.identity,
                    byteCount = installed.byteCount,
                    contentSha256 = installed.contentSha256,
                    bundleId = metadata.bundleId,
                    localRelativePath = installed.localRelativePath,
                ) ?: return@withRoots false
            } finally {
                artifactStore.close()
            }
        }
        val store = bundleStore(pathProvider, ownerRoot, allowedRoots)
        try {
            store.recover()
            if (store.readManifestOnly() != previous) return@withRoots false
            block(store, entries)
        } finally {
            store.close()
        }
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}

private fun readOwnerManifest(ownerRoot: Path): ArtifactManifest? {
    val store = ArtifactBundleManifestStore(ownerRoot) { true }
    return try {
        store.readManifestOnly()
    } finally {
        store.close()
    }
}

private fun bundleStore(
    pathProvider: StoragePathProvider,
    ownerRoot: Path,
    allowedRoots: Set<String>,
): ArtifactBundleManifestStore =
    ArtifactBundleManifestStore(ownerRoot) { entry ->
        val artifactRoot = metadataRoot(pathProvider, entry.identity.repositoryId)
        if (rootKey(artifactRoot) !in allowedRoots) return@ArtifactBundleManifestStore false
        val store = ArtifactManifestStore(artifactRoot)
        try {
            store.readValidated()?.entries?.any { installed ->
                installed.logicalRole == entry.logicalRole && installed.identity == entry.identity &&
                    installed.localRelativePath == entry.localRelativePath &&
                    installed.byteCount == entry.byteCount && installed.contentSha256 == entry.contentSha256
            } == true
        } finally {
            store.close()
        }
    }

private fun metadataRoot(pathProvider: StoragePathProvider, modelId: String): Path =
    pathProvider.getModelsStorageDirectory(validateModelId(modelId)).toPath(normalize = true)

private fun rootKey(path: Path): String = path.toString().trim().replace('\\', '/').trimEnd('/').lowercase()
