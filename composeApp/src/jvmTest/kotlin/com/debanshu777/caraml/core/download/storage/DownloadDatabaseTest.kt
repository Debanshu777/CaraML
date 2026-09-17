package com.debanshu777.caraml.core.download.storage

import com.debanshu777.caraml.core.download.DownloadArtifactRequest
import com.debanshu777.caraml.core.download.DownloadArtifactState
import com.debanshu777.caraml.core.download.DownloadBatchRequest
import com.debanshu777.caraml.core.download.DownloadBatchState
import com.debanshu777.caraml.core.download.DownloadUserIntent
import com.debanshu777.caraml.core.storage.getDatabaseBuilder
import com.debanshu777.caraml.core.storage.getRoomDatabase
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.download.artifactBundleId
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DownloadDatabaseTest {
    @Test
    fun enqueueIsIdempotentAndPersistsExactIdentity() = runTest {
        val database = openDatabase("identity")
        val store = RoomDownloadTaskStore(database.downloadTaskDao())
        val request = request()
        try {
            val firstId = store.create(request, nowEpochMs = 10L)
            val secondId = store.create(request, nowEpochMs = 20L)
            val stored = assertNotNull(store.getBatch(firstId))

            assertEquals(firstId, secondId)
            assertEquals(1, stored.artifacts.size)
            assertEquals(request.artifacts.single().metadata.artifact, stored.artifacts.single().request.metadata.artifact)
            assertEquals(10L, database.downloadTaskDao().requireBatch(firstId).createdAtEpochMs)
        } finally {
            database.close()
        }
    }

    @Test
    fun onlyOneLeaseCanClaimTheSameArtifact() = runTest {
        val database = openDatabase("lease")
        val store = RoomDownloadTaskStore(database.downloadTaskDao())
        try {
            val batchId = store.create(request(), nowEpochMs = 1L)
            val artifactId = store.getBatch(batchId)!!.artifacts.single().artifactId

            assertTrue(store.claim(artifactId, owner = "worker-a", nowEpochMs = 2L, expiresAtEpochMs = 100L))
            assertFalse(store.claim(artifactId, owner = "worker-b", nowEpochMs = 3L, expiresAtEpochMs = 101L))
        } finally {
            database.close()
        }
    }

    @Test
    fun reopeningDatabasePreservesPausedCheckpointAndIntent() = runTest {
        val directory = Files.createTempDirectory("caraml-download-db-reopen")
        val dbPath = directory.resolve("downloads.db").toString()
        var database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(dbPath))
        val batchId: String
        val artifactId: String
        try {
            val store = RoomDownloadTaskStore(database.downloadTaskDao())
            batchId = store.create(request(), nowEpochMs = 1L)
            artifactId = store.getBatch(batchId)!!.artifacts.single().artifactId
            store.setUserIntent(batchId, DownloadUserIntent.PAUSE, nowEpochMs = 2L)
            store.updateProgress(
                artifactId = artifactId,
                bytesReceived = 256L,
                entityTag = "etag-1",
                lastModified = null,
                nowEpochMs = 3L,
            )
            store.transitionArtifact(artifactId, DownloadArtifactState.PAUSED, failureCode = null, nowEpochMs = 4L)
        } finally {
            database.close()
        }

        database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(dbPath))
        try {
            val restored = RoomDownloadTaskStore(database.downloadTaskDao()).getBatch(batchId)!!
            assertEquals(DownloadUserIntent.PAUSE, restored.userIntent)
            assertEquals(DownloadArtifactState.PAUSED, restored.artifacts.single().state)
            assertEquals(256L, restored.artifacts.single().bytesReceived)
            assertEquals("etag-1", database.downloadTaskDao().requireArtifact(artifactId).entityTag)
        } finally {
            database.close()
        }
    }

    @Test
    fun clearingDownloadQueueLeavesPrimaryModelDatabaseUntouched() = runTest {
        val directory = Files.createTempDirectory("caraml-download-db-isolation")
        val appDatabase = getRoomDatabase(getDatabaseBuilder(directory.resolve("caraml.db").toString()))
        val downloadDatabase = getDownloadRoomDatabase(
            getDownloadDatabaseBuilder(directory.resolve("downloads.db").toString()),
        )
        try {
            appDatabase.localModelDao().insert(
                LocalModelEntity(
                    modelId = "owner/model",
                    filename = "model.gguf",
                    localPath = "/private/model.gguf",
                    sizeBytes = 1L,
                    downloadedAt = 1L,
                    author = null,
                    libraryName = null,
                    pipelineTag = null,
                ),
            )
            val store = RoomDownloadTaskStore(downloadDatabase.downloadTaskDao())
            store.create(request(), nowEpochMs = 1L)
            store.clearAll()

            assertEquals(0, downloadDatabase.downloadTaskDao().countBatches())
            assertEquals("owner/model", appDatabase.localModelDao().getAllDownloadedFiles().first().single().modelId)
        } finally {
            downloadDatabase.close()
            appDatabase.close()
        }
    }

    @Test
    fun partiallyCompletedCancellationCanBeEnqueuedAgain() = runTest {
        val database = openDatabase("partial-cancel")
        val store = RoomDownloadTaskStore(database.downloadTaskDao())
        val request = twoArtifactRequest()
        try {
            val batchId = store.create(request, nowEpochMs = 1L)
            val artifacts = store.getBatch(batchId)!!.artifacts
            val completed = artifacts.first { it.request.primary }
            val cancelled = artifacts.first { !it.request.primary }

            assertTrue(store.claim(completed.artifactId, "worker", 2L, 100L))
            assertTrue(store.transitionArtifact(completed.artifactId, DownloadArtifactState.VERIFYING, null, 3L))
            assertTrue(store.transitionArtifact(completed.artifactId, DownloadArtifactState.COMPLETED, null, 4L))
            assertTrue(store.transitionArtifact(cancelled.artifactId, DownloadArtifactState.CANCELLED, null, 5L))
            assertEquals(DownloadBatchState.CANCELLED, store.getBatch(batchId)!!.state)

            store.create(request, nowEpochMs = 6L)

            val reactivated = store.getBatch(batchId)!!
            assertEquals(DownloadBatchState.QUEUED, reactivated.state)
            assertEquals(DownloadArtifactState.COMPLETED, reactivated.artifacts.first { it.request.primary }.state)
            assertEquals(DownloadArtifactState.QUEUED, reactivated.artifacts.first { !it.request.primary }.state)
        } finally {
            database.close()
        }
    }

    private fun openDatabase(suffix: String): DownloadDatabase {
        val path = Files.createTempDirectory("caraml-download-$suffix").resolve("downloads.db")
        return getDownloadRoomDatabase(getDownloadDatabaseBuilder(path.toString()))
    }

    private fun request(): DownloadBatchRequest {
        val identity = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = "owner/model",
                immutableRevision = "a".repeat(40),
                relativePath = "weights/model.gguf",
                remoteObjectId = "b".repeat(64),
                expectedBytes = 1_024L,
            ),
        )
        return DownloadBatchRequest(
            ownerModelId = identity.repositoryId,
            modelType = "text",
            artifacts = listOf(
                DownloadArtifactRequest(
                    metadata = DownloadMetadataDTO(
                        artifact = identity,
                        logicalRole = "model",
                        sizeBytes = identity.expectedBytes,
                        author = "owner",
                        libraryName = "gguf",
                        pipelineTag = "text-generation",
                    ),
                    primary = true,
                ),
            ),
            downloadForLaterConfirmed = false,
            displayName = "Example model",
        )
    }

    private fun twoArtifactRequest(): DownloadBatchRequest {
        val primary = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = "owner/model",
                immutableRevision = "a".repeat(40),
                relativePath = "weights/model.gguf",
                remoteObjectId = "b".repeat(64),
                expectedBytes = 1_024L,
            ),
        )
        val component = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = "owner/component",
                immutableRevision = "c".repeat(40),
                relativePath = "tokenizer.gguf",
                remoteObjectId = "d".repeat(64),
                expectedBytes = 512L,
            ),
        )
        val bundleId = requireNotNull(artifactBundleId(listOf(primary, component)))
        fun metadata(identity: DownloadArtifactIdentity, role: String) = DownloadMetadataDTO(
            artifact = identity,
            logicalRole = role,
            sizeBytes = identity.expectedBytes,
            author = "owner",
            libraryName = "gguf",
            pipelineTag = "text-generation",
            destinationRelativePath = identity.relativePath,
            bundleId = bundleId,
        )
        return DownloadBatchRequest(
            ownerModelId = primary.repositoryId,
            modelType = "text",
            artifacts = listOf(
                DownloadArtifactRequest(metadata(primary, "model"), primary = true),
                DownloadArtifactRequest(metadata(component, "tokenizer"), primary = false),
            ),
            downloadForLaterConfirmed = false,
            displayName = "Example model",
        )
    }
}
