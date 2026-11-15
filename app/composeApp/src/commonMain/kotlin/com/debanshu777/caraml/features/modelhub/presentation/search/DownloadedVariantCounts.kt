package com.debanshu777.caraml.features.modelhub.presentation.search

import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.storage.component.ComponentRepository
import com.debanshu777.caraml.core.storage.component.DownloadedComponentEntity
import com.debanshu777.caraml.core.download.transfer.DownloadManager
import com.debanshu777.caraml.core.download.transfer.StoragePathProvider
import com.debanshu777.caraml.core.download.transfer.ArtifactManifest
import com.debanshu777.caraml.core.download.transfer.ArtifactManifestEntry
import com.debanshu777.caraml.core.storage.localmodel.ModelType
import com.debanshu777.caraml.core.download.diffusion.DIFFUSERS_BUNDLE_DB_FILENAME
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Published identity and current file-shape projection; payload validation belongs to publication/recovery. */
fun Flow<List<LocalModelEntity>>.downloadedVariantCounts(
    componentRepository: ComponentRepository,
    downloadManager: DownloadManager,
    storagePathProvider: StoragePathProvider,
): Flow<Map<String, Int>> = downloadedVariantCounts(componentRepository, storagePathProvider) { modelId, diffusion ->
    if (diffusion) downloadManager.installedBundleForLoad(modelId) else downloadManager.installedArtifactsForDisplay(modelId)
}

internal fun Flow<List<LocalModelEntity>>.downloadedVariantCounts(
    componentRepository: ComponentRepository,
    storagePathProvider: StoragePathProvider,
    manifestFor: suspend (modelId: String, diffusionBundle: Boolean) -> ArtifactManifest?,
): Flow<Map<String, Int>> = distinctUntilChangedBy { models ->
    // Chat usage increments and usage-based reordering do not change installed artifacts.
    models.map { it.copy(usageCount = 0) }.toSet()
}.map { models ->
    aggregateDownloadedVariantCounts(models, componentRepository, storagePathProvider, manifestFor)
}.flowOn(Dispatchers.IO)

internal suspend fun aggregateDownloadedVariantCounts(
    models: List<LocalModelEntity>,
    componentRepository: ComponentRepository,
    storagePathProvider: StoragePathProvider,
    manifestFor: suspend (modelId: String, diffusionBundle: Boolean) -> ArtifactManifest?,
): Map<String, Int> {
    val uniqueReadyPaths = mutableSetOf<Pair<String, String>>()
    val manifests = mutableMapOf<Pair<String, Boolean>, ArtifactManifest?>()
    for (model in models) {
        val isDiffusion = model.modelType == ModelType.IMAGE || model.modelType == ModelType.VIDEO
        val isDirectoryBundle = model.filename == DIFFUSERS_BUNDLE_DB_FILENAME
        if (model.componentStatus != LocalModelEntity.STATUS_READY || !model.isMainModel ||
            model.modelId.isBlank() || model.localPath.isBlank() || model.filename.isShardPart() ||
            (!isDirectoryBundle && !model.filename.isRunnableModelArtifact())
        ) continue

        val components = componentRepository.getComponentsForModel(model.modelId)
        val manifestKey = model.modelId to isDiffusion
        val manifest = if (manifestKey in manifests) manifests[manifestKey] else {
            manifestFor(model.modelId, isDiffusion).also { manifests[manifestKey] = it }
        }
        val manifestEntries = manifest?.entries.orEmpty()
        val root = storagePathProvider.getModelsStorageDirectory(model.modelId).trimEnd('/')
        val candidateBundles = manifestEntries
            .filter { it.logicalRole == "model" && it.identity.repositoryId == model.modelId }
            .filter { entry ->
                val entryPath = "$root/${entry.localRelativePath}"
                if (isDirectoryBundle) {
                    storagePathProvider.isDirectoryReadable(model.localPath) &&
                        entryPath.startsWith(model.localPath.trimEnd('/') + "/")
                } else {
                    entryPath == model.localPath && entry.layoutRelativePath.substringAfterLast('/') == model.filename
                }
            }
            .map(ArtifactManifestEntry::bundleId)
            .distinct()
        val selectedBundle = candidateBundles.singleOrNull()
        val bundle = manifestEntries.filter { it.bundleId == selectedBundle }
        val primaryFiles = bundle.filter { entry ->
            entry.logicalRole == "model" ||
                (isDirectoryBundle && entry.identity.repositoryId == model.modelId &&
                    (isDiffusion || entry.identity.relativePath.isShardPart()))
        }
        val installedBytes = if (model.filename.endsWith(".onnx", ignoreCase = true)) {
            bundle.sumOf { it.byteCount }
        } else {
            primaryFiles.sumOf { it.byteCount }
        }
        val artifactExists = selectedBundle != null && installedBytes == model.sizeBytes &&
            exactBundleCatalogComponents(
                expected = bundle - primaryFiles.toSet(),
                actual = components.filter { it.bundleId == selectedBundle },
                storagePathProvider = storagePathProvider,
            )
        val hasSafeLegacyGguf = !artifactExists && !isDiffusion && !isDirectoryBundle &&
            components.isEmpty() && manifest == null && model.filename.endsWith(".gguf", ignoreCase = true) &&
            storagePathProvider.inspectDownloadedArtifact(model.modelId, model.localPath)
                ?.let { it.byteCount == (model.sizeBytes ?: -1L) } == true &&
            storagePathProvider.isModelFileReadable(model.localPath)
        val countsArtifact = artifactExists || hasSafeLegacyGguf
        if (countsArtifact) uniqueReadyPaths += model.modelId to model.localPath
    }
    return uniqueReadyPaths.groupingBy { it.first }.eachCount()
}

private fun exactBundleCatalogComponents(
    expected: List<ArtifactManifestEntry>,
    actual: List<DownloadedComponentEntity>,
    storagePathProvider: StoragePathProvider,
): Boolean {
    if (expected.size != actual.size) return false
    return expected.all { entry ->
        actual.singleOrNull { component ->
            component.repoId == entry.identity.repositoryId &&
                component.filePath == entry.identity.relativePath &&
                component.role == entry.logicalRole &&
                component.immutableRevision == entry.identity.immutableRevision &&
                component.remoteObjectId == entry.identity.remoteObjectId &&
                component.bundleId == entry.bundleId && component.sizeBytes == entry.byteCount &&
                component.localPath == storagePathProvider.getModelsStorageDirectory(component.repoId)
                    .trimEnd('/') + "/" + entry.localRelativePath
        } != null
    } && actual.all { component ->
        component.repoId.isNotBlank() && component.role.isNotBlank() && component.localPath.isNotBlank() &&
            storagePathProvider.inspectDownloadedArtifact(component.repoId, component.localPath)
                ?.let { it.byteCount == (component.sizeBytes ?: -1L) } == true
    }
}

private fun String.isRunnableModelArtifact(): Boolean =
    listOf(".gguf", ".onnx", ".safetensors", ".ckpt", ".pth")
        .any { endsWith(it, ignoreCase = true) }

private fun String.isShardPart(): Boolean =
    Regex("(?:^|[-_.])\\d{5}-of-\\d{5}(?=\\.[^.]+$)", RegexOption.IGNORE_CASE).containsMatchIn(this)
