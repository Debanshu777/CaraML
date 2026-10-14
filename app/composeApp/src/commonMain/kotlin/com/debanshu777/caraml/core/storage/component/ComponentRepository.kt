package com.debanshu777.caraml.core.storage.component

class ComponentRepository(private val dao: DownloadedComponentDao) {
    /** Returns all components linked to a given model. */
    suspend fun getComponentsForModel(modelId: String): List<DownloadedComponentEntity> =
        dao.getComponentsForModel(modelId)
}
