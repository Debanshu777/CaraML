package com.debanshu777.caraml.core.storage.component

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "model_component_link",
    indices = [
        Index(value = ["model_id", "component_id"], unique = true),
        Index(value = ["component_id"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = DownloadedComponentEntity::class,
            parentColumns = ["id"],
            childColumns = ["component_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ModelComponentLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "component_id") val componentId: Long,
    @ColumnInfo(name = "role") val role: String,
)
