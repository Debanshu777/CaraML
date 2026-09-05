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
        val contextFloor = requiredContextFloor(workload) ?: return emptyList()
        if (!validInputs(descriptor, workload, settings, contextFloor)) return emptyList()

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
                addAll(CONTEXT_BUCKETS.filter { it < workload.contextTokens && it >= contextFloor })
                if (contextFloor < workload.contextTokens) add(contextFloor)
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
        fun add(contextIndex: Int, kvIndex: Int, batchIndex: Int) {
            if (candidates.size >= MAX_LLM_PLAN_CANDIDATES) return
            val context = contexts[contextIndex]
            val kv = kvPairs[kvIndex]
            val batch = batches[batchIndex]
            val microBatch = minOf(workload.microBatchSize, batch)
            if (
                context !in contextFloor..workload.contextTokens ||
                batch !in 1..workload.batchSize ||
                microBatch !in 1..batch ||
                kv.first !in allowedKv ||
                kv.second !in allowedKv
            ) {
                return
            }
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

        val endpoint = CandidateIndex(contexts.lastIndex, kvPairs.lastIndex, batches.lastIndex)
        val frontier = buildList {
            for (contextIndex in contexts.indices) {
                for (kvIndex in kvPairs.indices) {
                    for (batchIndex in batches.indices) {
                        val candidate = CandidateIndex(contextIndex, kvIndex, batchIndex)
                        if (candidate != CandidateIndex.REQUESTED && candidate != endpoint) add(candidate)
                    }
                }
            }
        }.sortedWith(
            compareBy<CandidateIndex> { it.totalDegradation }
                .thenByDescending { it.changedAxisCount }
                .thenBy { it.contextIndex }
                .thenBy { it.kvIndex }
                .thenBy { it.batchIndex },
        )

        add(CandidateIndex.REQUESTED.contextIndex, CandidateIndex.REQUESTED.kvIndex, CandidateIndex.REQUESTED.batchIndex)
        frontier.forEach { candidate ->
            if (candidates.size < MAX_LLM_PLAN_CANDIDATES - 1 || endpoint == CandidateIndex.REQUESTED) {
                add(candidate.contextIndex, candidate.kvIndex, candidate.batchIndex)
            }
        }
        if (endpoint != CandidateIndex.REQUESTED) {
            add(endpoint.contextIndex, endpoint.kvIndex, endpoint.batchIndex)
        }
        return candidates.toList()
    }

    private fun validInputs(
        descriptor: LlmModelDescriptor,
        workload: LlmWorkloadConfig,
        settings: PlanningSettings,
        contextFloor: Int,
    ): Boolean =
        descriptor.file.sizeBytes in 1..DescriptorLimits.MAX_FILE_BYTES &&
            workload.contextTokens in 1..DescriptorLimits.MAX_CONTEXT_TOKENS &&
            workload.minimumContextTokens in 1..workload.contextTokens &&
            contextFloor in workload.minimumContextTokens..workload.contextTokens &&
            descriptor.contextLimit?.let { workload.contextTokens <= it } != false &&
            workload.promptTokens in 1..DescriptorLimits.MAX_CONTEXT_TOKENS &&
            workload.generationReserveTokens in 1..DescriptorLimits.MAX_CONTEXT_TOKENS &&
            workload.batchSize in 1..WorkloadLimits.MAX_BATCH_SIZE &&
            workload.microBatchSize in 1..workload.batchSize &&
            workload.sequenceCount in 1..WorkloadLimits.MAX_SEQUENCE_COUNT &&
            settings.engineMaxContextTokens in workload.contextTokens..DescriptorLimits.MAX_CONTEXT_TOKENS &&
            settings.engineMaxBatchSize in workload.batchSize..WorkloadLimits.MAX_BATCH_SIZE &&
            settings.engineMaxMicroBatchSize in workload.microBatchSize..WorkloadLimits.MAX_BATCH_SIZE &&
            settings.engineMaxSequenceCount in workload.sequenceCount..WorkloadLimits.MAX_SEQUENCE_COUNT &&
            requestedKvAllowed(workload, settings) &&
            validPlacement(descriptor, settings)

    private fun requiredContextFloor(workload: LlmWorkloadConfig): Int? {
        val reserve = checkedAdd(workload.promptTokens.toLong(), workload.generationReserveTokens.toLong())
        if (reserve !is CheckedLong.Value || reserve.value > DescriptorLimits.MAX_CONTEXT_TOKENS.toLong()) return null
        return maxOf(workload.minimumContextTokens, reserve.value.toInt())
    }

    private fun requestedKvAllowed(workload: LlmWorkloadConfig, settings: PlanningSettings): Boolean {
        val allowed = workload.allowedKvCacheTypes.intersect(settings.allowedKvCacheTypes.toSet())
        return when (val selection = workload.kvCacheSelection) {
            KvCacheSelection.Auto -> allowed.isNotEmpty()
            is KvCacheSelection.Explicit -> selection.keyType in allowed && selection.valueType in allowed
        }
    }

    private fun validPlacement(descriptor: LlmModelDescriptor, settings: PlanningSettings): Boolean = when {
        settings.gpuLayerCount?.let { it < 0 } == true -> false
        settings.backend == BackendKind.CPU -> settings.gpuLayerCount in listOf(null, 0)
        settings.gpuLayerCount == 0 -> false
        settings.gpuLayerCount != null && descriptor.transformerShape?.layerCount != null ->
            settings.gpuLayerCount <= descriptor.transformerShape.layerCount
        else -> true
    }
}

private data class CandidateIndex(
    val contextIndex: Int,
    val kvIndex: Int,
    val batchIndex: Int,
) {
    val totalDegradation: Int = contextIndex + kvIndex + batchIndex
    val changedAxisCount: Int = listOf(contextIndex, kvIndex, batchIndex).count { it > 0 }

    companion object {
        val REQUESTED = CandidateIndex(0, 0, 0)
    }
}
