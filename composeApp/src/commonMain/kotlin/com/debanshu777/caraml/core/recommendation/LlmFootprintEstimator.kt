package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology

private const val MIB = 1_048_576L

sealed interface MemoryCalibration {
    data object None : MemoryCalibration

    data class Correction(
        val likelyNumerator: Long,
        val likelyDenominator: Long,
        val highNumerator: Long,
        val highDenominator: Long,
    ) : MemoryCalibration
}

class LlmFootprintEstimator {
    fun estimate(
        descriptor: LlmModelDescriptor,
        plan: LlmRunPlan,
        calibration: MemoryCalibration,
    ): PlanAssessment {
        val storage = descriptor.file.sizeBytes
            .takeIf { it in 1..DescriptorLimits.MAX_FILE_BYTES }
            ?.let(::exactRange)
        val invalidReason = validate(descriptor, plan, calibration)
        if (invalidReason != null) return invalidAssessment(plan, storage, invalidReason)

        val components = when (val calculated = calculateComponents(descriptor, plan)) {
            is ComponentResult.Invalid -> return invalidAssessment(plan, storage, calculated.reason)
            is ComponentResult.Value -> calculated
        }
        val poolEstimate = when {
            plan.backend == BackendKind.CPU -> singlePool(
                weights = descriptor.file.sizeBytes,
                dynamic = components.allDynamic,
                calibration = calibration,
                pool = Pool.HOST,
            )
            plan.memoryTopology == MemoryTopology.UNIFIED -> singlePool(
                weights = descriptor.file.sizeBytes,
                dynamic = components.allDynamic,
                calibration = calibration,
                pool = Pool.SHARED,
            )
            plan.memoryTopology == MemoryTopology.DISCRETE -> discretePools(
                weights = descriptor.file.sizeBytes,
                layerCount = descriptor.transformerShape?.layerCount,
                gpuLayerCount = plan.gpuLayerCount,
                runtime = components.runtime,
                acceleratorDynamic = components.acceleratorDynamic,
                allDynamic = components.allDynamic,
                calibration = calibration,
            )
            else -> PoolResult.Invalid(AssessmentReason.BACKEND_CAPABILITY_UNKNOWN)
        }
        if (poolEstimate is PoolResult.Invalid) {
            return invalidAssessment(plan, storage, poolEstimate.reason, components.evidence)
        }
        poolEstimate as PoolResult.Value
        val lowConfidence = components.lowConfidence || poolEstimate.lowConfidence
        val evidence = buildList {
            add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "weights:file-size"))
            addAll(components.evidence)
            addAll(poolEstimate.evidence)
        }
        return PlanAssessment(
            plan = plan,
            hostMemoryBytes = poolEstimate.host,
            gpuMemoryBytes = poolEstimate.gpu,
            sharedMemoryBytes = poolEstimate.shared,
            storageBytes = storage,
            confidence = confidence(if (lowConfidence) Confidence.LOW else Confidence.MEDIUM, storage != null),
            evidence = evidence,
        )
    }

    private fun validate(
        descriptor: LlmModelDescriptor,
        plan: LlmRunPlan,
        calibration: MemoryCalibration,
    ): AssessmentReason? {
        validateRunPlan(plan)?.let { return it }
        if (descriptor.file.sizeBytes !in 1..DescriptorLimits.MAX_FILE_BYTES) {
            return AssessmentReason.INVALID_METADATA
        }
        val shapeValues = descriptor.transformerShape?.let {
            listOf(it.layerCount, it.kvHeadCount, it.attentionHeadCount, it.hiddenSize, it.headDim)
        }.orEmpty()
        if (shapeValues.filterNotNull().any { it <= 0 }) return AssessmentReason.INVALID_METADATA
        if (
            descriptor.contextLimit?.let { plan.contextTokens > it } == true ||
            descriptor.transformerShape?.layerCount?.let { layers ->
                plan.gpuLayerCount?.let { it > layers }
            } == true
        ) {
            return AssessmentReason.INVALID_WORKLOAD
        }
        return when (calibration) {
            MemoryCalibration.None -> null
            is MemoryCalibration.Correction -> validateCalibration(calibration)
        }
    }

    private fun validateCalibration(value: MemoryCalibration.Correction): AssessmentReason? {
        if (
            value.likelyNumerator <= 0L || value.likelyDenominator <= 0L ||
            value.highNumerator <= 0L || value.highDenominator <= 0L ||
            value.likelyNumerator < value.likelyDenominator ||
            value.highNumerator < value.highDenominator
        ) {
            return AssessmentReason.INVALID_ESTIMATE_RANGE
        }
        val left = checkedMultiply(value.highNumerator, value.likelyDenominator)
        val right = checkedMultiply(value.likelyNumerator, value.highDenominator)
        return when {
            left is CheckedLong.Invalid || right is CheckedLong.Invalid -> AssessmentReason.ARITHMETIC_OVERFLOW
            (left as CheckedLong.Value).value < (right as CheckedLong.Value).value ->
                AssessmentReason.INVALID_ESTIMATE_RANGE
            else -> null
        }
    }

    private fun calculateComponents(
        descriptor: LlmModelDescriptor,
        plan: LlmRunPlan,
    ): ComponentResult {
        val evidence = mutableListOf<Evidence>()
        var lowConfidence = false
        val shape = descriptor.transformerShape

        val kv = if (shape?.layerCount != null && shape.kvHeadCount != null && shape.headDim != null) {
            when (val value = exactKvRange(shape.layerCount, shape.kvHeadCount, shape.headDim, plan)) {
                is RangeResult.Invalid -> return ComponentResult.Invalid(value.reason)
                is RangeResult.Value -> value.range
            }
        } else {
            lowConfidence = true
            evidence += missingShapeEvidence("kv-cache")
            when (val value = unknownKvRange(plan)) {
                is RangeResult.Invalid -> return ComponentResult.Invalid(value.reason)
                is RangeResult.Value -> value.range
            }
        }

        val graph = when (val value = graphRange(shape, plan.batchSize, plan.microBatchSize)) {
            is RangeResult.Invalid -> return ComponentResult.Invalid(value.reason)
            is RangeResult.Value -> {
                if (value.inferred) {
                    lowConfidence = true
                    evidence += missingShapeEvidence("compute-graph")
                }
                value.range
            }
        }
        val runtime = when (val value = percentageRange(descriptor.file.sizeBytes, 5, 10, 20)) {
            is RangeResult.Invalid -> return ComponentResult.Invalid(value.reason)
            is RangeResult.Value -> value.range
        }
        val backend = validRange(16L * MIB, 32L * MIB, 64L * MIB)
        val recurrent = when (val value = recurrentRange(descriptor, plan)) {
            is RangeResult.Invalid -> return ComponentResult.Invalid(value.reason)
            is RangeResult.Value -> {
                if (value.inferred) {
                    lowConfidence = true
                    evidence += Evidence(
                        AssessmentReason.RECURRENT_STATE_ESTIMATED,
                        Confidence.LOW,
                        "architecture-family",
                    )
                }
                value.range
            }
        }
        val acceleratorDynamic = when (val value = addRanges(kv, graph, backend, recurrent)) {
            is RangeResult.Invalid -> return ComponentResult.Invalid(value.reason)
            is RangeResult.Value -> value.range
        }
        val allDynamic = when (val value = addRanges(acceleratorDynamic, runtime)) {
            is RangeResult.Invalid -> return ComponentResult.Invalid(value.reason)
            is RangeResult.Value -> value.range
        }
        return ComponentResult.Value(
            runtime = runtime,
            acceleratorDynamic = acceleratorDynamic,
            allDynamic = allDynamic,
            lowConfidence = lowConfidence,
            evidence = evidence,
        )
    }

    private fun exactKvRange(
        layers: Int,
        kvHeads: Int,
        headDim: Int,
        plan: LlmRunPlan,
    ): RangeResult {
        val elements = checkedProduct(
            layers.toLong(),
            kvHeads.toLong(),
            headDim.toLong(),
            plan.contextTokens.toLong(),
            plan.sequenceCount.toLong(),
        )
        if (elements is CheckedLong.Invalid) return RangeResult.Invalid(elements.reason)
        elements as CheckedLong.Value
        val keyBytes = bytesForElements(elements.value, plan.keyCacheType)
        val valueBytes = bytesForElements(elements.value, plan.valueCacheType)
        if (keyBytes is CheckedLong.Invalid) return RangeResult.Invalid(keyBytes.reason)
        if (valueBytes is CheckedLong.Invalid) return RangeResult.Invalid(valueBytes.reason)
        val total = checkedAdd((keyBytes as CheckedLong.Value).value, (valueBytes as CheckedLong.Value).value)
        return when (total) {
            is CheckedLong.Invalid -> RangeResult.Invalid(total.reason)
            is CheckedLong.Value -> RangeResult.Value(exactRange(total.value))
        }
    }

    private fun unknownKvRange(plan: LlmRunPlan): RangeResult {
        val tokens = checkedMultiply(plan.contextTokens.toLong(), plan.sequenceCount.toLong())
        if (tokens is CheckedLong.Invalid) return RangeResult.Invalid(tokens.reason)
        tokens as CheckedLong.Value
        val low = checkedMultiply(tokens.value, 64L * 1_024L)
        val likely = checkedMultiply(tokens.value, 256L * 1_024L)
        val high = checkedMultiply(tokens.value, MIB)
        return rangeFromChecked(low, likely, high)
    }

    private fun graphRange(shape: TransformerShape?, batchSize: Int, microBatchSize: Int): RangeResult {
        val hidden = when {
            shape?.hiddenSize != null -> CheckedLong.Value(shape.hiddenSize.toLong())
            shape?.attentionHeadCount != null && shape.headDim != null ->
                checkedMultiply(shape.attentionHeadCount.toLong(), shape.headDim.toLong())
            else -> null
        }
        if (hidden == null) {
            val batch = checkedMultiply(batchSize.toLong(), 128L * 1_024L)
            val microBatch = checkedMultiply(microBatchSize.toLong(), 256L * 1_024L)
            if (batch is CheckedLong.Invalid) return RangeResult.Invalid(batch.reason)
            if (microBatch is CheckedLong.Invalid) return RangeResult.Invalid(microBatch.reason)
            val base = checkedAdd((batch as CheckedLong.Value).value, (microBatch as CheckedLong.Value).value)
            if (base is CheckedLong.Invalid) return RangeResult.Invalid(base.reason)
            base as CheckedLong.Value
            val likely = checkedMultiply(base.value, 2L)
            val high = checkedMultiply(base.value, 4L)
            return rangeFromChecked(base, likely, high, inferred = true)
        }
        if (hidden is CheckedLong.Invalid) return RangeResult.Invalid(hidden.reason)
        hidden as CheckedLong.Value
        val batchPayload = checkedMultiply(hidden.value, batchSize.toLong())
        val microBatchPayload = checkedProduct(hidden.value, microBatchSize.toLong(), 2L)
        if (batchPayload is CheckedLong.Invalid) return RangeResult.Invalid(batchPayload.reason)
        if (microBatchPayload is CheckedLong.Invalid) return RangeResult.Invalid(microBatchPayload.reason)
        val graphPayload = checkedAdd(
            (batchPayload as CheckedLong.Value).value,
            (microBatchPayload as CheckedLong.Value).value,
        )
        if (graphPayload is CheckedLong.Invalid) return RangeResult.Invalid(graphPayload.reason)
        val base = checkedAdd((graphPayload as CheckedLong.Value).value, 16L * MIB)
        if (base is CheckedLong.Invalid) return RangeResult.Invalid(base.reason)
        base as CheckedLong.Value
        val likely = multiplyRatioCeil(base.value, 3L, 2L)
        val high = checkedMultiply(base.value, 2L)
        return rangeFromChecked(base, likely, high)
    }

    private fun recurrentRange(descriptor: LlmModelDescriptor, plan: LlmRunPlan): RangeResult {
        val hybrid = descriptor.architecture?.lowercase()?.let {
            "mamba" in it || "rwkv" in it || "hybrid" in it
        } == true
        if (!hybrid) return RangeResult.Value(exactRange(0L))
        val shape = descriptor.transformerShape
        val layers = shape?.layerCount
        val hidden = shape?.hiddenSize
        if (layers == null || hidden == null) {
            val likely = checkedPercentage(descriptor.file.sizeBytes, 5)
            val high = checkedPercentage(descriptor.file.sizeBytes, 15)
            return rangeFromChecked(CheckedLong.Value(0L), likely, high, inferred = true)
        }
        val low = checkedProduct(layers.toLong(), hidden.toLong(), plan.sequenceCount.toLong(), 2L)
        if (low is CheckedLong.Invalid) return RangeResult.Invalid(low.reason)
        low as CheckedLong.Value
        val likely = checkedMultiply(low.value, 2L)
        val high = checkedMultiply(low.value, 4L)
        return rangeFromChecked(low, likely, high, inferred = true)
    }

    private fun singlePool(
        weights: Long,
        dynamic: EstimateRange,
        calibration: MemoryCalibration,
        pool: Pool,
    ): PoolResult {
        val total = when (val value = addRanges(exactRange(weights), dynamic)) {
            is RangeResult.Invalid -> return PoolResult.Invalid(value.reason)
            is RangeResult.Value -> value.range
        }
        val calibrated = when (val value = calibrate(total, calibration)) {
            is RangeResult.Invalid -> return PoolResult.Invalid(value.reason)
            is RangeResult.Value -> value.range
        }
        return PoolResult.Value(
            host = calibrated.takeIf { pool == Pool.HOST },
            gpu = null,
            shared = calibrated.takeIf { pool == Pool.SHARED },
            lowConfidence = false,
            evidence = emptyList(),
        )
    }

    private fun discretePools(
        weights: Long,
        layerCount: Int?,
        gpuLayerCount: Int?,
        runtime: EstimateRange,
        acceleratorDynamic: EstimateRange,
        allDynamic: EstimateRange,
        calibration: MemoryCalibration,
    ): PoolResult {
        if (layerCount == null || gpuLayerCount == null) {
            val half = weights / 2L
            val hostWeights = validRange(0L, half, weights)
            val gpuWeights = validRange(0L, weights - half, weights)
            val hostDynamic = validRange(runtime.lowBytes, runtime.likelyBytes, allDynamic.highBytes)
            val gpuDynamic = validRange(
                acceleratorDynamic.lowBytes,
                acceleratorDynamic.likelyBytes,
                allDynamic.highBytes,
            )
            return buildDiscreteResult(
                hostWeights,
                gpuWeights,
                hostDynamic,
                gpuDynamic,
                calibration,
                lowConfidence = true,
                evidence = listOf(
                    Evidence(
                        AssessmentReason.GPU_LAYER_SPLIT_UNKNOWN,
                        Confidence.LOW,
                        "native-preflight-required",
                    ),
                ),
            )
        }

        val overhead = when (val value = percentageRange(weights, 5, 10, 20)) {
            is RangeResult.Invalid -> return PoolResult.Invalid(value.reason)
            is RangeResult.Value -> value.range
        }
        val assignableLow = weights - overhead.highBytes
        val assignableLikely = weights - overhead.likelyBytes
        val assignableHigh = weights - overhead.lowBytes
        val gpuLow = multiplyRatioFloor(assignableLow, gpuLayerCount.toLong(), layerCount.toLong())
        val gpuLikely = multiplyRatioFloor(assignableLikely, gpuLayerCount.toLong(), layerCount.toLong())
        val gpuHigh = multiplyRatioFloor(assignableHigh, gpuLayerCount.toLong(), layerCount.toLong())
        val gpuWeights = when (val value = rangeFromChecked(gpuLow, gpuLikely, gpuHigh)) {
            is RangeResult.Invalid -> return PoolResult.Invalid(value.reason)
            is RangeResult.Value -> value.range
        }
        val hostWeights = validRange(
            lowBytes = weights - gpuWeights.highBytes,
            likelyBytes = weights - gpuWeights.likelyBytes,
            highBytes = weights - gpuWeights.lowBytes,
        )
        return buildDiscreteResult(
            hostWeights,
            gpuWeights,
            runtime,
            acceleratorDynamic,
            calibration,
            lowConfidence = false,
            evidence = emptyList(),
        )
    }

    private fun buildDiscreteResult(
        hostWeights: EstimateRange,
        gpuWeights: EstimateRange,
        runtime: EstimateRange,
        acceleratorDynamic: EstimateRange,
        calibration: MemoryCalibration,
        lowConfidence: Boolean,
        evidence: List<Evidence>,
    ): PoolResult {
        val host = when (val value = addRanges(hostWeights, runtime)) {
            is RangeResult.Invalid -> return PoolResult.Invalid(value.reason)
            is RangeResult.Value -> value.range
        }
        val gpu = when (val value = addRanges(gpuWeights, acceleratorDynamic)) {
            is RangeResult.Invalid -> return PoolResult.Invalid(value.reason)
            is RangeResult.Value -> value.range
        }
        val calibratedHost = when (val value = calibrate(host, calibration)) {
            is RangeResult.Invalid -> return PoolResult.Invalid(value.reason)
            is RangeResult.Value -> value.range
        }
        val calibratedGpu = when (val value = calibrate(gpu, calibration)) {
            is RangeResult.Invalid -> return PoolResult.Invalid(value.reason)
            is RangeResult.Value -> value.range
        }
        return PoolResult.Value(
            host = calibratedHost,
            gpu = calibratedGpu,
            shared = null,
            lowConfidence = lowConfidence,
            evidence = evidence,
        )
    }

    private fun calibrate(range: EstimateRange, calibration: MemoryCalibration): RangeResult =
        when (calibration) {
            MemoryCalibration.None -> RangeResult.Value(range)
            is MemoryCalibration.Correction -> rangeFromChecked(
                CheckedLong.Value(range.lowBytes),
                multiplyRatioCeil(range.likelyBytes, calibration.likelyNumerator, calibration.likelyDenominator),
                multiplyRatioCeil(range.highBytes, calibration.highNumerator, calibration.highDenominator),
            )
        }

    private fun invalidAssessment(
        plan: LlmRunPlan,
        storage: EstimateRange?,
        reason: AssessmentReason,
        additionalEvidence: Collection<Evidence> = emptyList(),
    ) = PlanAssessment(
        plan = plan,
        hostMemoryBytes = null,
        gpuMemoryBytes = null,
        sharedMemoryBytes = null,
        storageBytes = storage,
        confidence = confidence(Confidence.LOW, storage != null),
        evidence = additionalEvidence + Evidence(reason, Confidence.LOW),
    )

    private fun confidence(memory: Confidence, hasStorage: Boolean) = AssessmentConfidence(
        compatibility = Confidence.MEDIUM,
        memory = memory,
        storage = if (hasStorage) Confidence.HIGH else Confidence.LOW,
        performance = Confidence.LOW,
    )

    private fun missingShapeEvidence(component: String) = Evidence(
        AssessmentReason.MISSING_MODEL_SHAPE,
        Confidence.LOW,
        component,
    )
}

