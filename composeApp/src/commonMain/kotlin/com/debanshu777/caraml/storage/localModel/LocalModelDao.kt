package com.debanshu777.caraml.storage.localModel

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalModelDao {
    @Query("SELECT filename FROM local_model WHERE model_id = :modelId")
    suspend fun getFilenamesByModelId(modelId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LocalModelEntity)

    @Query("SELECT * FROM local_model")
    fun getAll(): Flow<List<LocalModelEntity>>

    @Query("SELECT * FROM local_model ORDER BY id DESC")
    fun getAllDownloadedFiles(): Flow<List<LocalModelEntity>>
}
