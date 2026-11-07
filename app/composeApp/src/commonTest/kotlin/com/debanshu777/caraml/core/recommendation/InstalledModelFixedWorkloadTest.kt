package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import com.debanshu777.caraml.sdk.plugin.llama.bridge.NativeRunnerConfig
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher

class InstalledModelFixedWorkloadTest {
    @Test
    fun exactPlacementPassesThroughRealAssessmentAndNativeAdapter() {
        val descriptor = fixedPlacementDescriptor()
        val workload = assertIs<LlmWorkloadConfig>(InstalledModelWorkloadFactory(fixedInstalledWorkload(), true)
            .create(descriptor, GenerationMode.Text, AppSettings()))
        assertEquals(33, workload.requestedFullVulkanLayerCount)
        val engine = SuitabilityEngine(CompatibilityChecker { SupportEvidence.Supported }, NoCalibrationSource)
        for (backend in listOf(BackendKind.CPU, BackendKind.VULKAN)) {
            val assessed = engine.assessPlans(descriptor,
                task6Hardware(backend = backend, topology = MemoryTopology.UNIFIED), workload)
            val assessment = assessed.values.single()
            val plan = assertIs<LlmRunPlan>(assessment.plan)
            val config = NativeRunPlanAdapter.toLlamaConfig(plan, NativeRunnerConfig())
            assertEquals(if (backend == BackendKind.CPU) 0 else 33, plan.gpuLayerCount)
            assertEquals(plan.gpuLayerCount, config.nGpuLayers)
            assertFalse(config.autoFit)
            assertEquals(backend == BackendKind.VULKAN, config.offloadKqv)
            assertEquals(4_096, config.nCtx)
            assertEquals(256, config.nBatch)
            assertEquals(64, config.nUbatch)
            assertEquals(1, config.typeK)
            assertEquals(1, config.typeV)
            assertTrue(assessment.evidence.none { it.reason == AssessmentReason.INVALID_WORKLOAD })
            assertNotNull(if (backend == BackendKind.CPU) assessment.hostMemoryBytes else assessment.sharedMemoryBytes)
        }
    }

    @Test
    fun qwen2ThirtySixBlocksUseThirtySevenExplicitNativeLayers() {
        val descriptor = fixedPlacementDescriptor(architecture = "qwen2", shape = TransformerShape(36, 2, 16, 2_048, 128))
        val adaptive = assertIs<LlmWorkloadConfig>(InstalledModelWorkloadFactory(fixedInstalledWorkload())
            .create(descriptor, GenerationMode.Text, AppSettings()))
        assertNull(adaptive.requestedFullVulkanLayerCount)
        val exact = assertIs<LlmWorkloadConfig>(InstalledModelWorkloadFactory(fixedInstalledWorkload(), true)
            .create(descriptor, GenerationMode.Text, AppSettings()))
        assertEquals(37, exact.requestedFullVulkanLayerCount)
        val engine = SuitabilityEngine(CompatibilityChecker { SupportEvidence.Supported }, NoCalibrationSource)
        val plan = assertIs<LlmRunPlan>(engine.assessPlans(descriptor,
            task6Hardware(backend = BackendKind.VULKAN, topology = MemoryTopology.UNIFIED), exact).values.single().plan)
        val native = NativeRunPlanAdapter.toLlamaConfig(plan, NativeRunnerConfig())
        assertEquals(37, native.nGpuLayers)
        assertFalse(native.autoFit)
    }

