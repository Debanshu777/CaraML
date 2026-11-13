package com.debanshu777.caraml.core.data.inference

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.download.transfer.*
import com.debanshu777.caraml.core.platform.BackendCapabilitySource
import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.recommendation.*
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.settings.KvQuantPreset
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.sdk.plugin.onnx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.*
import kotlin.time.Clock

/** Real repository, admission, resolver and root lease; only native inference and hardware are faked. */
@OptIn(ExperimentalCoroutinesApi::class)
class OnnxResidentReuseTest {
    @Test fun idleSelectionReusesWithoutLoadingHashingOrWritingRecoveryAgain() = runTest {
        withFixture { f ->
            assertNull(f.reuse())
            f.load()
            val probes = f.snapshotReads
            assertEquals(ModelLoadResult.Success(2048), f.reuse())
            f.settings.value = f.settings.value.copy(benchmarkMode = true, modelProfileOnboardingComplete = true)
            assertEquals(ModelLoadResult.Success(2048), f.repository.tryReuseInstalledModel(
                f.request.model.copy(usageCount = 99), f.artifact))
            assertEquals(1, f.runtime.loads)
            assertEquals(0, f.runtime.unloads)
            assertEquals(probes, f.snapshotReads, "Resident reuse must not rerun allocation admission")
            assertNull(f.recovery.recoverPendingLoad())
            assertEquals(0, f.recovery.recoveryRecordCount())
            f.load()
            assertEquals(2, f.runtime.loads, "loadModel remains an explicit full load")
        }
    }

    @Test fun runtimeSettingsModelAndArtifactChangesRejectTheResidentSession() = runTest {
        withFixture { f ->
            f.load()
            val initial = f.settings.value
            val changed = listOf(initial.copy(systemPrompt = "changed"), initial.copy(temperature = 0.8f),
                initial.copy(useGpu = !initial.useGpu), initial.copy(kvQuantPreset = KvQuantPreset.F16_F16),
                initial.copy(riskTolerance = RiskTolerance.CONSERVATIVE),
                initial.copy(optimizationPriority = OptimizationPriority.SPEED_EFFICIENCY))
            for (setting in changed) {
                f.settings.value = setting
                assertNull(f.reuse(), setting.toString())
            }
            f.settings.value = initial
            assertNull(f.repository.tryReuseInstalledModel(f.request.model.copy(contextLength = 1024), f.artifact))
            assertNull(f.repository.tryReuseInstalledModel(f.request.model.copy(modelId = "other/model"), f.artifact))
            assertNull(f.repository.tryReuseInstalledModel(f.request.model, f.artifact.copy(
                components = f.artifact.components.map { it.copy(contentSha256 = "f".repeat(64)) })))
            f.runtime.engineVersion = "changed-engine"
            assertNull(f.reuse())
            assertEquals(1, f.runtime.loads)
        }
    }

    @Test fun missingTruncatedOrReplacedInstalledFilesFailTheFreshLeaseCheck() = runTest {
        for (mutation in listOf("truncate", "remove", "manifest")) withFixture { f ->
            f.load()
            val weights = f.root / f.manifest.entries.single { it.identity.relativePath.endsWith(".data") }.localRelativePath
            when (mutation) {
                "truncate" -> FileSystem.SYSTEM.write(weights) { writeUtf8("x") }
                "remove" -> FileSystem.SYSTEM.delete(weights)
                else -> {
                    val replaced = requireNotNull(ArtifactManifest.create(f.manifest.entries.map { entry ->
                        requireNotNull(ArtifactManifestEntry.create(
                            if (entry.logicalRole == "config.json") "new-config" else entry.logicalRole,
                            entry.identity, entry.byteCount, entry.contentSha256, entry.bundleId,
                            entry.localRelativePath, entry.layoutRelativePath))
                    }))
                    for (name in listOf(ArtifactManifestStore.MANIFEST_FILE_NAME, ArtifactBundleManifestStore.MANIFEST_FILE_NAME)) {
                        FileSystem.SYSTEM.write(f.root / name) { writeUtf8(Json.encodeToString(replaced)) }
                    }
                }
            }
            assertNull(f.reuse(), mutation)
            assertEquals(1, f.runtime.loads)
        }
    }

