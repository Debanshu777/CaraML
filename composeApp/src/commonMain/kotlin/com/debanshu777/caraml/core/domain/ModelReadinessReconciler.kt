package com.debanshu777.caraml.core.domain

import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelRepository
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.download.DownloadManager
import com.debanshu777.huggingfacemanager.sdcpp.getModelSetup

/**
 * On-startup use case that walks all downloaded main models, checks whether their required
 * components are present on disk, and writes the cached `componentStatus` field back to the DB.
 *
 * This keeps the "Ready / Missing components" UI indicators accurate even after the app is
 * restarted or files are manually moved.
 */
class ModelReadinessReconciler(
    private val localModelRepository: LocalModelRepository,
    private val storagePathProvider: StoragePathProvider,
) {
    private val downloadManager = DownloadManager(storagePathProvider)

    suspend fun reconcile() {
        localModelRepository.demoteMmprojFilesFromMain()
        val models = localModelRepository.getMainModels()
        for (model in models) {
            val setup = getModelSetup(model.modelId)
            val expectedStatus = when {
                setup == null -> LocalModelEntity.STATUS_READY        // unknown → assume ready (single-file)
                downloadManager.validatedBundle(model.modelId)?.let { manifest ->
                    manifest.entries.count {
                        it.logicalRole == "model" && it.identity.repositoryId == model.modelId
                    } == 1 && setup.components.filter { it.required }.all { component ->
                        manifest.entries.singleOrNull { entry ->
                            entry.logicalRole == component.role.name.lowercase() &&
                                entry.identity.repositoryId == component.repoId &&
                                entry.identity.relativePath == component.filePath
                        } != null
                    }
                } == true -> LocalModelEntity.STATUS_READY
                else -> LocalModelEntity.STATUS_PARTIAL
            }
            if (model.componentStatus != expectedStatus) {
                localModelRepository.updateComponentStatus(model.modelId, expectedStatus)
            }
        }
    }
}
