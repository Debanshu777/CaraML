package com.debanshu777.huggingfacemanager.sdcpp

import com.debanshu777.huggingfacemanager.download.StoragePathProvider

/**
 * Checks availability of model components using filesystem existence checks.
 * No database queries needed - components are tracked by their physical presence on disk.
 */
class SdCppComponentChecker(
    private val storagePathProvider: StoragePathProvider,
) {
    
    /**
     * Check if a component is downloaded and available on disk.
     */
    fun isComponentDownloaded(component: SdCppComponent): Boolean {
        val dir = storagePathProvider.getModelsStorageDirectory(component.repoId)
        return storagePathProvider.fileExists("$dir/${component.filePath}")
    }
    
    /**
     * Resolve the full filesystem path to a component if it exists.
     * Returns null if the component is not downloaded.
     */
    fun resolveComponentPath(component: SdCppComponent): String? {
        val dir = storagePathProvider.getModelsStorageDirectory(component.repoId)
        val path = "$dir/${component.filePath}"
        return if (storagePathProvider.fileExists(path)) path else null
    }
    
    /**
     * Get all required components that are missing from disk.
     */
    fun getMissingComponents(setup: SdCppModelSetup): List<SdCppComponent> {
        return setup.components.filter { it.required && !isComponentDownloaded(it) }
    }
    
    /**
     * Get all components with their availability status.
     */
    fun getComponentsStatus(setup: SdCppModelSetup): List<Pair<SdCppComponent, Boolean>> {
        return setup.components.map { component ->
            component to isComponentDownloaded(component)
        }
    }
    
    /**
     * Check if all required components are available.
     */
    fun areAllRequiredComponentsAvailable(setup: SdCppModelSetup): Boolean {
        return setup.components.filter { it.required }.all { isComponentDownloaded(it) }
    }
    
    /**
     * Resolve component paths by role for a given setup.
     * Returns a map of role to filesystem path for available components.
     */
    fun resolveComponentsByRole(setup: SdCppModelSetup): Map<ComponentRole, String> {
        return setup.components
            .mapNotNull { component ->
                resolveComponentPath(component)?.let { path ->
                    component.role to path
                }
            }
            .toMap()
    }
}