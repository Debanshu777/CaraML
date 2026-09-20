package com.debanshu777.caraml.core.download

import com.debanshu777.caraml.core.recommendation.DiffusionComponentDescriptor
import com.debanshu777.caraml.core.recommendation.DiffusionMode
import com.debanshu777.caraml.core.recommendation.DiffusionModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.QuantizationEvidence
import com.debanshu777.caraml.core.recommendation.storage.EncodedModelEvidence
import com.debanshu777.caraml.core.recommendation.storage.PersistedModelEvidenceCodec
import com.debanshu777.caraml.core.storage.catalog.InstalledModelPublicationCoordinator
import com.debanshu777.caraml.core.storage.getDatabaseBuilder
import com.debanshu777.caraml.core.storage.getRoomDatabase
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.ArtifactVerificationException
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.download.immutableArtifactGenerationRoot
import com.debanshu777.huggingfacemanager.model.DIFFUSERS_BUNDLE_DB_FILENAME
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelDownloadFinalizerTest {
    @Test
    fun persistedUnscopedArtifactCannotPublishManifestOrReadyCatalog() = runTest {
        val calls = mutableListOf<String>()
        val scoped = finalizerBatch()
        val unsafe = scoped.copy(
            artifacts = scoped.artifacts.map { artifact ->
                artifact.copy(
                    request = artifact.request.copy(
                        metadata = artifact.request.metadata.copy(
                            destinationRelativePath = artifact.request.metadata.layoutRelativePath,
                        ),
                    ),
                )
            },
        )

        assertFailsWith<ArtifactVerificationException> {
            finalizer(unsafe, calls).finalize(unsafe.batchId)
        }

        assertEquals(emptyList(), calls)
    }

    @Test
    fun catalogPublisherPersistsExactScopedFileAndExternalComponentIdentity() = runTest {
        val databasePath = Files.createTempDirectory("caraml-finalizer-file").resolve("caraml.db").toString()
        val database = getRoomDatabase(getDatabaseBuilder(databasePath))
        val paths = FinalizerStoragePathProvider()
        val batch = finalizerBatch()
        try {
            RepositoryModelCatalogPublisher(database.installedModelCatalogDao(), paths, nowEpochMs = { 7L })
                .publish(batch, batch.evidence)

            val snapshot = requireNotNull(database.installedModelCatalogDao().snapshotReady(batch.ownerModelId))
            val primary = batch.artifacts.single { it.request.primary }.request.metadata
            val external = batch.artifacts.single { !it.request.primary }.request.metadata
            assertEquals(
                "${paths.getModelsStorageDirectory(batch.ownerModelId)}/${primary.destinationRelativePath}",
                snapshot.model.localPath,
            )
            assertEquals(primary.layoutRelativePath.substringAfterLast('/'), snapshot.model.filename)
            assertEquals(external.artifact.immutableRevision, snapshot.components.single().immutableRevision)
            assertEquals(external.artifact.remoteObjectId, snapshot.components.single().remoteObjectId)
            assertEquals(external.bundleId, snapshot.components.single().bundleId)
            assertEquals(
                "${paths.getModelsStorageDirectory(external.artifact.repositoryId)}/${external.destinationRelativePath}",
                snapshot.components.single().localPath,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun directoryCatalogPathIsTheExactImmutableGenerationRoot() = runTest {
        val databasePath = Files.createTempDirectory("caraml-finalizer-directory").resolve("caraml.db").toString()
        val database = getRoomDatabase(getDatabaseBuilder(databasePath))
        val paths = FinalizerStoragePathProvider()
        val batch = directoryFinalizerBatch()
        try {
            RepositoryModelCatalogPublisher(database.installedModelCatalogDao(), paths, nowEpochMs = { 8L })
                .publish(batch, batch.evidence)

            val model = requireNotNull(database.installedModelCatalogDao().snapshotReady(batch.ownerModelId)).model
            val bundleId = batch.artifacts.first().request.metadata.bundleId
            assertEquals(DIFFUSERS_BUNDLE_DB_FILENAME, model.filename)
            assertEquals(
                "${paths.getModelsStorageDirectory(batch.ownerModelId)}/${immutableArtifactGenerationRoot(bundleId)}",
                model.localPath,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun aggregateManifestIsValidatedBeforeCatalogPublication() = runTest {
        val calls = mutableListOf<String>()
        val batch = finalizerBatch()
        val finalizer = finalizer(batch, calls)

        finalizer.finalize("batch")
        finalizer.finalize("batch")

        assertEquals(
            listOf(
                "manifest:publish", "manifest:validate", "catalog:1",
                "manifest:publish", "manifest:validate", "catalog:1",
            ),
            calls,
        )
    }

    @Test
    fun sameOwnerFinalizersHoldPublicationThroughCatalogCommit() = runTest {
        val coordinator = InstalledModelPublicationCoordinator()
        val calls = mutableListOf<String>()
        val catalogEntered = CompletableDeferred<Unit>()
        val releaseFirstCatalog = CompletableDeferred<Unit>()
        val secondManifestEntered = CompletableDeferred<Unit>()
        val manifests = mutableMapOf<String, String>()
        val catalogs = mutableMapOf<String, String>()
        val publisher = object : BundlePublisher {
            override suspend fun publish(ownerModelId: String, artifacts: List<DownloadMetadataDTO>): Boolean {
                val variant = artifacts.primaryVariant()
                calls += "manifest:$variant"
                manifests[ownerModelId] = variant
                if (variant == "b") secondManifestEntered.complete(Unit)
                return true
            }

            override suspend fun validate(ownerModelId: String, artifacts: List<DownloadMetadataDTO>): Boolean {
                val variant = artifacts.primaryVariant()
                calls += "validate:$variant"
                return manifests[ownerModelId] == variant
            }
        }
        val catalog = ModelCatalogPublisher { batch, evidence ->
            val variant = batch.primaryVariant()
            calls += "catalog:$variant:enter"
            if (variant == "a") {
                catalogEntered.complete(Unit)
                releaseFirstCatalog.await()
            }
            assertEquals(batch.evidence, evidence)
            assertEquals(variant, manifests[batch.ownerModelId])
            catalogs[batch.ownerModelId] = variant
            calls += "catalog:$variant:done"
        }
        val first = finalizer(finalizerBatch("a"), publisher, catalog, coordinator)
        val second = finalizer(finalizerBatch("b"), publisher, catalog, coordinator)

        val firstJob = async(start = CoroutineStart.UNDISPATCHED) { first.finalize("batch-a") }
        catalogEntered.await()
        val secondJob = async(start = CoroutineStart.UNDISPATCHED) { second.finalize("batch-b") }

        assertFalse(secondManifestEntered.isCompleted)
        releaseFirstCatalog.complete(Unit)
        awaitAll(firstJob, secondJob)

        assertTrue(secondManifestEntered.isCompleted)
        assertEquals("b", manifests["owner/model"])
        assertEquals("b", catalogs["owner/model"])
        assertEquals(
            listOf(
                "manifest:a", "validate:a", "catalog:a:enter", "catalog:a:done",
                "manifest:b", "validate:b", "catalog:b:enter", "catalog:b:done",
            ),
            calls,
        )
    }

    @Test
    fun differentOwnerFinalizersCanPublishConcurrently() = runTest {
        val coordinator = InstalledModelPublicationCoordinator()
        val bothPublishing = CompletableDeferred<Unit>()
        var activePublishers = 0
        var maxActivePublishers = 0
        val publisher = object : BundlePublisher {
            override suspend fun publish(ownerModelId: String, artifacts: List<DownloadMetadataDTO>): Boolean {
                activePublishers += 1
                maxActivePublishers = maxOf(maxActivePublishers, activePublishers)
                if (activePublishers == 2) bothPublishing.complete(Unit)
                bothPublishing.await()
                activePublishers -= 1
                return true
            }

            override suspend fun validate(ownerModelId: String, artifacts: List<DownloadMetadataDTO>) = true
        }
        val catalog = ModelCatalogPublisher { _, _ -> }
        val first = finalizer(finalizerBatch("a", ownerModelId = "owner/alpha"), publisher, catalog, coordinator)
        val second = finalizer(finalizerBatch("b", ownerModelId = "owner/beta"), publisher, catalog, coordinator)

        awaitAll(
            async { first.finalize("batch-a") },
            async { second.finalize("batch-b") },
        )

        assertEquals(2, maxActivePublishers)
    }

    @Test
    fun malformedEvidenceNeverPublishesReadyCatalogEntry() = runTest {
        val calls = mutableListOf<String>()
        val valid = finalizerBatch()
        val malformed = valid.evidence.copy(sha256 = "0".repeat(64))

        assertFailsWith<ArtifactVerificationException> {
            finalizer(valid.copy(evidence = malformed), calls).finalize("batch")
        }

        assertEquals(emptyList(), calls)
    }

    @Test
    fun evidenceArtifactMismatchNeverPublishesReadyCatalogEntry() = runTest {
        val calls = mutableListOf<String>()
        val mismatched = finalizerEvidence(modelPath = "other.gguf")

        assertFailsWith<ArtifactVerificationException> {
            finalizer(finalizerBatch(evidence = mismatched), calls).finalize("batch")
        }

        assertEquals(emptyList(), calls)
    }

    @Test
    fun nonCanonicalMatchingObjectIdNeverPublishesReadyCatalogEntry() = runTest {
        val batch = finalizerBatch()
        val identities = batch.artifacts.map { artifact ->
            val identity = artifact.request.metadata.artifact.toModelFileIdentity()
            if (identity.repositoryId == batch.ownerModelId) {
                ModelFileIdentity(
                    repositoryId = identity.repositoryId,
                    revision = identity.revision,
                    path = identity.path,
                    sizeBytes = identity.sizeBytes,
                    gitOid = null,
                    lfsOid = "c".repeat(64),
                    xetHash = artifact.request.metadata.artifact.remoteObjectId,
                    evidence = emptyList(),
                )
            } else {
                identity
            }
        }
        val conflicting = PersistedModelEvidenceCodec().encode(identities, descriptor = null)

        assertRejectedBeforeManifest(batch.copy(evidence = conflicting))
    }

    @Test
    fun restoredSnapshotWithoutPrimaryNeverPublishesReadyCatalogEntry() = runTest {
        val batch = finalizerBatch()
        val withoutPrimary = batch.copy(
            artifacts = batch.artifacts.map { artifact ->
                artifact.copy(request = artifact.request.copy(primary = false))
            },
        )

        assertRejectedBeforeManifest(withoutPrimary)
    }

    @Test
    fun restoredSnapshotWithPrimaryForDifferentOwnerNeverPublishesReadyCatalogEntry() = runTest {
        val batch = finalizerBatch().copy(ownerModelId = "other/owner")

        assertRejectedBeforeManifest(batch)
    }

    @Test
    fun restoredSnapshotWithCompleteDescriptorForDifferentOwnerNeverPublishesReadyCatalogEntry() = runTest {
        val batch = finalizerBatch()
        val identities = batch.artifacts.map { it.request.metadata.artifact.toModelFileIdentity() }
        val ownerIdentity = identities.single { it.repositoryId == batch.ownerModelId }
        val componentIdentity = identities.single { it.repositoryId != batch.ownerModelId }
        val descriptor = DiffusionModelDescriptor(
            repositoryId = componentIdentity.repositoryId,
            revision = componentIdentity.revision,
            components = listOf(
                DiffusionComponentDescriptor(
                    file = componentIdentity,
                    role = null,
                    required = true,
                    isPrimary = true,
                    quantization = QuantizationEvidence.Unknown,
                ),
                DiffusionComponentDescriptor(
                    file = ownerIdentity,
                    role = null,
                    required = true,
                    isPrimary = false,
                    quantization = QuantizationEvidence.Unknown,
                ),
            ),
            mode = DiffusionMode.IMAGE,
            family = "test",
            quantizationDistribution = emptySet(),
            requiredComponentsPresent = true,
            requiredEngineFeatures = emptySet(),
            evidence = emptyList(),
        )
        val complete = PersistedModelEvidenceCodec().encode(identities, descriptor)

        assertRejectedBeforeManifest(batch.copy(evidence = complete))
    }
}

private suspend fun assertRejectedBeforeManifest(batch: DownloadBatchSnapshot) {
    val calls = mutableListOf<String>()

    assertFailsWith<ArtifactVerificationException> {
        finalizer(batch, calls).finalize(batch.batchId)
    }

    assertEquals(emptyList(), calls)
}

private fun finalizer(
    batch: DownloadBatchSnapshot,
    calls: MutableList<String>,
) = ModelDownloadFinalizer(
    store = FinalizerStore(batch),
    bundlePublisher = object : BundlePublisher {
        override suspend fun publish(ownerModelId: String, artifacts: List<DownloadMetadataDTO>) =
            true.also { calls += "manifest:publish" }

        override suspend fun validate(ownerModelId: String, artifacts: List<DownloadMetadataDTO>) =
            true.also { calls += "manifest:validate" }
    },
    catalogPublisher = ModelCatalogPublisher { snapshot, evidence ->
        assertEquals(snapshot.evidence, evidence)
        calls += "catalog:${snapshot.artifacts.count { !it.request.primary }}"
    },
    publicationCoordinator = InstalledModelPublicationCoordinator(),
)

private fun finalizer(
    batch: DownloadBatchSnapshot,
    bundlePublisher: BundlePublisher,
    catalogPublisher: ModelCatalogPublisher,
    publicationCoordinator: InstalledModelPublicationCoordinator,
) = ModelDownloadFinalizer(
    store = FinalizerStore(batch),
    bundlePublisher = bundlePublisher,
    catalogPublisher = catalogPublisher,
    publicationCoordinator = publicationCoordinator,
)

private class FinalizerStore(private val batch: DownloadBatchSnapshot) : DownloadTaskStore {
    override suspend fun create(request: DownloadBatchRequest, nowEpochMs: Long) = batch.batchId
    override fun observeForModel(modelId: String) = flowOf(listOf(batch))
    override suspend fun getBatch(batchId: String) = batch
    override suspend fun recoverableBatches() = listOf(batch)
    override suspend fun claim(artifactId: String, owner: String, nowEpochMs: Long, expiresAtEpochMs: Long) = true
    override suspend fun updateProgress(artifactId: String, bytesReceived: Long, entityTag: String?, lastModified: String?, nowEpochMs: Long) = true
    override suspend fun transitionArtifact(artifactId: String, state: DownloadArtifactState, failureCode: DownloadFailureCode?, nowEpochMs: Long) = true
    override suspend fun setUserIntent(batchId: String, intent: DownloadUserIntent, nowEpochMs: Long) = true
    override suspend fun setPlatformTaskId(artifactId: String, platformTaskId: String?, nowEpochMs: Long) = true
    override suspend fun releaseLease(artifactId: String, owner: String, nowEpochMs: Long) = true
    override suspend fun clearAll() = Unit
}

private fun finalizerBatch(
    variant: String = "v1",
    ownerModelId: String = "owner/model",
    evidence: EncodedModelEvidence? = null,
): DownloadBatchSnapshot {
    fun artifact(repo: String, path: String, role: String, primary: Boolean): DownloadArtifactRequest {
        val identity = finalizerIdentity(repo, path)
        return DownloadArtifactRequest(
            DownloadMetadataDTO(identity, role, 10L, null, null, null, bundleId = "c".repeat(64)),
            primary,
        )
    }
    val requests = listOf(
        artifact(ownerModelId, "model-$variant.gguf", "model", true),
        artifact("$ownerModelId-component", "clip-$variant.gguf", "clip", false),
    )
    return DownloadBatchSnapshot(
        "batch-$variant", ownerModelId, "text", "Model", DownloadBatchState.VERIFYING, DownloadUserIntent.RUN,
        requests.mapIndexed { index, request ->
            DownloadArtifactSnapshot(
                "artifact-$index", "batch", request, DownloadArtifactState.VERIFYING,
                DownloadUserIntent.RUN, 10L, 10L,
            )
        },
        evidence ?: pendingEvidence(requests.map { it.metadata.artifact }),
    )
}

private fun directoryFinalizerBatch(): DownloadBatchSnapshot {
    val owner = "owner/diffusion"
    val bundleId = "e".repeat(64)
    val paths = listOf(
        "unet/diffusion_pytorch_model.safetensors" to "model",
        "vae/diffusion_pytorch_model.safetensors" to "diffusers-vae",
        "text_encoder/model.safetensors" to "diffusers-clip-l",
        "text_encoder_2/model.safetensors" to "diffusers-clip-g",
    )
    val requests = paths.map { (path, role) ->
        DownloadArtifactRequest(
            metadata = DownloadMetadataDTO(
                artifact = finalizerIdentity(owner, path),
                logicalRole = role,
                sizeBytes = 10L,
                author = null,
                libraryName = "diffusers",
                pipelineTag = "text-to-image",
                bundleId = bundleId,
            ),
            primary = true,
        )
    }
    return DownloadBatchSnapshot(
        batchId = "batch-directory",
        ownerModelId = owner,
        modelType = "image",
        displayName = "Diffusion",
        state = DownloadBatchState.VERIFYING,
        userIntent = DownloadUserIntent.RUN,
        artifacts = requests.mapIndexed { index, request ->
            DownloadArtifactSnapshot(
                artifactId = "directory-artifact-$index",
                batchId = "batch-directory",
                request = request,
                state = DownloadArtifactState.VERIFYING,
                userIntent = DownloadUserIntent.RUN,
                bytesReceived = 10L,
                expectedBytes = 10L,
            )
        },
        evidence = pendingEvidence(requests.map { it.metadata.artifact }),
    )
}

private class FinalizerStoragePathProvider : StoragePathProvider {
    override fun getModelsStorageDirectory(modelId: String): String = "/models/$modelId"
    override fun getDatabasePath(): String = "/databases/caraml.db"
    override fun fileExists(path: String): Boolean = false
    override fun getAvailableStorageBytes(): Long = Long.MAX_VALUE
    override fun getTotalStorageBytes(): Long = Long.MAX_VALUE
    override fun isModelFileReadable(path: String): Boolean = false
    override fun isDirectoryReadable(path: String): Boolean = false
    override fun getFileSize(path: String): Long = 0L
    override fun renameFile(from: String, to: String): Boolean = false
    override fun deleteDownloadedModelContent(modelId: String, localPath: String): Boolean = false
}

private fun DownloadBatchSnapshot.primaryVariant(): String =
    artifacts.single { it.request.primary }.request.metadata.destinationRelativePath
        .substringAfter("model-")
        .substringBefore(".gguf")

private fun List<DownloadMetadataDTO>.primaryVariant(): String =
    single { it.logicalRole == "model" }.destinationRelativePath
        .substringAfter("model-")
        .substringBefore(".gguf")

private fun finalizerEvidence(modelPath: String): EncodedModelEvidence = pendingEvidence(
    listOf(
        finalizerIdentity("owner/model", modelPath),
        finalizerIdentity("owner/component", "clip.gguf"),
    ),
)

private fun finalizerIdentity(repo: String, path: String): DownloadArtifactIdentity = requireNotNull(
    DownloadArtifactIdentity.create(repo, "a".repeat(40), path, "b".repeat(64), 10L),
)
