package com.debanshu777.caraml.core.storage.evidence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface InstalledModelEvidenceDao {
    @Query("SELECT * FROM installed_model_evidence WHERE model_id = :modelId")
    suspend fun get(modelId: String): InstalledModelEvidenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: InstalledModelEvidenceEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: InstalledModelEvidenceEntity): Long

    @Update
    suspend fun update(entity: InstalledModelEvidenceEntity): Int

    @Transaction
    suspend fun compareAndSet(
        expected: InstalledModelEvidenceEntity?,
        replacement: InstalledModelEvidenceEntity,
    ): Boolean {
        require(expected == null || expected.modelId == replacement.modelId) {
            "Evidence compare-and-set owner mismatch"
        }
        if (get(replacement.modelId) != expected) return false
        return if (expected == null) {
            insertIfAbsent(replacement) != -1L
        } else {
            update(replacement) == 1
        }
    }

    @Query("DELETE FROM installed_model_evidence WHERE model_id = :modelId")
    suspend fun delete(modelId: String)
}
