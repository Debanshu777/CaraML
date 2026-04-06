package com.debanshu777.huggingfacemanager.download

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File

class AndroidStoragePathProvider(private val context: Context) : StoragePathProvider {
    override fun getModelsStorageDirectory(modelId: String): String {
        val base = when {
            Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED ->
                context.getExternalFilesDir(null)
            else -> null
        } ?: context.filesDir
        return File(base, "models/$modelId").apply { mkdirs() }.absolutePath
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

    override fun isModelFileReadable(path: String): Boolean {
        val file = File(path)
        return file.exists() && file.isFile && file.canRead()
    }

    override fun isDirectoryReadable(path: String): Boolean {
        val file = File(path)
        return file.exists() && file.isDirectory && file.canRead()
    }

    override fun renameFile(from: String, to: String): Boolean =
        try { File(from).renameTo(File(to)) } catch (_: Exception) { false }
}