    @Test fun unloadedUnsupportedOrWrongRuntimeContextCannotBeReused() = runTest {
        withFixture { f ->
            f.load()
            f.runtime.contextLimit = 0
            assertNull(f.reuse())
            f.runtime.contextLimit = 1024
            assertNull(f.reuse())
            f.runtime.contextLimit = 2048
            f.runtime.supported = false
            assertNull(f.reuse())
        }
    }

    @Test fun failedNativeLoadOrClampedContextCannotPublishAReadyKey() = runTest {
        withFixture { f ->
            f.runtime.afterLoad = { throw IllegalStateException("session construction failed") }
            assertIs<ModelLoadResult.Error>(f.repository.loadModel(f.request))
            assertNull(f.reuse())
            assertEquals(1, f.runtime.unloads, "Failed native state must pass through coordinator cleanup")
        }
        withFixture { f ->
            f.runtime.afterLoad = { f.runtime.contextLimit = 1024 }
            f.load()
            assertNull(f.reuse(), "The requested configuration must equal the effective runtime context")
        }
    }

    @Test fun exactRuntimeConfigurationAndAdmittedPlanParticipateInTheKey() = runTest {
        withFixture { f ->
            val config = OnnxRuntimeConfig(contextTokens = 2048, provider = OnnxExecutionProvider.CPU,
                temperature = f.settings.value.temperature)
            fun key(request: LoadRequest = f.request, candidate: OnnxRuntimeConfig = config) =
                ResidentOnnxReuseKey.from(request, f.settings.value, candidate, f.runtime.engineVersion)
            val original = assertNotNull(key())
            assertNotEquals(original, key(candidate = config.copy(threads = 2)))
            assertNotEquals(original, key(candidate = config.copy(lowMemory = true)))
            assertNotEquals(original, key(candidate = config.copy(basicOptimizations = true)))
            val changedPlan = LlmRunPlan(2048, 256, 64, 1, KvCacheType.F32, KvCacheType.F32,
                BackendKind.CPU, MemoryTopology.UNIFIED, 0, compromises = emptyList())
            assertNotEquals(original, key(request = f.request.copy(plan = changedPlan)))
            assertNull(key(candidate = config.copy(contextTokens = 1024)))
            assertNull(key(candidate = config.copy(provider = OnnxExecutionProvider.XNNPACK)))
        }
    }

    @Test fun resetSummaryRetryCancellationAndFailedReplacementInvalidateEligibility() = runTest {
        for (action in listOf("reset", "summaryReset", "summarize", "retry", "cancel", "replacement", "unload")) {
            withFixture { f ->
                f.load()
                when (action) {
                    "reset" -> f.repository.resetContext()
                    "summaryReset" -> assertTrue(f.repository.resetContextWithSummary("summary", "exchange"))
                    "summarize" -> f.repository.summarizeConversation("transcript").toList()
                    "retry" -> f.repository.allowExplicitRetry(f.request)
                    "cancel" -> f.repository.cancelGeneration()
                    "replacement" -> assertIs<ModelLoadResult.Error>(f.repository.loadModel(f.request.copy(artifact = null)))
                    "unload" -> f.repository.unloadModel()
                }
                assertNull(f.reuse(), action)
            }
        }
    }

