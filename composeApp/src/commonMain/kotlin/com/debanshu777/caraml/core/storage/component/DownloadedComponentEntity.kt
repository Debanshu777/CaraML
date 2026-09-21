package com.debanshu777.caraml.core.storage.component

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "downloaded_component",
    indices = [
        Index(
            value = ["repo_id", "immutable_revision", "file_path", "remote_object_id", "bundle_id", "local_path"],
            unique = true,
            name = "index_downloaded_component_exact_storage_identity",
        ),
        Index(value = ["local_path"], name = "index_downloaded_component_local_path"),
    ],
)
data class DownloadedComponentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "repo_id") val repoId: String,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "local_path") val localPath: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long?,
    @ColumnInfo(name = "downloaded_at") val downloadedAt: Long,
    /** Nullable for database integrity, but incomplete identity is never Ready or a dedup key. */
    @ColumnInfo(name = "immutable_revision") val immutableRevision: String? = null,
    @ColumnInfo(name = "remote_object_id") val remoteObjectId: String? = null,
    @ColumnInfo(name = "bundle_id") val bundleId: String? = null,
    @ColumnInfo(name = "content_sha256") val contentSha256: String? = null,
)
