package com.debanshu777.huggingfacemanager.download

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.deleteRecursively

class AndroidStoragePathProvider(private val context: Context) : StoragePathProvider {
    override fun getModelsStorageDirectory(modelId: String): String {
        val safeModelId = validateModelId(modelId)
        val base = when {
            Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED ->
                context.getExternalFilesDir(null)
            else -> null
        } ?: context.filesDir
        val modelsRoot = File(base, "models")
        return File(modelsRoot, safeModelId).absolutePath
    }
    
    override fun getDatabasePath(): String =
        File(context.filesDir, "databases").apply { mkdirs() }.absolutePath + "/caraml.db"
    
    override fun fileExists(path: String): Boolean = File(path).exists()

    override fun getAvailableStorageBytes(): Long {
        val base = when {
            Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED ->
                context.getExternalFilesDir(null)
            else -> null
        } ?: context.filesDir
        return StatFs(base.absolutePath).availableBytes
    }

    override fun getTotalStorageBytes(): Long {
        val base = when {
            Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED ->
                context.getExternalFilesDir(null)
            else -> null
        } ?: context.filesDir
        return StatFs(base.absolutePath).totalBytes
    }

    override fun inspectDownloadedArtifact(modelId: String, localPath: String): StoredArtifactSnapshot? =
        try {
            if (localPath.isBlank() || '\u0000' in localPath) return null
            val root = File(getModelsStorageDirectory(modelId)).toPath().toAbsolutePath().normalize()
            val raw = File(localPath).toPath().toAbsolutePath().normalize()
            if (raw != root && !raw.startsWith(root)) return null
            if (Files.isSymbolicLink(raw)) return null
            val realRoot = root.toRealPath()
            val realTarget = raw.toRealPath()
            if (realTarget != realRoot && !realTarget.startsWith(realRoot)) return null
            val attributes = Files.readAttributes(
                realTarget,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            val kind = when {
                attributes.isRegularFile -> StoredArtifactKind.REGULAR_FILE
                attributes.isDirectory -> StoredArtifactKind.DIRECTORY
                else -> return null
            }
            StoredArtifactSnapshot(
                kind = kind,
                byteCount = if (attributes.isRegularFile) attributes.size() else 0L,
                changeStamp = "${attributes.lastModifiedTime().toMillis()}:${attributes.size()}",
            )
        } catch (_: Exception) {
            null
        }

    override fun isModelFileReadable(path: String): Boolean {
        val file = File(path)
        return file.exists() && file.isFile && file.canRead()
    }

    override fun isDirectoryReadable(path: String): Boolean {
        val file = File(path)
        return file.exists() && file.isDirectory && file.canRead()
    }

    override fun getFileSize(path: String): Long {
        val file = File(path)
        if (!file.exists() || !file.canRead()) return 0L
        return if (file.isFile) file.length() else file.walkTopDown()
            .filter { it.isFile }.sumOf { it.length() }
    }

    override fun renameFile(from: String, to: String): Boolean =
        try { File(from).renameTo(File(to)) } catch (_: Exception) { false }

    override fun deleteDownloadedModelContent(modelId: String, localPath: String): Boolean =
        try {
            val root = File(getModelsStorageDirectory(modelId)).canonicalFile
            val target = File(localPath).canonicalFile
            if (!isPathWithinModelRoot(root, target)) return false
            if (!target.exists()) return true
            target.deleteRecursively()
            !target.exists()
        } catch (_: Exception) {
            false
        }
}

private fun isPathWithinModelRoot(root: File, target: File): Boolean {
    val rootPath = root.path
    val targetPath = target.path
    if (targetPath == rootPath) return true
    val prefix = rootPath + File.separator
    return targetPath.startsWith(prefix)
}
