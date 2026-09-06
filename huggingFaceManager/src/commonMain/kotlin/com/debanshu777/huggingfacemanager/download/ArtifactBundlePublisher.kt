package com.debanshu777.huggingfacemanager.download

import okio.Path.Companion.toPath

internal fun publishArtifactBundle(
    pathProvider: StoragePathProvider,
    ownerModelId: String,
    artifacts: List<DownloadMetadataDTO>,
): Boolean = withBundleStore(pathProvider, ownerModelId, artifacts) { store, entries ->
    store.publish(entries)
    store.readValidated()?.bundleDigest == ArtifactManifest.create(entries)?.bundleDigest
}

internal fun validateArtifactBundle(
    pathProvider: StoragePathProvider,
    ownerModelId: String,
    artifacts: List<DownloadMetadataDTO>,
): Boolean = withBundleStore(pathProvider, ownerModelId, artifacts) { store, entries ->
    store.recover()
    store.readValidated()?.bundleDigest == ArtifactManifest.create(entries)?.bundleDigest
}

private inline fun withBundleStore(
    pathProvider: StoragePathProvider,
    ownerModelId: String,
    artifacts: List<DownloadMetadataDTO>,
    block: (ArtifactBundleManifestStore, List<ArtifactManifestEntry>) -> Boolean,
): Boolean = try {
    validateModelId(ownerModelId)
    if (artifacts.isEmpty() || artifacts.size > 64 || artifacts.map { it.bundleId }.toSet().size != 1) return false
    val entries = artifacts.map { metadata ->
        val root = pathProvider.getModelsStorageDirectory(metadata.artifact.repositoryId).toPath(normalize = true)
        val artifactStore = ArtifactManifestStore(root)
        try {
            val installed = artifactStore.readValidated()?.entries?.singleOrNull { entry ->
                entry.logicalRole == metadata.logicalRole &&
                    entry.identity == metadata.artifact && entry.localRelativePath == metadata.destinationRelativePath
            } ?: return false
            ArtifactManifestEntry.create(
                logicalRole = installed.logicalRole,
                identity = installed.identity,
                byteCount = installed.byteCount,
                contentSha256 = installed.contentSha256,
                bundleId = metadata.bundleId,
                localRelativePath = installed.localRelativePath,
            ) ?: return false
        } finally {
            artifactStore.close()
        }
    }
    val ownerRoot = pathProvider.getModelsStorageDirectory(ownerModelId).toPath(normalize = true)
    val store = ArtifactBundleManifestStore(ownerRoot) { entry ->
        val root = pathProvider.getModelsStorageDirectory(entry.identity.repositoryId).toPath(normalize = true)
        val artifactStore = ArtifactManifestStore(root)
        try {
            artifactStore.readValidated()?.entries?.any { installed ->
                installed.logicalRole == entry.logicalRole && installed.identity == entry.identity &&
                    installed.localRelativePath == entry.localRelativePath &&
                    installed.byteCount == entry.byteCount && installed.contentSha256 == entry.contentSha256
            } == true
        } finally {
            artifactStore.close()
        }
    }
    try {
        block(store, entries)
    } finally {
        store.close()
    }
} catch (_: Exception) {
    false
}
