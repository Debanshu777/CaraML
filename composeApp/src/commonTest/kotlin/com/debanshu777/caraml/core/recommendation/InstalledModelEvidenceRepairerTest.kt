package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.recommendation.storage.InstalledEvidenceState
import com.debanshu777.caraml.core.recommendation.storage.PersistedModelEvidenceCodec
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
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.download.StoredArtifactKind
import com.debanshu777.huggingfacemanager.download.StoredArtifactSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
                repairer.requireComplete(fixture.model, emptyList(), GenerationMode.Text),
            )
            assertIs<EvidenceRepairResult.Ready>(
                repairer.requireComplete(fixture.model, emptyList(), GenerationMode.Text),
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
                repairer.requireComplete(fixture.model, emptyList(), GenerationMode.Text),
            )
            assertIs<EvidenceRepairResult.Ready>(
                repairer.requireComplete(fixture.model, emptyList(), GenerationMode.Text),
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
                async { repairer.requireComplete(fixture.model, emptyList(), GenerationMode.Text) }
            }.awaitAll()

            assertTrue(results.all { it is EvidenceRepairResult.Ready })
            assertEquals(1, lookups)
            assertEquals(1, maxConcurrent)
            assertEquals(1, fixture.dao.upsertCalls)
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
                repairer.requireComplete(fixture.model, emptyList(), GenerationMode.Text),
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
                repairer.requireComplete(fixture.model, emptyList(), GenerationMode.Text),
            )
            val second = assertIs<EvidenceRepairResult.Ready>(
                repairer.requireComplete(fixture.model, emptyList(), GenerationMode.Text),
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
                repairer.requireComplete(fixture.model, emptyList(), GenerationMode.Text),
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
            }.requireComplete(fixture.model, emptyList(), GenerationMode.Text)

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
            }.requireComplete(fixture.model, emptyList(), GenerationMode.Text)

            val rejected = assertIs<EvidenceRepairResult.Rejected>(result)
            assertEquals(listOf(AssessmentReason.INVALID_METADATA), rejected.reasons)
            assertFalse(FileSystem.SYSTEM.exists(fixture.root / ".caraml-local-identity-v1.json"))
            assertEquals(0, fixture.dao.upsertCalls)
        }
    }

    @Test
    fun repositoryFailureRejectsAndCancellationEscapes() = runTest {
        withFixture { fixture ->
            fixture.dao.writeFailure = IllegalStateException("database unavailable")
            val rejected = assertIs<EvidenceRepairResult.Rejected>(
                fixture.repairer { _, _, _ -> InstalledDescriptorLookup.Ready(fixture.descriptor) }
                    .requireComplete(fixture.model, emptyList(), GenerationMode.Text),
            )
            assertEquals(listOf(AssessmentReason.INVALID_METADATA), rejected.reasons)

            fixture.dao.writeFailure = CancellationException("cancelled")
            assertFailsWith<CancellationException> {
                fixture.repairer { _, _, _ -> InstalledDescriptorLookup.Ready(fixture.descriptor) }
                    .requireComplete(fixture.model, emptyList(), GenerationMode.Text)
            }
        }
    }

    private suspend fun TestScope.withFixture(
        manifestAvailable: Boolean = true,
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
                    repositoryId = "owner/model",
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
                repositoryId = "owner/model",
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
                modelId = "owner/model",
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
            block(Fixture(root, model, identity, descriptor, resolver, dao, codec, repository))
        } finally {
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    private data class Fixture(
        val root: Path,
        val model: LocalModelEntity,
        val identity: ModelFileIdentity,
        val descriptor: LlmModelDescriptor,
        val resolver: LocalArtifactIdentityResolver,
        val dao: FakeEvidenceDao,
        val codec: PersistedModelEvidenceCodec,
        val repository: InstalledModelEvidenceRepository,
    ) {
        fun repairer(
            lookup: suspend (String, ModelHubBrowseMode, List<ModelFileIdentity>) -> InstalledDescriptorLookup,
        ) = InstalledModelEvidenceRepairer(
            artifactResolver = resolver,
            evidenceRepository = repository,
            metadataSource = InstalledDescriptorMetadataSource(lookup),
            codec = codec,
            clock = { 2L },
        )
    }

    private class FakeEvidenceDao : InstalledModelEvidenceDao {
        var entity: InstalledModelEvidenceEntity? = null
        var upsertCalls: Int = 0
        var writeFailure: Throwable? = null

        override suspend fun get(modelId: String): InstalledModelEvidenceEntity? = entity?.takeIf { it.modelId == modelId }

        override suspend fun upsert(entity: InstalledModelEvidenceEntity) {
            writeFailure?.let { throw it }
            upsertCalls += 1
            this.entity = entity
        }

        override suspend fun delete(modelId: String) {
            if (entity?.modelId == modelId) entity = null
        }
    }

    private class TestStorage(
        private val root: Path,
        private val modelPath: Path,
        private val modelBytes: Long,
    ) : StoragePathProvider {
        override fun getModelsStorageDirectory(modelId: String): String = root.toString()
        override fun getDatabasePath(): String = (root / "db").toString()
        override fun fileExists(path: String): Boolean = FileSystem.SYSTEM.exists(path.toPath())
        override fun getAvailableStorageBytes(): Long = Long.MAX_VALUE
        override fun getTotalStorageBytes(): Long = Long.MAX_VALUE
        override fun isModelFileReadable(path: String): Boolean = path == modelPath.toString()
        override fun isDirectoryReadable(path: String): Boolean = path == root.toString()
        override fun getFileSize(path: String): Long = if (path == modelPath.toString()) modelBytes else 0L
        override fun renameFile(from: String, to: String): Boolean = false
        override fun deleteDownloadedModelContent(modelId: String, localPath: String): Boolean = false

        override fun inspectDownloadedArtifact(modelId: String, localPath: String): StoredArtifactSnapshot? = when (localPath) {
            modelPath.toString() -> StoredArtifactSnapshot(StoredArtifactKind.REGULAR_FILE, modelBytes, "model-stamp")
            root.toString() -> StoredArtifactSnapshot(StoredArtifactKind.DIRECTORY, 0L, "root-stamp")
            else -> null
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
