package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind

private const val MAX_LLM_PLAN_CANDIDATES = 24
private val CONTEXT_BUCKETS = listOf(16_384, 8_192, 4_096, 2_048, 1_024, 512)
private val BATCH_BUCKETS = listOf(512, 256, 128)
private val KV_PREFERENCE_ORDER = listOf(KvCacheType.F16, KvCacheType.Q8_0, KvCacheType.Q4_0)

class RunPlanGenerator {
    fun llmCandidates(
        descriptor: LlmModelDescriptor,
        workload: LlmWorkloadConfig,
        settings: PlanningSettings,
    ): List<LlmRunPlan> {
        if (!validInputs(descriptor, workload, settings)) return emptyList()

        val allowedKv = KV_PREFERENCE_ORDER.filter {
            it in workload.allowedKvCacheTypes && it in settings.allowedKvCacheTypes
        }
        val requestedTypes = when (val selection = workload.kvCacheSelection) {
            KvCacheSelection.Auto -> allowedKv.firstOrNull()?.let { it to it } ?: return emptyList()
            is KvCacheSelection.Explicit -> selection.keyType to selection.valueType
        }
        val contexts = buildList {
            add(workload.contextTokens)
            if (workload.allowContextFallback && settings.allowContextFallback) {
                addAll(CONTEXT_BUCKETS.filter { it < workload.contextTokens && it >= workload.minimumContextTokens })
                if (workload.minimumContextTokens < workload.contextTokens) add(workload.minimumContextTokens)
            }
        }.distinct()
        val kvPairs = buildList {
            add(requestedTypes)
            if (
                workload.kvCacheSelection == KvCacheSelection.Auto &&
                workload.allowKvCacheFallback &&
                settings.allowKvCacheFallback
            ) {
                addAll(allowedKv.drop(1).map { it to it })
            }
        }.distinct()
        val batches = buildList {
            add(workload.batchSize)
            if (workload.allowBatchFallback && settings.allowBatchFallback) {
                addAll(BATCH_BUCKETS.filter { it < workload.batchSize })
            }
        }.distinct()

        val candidates = LinkedHashSet<LlmRunPlan>(MAX_LLM_PLAN_CANDIDATES)
        fun add(context: Int, kv: Pair<KvCacheType, KvCacheType>, batch: Int) {
            if (candidates.size >= MAX_LLM_PLAN_CANDIDATES) return
            val microBatch = minOf(workload.microBatchSize, batch)
            val compromises = buildList {
                if (context != workload.contextTokens) add(RunPlanCompromise.CONTEXT_REDUCED)
                if (batch != workload.batchSize) add(RunPlanCompromise.BATCH_REDUCED)
                if (microBatch != workload.microBatchSize) add(RunPlanCompromise.MICRO_BATCH_REDUCED)
                if (kv != requestedTypes) add(RunPlanCompromise.KV_CACHE_REDUCED)
            }
            candidates += LlmRunPlan(
                contextTokens = context,
                batchSize = batch,
                microBatchSize = microBatch,
                sequenceCount = workload.sequenceCount,
                keyCacheType = kv.first,
                valueCacheType = kv.second,
                backend = settings.backend,
                memoryTopology = settings.memoryTopology,
                gpuLayerCount = if (settings.backend == BackendKind.CPU) 0 else settings.gpuLayerCount,
                compromises = compromises,
            )
        }

        add(workload.contextTokens, requestedTypes, workload.batchSize)
        contexts.drop(1).forEach { add(it, requestedTypes, workload.batchSize) }
        kvPairs.drop(1).forEach { add(workload.contextTokens, it, workload.batchSize) }
        batches.drop(1).forEach { add(workload.contextTokens, requestedTypes, it) }
        contexts.drop(1).forEach { context ->
            kvPairs.drop(1).forEach { kv -> add(context, kv, workload.batchSize) }
        }
        contexts.forEach { context ->
            batches.drop(1).forEach { batch -> add(context, requestedTypes, batch) }
        }
        return candidates.toList()
    }

    private fun validInputs(
        descriptor: LlmModelDescriptor,
        workload: LlmWorkloadConfig,
        settings: PlanningSettings,
    ): Boolean =
        descriptor.file.sizeBytes in 1..DescriptorLimits.MAX_FILE_BYTES &&
            workload.contextTokens in 1..DescriptorLimits.MAX_CONTEXT_TOKENS &&
            workload.minimumContextTokens in 1..workload.contextTokens &&
            descriptor.contextLimit?.let { workload.contextTokens <= it } != false &&
            workload.promptTokens > 0 &&
            workload.generationReserveTokens > 0 &&
            workload.batchSize in 1..WorkloadLimits.MAX_BATCH_SIZE &&
            workload.microBatchSize in 1..workload.batchSize &&
            workload.sequenceCount in 1..WorkloadLimits.MAX_SEQUENCE_COUNT &&
            settings.engineMaxContextTokens in workload.contextTokens..DescriptorLimits.MAX_CONTEXT_TOKENS &&
            settings.engineMaxBatchSize >= workload.batchSize &&
            settings.engineMaxMicroBatchSize >= workload.microBatchSize &&
            settings.engineMaxSequenceCount >= workload.sequenceCount
}