    @Test fun successfulGenerationRetainsEligibilityButErrorsAndCancellationClearIt() = runTest {
        withFixture { f ->
            f.load()
            val output = f.repository.generateResponse("synthetic prompt").toList().joinToString("") { it.contentDelta }
            assertEquals("answer", output)
            assertNotNull(f.reuse())
            f.runtime.failure = IllegalStateException("generation failed")
            assertFailsWith<IllegalStateException> { f.repository.generateResponse("synthetic prompt").toList() }
            assertNull(f.reuse())
        }
        for (reason in listOf(OnnxStopReason.CANCELLED, OnnxStopReason.ERROR)) withFixture { f ->
            f.load()
            f.runtime.terminalReason = reason
            f.repository.generateResponse("synthetic prompt").toList()
            assertNull(f.reuse(), "A terminal failure can be reported without throwing")
        }
        withFixture { f ->
            f.load()
            val entered = CompletableDeferred<Unit>()
            f.runtime.beforeGenerate = { entered.complete(Unit); awaitCancellation() }
            val generation = launch { f.repository.generateResponse("synthetic prompt").toList() }
            entered.await()
            val cancels = f.runtime.cancels
            assertNull(f.reuse(), "Reuse must not wait for or interrupt generation")
            assertEquals(cancels, f.runtime.cancels)
            generation.cancelAndJoin()
            assertNull(f.reuse())
        }
    }

    @Test fun cancellationWhileWaitingForTheLeaseCannotReturnAStaleReadyKey() = runTest {
        withFixture { f ->
            f.load()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.lifetime.beforeBlock = { entered.complete(Unit); release.await() }
            val reuse = async { f.reuse() }
            entered.await()
            f.repository.cancelGeneration()
            release.complete(Unit)
            assertNull(reuse.await())
            f.lifetime.beforeBlock = {}
            assertNull(f.reuse())
        }
    }

    @Test fun suspendedReuseHoldsTheActualRootLeaseAndCancellationReleasesBothLocks() = runTest {
        withFixture { f ->
            f.load()
            val entered = CompletableDeferred<Unit>()
            f.lifetime.beforeBlock = { entered.complete(Unit); awaitCancellation() }
            val reuse = launch { f.reuse() }
            entered.await()
            var mutationEntered = false
            val mutation = launch {
                ArtifactRootLockCoordinator.withRoots(listOf(f.root.toString())) { mutationEntered = true }
            }
            runCurrent()
            assertFalse(mutationEntered)
            reuse.cancelAndJoin()
            mutation.join()
            assertTrue(mutationEntered)
            f.lifetime.beforeBlock = {}
            f.load()
            assertNotNull(f.reuse(), "A cancelled reuse must release repository and coordinator ownership")
        }
    }

    @Test fun cancelledLoadingCannotPublishReuseEligibilityAfterTheRuntimeReturns() = runTest {
        withFixture { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.runtime.afterLoad = { entered.complete(Unit); release.await() }
            val loading = async { f.load() }
            entered.await()
            f.repository.cancelGeneration()
            release.complete(Unit)
            loading.await()
            assertNull(f.reuse())
            f.runtime.afterLoad = {}
            f.load()
            assertNotNull(f.reuse())
        }
    }

