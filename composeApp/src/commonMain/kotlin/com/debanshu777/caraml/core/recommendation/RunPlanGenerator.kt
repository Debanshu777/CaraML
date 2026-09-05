package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind

private const val MAX_LLM_PLAN_CANDIDATES = 24
private const val MAX_DIFFUSION_PLAN_CANDIDATES = 12
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

    fun diffusionCandidates(
        descriptor: DiffusionModelDescriptor,
        workload: DiffusionWorkloadConfig,
        settings: PlanningSettings,
    ): List<DiffusionRunPlan> {
        if (!validDiffusionInputs(descriptor, workload, settings)) return emptyList()

        val candidates = LinkedHashSet<DiffusionRunPlan>(MAX_DIFFUSION_PLAN_CANDIDATES)
        fun add(
            width: Int = workload.width,
            height: Int = workload.height,
            frameCount: Int = workload.frameCount,
            vaeTiling: Boolean = workload.vaeTiling,
            offloadToCpu: Boolean = workload.offloadToCpu,
            maxVramBytes: Long? = workload.maxVramBytes,
            layerStreaming: Boolean = workload.layerStreaming,
            requiresUserAcceptance: Boolean = false,
            compromises: Collection<DiffusionPlanCompromise> = emptyList(),
        ) {
            if (candidates.size >= MAX_DIFFUSION_PLAN_CANDIDATES) return
            candidates += DiffusionRunPlan(
                mode = workload.mode,
                width = width,
                height = height,
                frameCount = frameCount,
                batchSize = workload.batchSize,
                steps = workload.steps,
                vaeTiling = vaeTiling,
                offloadToCpu = offloadToCpu,
                keepClipOnCpu = workload.keepClipOnCpu,
                keepVaeOnCpu = workload.keepVaeOnCpu,
                maxVramBytes = maxVramBytes,
                layerStreaming = layerStreaming,
                requiresUserAcceptance = requiresUserAcceptance,
                backend = settings.backend,
                memoryTopology = settings.memoryTopology,
                compromises = compromises,
            )
        }

        add()

        val optionalAxes = buildList {
            if (!workload.vaeTiling && workload.allowVaeTilingFallback && settings.supportsVaeTiling) {
                add(DiffusionFallbackAxis.VAE_TILING)
            }
            if (
                workload.maxVramBytes == null && workload.allowMaxVramFallback &&
                settings.supportsMaxVram && settings.maxVramBytes != null &&
                settings.memoryTopology == com.debanshu777.caraml.core.platform.MemoryTopology.DISCRETE
            ) {
                add(DiffusionFallbackAxis.MAX_VRAM)
            }
            if (
                !workload.layerStreaming && workload.allowLayerStreamingFallback && settings.supportsLayerStreaming &&
                settings.backend != BackendKind.CPU
            ) {
                add(DiffusionFallbackAxis.LAYER_STREAMING)
            }
        }
        for (mask in 1 until (1 shl optionalAxes.size)) {
            var vaeTiling = workload.vaeTiling
            var offloadToCpu = workload.offloadToCpu
            var maxVramBytes = workload.maxVramBytes
            var layerStreaming = workload.layerStreaming
            val compromises = mutableListOf<DiffusionPlanCompromise>()
            optionalAxes.forEachIndexed { index, axis ->
                if (mask and (1 shl index) == 0) return@forEachIndexed
                when (axis) {
                    DiffusionFallbackAxis.VAE_TILING -> {
                        vaeTiling = true
                        compromises += DiffusionPlanCompromise.VAE_TILING
                    }
                    DiffusionFallbackAxis.MAX_VRAM -> {
                        maxVramBytes = settings.maxVramBytes
                        compromises += DiffusionPlanCompromise.MAX_VRAM_LIMIT
                    }
                    DiffusionFallbackAxis.LAYER_STREAMING -> {
                        layerStreaming = true
                        if (!offloadToCpu) {
                            offloadToCpu = true
                            compromises += DiffusionPlanCompromise.CPU_OFFLOAD
                        }
                        compromises += DiffusionPlanCompromise.LAYER_STREAMING
                    }
                }
            }
            add(
                vaeTiling = vaeTiling,
                offloadToCpu = offloadToCpu,
                maxVramBytes = maxVramBytes,
                layerStreaming = layerStreaming,
                compromises = compromises,
            )
        }

        if (workload.mode == DiffusionMode.VIDEO && workload.allowFrameCountFallback) {
            val reducedFrames = maxOf(workload.minimumFrameCount, workload.frameCount / 2)
            if (reducedFrames < workload.frameCount) {
                add(
                    frameCount = reducedFrames,
                    requiresUserAcceptance = true,
                    compromises = listOf(DiffusionPlanCompromise.FRAME_COUNT_REDUCED),
                )
            }
        }

        if (workload.allowResolutionFallback) {
            lowerResolutionCandidates(workload, settings.engineImageDimensionMultiple).forEach { resolution ->
                add(
                    width = resolution.first,
                    height = resolution.second,
                    requiresUserAcceptance = true,
                    compromises = listOf(DiffusionPlanCompromise.LOWER_RESOLUTION),
                )
            }
        }
        return candidates.toList().take(MAX_DIFFUSION_PLAN_CANDIDATES)
    }

    private fun validDiffusionInputs(
        descriptor: DiffusionModelDescriptor,
        workload: DiffusionWorkloadConfig,
        settings: PlanningSettings,
    ): Boolean {
        val multiple = settings.engineImageDimensionMultiple
        if (
            !validDiffusionDescriptor(descriptor) || descriptor.mode != workload.mode ||
            multiple !in 1..DescriptorLimits.MAX_IMAGE_DIMENSION ||
            settings.engineMaxImageDimension !in 1..DescriptorLimits.MAX_IMAGE_DIMENSION ||
            settings.engineMaxDiffusionFrames !in 1..WorkloadLimits.MAX_DIFFUSION_FRAMES ||
            settings.engineMaxDiffusionSteps !in 1..WorkloadLimits.MAX_DIFFUSION_STEPS ||
            settings.engineMaxBatchSize !in 1..WorkloadLimits.MAX_BATCH_SIZE ||
            workload.width !in 1..settings.engineMaxImageDimension ||
            workload.height !in 1..settings.engineMaxImageDimension ||
            workload.minimumWidth !in 1..workload.width ||
            workload.minimumHeight !in 1..workload.height ||
            workload.width % multiple != 0 || workload.height % multiple != 0 ||
            workload.frameCount !in 1..settings.engineMaxDiffusionFrames ||
            workload.minimumFrameCount !in 1..workload.frameCount ||
            workload.batchSize !in 1..settings.engineMaxBatchSize ||
            workload.steps !in 1..settings.engineMaxDiffusionSteps ||
            (workload.mode == DiffusionMode.IMAGE &&
                (workload.frameCount != 1 || workload.minimumFrameCount != 1)) ||
            (settings.backend != BackendKind.CPU && settings.memoryTopology == com.debanshu777.caraml.core.platform.MemoryTopology.UNKNOWN) ||
            (settings.backend == BackendKind.CPU && (workload.maxVramBytes != null || workload.layerStreaming))
        ) {
            return false
        }
        if (workload.vaeTiling && !settings.supportsVaeTiling) return false
        if (workload.layerStreaming && (!settings.supportsLayerStreaming || !workload.offloadToCpu)) return false
        if (workload.maxVramBytes != null) {
            if (
                !settings.supportsMaxVram ||
                settings.memoryTopology != com.debanshu777.caraml.core.platform.MemoryTopology.DISCRETE ||
                workload.maxVramBytes !in 1..DescriptorLimits.MAX_BUNDLE_BYTES
            ) return false
            if (settings.maxVramBytes != null && workload.maxVramBytes > settings.maxVramBytes) return false
        }
        if (settings.maxVramBytes?.let { it !in 1..DescriptorLimits.MAX_BUNDLE_BYTES } == true) {
            return false
        }
        return true
    }

    private fun validDiffusionDescriptor(descriptor: DiffusionModelDescriptor): Boolean {
        if (
            descriptor.repositoryId.isBlank() || descriptor.revision.isBlank() || descriptor.family.isBlank() ||
            descriptor.family.length > DescriptorLimits.MAX_METADATA_STRING_LENGTH ||
            descriptor.width?.let { it !in 1..DescriptorLimits.MAX_IMAGE_DIMENSION } == true ||
            descriptor.height?.let { it !in 1..DescriptorLimits.MAX_IMAGE_DIMENSION } == true ||
            descriptor.components.isEmpty() || descriptor.components.size > DescriptorLimits.MAX_COMPONENTS ||
            !descriptor.requiredComponentsPresent || descriptor.components.none { it.isPrimary }
        ) {
            return false
        }
        val seen = mutableSetOf<String>()
        var bundleBytes = 0L
        for (component in descriptor.components) {
            val file = component.file
            if (
                file.repositoryId != descriptor.repositoryId || file.revision != descriptor.revision ||
                file.path.isBlank() || file.path.length > DescriptorLimits.MAX_RELATIVE_PATH_LENGTH ||
                file.sizeBytes !in 1..DescriptorLimits.MAX_FILE_BYTES ||
                (!component.isPrimary && component.role == null) ||
                !seen.add("${file.repositoryId}:${file.revision}:${file.path}")
            ) {
                return false
            }
            bundleBytes = when (val sum = checkedAdd(bundleBytes, file.sizeBytes)) {
                is CheckedLong.Invalid -> return false
                is CheckedLong.Value -> sum.value
            }
            if (bundleBytes > DescriptorLimits.MAX_BUNDLE_BYTES) return false
        }
        return true
    }

    private fun lowerResolutionCandidates(
        workload: DiffusionWorkloadConfig,
        multiple: Int,
    ): List<Pair<Int, Int>> {
        val divisor = greatestCommonDivisor(workload.width, workload.height)
        val widthUnits = workload.width / divisor
        val heightUnits = workload.height / divisor
        val minimumUnit = maxOf(
            ceilingDivide(workload.minimumWidth, widthUnits),
            ceilingDivide(workload.minimumHeight, heightUnits),
        )
        val alignedMinimumUnit = ceilingDivide(minimumUnit, multiple) * multiple
        return listOf(3 to 4, 1 to 2).mapNotNull { (numerator, denominator) ->
            val requestedUnit = ((divisor.toLong() * numerator / denominator) / multiple * multiple).toInt()
            val unit = maxOf(requestedUnit, alignedMinimumUnit)
            val width = widthUnits.toLong() * unit
            val height = heightUnits.toLong() * unit
            if (
                unit <= 0 || width >= workload.width || height >= workload.height ||
                width < workload.minimumWidth || height < workload.minimumHeight ||
                width > Int.MAX_VALUE || height > Int.MAX_VALUE
            ) {
                null
            } else {
                width.toInt() to height.toInt()
            }
        }.distinct()
    }

    private fun greatestCommonDivisor(left: Int, right: Int): Int {
        var a = left
        var b = right
        while (b != 0) {
            val remainder = a % b
            a = b
            b = remainder
        }
        return a
    }

    private fun ceilingDivide(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt()

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

private enum class DiffusionFallbackAxis {
    VAE_TILING,
    MAX_VRAM,
    LAYER_STREAMING,
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
