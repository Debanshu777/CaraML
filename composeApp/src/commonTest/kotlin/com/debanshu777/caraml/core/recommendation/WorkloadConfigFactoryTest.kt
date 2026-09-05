package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkloadConfigFactoryTest {
    private val factory = WorkloadConfigFactory()

    @Test
    fun rejectsNonPositiveWorkloadValuesBeforePlanning() {
        val invalidRequests = listOf(
            request().copy(contextTokens = 0),
            request().copy(minimumContextTokens = 0),
            request().copy(promptTokens = 0),
            request().copy(generationReserveTokens = 0),
            request().copy(batchSize = 0),
            request().copy(microBatchSize = 0),
            request().copy(sequenceCount = 0),
        )

        invalidRequests.forEach { invalid ->
            val result = assertIs<WorkloadConfigResult.Invalid>(
                factory.llm(invalid, descriptor(), settings()),
            )
            assertTrue(AssessmentReason.INVALID_WORKLOAD in result.reasons)
        }
    }

    @Test
    fun rejectsValuesBeyondAbsoluteProductLimitsWithoutThrowing() {
        val invalidRequests = listOf(
            request().copy(contextTokens = DescriptorLimits.MAX_CONTEXT_TOKENS + 1),
            request().copy(minimumContextTokens = DescriptorLimits.MAX_CONTEXT_TOKENS + 1),
            request().copy(promptTokens = DescriptorLimits.MAX_CONTEXT_TOKENS + 1),
            request().copy(generationReserveTokens = DescriptorLimits.MAX_CONTEXT_TOKENS + 1),
            request().copy(batchSize = WorkloadLimits.MAX_BATCH_SIZE + 1),
            request().copy(microBatchSize = WorkloadLimits.MAX_BATCH_SIZE + 1),
            request().copy(sequenceCount = WorkloadLimits.MAX_SEQUENCE_COUNT + 1),
        )

        invalidRequests.forEach { invalid ->
            assertIs<WorkloadConfigResult.Invalid>(factory.llm(invalid, descriptor(), settings()))
        }
    }

    @Test
    fun clampsToModelAndEngineLimitsAndRecordsEveryClamp() {
        val result = assertIs<WorkloadConfigResult.Ready>(
            factory.llm(
                request(
                    contextTokens = 12_000,
                    batchSize = 1_024,
                    microBatchSize = 768,
                    sequenceCount = 8,
                ),
                descriptor(contextLimit = 8_192),
                settings(
                    engineMaxContextTokens = 4_096,
                    engineMaxBatchSize = 512,
                    engineMaxMicroBatchSize = 256,
                    engineMaxSequenceCount = 4,
                ),
            ),
        )
        val workload = assertIs<LlmWorkloadConfig>(result.workload)

        assertEquals(12_000, workload.userRequestedContextTokens)
        assertEquals(4_096, workload.contextTokens)
        assertEquals(512, workload.batchSize)
        assertEquals(256, workload.microBatchSize)
        assertEquals(4, workload.sequenceCount)
        assertEquals(4, result.evidence.count { it.reason == AssessmentReason.WORKLOAD_CLAMPED })
        assertTrue(result.evidence.all { it.detail != null })
    }

    @Test
    fun rejectsSettingsAndReservesThatCannotProduceAValidRequestedPlan() {
        assertIs<WorkloadConfigResult.Invalid>(
            factory.llm(
                request(contextTokens = 1_024, promptTokens = 768, generationReserveTokens = 512),
                descriptor(),
                settings(),
            ),
        )
        assertIs<WorkloadConfigResult.Invalid>(
            factory.llm(request(), descriptor(), settings(engineMaxContextTokens = 0)),
        )
        assertIs<WorkloadConfigResult.Invalid>(
            factory.llm(
                request(kvCacheSelection = KvCacheSelection.Explicit(KvCacheType.Q4_0, KvCacheType.Q4_0)),
                descriptor(),
                settings(allowedKvCacheTypes = listOf(KvCacheType.F16)),
            ),
        )
    }

    @Test
    fun defaultMinimumContextIsFiveHundredAndTwelveTokens() {
        val workload = ready(request(minimumContextTokens = null))

        assertEquals(512, workload.minimumContextTokens)
    }

    @Test
    fun fallbackPermissionsRemainProfileNeutralAndExplicit() {
        val workload = ready(
            request(),
            settings(
                allowContextFallback = false,
                allowBatchFallback = true,
                allowKvCacheFallback = false,
            ),
        )

        assertEquals(false, workload.allowContextFallback)
        assertEquals(true, workload.allowBatchFallback)
        assertEquals(false, workload.allowKvCacheFallback)
    }

    @Test
    fun settingsAndWorkloadSnapshotCallerOwnedKvCollections() {
        val allowed = mutableListOf(KvCacheType.F16, KvCacheType.Q8_0)
        val planningSettings = settings(allowedKvCacheTypes = allowed)
        val workload = ready(request(), planningSettings)

        allowed.clear()

        assertEquals(listOf(KvCacheType.F16, KvCacheType.Q8_0), planningSettings.allowedKvCacheTypes)
        assertEquals(listOf(KvCacheType.F16, KvCacheType.Q8_0), workload.allowedKvCacheTypes)
    }

    @Test
    fun rejectsAcceleratorZeroAndGpuLayerCountsBeyondTheDescriptor() {
        val zeroAccelerator = factory.llm(
            request(),
            descriptor(),
            settings(
                backend = BackendKind.CUDA,
                memoryTopology = MemoryTopology.DISCRETE,
                gpuLayerCount = 0,
            ),
        )
        val excessiveLayers = factory.llm(
            request(),
            descriptor(),
            settings(
                backend = BackendKind.CUDA,
                memoryTopology = MemoryTopology.DISCRETE,
                gpuLayerCount = 33,
            ),
        )

        assertIs<WorkloadConfigResult.Invalid>(zeroAccelerator)
        assertIs<WorkloadConfigResult.Invalid>(excessiveLayers)
    }

    private fun ready(
        request: LlmWorkloadRequest,
        settings: PlanningSettings = settings(),
    ): LlmWorkloadConfig = assertIs<LlmWorkloadConfig>(
        assertIs<WorkloadConfigResult.Ready>(factory.llm(request, descriptor(), settings)).workload,
    )

    private fun request(
        contextTokens: Int = 4_096,
        minimumContextTokens: Int? = 512,
        promptTokens: Int = 1_024,
        generationReserveTokens: Int = 512,
        batchSize: Int = 512,
        microBatchSize: Int = 256,
        sequenceCount: Int = 1,
        kvCacheSelection: KvCacheSelection = KvCacheSelection.Auto,
    ) = LlmWorkloadRequest(
        contextTokens = contextTokens,
        minimumContextTokens = minimumContextTokens,
        promptTokens = promptTokens,
        generationReserveTokens = generationReserveTokens,
        batchSize = batchSize,
        microBatchSize = microBatchSize,
        sequenceCount = sequenceCount,
        kvCacheSelection = kvCacheSelection,
    )

    private fun settings(
        engineMaxContextTokens: Int = 16_384,
        engineMaxBatchSize: Int = 1_024,
        engineMaxMicroBatchSize: Int = 512,
        engineMaxSequenceCount: Int = 16,
        allowContextFallback: Boolean = true,
        allowBatchFallback: Boolean = true,
        allowKvCacheFallback: Boolean = true,
        allowedKvCacheTypes: Collection<KvCacheType> = KvCacheType.entries,
        backend: BackendKind = BackendKind.CPU,
        memoryTopology: MemoryTopology = MemoryTopology.UNKNOWN,
        gpuLayerCount: Int? = 0,
    ) = PlanningSettings(
        engineMaxContextTokens = engineMaxContextTokens,
        engineMaxBatchSize = engineMaxBatchSize,
        engineMaxMicroBatchSize = engineMaxMicroBatchSize,
        engineMaxSequenceCount = engineMaxSequenceCount,
        allowContextFallback = allowContextFallback,
        allowBatchFallback = allowBatchFallback,
        allowKvCacheFallback = allowKvCacheFallback,
        allowedKvCacheTypes = allowedKvCacheTypes,
        backend = backend,
        memoryTopology = memoryTopology,
        gpuLayerCount = gpuLayerCount,
    )

    private fun descriptor(contextLimit: Int? = 131_072) = llmDescriptor(
        sizeBytes = 4_000_000_000L,
        contextLimit = contextLimit,
        shape = TransformerShape(32, 8, 32, 4_096, 128),
    )
}