    private suspend fun TestScope.withFixture(block: suspend (Fixture) -> Unit) {
        val modelsRoot = Files.createTempDirectory("caraml-onnx-resident-").toRealPath().toString().toPath()
        // SecureArtifactRoot expects <models>/<owner>/<model>. Using the temp directory
        // itself accidentally worked with macOS's deeper temp path, but fails under /tmp.
        val root = modelsRoot / "owner" / "onnx"
        val stateScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val f = Fixture(root, stateScope, StandardTestDispatcher(testScheduler))
            f.initialize()
            block(f)
        } finally {
            stateScope.coroutineContext[Job]!!.cancelAndJoin()
            FileSystem.SYSTEM.deleteRecursively(modelsRoot, mustExist = false)
        }
    }

    private class Fixture(val root: Path, stateScope: CoroutineScope, dispatcher: CoroutineDispatcher) {
        val runtime = FakeRuntime()
        val settings = FakeSettings()
        private val storage = TestStorage(root)
        private val bytes = listOf("model.onnx", "model.onnx.data", "config.json", "tokenizer.json")
            .associateWith { "fixture-$it".encodeToByteArray() }
        private val identities = bytes.map { (path, data) -> requireNotNull(DownloadArtifactIdentity.create(
            "owner/onnx", "a".repeat(40), path, "sha256:${Buffer().write(data).snapshot().sha256().hex()}", data.size.toLong())) }
        private val bundle = requireNotNull(artifactBundleId(identities))
        val manifest = requireNotNull(ArtifactManifest.create(identities.map { identity ->
            val location = immutableArtifactStorageLocation(identity, bundle)
            requireNotNull(ArtifactManifestEntry.create(
                if (identity.relativePath == "model.onnx") "model" else identity.relativePath,
                identity, identity.expectedBytes, identity.remoteObjectId!!.removePrefix("sha256:"), bundle,
                location.localRelativePath, location.layoutRelativePath))
        }))
        val recovery = LoadRecoveryRepository(
            PreferenceDataStoreFactory.createWithPath(scope = stateScope) { root / "recovery.preferences_pb" },
            runtime.engineVersion, { Clock.System.now().toEpochMilliseconds() })
        val lifetime = ObservedLifetime(artifactRootLifetime(storage))
        private val resolver = LocalArtifactIdentityResolver(storage, { manifest }, dispatcher,
            hashFile = { _, _ -> error("Load/reuse must not hash model payloads") }, verifyContent = false)
        var snapshotReads = 0
        private val snapshots = DeviceSnapshotProvider(
            hardwareProfileSource = { snapshotReads++; task6Hardware(topology = MemoryTopology.UNIFIED) },
            resourceSnapshotSource = { task6ResourceSnapshot(hostBytes = 20L * 1024 * 1024 * 1024) },
            backendCapabilitySource = BackendCapabilitySource { listOf(task6Backend(BackendKind.CPU)) },
            storageBytesSource = { 20L * 1024 * 1024 * 1024 }, probeDispatcher = dispatcher,
            clock = { Clock.System.now().toEpochMilliseconds() })
        val repository = OnnxInferenceRepository(runtime, settings, snapshots,
            SuitabilityEngine(CompatibilityChecker(EngineCapabilitySource { SupportEvidence.Supported }), NoCalibrationSource),
            RecommendationPolicy(), recovery, resolver, LoadSessionCoordinator(recovery, lifetime))
        lateinit var artifact: ResolvedLocalArtifact
        lateinit var request: LoadRequest

        suspend fun initialize() {
            for (entry in manifest.entries) {
                val path = root / entry.localRelativePath
                FileSystem.SYSTEM.createDirectories(path.parent!!)
                FileSystem.SYSTEM.write(path) { write(bytes.getValue(entry.identity.relativePath)) }
            }
            for (name in listOf(ArtifactManifestStore.MANIFEST_FILE_NAME, ArtifactBundleManifestStore.MANIFEST_FILE_NAME)) {
                FileSystem.SYSTEM.write(root / name) { writeUtf8(Json.encodeToString(manifest)) }
            }
            val primary = manifest.entries.single { it.logicalRole == "model" }
            val model = LocalModelEntity(modelId = "owner/onnx", filename = "model.onnx",
                localPath = (root / primary.localRelativePath).toString(), sizeBytes = primary.byteCount,
                downloadedAt = 1, author = null, libraryName = null, pipelineTag = "text-generation", contextLength = 2048)
            artifact = assertIs<ArtifactIdentityResolution.Verified>(resolver.resolve(model, emptyList())).artifact
            val plan = LlmRunPlan(2048, 128, 64, 1, KvCacheType.F32, KvCacheType.F32,
                BackendKind.CPU, MemoryTopology.UNIFIED, 0, compromises = emptyList())
            // CPU allocations use the host estimate, including on unified-memory devices.
            val assessed = AssessedPlans(listOf(task6PlanAssessment(plan = plan)),
                "onnx-resident", compatibility = Compatibility.Compatible, memoryTopology = MemoryTopology.UNIFIED)
            request = LoadRequest(model, artifact.identity, requireNotNull(ObservationModelIdentity.fromDescriptor(task6LlmDescriptor())),
                plan, "onnx-resident", artifact = artifact, assessedPlans = assessed)
        }

        suspend fun load() {
            val result = repository.loadModel(request)
            assertIs<ModelLoadResult.Success>(result, "Fixture load failed: $result")
        }
        suspend fun reuse() = repository.tryReuseInstalledModel(request.model, artifact)
    }

    private class ObservedLifetime(private val delegate: ArtifactRootLifetime) : ArtifactRootLifetime {
        var beforeBlock: suspend () -> Unit = {}
        override suspend fun <T> withCurrentBundle(ownerModelId: String, expectedRepositoryRoots: Map<String, String>,
            block: suspend (ArtifactManifest?) -> T): T = delegate.withCurrentBundle(ownerModelId, expectedRepositoryRoots) {
            beforeBlock()
            block(it)
        }
    }

    private class FakeSettings : SettingsRepository {
        var value = AppSettings()
        override fun getSettings() = flowOf(value)
        override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) { value = transform(value) }
        override suspend fun updateRecommendationProfile(profile: RecommendationProfile) = Unit
        override suspend fun completeModelProfileOnboarding(profile: RecommendationProfile) = Unit
    }

    private class FakeRuntime : OnnxTextRuntime {
        override var supported = true
        override var engineVersion = "onnxruntime-resident-test"
        override var contextUsed = 0
        override var contextLimit = 0
        override var stopReason = OnnxStopReason.NONE
        override val runtimeDescription = "fake CPU"
        var loads = 0
        var unloads = 0
        var cancels = 0
        var afterLoad: suspend () -> Unit = {}
        var beforeGenerate: suspend () -> Unit = {}
        var failure: Exception? = null
        var terminalReason = OnnxStopReason.EOS
        override suspend fun load(modelPath: String, config: OnnxRuntimeConfig) {
            loads++
            contextLimit = config.contextTokens
            afterLoad()
        }
        override fun tokenize(messages: List<OnnxChatMessage>) = intArrayOf(1)
        override fun generate(messages: List<OnnxChatMessage>, maxNewTokens: Int) = flow {
            beforeGenerate()
            failure?.let { throw it }
            contextUsed = 2
            emit(OnnxToken(1, "answer"))
            stopReason = terminalReason
        }
        override fun cancel() { cancels++ }
        override suspend fun unload() { unloads++; contextLimit = 0 }
    }

    private class TestStorage(private val root: Path) : StoragePathProvider {
        override fun getModelsStorageDirectory(modelId: String) = root.toString()
        override fun getDatabasePath() = (root / "db").toString()
        override fun fileExists(path: String) = FileSystem.SYSTEM.exists(path.toPath())
        override fun getAvailableStorageBytes() = 20L * 1024 * 1024 * 1024
        override fun getTotalStorageBytes() = getAvailableStorageBytes()
        override fun isModelFileReadable(path: String) = FileSystem.SYSTEM.metadataOrNull(path.toPath())?.isRegularFile == true
        override fun isDirectoryReadable(path: String) = FileSystem.SYSTEM.metadataOrNull(path.toPath())?.isDirectory == true
        override fun getFileSize(path: String) = FileSystem.SYSTEM.metadataOrNull(path.toPath())?.size ?: 0L
        override fun renameFile(from: String, to: String) = false
        override fun deleteDownloadedModelContent(modelId: String, localPath: String) = false
        override fun inspectDownloadedArtifact(modelId: String, localPath: String): StoredArtifactSnapshot? {
            val path = localPath.toPath()
            if (path != path.normalized() || !localPath.startsWith("$root/")) return null
            val metadata = FileSystem.SYSTEM.metadataOrNull(path) ?: return null
            if (metadata.symlinkTarget != null || !metadata.isRegularFile) return null
            return StoredArtifactSnapshot(StoredArtifactKind.REGULAR_FILE, metadata.size ?: 0, "fixture")
        }
    }
}
