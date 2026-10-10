package com.debanshu777.caraml.core.download

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import okio.Path.Companion.toPath
import com.debanshu777.caraml.core.download.storage.RoomDownloadTaskStore
import com.debanshu777.caraml.core.download.storage.getDownloadDatabaseBuilder
import com.debanshu777.caraml.core.download.storage.getDownloadRoomDatabase
import com.debanshu777.caraml.core.storage.catalog.InstalledModelPublicationCoordinator
import com.debanshu777.caraml.core.storage.evidence.toEntity
import com.debanshu777.caraml.core.storage.getDatabaseBuilder
import com.debanshu777.caraml.core.storage.getRoomDatabase
import com.debanshu777.huggingfacemanager.download.ArtifactBundleManifestStore
import com.debanshu777.huggingfacemanager.download.ArtifactManifestEntry
import com.debanshu777.huggingfacemanager.download.ArtifactManifestStore
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadManager
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toOkioPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class PublishedDownloadReconcilerTest {
    @Test
    fun sixtyFourNewerUnpublishedRowsCannotStarveOlderPublicationAcrossPreferenceRestarts() = runTest {
        Fixture().use { f ->
            val published = f.prepare(false)
            val unchanged = f.addUnpublishedRowsBefore(published.batchId)
            val preferencePath = File(f.root, "scan.preferences_pb").path
            repeat(2) {
                val preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                try {
                    val preferences = PreferenceDataStoreFactory.createWithPath(scope = preferenceScope) { preferencePath.toPath() }
                    f.reconciler(preferences = preferences).reconcile()
                } finally { preferenceScope.coroutineContext[Job]!!.cancelAndJoin() }
            }
            assertEquals(DownloadBatchState.COMPLETED, f.store.getBatch(published.batchId)!!.state)
            unchanged.forEach { before -> assertEquals(before, f.store.getBatch(before.batchId)) }
        }
    }

    @Test
    fun interruptedPageAdvancesDurablyAndReturnsAfterWrapWithoutMutatingFailedProofs() = runTest {
        Fixture().use { f ->
            val published = f.prepare(false)
            val untouched = f.addUnpublishedRowsBefore(published.batchId)
            withRestartedScanPreferences(f.root) { preferences -> f.reconciler(preferences = preferences).reconcile() }
            val cancelled = object : BundlePublisher by f.bundle {
                override suspend fun current(ownerModelId: String): com.debanshu777.huggingfacemanager.download.ArtifactManifest? {
                    throw CancellationException("private-sentinel")
                }
            }
            withRestartedScanPreferences(f.root) { preferences ->
                assertFailsWith<CancellationException> { f.reconciler(publisher = cancelled, preferences = preferences).reconcile() }
            }
            assertEquals(published, f.store.getBatch(published.batchId))
            // The end key wraps to the first bounded window, then revisits the interrupted target.
            withRestartedScanPreferences(f.root) { preferences -> f.reconciler(preferences = preferences).reconcile() }
            assertEquals(published, f.store.getBatch(published.batchId))
            withRestartedScanPreferences(f.root) { preferences -> f.reconciler(preferences = preferences).reconcile() }
            assertEquals(DownloadBatchState.COMPLETED, f.store.getBatch(published.batchId)!!.state)
            untouched.forEach { before -> assertEquals(before, f.store.getBatch(before.batchId)) }
        }
    }

    @Test
    fun rawCursorPassesUnreadableRowsWithoutQuarantiningOrStarvingPublication() = runTest {
        Fixture().use { f ->
            val published = f.prepare(false)
            val corrupt = f.addUnpublishedRowsBefore(published.batchId)
            androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(f.downloadPath).use { connection ->
                connection.prepare("UPDATE download_batch SET evidence_sha256 = ? WHERE batch_id != ?").use {
                    it.bindText(1, "f".repeat(64)); it.bindText(2, published.batchId); it.step()
                }
            }
            val persisted = corrupt.map { f.downloads.downloadTaskDao().batch(it.batchId)!! }
            repeat(2) { withRestartedScanPreferences(f.root) { preferences -> f.reconciler(preferences = preferences).reconcile() } }
            assertEquals(DownloadBatchState.COMPLETED, f.store.getBatch(published.batchId)!!.state)
            persisted.forEach { before -> assertEquals(before, f.downloads.downloadTaskDao().batch(before.batch.batchId)) }
        }
    }

    @Test
    fun publishedFullByteIntegrityFailureAndPausedVerificationCompleteWithoutRepublishing() = runTest {
        for (terminal in listOf(false, true)) {
            Fixture().use { f ->
                val batch = f.prepare(terminal)
                val catalogBefore = f.catalog.snapshot(batch.ownerModelId)
                val plan = File(f.paths.getModelsStorageDirectory(batch.ownerModelId), ArtifactBundleManifestStore.REPLACEMENT_PLAN_FILE_NAME)
                plan.writeText("invalid-replacement-record-retained")
                f.reconciler().reconcile()
                val complete = f.store.getBatch(batch.batchId)!!
                assertEquals(DownloadBatchState.COMPLETED, complete.state)
                assertEquals(DownloadArtifactState.COMPLETED, complete.artifacts.single().state)
                assertEquals(DownloadUserIntent.PAUSE, complete.userIntent)
                assertEquals(batch.expectedBytes, complete.bytesReceived)
                assertEquals("private-validator", complete.artifacts.single().entityTag)
                assertEquals(batch.evidence, complete.evidence)
                assertEquals(catalogBefore, f.catalog.snapshot(batch.ownerModelId))
                assertEquals("invalid-replacement-record-retained", plan.readText())
                assertTrue(f.store.observeQueue().first().none { it.batchId == batch.batchId })
            }
        }
    }

    @Test
    fun absentDamagedOrDifferentPublicationNeverCompletesStaleWork() = runTest {
        for (variant in listOf("missing", "damaged", "revision", "catalogPath", "evidence", "partial", "cancel")) {
            Fixture().use { f ->
                val batch = f.prepare(false, variant = variant)
                val before = f.store.getBatch(batch.batchId)
                f.reconciler().reconcile()
                assertEquals(before, f.store.getBatch(batch.batchId), variant)
            }
        }
    }

    @Test
    fun newerSameMillisIntentOrCheckpointInvalidatesCompletionObservation() = runTest {
        for (mutation in listOf("pause", "cancel", "checkpoint", "lease")) {
            Fixture().use { f ->
                val batch = f.prepare(false)
                val candidate = f.store.publishedDownloadCandidates(100L).single()
                when (mutation) {
                    "pause" -> f.store.setUserIntent(batch.batchId, DownloadUserIntent.PAUSE, candidate.observedVersion)
                    "cancel" -> f.store.setUserIntent(batch.batchId, DownloadUserIntent.CANCEL, candidate.observedVersion)
                    "checkpoint" -> f.store.updateProgress(batch.artifacts.single().artifactId, batch.expectedBytes, "new-validator", null, 100L)
                    "lease" -> f.rawUpdate("UPDATE download_artifact SET lease_owner='new-worker', lease_expires_at_epoch_ms=1000")
                }
                val before = f.store.getBatch(batch.batchId)
                assertFalse(f.store.completePublishedDownload(candidate, 101L), mutation)
                assertEquals(before, f.store.getBatch(batch.batchId), mutation)
            }
        }
    }

    @Test
    fun candidateQueryExcludesLiveLeaseCancelAndIncompleteBytes() = runTest {
        for (variant in listOf("lease", "cancel", "partial")) {
            Fixture().use { f ->
                f.prepare(false, variant = variant)
                assertTrue(f.store.publishedDownloadCandidates(100L).isEmpty(), variant)
            }
        }
    }

    @Test
    fun activePlatformVerificationIsNotReconciled() = runTest {
        Fixture().use { f ->
            val batch = f.prepare(false)
            val active = object : PlatformDownloadScheduler by PublishedReconciliationScheduler() {
                override suspend fun isActive(batchId: String) = true
            }
            f.reconciler(scheduler = active).reconcile()
            assertEquals(batch, f.store.getBatch(batch.batchId))
        }
    }

    @Test
    fun startupRepairsBeforeSchedulingAndConsumesDurableStopMarkersFirst() = runTest {
        for (stopMarker in listOf(false, true)) {
            Fixture().use { f ->
                val batch = f.prepare(false)
                val scheduled = mutableListOf<String>()
                val scheduler = object : PlatformDownloadScheduler by PublishedReconciliationScheduler() {
                    override suspend fun enqueue(batchId: String) { scheduled += batchId }
                    override suspend fun reconcile(liveBatchIds: Set<String>) {
                        if (stopMarker) f.store.setUserIntent(batch.batchId, DownloadUserIntent.CANCEL, 50L)
                    }
                }
                DownloadReconciler(f.store, scheduler, { 100L }, f.reconciler(scheduler = scheduler)).reconcile()
                val current = f.store.getBatch(batch.batchId)!!
                assertEquals(if (stopMarker) DownloadBatchState.CANCELLED else DownloadBatchState.COMPLETED, current.state)
                assertEquals(if (stopMarker) DownloadUserIntent.CANCEL else DownloadUserIntent.PAUSE, current.userIntent)
                assertTrue(scheduled.isEmpty())
            }
        }
    }

    @Test
    fun cancellationFromBundleValidationPropagatesAndLeavesQueueUntouched() = runTest {
        Fixture().use { f ->
            val batch = f.prepare(false)
            val before = f.store.getBatch(batch.batchId)
            val cancelled = object : BundlePublisher by f.bundle {
                override suspend fun current(ownerModelId: String): com.debanshu777.huggingfacemanager.download.ArtifactManifest? {
                    throw CancellationException("private-sentinel")
                }
            }
            assertFailsWith<CancellationException> { f.reconciler(cancelled).reconcile() }
            assertEquals(before, f.store.getBatch(batch.batchId))
        }
    }
}

