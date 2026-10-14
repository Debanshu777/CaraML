package com.debanshu777.caraml.core.recommendation.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface RecommendationObservationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(samples: List<RecommendationObservationEntity>)

    @Query("SELECT * FROM recommendation_observation ORDER BY capturedAtEpochMs DESC, id DESC LIMIT 500")
    suspend fun allSamples(): List<RecommendationObservationEntity>

    @Query("DELETE FROM recommendation_observation WHERE capturedAtEpochMs < :cutoffEpochMs")
    suspend fun deleteOlderThan(cutoffEpochMs: Long)

    @Query("DELETE FROM recommendation_observation WHERE capturedAtEpochMs > :cutoffEpochMs")
    suspend fun deleteNewerThan(cutoffEpochMs: Long)

    @Query(
        """
        DELETE FROM recommendation_observation
        WHERE id NOT IN (
            SELECT id FROM recommendation_observation
            ORDER BY capturedAtEpochMs DESC, id DESC LIMIT :limit
        )
        """,
    )
    suspend fun retainNewest(limit: Int)

    @Query("SELECT COUNT(*) FROM recommendation_observation")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM recommendation_observation WHERE capturedAtEpochMs < :cutoffEpochMs")
    suspend fun countOlderThan(cutoffEpochMs: Long): Int

    @Query("SELECT MAX(capturedAtEpochMs) FROM recommendation_observation")
    suspend fun maximumCapturedAt(): Long?

    @Query("DELETE FROM recommendation_observation")
    suspend fun clearAll()

    @Transaction
    suspend fun insertAndPrune(
        samples: List<RecommendationObservationEntity>,
        cutoffEpochMs: Long,
        limit: Int,
    ): List<RecommendationObservationEntity> {
        insertAll(samples)
        deleteOlderThan(cutoffEpochMs)
        retainNewest(limit)
        return allSamples()
    }

    @Transaction
    suspend fun pruneTransaction(cutoffEpochMs: Long, limit: Int): List<RecommendationObservationEntity> {
        deleteOlderThan(cutoffEpochMs)
        retainNewest(limit)
        return allSamples()
    }

    @Transaction
    suspend fun initializeTransaction(
        oldestEpochMs: Long,
        newestEpochMs: Long,
        limit: Int,
    ): List<RecommendationObservationEntity> {
        deleteOlderThan(oldestEpochMs)
        deleteNewerThan(newestEpochMs)
        retainNewest(limit)
        return allSamples()
    }

    @Transaction
    suspend fun resetTransaction() {
        clearAll()
    }
}
