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
     * Check if a component is downloaded and valid on disk.
     *
     * Existence alone is not sufficient — a Git LFS pointer file (~130 bytes) or a truncated
     * download will satisfy [fileExists] but will be rejected by stable-diffusion.cpp with
     * "invalid safetensor file". We require at least [MIN_VALID_BYTES] to distinguish real
     * model weights from pointer files and partial downloads.
     */
    fun isComponentDownloaded(component: SdCppComponent): Boolean {
        val dir = storagePathProvider.getModelsStorageDirectory(component.repoId)
        val path = "$dir/${component.filePath}"
        if (!storagePathProvider.fileExists(path)) return false
        return storagePathProvider.getFileSize(path) >= MIN_VALID_BYTES
    }

    /**
     * Resolve the full filesystem path to a component if it exists and is valid.
     * Returns null if the component is not downloaded or is too small to be real weights.
     */
    fun resolveComponentPath(component: SdCppComponent): String? {
        val dir = storagePathProvider.getModelsStorageDirectory(component.repoId)
        val path = "$dir/${component.filePath}"
        if (!storagePathProvider.fileExists(path)) return null
        if (storagePathProvider.getFileSize(path) < MIN_VALID_BYTES) return null
        return path
    }

    /**
     * Get all required components that are missing from disk (or present but invalid).
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
     * Check if all required components are available and valid.
     */
    fun areAllRequiredComponentsAvailable(setup: SdCppModelSetup): Boolean {
        return setup.components.filter { it.required }.all { isComponentDownloaded(it) }
    }

    /**
     * Resolve component paths by role for a given setup.
     * Returns a map of role to filesystem path for available (and valid) components.
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

    companion object {
        /**
         * Minimum file size for a component to be considered a real download.
         * Git LFS pointer files are ~130 bytes; any real model component is several MB.
         */
        private const val MIN_VALID_BYTES = 1024L * 1024L // 1 MB
    }
}