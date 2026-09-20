package com.debanshu777.huggingfacemanager.download

import kotlinx.coroutines.CancellationException
import okio.Path
import okio.Path.Companion.toPath

internal suspend fun publishArtifactBundle(
    pathProvider: StoragePathProvider,
    ownerModelId: String,
    artifacts: List<DownloadMetadataDTO>,
): Boolean {
    if (artifacts.any { !it.usesImmutableStorageLayout }) return false
    return withBundleStore(pathProvider, ownerModelId, artifacts) { store, entries ->
        store.publish(entries)
        store.readValidated()?.bundleDigest == ArtifactManifest.create(entries)?.bundleDigest
    }
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
    val ownerRoot = artifactMetadataRoot(pathProvider, ownerId)
    val candidates = readOwnerManifestCandidates(ownerRoot).takeIf { it.isNotEmpty() } ?: return null
    val roots = (
        candidates.flatMap { candidate ->
            candidate.entries.map { artifactMetadataRoot(pathProvider, it.identity.repositoryId) }
        } + ownerRoot
    ).distinct()
    if (roots.size > MAX_BUNDLE_ROOTS) return null
    val allowedRoots = roots.mapTo(mutableSetOf(), ::artifactRootKey)
    ArtifactRootLockCoordinator.withRoots(allowedRoots) {
        val store = artifactBundleStore(pathProvider, ownerRoot, allowedRoots)
        try {
            if (store.readRecoveryCandidates() != candidates || !recoverArtifactStores(roots)) {
                return@withRoots null
            }
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

internal suspend fun readValidatedArtifactManifest(
    pathProvider: StoragePathProvider,
    modelId: String,
): ArtifactManifest? = try {
    val root = artifactMetadataRoot(pathProvider, validateModelId(modelId))
    ArtifactRootLockCoordinator.withRoots(listOf(artifactRootKey(root))) {
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
    val ownerRoot = artifactMetadataRoot(pathProvider, ownerId)
    val previousCandidates = readOwnerManifestCandidates(ownerRoot)
    val roots = (
        artifacts.map { artifactMetadataRoot(pathProvider, it.artifact.repositoryId) } +
            previousCandidates.flatMap { candidate ->
                candidate.entries.map { artifactMetadataRoot(pathProvider, it.identity.repositoryId) }
            } + ownerRoot
    ).distinct()
    if (roots.size > MAX_BUNDLE_ROOTS) return false
    val allowedRoots = roots.mapTo(mutableSetOf(), ::artifactRootKey)
    ArtifactRootLockCoordinator.withRoots(allowedRoots) {
        if (!recoverArtifactStores(roots)) return@withRoots false
        val store = artifactBundleStore(pathProvider, ownerRoot, allowedRoots)
        try {
            if (store.readRecoveryCandidates() != previousCandidates) return@withRoots false
            store.recover()
            val entries = artifacts.map { metadata ->
                val artifactStore = ArtifactManifestStore(
                    artifactMetadataRoot(pathProvider, metadata.artifact.repositoryId),
                )
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
                        layoutRelativePath = installed.layoutRelativePath,
                    ) ?: return@withRoots false
                } finally {
                    artifactStore.close()
                }
            }
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

internal fun readOwnerManifestCandidates(ownerRoot: Path): List<ArtifactManifest> {
    val store = ArtifactBundleManifestStore(ownerRoot) { true }
    return try {
        store.readRecoveryCandidates()
    } finally {
        store.close()
    }
}

internal fun artifactBundleStore(
    pathProvider: StoragePathProvider,
    ownerRoot: Path,
    allowedRoots: Set<String>,
): ArtifactBundleManifestStore =
    ArtifactBundleManifestStore(ownerRoot) { entry ->
        val artifactRoot = artifactMetadataRoot(pathProvider, entry.identity.repositoryId)
        if (artifactRootKey(artifactRoot) !in allowedRoots) return@ArtifactBundleManifestStore false
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

internal fun artifactMetadataRoot(pathProvider: StoragePathProvider, modelId: String): Path =
    pathProvider.getModelsStorageDirectory(validateModelId(modelId)).toPath(normalize = true)

internal fun artifactRootKey(path: Path): String =
    path.toString().trim().replace('\\', '/').trimEnd('/').lowercase()

internal fun recoverArtifactStores(roots: Collection<Path>): Boolean = roots.all { root ->
    val store = ArtifactManifestStore(root)
    try {
        store.recover() != ArtifactManifestRecoveryResult.QUARANTINED
    } finally {
        store.close()
    }
}

private const val MAX_BUNDLE_ROOTS = 256
