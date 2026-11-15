package com.debanshu777.caraml.core.storage.catalog

import com.debanshu777.caraml.core.storage.component.DownloadedComponentEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.download.transfer.ArtifactManifest
import com.debanshu777.caraml.core.download.transfer.ArtifactManifestEntry
import com.debanshu777.caraml.core.download.transfer.StoragePathProvider
import com.debanshu777.caraml.core.download.transfer.ManagedArtifactFile
import com.debanshu777.caraml.core.download.transfer.deleteCatalogArtifactFiles
import com.debanshu777.caraml.core.download.transfer.validateDownloadRequest
import com.debanshu777.caraml.core.download.transfer.validateModelId
import com.debanshu777.caraml.core.download.transfer.persistedArtifactStorageLocation
import com.debanshu777.caraml.core.download.diffusion.DIFFUSERS_BUNDLE_DB_FILENAME
import kotlinx.coroutines.CancellationException
import okio.Path.Companion.toPath

enum class InstalledModelRemovalResult {
    Removed,
    RemovedWithCleanupFailure,
    Rejected,
}

/**
 * Removes a selected installation only while its catalog snapshot and owned paths still agree.
 * A committed manifest binds exact files even when payloads are damaged. Legacy fallback only
 * removes catalog-owned files; ambiguous directory content is retained and reported. Database unlinking is transactional; byte cleanup is
 * conservative and never targets a path that remains referenced by another catalog owner.
 */
