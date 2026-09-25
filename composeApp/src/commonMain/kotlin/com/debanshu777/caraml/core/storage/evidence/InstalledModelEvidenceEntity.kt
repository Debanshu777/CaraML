package com.debanshu777.caraml.core.storage.evidence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "installed_model_evidence")
data class InstalledModelEvidenceEntity(
    @PrimaryKey @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "evidence_state") val evidenceState: String,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    @ColumnInfo(name = "payload") val payload: String,
    @ColumnInfo(name = "sha256") val sha256: String,
    @ColumnInfo(name = "published_at_epoch_ms") val publishedAtEpochMs: Long,
)