private enum class Pool { HOST, SHARED }

private sealed interface ComponentResult {
    data class Value(
        val runtime: EstimateRange,
        val acceleratorDynamic: EstimateRange,
        val allDynamic: EstimateRange,
        val lowConfidence: Boolean,
        val evidence: List<Evidence>,
    ) : ComponentResult

    data class Invalid(val reason: AssessmentReason) : ComponentResult
}

private sealed interface PoolResult {
    data class Value(
        val host: EstimateRange?,
        val gpu: EstimateRange?,
        val shared: EstimateRange?,
        val lowConfidence: Boolean,
        val evidence: List<Evidence>,
    ) : PoolResult

    data class Invalid(val reason: AssessmentReason) : PoolResult
}

private sealed interface RangeResult {
    data class Value(val range: EstimateRange, val inferred: Boolean = false) : RangeResult
    data class Invalid(val reason: AssessmentReason) : RangeResult
}

private fun bytesForElements(elements: Long, type: KvCacheType): CheckedLong {
    val (numerator, denominator) = when (type) {
        KvCacheType.F16 -> 2L to 1L
        KvCacheType.Q8_0 -> 34L to 32L
        KvCacheType.Q4_0 -> 18L to 32L
    }
    return multiplyRatioCeil(elements, numerator, denominator)
}

