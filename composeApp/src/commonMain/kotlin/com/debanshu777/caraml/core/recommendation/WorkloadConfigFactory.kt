package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind

class WorkloadConfigFactory {
    fun llm(
        request: LlmWorkloadRequest,
        descriptor: LlmModelDescriptor,
        settings: PlanningSettings,
    ): WorkloadConfigResult {
        if (
            !validRequest(request) ||
            !validSettings(settings, descriptor.transformerShape?.layerCount) ||
            !validDescriptorLimit(descriptor.contextLimit)
        ) {
            return invalidWorkload()
        }

        val minimumContext = request.minimumContextTokens ?: WorkloadLimits.DEFAULT_MIN_LLM_CONTEXT_TOKENS
        val modelLimit = descriptor.contextLimit ?: DescriptorLimits.MAX_CONTEXT_TOKENS
        val context = minOf(request.contextTokens, modelLimit, settings.engineMaxContextTokens)
        val batch = minOf(request.batchSize, settings.engineMaxBatchSize)
        val microBatch = minOf(request.microBatchSize, settings.engineMaxMicroBatchSize, batch)
        val sequences = minOf(request.sequenceCount, settings.engineMaxSequenceCount)

        val reserve = checkedAdd(request.promptTokens.toLong(), request.generationReserveTokens.toLong())
        if (
            minimumContext > context ||
            reserve !is CheckedLong.Value ||
            reserve.value > context.toLong() ||
            !selectionAllowed(request.kvCacheSelection, settings.allowedKvCacheTypes)
        ) {
            return invalidWorkload()
        }

        val evidence = buildList {
            if (context != request.contextTokens) {
                add(clampEvidence("context", request.contextTokens, context))
            }
            if (batch != request.batchSize) {
                add(clampEvidence("batch", request.batchSize, batch))
            }
            if (microBatch != request.microBatchSize) {
                add(clampEvidence("micro-batch", request.microBatchSize, microBatch))
            }
            if (sequences != request.sequenceCount) {
                add(clampEvidence("sequence-count", request.sequenceCount, sequences))
            }
        }
        val workload = LlmWorkloadConfig(
            userRequestedContextTokens = request.contextTokens,
            contextTokens = context,
            minimumContextTokens = minimumContext,
            promptTokens = request.promptTokens,
            generationReserveTokens = request.generationReserveTokens,
            batchSize = batch,
            microBatchSize = microBatch,
            sequenceCount = sequences,
            kvCacheSelection = request.kvCacheSelection,
            allowContextFallback = settings.allowContextFallback,
            allowBatchFallback = settings.allowBatchFallback,
            allowKvCacheFallback = settings.allowKvCacheFallback,
            allowedKvCacheTypes = settings.allowedKvCacheTypes,
            evidence = evidence,
        )
        return WorkloadConfigResult.Ready(workload, evidence)
    }

    private fun validRequest(request: LlmWorkloadRequest): Boolean {
        val minimumContext = request.minimumContextTokens ?: WorkloadLimits.DEFAULT_MIN_LLM_CONTEXT_TOKENS
        return request.contextTokens in 1..DescriptorLimits.MAX_CONTEXT_TOKENS &&
            minimumContext in 1..DescriptorLimits.MAX_CONTEXT_TOKENS &&
            minimumContext <= request.contextTokens &&
            request.promptTokens in 1..DescriptorLimits.MAX_CONTEXT_TOKENS &&
            request.generationReserveTokens in 1..DescriptorLimits.MAX_CONTEXT_TOKENS &&
            request.batchSize in 1..WorkloadLimits.MAX_BATCH_SIZE &&
            request.microBatchSize in 1..WorkloadLimits.MAX_BATCH_SIZE &&
            request.microBatchSize <= request.batchSize &&
            request.sequenceCount in 1..WorkloadLimits.MAX_SEQUENCE_COUNT
    }

    private fun validSettings(settings: PlanningSettings, modelLayerCount: Int?): Boolean =
        settings.engineMaxContextTokens in 1..DescriptorLimits.MAX_CONTEXT_TOKENS &&
            settings.engineMaxBatchSize in 1..WorkloadLimits.MAX_BATCH_SIZE &&
            settings.engineMaxMicroBatchSize in 1..WorkloadLimits.MAX_BATCH_SIZE &&
            settings.engineMaxSequenceCount in 1..WorkloadLimits.MAX_SEQUENCE_COUNT &&
            settings.allowedKvCacheTypes.isNotEmpty() &&
            settings.allowedKvCacheTypes.size <= KvCacheType.entries.size &&
            settings.gpuLayerCount?.let { it >= 0 } != false &&
            validPlacement(settings, modelLayerCount)

    private fun validPlacement(settings: PlanningSettings, modelLayerCount: Int?): Boolean = when {
        settings.backend == BackendKind.CPU -> settings.gpuLayerCount in listOf(null, 0)
        settings.gpuLayerCount == 0 -> false
        settings.gpuLayerCount != null && modelLayerCount != null -> settings.gpuLayerCount <= modelLayerCount
        else -> true
    }

    private fun validDescriptorLimit(value: Int?): Boolean =
        value == null || value in 1..DescriptorLimits.MAX_CONTEXT_TOKENS

    private fun selectionAllowed(
        selection: KvCacheSelection,
        allowed: Collection<KvCacheType>,
    ): Boolean = when (selection) {
        KvCacheSelection.Auto -> allowed.isNotEmpty()
        is KvCacheSelection.Explicit -> selection.keyType in allowed && selection.valueType in allowed
    }

    private fun invalidWorkload(): WorkloadConfigResult.Invalid = WorkloadConfigResult.Invalid(
        reasons = listOf(AssessmentReason.INVALID_WORKLOAD),
        evidence = listOf(Evidence(AssessmentReason.INVALID_WORKLOAD, Confidence.LOW)),
    )

    private fun clampEvidence(field: String, requested: Int, selected: Int) = Evidence(
        reason = AssessmentReason.WORKLOAD_CLAMPED,
        confidence = Confidence.HIGH,
        detail = "$field:requested=$requested,selected=$selected",
    )
}
