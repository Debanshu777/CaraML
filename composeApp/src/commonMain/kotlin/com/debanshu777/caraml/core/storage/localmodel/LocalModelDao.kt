package com.debanshu777.caraml.core.storage.localmodel

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalModelDao {
    @Query("SELECT filename FROM local_model WHERE model_id = :modelId")
    suspend fun getFilenamesByModelId(modelId: String): List<String>

    @Query("DELETE FROM local_model WHERE model_id = :modelId AND filename = :filename")
    suspend fun deleteByModelIdAndFilename(modelId: String, filename: String)

    @Query("DELETE FROM local_model WHERE model_id = :modelId")
    suspend fun deleteAllForModelId(modelId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LocalModelEntity)

    @Query("SELECT * FROM local_model ORDER BY usage_count DESC, id DESC")
    fun getAllDownloadedFiles(): Flow<List<LocalModelEntity>>

    @Query(
        "SELECT * FROM local_model WHERE model_type = :modelType ORDER BY usage_count DESC, id DESC"
    )
    fun getDownloadedFilesByType(modelType: String): Flow<List<LocalModelEntity>>

    @Query("UPDATE local_model SET usage_count = usage_count + 1 WHERE model_id = :modelId AND filename = :filename")
    suspend fun incrementUsageCount(modelId: String, filename: String)

    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM local_model")
    fun getTotalDownloadedSizeBytes(): Flow<Long>

    @Query("UPDATE local_model SET component_status = :status WHERE model_id = :modelId")
    suspend fun updateComponentStatus(modelId: String, status: String)

    @Query("SELECT * FROM local_model WHERE is_main_model = 1")
    suspend fun getMainModels(): List<LocalModelEntity>

    @Query("UPDATE local_model SET arch = :arch WHERE model_id = :modelId")
    suspend fun updateArch(modelId: String, arch: String)
}
