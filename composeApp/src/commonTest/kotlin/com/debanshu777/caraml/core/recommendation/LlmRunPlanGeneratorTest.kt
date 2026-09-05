package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmRunPlanGeneratorTest {
    private val factory = WorkloadConfigFactory()
    private val generator = RunPlanGenerator()

    @Test
    fun candidatesAreFiniteDistinctStableAndStartWithTheRequestedWorkload() {
        val descriptor = descriptor(maxContext = 131_072)
        val workload = workload(context = 32_768, minimumContext = 512)
        val settings = settings()

        val first = generator.llmCandidates(descriptor, workload, settings)
        val second = generator.llmCandidates(descriptor, workload, settings)

        assertTrue(first.size <= 24)
        assertEquals(first.distinct(), first)
        assertEquals(first, second)
        assertEquals(32_768, first.first().contextTokens)
        assertEquals(512, first.first().batchSize)
        assertEquals(256, first.first().microBatchSize)
        assertEquals(KvCacheType.F16, first.first().keyCacheType)
        assertEquals(KvCacheType.F16, first.first().valueCacheType)
        assertTrue(first.first().compromises.isEmpty())
    }

    @Test
    fun contextFallbacksUseOnlyLowerBucketsAndTheExactMinimum() {
        val plans = generator.llmCandidates(
            descriptor(maxContext = 131_072),
            workload(context = 20_000, minimumContext = 3_000, kv = explicitF16()),
            settings(allowBatchFallback = false, allowKvCacheFallback = false),
        )

        assertEquals(listOf(20_000, 16_384, 8_192, 4_096, 3_000), plans.map { it.contextTokens })
        assertTrue(plans.drop(1).all { RunPlanCompromise.CONTEXT_REDUCED in it.compromises })
        assertTrue(plans.none { it.contextTokens < 3_000 })
    }

    @Test
    fun explicitKvTypesAreNeverReplacedByGeneratedAlternatives() {
        val plans = generator.llmCandidates(
            descriptor(),
            workload(kv = KvCacheSelection.Explicit(KvCacheType.Q8_0, KvCacheType.F16)),
            settings(),
        )

        assertEquals(setOf(KvCacheType.Q8_0 to KvCacheType.F16), plans.map { it.keyCacheType to it.valueCacheType }.toSet())
    }

    @Test
    fun autoKvAddsOnlyPermittedAlternativesAndDisclosesTheCompromise() {
        val plans = generator.llmCandidates(
            descriptor(),
            workload(kv = KvCacheSelection.Auto, minimumContext = 4_096),
            settings(
                allowContextFallback = false,
                allowBatchFallback = false,
                allowedKvCacheTypes = listOf(KvCacheType.F16, KvCacheType.Q8_0),
            ),
        )

        assertEquals(listOf(KvCacheType.F16, KvCacheType.Q8_0), plans.map { it.keyCacheType })
        assertTrue(plans[1].compromises == listOf(RunPlanCompromise.KV_CACHE_REDUCED))
    }

    @Test
    fun disabledFallbackAxesDoNotMutateTheRequestedPlan() {
        val settings = settings(
            allowContextFallback = false,
            allowBatchFallback = false,
            allowKvCacheFallback = false,
        )
        val plans = generator.llmCandidates(
            descriptor(),
            workload(kv = KvCacheSelection.Auto, settings = settings),
            settings,
        )

        assertEquals(1, plans.size)
        assertTrue(plans.single().compromises.isEmpty())
    }

    @Test
    fun batchFallbacksAreBoundedAndEveryChangedFieldIsDisclosed() {
        val plans = generator.llmCandidates(
            descriptor(),
            workload(context = 4_096, minimumContext = 4_096, kv = explicitF16(), batch = 512, microBatch = 256),
            settings(allowContextFallback = false, allowKvCacheFallback = false),
        )

        assertEquals(listOf(512, 256, 128), plans.map { it.batchSize })
        assertTrue(RunPlanCompromise.BATCH_REDUCED in plans[1].compromises)
        assertTrue(RunPlanCompromise.BATCH_REDUCED in plans[2].compromises)
        assertTrue(RunPlanCompromise.MICRO_BATCH_REDUCED in plans[2].compromises)
    }

    @Test
    fun planSnapshotsCallerOwnedCompromises() {
        val compromises = mutableListOf(RunPlanCompromise.CONTEXT_REDUCED)
        val plan = LlmRunPlan(
            contextTokens = 2_048,
            batchSize = 128,
            microBatchSize = 128,
            sequenceCount = 1,
            keyCacheType = KvCacheType.F16,
            valueCacheType = KvCacheType.F16,
            backend = BackendKind.CPU,
            memoryTopology = MemoryTopology.UNKNOWN,
            gpuLayerCount = 0,
            compromises = compromises,
        )

        compromises.clear()

        assertEquals(listOf(RunPlanCompromise.CONTEXT_REDUCED), plan.compromises)
    }

    private fun workload(
        context: Int = 8_192,
        minimumContext: Int = 512,
        kv: KvCacheSelection = KvCacheSelection.Auto,
        batch: Int = 512,
        microBatch: Int = 256,
        settings: PlanningSettings = settings(),
    ): LlmWorkloadConfig {
        val result = factory.llm(
            LlmWorkloadRequest(
                contextTokens = context,
                minimumContextTokens = minimumContext,
                promptTokens = 256,
                generationReserveTokens = 256,
                batchSize = batch,
                microBatchSize = microBatch,
                sequenceCount = 1,
                kvCacheSelection = kv,
            ),
            descriptor(maxContext = 131_072),
            settings,
        )
        return (result as WorkloadConfigResult.Ready).workload as LlmWorkloadConfig
    }

    private fun settings(
        allowContextFallback: Boolean = true,
        allowBatchFallback: Boolean = true,
        allowKvCacheFallback: Boolean = true,
        allowedKvCacheTypes: Collection<KvCacheType> = KvCacheType.entries,
    ) = PlanningSettings(
        engineMaxContextTokens = 131_072,
        engineMaxBatchSize = 1_024,
        engineMaxMicroBatchSize = 512,
        engineMaxSequenceCount = 8,
        allowContextFallback = allowContextFallback,
        allowBatchFallback = allowBatchFallback,
        allowKvCacheFallback = allowKvCacheFallback,
        allowedKvCacheTypes = allowedKvCacheTypes,
        backend = BackendKind.CPU,
        memoryTopology = MemoryTopology.UNKNOWN,
        gpuLayerCount = 0,
    )

    private fun descriptor(maxContext: Int? = 131_072) = llmDescriptor(
        sizeBytes = 4_000_000_000L,
        contextLimit = maxContext,
        shape = TransformerShape(32, 8, 32, 4_096, 128),
    )

    private fun explicitF16() = KvCacheSelection.Explicit(KvCacheType.F16, KvCacheType.F16)
}
