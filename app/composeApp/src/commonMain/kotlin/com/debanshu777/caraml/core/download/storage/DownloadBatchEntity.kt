package com.debanshu777.caraml.core.download.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "download_batch",
    indices = [Index(value = ["owner_model_id", "updated_at_epoch_ms"])],
)
data class DownloadBatchEntity(
    @PrimaryKey @ColumnInfo(name = "batch_id") val batchId: String,
    @ColumnInfo(name = "owner_model_id") val ownerModelId: String,
    @ColumnInfo(name = "model_type") val modelType: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "user_intent") val userIntent: String,
    @ColumnInfo(name = "failure_code") val failureCode: String?,
    @ColumnInfo(name = "evidence_state") val evidenceState: String?,
    @ColumnInfo(name = "evidence_schema_version") val evidenceSchemaVersion: Int?,
    @ColumnInfo(name = "evidence_payload") val evidencePayload: String?,
    @ColumnInfo(name = "evidence_sha256") val evidenceSha256: String?,
    @ColumnInfo(name = "download_for_later_confirmed") val downloadForLaterConfirmed: Boolean,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)
