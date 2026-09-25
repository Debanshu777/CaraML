package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class InferenceObservationPlanTest {
    @Test
    fun materialRunConfigurationChangesProduceDifferentBoundedFingerprints() {
        val identity = requireNotNull(ObservationModelIdentity.fromDescriptor(task6LlmDescriptor()))
        val llmPlans = listOf(
            llmFingerprintPlan(),
            llmFingerprintPlan(gpuLayers = 17),
            llmFingerprintPlan(keyCache = KvCacheType.Q4_0),
            llmFingerprintPlan(valueCache = KvCacheType.F16),
            llmFingerprintPlan(batch = 64),
            llmFingerprintPlan(microBatch = 64),
            llmFingerprintPlan(sequences = 2),
            llmFingerprintPlan(useMmap = false),
        )
        val llmBuckets = llmPlans.map {
            identity.calibrationKey(it, "native-engine-v1").workloadBucket
        }

        val diffusionPlans = listOf(
            diffusionFingerprintPlan(),
            diffusionFingerprintPlan(frames = 16),
            diffusionFingerprintPlan(vaeTiling = true),
            diffusionFingerprintPlan(offload = true),
            diffusionFingerprintPlan(keepClip = true),
            diffusionFingerprintPlan(keepVae = true),
            diffusionFingerprintPlan(maxVramBytes = 2_147_483_648L),
            diffusionFingerprintPlan(offload = true, streaming = true),
        )
        val diffusionBuckets = diffusionPlans.map {
            identity.calibrationKey(it, "native-engine-v1").workloadBucket
        }

        assertEquals(llmPlans.size, llmBuckets.toSet().size)
        assertEquals(diffusionPlans.size, diffusionBuckets.toSet().size)
        assertEquals(true, (llmBuckets + diffusionBuckets).all { it.length in 1..128 })
    }

    @Test
    fun loadAndGenerationMemoryEvidenceUseDifferentVersionedKeys() {
        val descriptor = task6LlmDescriptor()
        val plan = task6LlmPlan()
        val memory = task6Range(20, 30, 40)
        val baseAssessment = task6PlanAssessment(
            plan = plan,
        )
        val assessment = PlanAssessment(
            plan = plan,
            hostMemoryBytes = baseAssessment.hostMemoryBytes,
            gpuMemoryBytes = baseAssessment.gpuMemoryBytes,
            sharedMemoryBytes = baseAssessment.sharedMemoryBytes,
            storageBytes = baseAssessment.storageBytes,
            confidence = baseAssessment.confidence,
            evidence = baseAssessment.evidence,
            performance = baseAssessment.performance,
            utilityMetrics = baseAssessment.utilityMetrics,
            rawMemoryByPhase = MemoryPhaseEstimates(
                load = MemoryPoolEstimates(hostMemoryBytes = memory),
                generation = MemoryPoolEstimates(hostMemoryBytes = memory),
            ),
        )
        val request = LoadRequest(
            model = localModel(arch = null, filename = "model.gguf"),
            identity = descriptor.file,
            plan = plan,
            assessmentKey = "assessment",
            assessedPlans = AssessedPlans(values = listOf(assessment), assessmentKey = "assessment"),
            observationIdentity = requireNotNull(ObservationModelIdentity.fromDescriptor(descriptor)),
        )

        val load = assertNotNull(
            request.toInferenceObservationPlan("native-engine-v1", InferenceObservationPhase.LOAD),
        )
        val generation = assertNotNull(
            request.toInferenceObservationPlan("native-engine-v1", InferenceObservationPhase.GENERATION),
        )

        assertNotEquals(load.key.workloadBucket, generation.key.workloadBucket)
    }

    @Test
    fun llmIdentityComesFromValidatedDescriptorNotMutableRoomMetadata() {
        val descriptor = task6LlmDescriptor()
        val plan = task6LlmPlan()
        val assessment = task6PlanAssessment(
            plan = plan,
            performance = task6LlmPerformance(8.0, Confidence.HIGH),
        )
        val request = LoadRequest(
            model = localModel(arch = null, filename = "downloaded-without-quantization.bin"),
            identity = descriptor.file,
            plan = plan,
            assessmentKey = "assessment",
            assessedPlans = AssessedPlans(values = listOf(assessment), assessmentKey = "assessment"),
            observationIdentity = requireNotNull(ObservationModelIdentity.fromDescriptor(descriptor)),
        )

        val observation = assertNotNull(
            request.toInferenceObservationPlan("native-engine-v1", InferenceObservationPhase.GENERATION),
        )

        assertEquals("llama", observation.key.architectureFamily)
        assertEquals("Q4_K_M", observation.key.quantizationFamily)
    }

    @Test
    fun diffusionBundleIdentityDoesNotDependOnPrimaryFilename() {
        val descriptor = task6DiffusionDescriptor()
        val plan = task6DiffusionPlan()
        val assessment = PlanAssessment(
            plan = plan,
            hostMemoryBytes = task6Range(100, 100, 100),
            gpuMemoryBytes = null,
            sharedMemoryBytes = null,
            storageBytes = task6Range(100, 100, 100),
            confidence = task6Confidence(),
            evidence = emptyList(),
            rawMemoryByPhase = MemoryPhaseEstimates(
                load = MemoryPoolEstimates(hostMemoryBytes = task6Range(20, 30, 40)),
            ),
        )
        val request = LoadRequest(
            model = localModel(arch = null, filename = "model.safetensors"),
            identity = descriptor.components.first().file,
            plan = plan,
            assessmentKey = "assessment",
            assessedPlans = AssessedPlans(values = listOf(assessment), assessmentKey = "assessment"),
            observationIdentity = requireNotNull(ObservationModelIdentity.fromDescriptor(descriptor)),
        )

        val observation = assertNotNull(
            request.toInferenceObservationPlan("native-engine-v1", InferenceObservationPhase.LOAD),
        )

        assertEquals("SDXL", observation.key.architectureFamily)
        assertEquals("F16", observation.key.quantizationFamily)
        assertEquals(30.0, observation.prediction.hostMemoryBytes)
    }

    private fun localModel(arch: String?, filename: String) = LocalModelEntity(
        modelId = "owner/model",
        filename = filename,
        localPath = "/private/model",
        sizeBytes = 1_073_741_824L,
        downloadedAt = 1L,
        author = null,
        libraryName = null,
        pipelineTag = null,
        arch = arch,
    )

    private fun llmFingerprintPlan(
        gpuLayers: Int = 16,
        keyCache: KvCacheType = KvCacheType.Q8_0,
        valueCache: KvCacheType = KvCacheType.Q8_0,
        batch: Int = 128,
        microBatch: Int = 128,
        sequences: Int = 1,
        useMmap: Boolean = true,
    ) = LlmRunPlan(
        contextTokens = 4_096,
        batchSize = batch,
        microBatchSize = microBatch,
        sequenceCount = sequences,
        keyCacheType = keyCache,
        valueCacheType = valueCache,
        backend = BackendKind.METAL,
        memoryTopology = MemoryTopology.UNIFIED,
        gpuLayerCount = gpuLayers,
        useMmap = useMmap,
        compromises = emptyList(),
    )

    private fun diffusionFingerprintPlan(
        frames: Int = 8,
        vaeTiling: Boolean = false,
        offload: Boolean = false,
        keepClip: Boolean = false,
        keepVae: Boolean = false,
        maxVramBytes: Long? = null,
        streaming: Boolean = false,
    ) = DiffusionRunPlan(
        mode = DiffusionMode.VIDEO,
        width = 512,
        height = 512,
        frameCount = frames,
        batchSize = 1,
        steps = 20,
        vaeTiling = vaeTiling,
        offloadToCpu = offload,
        keepClipOnCpu = keepClip,
        keepVaeOnCpu = keepVae,
        maxVramBytes = maxVramBytes,
        segmentedCompute = streaming,
        requiresUserAcceptance = false,
        backend = BackendKind.METAL,
        memoryTopology = MemoryTopology.UNIFIED,
        compromises = emptyList(),
    )
}
