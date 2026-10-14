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
import com.debanshu777.caraml.core.download.PublishedDownloadSnapshot
import com.debanshu777.caraml.core.download.PublishedDownloadScanPage
import com.debanshu777.caraml.core.download.isPublishedDownloadCandidate
import com.debanshu777.caraml.core.download.canTransitionTo
import com.debanshu777.caraml.core.download.downloadBatchArtifactId
import com.debanshu777.caraml.core.download.downloadBatchId
import com.debanshu777.caraml.core.recommendation.storage.EncodedModelEvidence
import com.debanshu777.caraml.core.recommendation.storage.InstalledEvidenceState
import com.debanshu777.caraml.core.recommendation.storage.PersistedModelEvidenceCodec
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transform

class RoomDownloadTaskStore(
    private val dao: DownloadTaskDao,
) : DownloadTaskStore {
    override suspend fun create(request: DownloadBatchRequest, nowEpochMs: Long): String =
        createRecord(request, nowEpochMs, newOnly = false).first

    /** The insertion result proves fresh creation; existing terminal work is never reactivated. */
    suspend fun createNewOnly(request: DownloadBatchRequest, nowEpochMs: Long): Pair<String, Boolean> =
        createRecord(request, nowEpochMs, newOnly = true)

    /** Retries only already-requested running-intent network work and preserves its checkpoint. */
    suspend fun retryNetworkIfRunningIntent(batchId: String, nowEpochMs: Long): Boolean {
        if (batchId.length != 64 || batchId.any { it !in '0'..'9' && it !in 'a'..'f' }) return false
        return dao.retryNetworkIfRunningIntent(batchId, nowEpochMs)
    }

    suspend fun runningPauseSnapshot(batchId: String): Pair<DownloadBatchSnapshot, Long>? {
        val persisted = dao.batch(batchId) ?: return null
        val snapshot = persisted.toReadSafeSnapshotOrNull() ?: return null
        if (snapshot.userIntent != DownloadUserIntent.RUN || snapshot.state in
            setOf(DownloadBatchState.COMPLETED, DownloadBatchState.FAILED_TERMINAL, DownloadBatchState.CANCELLED)) return null
        return snapshot to persisted.batch.updatedAtEpochMs
    }

    /** Pauses one observed running intent without overwriting any newer command. */
    suspend fun pauseRunningSnapshot(snapshot: DownloadBatchSnapshot, observedVersion: Long, nowEpochMs: Long): Boolean {
        val id = snapshot.batchId
        if (id.length != 64 || id.any { it !in '0'..'9' && it !in 'a'..'f' } ||
            observedVersion < 0L || observedVersion == Long.MAX_VALUE || nowEpochMs < 0L ||
            snapshot.userIntent != DownloadUserIntent.RUN) return false
        return dao.pauseRunningSnapshot(id, snapshot.state.name, snapshot.displayName, observedVersion, nowEpochMs)
    }

    /** Reads a fresh pause token; callers must verify the selected immutable identities before resuming. */
    suspend fun pausedResumeSnapshot(batchId: String): Pair<DownloadBatchSnapshot, Long>? {
        val persisted = dao.batch(batchId) ?: return null
        val snapshot = persisted.toReadSafeSnapshotOrNull() ?: return null
        if (snapshot.userIntent != DownloadUserIntent.PAUSE || snapshot.state !in
            setOf(DownloadBatchState.PAUSED, DownloadBatchState.VERIFYING)) return null
        return snapshot to persisted.batch.updatedAtEpochMs
    }

    suspend fun resumePausedSnapshot(snapshot: DownloadBatchSnapshot, observedVersion: Long, nowEpochMs: Long): Boolean {
        val id = snapshot.batchId
        if (id.length != 64 || id.any { it !in '0'..'9' && it !in 'a'..'f' } ||
            observedVersion < 0L || observedVersion == Long.MAX_VALUE || nowEpochMs < 0L || snapshot.userIntent != DownloadUserIntent.PAUSE ||
            snapshot.state !in setOf(DownloadBatchState.PAUSED, DownloadBatchState.VERIFYING)) return false
        return dao.resumePausedSnapshot(id, snapshot.state.name, observedVersion, nowEpochMs)
    }

    private suspend fun createRecord(request: DownloadBatchRequest, nowEpochMs: Long, newOnly: Boolean): Pair<String, Boolean> {
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
            val artifactId = downloadBatchArtifactId(batchId, artifact)
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
        val inserted = if (newOnly) dao.insertOnly(batch, artifacts) else {
            dao.insertIfAbsent(batch, artifacts)
            false
        }
        return batchId to inserted
    }

    override fun observeForModel(modelId: String): Flow<List<DownloadBatchSnapshot>> =
        dao.observeForModel(modelId).transform { batches ->
            val snapshots = mutableListOf<DownloadBatchSnapshot>()
            batches.forEach { persisted ->
                val snapshot = persisted.toReadSafeSnapshotOrNull()
                if (snapshot != null) {
                    snapshots += snapshot
                } else if (persisted.requiresQuarantine()) {
                    dao.quarantineMutableBatch(persisted.batch.batchId)
                }
            }
            emit(snapshots)
        }.distinctUntilChanged()

    override fun observeQueue(): Flow<List<DownloadBatchSnapshot>> =
        dao.observeQueue().transform { batches ->
            val snapshots = mutableListOf<DownloadBatchSnapshot>()
            batches.forEach { persisted ->
                val snapshot = persisted.toReadSafeSnapshotOrNull()
                if (snapshot != null) snapshots += snapshot
                else if (persisted.requiresQuarantine()) dao.quarantineMutableBatch(persisted.batch.batchId)
            }
            emit(snapshots)
        }.distinctUntilChanged()

    override suspend fun getBatch(batchId: String): DownloadBatchSnapshot? {
        val persisted = dao.batch(batchId) ?: return null
        persisted.toReadSafeSnapshotOrNull()?.let { return it }
        if (persisted.requiresQuarantine()) dao.quarantineMutableBatch(batchId)
        return null
    }

    override suspend fun recoverableBatches(): List<DownloadBatchSnapshot> {
        val recovered = mutableListOf<DownloadBatchSnapshot>()
        dao.recoverableBatches().forEach { persisted ->
            persisted.toMutationSafeSnapshotOrNull()?.let(recovered::add)
                ?: dao.quarantineMutableBatch(persisted.batch.batchId)
        }
        return recovered
    }

    override suspend fun publishedDownloadCandidates(nowEpochMs: Long): List<PublishedDownloadSnapshot> =
        scanPublishedDownloads(nowEpochMs, null).candidates

    override suspend fun scanPublishedDownloads(nowEpochMs: Long, afterBatchId: String?): PublishedDownloadScanPage {
        if (nowEpochMs < 0L || afterBatchId != null &&
            (afterBatchId.length != 64 || afterBatchId.any { it !in '0'..'9' && it !in 'a'..'f' })) {
            return PublishedDownloadScanPage(emptyList(), null)
        }
        val scanned = dao.publishedDownloadCandidates(nowEpochMs, afterBatchId)
        val candidates = scanned.mapNotNull { persisted ->
            val snapshot = persisted.toMutationSafeSnapshotOrNull() ?: return@mapNotNull null
            if (!snapshot.isPublishedDownloadCandidate()) return@mapNotNull null
            PublishedDownloadSnapshot(snapshot, persisted.batch.updatedAtEpochMs)
        }
        // Advance across unreadable rows too; a corrupt observation never changes a queue row.
        return PublishedDownloadScanPage(candidates, scanned.lastOrNull()?.batch?.batchId)
    }

    override suspend fun completePublishedDownload(expected: PublishedDownloadSnapshot, nowEpochMs: Long): Boolean {
        if (nowEpochMs < 0L || expected.observedVersion < 0L || expected.observedVersion == Long.MAX_VALUE ||
            !expected.batch.isPublishedDownloadCandidate()) return false
        val persisted = dao.batch(expected.batch.batchId) ?: return false
        if (persisted.batch.updatedAtEpochMs != expected.observedVersion ||
            persisted.toMutationSafeSnapshotOrNull() != expected.batch) return false
        return dao.completePublishedSnapshot(persisted, nowEpochMs)
    }

    override suspend fun claim(
        artifactId: String,
        owner: String,
        nowEpochMs: Long,
        expiresAtEpochMs: Long,
    ): Boolean {
        require(owner.isNotBlank() && owner.length <= 128 && owner.none(Char::isISOControl))
        require(expiresAtEpochMs > nowEpochMs)
        val entity = dao.artifact(artifactId) ?: return false
        val batch = dao.batch(entity.batchId) ?: return false
        val snapshot = batch.toMutationSafeSnapshotOrNull()
        if (snapshot == null) {
            dao.quarantineMutableBatch(entity.batchId)
            return false
        }
        if (snapshot.artifacts.none { it.artifactId == artifactId }) return false
        val claimed = dao.claim(artifactId, owner, nowEpochMs, expiresAtEpochMs) == 1
        if (claimed) refreshBatchForArtifact(artifactId, nowEpochMs)
        return claimed
    }

    override suspend fun restartTransferCheckpoint(
        artifactId: String, owner: String, previousBytes: Long, nowEpochMs: Long,
    ): Boolean {
        require(owner.isNotBlank() && owner.length <= 128 && owner.none(Char::isISOControl))
        require(previousBytes > 0L)
        return dao.restartTransferCheckpoint(artifactId, owner, previousBytes, nowEpochMs) == 1
    }

    override suspend fun updateTransferProgress(
        artifactId: String, owner: String, bytesReceived: Long, entityTag: String?, lastModified: String?,
        nowEpochMs: Long, expiresAtEpochMs: Long,
    ): Boolean {
        require(owner.isNotBlank() && owner.length <= 128 && owner.none(Char::isISOControl))
        require(bytesReceived >= 0L && expiresAtEpochMs > nowEpochMs)
        require(entityTag == null || entityTag.length <= 512 && entityTag.none(Char::isISOControl))
        require(lastModified == null || lastModified.length <= 128 && lastModified.none(Char::isISOControl))
        return dao.updateTransferProgress(artifactId, owner, bytesReceived, entityTag, lastModified, nowEpochMs, expiresAtEpochMs) == 1
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

    override suspend fun requeueMissingCompletedArtifact(
        artifactId: String,
        nowEpochMs: Long,
    ): Boolean {
        val current = dao.artifact(artifactId) ?: return false
        val changed = dao.requeueMissingCompletedArtifact(artifactId, nowEpochMs) == 1
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

    override suspend fun transitionPlatformTask(
        artifactId: String,
        platformTaskId: String,
        state: DownloadArtifactState,
        failureCode: DownloadFailureCode?,
        completedBytes: Long?,
        nowEpochMs: Long,
    ): Boolean {
        require(platformTaskId.isNotBlank() && platformTaskId.length <= 128 && platformTaskId.none(Char::isISOControl))
        require(state in PLATFORM_COMPLETION_STATES) { "Invalid platform completion state" }
        require(completedBytes == null || completedBytes > 0L)
        require(
            if (state == DownloadArtifactState.VERIFYING) {
                failureCode == null && completedBytes != null
            } else {
                failureCode != null && completedBytes == null
            },
        ) { "Invalid platform completion transition" }
        val entity = dao.artifact(artifactId) ?: return false
        val changed = dao.transitionPlatformTask(
            artifactId = artifactId,
            platformTaskId = platformTaskId,
            nextState = state.name,
            failureCode = failureCode?.name,
            completedBytes = completedBytes,
            nowEpochMs = nowEpochMs,
        ) == 1
        if (changed) refreshBatch(entity.batchId, nowEpochMs)
        return changed
    }

    override suspend fun bindPlatformTask(
        batchId: String,
        platformTaskId: String,
        nowEpochMs: Long,
    ): Boolean {
        require(platformTaskId.isNotBlank() && platformTaskId.length <= 128 && platformTaskId.none(Char::isISOControl))
        return dao.bindPlatformTask(batchId, platformTaskId, nowEpochMs) > 0
    }

    override suspend fun pausePlatformTask(
        batchId: String,
        platformTaskId: String,
        nowEpochMs: Long,
    ): Boolean {
        require(platformTaskId.isNotBlank() && platformTaskId.length <= 128 && platformTaskId.none(Char::isISOControl))
        val changed = dao.pausePlatformTask(batchId, platformTaskId, nowEpochMs)
        if (changed) refreshBatch(batchId, nowEpochMs)
        return changed
    }

    override suspend fun checkpointCancellation(
        batchId: String,
        artifactId: String,
        owner: String,
        nowEpochMs: Long,
    ): Boolean {
        require(owner.isNotBlank() && owner.length <= 128 && owner.none(Char::isISOControl))
        val entity = dao.artifact(artifactId) ?: return false
        if (entity.batchId != batchId) return false
        val changed = dao.checkpointCancellation(artifactId, owner, nowEpochMs) == 1
        if (changed) refreshBatch(batchId, nowEpochMs)
        return changed
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

    private companion object {
        val PLATFORM_COMPLETION_STATES = setOf(
            DownloadArtifactState.VERIFYING,
            DownloadArtifactState.FAILED_RETRYABLE,
            DownloadArtifactState.FAILED_TERMINAL,
        )
    }
}

private fun DownloadBatchWithArtifacts.toSnapshot(): DownloadBatchSnapshot {
    val intent = strictEnum<DownloadUserIntent>(batch.userIntent)
    val artifactSnapshots = artifacts.sortedBy(DownloadArtifactEntity::artifactId).map { artifact ->
        val metadata = artifact.toMetadata()
        DownloadArtifactSnapshot(
            artifactId = artifact.artifactId,
            batchId = artifact.batchId,
            request = DownloadArtifactRequest(
                metadata = metadata,
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
        evidence = batch.restoreEvidence(),
        failureCode = batch.failureCode?.let(::strictEnum),
    )
}

private fun DownloadBatchWithArtifacts.toReadSafeSnapshotOrNull(): DownloadBatchSnapshot? = try {
    val snapshot = toSnapshot()
    validatePersistedStructure(snapshot)
    validateExactMutableIdentity(snapshot)
    snapshot
} catch (_: Exception) {
    null
}

private fun DownloadBatchWithArtifacts.toMutationSafeSnapshotOrNull(): DownloadBatchSnapshot? = try {
    val snapshot = toSnapshot()
    validatePersistedStructure(snapshot)
    validateExactMutableIdentity(snapshot)
    snapshot
} catch (_: Exception) {
    null
}

private fun DownloadBatchWithArtifacts.validatePersistedStructure(snapshot: DownloadBatchSnapshot) {
    val persistedById = artifacts.associateBy(DownloadArtifactEntity::artifactId)
    check(persistedById.size == artifacts.size) { "Corrupt persisted artifact identity" }
    snapshot.artifacts.forEach { artifact ->
        val persisted = checkNotNull(persistedById[artifact.artifactId]) {
            "Corrupt persisted artifact identity"
        }
        check(persisted.batchId == snapshot.batchId) { "Corrupt persisted batch link" }
        check(persisted.stagingToken == artifact.artifactId) { "Corrupt persisted staging identity" }
        check(artifact.bytesReceived in 0L..artifact.expectedBytes && artifact.retryCount >= 0) {
            "Corrupt persisted artifact progress"
        }
    }
}

private fun DownloadBatchWithArtifacts.validateExactMutableIdentity(snapshot: DownloadBatchSnapshot) {
    val request = DownloadBatchRequest(
        ownerModelId = snapshot.ownerModelId,
        modelType = snapshot.modelType,
        artifacts = snapshot.artifacts.map(DownloadArtifactSnapshot::request),
        evidence = snapshot.evidence,
        downloadForLaterConfirmed = batch.downloadForLaterConfirmed,
        displayName = snapshot.displayName,
    )
    check(downloadBatchId(request) == snapshot.batchId) { "Corrupt persisted batch identity" }
    snapshot.artifacts.forEach { artifact ->
        check(artifact.artifactId == downloadBatchArtifactId(snapshot.batchId, artifact.request)) {
            "Corrupt persisted artifact identity"
        }
    }
}

private fun DownloadBatchWithArtifacts.requiresQuarantine(): Boolean =
    batch.state !in TERMINAL_BATCH_STATE_NAMES || artifacts.any { it.state !in TERMINAL_ARTIFACT_STATE_NAMES }

private val DownloadBatchState.isTerminal: Boolean
    get() = this in setOf(
        DownloadBatchState.COMPLETED,
        DownloadBatchState.FAILED_TERMINAL,
        DownloadBatchState.CANCELLED,
    )

private val DownloadArtifactState.isTerminal: Boolean
    get() = this in setOf(
        DownloadArtifactState.COMPLETED,
        DownloadArtifactState.FAILED_TERMINAL,
        DownloadArtifactState.CANCELLED,
    )

private val TERMINAL_BATCH_STATE_NAMES = DownloadBatchState.entries
    .filter(DownloadBatchState::isTerminal)
    .mapTo(mutableSetOf(), DownloadBatchState::name)

private val TERMINAL_ARTIFACT_STATE_NAMES = DownloadArtifactState.entries
    .filter(DownloadArtifactState::isTerminal)
    .mapTo(mutableSetOf(), DownloadArtifactState::name)

private fun DownloadArtifactEntity.toMetadata(): DownloadMetadataDTO {
    val identity = requireNotNull(
        DownloadArtifactIdentity.create(
            repositoryId = repositoryId,
            immutableRevision = immutableRevision,
            relativePath = relativePath,
            remoteObjectId = remoteObjectId,
            expectedBytes = expectedBytes,
        ),
    ) { "Corrupt persisted artifact identity" }
    return DownloadMetadataDTO(
        artifact = identity,
        logicalRole = logicalRole,
        sizeBytes = expectedBytes,
        author = author,
        libraryName = libraryName,
        pipelineTag = pipelineTag,
        contextLength = contextLength,
        destinationRelativePath = destinationRelativePath,
        bundleId = bundleId,
    )
}

private fun DownloadBatchEntity.restoreEvidence(): EncodedModelEvidence {
    val fields = listOf(evidenceState, evidenceSchemaVersion, evidencePayload, evidenceSha256)
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
