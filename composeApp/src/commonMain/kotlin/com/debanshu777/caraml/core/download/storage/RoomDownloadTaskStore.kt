package com.debanshu777.caraml.core.download.storage

import com.debanshu777.caraml.core.download.DownloadArtifactRequest
import com.debanshu777.caraml.core.download.DownloadArtifactSnapshot
import com.debanshu777.caraml.core.download.DownloadArtifactState
import com.debanshu777.caraml.core.download.DownloadBatchRequest
import com.debanshu777.caraml.core.download.DownloadBatchSnapshot
import com.debanshu777.caraml.core.download.DownloadBatchState
import com.debanshu777.caraml.core.download.DownloadFailureCode
import com.debanshu777.caraml.core.download.DownloadTaskStore
import com.debanshu777.caraml.core.download.DownloadUserIntent
import com.debanshu777.caraml.core.download.canTransitionTo
import com.debanshu777.caraml.core.download.downloadArtifactTaskId
import com.debanshu777.caraml.core.download.downloadBatchId
import com.debanshu777.caraml.core.download.pendingEvidence
import com.debanshu777.caraml.core.recommendation.storage.EncodedModelEvidence
import com.debanshu777.caraml.core.recommendation.storage.InstalledEvidenceState
import com.debanshu777.caraml.core.recommendation.storage.PersistedModelEvidenceCodec
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomDownloadTaskStore(
    private val dao: DownloadTaskDao,
) : DownloadTaskStore {
    override suspend fun create(request: DownloadBatchRequest, nowEpochMs: Long): String {
        val batchId = downloadBatchId(request)
        val batch = DownloadBatchEntity(
            batchId = batchId,
            ownerModelId = request.ownerModelId,
            modelType = request.modelType,
            displayName = request.displayName,
            state = DownloadBatchState.QUEUED.name,
            userIntent = DownloadUserIntent.RUN.name,
            failureCode = null,
            evidenceState = request.evidence.state.name,
            evidenceSchemaVersion = request.evidence.schemaVersion,
            evidencePayload = request.evidence.payload,
            evidenceSha256 = request.evidence.sha256,
            downloadForLaterConfirmed = request.downloadForLaterConfirmed,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
        )
        val artifacts = request.artifacts.map { artifact ->
            val artifactId = downloadArtifactTaskId(artifact)
            val metadata = artifact.metadata
            DownloadArtifactEntity(
                artifactId = artifactId,
                batchId = batchId,
                repositoryId = metadata.artifact.repositoryId,
                immutableRevision = metadata.artifact.immutableRevision,
                relativePath = metadata.artifact.relativePath,
                remoteObjectId = metadata.artifact.remoteObjectId,
                expectedBytes = metadata.artifact.expectedBytes,
                logicalRole = metadata.logicalRole,
                destinationRelativePath = metadata.destinationRelativePath,
                bundleId = metadata.bundleId,
                isPrimary = artifact.primary,
                author = metadata.author,
                libraryName = metadata.libraryName,
                pipelineTag = metadata.pipelineTag,
                contextLength = metadata.contextLength,
                state = DownloadArtifactState.QUEUED.name,
                bytesReceived = 0L,
                entityTag = null,
                lastModified = null,
                failureCode = null,
                retryCount = 0,
                platformTaskId = null,
                leaseOwner = null,
                leaseExpiresAtEpochMs = null,
                stagingToken = artifactId,
                updatedAtEpochMs = nowEpochMs,
            )
        }
        dao.insertIfAbsent(batch, artifacts)
        return batchId
    }

    override fun observeForModel(modelId: String): Flow<List<DownloadBatchSnapshot>> =
        dao.observeForModel(modelId).map { batches -> batches.map(DownloadBatchWithArtifacts::toSnapshot) }

    override suspend fun getBatch(batchId: String): DownloadBatchSnapshot? = dao.batch(batchId)?.toSnapshot()

    override suspend fun recoverableBatches(): List<DownloadBatchSnapshot> =
        dao.recoverableBatches().map(DownloadBatchWithArtifacts::toSnapshot)

    override suspend fun claim(
        artifactId: String,
        owner: String,
        nowEpochMs: Long,
        expiresAtEpochMs: Long,
    ): Boolean {
        require(owner.isNotBlank() && owner.length <= 128 && owner.none(Char::isISOControl))
        require(expiresAtEpochMs > nowEpochMs)
        val claimed = dao.claim(artifactId, owner, nowEpochMs, expiresAtEpochMs) == 1
        if (claimed) refreshBatchForArtifact(artifactId, nowEpochMs)
        return claimed
    }

    override suspend fun updateProgress(
        artifactId: String,
        bytesReceived: Long,
        entityTag: String?,
        lastModified: String?,
        nowEpochMs: Long,
    ): Boolean {
        require(bytesReceived >= 0L)
        require(entityTag == null || entityTag.length <= 512 && entityTag.none(Char::isISOControl))
        require(lastModified == null || lastModified.length <= 128 && lastModified.none(Char::isISOControl))
        return dao.updateProgress(artifactId, bytesReceived, entityTag, lastModified, nowEpochMs) == 1
    }

    override suspend fun transitionArtifact(
        artifactId: String,
        state: DownloadArtifactState,
        failureCode: DownloadFailureCode?,
        nowEpochMs: Long,
    ): Boolean {
        val current = dao.requireArtifact(artifactId)
        val currentState = strictEnum<DownloadArtifactState>(current.state)
        require(currentState.canTransitionTo(state)) { "Illegal artifact transition" }
        val changed = dao.compareAndSetArtifactState(
            artifactId = artifactId,
            currentState = currentState.name,
            nextState = state.name,
            failureCode = failureCode?.name,
            nowEpochMs = nowEpochMs,
            releaseLease = state != DownloadArtifactState.RUNNING,
        ) == 1
        if (changed) refreshBatch(current.batchId, nowEpochMs)
        return changed
    }

    override suspend fun setUserIntent(
        batchId: String,
        intent: DownloadUserIntent,
        nowEpochMs: Long,
    ): Boolean = dao.setUserIntent(batchId, intent.name, nowEpochMs) == 1

    override suspend fun setPlatformTaskId(
        artifactId: String,
        platformTaskId: String?,
        nowEpochMs: Long,
    ): Boolean {
        require(platformTaskId == null || platformTaskId.length <= 256 && platformTaskId.none(Char::isISOControl))
        return dao.setPlatformTaskId(artifactId, platformTaskId, nowEpochMs) == 1
    }

    override suspend fun releaseLease(artifactId: String, owner: String, nowEpochMs: Long): Boolean =
        dao.releaseLease(artifactId, owner, nowEpochMs) == 1

    override suspend fun clearAll() = dao.clearAll()

    private suspend fun refreshBatchForArtifact(artifactId: String, nowEpochMs: Long) {
        refreshBatch(dao.requireArtifact(artifactId).batchId, nowEpochMs)
    }

    private suspend fun refreshBatch(batchId: String, nowEpochMs: Long) {
        val artifacts = dao.artifactsForBatch(batchId)
        val states = artifacts.map { strictEnum<DownloadArtifactState>(it.state) }
        val state = deriveBatchState(states)
        val failure = artifacts.firstNotNullOfOrNull { it.failureCode }
        dao.updateBatchState(batchId, state.name, failure, nowEpochMs)
    }
}

