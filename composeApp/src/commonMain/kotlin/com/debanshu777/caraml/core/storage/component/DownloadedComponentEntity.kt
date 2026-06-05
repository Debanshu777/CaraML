package com.debanshu777.caraml.core.storage.component

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "downloaded_component",
    indices = [Index(value = ["repo_id", "file_path"], unique = true)],
)
data class DownloadedComponentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "repo_id") val repoId: String,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "local_path") val localPath: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long?,
    @ColumnInfo(name = "downloaded_at") val downloadedAt: Long,
)
