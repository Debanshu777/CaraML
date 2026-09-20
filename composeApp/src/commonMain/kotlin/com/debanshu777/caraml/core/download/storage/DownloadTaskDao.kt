package com.debanshu777.caraml.core.download.storage

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class DownloadBatchWithArtifacts(
    @Embedded val batch: DownloadBatchEntity,
    @Relation(parentColumn = "batch_id", entityColumn = "batch_id")
    val artifacts: List<DownloadArtifactEntity>,
)

@Dao
interface DownloadTaskDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBatch(batch: DownloadBatchEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertArtifacts(artifacts: List<DownloadArtifactEntity>)

    @Transaction
    suspend fun insertIfAbsent(batch: DownloadBatchEntity, artifacts: List<DownloadArtifactEntity>) {
        if (insertBatch(batch) != -1L) {
            insertArtifacts(artifacts)
        } else {
            reactivateTerminalBatch(batch.batchId, batch.updatedAtEpochMs)
        }
    }

    @Query(
        """
        UPDATE download_batch
        SET state = 'QUEUED', user_intent = 'RUN', failure_code = NULL, updated_at_epoch_ms = :nowEpochMs
        WHERE batch_id = :batchId AND state IN ('FAILED_TERMINAL', 'CANCELLED')
        """,
    )
    suspend fun reactivateTerminalBatchRecord(batchId: String, nowEpochMs: Long): Int

    @Query(
        """
        UPDATE download_artifact
        SET state = 'QUEUED', failure_code = NULL, platform_task_id = NULL,
            bytes_received = 0, entity_tag = NULL, last_modified = NULL,
            lease_owner = NULL, lease_expires_at_epoch_ms = NULL, updated_at_epoch_ms = :nowEpochMs
        WHERE batch_id = :batchId AND state IN ('FAILED_TERMINAL', 'CANCELLED')
        """,
    )
    suspend fun reactivateTerminalArtifacts(batchId: String, nowEpochMs: Long)

    @Transaction
    suspend fun reactivateTerminalBatch(batchId: String, nowEpochMs: Long) {
        if (reactivateTerminalBatchRecord(batchId, nowEpochMs) == 1) {
            reactivateTerminalArtifacts(batchId, nowEpochMs)
        }
    }

    @Transaction
    @Query("SELECT * FROM download_batch WHERE batch_id = :batchId")
    suspend fun batch(batchId: String): DownloadBatchWithArtifacts?

    @Transaction
    @Query("SELECT * FROM download_batch WHERE owner_model_id = :modelId ORDER BY updated_at_epoch_ms DESC")
    fun observeForModel(modelId: String): Flow<List<DownloadBatchWithArtifacts>>

    @Transaction
    @Query("SELECT * FROM download_batch WHERE state NOT IN ('COMPLETED', 'FAILED_TERMINAL', 'CANCELLED')")
    suspend fun recoverableBatches(): List<DownloadBatchWithArtifacts>

    @Query(
        """
        UPDATE download_artifact
        SET state = 'FAILED_TERMINAL', failure_code = 'SECURE_PATH', platform_task_id = NULL,
            lease_owner = NULL, lease_expires_at_epoch_ms = NULL
        WHERE batch_id = :batchId AND state NOT IN ('COMPLETED', 'FAILED_TERMINAL', 'CANCELLED')
        """,
    )
    suspend fun quarantineMutableArtifacts(batchId: String)

    @Query(
        """
        UPDATE download_batch
        SET state = 'FAILED_TERMINAL', failure_code = 'SECURE_PATH'
        WHERE batch_id = :batchId AND state NOT IN ('COMPLETED', 'FAILED_TERMINAL', 'CANCELLED')
        """,
    )
    suspend fun quarantineMutableBatchRecord(batchId: String)

    @Transaction
    suspend fun quarantineMutableBatch(batchId: String) {
        quarantineMutableArtifacts(batchId)
        quarantineMutableBatchRecord(batchId)
    }

    @Query("SELECT * FROM download_batch WHERE batch_id = :batchId")
    suspend fun requireBatch(batchId: String): DownloadBatchEntity

    @Query("SELECT * FROM download_artifact WHERE artifact_id = :artifactId")
    suspend fun requireArtifact(artifactId: String): DownloadArtifactEntity

    @Query("SELECT * FROM download_artifact WHERE artifact_id = :artifactId")
    suspend fun artifact(artifactId: String): DownloadArtifactEntity?

    @Query(
        """
        UPDATE download_artifact
        SET state = 'RUNNING', lease_owner = :owner,
            lease_expires_at_epoch_ms = :expiresAtEpochMs, updated_at_epoch_ms = :nowEpochMs
        WHERE artifact_id = :artifactId
          AND state IN ('QUEUED', 'FAILED_RETRYABLE', 'WAITING_FOR_NETWORK')
          AND (lease_owner IS NULL OR lease_expires_at_epoch_ms < :nowEpochMs)
          AND length(bundle_id) = 64
          AND bundle_id = lower(bundle_id)
          AND bundle_id NOT GLOB '*[^0-9a-f]*'
          AND destination_relative_path LIKE '.caraml-artifacts/' || bundle_id || '/%'
          AND length(destination_relative_path) > length('.caraml-artifacts/' || bundle_id || '/')
          AND destination_relative_path NOT LIKE '%/../%'
          AND destination_relative_path NOT LIKE '%/./%'
        """,
    )
    suspend fun claim(
        artifactId: String,
        owner: String,
        nowEpochMs: Long,
        expiresAtEpochMs: Long,
    ): Int

    @Query(
        """
        UPDATE download_artifact
        SET bytes_received = :bytesReceived, entity_tag = :entityTag,
            last_modified = :lastModified, updated_at_epoch_ms = :nowEpochMs
        WHERE artifact_id = :artifactId
          AND bytes_received <= :bytesReceived
          AND :bytesReceived <= expected_bytes
        """,
    )
    suspend fun updateProgress(
        artifactId: String,
        bytesReceived: Long,
        entityTag: String?,
        lastModified: String?,
        nowEpochMs: Long,
    ): Int

    @Query(
        """
        UPDATE download_artifact
        SET state = :nextState, failure_code = :failureCode, updated_at_epoch_ms = :nowEpochMs,
            lease_owner = CASE WHEN :releaseLease THEN NULL ELSE lease_owner END,
            lease_expires_at_epoch_ms = CASE WHEN :releaseLease THEN NULL ELSE lease_expires_at_epoch_ms END
        WHERE artifact_id = :artifactId AND state = :currentState
        """,
    )
    suspend fun compareAndSetArtifactState(
        artifactId: String,
        currentState: String,
        nextState: String,
        failureCode: String?,
        nowEpochMs: Long,
        releaseLease: Boolean,
    ): Int

    @Query(
        "UPDATE download_batch SET user_intent = :intent, updated_at_epoch_ms = :nowEpochMs WHERE batch_id = :batchId",
    )
    suspend fun setUserIntent(batchId: String, intent: String, nowEpochMs: Long): Int

    @Query(
        "UPDATE download_artifact SET platform_task_id = :platformTaskId, updated_at_epoch_ms = :nowEpochMs WHERE artifact_id = :artifactId",
    )
    suspend fun setPlatformTaskId(artifactId: String, platformTaskId: String?, nowEpochMs: Long): Int

    @Query(
        """
        UPDATE download_artifact
        SET state = :nextState, failure_code = :failureCode,
            bytes_received = COALESCE(:completedBytes, bytes_received),
            platform_task_id = NULL,
            lease_owner = NULL, lease_expires_at_epoch_ms = NULL, updated_at_epoch_ms = :nowEpochMs
        WHERE artifact_id = :artifactId
          AND platform_task_id = :platformTaskId
          AND state = 'RUNNING'
          AND (:completedBytes IS NULL OR :completedBytes = expected_bytes)
          AND EXISTS (
              SELECT 1 FROM download_batch
              WHERE download_batch.batch_id = download_artifact.batch_id
                AND download_batch.user_intent = 'RUN'
          )
        """,
    )
    suspend fun transitionPlatformTask(
        artifactId: String,
        platformTaskId: String,
        nextState: String,
        failureCode: String?,
        completedBytes: Long?,
        nowEpochMs: Long,
    ): Int

    @Query(
        """
        UPDATE download_artifact
        SET platform_task_id = :platformTaskId, updated_at_epoch_ms = :nowEpochMs
        WHERE batch_id = :batchId
          AND state NOT IN ('COMPLETED', 'FAILED_TERMINAL', 'CANCELLED')
          AND EXISTS (
              SELECT 1 FROM download_batch
              WHERE download_batch.batch_id = :batchId
                AND download_batch.user_intent = 'RUN'
          )
        """,
    )
    suspend fun bindPlatformTask(
        batchId: String,
        platformTaskId: String,
        nowEpochMs: Long,
    ): Int

    @Query(
        """
        UPDATE download_artifact
        SET state = 'PAUSED', failure_code = NULL, platform_task_id = NULL,
            lease_owner = NULL, lease_expires_at_epoch_ms = NULL, updated_at_epoch_ms = :nowEpochMs
        WHERE batch_id = :batchId
          AND platform_task_id = :platformTaskId
          AND state NOT IN ('COMPLETED', 'FAILED_TERMINAL', 'CANCELLED')
          AND EXISTS (
              SELECT 1 FROM download_batch
              WHERE download_batch.batch_id = :batchId
                AND download_batch.user_intent = 'RUN'
          )
        """,
    )
    suspend fun pausePlatformTaskArtifacts(
        batchId: String,
        platformTaskId: String,
        nowEpochMs: Long,
    ): Int

    @Query(
        """
        UPDATE download_batch
        SET state = 'PAUSED', user_intent = 'PAUSE', failure_code = NULL,
            updated_at_epoch_ms = :nowEpochMs
        WHERE batch_id = :batchId AND user_intent = 'RUN'
        """,
    )
    suspend fun pausePlatformTaskBatch(batchId: String, nowEpochMs: Long): Int

    @Transaction
    suspend fun pausePlatformTask(
        batchId: String,
        platformTaskId: String,
        nowEpochMs: Long,
    ): Boolean {
        if (pausePlatformTaskArtifacts(batchId, platformTaskId, nowEpochMs) == 0) return false
        return pausePlatformTaskBatch(batchId, nowEpochMs) == 1
    }

    @Query(
        """
        UPDATE download_artifact
        SET state = CASE (
                SELECT user_intent FROM download_batch
                WHERE download_batch.batch_id = download_artifact.batch_id
            )
                WHEN 'PAUSE' THEN 'PAUSED'
                WHEN 'CANCEL' THEN 'CANCELLED'
                ELSE 'FAILED_RETRYABLE'
            END,
            failure_code = CASE (
                SELECT user_intent FROM download_batch
                WHERE download_batch.batch_id = download_artifact.batch_id
            )
                WHEN 'RUN' THEN 'NETWORK'
                ELSE NULL
            END,
            lease_owner = NULL,
            lease_expires_at_epoch_ms = NULL,
            updated_at_epoch_ms = :nowEpochMs
        WHERE artifact_id = :artifactId
          AND state = 'RUNNING'
          AND lease_owner = :owner
        """,
    )
    suspend fun checkpointCancellation(
        artifactId: String,
        owner: String,
        nowEpochMs: Long,
    ): Int

    @Query(
        """
        UPDATE download_artifact
        SET lease_owner = NULL, lease_expires_at_epoch_ms = NULL, updated_at_epoch_ms = :nowEpochMs
        WHERE artifact_id = :artifactId AND lease_owner = :owner
        """,
    )
    suspend fun releaseLease(artifactId: String, owner: String, nowEpochMs: Long): Int

    @Query(
        "UPDATE download_batch SET state = :state, failure_code = :failureCode, updated_at_epoch_ms = :nowEpochMs WHERE batch_id = :batchId",
    )
    suspend fun updateBatchState(batchId: String, state: String, failureCode: String?, nowEpochMs: Long): Int

    @Query("SELECT * FROM download_artifact WHERE batch_id = :batchId")
    suspend fun artifactsForBatch(batchId: String): List<DownloadArtifactEntity>

    @Query("DELETE FROM download_batch")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM download_batch")
    suspend fun countBatches(): Int
}
