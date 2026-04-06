package com.debanshu777.huggingfacemanager.download

interface StoragePathProvider {
    fun getModelsStorageDirectory(modelId: String): String
    fun getDatabasePath(): String
    fun fileExists(path: String): Boolean
    fun getAvailableStorageBytes(): Long
    fun getTotalStorageBytes(): Long

    /**
     * Returns true if the path points to a readable model file (exists, is a file, and can be read).
     */
    fun isModelFileReadable(path: String): Boolean

    /**
     * Returns true if the path is an existing directory that can be read (e.g. diffusers model root).
     */
    fun isDirectoryReadable(path: String): Boolean

    /**
     * Atomically renames [from] to [to]. Returns true on success.
     * If [to] already exists the behaviour is platform-defined (overwrite on JVM/Android,
     * fail on iOS).
     */
    fun renameFile(from: String, to: String): Boolean
}