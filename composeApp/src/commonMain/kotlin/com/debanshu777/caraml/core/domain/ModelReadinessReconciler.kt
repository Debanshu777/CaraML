package com.debanshu777.caraml.core.domain

import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelRepository
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.sdcpp.SdCppComponentChecker
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
    private val componentChecker = SdCppComponentChecker(storagePathProvider)

    suspend fun reconcile() {
        val models = localModelRepository.getMainModels()
        for (model in models) {
            val setup = getModelSetup(model.modelId)
            val expectedStatus = when {
                setup == null -> LocalModelEntity.STATUS_READY        // unknown → assume ready (single-file)
                setup.selfContained -> LocalModelEntity.STATUS_READY
                componentChecker.areAllRequiredComponentsAvailable(setup) -> LocalModelEntity.STATUS_READY
                else -> LocalModelEntity.STATUS_PARTIAL
            }
            if (model.componentStatus != expectedStatus) {
                localModelRepository.updateComponentStatus(model.modelId, expectedStatus)
            }
        }
    }
}
