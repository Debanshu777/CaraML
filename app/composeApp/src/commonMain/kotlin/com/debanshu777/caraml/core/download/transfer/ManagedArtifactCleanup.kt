package com.debanshu777.caraml.core.download.transfer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileSystem

/** A catalog-owned file, never a directory or an unbounded repository root. */
internal data class ManagedArtifactFile(val repositoryId: String, val relativePath: String)

/** Removes catalog-owned files through the same no-follow boundary used for download writes. */
internal suspend fun deleteCatalogArtifactFiles(
    paths: StoragePathProvider,
    files: Collection<ManagedArtifactFile>,
): Boolean = withContext(Dispatchers.IO) {
    try {
        require(files.size <= 64)
        files.forEach { file ->
            validateDownloadRequest(file.repositoryId, file.relativePath)
            require(file.relativePath.split('/').none {
                it.startsWith(".caraml-") && it != ".caraml-artifacts"
            })
        }
        val groups = files.distinct().groupBy { artifactMetadataRoot(paths, it.repositoryId) }
        ArtifactRootLockCoordinator.withRoots(groups.keys.map(::artifactRootKey)) {
            groups.all { (root, entries) ->
                if (FileSystem.SYSTEM.metadataOrNull(root) == null) return@all true
                val secure = SecureArtifactRoot(root)
                try {
                    // A directory or symlink is never a valid catalog-file deletion target.
                    if (entries.any { file ->
                            val info = FileSystem.SYSTEM.metadataOrNull(root / file.relativePath)
                            info != null && (!info.isRegularFile || info.symlinkTarget != null ||
                                !secure.existsRegularFile(file.relativePath))
                        }) return@all false
                    val store = ArtifactManifestStore(root)
                    try {
                        if (store.recover() == ArtifactManifestRecoveryResult.QUARANTINED) return@all false
                        val manifest = store.read()
                        if (manifest == null && FileSystem.SYSTEM.metadataOrNull(root / ArtifactManifestStore.MANIFEST_FILE_NAME) != null) {
                            return@all false
                        }
                        val selected = manifest?.entries.orEmpty().filter { entry ->
                            entries.any { it.relativePath == entry.localRelativePath }
                        }
                        if (selected.isNotEmpty() && !store.pruneForRemoval(selected)) return@all false
                    } finally { store.close() }
                    entries.forEach { file ->
                        // Missing files are already clean. Secure deletion never follows a swapped parent.
                        if (FileSystem.SYSTEM.metadataOrNull(root / file.relativePath) != null) {
                            secure.delete(file.relativePath)
                            secure.syncDirectory(file.relativePath.substringBeforeLast('/', "."))
                        }
                    }
                    true
                } finally { secure.close() }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}