private fun checkedProduct(vararg factors: Long): CheckedLong {
    var product = 1L
    for (factor in factors) {
        when (val result = checkedMultiply(product, factor)) {
            is CheckedLong.Invalid -> return result
            is CheckedLong.Value -> product = result.value
        }
    }
    return CheckedLong.Value(product)
}

private fun percentageRange(value: Long, low: Int, likely: Int, high: Int): RangeResult =
    rangeFromChecked(
        checkedPercentage(value, low),
        checkedPercentage(value, likely),
        checkedPercentage(value, high),
    )

private fun addRanges(vararg ranges: EstimateRange): RangeResult {
    var low = 0L
    var likely = 0L
    var high = 0L
    for (range in ranges) {
        val nextLow = checkedAdd(low, range.lowBytes)
        val nextLikely = checkedAdd(likely, range.likelyBytes)
        val nextHigh = checkedAdd(high, range.highBytes)
        if (nextLow is CheckedLong.Invalid) return RangeResult.Invalid(nextLow.reason)
        if (nextLikely is CheckedLong.Invalid) return RangeResult.Invalid(nextLikely.reason)
        if (nextHigh is CheckedLong.Invalid) return RangeResult.Invalid(nextHigh.reason)
        low = (nextLow as CheckedLong.Value).value
        likely = (nextLikely as CheckedLong.Value).value
        high = (nextHigh as CheckedLong.Value).value
    }
    return RangeResult.Value(validRange(low, likely, high))
}