private class Fixture : AutoCloseable {
    val root = Files.createTempDirectory("caraml-published-reconciliation").toRealPath().toFile()
    val paths = ReconciliationPaths(root)
    val database = getRoomDatabase(getDatabaseBuilder(File(root, "catalog.db").path))
    val downloadPath = File(root, "downloads.db").path
    val downloads = getDownloadRoomDatabase(getDownloadDatabaseBuilder(downloadPath))
    val store = RoomDownloadTaskStore(downloads.downloadTaskDao())
    val manager = DownloadManager(paths)
    val bundle = DownloadManagerBundlePublisher(manager)
    val catalog = RepositoryModelCatalogPublisher(database.installedModelCatalogDao(), paths)
    fun reconciler(publisher: BundlePublisher = bundle, scheduler: PlatformDownloadScheduler = PublishedReconciliationScheduler(), preferences: DataStore<Preferences>? = null) = PublishedDownloadReconciler(store, publisher, catalog, paths,
        InstalledModelPublicationCoordinator(), scheduler, { 100L }, preferences)

    suspend fun prepare(terminal: Boolean, variant: String = "valid"): DownloadBatchSnapshot {
        val bytes = ByteArray(128) { (it + if (terminal) 1 else 0).toByte() }
        val identity = DownloadArtifactIdentity.create("owner/model", "a".repeat(40),
            if (terminal) "failed.gguf" else "model.gguf", bytes.toByteString().sha256().hex(), bytes.size.toLong())!!
        val metadata = DownloadMetadataDTO(identity, "model", bytes.size.toLong(), null, "gguf", "text-generation")
        val request = DownloadBatchRequest(identity.repositoryId, "text", listOf(DownloadArtifactRequest(metadata, true)),
            pendingEvidence(identity), false, "Model")
        val id = store.create(request, 1L)
        val artifact = store.getBatch(id)!!.artifacts.single()
        assertTrue(store.claim(artifact.artifactId, "worker", 2L, 20L))
        assertTrue(store.updateProgress(artifact.artifactId, if (variant == "partial") 64L else 128L, "private-validator", null, 3L))
        assertTrue(store.transitionArtifact(artifact.artifactId, DownloadArtifactState.VERIFYING, null, 4L))
        if (terminal) assertTrue(store.transitionArtifact(artifact.artifactId, DownloadArtifactState.FAILED_TERMINAL, DownloadFailureCode.INTEGRITY, 5L))
        assertTrue(store.setUserIntent(id, if (variant == "cancel") DownloadUserIntent.CANCEL else DownloadUserIntent.PAUSE, 6L))
        val snapshot = store.getBatch(id)!!
        val artifactRoot = File(paths.getModelsStorageDirectory(identity.repositoryId))
        File(artifactRoot, metadata.destinationRelativePath + ".part").apply { parentFile.mkdirs(); writeBytes(bytes) }
        val entry = ArtifactManifestEntry.create("model", identity,128L, bytes.toByteString().sha256().hex(),metadata.bundleId,
            metadata.destinationRelativePath, metadata.layoutRelativePath)!!
        ArtifactManifestStore(artifactRoot.toOkioPath()).commit(metadata.destinationRelativePath,entry)
        assertTrue(manager.publishBundle(snapshot.ownerModelId,listOf(metadata)))
        catalog.publish(snapshot,snapshot.evidence)
        when (variant) {
            "missing" -> File(artifactRoot,metadata.destinationRelativePath).delete()
            "damaged" -> File(artifactRoot,metadata.destinationRelativePath).writeBytes(ByteArray(128))
            "revision" -> {
                val other = DownloadArtifactIdentity.create(identity.repositoryId,"d".repeat(40),identity.relativePath,identity.remoteObjectId,128L)!!
                val otherMetadata = DownloadMetadataDTO(other,"model",128L,null,"gguf","text-generation")
                File(artifactRoot,otherMetadata.destinationRelativePath+".part").apply { parentFile.mkdirs();writeBytes(bytes) }
                val otherEntry = ArtifactManifestEntry.create("model",other,128L,entry.contentSha256,otherMetadata.bundleId,
                    otherMetadata.destinationRelativePath,otherMetadata.layoutRelativePath)!!
                ArtifactManifestStore(artifactRoot.toOkioPath()).commit(otherMetadata.destinationRelativePath,otherEntry)
                assertTrue(manager.publishBundle(snapshot.ownerModelId,listOf(otherMetadata)))
            }
            "catalogPath", "evidence" -> {
                val current = catalog.snapshot(snapshot.ownerModelId)!!
                val evidence = if (variant == "evidence") current.evidence!!.copy(sha256="f".repeat(64)) else current.evidence!!
                database.installedModelCatalogDao().replaceReady(com.debanshu777.caraml.core.storage.catalog.InstalledCatalogRecord(
                    if (variant == "catalogPath") current.model.copy(localPath="/different/model.gguf") else current.model,
                    current.components,evidence))
            }
            "lease" -> rawUpdate("UPDATE download_artifact SET lease_owner='worker', lease_expires_at_epoch_ms=1000")
        }
        return snapshot
    }
    suspend fun addUnpublishedRowsBefore(targetId: String): List<DownloadBatchSnapshot> {
        val selected = mutableListOf<DownloadBatchSnapshot>()
        for (index in 1..65536) {
            val identity = DownloadArtifactIdentity.create("owner/model", "e".repeat(40), "unpublished-$index.gguf", "b".repeat(64), 128L)!!
            val metadata = DownloadMetadataDTO(identity, "model", 128L, null, "gguf", "text-generation")
            val request = DownloadBatchRequest(identity.repositoryId, "text", listOf(DownloadArtifactRequest(metadata, true)),
                pendingEvidence(identity), false, "Unpublished model")
            if (downloadBatchId(request) >= targetId) continue
            val now = 1000L + index * 10L
            val id = store.create(request, now)
            val artifact = store.getBatch(id)!!.artifacts.single()
            assertTrue(store.claim(artifact.artifactId, "worker", now + 1L, now + 100L))
            assertTrue(store.updateProgress(artifact.artifactId, 128L, "untouched-validator", null, now + 2L))
            assertTrue(store.transitionArtifact(artifact.artifactId, DownloadArtifactState.VERIFYING, null, now + 3L))
            assertTrue(store.setUserIntent(id, DownloadUserIntent.PAUSE, now + 4L))
            selected += store.getBatch(id)!!
            if (selected.size == 64) return selected
        }
        error("Unable to construct fixed bounded fairness fixture")
    }