private fun DownloadBatchWithArtifacts.toSnapshot(): DownloadBatchSnapshot {
    val intent = strictEnum<DownloadUserIntent>(batch.userIntent)
    val artifactSnapshots = artifacts.sortedBy(DownloadArtifactEntity::artifactId).map { artifact ->
        val identity = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = artifact.repositoryId,
                immutableRevision = artifact.immutableRevision,
                relativePath = artifact.relativePath,
                remoteObjectId = artifact.remoteObjectId,
                expectedBytes = artifact.expectedBytes,
            ),
        ) { "Corrupt persisted artifact identity" }
        DownloadArtifactSnapshot(
            artifactId = artifact.artifactId,
            batchId = artifact.batchId,
            request = DownloadArtifactRequest(
                metadata = DownloadMetadataDTO(
                    artifact = identity,
                    logicalRole = artifact.logicalRole,
                    sizeBytes = artifact.expectedBytes,
                    author = artifact.author,
                    libraryName = artifact.libraryName,
                    pipelineTag = artifact.pipelineTag,
                    contextLength = artifact.contextLength,
                    destinationRelativePath = artifact.destinationRelativePath,
                    bundleId = artifact.bundleId,
                ),
                primary = artifact.isPrimary,
            ),
            state = strictEnum(artifact.state),
            userIntent = intent,
            bytesReceived = artifact.bytesReceived,
            expectedBytes = artifact.expectedBytes,
            entityTag = artifact.entityTag,
            lastModified = artifact.lastModified,
            platformTaskId = artifact.platformTaskId,
            failureCode = artifact.failureCode?.let(::strictEnum),
            retryCount = artifact.retryCount,
        )
    }
    return DownloadBatchSnapshot(
        batchId = batch.batchId,
        ownerModelId = batch.ownerModelId,
        modelType = batch.modelType,
        displayName = batch.displayName,
        state = strictEnum(batch.state),
        userIntent = intent,
        artifacts = artifactSnapshots,
        evidence = batch.restoreEvidence(artifactSnapshots.map { it.request.metadata.artifact }),
        failureCode = batch.failureCode?.let(::strictEnum),
    )
}