private fun rangeFromChecked(
    low: CheckedLong,
    likely: CheckedLong,
    high: CheckedLong,
    inferred: Boolean = false,
): RangeResult {
    if (low is CheckedLong.Invalid) return RangeResult.Invalid(low.reason)
    if (likely is CheckedLong.Invalid) return RangeResult.Invalid(likely.reason)
    if (high is CheckedLong.Invalid) return RangeResult.Invalid(high.reason)
    return when (
        val result = EstimateRange.create(
            (low as CheckedLong.Value).value,
            (likely as CheckedLong.Value).value,
            (high as CheckedLong.Value).value,
        )
    ) {
        is CheckedEstimateRange.Invalid -> RangeResult.Invalid(result.reason)
        is CheckedEstimateRange.Value -> RangeResult.Value(result.range, inferred)
    }
}

private fun multiplyRatioFloor(value: Long, numerator: Long, denominator: Long): CheckedLong {
    if (value < 0L || numerator < 0L || denominator <= 0L) {
        return CheckedLong.Invalid(AssessmentReason.INVALID_METADATA)
    }
    return when (val multiplied = checkedMultiply(value, numerator)) {
        is CheckedLong.Invalid -> multiplied
        is CheckedLong.Value -> CheckedLong.Value(multiplied.value / denominator)
    }
}

