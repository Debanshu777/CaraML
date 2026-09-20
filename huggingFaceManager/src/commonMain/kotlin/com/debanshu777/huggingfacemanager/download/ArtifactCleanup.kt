package com.debanshu777.huggingfacemanager.download

import kotlinx.coroutines.CancellationException
import okio.Path
import okio.Path.Companion.toPath

/**
 * Removes exact, already-unlinked artifact entries without invalidating other immutable revisions
 * stored below the same repository root. Every target path is re-derived from validated manifest
 * evidence; callers cannot supply an independent filesystem path.
 *
 * Manifests are pruned before bytes are removed. A crash or later deletion failure can therefore
 * leave unreferenced bytes behind, but it cannot leave a retained manifest pointing at bytes this
 * operation deleted.
 */
suspend fun deleteValidatedArtifactEntries(
    pathProvider: StoragePathProvider,
    entries: Collection<ArtifactManifestEntry>,
): Boolean = try {
    val requested = entries.asSequence().take(MAX_CLEANUP_ENTRIES + 1).toList()
    if (requested.isEmpty() || requested.size != entries.size || requested.distinct().size != requested.size) {
        return false
    }
    if (requested.distinctBy { it.identity.repositoryId to it.localRelativePath }.size != requested.size ||
        requested.any { entry ->
            persistedArtifactStorageLocation(entry.identity, entry.bundleId, entry.localRelativePath)
                ?.layoutRelativePath != entry.layoutRelativePath
        }
    ) return false

    val boundEntries = requested.map { entry ->
        val repositoryId = entry.identity.repositoryId
        val root = cleanupRoot(pathProvider, repositoryId) ?: return false
        BoundCleanupEntry(repositoryId, root, entry)
    }
    val groups = boundEntries.groupBy { cleanupRootKey(it.root) }.values.map { groupEntries ->
        CleanupGroup(groupEntries.first().root, groupEntries)
    }
    ArtifactRootLockCoordinator.withRoots(groups.map { it.root.toString() }) {
        val stores = groups.map { group -> group to ArtifactManifestStore(group.root) }
        try {
            val allBound = stores.all { (group, store) ->
                store.recover()
                val manifest = store.readValidated() ?: return@all false
                group.entries.map(BoundCleanupEntry::entry).all(manifest.entries::contains)
            }
            if (!allBound) return@withRoots false
            if (!stores.all { (group, store) -> store.pruneValidated(group.entries.map(BoundCleanupEntry::entry)) }) {
                return@withRoots false
            }
            groups.all { group ->
                group.entries.all { bound ->
                    val target = (bound.root / bound.entry.localRelativePath).normalized()
                    target != bound.root && target.isUnder(bound.root) &&
                        pathProvider.deleteDownloadedModelContent(bound.repositoryId, target.toString())
                }
            }
        } finally {
            stores.forEach { (_, store) -> store.close() }
        }
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}

private data class CleanupGroup(
    val root: Path,
    val entries: List<BoundCleanupEntry>,
)

private data class BoundCleanupEntry(
    val repositoryId: String,
    val root: Path,
    val entry: ArtifactManifestEntry,
)

private fun cleanupRoot(pathProvider: StoragePathProvider, repositoryId: String): Path? = try {
    val validated = validateModelId(repositoryId)
    pathProvider.getModelsStorageDirectory(validated)
        .takeIf { it.isNotBlank() && '\u0000' !in it }
        ?.toPath(normalize = true)
} catch (_: Exception) {
    null
}

private fun cleanupRootKey(path: Path): String =
    path.toString().trim().replace('\\', '/').trimEnd('/').lowercase()

private fun Path.isUnder(root: Path): Boolean =
    segments.size > root.segments.size && segments.take(root.segments.size) == root.segments

private const val MAX_CLEANUP_ENTRIES = 64