    fun rawUpdate(sql: String) {
        androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(downloadPath).use { connection ->
            connection.prepare(sql).use { it.step() }
        }
    }
    override fun close() { downloads.close();database.close();root.deleteRecursively() }
}

private class ReconciliationPaths(private val root: File) : StoragePathProvider {
    override fun getModelsStorageDirectory(modelId: String) = File(root,modelId).absolutePath
    override fun getDatabasePath() = File(root,"catalog.db").absolutePath
    override fun fileExists(path: String) = File(path).exists()
    override fun getAvailableStorageBytes() = root.usableSpace
    override fun getTotalStorageBytes() = root.totalSpace
    override fun isModelFileReadable(path: String) = File(path).isFile
    override fun isDirectoryReadable(path: String) = File(path).isDirectory
    override fun getFileSize(path: String) = File(path).length()
    override fun renameFile(from: String,to: String) = File(from).renameTo(File(to))
    override fun deleteDownloadedModelContent(modelId: String,localPath: String) = false
}

private class PublishedReconciliationScheduler : PlatformDownloadScheduler {
    override suspend fun enqueue(batchId: String) = error("Reconciliation must not enqueue")
    override suspend fun pause(batchId: String) = Unit
    override suspend fun cancel(batchId: String) = Unit
    override suspend fun reconcile(liveBatchIds: Set<String>) = Unit
}

private suspend fun withRestartedScanPreferences(root: File, block: suspend (DataStore<Preferences>) -> Unit) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
        val preferences = PreferenceDataStoreFactory.createWithPath(scope = scope) {
            File(root, "scan.preferences_pb").absolutePath.toPath()
        }
        block(preferences)
    } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
}
