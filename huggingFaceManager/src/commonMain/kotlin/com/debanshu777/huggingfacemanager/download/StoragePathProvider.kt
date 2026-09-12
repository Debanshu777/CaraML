package com.debanshu777.huggingfacemanager.download

enum class StoredArtifactKind {
    REGULAR_FILE,
    DIRECTORY,
}

/**
 * Non-identifying filesystem evidence captured after path containment and special-file checks.
 * [changeStamp] is only a bounded rehash trigger. It must never be used as content identity.
 */
data class StoredArtifactSnapshot(
    val kind: StoredArtifactKind,
    val byteCount: Long,
    val changeStamp: String,
) {
    init {
        require(byteCount >= 0L)
        require(changeStamp.isNotBlank() && changeStamp.length <= MAX_ARTIFACT_CHANGE_STAMP_LENGTH)
        require(changeStamp.none(Char::isISOControl))
    }
}

const val MAX_ARTIFACT_CHANGE_STAMP_LENGTH: Int = 128

interface StoragePathProvider {
    fun getModelsStorageDirectory(modelId: String): String
    fun getDatabasePath(): String
    fun fileExists(path: String): Boolean
    fun getAvailableStorageBytes(): Long
    fun getTotalStorageBytes(): Long

    /**
     * Resolves [localPath], requires containment under this model's storage root, and rejects
     * symlinks that escape the root plus non-regular special files. No canonical path is exposed.
     */
    fun inspectDownloadedArtifact(modelId: String, localPath: String): StoredArtifactSnapshot? = null

    /**
     * Returns true if the path points to a readable model file (exists, is a file, and can be read).
     */
    fun isModelFileReadable(path: String): Boolean

    /**
     * Returns true if the path is an existing directory that can be read (e.g. diffusers model root).
     */
    fun isDirectoryReadable(path: String): Boolean

    /**
     * Returns the size in bytes of the file (or recursive size of a directory) at [path].
     * Returns 0 if the path does not exist or cannot be read.
     */
    fun getFileSize(path: String): Long

    /**
     * Atomically renames [from] to [to]. Returns true on success.
     * If [to] already exists the behaviour is platform-defined (overwrite on JVM/Android,
     * fail on iOS).
     */
    fun renameFile(from: String, to: String): Boolean

    /**
     * Deletes a downloaded artifact at [localPath] after verifying it lies under
     * [getModelsStorageDirectory](modelId). Removes a single file or a directory tree.
     * Returns true if the path no longer exists afterwards (including if it was already absent).
     */
    fun deleteDownloadedModelContent(modelId: String, localPath: String): Boolean
}
