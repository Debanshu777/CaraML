package com.debanshu777.caraml.core.storage.evidence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface InstalledModelEvidenceDao {
    @Query("SELECT * FROM installed_model_evidence WHERE model_id = :modelId")
    suspend fun get(modelId: String): InstalledModelEvidenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: InstalledModelEvidenceEntity)

    @Query("DELETE FROM installed_model_evidence WHERE model_id = :modelId")
    suspend fun delete(modelId: String)
}
