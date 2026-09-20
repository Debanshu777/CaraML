package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.recommendation.storage.InstalledEvidenceState
import com.debanshu777.caraml.core.recommendation.storage.PersistedModelEvidenceCodec
import com.debanshu777.caraml.core.download.BatchFinalizer
import com.debanshu777.caraml.core.download.BundlePublisher
import com.debanshu777.caraml.core.download.DownloadArtifactRequest
import com.debanshu777.caraml.core.download.DownloadArtifactSnapshot
import com.debanshu777.caraml.core.download.DownloadArtifactState
import com.debanshu777.caraml.core.download.DownloadBatchRequest
import com.debanshu777.caraml.core.download.DownloadBatchSnapshot
import com.debanshu777.caraml.core.download.DownloadBatchState
import com.debanshu777.caraml.core.download.DownloadFailureCode
import com.debanshu777.caraml.core.download.DownloadTaskStore
import com.debanshu777.caraml.core.download.DownloadUserIntent
import com.debanshu777.caraml.core.download.ModelCatalogPublisher
import com.debanshu777.caraml.core.download.ModelDownloadFinalizer
import com.debanshu777.caraml.core.storage.catalog.InstalledCatalogSnapshot
import com.debanshu777.caraml.core.storage.catalog.InstalledModelPublicationCoordinator
import com.debanshu777.caraml.core.storage.component.DownloadedComponentEntity
import com.debanshu777.caraml.core.storage.evidence.InstalledModelEvidenceDao
import com.debanshu777.caraml.core.storage.evidence.InstalledModelEvidenceEntity
import com.debanshu777.caraml.core.storage.evidence.InstalledModelEvidenceRepository
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import com.debanshu777.huggingfacemanager.download.ArtifactManifest
import com.debanshu777.huggingfacemanager.download.ArtifactManifestEntry
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.download.StoredArtifactKind
import com.debanshu777.huggingfacemanager.download.StoredArtifactSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InstalledModelEvidenceRepairerTest {
    @Test
    fun missingEvidenceIsFetchedPersistedAndReusedOffline() = runTest {
        withFixture { fixture ->
            var lookups = 0
            val repairer = fixture.repairer { _, _, _ ->
                lookups += 1
                InstalledDescriptorLookup.Ready(fixture.descriptor)
            }

            assertIs<EvidenceRepairResult.Ready>(
                repairer.requireComplete(fixture.model.modelId, GenerationMode.Text),
            )
            assertIs<EvidenceRepairResult.Ready>(
                repairer.requireComplete(fixture.model.modelId, GenerationMode.Text),
            )

            assertEquals(1, lookups)
            assertEquals(1, fixture.dao.upsertCalls)
            assertEquals(
                InstalledEvidenceState.COMPLETE,
                fixture.repository.get(fixture.model.modelId)?.state,
            )
        }
    }

    @Test
    fun enrichmentEvidenceIsRepairedAndThenReusedOffline() = runTest {
        withFixture { fixture ->
            fixture.repository.put(
                fixture.model.modelId,
                fixture.codec.encode(listOf(fixture.identity), descriptor = null),
                nowEpochMs = 1L,
            )
            fixture.dao.upsertCalls = 0
            var lookups = 0
            val repairer = fixture.repairer { _, _, _ ->
                lookups += 1
                InstalledDescriptorLookup.Ready(fixture.descriptor)
            }

            assertIs<EvidenceRepairResult.Ready>(
                repairer.requireComplete(fixture.model.modelId, GenerationMode.Text),
            )
            assertIs<EvidenceRepairResult.Ready>(
                repairer.requireComplete(fixture.model.modelId, GenerationMode.Text),
            )

            assertEquals(1, lookups)
            assertEquals(1, fixture.dao.upsertCalls)
        }
    }

    @Test
    fun concurrentRepairsSerializeToOneLookupAndOnePersistedRecord() = runTest {
        withFixture { fixture ->
            var lookups = 0
            var concurrent = 0
            var maxConcurrent = 0
            val repairer = fixture.repairer { _, _, _ ->
                lookups += 1
                concurrent += 1
                maxConcurrent = maxOf(maxConcurrent, concurrent)
                try {
                    delay(10)
                    InstalledDescriptorLookup.Ready(fixture.descriptor)
                } finally {
                    concurrent -= 1
                }
            }

            val results = List(8) {
                async { repairer.requireComplete(fixture.model.modelId, GenerationMode.Text) }
            }.awaitAll()

            assertTrue(results.all { it is EvidenceRepairResult.Ready })
            assertEquals(1, lookups)
            assertEquals(1, maxConcurrent)
            assertEquals(1, fixture.dao.upsertCalls)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cancelledRepairLeaderLetsLiveFollowersPerformOneSuccessorLookupAndWrite() = runTest {
        withFixture { fixture ->
            val firstLookupEntered = CompletableDeferred<Unit>()
            var lookups = 0
            val repairer = fixture.repairer { _, _, _ ->
                lookups += 1
                if (lookups == 1) {
                    firstLookupEntered.complete(Unit)
                    awaitCancellation()
                }
                InstalledDescriptorLookup.Ready(fixture.descriptor)
            }
            val leader = async(start = CoroutineStart.UNDISPATCHED) {
                repairer.requireComplete(fixture.model.modelId, GenerationMode.Text)
            }
            firstLookupEntered.await()
            val followers = List(8) {
                async(start = CoroutineStart.UNDISPATCHED) {
                    repairer.requireComplete(fixture.model.modelId, GenerationMode.Text)
                }
            }

            leader.cancel()
            assertFailsWith<CancellationException> { leader.await() }
            runCurrent()
            val results = followers.awaitAll()

            assertTrue(results.all { it is EvidenceRepairResult.Ready })
            assertEquals(2, lookups)
            assertEquals(1, fixture.dao.upsertCalls)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun differentOwnerRepairsCanPerformRemoteLookupConcurrently() = runTest {
        val coordinator = InstalledModelPublicationCoordinator()
        withFixture(ownerModelId = "owner/alpha", coordinator = coordinator) { first ->
            withFixture(ownerModelId = "owner/beta", coordinator = coordinator) { second ->
                val fixtures = listOf(first, second).associateBy { it.model.modelId }
                val releaseLookups = CompletableDeferred<Unit>()
                val firstLookupEntered = CompletableDeferred<Unit>()
                val bothLookupsEntered = CompletableDeferred<Unit>()
                var activeLookups = 0
                var maxActiveLookups = 0
                val repairer = repairerFor(fixtures, coordinator) { repositoryId, _, _ ->
                    activeLookups += 1
                    maxActiveLookups = maxOf(maxActiveLookups, activeLookups)
                    firstLookupEntered.complete(Unit)
                    if (activeLookups == 2) bothLookupsEntered.complete(Unit)
                    try {
                        releaseLookups.await()
                        InstalledDescriptorLookup.Ready(requireNotNull(fixtures[repositoryId]).descriptor)
                    } finally {
                        activeLookups -= 1
                    }
                }

                val firstJob = async(start = CoroutineStart.UNDISPATCHED) {
                    repairer.requireComplete(first.model.modelId, GenerationMode.Text)
                }
                firstLookupEntered.await()
                val secondJob = async(start = CoroutineStart.UNDISPATCHED) {
                    repairer.requireComplete(second.model.modelId, GenerationMode.Text)
                }
                runCurrent()
                try {
                    assertTrue(bothLookupsEntered.isCompleted)
                } finally {
                    releaseLookups.complete(Unit)
                }
                val results = awaitAll(firstJob, secondJob)

                assertTrue(results.all { it is EvidenceRepairResult.Ready })
                assertEquals(2, maxActiveLookups)
                assertEquals(1, first.dao.upsertCalls)
                assertEquals(1, second.dao.upsertCalls)
            }
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun caseDistinctOwnersNeverShareRepairResult() = runTest {
        val coordinator = InstalledModelPublicationCoordinator()
        withFixture(ownerModelId = "Org/Model", coordinator = coordinator) { upper ->
            withFixture(ownerModelId = "org/model", coordinator = coordinator) { lower ->
                val fixtures = listOf(upper, lower).associateBy { it.model.modelId }
                val firstLookupEntered = CompletableDeferred<Unit>()
                val releaseFirstLookup = CompletableDeferred<Unit>()
                val lookups = mutableListOf<String>()
                val repairer = repairerFor(fixtures, coordinator) { repositoryId, _, _ ->
                    lookups += repositoryId
                    if (repositoryId == upper.model.modelId) {
                        firstLookupEntered.complete(Unit)
                        releaseFirstLookup.await()
                    }
                    InstalledDescriptorLookup.Ready(requireNotNull(fixtures[repositoryId]).descriptor)
                }

                val upperJob = async(start = CoroutineStart.UNDISPATCHED) {
                    repairer.requireComplete(upper.model.modelId, GenerationMode.Text)
                }
                firstLookupEntered.await()
                val lowerJob = async(start = CoroutineStart.UNDISPATCHED) {
                    repairer.requireComplete(lower.model.modelId, GenerationMode.Text)
                }
                runCurrent()
                assertEquals(listOf(upper.model.modelId), lookups)

                releaseFirstLookup.complete(Unit)
                val upperReady = assertIs<EvidenceRepairResult.Ready>(upperJob.await())
                val lowerReady = assertIs<EvidenceRepairResult.Ready>(lowerJob.await())

                assertEquals(upper.descriptor, upperReady.descriptor)
                assertEquals(lower.descriptor, lowerReady.descriptor)
                assertEquals(listOf(upper.model.modelId, lower.model.modelId), lookups)
                assertEquals(1, upper.dao.upsertCalls)
                assertEquals(1, lower.dao.upsertCalls)
            }
        }
    }

    @Test
    fun repairBlockedInLookupCannotOverwriteNewerFinalizerPublication() = runTest {
        withFixture { fixture ->
            val lookupEntered = CompletableDeferred<Unit>()
            val releaseLookup = CompletableDeferred<Unit>()
            val repairer = fixture.repairer { _, _, _ ->
                lookupEntered.complete(Unit)
                releaseLookup.await()
                InstalledDescriptorLookup.Ready(fixture.descriptor)
            }
            val repairJob = async(start = CoroutineStart.UNDISPATCHED) {
                repairer.requireComplete(fixture.model.modelId, GenerationMode.Text)
            }
            lookupEntered.await()
            val replacement = fixture.replacementPublication("new")

            fixture.finalizer(replacement).finalize(replacement.batch.batchId)
            releaseLookup.complete(Unit)
            val ready = assertIs<EvidenceRepairResult.Ready>(repairJob.await())

            assertEquals(replacement.descriptor, ready.descriptor)
            assertEquals(replacement.evidence, fixture.dao.entity)
            assertEquals(0, fixture.dao.compareAndSetCalls)
        }
    }

    @Test
    fun manifestSuccessCatalogFailureStaysFailClosedUntilFinalizerRetryConverges() = runTest {
        withFixture { fixture ->
            val replacement = fixture.replacementPublication("retry")

            assertFailsWith<IllegalStateException> {
                fixture.finalizer(
                    replacement = replacement,
                    catalogFailure = IllegalStateException("catalog unavailable"),
                ).finalize(replacement.batch.batchId)
            }

            assertEquals(replacement.manifest, fixture.currentManifest)
            assertEquals(fixture.model, fixture.currentModel)
            assertEquals(null, fixture.dao.entity)
            var staleLookups = 0
            val rejected = assertIs<EvidenceRepairResult.Rejected>(
                fixture.repairer { _, _, _ ->
                    staleLookups += 1
                    InstalledDescriptorLookup.Ready(fixture.descriptor)
                }.requireComplete(fixture.model.modelId, GenerationMode.Text),
            )
            assertEquals(listOf(AssessmentReason.INVALID_METADATA), rejected.reasons)
            assertEquals(0, staleLookups)
            assertEquals(null, fixture.dao.entity)

            fixture.finalizer(replacement).finalize(replacement.batch.batchId)
            val ready = assertIs<EvidenceRepairResult.Ready>(
                fixture.repairer { _, _, _ -> error("complete retry evidence must be reused") }
                    .requireComplete(fixture.model.modelId, GenerationMode.Text),
            )

            assertEquals(replacement.model, fixture.currentModel)
            assertEquals(replacement.evidence, fixture.dao.entity)
            assertEquals(replacement.descriptor, ready.descriptor)
        }
    }

    @Test
    fun exactCompleteEvidenceIsReusedWithoutNetwork() = runTest {
        withFixture { fixture ->
            fixture.repository.put(
                fixture.model.modelId,
                fixture.codec.encode(listOf(fixture.identity), fixture.descriptor),
                nowEpochMs = 1L,
            )
            fixture.dao.upsertCalls = 0
            var lookups = 0
            val repairer = fixture.repairer { _, _, _ ->
                lookups += 1
                InstalledDescriptorLookup.RetryableUnavailable
            }

            val ready = assertIs<EvidenceRepairResult.Ready>(
                repairer.requireComplete(fixture.model.modelId, GenerationMode.Text),
            )

            assertEquals(fixture.descriptor, ready.descriptor)
            assertEquals(0, lookups)
            assertEquals(0, fixture.dao.upsertCalls)
        }
    }

    @Test
    fun incompleteLlmHeaderMetadataIsEnrichedLocallyAndPersistedForOfflineReuse() = runTest {
        withFixture { fixture ->
            val incomplete = descriptor(fixture.identity, architecture = null, ggufVersion = null)
            fixture.repository.put(
                fixture.model.modelId,
                fixture.codec.encode(listOf(fixture.identity), incomplete),
                nowEpochMs = 1L,
            )
            fixture.dao.upsertCalls = 0
            var lookups = 0
            val repairer = fixture.repairer { _, _, _ ->
                lookups += 1
                InstalledDescriptorLookup.RetryableUnavailable
            }

            val first = assertIs<EvidenceRepairResult.Ready>(
                repairer.requireComplete(fixture.model.modelId, GenerationMode.Text),
            )
            val second = assertIs<EvidenceRepairResult.Ready>(
                repairer.requireComplete(fixture.model.modelId, GenerationMode.Text),
            )

            assertEquals("llama", assertIs<LlmModelDescriptor>(first.descriptor).architecture)
            assertEquals(3, first.descriptor.ggufVersion)
            assertEquals(first.descriptor, second.descriptor)
            assertEquals(0, lookups)
            assertEquals(1, fixture.dao.upsertCalls)
        }
    }

    @Test
    fun staleCompleteEvidenceIsNotReusedBlindly() = runTest {
        withFixture { fixture ->
            val staleIdentity = fixture.identity.copyForTest(sizeBytes = fixture.identity.sizeBytes + 1L)
            val staleDescriptor = fixture.descriptor.copyForTest(staleIdentity)
            fixture.repository.put(
                fixture.model.modelId,
                fixture.codec.encode(listOf(staleIdentity), staleDescriptor),
                nowEpochMs = 1L,
            )
            fixture.dao.upsertCalls = 0
            var lookups = 0
            val repairer = fixture.repairer { _, mode, identities ->
                lookups += 1
                assertEquals(ModelHubBrowseMode.LanguageModels, mode)
                assertEquals(listOf(fixture.identity), identities)
                InstalledDescriptorLookup.Ready(fixture.descriptor)
            }

            val ready = assertIs<EvidenceRepairResult.Ready>(
                repairer.requireComplete(fixture.model.modelId, GenerationMode.Text),
            )

            assertEquals(fixture.descriptor, ready.descriptor)
            assertEquals(1, lookups)
            assertEquals(1, fixture.dao.upsertCalls)
        }
    }

    @Test
    fun networkFailureNeedsNetworkAndNeverMutatesInstalledFiles() = runTest {
        withFixture { fixture ->
            val original = FileSystem.SYSTEM.read(fixture.model.localPath.toPath()) { readByteArray() }
            val before = FileSystem.SYSTEM.list(fixture.root).map(Path::name).sorted()
            val result = fixture.repairer { _, _, _ ->
                InstalledDescriptorLookup.RetryableUnavailable
            }.requireComplete(fixture.model.modelId, GenerationMode.Text)

            assertEquals(EvidenceRepairResult.NeedsNetwork, result)
            assertEquals(original.toList(), FileSystem.SYSTEM.read(fixture.model.localPath.toPath()) { readByteArray() }.toList())
            assertEquals(before, FileSystem.SYSTEM.list(fixture.root).map(Path::name).sorted())
            assertEquals(0, fixture.dao.upsertCalls)
        }
    }

    @Test
    fun missingManifestRejectsWithoutCreatingLegacyIdentitySidecar() = runTest {
        withFixture(manifestAvailable = false) { fixture ->
            val result = fixture.repairer { _, _, _ ->
                error("metadata lookup must not run without exact manifest identity")
            }.requireComplete(fixture.model.modelId, GenerationMode.Text)

            val rejected = assertIs<EvidenceRepairResult.Rejected>(result)
            assertEquals(listOf(AssessmentReason.INVALID_METADATA), rejected.reasons)
            assertFalse(FileSystem.SYSTEM.exists(fixture.root / ".caraml-local-identity-v1.json"))
            assertEquals(0, fixture.dao.upsertCalls)
        }
    }

    @Test
    fun evidenceCasFailureRejectsAndCancellationEscapes() = runTest {
        withFixture { fixture ->
            fixture.dao.writeFailure = IllegalStateException("database unavailable")
            val rejected = assertIs<EvidenceRepairResult.Rejected>(
                fixture.repairer { _, _, _ -> InstalledDescriptorLookup.Ready(fixture.descriptor) }
                    .requireComplete(fixture.model.modelId, GenerationMode.Text),
            )
            assertEquals(listOf(AssessmentReason.INVALID_METADATA), rejected.reasons)

            fixture.dao.writeFailure = CancellationException("cancelled")
            assertFailsWith<CancellationException> {
                fixture.repairer { _, _, _ -> InstalledDescriptorLookup.Ready(fixture.descriptor) }
                    .requireComplete(fixture.model.modelId, GenerationMode.Text)
            }
        }
    }

    private suspend fun TestScope.withFixture(
        manifestAvailable: Boolean = true,
        ownerModelId: String = "owner/model",
        coordinator: InstalledModelPublicationCoordinator = InstalledModelPublicationCoordinator(),
        block: suspend (Fixture) -> Unit,
    ) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "caraml-repair-${Random.nextLong()}"
        FileSystem.SYSTEM.createDirectories(root)
        try {
            val bytes = minimalGguf(version = 3, architecture = "llama")
            val modelPath = root / "model.gguf"
            FileSystem.SYSTEM.write(modelPath) { write(bytes) }
            val digest = Buffer().write(bytes).snapshot().sha256().hex()
            val revision = "a".repeat(40)
            val remoteObjectId = "sha256:$digest"
            val downloadIdentity = assertNotNull(
                DownloadArtifactIdentity.create(
                    repositoryId = ownerModelId,
                    immutableRevision = revision,
                    relativePath = "model.gguf",
                    remoteObjectId = remoteObjectId,
                    expectedBytes = bytes.size.toLong(),
                ),
            )
            val manifest = assertNotNull(
                ArtifactManifest.create(
                    listOf(
                        assertNotNull(
                            ArtifactManifestEntry.create(
                                logicalRole = "model",
                                identity = downloadIdentity,
                                byteCount = bytes.size.toLong(),
                                contentSha256 = digest,
                            ),
                        ),
                    ),
                ),
            )
            val identity = ModelFileIdentity(
                repositoryId = ownerModelId,
                revision = revision,
                path = "model.gguf",
                sizeBytes = bytes.size.toLong(),
                gitOid = null,
                lfsOid = remoteObjectId,
                xetHash = null,
                evidence = emptyList(),
            )
            val descriptor = descriptor(identity)
            val model = LocalModelEntity(
                modelId = ownerModelId,
                filename = "model.gguf",
                localPath = modelPath.toString(),
                sizeBytes = bytes.size.toLong(),
                downloadedAt = 1L,
                author = "owner",
                libraryName = null,
                pipelineTag = "text-generation",
                componentStatus = LocalModelEntity.STATUS_READY,
            )
            val storage = TestStorage(root, modelPath, bytes.size.toLong())
            val resolver = LocalArtifactIdentityResolver(
                storagePathProvider = storage,
                manifestSource = { if (manifestAvailable) manifest else null },
                hashingDispatcher = StandardTestDispatcher(testScheduler),
                fileSystem = FileSystem.SYSTEM,
            )
            val dao = FakeEvidenceDao()
            val codec = PersistedModelEvidenceCodec()
            val repository = InstalledModelEvidenceRepository(dao, codec)
            block(
                Fixture(
                    root = root,
                    model = model,
                    identity = identity,
                    descriptor = descriptor,
                    manifest = manifest,
                    storage = storage,
                    resolver = resolver,
                    dao = dao,
                    codec = codec,
                    repository = repository,
                    coordinator = coordinator,
                    manifestAvailable = manifestAvailable,
                ),
            )
        } finally {
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    private class Fixture(
        val root: Path,
        val model: LocalModelEntity,
        val identity: ModelFileIdentity,
        val descriptor: LlmModelDescriptor,
        val manifest: ArtifactManifest,
        val storage: TestStorage,
        val resolver: LocalArtifactIdentityResolver,
        val dao: FakeEvidenceDao,
        val codec: PersistedModelEvidenceCodec,
        val repository: InstalledModelEvidenceRepository,
        val coordinator: InstalledModelPublicationCoordinator,
        val manifestAvailable: Boolean,
    ) {
        var currentModel: LocalModelEntity = model
        var currentComponents: List<DownloadedComponentEntity> = emptyList()
        var currentManifest: ArtifactManifest? = manifest.takeIf { manifestAvailable }

        fun catalogSnapshot(modelId: String): InstalledCatalogSnapshot? =
            currentModel.takeIf { it.modelId == modelId }?.let { persisted ->
                InstalledCatalogSnapshot(persisted, currentComponents, dao.entity)
            }

        fun repairer(
            lookup: suspend (String, ModelHubBrowseMode, List<ModelFileIdentity>) -> InstalledDescriptorLookup,
        ) = InstalledModelEvidenceRepairer(
            publicationCoordinator = coordinator,
            catalogSnapshot = ::catalogSnapshot,
            manifestSource = { modelId -> currentManifest?.takeIf { currentModel.modelId == modelId } },
            resolveArtifact = resolver::resolve,
            evidenceCompareAndSet = repository::compareAndSet,
            metadataSource = InstalledDescriptorMetadataSource(lookup),
            codec = codec,
            ggufMetadataInspector = GgufMetadataInspector(),
            clock = { 2L },
        )

        fun replacementPublication(variant: String): ReplacementPublication {
            val bytes = minimalGguf(version = 3, architecture = "llama") + variant.encodeToByteArray()
            val path = root / "model-$variant.gguf"
            FileSystem.SYSTEM.write(path) { write(bytes) }
            storage.addArtifact(path, bytes.size.toLong())
            val digest = Buffer().write(bytes).snapshot().sha256().hex()
            val revision = "c".repeat(40)
            val remoteObjectId = "sha256:$digest"
            val downloadIdentity = requireNotNull(
                DownloadArtifactIdentity.create(
                    repositoryId = model.modelId,
                    immutableRevision = revision,
                    relativePath = path.name,
                    remoteObjectId = remoteObjectId,
                    expectedBytes = bytes.size.toLong(),
                ),
            )
            val manifest = requireNotNull(
                ArtifactManifest.create(
                    listOf(
                        requireNotNull(
                            ArtifactManifestEntry.create(
                                logicalRole = "model",
                                identity = downloadIdentity,
                                byteCount = bytes.size.toLong(),
                                contentSha256 = digest,
                            ),
                        ),
                    ),
                ),
            )
            val identity = ModelFileIdentity(
                repositoryId = model.modelId,
                revision = revision,
                path = path.name,
                sizeBytes = bytes.size.toLong(),
                gitOid = null,
                lfsOid = remoteObjectId,
                xetHash = null,
                evidence = emptyList(),
            )
            val descriptor = descriptor(identity)
            val encoded = codec.encode(listOf(identity), descriptor)
            val evidence = InstalledModelEvidenceEntity(
                modelId = model.modelId,
                evidenceState = encoded.state.name,
                schemaVersion = encoded.schemaVersion,
                payload = encoded.payload,
                sha256 = encoded.sha256,
                publishedAtEpochMs = 3L,
            )
            val metadata = DownloadMetadataDTO(
                artifact = downloadIdentity,
                logicalRole = "model",
                sizeBytes = bytes.size.toLong(),
                author = "owner",
                libraryName = null,
                pipelineTag = "text-generation",
                bundleId = manifest.entries.single().bundleId,
            )
            val request = DownloadArtifactRequest(metadata, primary = true)
            val batch = DownloadBatchSnapshot(
                batchId = "batch-$variant",
                ownerModelId = model.modelId,
                modelType = "text",
                displayName = "Model",
                state = DownloadBatchState.VERIFYING,
                userIntent = DownloadUserIntent.RUN,
                artifacts = listOf(
                    DownloadArtifactSnapshot(
                        artifactId = "artifact-$variant",
                        batchId = "batch-$variant",
                        request = request,
                        state = DownloadArtifactState.VERIFYING,
                        userIntent = DownloadUserIntent.RUN,
                        bytesReceived = bytes.size.toLong(),
                        expectedBytes = bytes.size.toLong(),
                    ),
                ),
                evidence = encoded,
            )
            return ReplacementPublication(
                batch = batch,
                manifest = manifest,
                model = model.copy(
                    id = 2L,
                    filename = path.name,
                    localPath = path.toString(),
                    sizeBytes = bytes.size.toLong(),
                    downloadedAt = 3L,
                ),
                descriptor = descriptor,
                evidence = evidence,
            )
        }

        fun finalizer(
            replacement: ReplacementPublication,
            catalogFailure: Throwable? = null,
        ): BatchFinalizer = ModelDownloadFinalizer(
            store = SingleBatchStore(replacement.batch),
            bundlePublisher = object : BundlePublisher {
                override suspend fun publish(
                    ownerModelId: String,
                    artifacts: List<DownloadMetadataDTO>,
                ): Boolean = true.also { currentManifest = replacement.manifest }

                override suspend fun validate(
                    ownerModelId: String,
                    artifacts: List<DownloadMetadataDTO>,
                ): Boolean = currentManifest == replacement.manifest
            },
            catalogPublisher = ModelCatalogPublisher { _, _ ->
                catalogFailure?.let { throw it }
                currentModel = replacement.model
                currentComponents = emptyList()
                dao.entity = replacement.evidence
            },
            publicationCoordinator = coordinator,
        )
    }

    private fun repairerFor(
        fixtures: Map<String, Fixture>,
        coordinator: InstalledModelPublicationCoordinator,
        lookup: suspend (String, ModelHubBrowseMode, List<ModelFileIdentity>) -> InstalledDescriptorLookup,
    ) = InstalledModelEvidenceRepairer(
        publicationCoordinator = coordinator,
        catalogSnapshot = { modelId -> fixtures[modelId]?.catalogSnapshot(modelId) },
        manifestSource = { modelId -> fixtures[modelId]?.currentManifest },
        resolveArtifact = { model, components, manifest ->
            requireNotNull(fixtures[model.modelId]).resolver.resolve(model, components, manifest)
        },
        evidenceCompareAndSet = { modelId, expected, evidence, nowEpochMs ->
            requireNotNull(fixtures[modelId]).repository.compareAndSet(modelId, expected, evidence, nowEpochMs)
        },
        metadataSource = InstalledDescriptorMetadataSource(lookup),
        codec = PersistedModelEvidenceCodec(),
        ggufMetadataInspector = GgufMetadataInspector(),
        clock = { 2L },
    )

    private data class ReplacementPublication(
        val batch: DownloadBatchSnapshot,
        val manifest: ArtifactManifest,
        val model: LocalModelEntity,
        val descriptor: LlmModelDescriptor,
        val evidence: InstalledModelEvidenceEntity,
    )

    private class SingleBatchStore(
        private val batch: DownloadBatchSnapshot,
    ) : DownloadTaskStore {
        override suspend fun create(request: DownloadBatchRequest, nowEpochMs: Long) = batch.batchId
        override fun observeForModel(modelId: String) = flowOf(listOf(batch))
        override suspend fun getBatch(batchId: String) = batch.takeIf { it.batchId == batchId }
        override suspend fun recoverableBatches() = listOf(batch)
        override suspend fun claim(
            artifactId: String,
            owner: String,
            nowEpochMs: Long,
            expiresAtEpochMs: Long,
        ) = true

        override suspend fun updateProgress(
            artifactId: String,
            bytesReceived: Long,
            entityTag: String?,
            lastModified: String?,
            nowEpochMs: Long,
        ) = true

        override suspend fun transitionArtifact(
            artifactId: String,
            state: DownloadArtifactState,
            failureCode: DownloadFailureCode?,
            nowEpochMs: Long,
        ) = true

        override suspend fun setUserIntent(batchId: String, intent: DownloadUserIntent, nowEpochMs: Long) = true
        override suspend fun setPlatformTaskId(
            artifactId: String,
            platformTaskId: String?,
            nowEpochMs: Long,
        ) = true

        override suspend fun releaseLease(artifactId: String, owner: String, nowEpochMs: Long) = true
        override suspend fun clearAll() = Unit
    }

    private class FakeEvidenceDao : InstalledModelEvidenceDao {
        var entity: InstalledModelEvidenceEntity? = null
        var upsertCalls: Int = 0
        var compareAndSetCalls: Int = 0
        var writeFailure: Throwable? = null

        override suspend fun get(modelId: String): InstalledModelEvidenceEntity? = entity?.takeIf { it.modelId == modelId }

        override suspend fun upsert(entity: InstalledModelEvidenceEntity) {
            writeFailure?.let { throw it }
            upsertCalls += 1
            this.entity = entity
        }

        override suspend fun insertIfAbsent(entity: InstalledModelEvidenceEntity): Long {
            writeFailure?.let { throw it }
            compareAndSetCalls += 1
            if (this.entity != null) return -1L
            upsertCalls += 1
            this.entity = entity
            return 1L
        }

        override suspend fun update(entity: InstalledModelEvidenceEntity): Int {
            writeFailure?.let { throw it }
            compareAndSetCalls += 1
            if (this.entity?.modelId != entity.modelId) return 0
            upsertCalls += 1
            this.entity = entity
            return 1
        }

        override suspend fun delete(modelId: String) {
            if (entity?.modelId == modelId) entity = null
        }
    }

    private class TestStorage(
        private val root: Path,
        modelPath: Path,
        modelBytes: Long,
    ) : StoragePathProvider {
        private val artifacts = mutableMapOf(modelPath.toString() to modelBytes)

        fun addArtifact(path: Path, bytes: Long) {
            artifacts[path.toString()] = bytes
        }

        override fun getModelsStorageDirectory(modelId: String): String = root.toString()
        override fun getDatabasePath(): String = (root / "db").toString()
        override fun fileExists(path: String): Boolean = FileSystem.SYSTEM.exists(path.toPath())
        override fun getAvailableStorageBytes(): Long = Long.MAX_VALUE
        override fun getTotalStorageBytes(): Long = Long.MAX_VALUE
        override fun isModelFileReadable(path: String): Boolean = path in artifacts
        override fun isDirectoryReadable(path: String): Boolean = path == root.toString()
        override fun getFileSize(path: String): Long = artifacts[path] ?: 0L
        override fun renameFile(from: String, to: String): Boolean = false
        override fun deleteDownloadedModelContent(modelId: String, localPath: String): Boolean = false

        override fun inspectDownloadedArtifact(modelId: String, localPath: String): StoredArtifactSnapshot? = when (localPath) {
            root.toString() -> StoredArtifactSnapshot(StoredArtifactKind.DIRECTORY, 0L, "root-stamp")
            else -> artifacts[localPath]?.let { bytes ->
                StoredArtifactSnapshot(StoredArtifactKind.REGULAR_FILE, bytes, "model-stamp:$localPath:$bytes")
            }
        }
    }
}

private fun descriptor(
    identity: ModelFileIdentity,
    architecture: String? = "llama",
    ggufVersion: Int? = 3,
) = LlmModelDescriptor(
    repositoryId = identity.repositoryId,
    revision = identity.revision,
    file = identity,
    architecture = architecture,
    quantization = QuantizationEvidence.Known("Q4_K_M"),
    parameterCount = 1_000_000L,
    contextLimit = 4_096,
    transformerShape = null,
    ggufVersion = ggufVersion,
    requiredEngineFeatures = emptyList(),
    evidence = emptyList(),
)

private fun ModelFileIdentity.copyForTest(
    repositoryId: String = this.repositoryId,
    revision: String = this.revision,
    path: String = this.path,
    sizeBytes: Long = this.sizeBytes,
    gitOid: String? = this.gitOid,
    lfsOid: String? = this.lfsOid,
    xetHash: String? = this.xetHash,
) = ModelFileIdentity(repositoryId, revision, path, sizeBytes, gitOid, lfsOid, xetHash, evidence)

private fun LlmModelDescriptor.copyForTest(identity: ModelFileIdentity) = descriptor(identity)
