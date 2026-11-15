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
import com.debanshu777.caraml.core.download.transfer.DownloadArtifactIdentity
import com.debanshu777.caraml.core.download.transfer.ArtifactManifest
import com.debanshu777.caraml.core.download.transfer.ArtifactManifestEntry
import com.debanshu777.caraml.core.download.transfer.StoragePathProvider
import com.debanshu777.caraml.core.download.transfer.StoredArtifactSnapshot
import com.debanshu777.caraml.core.download.transfer.immutableArtifactStorageLocation
import java.nio.file.Files
import java.io.File
import kotlinx.serialization.json.Json
import com.debanshu777.caraml.core.download.transfer.ArtifactManifestStore
import com.debanshu777.caraml.core.download.diffusion.DIFFUSERS_BUNDLE_DB_FILENAME
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppDatabaseCurrentSchemaTest {
    @Test
    fun legacyVariantStillUnlinksAfterCurrentManifestOwnerWasRemovedFirst() = runTest {
        val storage = FileRemovalStorage()
        val database = getRoomDatabase(getDatabaseBuilder(storage.getDatabasePath()))
        try {
            val fixture = removalFixture("owner/model", "a".repeat(40), "b".repeat(64), "1".repeat(64), storage)
            val catalog = database.installedModelCatalogDao()
            catalog.replaceReady(fixture.record)
            val current = requireNotNull(catalog.snapshotReady("owner/model")).model
            val legacy = File(storage.getModelsStorageDirectory("owner/model"), "legacy.gguf").apply { writeText("retain") }
            database.localModelDao().insert(legacyModel("owner/model", legacy))
            val selected = database.localModelDao().getAllDownloadedFiles().first().single { it.filename == legacy.name }
            val service = InstalledModelRemovalService(catalog, storage, { fixture.manifest }, InstalledModelPublicationCoordinator())
            assertEquals(InstalledModelRemovalResult.Removed, service.remove(current))
            assertEquals(InstalledModelRemovalResult.RemovedWithCleanupFailure, service.remove(selected))
            assertTrue(database.localModelDao().getAllDownloadedFiles().first().isEmpty())
            assertEquals("retain", legacy.readText())
        } finally { database.close() }
    }

    @Test
    fun partialDamagedInstallationRemovesOwnedFilesAndPrunesItsManifest() = runTest {
        val storage = FileRemovalStorage()
        val database = getRoomDatabase(getDatabaseBuilder(storage.getDatabasePath()))
        try {
            val fixture = removalFixture("owner/model", "a".repeat(40), "b".repeat(64), "1".repeat(64),
                storage, componentRepositoryId = "owner/model")
            val catalog = database.installedModelCatalogDao()
            catalog.replaceReady(fixture.record)
            database.localModelDao().updateComponentStatus("owner/model", LocalModelEntity.STATUS_PARTIAL)
            fixture.manifest.entries.forEach { entry ->
                File(storage.getModelsStorageDirectory(entry.identity.repositoryId), entry.localRelativePath)
                    .apply { parentFile.mkdirs(); writeText("damaged") }
            }
            val manifestFile = File(storage.getModelsStorageDirectory("owner/model"), ArtifactManifestStore.MANIFEST_FILE_NAME)
            manifestFile.writeText(Json.encodeToString(ArtifactManifest.serializer(), fixture.manifest))
            val model = database.localModelDao().getAllDownloadedFiles().first().single()
            val service = InstalledModelRemovalService(catalog, storage, { fixture.manifest }, InstalledModelPublicationCoordinator())
            assertEquals(InstalledModelRemovalResult.Removed, service.remove(model))
            assertFalse(File(model.localPath).exists())
            assertFalse(File(fixture.record.components.single().localPath).exists())
            assertFalse(manifestFile.exists())
            assertTrue(database.localModelDao().getAllDownloadedFiles().first().isEmpty())
        } finally { database.close() }
    }

    @Test
    fun legacyFileRemovalPreservesOtherVariantAndNeverRecursesAmbiguousDirectory() = runTest {
        val storage = FileRemovalStorage()
        val database = getRoomDatabase(getDatabaseBuilder(storage.getDatabasePath()))
        try {
            val dao = database.localModelDao()
            val root = File(storage.getModelsStorageDirectory("owner/model"))
            val first = File(root, "old.gguf").apply { writeText("old") }
            val second = File(root, "other.gguf").apply { writeText("other") }
            dao.insert(legacyModel("owner/model", first))
            dao.insert(legacyModel("owner/model", second))
            val service = InstalledModelRemovalService(database.installedModelCatalogDao(), storage, { null }, InstalledModelPublicationCoordinator())
            val selected = dao.getAllDownloadedFiles().first().single { it.filename == first.name }
            assertEquals(InstalledModelRemovalResult.Removed, service.remove(selected))
            assertFalse(first.exists())
            assertTrue(second.exists())
            assertEquals(listOf(second.name), dao.getAllDownloadedFiles().first().map { it.filename })
            dao.insert(legacyModel("owner/model", root).copy(filename = DIFFUSERS_BUNDLE_DB_FILENAME))
            val directory = dao.getAllDownloadedFiles().first().single { it.filename == DIFFUSERS_BUNDLE_DB_FILENAME }
            assertEquals(InstalledModelRemovalResult.RemovedWithCleanupFailure, service.remove(directory))
            assertTrue(root.exists())
            assertEquals("other", second.readText())
        } finally { database.close() }
    }

    @Test
    fun legacyRemovalRejectsSymlinkDeletionAndProtectsOverlappingCatalogOwner() = runTest {
        val storage = FileRemovalStorage()
        val database = getRoomDatabase(getDatabaseBuilder(storage.getDatabasePath()))
        try {
            val dao = database.localModelDao()
            val root = File(storage.getModelsStorageDirectory("owner/model"))
            val outside = Files.createTempFile("caraml-removal-outside", ".gguf").toFile().apply { writeText("keep") }
            val linked = File(root, "linked.gguf")
            Files.createSymbolicLink(linked.toPath(), outside.toPath())
            dao.insert(legacyModel("owner/model", linked))
            val service = InstalledModelRemovalService(database.installedModelCatalogDao(), storage, { null }, InstalledModelPublicationCoordinator())
            assertEquals(InstalledModelRemovalResult.RemovedWithCleanupFailure, service.remove(dao.getAllDownloadedFiles().first().single()))
            assertEquals("keep", outside.readText())
            assertTrue(Files.isSymbolicLink(linked.toPath()))
            val child = File(root, "retained.gguf").apply { writeText("shared") }
            dao.insert(legacyModel("owner/model", child))
            dao.insert(legacyModel("other/owner", root).copy(filename = DIFFUSERS_BUNDLE_DB_FILENAME))
            val selected = dao.getAllDownloadedFiles().first().single { it.filename == child.name }
            assertEquals(InstalledModelRemovalResult.Removed, service.remove(selected))
            assertEquals("shared", child.readText())
            assertEquals("other/owner", dao.getAllDownloadedFiles().first().single().modelId)
        } finally { database.close() }
    }

    @Test
    fun unboundLegacyVariantCanBeUnlinkedWithoutTouchingCurrentManifestOwner() = runTest {
        val storage = FileRemovalStorage()
        val database = getRoomDatabase(getDatabaseBuilder(storage.getDatabasePath()))
        try {
            val fixture = removalFixture("owner/model", "a".repeat(40), "b".repeat(64), "1".repeat(64), storage)
            val catalog = database.installedModelCatalogDao()
            catalog.replaceReady(fixture.record)
            val legacy = File(storage.getModelsStorageDirectory("owner/model"), "legacy.gguf").apply { writeText("retain") }
            database.localModelDao().insert(legacyModel("owner/model", legacy))
            val current = database.localModelDao().getAllDownloadedFiles().first().single { it.filename != legacy.name }
            val selected = database.localModelDao().getAllDownloadedFiles().first().single { it.filename == legacy.name }
            val service = InstalledModelRemovalService(catalog, storage, { fixture.manifest }, InstalledModelPublicationCoordinator())
            assertEquals(InstalledModelRemovalResult.RemovedWithCleanupFailure, service.remove(selected))
            assertEquals(listOf(current), database.localModelDao().getAllDownloadedFiles().first())
            assertEquals(fixture.record.components.single().localPath, catalog.snapshotComponents("owner/model").single().localPath)
            assertEquals("retain", legacy.readText())
        } finally { database.close() }
    }

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
            val firstRemoval = requireNotNull(database.installedModelCatalogDao().removeSelectedIfMatches(first))

            assertEquals(emptyList(), firstRemoval.unreferencedComponents)
            assertTrue(database.installedModelCatalogDao().snapshotReady("owner/b") != null)
            assertEquals(1, database.downloadedComponentDao().getAllComponents().first().size)

            val second = requireNotNull(database.installedModelCatalogDao().snapshotReady("owner/b"))
            assertEquals("clip_g", second.components.single().role)
            val secondRemoval = requireNotNull(database.installedModelCatalogDao().removeSelectedIfMatches(second))

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

            assertEquals(null, database.installedModelCatalogDao().removeSelectedIfMatches(stale))
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
    fun sameRepositoryOnnxCompanionsAreRemovedWithTheirPrimaryGraph() = runTest {
        val database = getRoomDatabase(getDatabaseBuilder(
            Files.createTempDirectory("caraml-onnx-removal").resolve("caraml.db").toString(),
        ))
        val storage = RemovalStoragePathProvider()
        val fixture = removalFixture(
            "owner/onnx", "a".repeat(40), "b".repeat(64), "1".repeat(64), storage,
            componentRepositoryId = "owner/onnx", modelFilename = "model.onnx",
            componentFilename = "model.onnx_data", componentRole = "onnx-companion-0",
        )
        try {
            database.installedModelCatalogDao().replaceReady(fixture.record)
            val installed = requireNotNull(database.installedModelCatalogDao().snapshotReady("owner/onnx"))
            val deleted = mutableListOf<ArtifactManifestEntry>()
            val service = InstalledModelRemovalService(
                database.installedModelCatalogDao(), storage, { fixture.manifest },
                InstalledModelPublicationCoordinator(), { entries -> deleted.addAll(entries); true },
            )

            assertEquals(InstalledModelRemovalResult.Removed, service.remove(installed.model))
            assertEquals(fixture.manifest.entries.toSet(), deleted.toSet())
            assertEquals(null, database.installedModelCatalogDao().snapshotReady("owner/onnx"))
        } finally {
            database.close()
        }
    }

    @Test
    fun sameRepositoryCompanionIsRetainedWhileAnotherOwnerReferencesIt() = runTest {
        val database = getRoomDatabase(getDatabaseBuilder(
            Files.createTempDirectory("caraml-onnx-shared-removal").resolve("caraml.db").toString(),
        ))
        val storage = RemovalStoragePathProvider()
        fun fixture(owner: String) = removalFixture(
            owner, "a".repeat(40), "b".repeat(64), "1".repeat(64), storage,
            componentRepositoryId = "owner/onnx", modelFilename = "model.onnx",
            componentFilename = "tokenizer.json", componentRole = "onnx-companion-0",
        )
        val first = fixture("owner/onnx")
        val second = fixture("owner/other")
        val manifests = mapOf("owner/onnx" to first.manifest, "owner/other" to second.manifest)
        val deleted = mutableListOf<ArtifactManifestEntry>()
        try {
            val catalog = database.installedModelCatalogDao()
            catalog.replaceReady(first.record)
            catalog.replaceReady(second.record)
            val service = InstalledModelRemovalService(
                catalog, storage, manifests::get, InstalledModelPublicationCoordinator(),
                { entries -> deleted.addAll(entries); true },
            )

            assertEquals(InstalledModelRemovalResult.Removed, service.remove(requireNotNull(catalog.snapshotReady("owner/onnx")).model))
            assertEquals(listOf(first.manifest.entries.single { it.logicalRole == "model" }), deleted)
            assertTrue(catalog.snapshotReady("owner/other") != null)
            assertEquals(InstalledModelRemovalResult.Removed, service.remove(requireNotNull(catalog.snapshotReady("owner/other")).model))
            assertEquals((first.manifest.entries + second.manifest.entries).toSet(), deleted.toSet())
        } finally {
            database.close()
        }
    }

    @Test
    fun unboundSameRepositoryCompanionRejectsRemovalWithoutDeletingAnything() = runTest {
        val database = getRoomDatabase(getDatabaseBuilder(
            Files.createTempDirectory("caraml-unbound-companion").resolve("caraml.db").toString(),
        ))
        val storage = RemovalStoragePathProvider()
        val fixture = removalFixture(
            "owner/onnx", "a".repeat(40), "b".repeat(64), "1".repeat(64), storage,
            componentRepositoryId = "owner/onnx", modelFilename = "model.onnx",
            componentFilename = "tokenizer.json", componentRole = "onnx-companion-0",
        )
        try {
            val catalog = database.installedModelCatalogDao()
            catalog.replaceReady(fixture.record.copy(components = emptyList()))
            val installed = requireNotNull(catalog.snapshotReady("owner/onnx"))
            val service = InstalledModelRemovalService(
                catalog, storage, { fixture.manifest }, InstalledModelPublicationCoordinator(),
                { error("An unbound companion must not reach cleanup") },
            )

            assertEquals(InstalledModelRemovalResult.Rejected, service.remove(installed.model))
            assertEquals(installed, catalog.snapshotReady("owner/onnx"))
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
    componentRepositoryId: String = "shared/repo",
    modelFilename: String = "model.safetensors",
    componentFilename: String = "component.safetensors",
    componentRole: String = "vae",
): RemovalFixture {
    val ownerIdentity = identity(owner, modelFilename, revision, "sha256:$objectDigest")
    val componentIdentity = identity(componentRepositoryId, componentFilename, revision, "sha256:$objectDigest")
    val ownerLocation = immutableArtifactStorageLocation(ownerIdentity, bundleId)
    val componentLocation = immutableArtifactStorageLocation(componentIdentity, bundleId)
    fun absolute(repositoryId: String, relativePath: String): String =
        "${storage.getModelsStorageDirectory(repositoryId)}/$relativePath"
    val base = exactCatalogRecord(owner, revision, objectDigest, bundleId)
    val evidence = pendingEvidence(listOf(ownerIdentity, componentIdentity))
    val isOnnx = modelFilename.endsWith(".onnx")
    val record = base.copy(
        model = base.model.copy(
            filename = ownerLocation.layoutRelativePath.substringAfterLast('/'),
            localPath = absolute(owner, ownerLocation.localRelativePath),
            sizeBytes = if (isOnnx) ownerIdentity.expectedBytes + componentIdentity.expectedBytes else base.model.sizeBytes,
            libraryName = if (isOnnx) "onnx" else base.model.libraryName,
            modelType = if (isOnnx) "text" else base.model.modelType,
        ),
        components = listOf(
            base.components.single().copy(
                repoId = componentRepositoryId,
                filePath = componentFilename,
                role = componentRole,
                localPath = absolute(componentIdentity.repositoryId, componentLocation.localRelativePath),
            ),
        ),
        evidence = base.evidence.copy(
            evidenceState = evidence.state.name,
            schemaVersion = evidence.schemaVersion,
            payload = evidence.payload,
            sha256 = evidence.sha256,
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
                        logicalRole = componentRole,
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

private fun legacyModel(owner: String, file: File) = LocalModelEntity(
    modelId = owner, filename = file.name, localPath = file.absolutePath,
    sizeBytes = file.length(), downloadedAt = 1L, author = null, libraryName = null, pipelineTag = null,
)

private class FileRemovalStorage : StoragePathProvider {
    private val root = Files.createTempDirectory("caraml-removal-files").toRealPath().toFile()
    override fun getModelsStorageDirectory(modelId: String): String = File(root, "models/$modelId").apply { mkdirs() }.absolutePath
    override fun getDatabasePath(): String = File(root, "caraml.db").absolutePath
    override fun fileExists(path: String) = File(path).exists()
    override fun getAvailableStorageBytes() = Long.MAX_VALUE
    override fun getTotalStorageBytes() = Long.MAX_VALUE
    override fun isModelFileReadable(path: String) = File(path).isFile
    override fun isDirectoryReadable(path: String) = File(path).isDirectory
    override fun getFileSize(path: String) = File(path).length()

    override fun deleteDownloadedModelContent(modelId: String, localPath: String): Boolean =
        error("Removal must use the secure file boundary, never recursive platform deletion")
}