class InstalledModelRemovalService(
    private val catalog: InstalledModelCatalogDao,
    private val storagePathProvider: StoragePathProvider,
    private val manifestSource: suspend (String) -> ArtifactManifest?,
    private val publicationCoordinator: InstalledModelPublicationCoordinator,
    private val artifactCleaner: suspend (Collection<ArtifactManifestEntry>) -> Boolean = { entries ->
        deleteCatalogArtifactFiles(storagePathProvider, entries.map {
            ManagedArtifactFile(it.identity.repositoryId, it.localRelativePath)
        })
    },
) {
    suspend fun remove(requestedModel: LocalModelEntity): InstalledModelRemovalResult {
        repeat(MAX_MANIFEST_LOCK_ATTEMPTS) {
            val observed = catalog.snapshotForRemoval(requestedModel.id)
                ?.takeIf { it.model == requestedModel } ?: return InstalledModelRemovalResult.Rejected
            val observedManifest = readManifest(requestedModel.modelId)
            val storageKeys = (observedManifest?.entries.orEmpty().map { entry ->
                artifactStorageCoordinationKey(entry.identity.repositoryId, entry.localRelativePath)
            } + catalogFiles(observed).map { file ->
                artifactStorageCoordinationKey(file.repositoryId, file.relativePath)
            }).distinct()
            if (storageKeys.size > 128) return InstalledModelRemovalResult.Rejected
            val outcome = publicationCoordinator.withArtifactPublication(requestedModel.modelId, storageKeys) {
                val currentManifest = readManifest(requestedModel.modelId)
                if (currentManifest != observedManifest) return@withArtifactPublication LockedRemoval.Retry
                val snapshot = catalog.snapshotForRemoval(requestedModel.id)
                    ?: return@withArtifactPublication LockedRemoval.Rejected
                if (snapshot.model != requestedModel) return@withArtifactPublication LockedRemoval.Rejected
                if (snapshot != observed) return@withArtifactPublication LockedRemoval.Retry
                val binding = currentManifest?.let { bind(snapshot, it) }
                val unlinkOnly = currentManifest != null && binding == null
                if (unlinkOnly) {
                    // A historical variant may coexist with the current installed bundle. Only
                    // unlink it when another row proves who owns the current manifest, or its
                    // legacy location proves it is outside the immutable generation namespace.
                    // The latter also allows removing it after the current variant was removed.
                    val currentOwner = catalog.snapshotModels(requestedModel.modelId).any { other ->
                        other.id != requestedModel.id &&
                            bind(snapshot.copy(model = other), requireNotNull(currentManifest)) != null
                    }
                    val legacyFile = modelFile(snapshot.model)?.relativePath
                        ?.let { !it.startsWith(".caraml-artifacts/") } == true
                    val legacyDirectory = snapshot.model.filename == DIFFUSERS_BUNDLE_DB_FILENAME &&
                        normalizedRepositoryRoot(snapshot.model.modelId)?.let {
                            samePath(snapshot.model.localPath, it)
                        } == true
                    if (!currentOwner && !legacyFile && !legacyDirectory) {
                        return@withArtifactPublication LockedRemoval.Rejected
                    }
                }
                val removed = catalog.removeSelectedIfMatches(snapshot)
                    ?: return@withArtifactPublication LockedRemoval.Retry
                val cleaned = when {
                    unlinkOnly -> false
                    binding == null -> cleanupCatalogFiles(removed)
                    else -> cleanup(binding, removed)
                }
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
        val componentEntries = LinkedHashMap<Long, ArtifactManifestEntry>(snapshot.components.size)
        snapshot.components.forEach { component ->
            val entry = manifest.entries.singleOrNull { candidate -> component.matches(candidate) } ?: return null
            if (entry in componentEntries.values) return null
            componentEntries[component.id] = entry
        }
        // A companion can live in the primary repository (for example ONNX external weights).
        // Catalog component bindings, not repository ownership, identify the non-primary files.
        val primaryEntries = manifest.entries.filterNot { it in componentEntries.values }
        if (primaryEntries.isEmpty() || primaryEntries.any { it.identity.repositoryId != owner }) return null

        if (snapshot.model.filename == DIFFUSERS_BUNDLE_DB_FILENAME) {
            val locations = primaryEntries.map { entry ->
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
        } else {
            val primary = primaryEntries.singleOrNull()?.takeIf { it.logicalRole == "model" } ?: return null
            val expectedPath = absolutePath(primary) ?: return null
            if (!samePath(snapshot.model.localPath, expectedPath) ||
                snapshot.model.filename != primary.layoutRelativePath.substringAfterLast('/')
            ) return null
        }
        return BoundInstallation(primaryEntries, componentEntries)
    }

    private suspend fun cleanup(
        binding: BoundInstallation,
        removed: RemovedInstalledCatalog,
    ): Boolean = try {
        val entriesToDelete = LinkedHashSet<ArtifactManifestEntry>()
        binding.primaryEntries.forEach { entry ->
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
        if (!isReferenced(path)) {
            targets += entry
        }
    }

    private suspend fun isReferenced(path: String): Boolean = catalog.catalogStoragePaths().any { reference ->
        // A remaining directory owner protects descendants, and a remaining child protects its parent.
        val candidate = path.toPath(normalize = true)
        val other = reference.toPath(normalize = true)
        candidate.root == other.root &&
            (candidate.segments.take(other.segments.size) == other.segments ||
                other.segments.take(candidate.segments.size) == candidate.segments)
    }

    private fun catalogFiles(snapshot: InstalledCatalogSnapshot): List<ManagedArtifactFile> =
        listOfNotNull(modelFile(snapshot.model)) + snapshot.components.mapNotNull(::componentFile)

    private fun modelFile(model: LocalModelEntity): ManagedArtifactFile? =
        if (model.filename == DIFFUSERS_BUNDLE_DB_FILENAME) null
        else managedFile(model.modelId, model.localPath)?.takeIf {
            it.relativePath.substringAfterLast('/') == model.filename
        }

    private fun componentFile(component: DownloadedComponentEntity): ManagedArtifactFile? =
        managedFile(component.repoId, component.localPath)?.takeIf {
            // Both legacy normalized Diffusers names and immutable generation paths retain this leaf.
            it.relativePath.substringAfterLast('/') ==
                com.debanshu777.caraml.core.download.diffusion.normalizedDiffusersRelativePath(component.filePath)
                    .substringAfterLast('/')
        }

    private fun managedFile(repositoryId: String, absolutePath: String): ManagedArtifactFile? = try {
        val root = normalizedRepositoryRoot(repositoryId)?.toPath() ?: return null
        val path = absolutePath.toPath()
        if (!path.isAbsolute || path != path.normalized() || path == root ||
            path.root != root.root || path.segments.take(root.segments.size) != root.segments
        ) return null
        val relative = path.segments.drop(root.segments.size).joinToString("/")
        validateDownloadRequest(repositoryId, relative)
        if (relative.split('/').any { it.startsWith(".caraml-") && it != ".caraml-artifacts" }) return null
        ManagedArtifactFile(repositoryId, relative)
    } catch (_: Exception) { null }

    private suspend fun cleanupCatalogFiles(removed: RemovedInstalledCatalog): Boolean = try {
        val targets = mutableListOf<ManagedArtifactFile>()
        var fullyBound = true
        val primary = modelFile(removed.model)
        if (primary == null) fullyBound = false else targets += primary
        removed.unreferencedComponents.forEach { component ->
            val file = componentFile(component)
            if (file == null) fullyBound = false else targets += file
        }
        val unreferenced = targets.distinct().filterNot { file ->
            val root = requireNotNull(normalizedRepositoryRoot(file.repositoryId))
            isReferenced(requireNotNull(pathUnder(root, file.relativePath)))
        }
        val cleaned = unreferenced.isEmpty() || deleteCatalogArtifactFiles(storagePathProvider, unreferenced)
        fullyBound && cleaned
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { false }

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
        storagePathProvider.getModelsStorageDirectory(validateModelId(repositoryId))
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
        val primaryEntries: List<ArtifactManifestEntry>,
        val componentEntries: Map<Long, ArtifactManifestEntry>,
    )

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