private fun DownloadBatchEntity.restoreEvidence(
    artifacts: Collection<DownloadArtifactIdentity>,
): EncodedModelEvidence {
    val fields = listOf(evidenceState, evidenceSchemaVersion, evidencePayload, evidenceSha256)
    if (fields.all { it == null }) {
        return try {
            pendingEvidence(artifacts)
        } catch (cause: IllegalArgumentException) {
            throw IllegalStateException("Corrupt persisted evidence", cause)
        }
    }
    check(fields.none { it == null }) { "Corrupt persisted evidence" }
    val encoded = EncodedModelEvidence(
        state = strictEnum<InstalledEvidenceState>(requireNotNull(evidenceState)),
        schemaVersion = requireNotNull(evidenceSchemaVersion),
        payload = requireNotNull(evidencePayload),
        sha256 = requireNotNull(evidenceSha256),
    )
    try {
        PersistedModelEvidenceCodec().decode(encoded)
    } catch (cause: IllegalArgumentException) {
        throw IllegalStateException("Corrupt persisted evidence", cause)
    }
    return encoded
}

private fun deriveBatchState(states: List<DownloadArtifactState>): DownloadBatchState = when {
    states.all { it == DownloadArtifactState.COMPLETED } -> DownloadBatchState.COMPLETED
    states.any { it == DownloadArtifactState.FAILED_TERMINAL } -> DownloadBatchState.FAILED_TERMINAL
    states.any { it == DownloadArtifactState.FAILED_RETRYABLE } -> DownloadBatchState.FAILED_RETRYABLE
    states.any { it == DownloadArtifactState.VERIFYING } -> DownloadBatchState.VERIFYING
    states.any { it == DownloadArtifactState.RUNNING } -> DownloadBatchState.RUNNING
    states.any { it == DownloadArtifactState.WAITING_FOR_NETWORK } -> DownloadBatchState.WAITING_FOR_NETWORK
    states.all { it in setOf(DownloadArtifactState.COMPLETED, DownloadArtifactState.CANCELLED) } &&
        states.any { it == DownloadArtifactState.CANCELLED } -> DownloadBatchState.CANCELLED
    states.any { it == DownloadArtifactState.PAUSED } -> DownloadBatchState.PAUSED
    else -> DownloadBatchState.QUEUED
}

private inline fun <reified T : Enum<T>> strictEnum(value: String): T =
    enumValues<T>().singleOrNull { it.name == value } ?: error("Invalid persisted enum")
