package com.debanshu777.caraml.core.data.inference

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.recommendation.KvCacheType
import com.debanshu777.caraml.core.recommendation.LlmRunPlan
import com.debanshu777.caraml.core.recommendation.LoadRequest
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.ObservationModelIdentity
import com.debanshu777.caraml.core.recommendation.OptimizationPriority
import com.debanshu777.caraml.core.recommendation.RepositoryCommit
import com.debanshu777.caraml.core.recommendation.ResolvedArtifactComponent
import com.debanshu777.caraml.core.recommendation.ResolvedLocalArtifact
import com.debanshu777.caraml.core.recommendation.RevisionIdentity
import com.debanshu777.caraml.core.recommendation.RiskTolerance
import com.debanshu777.caraml.core.recommendation.VerifiedArtifactLoadTarget
import com.debanshu777.caraml.core.recommendation.task6LlmDescriptor
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.settings.KvQuantPreset
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.sdk.plugin.llama.bridge.NativeRunnerConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResidentLlamaReuseTest {
    @Test
    fun exactReadySelectionReusesButUsageCountAndUiFlagsDoNotForceReload() {
        assertTrue(reuses(key()))
        assertTrue(reuses(key(request.copy(model = model.copy(usageCount = 42)))))
        assertTrue(reuses(key(settings = settings.copy(
            benchmarkMode = true,
            modelProfileOnboardingComplete = true,
            recommendationCalibrationOfferComplete = true,
        ))))
    }

    @Test
    fun runtimeSettingsChangesRequireTheNormalLoadPath() {
        val changes = listOf(
            settings.copy(useGpu = !settings.useGpu),
            settings.copy(kvQuantPreset = KvQuantPreset.Q8_Q8),
            settings.copy(temperature = 0.9f),
            settings.copy(systemPrompt = "A different instruction"),
            settings.copy(riskTolerance = RiskTolerance.EXPERIMENTAL),
            settings.copy(optimizationPriority = OptimizationPriority.SPEED_EFFICIENCY),
        )
        changes.forEach { assertFalse(reuses(key(settings = it))) }
    }

    @Test
    fun contextModelPathIdentityOrInstallationChangesRequireReload() {
        listOf(
            model.copy(contextLength = 2048),
            model.copy(localPath = "/private/replacement.gguf"),
            model.copy(id = 2),
            model.copy(modelId = "owner/replacement"),
            model.copy(filename = "replacement.gguf"),
            model.copy(sizeBytes = 8),
            model.copy(downloadedAt = 2),
            model.copy(componentStatus = LocalModelEntity.STATUS_PARTIAL),
            model.copy(arch = "other-architecture"),
        ).forEach { assertFalse(reuses(key(request.copy(model = it)))) }
    }

    @Test
    fun sameSizeArtifactGenerationOrLocationReplacementIsNotReusable() {
        val component = artifact.components.single()
        listOf(
            artifact.copy(components = listOf(component.copy(bundleId = "e".repeat(64)))),
            artifact.copy(components = listOf(component.copy(localRelativePath = "new-generation/model.gguf"))),
            artifact.copy(components = listOf(component.copy(storageRoot = "/other-root"))),
            artifact.copy(components = listOf(component.copy(contentSha256 = "e".repeat(64)))),
            artifact.copy(revisionIdentity = RevisionIdentity.HubCommit(listOf(RepositoryCommit("owner/model", "e".repeat(40))))),
        ).forEach { assertFalse(reuses(key(request.copy(artifact = it)))) }
    }

    @Test
    fun effectiveConfigBackendPlanOrEngineVersionChangesRequireReload() {
        listOf(
            config.copy(nCtx = 2048), config.copy(nThreads = 2), config.copy(nBatch = 128),
            config.copy(typeK = 8), config.copy(nGpuLayers = 12), config.copy(offloadKqv = true),
            config.copy(cpuMask = "0-3"),
        ).forEach { assertFalse(reuses(key(config = it))) }
        assertFalse(reuses(key(request.copy(plan = LlmRunPlan(
            contextTokens = 4096, batchSize = 256, microBatchSize = 128, sequenceCount = 1,
            keyCacheType = KvCacheType.F16, valueCacheType = KvCacheType.F16, backend = BackendKind.VULKAN,
            memoryTopology = MemoryTopology.UNIFIED, gpuLayerCount = 12, compromises = emptyList(),
        )))))
        assertFalse(reuses(key(engine = "different-build")))
    }

    @Test
    fun retryReloadUnreadyMissingSessionOrLostContextCannotReuse() {
        ResidentLlamaState.entries.filter { it != ResidentLlamaState.READY }.forEach {
            assertFalse(reuses(key(), state = it))
        }
        assertFalse(reuses(key(), intent = ResidentLlamaIntent.RETRY))
        assertFalse(reuses(key(), intent = ResidentLlamaIntent.RELOAD))
        assertFalse(reuses(null))
        assertFalse(canReuseResidentLlama(null, key(), ResidentLlamaState.READY, 4096, ResidentLlamaIntent.SELECT))
        assertFalse(reuses(key(), context = 0))
        assertFalse(reuses(key(), context = 2048))
    }

    @Test
    fun missingOrMismatchedArtifactNeverCreatesReusableIdentity() {
        assertNull(key(request.copy(artifact = null)))
        assertNull(key(request.copy(artifact = artifact.copy(components = emptyList()))))
        assertNull(key(request.copy(identity = ModelFileIdentity(
            repositoryId = model.modelId, revision = "e".repeat(40), path = model.filename,
            sizeBytes = 4, gitOid = null, lfsOid = "sha256:${"b".repeat(64)}", xetHash = null,
            evidence = emptyList(),
        ))))
        assertNull(key(settings = settings.copy(temperature = Float.NaN)))
        assertNull(key(config = config.copy(nCtx = 0)))
        assertNull(key(engine = ""))
    }

    private fun reuses(
        candidate: ResidentLlamaReuseKey?,
        state: ResidentLlamaState = ResidentLlamaState.READY,
        intent: ResidentLlamaIntent = ResidentLlamaIntent.SELECT,
        context: Int = 4096,
    ) = canReuseResidentLlama(key(), candidate, state, context, intent)

    private fun key(
        request: LoadRequest = this.request,
        settings: AppSettings = this.settings,
        config: NativeRunnerConfig = this.config,
        engine: String = "test-engine",
    ) = ResidentLlamaReuseKey.from(request, settings, config, engine)

    private val model = LocalModelEntity(
        id = 1, modelId = "owner/model", filename = "model.gguf", localPath = "/private/model.gguf",
        sizeBytes = 4, downloadedAt = 1, author = null, libraryName = null,
        pipelineTag = "text-generation", contextLength = 4096, arch = "llama",
        componentStatus = LocalModelEntity.STATUS_READY,
    )
    private val identity = ModelFileIdentity(
        repositoryId = model.modelId, revision = "a".repeat(40), path = model.filename,
        sizeBytes = 4, gitOid = null, lfsOid = "sha256:${"b".repeat(64)}", xetHash = null,
        evidence = emptyList(),
    )
    private val artifact = ResolvedLocalArtifact(
        identity = identity,
        revisionIdentity = RevisionIdentity.HubCommit(listOf(RepositoryCommit(model.modelId, identity.revision))),
        components = listOf(ResolvedArtifactComponent(
            logicalRole = "model", repositoryId = model.modelId, repositoryRelativePath = model.filename,
            localPath = model.localPath, byteCount = 4, contentSha256 = "b".repeat(64), identity = identity,
            bundleId = "c".repeat(64), remoteObjectId = "sha256:${"b".repeat(64)}", storageRoot = "/private",
        )),
        loadTarget = VerifiedArtifactLoadTarget.File(model.localPath, "model", model.modelId, model.filename),
    )
    private val plan = LlmRunPlan(
        contextTokens = 4096, batchSize = 256, microBatchSize = 128, sequenceCount = 1,
        keyCacheType = KvCacheType.F16, valueCacheType = KvCacheType.F16, backend = BackendKind.CPU,
        memoryTopology = MemoryTopology.UNKNOWN, gpuLayerCount = 0, compromises = emptyList(),
    )
    private val request = LoadRequest(
        model = model, identity = identity,
        observationIdentity = requireNotNull(ObservationModelIdentity.fromDescriptor(task6LlmDescriptor())),
        plan = plan, assessmentKey = "assessment", artifact = artifact,
    )
    private val settings = AppSettings(useGpu = false, kvQuantPreset = KvQuantPreset.F16_F16)
    private val config = NativeRunnerConfig(
        nCtx = 4096, nBatch = 256, nUbatch = 128, nGpuLayers = 0, offloadKqv = false, autoFit = false,
    )
}
