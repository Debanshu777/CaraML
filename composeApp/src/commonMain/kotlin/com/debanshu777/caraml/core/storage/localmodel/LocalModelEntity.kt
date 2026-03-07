package com.debanshu777.caraml.core.storage.localmodel

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_model")
data class LocalModelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "filename") val filename: String,
    @ColumnInfo(name = "local_path") val localPath: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long?,
    @ColumnInfo(name = "downloaded_at") val downloadedAt: Long,
    @ColumnInfo(name = "author") val author: String?,
    @ColumnInfo(name = "library_name") val libraryName: String?,
    @ColumnInfo(name = "pipeline_tag") val pipelineTag: String?,
    @ColumnInfo(name = "usage_count", defaultValue = "0") val usageCount: Long = 0,
)