    @Test
    fun repositoryRetainsPlacementRequestWithoutChangingDescriptorNormalization() = runTest {
        val engine = SuitabilityEngine(CompatibilityChecker { SupportEvidence.Supported }, NoCalibrationSource)
        val repository = ModelAssessmentRepository(engine, RecommendationPolicy(),
            assessmentDispatcher = StandardTestDispatcher(testScheduler))
        val snapshot = task6Snapshot(topology = MemoryTopology.UNIFIED,
            backends = listOf(task6Backend(BackendKind.VULKAN)), sharedBudget = 16_000_000_000L)
        val descriptor = fixedPlacementDescriptor()
        val adaptive = fixedInstalledWorkload()
        val exact = assertIs<LlmWorkloadConfig>(InstalledModelWorkloadFactory(adaptive, true)
            .create(descriptor, GenerationMode.Text, AppSettings()))
        val adaptiveKey = assertNotNull(assessmentCacheKey(descriptor, adaptive, snapshot.hardwareProfile, "fixture-1", 1, 0))
        val exactKey = assertNotNull(assessmentCacheKey(descriptor, exact, snapshot.hardwareProfile, "fixture-1", 1, 0))
        assertNotEquals(adaptiveKey, exactKey)
        assertEquals(adaptiveKey.descriptor, exactKey.descriptor)
        assertTrue(assertNotNull(exactKey.descriptor).evidence.isEmpty())
        assertEquals(33, assertIs<LlmWorkloadConfig>(exactKey.workload).requestedFullVulkanLayerCount)
        for ((workload, expectedLayers) in listOf(adaptive to null, exact to 33, adaptive to null, exact to 33)) {
            val assessment = repository.assess(descriptor, snapshot, workload)
            val plan = assertIs<LlmRunPlan>(assessment.planAssessments.values.single().plan)
            assertEquals(expectedLayers, plan.gpuLayerCount)
            assertEquals(expectedLayers == null, NativeRunPlanAdapter.toLlamaConfig(plan, NativeRunnerConfig()).autoFit)
        }
    }

    @Test
    fun factoryRejectsMissingUntrustedUnsupportedAndMismatchedPlacement() {
        val factory = InstalledModelWorkloadFactory(fixedInstalledWorkload(), true)
        for (descriptor in listOf(fixedPlacementDescriptor(shape = null),
            fixedPlacementDescriptor(evidence = emptyList()),
            fixedPlacementDescriptor(evidence = listOf(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.LOW, "shape:local-gguf-header"))),
            fixedPlacementDescriptor(architecture = "mamba"), fixedPlacementDescriptor(architecture = "nanbeige"))) {
            assertNull(factory.create(descriptor, GenerationMode.Text, AppSettings()))
        }
        assertNull(InstalledModelWorkloadFactory(fixedInstalledWorkload(requestedLayers = 34), true)
            .create(fixedPlacementDescriptor(), GenerationMode.Text, AppSettings()))
    }

    @Test
    fun engineRejectsUnsupportedBackendAndCountDrift() {
        val engine = SuitabilityEngine(CompatibilityChecker { SupportEvidence.Supported }, NoCalibrationSource)
        val descriptor = fixedPlacementDescriptor()
        val workload = fixedInstalledWorkload(requestedLayers = 33)
        for ((backend, topology) in listOf(BackendKind.VULKAN to MemoryTopology.DISCRETE,
            BackendKind.CUDA to MemoryTopology.DISCRETE, BackendKind.METAL to MemoryTopology.UNIFIED)) {
            assertTrue(engine.assessPlans(descriptor, task6Hardware(backend = backend, topology = topology), workload).values.isEmpty())
        }
        val hardware = task6Hardware(backend = BackendKind.VULKAN, topology = MemoryTopology.UNIFIED)
        assertTrue(engine.assessPlans(descriptor, hardware, fixedInstalledWorkload(requestedLayers = 34)).values.isEmpty())
        assertTrue(engine.assessPlans(fixedPlacementDescriptor(shape = null), hardware, workload).values.isEmpty())
        val unsupported = SuitabilityEngine(CompatibilityChecker {
            SupportEvidence.Unsupported(listOf(AssessmentReason.UNSUPPORTED_ARCHITECTURE))
        }, NoCalibrationSource)
        assertTrue(unsupported.assessPlans(descriptor, hardware, workload).values.isEmpty())
    }

    @Test
    fun exactPlacementCannotReuseCachedAdaptiveAssessment() {
        val engine = SuitabilityEngine(CompatibilityChecker { SupportEvidence.Supported }, NoCalibrationSource)
        val descriptor = fixedPlacementDescriptor()
        val hardware = task6Hardware(backend = BackendKind.VULKAN, topology = MemoryTopology.UNIFIED)
        val adaptive = engine.assessPlans(descriptor, hardware, fixedInstalledWorkload())
        assertTrue(engine.reassessPlans(descriptor, hardware, fixedInstalledWorkload(requestedLayers = 33), adaptive).values.isEmpty())
    }

    @Test
    fun fixedRequestProducesOnlyExactCpuAndVulkanCandidatesBeforeAdmission() {
        val factory = InstalledModelWorkloadFactory(fixedInstalledWorkload())
        val descriptor = task6LlmDescriptor()
        val workload = assertIs<LlmWorkloadConfig>(factory.create(descriptor, GenerationMode.Text, AppSettings()))
        for (backend in listOf(BackendKind.CPU, BackendKind.VULKAN)) {
            val candidates = RunPlanGenerator().llmCandidates(descriptor, workload, PlanningSettings(
                engineMaxContextTokens = 16_384, engineMaxBatchSize = 512, engineMaxMicroBatchSize = 512,
                engineMaxSequenceCount = 1, allowContextFallback = true, allowBatchFallback = true,
                allowKvCacheFallback = true, allowedKvCacheTypes = KvCacheType.entries,
                backend = backend,
                memoryTopology = if (backend == BackendKind.CPU) MemoryTopology.UNKNOWN else MemoryTopology.UNIFIED,
                gpuLayerCount = if (backend == BackendKind.CPU) 0 else 16,
            ))
            assertEquals(1, candidates.size)
            val plan = candidates.single()
            assertEquals(4_096, plan.contextTokens)
            assertEquals(256, plan.batchSize)
            assertEquals(64, plan.microBatchSize)
            assertEquals(KvCacheType.F16, plan.keyCacheType)
            assertEquals(KvCacheType.F16, plan.valueCacheType)
            assertTrue(plan.compromises.isEmpty())
        }
    }

    @Test
    fun fixedRequestCannotSilentlyShrinkToTheDescriptorLimit() {
        assertNull(InstalledModelWorkloadFactory(fixedInstalledWorkload(32_768))
            .create(task6LlmDescriptor(), GenerationMode.Text, AppSettings()))
    }

    @Test
    fun productionFactoryAndBrowsingRemainAdaptive() {
        val normal = assertIs<LlmWorkloadConfig>(InstalledModelWorkloadFactory()
            .create(task6LlmDescriptor(), GenerationMode.Text, AppSettings()))
        assertTrue(normal.allowContextFallback)
        assertTrue(normal.allowBatchFallback)
        val fixedFactory = InstalledModelWorkloadFactory(fixedInstalledWorkload())
        val browse = assertIs<LlmWorkloadConfig>(fixedFactory.createForBrowse(ModelHubBrowseMode.LanguageModels))
        assertTrue(browse.allowContextFallback)
        assertTrue(browse.allowBatchFallback)
        assertFalse(assertIs<LlmWorkloadConfig>(fixedFactory
            .create(task6LlmDescriptor(), GenerationMode.Text, AppSettings())).allowBatchFallback)
    }
}

