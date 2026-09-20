package com.debanshu777.caraml.core.storage

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.debanshu777.caraml.core.download.pendingEvidence
import com.debanshu777.caraml.core.recommendation.LlmModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.QuantizationEvidence
import com.debanshu777.caraml.core.recommendation.storage.PersistedModelEvidenceCodec
import com.debanshu777.caraml.core.storage.catalog.InstalledCatalogRecord
import com.debanshu777.caraml.core.storage.catalog.InstalledModelPublicationCoordinator
import com.debanshu777.caraml.core.storage.catalog.InstalledModelRemovalResult
import com.debanshu777.caraml.core.storage.catalog.InstalledModelRemovalService
import com.debanshu777.caraml.core.storage.component.DownloadedComponentEntity
import com.debanshu777.caraml.core.storage.evidence.InstalledModelEvidenceEntity
import com.debanshu777.caraml.core.storage.evidence.InstalledModelEvidenceRepository
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.ArtifactManifest
import com.debanshu777.huggingfacemanager.download.ArtifactManifestEntry
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.download.StoredArtifactSnapshot
import com.debanshu777.huggingfacemanager.download.immutableArtifactStorageLocation
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppDatabaseCurrentSchemaTest {
    @Test
    fun exactComponentRevisionsForDifferentOwnersCoexistAfterReopen() = runTest {
        val path = Files.createTempDirectory("caraml-component-revisions").resolve("caraml.db").toString()
        var database = getRoomDatabase(getDatabaseBuilder(path))
        try {
            database.installedModelCatalogDao().replaceReady(
                exactCatalogRecord("owner/a", "a".repeat(40), "b".repeat(64), "1".repeat(64)),
            )
            database.installedModelCatalogDao().replaceReady(
                exactCatalogRecord("owner/b", "c".repeat(40), "d".repeat(64), "2".repeat(64)),
            )
        } finally {
            database.close()
        }

        database = getRoomDatabase(getDatabaseBuilder(path))
        try {
            val first = requireNotNull(database.installedModelCatalogDao().snapshotReady("owner/a"))
            val second = requireNotNull(database.installedModelCatalogDao().snapshotReady("owner/b"))
            assertEquals("a".repeat(40), first.components.single().immutableRevision)
            assertEquals("c".repeat(40), second.components.single().immutableRevision)
            assertEquals("sha256:${"b".repeat(64)}", first.components.single().remoteObjectId)
            assertEquals("sha256:${"d".repeat(64)}", second.components.single().remoteObjectId)
            assertFalse(first.components.single().localPath == second.components.single().localPath)
        } finally {
            database.close()
        }
    }

    @Test
    fun removingOneReadyOwnerRetainsSharedExactComponentUntilTheLastLinkIsRemoved() = runTest {
        val path = Files.createTempDirectory("caraml-component-reference-removal").resolve("caraml.db").toString()
        val database = getRoomDatabase(getDatabaseBuilder(path))
        val shared = DownloadedComponentEntity(
            repoId = "shared/repo",
            filePath = "component.safetensors",
            role = "vae",
            localPath = "/models/shared/repo/.caraml-artifacts/${"1".repeat(64)}/component.safetensors",
            sizeBytes = 10L,
            downloadedAt = 1L,
            immutableRevision = "a".repeat(40),
            remoteObjectId = "sha256:${"b".repeat(64)}",
            bundleId = "1".repeat(64),
            contentSha256 = "b".repeat(64),
        )
        try {
            database.installedModelCatalogDao().replaceReady(
                exactCatalogRecord("owner/a", "a".repeat(40), "b".repeat(64), "1".repeat(64))
                    .copy(components = listOf(shared)),
            )
            database.installedModelCatalogDao().replaceReady(
                exactCatalogRecord("owner/b", "c".repeat(40), "d".repeat(64), "2".repeat(64))
                    .copy(components = listOf(shared.copy(role = "clip_g"))),
            )

            val first = requireNotNull(database.installedModelCatalogDao().snapshotReady("owner/a"))
            val secondBeforeRemoval = requireNotNull(database.installedModelCatalogDao().snapshotReady("owner/b"))
            assertEquals("vae", first.components.single().role)
            assertEquals("clip_g", secondBeforeRemoval.components.single().role)
            val firstRemoval = requireNotNull(database.installedModelCatalogDao().removeReadyIfMatches(first))

            assertEquals(emptyList(), firstRemoval.unreferencedComponents)
            assertTrue(database.installedModelCatalogDao().snapshotReady("owner/b") != null)
            assertEquals(1, database.downloadedComponentDao().getAllComponents().first().size)

            val second = requireNotNull(database.installedModelCatalogDao().snapshotReady("owner/b"))
            assertEquals("clip_g", second.components.single().role)
            val secondRemoval = requireNotNull(database.installedModelCatalogDao().removeReadyIfMatches(second))

            assertEquals(shared.localPath, secondRemoval.unreferencedComponents.single().localPath)
            assertEquals("clip_g", secondRemoval.unreferencedComponents.single().role)
            assertEquals(emptyList(), database.downloadedComponentDao().getAllComponents().first())
        } finally {
            database.close()
        }
    }

    @Test
    fun removalSnapshotMismatchLeavesReplacementUntouched() = runTest {
        val path = Files.createTempDirectory("caraml-component-stale-removal").resolve("caraml.db").toString()
        val database = getRoomDatabase(getDatabaseBuilder(path))
        try {
            database.installedModelCatalogDao().replaceReady(
                exactCatalogRecord("owner/a", "a".repeat(40), "b".repeat(64), "1".repeat(64)),
            )
            val stale = requireNotNull(database.installedModelCatalogDao().snapshotReady("owner/a"))
            database.installedModelCatalogDao().replaceReady(
                exactCatalogRecord("owner/a", "c".repeat(40), "d".repeat(64), "2".repeat(64)),
            )

            assertEquals(null, database.installedModelCatalogDao().removeReadyIfMatches(stale))
            assertEquals(
                "c".repeat(40),
                database.installedModelCatalogDao().snapshotReady("owner/a")?.components?.single()?.immutableRevision,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun exactManifestRemovalDeletesOnlySelectedRevisionAndKeepsOtherOwnerReady() = runTest {
        val path = Files.createTempDirectory("caraml-exact-removal").resolve("caraml.db").toString()
        var database = getRoomDatabase(getDatabaseBuilder(path))
        val storage = RemovalStoragePathProvider()
        val first = removalFixture("owner/a", "a".repeat(40), "b".repeat(64), "1".repeat(64), storage)
        val second = removalFixture("owner/b", "c".repeat(40), "d".repeat(64), "2".repeat(64), storage)
        val manifests = mapOf(first.record.model.modelId to first.manifest, second.record.model.modelId to second.manifest)
        try {
            database.installedModelCatalogDao().replaceReady(first.record)
            database.installedModelCatalogDao().replaceReady(second.record)
            val service = InstalledModelRemovalService(
                catalog = database.installedModelCatalogDao(),
                storagePathProvider = storage,
                manifestSource = manifests::get,
                publicationCoordinator = InstalledModelPublicationCoordinator(),
                artifactCleaner = { entries ->
                    entries.all { entry ->
                        storage.deleteDownloadedModelContent(
                            entry.identity.repositoryId,
                            "${storage.getModelsStorageDirectory(entry.identity.repositoryId)}/${entry.localRelativePath}",
                        )
                    }
                },
            )
            val installedFirst = requireNotNull(
                database.installedModelCatalogDao().snapshotReady("owner/a"),
            ).model

            assertEquals(InstalledModelRemovalResult.Removed, service.remove(installedFirst))
            assertEquals(null, database.installedModelCatalogDao().snapshotReady("owner/a"))
            assertTrue(database.installedModelCatalogDao().snapshotReady("owner/b") != null)
            assertTrue(first.record.model.localPath in storage.deletedPaths)
            assertTrue(first.record.components.single().localPath in storage.deletedPaths)
            assertFalse(second.record.model.localPath in storage.deletedPaths)
            assertFalse(second.record.components.single().localPath in storage.deletedPaths)
        } finally {
            database.close()
        }

        database = getRoomDatabase(getDatabaseBuilder(path))
        try {
            assertTrue(database.installedModelCatalogDao().snapshotReady("owner/b") != null)
        } finally {
            database.close()
        }
    }

    @Test
    fun staleManifestCannotRemoveOrDeleteCurrentReadyCatalog() = runTest {
        val path = Files.createTempDirectory("caraml-stale-manifest-removal").resolve("caraml.db").toString()
        val database = getRoomDatabase(getDatabaseBuilder(path))
        val storage = RemovalStoragePathProvider()
        val current = removalFixture("owner/a", "a".repeat(40), "b".repeat(64), "1".repeat(64), storage)
        val stale = removalFixture("owner/a", "c".repeat(40), "d".repeat(64), "2".repeat(64), storage)
        try {
            database.installedModelCatalogDao().replaceReady(current.record)
            val service = InstalledModelRemovalService(
                catalog = database.installedModelCatalogDao(),
                storagePathProvider = storage,
                manifestSource = { stale.manifest },
                publicationCoordinator = InstalledModelPublicationCoordinator(),
                artifactCleaner = { error("Stale removal must not reach artifact cleanup") },
            )
            val installedCurrent = requireNotNull(
                database.installedModelCatalogDao().snapshotReady("owner/a"),
            ).model

            assertEquals(InstalledModelRemovalResult.Rejected, service.remove(installedCurrent))
            assertTrue(database.installedModelCatalogDao().snapshotReady("owner/a") != null)
            assertEquals(emptyList(), storage.deletedPaths)
        } finally {
            database.close()
        }
    }

    @Test
    fun catalogReplacementPersistsReadyModelComponentsAndEvidenceTogether() = runTest {
        val path = Files.createTempDirectory("caraml-catalog-publication").resolve("caraml.db").toString()
        val database = getRoomDatabase(getDatabaseBuilder(path))
        val record = catalogRecord("v1", publishedAtEpochMs = 10L)
        try {
            database.installedModelCatalogDao().replaceReady(record)

            val model = database.localModelDao().getAllDownloadedFiles().first().single()
            val component = database.downloadedComponentDao().getComponentsForModel(MODEL_ID).single()
            val evidence = InstalledModelEvidenceRepository(database.installedModelEvidenceDao()).get(MODEL_ID)
            assertEquals("model-v1.gguf", model.filename)
            assertEquals(LocalModelEntity.STATUS_READY, model.componentStatus)
            assertEquals("clip-v1.gguf", component.filePath)
            assertEquals(record.evidence.encoded(), evidence)
        } finally {
            database.close()
        }
    }

    @Test
    fun evidenceInsertFailureRollsBackCatalogReplacement() = runTest {
        val path = Files.createTempDirectory("caraml-catalog-rollback").resolve("caraml.db").toString()
        var database = getRoomDatabase(getDatabaseBuilder(path))
        val original = catalogRecord("original", publishedAtEpochMs = 1L)
        database.installedModelCatalogDao().replaceReady(original)
        database.close()

        BundledSQLiteDriver().open(path).use { connection ->
            connection.execSQL(
                """
                CREATE TRIGGER reject_replacement_evidence
                BEFORE INSERT ON installed_model_evidence
                WHEN NEW.published_at_epoch_ms = 2
                BEGIN
                    SELECT RAISE(ABORT, 'simulated evidence write failure');
                END
                """.trimIndent(),
            )
        }

        database = getRoomDatabase(getDatabaseBuilder(path))
        try {
            assertFails {
                database.installedModelCatalogDao().replaceReady(
                    catalogRecord("replacement", publishedAtEpochMs = 2L),
                )
            }

            val model = database.localModelDao().getAllDownloadedFiles().first().single()
            val component = database.downloadedComponentDao().getComponentsForModel(MODEL_ID).single()
            val evidence = database.installedModelEvidenceDao().get(MODEL_ID)
            assertEquals("model-original.gguf", model.filename)
            assertEquals("clip-original.gguf", component.filePath)
            assertEquals(original.evidence, evidence)
        } finally {
            database.close()
        }
    }

    @Test
    fun evidenceCompareAndSetInsertsMissingRowAndSurvivesReopen() = runTest {
        val path = Files.createTempDirectory("caraml-evidence-cas-reopen").resolve("caraml.db").toString()
        val expected = catalogRecord("missing", publishedAtEpochMs = 1L).evidence
        var database = getRoomDatabase(getDatabaseBuilder(path))
        try {
            assertTrue(database.installedModelEvidenceDao().compareAndSet(expected = null, replacement = expected))
        } finally {
            database.close()
        }

        database = getRoomDatabase(getDatabaseBuilder(path))
        try {
            assertEquals(expected, database.installedModelEvidenceDao().get(MODEL_ID))
        } finally {
            database.close()
        }
    }

    @Test
    fun evidenceCompareAndSetReplacesObservedIncompleteAndCorruptRows() = runTest {
        val path = Files.createTempDirectory("caraml-evidence-cas-replace").resolve("caraml.db").toString()
        val database = getRoomDatabase(getDatabaseBuilder(path))
        val incomplete = catalogRecord("incomplete", publishedAtEpochMs = 1L).evidence
        val complete = completeEvidenceEntity(publishedAtEpochMs = 2L)
        val corrupt = complete.copy(payload = "{corrupt", publishedAtEpochMs = 3L)
        val repaired = complete.copy(publishedAtEpochMs = 4L)
        try {
            database.installedModelEvidenceDao().upsert(incomplete)
            assertTrue(database.installedModelEvidenceDao().compareAndSet(incomplete, complete))
            assertEquals(complete, database.installedModelEvidenceDao().get(MODEL_ID))

            database.installedModelEvidenceDao().upsert(corrupt)
            assertTrue(database.installedModelEvidenceDao().compareAndSet(corrupt, repaired))
            assertEquals(repaired, database.installedModelEvidenceDao().get(MODEL_ID))
        } finally {
            database.close()
        }
    }

    @Test
    fun evidenceCompareAndSetCannotOverwriteChangedNewerRow() = runTest {
        val path = Files.createTempDirectory("caraml-evidence-cas-newer").resolve("caraml.db").toString()
        val database = getRoomDatabase(getDatabaseBuilder(path))
        val observed = catalogRecord("observed", publishedAtEpochMs = 1L).evidence
        val newer = completeEvidenceEntity(publishedAtEpochMs = 3L)
        val staleReplacement = catalogRecord("stale", publishedAtEpochMs = 2L).evidence
        try {
            database.installedModelEvidenceDao().upsert(observed)
            database.installedModelEvidenceDao().upsert(newer)

            assertFalse(database.installedModelEvidenceDao().compareAndSet(observed, staleReplacement))
            assertEquals(newer, database.installedModelEvidenceDao().get(MODEL_ID))
        } finally {
            database.close()
        }
    }
}

private fun exactCatalogRecord(
    owner: String,
    revision: String,
    objectDigest: String,
    bundleId: String,
): InstalledCatalogRecord {
    val remoteObjectId = "sha256:$objectDigest"
    val componentIdentity = identity("shared/repo", "component.safetensors", revision, "sha256:$objectDigest")
    val ownerIdentity = identity(owner, "model.safetensors", revision, "sha256:$objectDigest")
    val evidence = pendingEvidence(listOf(ownerIdentity, componentIdentity))
    return InstalledCatalogRecord(
        model = LocalModelEntity(
            modelId = owner,
            filename = "model.safetensors",
            localPath = "/models/$owner/.caraml-artifacts/$bundleId/model.safetensors",
            sizeBytes = 10L,
            downloadedAt = 1L,
            author = null,
            libraryName = null,
            pipelineTag = null,
            modelType = "image",
            componentStatus = LocalModelEntity.STATUS_READY,
        ),
        components = listOf(
            DownloadedComponentEntity(
                repoId = "shared/repo",
                filePath = "component.safetensors",
                role = "vae",
                localPath = "/models/shared/repo/.caraml-artifacts/$bundleId/component.safetensors",
                sizeBytes = 10L,
                downloadedAt = 1L,
                immutableRevision = revision,
                remoteObjectId = remoteObjectId,
                bundleId = bundleId,
                contentSha256 = objectDigest,
            ),
        ),
        evidence = InstalledModelEvidenceEntity(
            modelId = owner,
            evidenceState = evidence.state.name,
            schemaVersion = evidence.schemaVersion,
            payload = evidence.payload,
            sha256 = evidence.sha256,
            publishedAtEpochMs = 1L,
        ),
    )
}

private data class RemovalFixture(
    val record: InstalledCatalogRecord,
    val manifest: ArtifactManifest,
)

private fun removalFixture(
    owner: String,
    revision: String,
    objectDigest: String,
    bundleId: String,
    storage: StoragePathProvider,
): RemovalFixture {
    val ownerIdentity = identity(owner, "model.safetensors", revision, "sha256:$objectDigest")
    val componentIdentity = identity("shared/repo", "component.safetensors", revision, "sha256:$objectDigest")
    val ownerLocation = immutableArtifactStorageLocation(ownerIdentity, bundleId)
    val componentLocation = immutableArtifactStorageLocation(componentIdentity, bundleId)
    fun absolute(repositoryId: String, relativePath: String): String =
        "${storage.getModelsStorageDirectory(repositoryId)}/$relativePath"
    val base = exactCatalogRecord(owner, revision, objectDigest, bundleId)
    val record = base.copy(
        model = base.model.copy(
            filename = ownerLocation.layoutRelativePath.substringAfterLast('/'),
            localPath = absolute(owner, ownerLocation.localRelativePath),
        ),
        components = listOf(
            base.components.single().copy(
                localPath = absolute(componentIdentity.repositoryId, componentLocation.localRelativePath),
            ),
        ),
    )
    val manifest = requireNotNull(
        ArtifactManifest.create(
            listOf(
                requireNotNull(
                    ArtifactManifestEntry.create(
                        logicalRole = "model",
                        identity = ownerIdentity,
                        byteCount = ownerIdentity.expectedBytes,
                        contentSha256 = objectDigest,
                        bundleId = bundleId,
                        localRelativePath = ownerLocation.localRelativePath,
                        layoutRelativePath = ownerLocation.layoutRelativePath,
                    ),
                ),
                requireNotNull(
                    ArtifactManifestEntry.create(
                        logicalRole = "vae",
                        identity = componentIdentity,
                        byteCount = componentIdentity.expectedBytes,
                        contentSha256 = objectDigest,
                        bundleId = bundleId,
                        localRelativePath = componentLocation.localRelativePath,
                        layoutRelativePath = componentLocation.layoutRelativePath,
                    ),
                ),
            ),
        ),
    )
    return RemovalFixture(record, manifest)
}

private class RemovalStoragePathProvider : StoragePathProvider {
    val deletedPaths = mutableListOf<String>()

    override fun getModelsStorageDirectory(modelId: String): String = "/models/$modelId"
    override fun getDatabasePath(): String = "/databases/caraml.db"
    override fun fileExists(path: String): Boolean = true
    override fun getAvailableStorageBytes(): Long = Long.MAX_VALUE
    override fun getTotalStorageBytes(): Long = Long.MAX_VALUE
    override fun inspectDownloadedArtifact(modelId: String, localPath: String): StoredArtifactSnapshot? = null
    override fun isModelFileReadable(path: String): Boolean = true
    override fun isDirectoryReadable(path: String): Boolean = true
    override fun getFileSize(path: String): Long = 10L
    override fun renameFile(from: String, to: String): Boolean = false
    override fun deleteDownloadedModelContent(modelId: String, localPath: String): Boolean {
        deletedPaths += localPath
        return true
    }
}

private const val MODEL_ID = "owner/model"

private fun completeEvidenceEntity(publishedAtEpochMs: Long): InstalledModelEvidenceEntity {
    val identity = ModelFileIdentity(
        repositoryId = MODEL_ID,
        revision = "a".repeat(40),
        path = "model-complete.gguf",
        sizeBytes = 10L,
        gitOid = null,
        lfsOid = "sha256:${"b".repeat(64)}",
        xetHash = null,
        evidence = emptyList(),
    )
    val descriptor = LlmModelDescriptor(
        repositoryId = MODEL_ID,
        revision = identity.revision,
        file = identity,
        architecture = "llama",
        quantization = QuantizationEvidence.Known("Q4_K_M"),
        parameterCount = 1_000_000L,
        contextLimit = 4_096,
        transformerShape = null,
        ggufVersion = 3,
        requiredEngineFeatures = emptyList(),
        evidence = emptyList(),
    )
    val encoded = PersistedModelEvidenceCodec().encode(listOf(identity), descriptor)
    return InstalledModelEvidenceEntity(
        modelId = MODEL_ID,
        evidenceState = encoded.state.name,
        schemaVersion = encoded.schemaVersion,
        payload = encoded.payload,
        sha256 = encoded.sha256,
        publishedAtEpochMs = publishedAtEpochMs,
    )
}

private fun catalogRecord(suffix: String, publishedAtEpochMs: Long): InstalledCatalogRecord {
    val evidence = pendingEvidence(
        listOf(
            identity(MODEL_ID, "model-$suffix.gguf", "a".repeat(40), "b".repeat(64)),
            identity("owner/component", "clip-$suffix.gguf", "c".repeat(40), "d".repeat(64)),
        ),
    )
    return InstalledCatalogRecord(
        model = LocalModelEntity(
            modelId = MODEL_ID,
            filename = "model-$suffix.gguf",
            localPath = "/models/owner/model/model-$suffix.gguf",
            sizeBytes = 10L,
            downloadedAt = publishedAtEpochMs,
            author = "owner",
            libraryName = "gguf",
            pipelineTag = "text-generation",
            modelType = "text",
            componentStatus = LocalModelEntity.STATUS_READY,
        ),
        components = listOf(
            DownloadedComponentEntity(
                repoId = "owner/component",
                filePath = "clip-$suffix.gguf",
                role = "clip",
                localPath = "/models/owner/component/clip-$suffix.gguf",
                sizeBytes = 5L,
                downloadedAt = publishedAtEpochMs,
                immutableRevision = "c".repeat(40),
                remoteObjectId = "sha256:${"d".repeat(64)}",
                bundleId = "e".repeat(64),
                contentSha256 = "d".repeat(64),
            ),
        ),
        evidence = InstalledModelEvidenceEntity(
            modelId = MODEL_ID,
            evidenceState = evidence.state.name,
            schemaVersion = evidence.schemaVersion,
            payload = evidence.payload,
            sha256 = evidence.sha256,
            publishedAtEpochMs = publishedAtEpochMs,
        ),
    )
}

private fun InstalledModelEvidenceEntity.encoded() =
    com.debanshu777.caraml.core.recommendation.storage.EncodedModelEvidence(
        state = com.debanshu777.caraml.core.recommendation.storage.InstalledEvidenceState.valueOf(evidenceState),
        schemaVersion = schemaVersion,
        payload = payload,
        sha256 = sha256,
    )

private fun identity(
    repositoryId: String,
    path: String,
    revision: String,
    objectId: String,
): DownloadArtifactIdentity = requireNotNull(
    DownloadArtifactIdentity.create(repositoryId, revision, path, objectId, 10L),
)
