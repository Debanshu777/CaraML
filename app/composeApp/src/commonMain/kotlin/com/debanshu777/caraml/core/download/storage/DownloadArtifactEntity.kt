package com.debanshu777.caraml.core.download.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "download_artifact",
    foreignKeys = [
        ForeignKey(
            entity = DownloadBatchEntity::class,
            parentColumns = ["batch_id"],
            childColumns = ["batch_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["batch_id"]), Index(value = ["state", "lease_expires_at_epoch_ms"])],
)
data class DownloadArtifactEntity(
    @PrimaryKey @ColumnInfo(name = "artifact_id") val artifactId: String,
    @ColumnInfo(name = "batch_id") val batchId: String,
    @ColumnInfo(name = "repository_id") val repositoryId: String,
    @ColumnInfo(name = "immutable_revision") val immutableRevision: String,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "remote_object_id") val remoteObjectId: String?,
    @ColumnInfo(name = "expected_bytes") val expectedBytes: Long,
    @ColumnInfo(name = "logical_role") val logicalRole: String,
    @ColumnInfo(name = "destination_relative_path") val destinationRelativePath: String,
    @ColumnInfo(name = "bundle_id") val bundleId: String,
    @ColumnInfo(name = "is_primary") val isPrimary: Boolean,
    @ColumnInfo(name = "author") val author: String?,
    @ColumnInfo(name = "library_name") val libraryName: String?,
    @ColumnInfo(name = "pipeline_tag") val pipelineTag: String?,
    @ColumnInfo(name = "context_length") val contextLength: Int?,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "bytes_received") val bytesReceived: Long,
    @ColumnInfo(name = "entity_tag") val entityTag: String?,
    @ColumnInfo(name = "last_modified") val lastModified: String?,
    @ColumnInfo(name = "failure_code") val failureCode: String?,
    @ColumnInfo(name = "retry_count") val retryCount: Int,
    @ColumnInfo(name = "platform_task_id") val platformTaskId: String?,
    @ColumnInfo(name = "lease_owner") val leaseOwner: String?,
    @ColumnInfo(name = "lease_expires_at_epoch_ms") val leaseExpiresAtEpochMs: Long?,
    @ColumnInfo(name = "staging_token") val stagingToken: String,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)