private fun multiplyRatioCeil(value: Long, numerator: Long, denominator: Long): CheckedLong {
    val multiplied = multiplyRatioFloorNumerator(value, numerator, denominator)
    if (multiplied is RatioNumerator.Invalid) return CheckedLong.Invalid(multiplied.reason)
    multiplied as RatioNumerator.Value
    val quotient = multiplied.value / denominator
    val remainder = multiplied.value % denominator
    return if (remainder == 0L) {
        CheckedLong.Value(quotient)
    } else {
        checkedAdd(quotient, 1L)
    }
}

private sealed interface RatioNumerator {
    data class Value(val value: Long) : RatioNumerator
    data class Invalid(val reason: AssessmentReason) : RatioNumerator
}

private fun multiplyRatioFloorNumerator(value: Long, numerator: Long, denominator: Long): RatioNumerator {
    if (value < 0L || numerator < 0L || denominator <= 0L) {
        return RatioNumerator.Invalid(AssessmentReason.INVALID_METADATA)
    }
    return when (val multiplied = checkedMultiply(value, numerator)) {
        is CheckedLong.Invalid -> RatioNumerator.Invalid(multiplied.reason)
        is CheckedLong.Value -> RatioNumerator.Value(multiplied.value)
    }
}

private fun exactRange(value: Long): EstimateRange = validRange(value, value, value)

private fun validRange(lowBytes: Long, likelyBytes: Long, highBytes: Long): EstimateRange =
    when (val checked = EstimateRange.create(lowBytes, likelyBytes, highBytes)) {
        is CheckedEstimateRange.Value -> checked.range
        is CheckedEstimateRange.Invalid -> error("Internal range invariant violated")
    }
