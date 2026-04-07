package com.debanshu777.huggingfacemanager.download

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSFileSystemSize
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSUserDomainMask

class IosStoragePathProvider : StoragePathProvider {
    @OptIn(ExperimentalForeignApi::class)
    override fun getModelsStorageDirectory(modelId: String): String {
        val docs = NSFileManager.defaultManager
            .URLForDirectory(NSDocumentDirectory, NSUserDomainMask, null, false, null)!!.path!!
        val dir = "$docs/models/$modelId"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        return dir
    }
    
    @OptIn(ExperimentalForeignApi::class)
    override fun getDatabasePath(): String {
        val docs = NSFileManager.defaultManager
            .URLForDirectory(NSDocumentDirectory, NSUserDomainMask, null, false, null)!!.path!!
        val dbDir = "$docs/databases"
        NSFileManager.defaultManager.createDirectoryAtPath(dbDir, true, null, null)
        return "$dbDir/caraml.db"
    }
    
    @OptIn(ExperimentalForeignApi::class)
    override fun fileExists(path: String): Boolean =
        NSFileManager.defaultManager.fileExistsAtPath(path)

    @OptIn(ExperimentalForeignApi::class)
    override fun getAvailableStorageBytes(): Long {
        val docs = NSFileManager.defaultManager
            .URLForDirectory(NSDocumentDirectory, NSUserDomainMask, null, false, null)?.path
            ?: return 0L
        val attributes = NSFileManager.defaultManager.attributesOfFileSystemForPath(docs, null)
        val free = attributes?.get(NSFileSystemFreeSize) as? NSNumber
        return free?.longLongValue ?: 0L
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun getTotalStorageBytes(): Long {
        val docs = NSFileManager.defaultManager
            .URLForDirectory(NSDocumentDirectory, NSUserDomainMask, null, false, null)?.path
            ?: return 0L
        val attributes = NSFileManager.defaultManager.attributesOfFileSystemForPath(docs, null)
        val total = attributes?.get(NSFileSystemSize) as? NSNumber
        return total?.longLongValue ?: 0L
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun isModelFileReadable(path: String): Boolean {
        val mgr = NSFileManager.defaultManager
        return mgr.fileExistsAtPath(path) && mgr.isReadableFileAtPath(path)
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun isDirectoryReadable(path: String): Boolean {
        val mgr = NSFileManager.defaultManager
        if (!mgr.isReadableFileAtPath(path)) return false
        return mgr.contentsOfDirectoryAtPath(path, null) != null
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun renameFile(from: String, to: String): Boolean =
        try { NSFileManager.defaultManager.moveItemAtPath(from, to, null) } catch (_: Exception) { false }

    @OptIn(ExperimentalForeignApi::class)
    override fun deleteDownloadedModelContent(modelId: String, localPath: String): Boolean =
        try {
            val root = getModelsStorageDirectory(modelId).trimEnd('/')
            val target = localPath.trimEnd('/')
            if (!isPathWithinModelRoot(root, target)) return false
            val mgr = NSFileManager.defaultManager
            if (!mgr.fileExistsAtPath(target)) return true
            mgr.removeItemAtPath(target, null)
            !mgr.fileExistsAtPath(target)
        } catch (_: Exception) {
            false
        }
}

private fun isPathWithinModelRoot(root: String, target: String): Boolean {
    val rootNorm = root.trimEnd('/')
    val targetNorm = target.trimEnd('/')
    if (targetNorm == rootNorm) return true
    return targetNorm.startsWith("$rootNorm/")
}
