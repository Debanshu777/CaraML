package com.debanshu777.caraml.core.storage.catalog

import com.debanshu777.caraml.core.storage.component.DownloadedComponentEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.huggingfacemanager.download.ArtifactManifest
import com.debanshu777.huggingfacemanager.download.ArtifactManifestEntry
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.download.deleteValidatedArtifactEntries
import com.debanshu777.huggingfacemanager.download.persistedArtifactStorageLocation
import com.debanshu777.huggingfacemanager.model.DIFFUSERS_BUNDLE_DB_FILENAME
import kotlinx.coroutines.CancellationException
import okio.Path.Companion.toPath

enum class InstalledModelRemovalResult {
    Removed,
    RemovedWithCleanupFailure,
    Rejected,
}

/**
 * Removes a Ready installation only while its exact validated manifest, catalog snapshot, and
 * immutable storage locations still agree. Database unlinking is transactional; byte cleanup is
 * conservative and never targets a path that remains referenced by another catalog owner.
 */
class InstalledModelRemovalService(
    private val catalog: InstalledModelCatalogDao,
    private val storagePathProvider: StoragePathProvider,
    private val manifestSource: suspend (String) -> ArtifactManifest?,
    private val publicationCoordinator: InstalledModelPublicationCoordinator,
    private val artifactCleaner: suspend (Collection<ArtifactManifestEntry>) -> Boolean = { entries ->
        deleteValidatedArtifactEntries(storagePathProvider, entries)
    },
) {
    suspend fun remove(requestedModel: LocalModelEntity): InstalledModelRemovalResult {
        repeat(MAX_MANIFEST_LOCK_ATTEMPTS) {
            val observedManifest = readManifest(requestedModel.modelId)
                ?: return InstalledModelRemovalResult.Rejected
            val storageKeys = observedManifest.entries.map { entry ->
                artifactStorageCoordinationKey(entry.identity.repositoryId, entry.localRelativePath)
            }
            val outcome = publicationCoordinator.withArtifactPublication(requestedModel.modelId, storageKeys) {
                val currentManifest = readManifest(requestedModel.modelId)
                    ?: return@withArtifactPublication LockedRemoval.Rejected
                if (currentManifest != observedManifest) return@withArtifactPublication LockedRemoval.Retry
                val snapshot = catalog.snapshotReady(requestedModel.modelId)
                    ?: return@withArtifactPublication LockedRemoval.Rejected
                if (snapshot.model != requestedModel) return@withArtifactPublication LockedRemoval.Rejected
                val binding = bind(snapshot, currentManifest)
                    ?: return@withArtifactPublication LockedRemoval.Rejected
                val removed = catalog.removeReadyIfMatches(snapshot)
                    ?: return@withArtifactPublication LockedRemoval.Retry
                val cleaned = cleanup(binding, removed)
                if (cleaned) LockedRemoval.Removed else LockedRemoval.RemovedWithCleanupFailure
            }
            when (outcome) {
                LockedRemoval.Removed -> return InstalledModelRemovalResult.Removed
                LockedRemoval.RemovedWithCleanupFailure ->
                    return InstalledModelRemovalResult.RemovedWithCleanupFailure
                LockedRemoval.Rejected -> return InstalledModelRemovalResult.Rejected
                LockedRemoval.Retry -> Unit
            }
        }
        return InstalledModelRemovalResult.Rejected
    }

    private suspend fun readManifest(ownerModelId: String): ArtifactManifest? = try {
        manifestSource(ownerModelId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun bind(
        snapshot: InstalledCatalogSnapshot,
        manifest: ArtifactManifest,
    ): BoundInstallation? {
        if (manifest.entries.isEmpty() || manifest.entries.map { it.bundleId }.toSet().size != 1) return null
        val owner = snapshot.model.modelId
        val ownerEntries = manifest.entries.filter { it.identity.repositoryId == owner }
        if (ownerEntries.isEmpty()) return null
        val externalEntries = manifest.entries.filterNot { it.identity.repositoryId == owner }
        if (externalEntries.size != snapshot.components.size) return null

        val componentEntries = LinkedHashMap<Long, ArtifactManifestEntry>(snapshot.components.size)
        snapshot.components.forEach { component ->
            val entry = externalEntries.singleOrNull { candidate -> component.matches(candidate) } ?: return null
            if (entry in componentEntries.values) return null
            componentEntries[component.id] = entry
        }

        val target = if (snapshot.model.filename == DIFFUSERS_BUNDLE_DB_FILENAME) {
            val locations = ownerEntries.map { entry ->
                persistedArtifactStorageLocation(entry.identity, entry.bundleId, entry.localRelativePath)
                    ?.takeIf { it.layoutRelativePath == entry.layoutRelativePath }
                    ?: return null
            }
            val generationRoots = locations.map { it.generationRootRelativePath }.distinct()
            if (generationRoots.size != 1) return null
            val repositoryRoot = normalizedRepositoryRoot(owner) ?: return null
            val generationRoot = generationRoots.single()
            val expectedRoot = if (generationRoot == null) {
                repositoryRoot
            } else {
                pathUnder(repositoryRoot, generationRoot) ?: return null
            }
            if (!samePath(snapshot.model.localPath, expectedRoot)) return null
            PrimaryStorageTarget.Directory(expectedRoot, scoped = generationRoot != null)
        } else {
            val primary = ownerEntries.singleOrNull { it.logicalRole == "model" } ?: return null
            val expectedPath = absolutePath(primary) ?: return null
            if (!samePath(snapshot.model.localPath, expectedPath) ||
                snapshot.model.filename != primary.layoutRelativePath.substringAfterLast('/')
            ) return null
            PrimaryStorageTarget.File(expectedPath)
        }
        return BoundInstallation(snapshot, ownerEntries, componentEntries, target)
    }

    private suspend fun cleanup(
        binding: BoundInstallation,
        removed: RemovedInstalledCatalog,
    ): Boolean = try {
        val entriesToDelete = LinkedHashSet<ArtifactManifestEntry>()
        binding.ownerEntries.forEach { entry ->
            addIfUnreferenced(entriesToDelete, entry)
        }

        removed.unreferencedComponents.forEach { component ->
            val entry = binding.componentEntries[component.id] ?: return false
            val path = absolutePath(entry) ?: return false
            if (!samePath(path, component.localPath)) return false
            addIfUnreferenced(entriesToDelete, entry)
        }
        entriesToDelete.isEmpty() || artifactCleaner(entriesToDelete)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun addIfUnreferenced(
        targets: MutableSet<ArtifactManifestEntry>,
        entry: ArtifactManifestEntry,
    ) {
        val path = absolutePath(entry) ?: return
        if (catalog.countCatalogStorageReferences(path) == 0L) {
            targets += entry
        }
    }

    private fun DownloadedComponentEntity.matches(entry: ArtifactManifestEntry): Boolean {
        if (role != entry.logicalRole || repoId != entry.identity.repositoryId ||
            filePath != entry.identity.relativePath || sizeBytes?.let { it != entry.byteCount } == true
        ) return false
        val expectedPath = absolutePath(entry) ?: return false
        if (!samePath(localPath, expectedPath)) return false
        val exactFields = listOf(immutableRevision, remoteObjectId, bundleId)
        return when {
            exactFields.all { it == null } -> contentSha256?.equals(entry.contentSha256, ignoreCase = true) != false
            exactFields.any { it.isNullOrBlank() } -> false
            else -> immutableRevision.equals(entry.identity.immutableRevision, ignoreCase = true) &&
                remoteObjectId.equals(entry.identity.remoteObjectId, ignoreCase = true) &&
                bundleId.equals(entry.bundleId, ignoreCase = true) &&
                contentSha256?.equals(entry.contentSha256, ignoreCase = true) != false
        }
    }

    private fun absolutePath(entry: ArtifactManifestEntry): String? =
        normalizedRepositoryRoot(entry.identity.repositoryId)?.let { root ->
            pathUnder(root, entry.localRelativePath)
        }

    private fun normalizedRepositoryRoot(repositoryId: String): String? = try {
        storagePathProvider.getModelsStorageDirectory(repositoryId)
            .takeIf { it.isNotBlank() && '\u0000' !in it }
            ?.toPath(normalize = true)
            ?.toString()
    } catch (_: Exception) {
        null
    }

    private fun pathUnder(root: String, relativePath: String): String? = try {
        val base = root.toPath(normalize = true)
        val candidate = (base / relativePath).normalized()
        candidate.toString().takeIf { candidate.segments.take(base.segments.size) == base.segments }
    } catch (_: Exception) {
        null
    }

    private fun samePath(left: String, right: String): Boolean = try {
        left.toPath(normalize = true) == right.toPath(normalize = true)
    } catch (_: Exception) {
        false
    }

    private data class BoundInstallation(
        val snapshot: InstalledCatalogSnapshot,
        val ownerEntries: List<ArtifactManifestEntry>,
        val componentEntries: Map<Long, ArtifactManifestEntry>,
        val primaryTarget: PrimaryStorageTarget,
    )

    private sealed interface PrimaryStorageTarget {
        data class File(val path: String) : PrimaryStorageTarget
        data class Directory(val path: String, val scoped: Boolean) : PrimaryStorageTarget
    }

    private enum class LockedRemoval {
        Removed,
        RemovedWithCleanupFailure,
        Rejected,
        Retry,
    }

    private companion object {
        const val MAX_MANIFEST_LOCK_ATTEMPTS = 2
    }
}
