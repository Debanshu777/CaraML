package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmFootprintEstimatorTest {
    private val estimator = LlmFootprintEstimator()

    @Test
    fun kvCacheUsesArchitectureShapeAndExactQ8BlockBytes() {
        val descriptor = descriptor(shape = TransformerShape(32, 8, 32, 4_096, 128))
        val small = estimator.estimate(descriptor, plan(context = 2_048, cacheType = KvCacheType.Q8_0), MemoryCalibration.None)
        val large = estimator.estimate(descriptor, plan(context = 4_096, cacheType = KvCacheType.Q8_0), MemoryCalibration.None)

        assertEquals(142_606_336L, host(large).likelyBytes - host(small).likelyBytes)
        assertEquals(Confidence.MEDIUM, large.confidence.memory)
    }

    @Test
    fun kvTypesUseTheirExactRationalBytesPerElement() {
        val descriptor = descriptor(shape = TransformerShape(32, 8, 32, 4_096, 128))

        fun contextDelta(type: KvCacheType): Long {
            val small = estimator.estimate(descriptor, plan(context = 2_048, cacheType = type), MemoryCalibration.None)
            val large = estimator.estimate(descriptor, plan(context = 4_096, cacheType = type), MemoryCalibration.None)
            return host(large).likelyBytes - host(small).likelyBytes
        }

        assertEquals(268_435_456L, contextDelta(KvCacheType.F16))
        assertEquals(142_606_336L, contextDelta(KvCacheType.Q8_0))
        assertEquals(75_497_472L, contextDelta(KvCacheType.Q4_0))
    }

    @Test
    fun sequenceCountControlsPersistentKvMultiplicity() {
        val descriptor = descriptor(shape = TransformerShape(32, 8, 32, 4_096, 128))
        val one = estimator.estimate(descriptor, plan(context = 4_096, sequences = 1), MemoryCalibration.None)
        val two = estimator.estimate(descriptor, plan(context = 4_096, sequences = 2), MemoryCalibration.None)

        assertEquals(285_212_672L, host(two).likelyBytes - host(one).likelyBytes)
    }

    @Test
    fun batchDoesNotMultiplyPersistentKvMemory() {
        val descriptor = descriptor(shape = TransformerShape(32, 8, 32, 4_096, 128))

        fun contextDelta(batch: Int): Long {
            val small = estimator.estimate(descriptor, plan(context = 2_048, batch = batch), MemoryCalibration.None)
            val large = estimator.estimate(descriptor, plan(context = 4_096, batch = batch), MemoryCalibration.None)
            return host(large).likelyBytes - host(small).likelyBytes
        }

        assertEquals(contextDelta(128), contextDelta(512))
    }

    @Test
    fun reducingMicroBatchLowersGraphMemoryWithoutChangingPersistentKv() {
        val descriptor = descriptor(shape = TransformerShape(32, 8, 32, 4_096, 128))
        val largeMicroBatch = estimator.estimate(
            descriptor,
            plan(context = 4_096, batch = 512, microBatch = 256),
            MemoryCalibration.None,
        )
        val smallMicroBatch = estimator.estimate(
            descriptor,
            plan(context = 4_096, batch = 512, microBatch = 128),
            MemoryCalibration.None,
        )

        assertTrue(host(smallMicroBatch).lowBytes < host(largeMicroBatch).lowBytes)
        assertTrue(host(smallMicroBatch).likelyBytes < host(largeMicroBatch).likelyBytes)
        assertTrue(host(smallMicroBatch).highBytes < host(largeMicroBatch).highBytes)
        assertEquals(largeMicroBatch.storageBytes, smallMicroBatch.storageBytes)

        fun contextDelta(microBatch: Int): Long {
            val small = estimator.estimate(
                descriptor,
                plan(context = 2_048, batch = 512, microBatch = microBatch),
                MemoryCalibration.None,
            )
            val large = estimator.estimate(
                descriptor,
                plan(context = 4_096, batch = 512, microBatch = microBatch),
                MemoryCalibration.None,
            )
            return host(large).likelyBytes - host(small).likelyBytes
        }

        assertEquals(contextDelta(256), contextDelta(128))
    }

    @Test
    fun missingShapeWidensOnlyRuntimeMemoryAndKeepsExactStorage() {
        val descriptor = descriptor(sizeBytes = 2L * GIB, shape = null)
        val estimate = estimator.estimate(descriptor, plan(), MemoryCalibration.None)

        val host = host(estimate)
        val storage = assertNotNull(estimate.storageBytes)
        assertTrue(host.lowBytes >= 2L * GIB)
        assertTrue(host.highBytes > host.likelyBytes)
        assertEquals(2L * GIB, storage.lowBytes)
        assertEquals(storage.lowBytes, storage.highBytes)
        assertEquals(Confidence.LOW, estimate.confidence.memory)
        assertTrue(estimate.evidence.any { it.reason == AssessmentReason.MISSING_MODEL_SHAPE })
    }

    @Test
    fun hybridArchitectureAddsAConservativeRecurrentStateInterval() {
        val shape = TransformerShape(32, 8, 32, 4_096, 128)
        val dense = estimator.estimate(descriptor(architecture = "llama", shape = shape), plan(), MemoryCalibration.None)
        val hybrid = estimator.estimate(descriptor(architecture = "mamba-hybrid", shape = shape), plan(), MemoryCalibration.None)

        assertTrue(host(hybrid).highBytes > host(dense).highBytes)
        assertTrue(hybrid.evidence.any { it.reason == AssessmentReason.RECURRENT_STATE_ESTIMATED })
    }

    @Test
    fun cpuUnifiedAndDiscretePlansKeepMemoryPoolsSeparate() {
        val descriptor = descriptor(shape = TransformerShape(32, 8, 32, 4_096, 128))
        val cpu = estimator.estimate(descriptor, plan(backend = BackendKind.CPU), MemoryCalibration.None)
        val unified = estimator.estimate(
            descriptor,
            plan(backend = BackendKind.METAL, topology = MemoryTopology.UNIFIED, gpuLayers = 32),
            MemoryCalibration.None,
        )
        val discrete = estimator.estimate(
            descriptor,
            plan(backend = BackendKind.CUDA, topology = MemoryTopology.DISCRETE, gpuLayers = 16),
            MemoryCalibration.None,
        )

        assertNotNull(cpu.hostMemoryBytes)
        assertNull(cpu.gpuMemoryBytes)
        assertNull(cpu.sharedMemoryBytes)
        assertNull(unified.hostMemoryBytes)
        assertNull(unified.gpuMemoryBytes)
        assertNotNull(unified.sharedMemoryBytes)
        assertNotNull(discrete.hostMemoryBytes)
        assertNotNull(discrete.gpuMemoryBytes)
        assertNull(discrete.sharedMemoryBytes)
    }

    @Test
    fun unknownDiscreteLayerSplitStaysLowConfidenceWithIndependentUpperBounds() {
        val descriptor = descriptor(sizeBytes = 2L * GIB, shape = TransformerShape(32, 8, 32, 4_096, 128))
        val estimate = estimator.estimate(
            descriptor,
            plan(backend = BackendKind.CUDA, topology = MemoryTopology.DISCRETE, gpuLayers = null),
            MemoryCalibration.None,
        )

        assertTrue(assertNotNull(estimate.hostMemoryBytes).highBytes >= 2L * GIB)
        assertTrue(assertNotNull(estimate.gpuMemoryBytes).highBytes >= 2L * GIB)
        assertEquals(Confidence.LOW, estimate.confidence.memory)
        assertTrue(estimate.evidence.any { it.reason == AssessmentReason.GPU_LAYER_SPLIT_UNKNOWN })
    }

    @Test
    fun acceleratorZeroIsRejectedWhileCpuZeroRemainsHostOnly() {
        val descriptor = descriptor(shape = TransformerShape(32, 8, 32, 4_096, 128))
        val invalidAccelerator = estimator.estimate(
            descriptor,
            plan(backend = BackendKind.CUDA, topology = MemoryTopology.DISCRETE, gpuLayers = 0),
            MemoryCalibration.None,
        )
        val cpu = estimator.estimate(descriptor, plan(backend = BackendKind.CPU, gpuLayers = 0), MemoryCalibration.None)

        assertNull(invalidAccelerator.hostMemoryBytes)
        assertNull(invalidAccelerator.gpuMemoryBytes)
        assertTrue(invalidAccelerator.evidence.any { it.reason == AssessmentReason.INVALID_WORKLOAD })
        assertNotNull(cpu.hostMemoryBytes)
        assertNull(cpu.gpuMemoryBytes)
    }

    @Test
    fun partialAndFullDiscreteEndpointsRemainIndependent() {
        val descriptor = descriptor(shape = TransformerShape(32, 8, 32, 4_096, 128))
        val partial = estimator.estimate(
            descriptor,
            plan(backend = BackendKind.CUDA, topology = MemoryTopology.DISCRETE, gpuLayers = 16),
            MemoryCalibration.None,
        )
        val full = estimator.estimate(
            descriptor,
            plan(backend = BackendKind.CUDA, topology = MemoryTopology.DISCRETE, gpuLayers = 32),
            MemoryCalibration.None,
        )

        assertTrue(assertNotNull(full.hostMemoryBytes).highBytes < assertNotNull(partial.hostMemoryBytes).highBytes)
        assertTrue(assertNotNull(full.gpuMemoryBytes).lowBytes > assertNotNull(partial.gpuMemoryBytes).lowBytes)
        assertNull(partial.sharedMemoryBytes)
        assertNull(full.sharedMemoryBytes)
    }

    @Test
    fun autoDiscretePlacementWidensEveryPotentiallyAffectedPool() {
        val descriptor = descriptor(sizeBytes = 2L * GIB, shape = TransformerShape(32, 8, 32, 4_096, 128))
        val cpu = estimator.estimate(descriptor, plan(backend = BackendKind.CPU), MemoryCalibration.None)
        val auto = estimator.estimate(
            descriptor,
            plan(backend = BackendKind.CUDA, topology = MemoryTopology.DISCRETE, gpuLayers = null),
            MemoryCalibration.None,
        )

        assertTrue(assertNotNull(auto.hostMemoryBytes).highBytes >= host(cpu).highBytes)
        assertTrue(assertNotNull(auto.gpuMemoryBytes).highBytes >= host(cpu).highBytes)
        assertEquals(Confidence.LOW, auto.confidence.memory)
        assertTrue(auto.evidence.any { it.reason == AssessmentReason.GPU_LAYER_SPLIT_UNKNOWN })
    }

    @Test
    fun overflowProducesStructuredUnknownMemoryInsteadOfWrappingOrThrowing() {
        val estimate = estimator.estimate(
            descriptor(
                sizeBytes = DescriptorLimits.MAX_FILE_BYTES,
                contextLimit = DescriptorLimits.MAX_CONTEXT_TOKENS,
                shape = TransformerShape(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE),
            ),
            plan(context = DescriptorLimits.MAX_CONTEXT_TOKENS, sequences = WorkloadLimits.MAX_SEQUENCE_COUNT),
            MemoryCalibration.None,
        )

        assertNull(estimate.hostMemoryBytes)
        assertEquals(Confidence.LOW, estimate.confidence.memory)
        assertTrue(estimate.evidence.any { it.reason == AssessmentReason.ARITHMETIC_OVERFLOW })
        assertTrue(estimate.evidence.none { it.detail?.contains("-") == true })
    }

    @Test
    fun malformedPlanAndCalibrationReturnStructuredConservativeResults() {
        val invalidPlan = estimator.estimate(descriptor(), plan(context = 0), MemoryCalibration.None)
        val invalidCalibration = estimator.estimate(
            descriptor(),
            plan(),
            MemoryCalibration.Correction(
                likelyNumerator = 0,
                likelyDenominator = 1,
                highNumerator = 1,
                highDenominator = 1,
            ),
        )

        assertNull(invalidPlan.hostMemoryBytes)
        assertTrue(invalidPlan.evidence.any { it.reason == AssessmentReason.INVALID_WORKLOAD })
        assertNull(invalidCalibration.hostMemoryBytes)
        assertTrue(invalidCalibration.evidence.any { it.reason == AssessmentReason.INVALID_ESTIMATE_RANGE })
    }

    @Test
    fun largerContextNeverReducesAnyEstimatedHostBound() {
        val descriptor = descriptor(shape = TransformerShape(32, 8, 32, 4_096, 128))
        var previous: EstimateRange? = null

        for (context in listOf(512, 1_024, 2_048, 4_096, 8_192)) {
            val current = host(estimator.estimate(descriptor, plan(context = context), MemoryCalibration.None))
            previous?.let {
                assertTrue(current.lowBytes >= it.lowBytes)
                assertTrue(current.likelyBytes >= it.likelyBytes)
                assertTrue(current.highBytes >= it.highBytes)
            }
            previous = current
        }
    }

    @Test
    fun largerWeightFileNeverReducesAnyEstimatedHostBound() {
        var previous: EstimateRange? = null

        for (size in listOf(256L, 512L, 1_024L, 2_048L).map { it * MIB }) {
            val current = host(estimator.estimate(descriptor(sizeBytes = size), plan(), MemoryCalibration.None))
            previous?.let {
                assertTrue(current.lowBytes >= it.lowBytes)
                assertTrue(current.likelyBytes >= it.likelyBytes)
                assertTrue(current.highBytes >= it.highBytes)
            }
            previous = current
        }
    }

    private fun host(assessment: PlanAssessment): EstimateRange = assertNotNull(assessment.hostMemoryBytes)

    private fun plan(
        context: Int = 4_096,
        batch: Int = 128,
        microBatch: Int = batch.coerceAtMost(128),
        sequences: Int = 1,
        cacheType: KvCacheType = KvCacheType.Q8_0,
        backend: BackendKind = BackendKind.CPU,
        topology: MemoryTopology = MemoryTopology.UNKNOWN,
        gpuLayers: Int? = if (backend == BackendKind.CPU) 0 else null,
    ) = LlmRunPlan(
        contextTokens = context,
        batchSize = batch,
        microBatchSize = microBatch,
        sequenceCount = sequences,
        keyCacheType = cacheType,
        valueCacheType = cacheType,
        backend = backend,
        memoryTopology = topology,
        gpuLayerCount = gpuLayers,
        compromises = emptyList(),
    )

    private fun descriptor(
        sizeBytes: Long = 1L * GIB,
        contextLimit: Int = 131_072,
        architecture: String? = "llama",
        shape: TransformerShape? = TransformerShape(32, 8, 32, 4_096, 128),
    ) = llmDescriptor(sizeBytes = sizeBytes, contextLimit = contextLimit, architecture = architecture, shape = shape)

    private companion object {
        const val MIB = 1_048_576L
        const val GIB = 1_073_741_824L
    }
}

internal fun llmDescriptor(
    sizeBytes: Long,
    contextLimit: Int?,
    architecture: String? = "llama",
    shape: TransformerShape?,
): LlmModelDescriptor {
    val identity = ModelFileIdentity(
        repositoryId = "owner/model",
        revision = "0123456789abcdef0123456789abcdef01234567",
        path = "model-Q4_K_M.gguf",
        sizeBytes = sizeBytes,
        gitOid = null,
        lfsOid = "sha256:fixture",
        xetHash = null,
        evidence = emptyList(),
    )
    return LlmModelDescriptor(
        repositoryId = identity.repositoryId,
        revision = identity.revision,
        file = identity,
        architecture = architecture,
        quantization = QuantizationEvidence.Known("Q4_K_M"),
        parameterCount = 7_000_000_000L,
        contextLimit = contextLimit,
        transformerShape = shape,
        ggufVersion = 3,
        requiredEngineFeatures = emptyList(),
        evidence = emptyList(),
    )
}