internal fun fixedInstalledWorkload(context: Int = 4_096, requestedLayers: Int? = null) = LlmWorkloadConfig(
    userRequestedContextTokens = context, contextTokens = context, minimumContextTokens = context,
    promptTokens = 256, generationReserveTokens = 256, batchSize = 256, microBatchSize = 64,
    sequenceCount = 1, kvCacheSelection = KvCacheSelection.Explicit(KvCacheType.F16, KvCacheType.F16),
    allowContextFallback = false, allowBatchFallback = false, allowKvCacheFallback = false,
    allowedKvCacheTypes = listOf(KvCacheType.F16), evidence = emptyList(), requestedFullVulkanLayerCount = requestedLayers,
)

private fun fixedPlacementDescriptor(
    shape: TransformerShape? = TransformerShape(32, 8, 32, 4_096, 128),
    architecture: String = "llama",
    evidence: Collection<Evidence> = listOf(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "shape:local-gguf-header")),
): LlmModelDescriptor {
    val base = task6LlmDescriptor()
    return LlmModelDescriptor(repositoryId = base.repositoryId, revision = base.revision, file = base.file,
        architecture = architecture, quantization = base.quantization, parameterCount = base.parameterCount,
        contextLimit = base.contextLimit, transformerShape = shape, ggufVersion = base.ggufVersion,
        requiredEngineFeatures = base.requiredEngineFeatures, evidence = evidence)
}
