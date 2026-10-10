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

    /** Inserts fresh work atomically and never changes an existing batch's intent or state. */
    @Transaction
    suspend fun insertOnly(batch: DownloadBatchEntity, artifacts: List<DownloadArtifactEntity>): Boolean {
        if (insertBatch(batch) == -1L) return false
        insertArtifacts(artifacts)
        return true
    }

    @Query("""
        UPDATE download_batch SET state = 'QUEUED', failure_code = NULL, updated_at_epoch_ms = MAX(:nowEpochMs, updated_at_epoch_ms + 1)
        WHERE batch_id = :batchId AND state = 'FAILED_RETRYABLE' AND failure_code = 'NETWORK'
          AND user_intent = 'RUN'
          AND EXISTS (SELECT 1 FROM download_artifact WHERE batch_id = :batchId
              AND state = 'FAILED_RETRYABLE' AND failure_code = 'NETWORK')
          AND NOT EXISTS (SELECT 1 FROM download_artifact WHERE batch_id = :batchId
              AND lease_owner IS NOT NULL AND lease_expires_at_epoch_ms > :nowEpochMs)
    """)
    suspend fun claimNetworkRetryWithRunningIntent(batchId: String, nowEpochMs: Long): Int

    @Query("""
        UPDATE download_artifact SET state = 'QUEUED', failure_code = NULL, platform_task_id = NULL,
            lease_owner = NULL, lease_expires_at_epoch_ms = NULL, updated_at_epoch_ms = :nowEpochMs
        WHERE batch_id = :batchId AND state = 'FAILED_RETRYABLE' AND failure_code = 'NETWORK'
          AND EXISTS (SELECT 1 FROM download_batch WHERE batch_id = :batchId AND user_intent = 'RUN')
    """)
    suspend fun queueNetworkRetryArtifacts(batchId: String, nowEpochMs: Long): Int

    /** Intent-preserving retry CAS; never resumes a paused/cancelled batch or interrupts a live lease. */
    @Transaction
    suspend fun retryNetworkIfRunningIntent(batchId: String, nowEpochMs: Long): Boolean {
        if (claimNetworkRetryWithRunningIntent(batchId, nowEpochMs) != 1) return false
        check(queueNetworkRetryArtifacts(batchId, nowEpochMs) > 0)
        return true
    }

    @Query("""
        UPDATE download_batch SET user_intent = 'PAUSE',
            state = CASE WHEN state = 'VERIFYING' THEN state ELSE 'PAUSED' END,
            updated_at_epoch_ms = MAX(:nowEpochMs, updated_at_epoch_ms + 1)
        WHERE batch_id = :batchId AND user_intent = 'RUN' AND state = :expectedState
          AND state NOT IN ('COMPLETED', 'FAILED_TERMINAL', 'CANCELLED')
          AND updated_at_epoch_ms = :observedVersion AND display_name = :expectedDisplayName
    """)
    suspend fun claimRunningPause(batchId: String, expectedState: String, expectedDisplayName: String,
        observedVersion: Long, nowEpochMs: Long): Int

    @Query("""
        UPDATE download_artifact SET state = 'PAUSED', failure_code = NULL,
            lease_owner = NULL, lease_expires_at_epoch_ms = NULL, updated_at_epoch_ms = :nowEpochMs
        WHERE batch_id = :batchId AND state IN ('QUEUED', 'RUNNING', 'WAITING_FOR_NETWORK')
    """)
    suspend fun pauseRunningArtifacts(batchId: String, nowEpochMs: Long): Int

    @Transaction
    suspend fun pauseRunningSnapshot(batchId: String, expectedState: String, expectedDisplayName: String,
        observedVersion: Long, nowEpochMs: Long): Boolean {
        if (claimRunningPause(batchId, expectedState, expectedDisplayName, observedVersion, nowEpochMs) != 1) return false
        pauseRunningArtifacts(batchId, nowEpochMs)
        return true
    }

    @Query("""
        UPDATE download_batch SET user_intent = 'RUN',
            state = CASE WHEN state = 'PAUSED' THEN 'QUEUED' ELSE state END,
            failure_code = NULL, updated_at_epoch_ms = MAX(:nowEpochMs, updated_at_epoch_ms + 1)
        WHERE batch_id = :batchId AND user_intent = 'PAUSE' AND state = :expectedState
          AND state IN ('PAUSED', 'VERIFYING') AND updated_at_epoch_ms = :observedVersion
          AND NOT EXISTS (SELECT 1 FROM download_artifact WHERE batch_id = :batchId
              AND lease_owner IS NOT NULL AND lease_expires_at_epoch_ms > :nowEpochMs)
    """)
    suspend fun claimExplicitPausedResume(batchId: String, expectedState: String, observedVersion: Long, nowEpochMs: Long): Int

    @Query("""
        UPDATE download_artifact SET state = 'QUEUED', failure_code = NULL, platform_task_id = NULL,
            lease_owner = NULL, lease_expires_at_epoch_ms = NULL, updated_at_epoch_ms = :nowEpochMs
        WHERE batch_id = :batchId AND state = 'PAUSED'
    """)
    suspend fun queueExplicitPausedArtifacts(batchId: String, nowEpochMs: Long): Int

    /** An explicit resume action against one observed pause, preserving newer intent and verification. */
    @Transaction
    suspend fun resumePausedSnapshot(batchId: String, expectedState: String, observedVersion: Long, nowEpochMs: Long): Boolean {
        if (claimExplicitPausedResume(batchId, expectedState, observedVersion, nowEpochMs) != 1) return false
        queueExplicitPausedArtifacts(batchId, nowEpochMs)
        return true
    }

    @Query(
        """
        UPDATE download_batch
        SET state = 'QUEUED', user_intent = 'RUN', failure_code = NULL, updated_at_epoch_ms = MAX(:nowEpochMs, updated_at_epoch_ms + 1)
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
    @Query("SELECT * FROM download_batch WHERE state NOT IN ('COMPLETED', 'CANCELLED') ORDER BY updated_at_epoch_ms DESC LIMIT 64")
    fun observeQueue(): Flow<List<DownloadBatchWithArtifacts>>

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

    @Transaction
    @Query("""
        SELECT * FROM download_batch b
        WHERE b.model_type = 'text' AND b.user_intent != 'CANCEL'
          AND length(b.batch_id) = 64 AND b.batch_id NOT GLOB '*[^0-9a-f]*'
          AND (:afterBatchId IS NULL OR b.batch_id > :afterBatchId)
          AND (b.state = 'VERIFYING' OR (b.state = 'FAILED_TERMINAL' AND b.failure_code = 'INTEGRITY'))
          AND (SELECT COUNT(*) FROM download_artifact a WHERE a.batch_id = b.batch_id) = 1
          AND EXISTS (SELECT 1 FROM download_artifact a WHERE a.batch_id = b.batch_id
              AND a.is_primary = 1 AND a.logical_role = 'model' AND a.expected_bytes > 0
              AND a.bytes_received = a.expected_bytes
              AND (a.state = 'VERIFYING' OR (a.state = 'FAILED_TERMINAL' AND a.failure_code = 'INTEGRITY')))
          AND NOT EXISTS (SELECT 1 FROM download_artifact a WHERE a.batch_id = b.batch_id
              AND a.lease_owner IS NOT NULL AND (a.lease_expires_at_epoch_ms IS NULL OR a.lease_expires_at_epoch_ms > :nowEpochMs))
        ORDER BY b.batch_id ASC LIMIT 64
    """)
    suspend fun publishedDownloadCandidates(nowEpochMs: Long, afterBatchId: String?): List<DownloadBatchWithArtifacts>

    @Query("""
        UPDATE download_artifact SET state = 'COMPLETED', failure_code = NULL,
            lease_owner = NULL, lease_expires_at_epoch_ms = NULL, updated_at_epoch_ms = :nowEpochMs
        WHERE batch_id = :batchId
    """)
    suspend fun completePublishedArtifacts(batchId: String, nowEpochMs: Long): Int

    /** Exact observation CAS inside one transaction; newer commands, progress and leases win. */
    @Transaction
    suspend fun completePublishedSnapshot(expected: DownloadBatchWithArtifacts, nowEpochMs: Long): Boolean {
        val current = batch(expected.batch.batchId) ?: return false
        if (current.batch != expected.batch || current.artifacts.sortedBy { it.artifactId } !=
            expected.artifacts.sortedBy { it.artifactId } || current.batch.userIntent == "CANCEL" ||
            current.batch.updatedAtEpochMs == Long.MAX_VALUE || current.artifacts.any {
                it.leaseOwner != null && (it.leaseExpiresAtEpochMs == null || it.leaseExpiresAtEpochMs > nowEpochMs)
            }) return false
        check(completePublishedArtifacts(current.batch.batchId, nowEpochMs) == current.artifacts.size)
        updateBatchState(current.batch.batchId, "COMPLETED", null, nowEpochMs)
        return true
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
          AND EXISTS (SELECT 1 FROM download_batch
              WHERE download_batch.batch_id = download_artifact.batch_id AND download_batch.user_intent = 'RUN')
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
        SET bytes_received = 0, entity_tag = NULL, last_modified = NULL, updated_at_epoch_ms = :nowEpochMs
        WHERE artifact_id = :artifactId AND state = 'RUNNING'
          AND lease_owner = :owner AND lease_expires_at_epoch_ms > :nowEpochMs
          AND bytes_received = :previousBytes AND :previousBytes > 0
          AND EXISTS (SELECT 1 FROM download_batch
              WHERE download_batch.batch_id = download_artifact.batch_id AND user_intent = 'RUN')
        """,
    )
    suspend fun restartTransferCheckpoint(artifactId: String, owner: String, previousBytes: Long, nowEpochMs: Long): Int

    @Query(
        """
        UPDATE download_artifact
        SET bytes_received = :bytesReceived, entity_tag = :entityTag, last_modified = :lastModified,
            lease_expires_at_epoch_ms = MAX(lease_expires_at_epoch_ms, :expiresAtEpochMs), updated_at_epoch_ms = :nowEpochMs
        WHERE artifact_id = :artifactId AND state = 'RUNNING' AND lease_owner = :owner
          AND lease_expires_at_epoch_ms > :nowEpochMs AND :expiresAtEpochMs > :nowEpochMs
          AND bytes_received <= :bytesReceived AND :bytesReceived <= expected_bytes
          AND EXISTS (SELECT 1 FROM download_batch
              WHERE download_batch.batch_id = download_artifact.batch_id AND user_intent = 'RUN')
        """,
    )
    suspend fun updateTransferProgress(artifactId: String, owner: String, bytesReceived: Long,
        entityTag: String?, lastModified: String?, nowEpochMs: Long, expiresAtEpochMs: Long): Int

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
        """
        UPDATE download_artifact
        SET state = 'QUEUED', failure_code = NULL, platform_task_id = NULL,
            bytes_received = 0, entity_tag = NULL, last_modified = NULL,
            lease_owner = NULL, lease_expires_at_epoch_ms = NULL, updated_at_epoch_ms = :nowEpochMs
        WHERE artifact_id = :artifactId
          AND state = 'COMPLETED'
          AND EXISTS (
              SELECT 1 FROM download_batch
              WHERE download_batch.batch_id = download_artifact.batch_id
                AND download_batch.user_intent = 'RUN'
          )
        """,
    )
    suspend fun requeueMissingCompletedArtifact(artifactId: String, nowEpochMs: Long): Int

    @Query(
        "UPDATE download_batch SET user_intent = :intent, updated_at_epoch_ms = MAX(:nowEpochMs, updated_at_epoch_ms + 1) WHERE batch_id = :batchId",
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
            updated_at_epoch_ms = MAX(:nowEpochMs, updated_at_epoch_ms + 1)
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
        "UPDATE download_batch SET state = :state, failure_code = :failureCode, updated_at_epoch_ms = MAX(:nowEpochMs, updated_at_epoch_ms + 1) WHERE batch_id = :batchId",
    )
    suspend fun updateBatchState(batchId: String, state: String, failureCode: String?, nowEpochMs: Long): Int

    @Query("SELECT * FROM download_artifact WHERE batch_id = :batchId")
    suspend fun artifactsForBatch(batchId: String): List<DownloadArtifactEntity>

    @Query("DELETE FROM download_batch")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM download_batch")
    suspend fun countBatches(): Int
}
