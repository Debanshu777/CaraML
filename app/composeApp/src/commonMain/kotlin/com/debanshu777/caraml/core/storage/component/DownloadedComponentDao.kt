package com.debanshu777.caraml.core.storage.component

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadedComponentDao {
    @Query("SELECT * FROM downloaded_component")
    fun getAllComponents(): Flow<List<DownloadedComponentEntity>>

    @Query(
        """
        SELECT dc.* FROM downloaded_component dc
        INNER JOIN model_component_link mcl ON dc.id = mcl.component_id
        WHERE mcl.model_id = :modelId
        """
    )
    suspend fun getComponentsForModel(modelId: String): List<DownloadedComponentEntity>
}
