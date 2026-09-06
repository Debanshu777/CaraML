package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.rating.SdArchitecture
import com.debanshu777.huggingfacemanager.sdcpp.ComponentRole

private const val DIFFUSION_MIB = 1_048_576L

class DiffusionFootprintEstimator {
    fun estimate(
        descriptor: DiffusionModelDescriptor,
        plan: DiffusionRunPlan,
        calibration: MemoryCalibration,
    ): PlanAssessment {
        val weights = when (val result = validatedWeights(descriptor)) {
            is DiffusionWeightsResult.Invalid -> {
                return conservativeAssessment(plan, result.storageBytes, result.reason)
            }
            is DiffusionWeightsResult.Value -> result.weights
        }
        val storage = diffusionExactRange(weights.total)

        validatePlan(descriptor, plan)?.let { reason ->
            return conservativeAssessment(plan, storage, reason)
        }
        validateDiffusionCalibration(calibration)?.let { reason ->
            return conservativeAssessment(plan, storage, reason)
        }

        val architecture = descriptor.architecture?.takeUnless { it == SdArchitecture.UNKNOWN }
            ?: return conservativeAssessment(plan, storage, AssessmentReason.UNKNOWN_ARCHITECTURE)
        val coefficients = DiffusionArchitectureCoefficientsV1.forArchitecture(architecture)
            ?: return conservativeAssessment(plan, storage, AssessmentReason.UNKNOWN_ARCHITECTURE)
        val activations = when (val result = activationRanges(plan, coefficients)) {
            is DiffusionActivationResult.Invalid -> {
                return conservativeAssessment(plan, storage, result.reason)
            }
            is DiffusionActivationResult.Value -> result.activations
        }

        val allocation = when (val result = allocate(plan, weights, activations, calibration)) {
            is DiffusionAllocationResult.Invalid -> {
                return conservativeAssessment(
                    plan = plan,
                    storage = storage,
                    reason = result.reason,
                    additionalEvidence = result.evidence,
                )
            }
            is DiffusionAllocationResult.Value -> result
        }

        val evidence = buildList {
            add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "component-weights:primary:${weights.primary}"))
            add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "component-weights:vae:${weights.vae}"))
            add(
                Evidence(
                    AssessmentReason.METADATA_VALIDATED,
                    Confidence.HIGH,
                    "component-weights:conditioning:${weights.conditioning}",
                ),
            )
            add(
                Evidence(
                    AssessmentReason.METADATA_VALIDATED,
                    Confidence.MEDIUM,
                    "diffusion-coefficients:v1:${architecture.name}",
                ),
            )
            addAll(allocation.evidence)
        }
        return PlanAssessment(
            plan = plan,
            hostMemoryBytes = allocation.host,
            gpuMemoryBytes = allocation.gpu,
            sharedMemoryBytes = allocation.shared,
            storageBytes = storage,
            confidence = diffusionConfidence(if (allocation.lowConfidence) Confidence.LOW else Confidence.MEDIUM),
            evidence = evidence,
        )
    }

    private fun validatedWeights(descriptor: DiffusionModelDescriptor): DiffusionWeightsResult {
        if (
            descriptor.components.isEmpty() || descriptor.components.size > DescriptorLimits.MAX_COMPONENTS ||
            !descriptor.requiredComponentsPresent || descriptor.family.isBlank() ||
            descriptor.family.length > DescriptorLimits.MAX_METADATA_STRING_LENGTH ||
            descriptor.width?.let { it !in 1..DescriptorLimits.MAX_IMAGE_DIMENSION } == true ||
            descriptor.height?.let { it !in 1..DescriptorLimits.MAX_IMAGE_DIMENSION } == true
        ) {
            return DiffusionWeightsResult.Invalid(AssessmentReason.INVALID_METADATA, null)
        }

        val seen = mutableSetOf<String>()
        var total = 0L
        for (component in descriptor.components) {
            val file = component.file
            if (
                !file.hasValidExactIdentity() ||
                (component.isPrimary &&
                    (file.repositoryId != descriptor.repositoryId || file.revision != descriptor.revision)) ||
                !seen.add("${file.repositoryId}:${file.revision}:${file.path}")
            ) {
                return DiffusionWeightsResult.Invalid(AssessmentReason.INVALID_METADATA, null)
            }
            total = when (val result = checkedAdd(total, file.sizeBytes)) {
                is CheckedLong.Invalid -> return DiffusionWeightsResult.Invalid(result.reason, null)
                is CheckedLong.Value -> result.value
            }
            if (total > DescriptorLimits.MAX_BUNDLE_BYTES) {
                return DiffusionWeightsResult.Invalid(AssessmentReason.BUNDLE_SIZE_LIMIT_EXCEEDED, null)
            }
        }

        val storage = diffusionExactRange(total)
        if (
            descriptor.components.count { it.isPrimary } != 1 ||
            descriptor.components.any { it.isPrimary && it.role != null }
        ) {
            return DiffusionWeightsResult.Invalid(AssessmentReason.COMPONENT_ROLE_UNKNOWN, storage)
        }
        val seenAuxiliaryRoles = mutableSetOf<ComponentRole>()
        var primary = 0L
        var vae = 0L
        var conditioning = 0L
        for (component in descriptor.components) {
            val file = component.file
            val destination = when {
                component.isPrimary -> DiffusionWeightRole.PRIMARY
                component.role == ComponentRole.HIGH_NOISE_MODEL -> DiffusionWeightRole.PRIMARY
                component.role == ComponentRole.VAE -> DiffusionWeightRole.VAE
                component.role == null -> {
                    return DiffusionWeightsResult.Invalid(
                        AssessmentReason.COMPONENT_ROLE_UNKNOWN,
                        storage,
                    )
                }
                else -> DiffusionWeightRole.CONDITIONING
            }
            if (!component.isPrimary && component.role != null && !seenAuxiliaryRoles.add(component.role)) {
                return DiffusionWeightsResult.Invalid(AssessmentReason.INVALID_METADATA, storage)
            }
            when (destination) {
                DiffusionWeightRole.PRIMARY -> primary = when (val result = checkedAdd(primary, file.sizeBytes)) {
                    is CheckedLong.Invalid -> return DiffusionWeightsResult.Invalid(result.reason, null)
                    is CheckedLong.Value -> result.value
                }
                DiffusionWeightRole.VAE -> vae = when (val result = checkedAdd(vae, file.sizeBytes)) {
                    is CheckedLong.Invalid -> return DiffusionWeightsResult.Invalid(result.reason, null)
                    is CheckedLong.Value -> result.value
                }
                DiffusionWeightRole.CONDITIONING -> conditioning = when (val result = checkedAdd(conditioning, file.sizeBytes)) {
                    is CheckedLong.Invalid -> return DiffusionWeightsResult.Invalid(result.reason, null)
                    is CheckedLong.Value -> result.value
                }
            }
        }
        if (primary == 0L) {
            return DiffusionWeightsResult.Invalid(AssessmentReason.MISSING_REQUIRED_COMPONENT, storage)
        }
        return DiffusionWeightsResult.Value(DiffusionWeights(primary, vae, conditioning, total))
    }

    private fun validatePlan(
        descriptor: DiffusionModelDescriptor,
        plan: DiffusionRunPlan,
    ): AssessmentReason? {
        validateRunPlan(plan)?.let { return it }
        if (descriptor.mode != plan.mode) {
            return AssessmentReason.INVALID_WORKLOAD
        }
        return null
    }

    private fun activationRanges(
        plan: DiffusionRunPlan,
        coefficients: DiffusionArchitectureCoefficients,
    ): DiffusionActivationResult {
        val pixels = checkedMultiply(plan.width.toLong(), plan.height.toLong())
        if (pixels is CheckedLong.Invalid) return DiffusionActivationResult.Invalid(pixels.reason)
        val frameUnits = checkedMultiply((pixels as CheckedLong.Value).value, plan.frameCount.toLong())
        if (frameUnits is CheckedLong.Invalid) return DiffusionActivationResult.Invalid(frameUnits.reason)
        val batchUnits = checkedMultiply((frameUnits as CheckedLong.Value).value, plan.batchSize.toLong())
        if (batchUnits is CheckedLong.Invalid) return DiffusionActivationResult.Invalid(batchUnits.reason)
        batchUnits as CheckedLong.Value

        val payload = diffusionRangeFromChecked(
            checkedMultiply(batchUnits.value, coefficients.lowBytesPerUnit),
            checkedMultiply(batchUnits.value, coefficients.likelyBytesPerUnit),
            checkedMultiply(batchUnits.value, coefficients.highBytesPerUnit),
        )
        if (payload is DiffusionRangeResult.Invalid) return DiffusionActivationResult.Invalid(payload.reason)
        payload as DiffusionRangeResult.Value

        val layer = diffusionScaleRange(payload.range, 65L, 100L)
        val conditioning = diffusionScaleRange(payload.range, 15L, 100L)
        var vae = diffusionScaleRange(payload.range, 20L, 100L)
        if (layer is DiffusionRangeResult.Invalid) return DiffusionActivationResult.Invalid(layer.reason)
        if (conditioning is DiffusionRangeResult.Invalid) return DiffusionActivationResult.Invalid(conditioning.reason)
        if (vae is DiffusionRangeResult.Invalid) return DiffusionActivationResult.Invalid(vae.reason)
        if (plan.vaeTiling) {
            vae = diffusionRangeFromChecked(
                diffusionMultiplyRatio((vae as DiffusionRangeResult.Value).range.lowBytes, 1L, 2L),
                diffusionMultiplyRatio(vae.range.likelyBytes, 3L, 5L),
                diffusionMultiplyRatio(vae.range.highBytes, 3L, 4L),
            )
            if (vae is DiffusionRangeResult.Invalid) return DiffusionActivationResult.Invalid(vae.reason)
        }
        return DiffusionActivationResult.Value(
            DiffusionActivations(
                layer = (layer as DiffusionRangeResult.Value).range,
                conditioning = (conditioning as DiffusionRangeResult.Value).range,
                vae = (vae as DiffusionRangeResult.Value).range,
                backend = diffusionValidRange(64L * DIFFUSION_MIB, 128L * DIFFUSION_MIB, 256L * DIFFUSION_MIB),
            ),
        )
    }

    private fun allocate(
        plan: DiffusionRunPlan,
        weights: DiffusionWeights,
        activations: DiffusionActivations,
        calibration: MemoryCalibration,
    ): DiffusionAllocationResult {
        val allWeights = diffusionExactRange(weights.total)
        val allActivations = when (
            val result = diffusionAddRanges(
                activations.layer,
                activations.conditioning,
                activations.vae,
                activations.backend,
            )
        ) {
            is DiffusionRangeResult.Invalid -> return DiffusionAllocationResult.Invalid(result.reason)
            is DiffusionRangeResult.Value -> result.range
        }
        if (plan.backend == BackendKind.CPU) {
            return diffusionSinglePool(allWeights, allActivations, calibration, DiffusionPool.HOST)
        }
        if (plan.memoryTopology == MemoryTopology.UNIFIED) {
            return diffusionSinglePool(allWeights, allActivations, calibration, DiffusionPool.SHARED)
        }
        if (plan.memoryTopology != MemoryTopology.DISCRETE) {
            return DiffusionAllocationResult.Invalid(AssessmentReason.BACKEND_CAPABILITY_UNKNOWN)
        }
        return diffusionDiscretePools(plan, weights, activations, calibration)
    }

    private fun diffusionSinglePool(
        weights: EstimateRange,
        activations: EstimateRange,
        calibration: MemoryCalibration,
        pool: DiffusionPool,
    ): DiffusionAllocationResult {
        val total = when (val result = diffusionAddRanges(weights, activations)) {
            is DiffusionRangeResult.Invalid -> return DiffusionAllocationResult.Invalid(result.reason)
            is DiffusionRangeResult.Value -> result.range
        }
        val calibrated = when (val result = diffusionCalibrate(total, calibration)) {
            is DiffusionRangeResult.Invalid -> return DiffusionAllocationResult.Invalid(result.reason)
            is DiffusionRangeResult.Value -> result.range
        }
        return DiffusionAllocationResult.Value(
            host = calibrated.takeIf { pool == DiffusionPool.HOST },
            gpu = null,
            shared = calibrated.takeIf { pool == DiffusionPool.SHARED },
            lowConfidence = false,
            evidence = emptyList(),
        )
    }

    private fun diffusionDiscretePools(
        plan: DiffusionRunPlan,
        weights: DiffusionWeights,
        activations: DiffusionActivations,
        calibration: MemoryCalibration,
    ): DiffusionAllocationResult {
        val evidence = mutableListOf(
            Evidence(
                AssessmentReason.METADATA_VALIDATED,
                Confidence.MEDIUM,
                "host-runtime:diffusion-v1",
            ),
        )
        val hostRuntime = when (val result = diffusionHostRuntimeRange(weights.total)) {
            is DiffusionRangeResult.Invalid -> return DiffusionAllocationResult.Invalid(result.reason, evidence)
            is DiffusionRangeResult.Value -> result.range
        }
        val hostNonPrimary = diffusionAddRanges(
            hostRuntime,
            if (plan.offloadToCpu || plan.keepClipOnCpu) {
                diffusionExactRange(weights.conditioning)
            } else {
                diffusionExactRange(0L)
            },
            if (plan.offloadToCpu || plan.keepVaeOnCpu) {
                diffusionExactRange(weights.vae)
            } else {
                diffusionExactRange(0L)
            },
            if (plan.keepClipOnCpu) activations.conditioning else diffusionExactRange(0L),
            if (plan.keepVaeOnCpu) activations.vae else diffusionExactRange(0L),
        )
        if (hostNonPrimary is DiffusionRangeResult.Invalid) {
            return DiffusionAllocationResult.Invalid(hostNonPrimary.reason)
        }
        hostNonPrimary as DiffusionRangeResult.Value

        val gpuFixed = diffusionAddRanges(
            if (plan.offloadToCpu || plan.keepClipOnCpu) {
                diffusionExactRange(0L)
            } else {
                diffusionExactRange(weights.conditioning)
            },
            if (plan.offloadToCpu || plan.keepVaeOnCpu) {
                diffusionExactRange(0L)
            } else {
                diffusionExactRange(weights.vae)
            },
            activations.layer,
            if (plan.keepClipOnCpu) diffusionExactRange(0L) else activations.conditioning,
            if (plan.keepVaeOnCpu) diffusionExactRange(0L) else activations.vae,
            activations.backend,
        )
        if (gpuFixed is DiffusionRangeResult.Invalid) return DiffusionAllocationResult.Invalid(gpuFixed.reason)
        gpuFixed as DiffusionRangeResult.Value

        var lowConfidence = false
        val primaryAllocation = when {
            plan.layerStreaming -> {
                lowConfidence = true
                evidence += Evidence(
                    AssessmentReason.GPU_ALLOCATION_UNKNOWN,
                    Confidence.LOW,
                    "layer-residency:diffusion-v1",
                )
                val gpu = when (val result = streamingPrimaryRange(weights.primary)) {
                    is DiffusionRangeResult.Invalid -> return DiffusionAllocationResult.Invalid(result.reason)
                    is DiffusionRangeResult.Value -> result.range
                }
                PrimaryAllocation(host = diffusionExactRange(weights.primary), gpu = gpu)
            }
            plan.offloadToCpu -> PrimaryAllocation(
                host = diffusionExactRange(weights.primary),
                gpu = diffusionExactRange(0L),
            )
            else -> PrimaryAllocation(
                host = diffusionExactRange(0L),
                gpu = diffusionExactRange(weights.primary),
            )
        }

        var hostPrimary = primaryAllocation.host
        var gpuPrimary = primaryAllocation.gpu
        if (plan.maxVramBytes != null) {
            val cap = plan.maxVramBytes
            if (gpuFixed.range.highBytes > cap) {
                return DiffusionAllocationResult.Invalid(
                    AssessmentReason.GPU_ALLOCATION_UNKNOWN,
                    evidence + Evidence(AssessmentReason.GPU_ALLOCATION_UNKNOWN, Confidence.LOW, "max-vram:fixed-floor"),
                )
            }
            val safePrimaryHigh = minOf(gpuPrimary.highBytes, cap - gpuFixed.range.highBytes)
            val safePrimaryLikely = minOf(gpuPrimary.likelyBytes, safePrimaryHigh)
            val safePrimaryLow = minOf(gpuPrimary.lowBytes, safePrimaryLikely)
            gpuPrimary = diffusionValidRange(safePrimaryLow, safePrimaryLikely, safePrimaryHigh)
            hostPrimary = if (plan.offloadToCpu || plan.layerStreaming) {
                diffusionExactRange(weights.primary)
            } else {
                diffusionValidRange(
                    weights.primary - safePrimaryHigh,
                    weights.primary - safePrimaryLikely,
                    weights.primary,
                )
            }
            lowConfidence = true
            evidence += Evidence(
                AssessmentReason.GPU_ALLOCATION_UNKNOWN,
                Confidence.LOW,
                "max-vram:native-fit-pending",
            )
        }

        val host = when (val result = diffusionAddRanges(hostNonPrimary.range, hostPrimary)) {
            is DiffusionRangeResult.Invalid -> return DiffusionAllocationResult.Invalid(result.reason, evidence)
            is DiffusionRangeResult.Value -> result.range
        }
        val gpu = when (val result = diffusionAddRanges(gpuFixed.range, gpuPrimary)) {
            is DiffusionRangeResult.Invalid -> return DiffusionAllocationResult.Invalid(result.reason, evidence)
            is DiffusionRangeResult.Value -> result.range
        }
        val calibratedHost = when (val result = diffusionCalibrate(host, calibration)) {
            is DiffusionRangeResult.Invalid -> return DiffusionAllocationResult.Invalid(result.reason, evidence)
            is DiffusionRangeResult.Value -> result.range
        }
        val calibratedGpu = when (val result = diffusionCalibrate(gpu, calibration)) {
            is DiffusionRangeResult.Invalid -> return DiffusionAllocationResult.Invalid(result.reason, evidence)
            is DiffusionRangeResult.Value -> result.range
        }
        return DiffusionAllocationResult.Value(
            host = calibratedHost,
            gpu = calibratedGpu,
            shared = null,
            lowConfidence = lowConfidence,
            evidence = evidence,
        )
    }

    private fun conservativeAssessment(
        plan: DiffusionRunPlan,
        storage: EstimateRange?,
        reason: AssessmentReason,
        additionalEvidence: Collection<Evidence> = emptyList(),
    ) = PlanAssessment(
        plan = plan,
        hostMemoryBytes = null,
        gpuMemoryBytes = null,
        sharedMemoryBytes = null,
        storageBytes = storage,
        confidence = diffusionConfidence(Confidence.LOW, storage != null),
        evidence = additionalEvidence + Evidence(reason, Confidence.LOW),
    )
}

private data class DiffusionArchitectureCoefficients(
    val lowBytesPerUnit: Long,
    val likelyBytesPerUnit: Long,
    val highBytesPerUnit: Long,
)

private object DiffusionArchitectureCoefficientsV1 {
    private val values = mapOf(
        SdArchitecture.SD1 to DiffusionArchitectureCoefficients(192L, 256L, 384L),
        SdArchitecture.SDXL to DiffusionArchitectureCoefficients(320L, 448L, 640L),
        SdArchitecture.SD3 to DiffusionArchitectureCoefficients(384L, 576L, 832L),
        SdArchitecture.FLUX to DiffusionArchitectureCoefficients(512L, 768L, 1_024L),
        SdArchitecture.WAN_SMALL to DiffusionArchitectureCoefficients(256L, 384L, 640L),
        SdArchitecture.WAN_LARGE to DiffusionArchitectureCoefficients(448L, 640L, 960L),
    )

    fun forArchitecture(architecture: SdArchitecture): DiffusionArchitectureCoefficients? = values[architecture]
}

private data class DiffusionWeights(
    val primary: Long,
    val vae: Long,
    val conditioning: Long,
    val total: Long,
)

private sealed interface DiffusionWeightsResult {
    data class Value(val weights: DiffusionWeights) : DiffusionWeightsResult
    data class Invalid(val reason: AssessmentReason, val storageBytes: EstimateRange?) : DiffusionWeightsResult
}

private enum class DiffusionWeightRole { PRIMARY, VAE, CONDITIONING }

private data class DiffusionActivations(
    val layer: EstimateRange,
    val conditioning: EstimateRange,
    val vae: EstimateRange,
    val backend: EstimateRange,
)

private sealed interface DiffusionActivationResult {
    data class Value(val activations: DiffusionActivations) : DiffusionActivationResult
    data class Invalid(val reason: AssessmentReason) : DiffusionActivationResult
}

private data class PrimaryAllocation(
    val host: EstimateRange,
    val gpu: EstimateRange,
)

private enum class DiffusionPool { HOST, SHARED }

private sealed interface DiffusionAllocationResult {
    data class Value(
        val host: EstimateRange?,
        val gpu: EstimateRange?,
        val shared: EstimateRange?,
        val lowConfidence: Boolean,
        val evidence: List<Evidence>,
    ) : DiffusionAllocationResult

    data class Invalid(
        val reason: AssessmentReason,
        val evidence: List<Evidence> = emptyList(),
    ) : DiffusionAllocationResult
}

private sealed interface DiffusionRangeResult {
    data class Value(val range: EstimateRange) : DiffusionRangeResult
    data class Invalid(val reason: AssessmentReason) : DiffusionRangeResult
}

private fun streamingPrimaryRange(weights: Long): DiffusionRangeResult = diffusionRangeFromChecked(
    diffusionMultiplyRatio(weights, 40L, 100L),
    diffusionMultiplyRatio(weights, 48L, 100L),
    diffusionMultiplyRatio(weights, 60L, 100L),
)

private fun diffusionHostRuntimeRange(totalWeights: Long): DiffusionRangeResult = diffusionRangeFromChecked(
    CheckedLong.Value(64L * DIFFUSION_MIB),
    CheckedLong.Value(128L * DIFFUSION_MIB),
    checkedAdd(totalWeights, 256L * DIFFUSION_MIB),
)

private fun diffusionAddRanges(vararg ranges: EstimateRange): DiffusionRangeResult {
    var low = 0L
    var likely = 0L
    var high = 0L
    for (range in ranges) {
        val nextLow = checkedAdd(low, range.lowBytes)
        val nextLikely = checkedAdd(likely, range.likelyBytes)
        val nextHigh = checkedAdd(high, range.highBytes)
        if (nextLow is CheckedLong.Invalid) return DiffusionRangeResult.Invalid(nextLow.reason)
        if (nextLikely is CheckedLong.Invalid) return DiffusionRangeResult.Invalid(nextLikely.reason)
        if (nextHigh is CheckedLong.Invalid) return DiffusionRangeResult.Invalid(nextHigh.reason)
        low = (nextLow as CheckedLong.Value).value
        likely = (nextLikely as CheckedLong.Value).value
        high = (nextHigh as CheckedLong.Value).value
    }
    return DiffusionRangeResult.Value(diffusionValidRange(low, likely, high))
}

private fun diffusionScaleRange(range: EstimateRange, numerator: Long, denominator: Long): DiffusionRangeResult =
    diffusionRangeFromChecked(
        diffusionMultiplyRatio(range.lowBytes, numerator, denominator),
        diffusionMultiplyRatio(range.likelyBytes, numerator, denominator),
        diffusionMultiplyRatio(range.highBytes, numerator, denominator),
    )

private fun diffusionCalibrate(range: EstimateRange, calibration: MemoryCalibration): DiffusionRangeResult = when (calibration) {
    MemoryCalibration.None -> DiffusionRangeResult.Value(range)
    is MemoryCalibration.Correction -> diffusionRangeFromChecked(
        CheckedLong.Value(range.lowBytes),
        diffusionMultiplyRatio(range.likelyBytes, calibration.likelyNumerator, calibration.likelyDenominator),
        diffusionMultiplyRatio(range.highBytes, calibration.highNumerator, calibration.highDenominator),
    )
}

private fun validateDiffusionCalibration(calibration: MemoryCalibration): AssessmentReason? {
    if (calibration == MemoryCalibration.None) return null
    calibration as MemoryCalibration.Correction
    if (
        calibration.likelyNumerator <= 0L || calibration.likelyDenominator <= 0L ||
        calibration.highNumerator <= 0L || calibration.highDenominator <= 0L ||
        calibration.likelyNumerator < calibration.likelyDenominator ||
        calibration.highNumerator < calibration.highDenominator
    ) {
        return AssessmentReason.INVALID_ESTIMATE_RANGE
    }
    val left = checkedMultiply(calibration.highNumerator, calibration.likelyDenominator)
    val right = checkedMultiply(calibration.likelyNumerator, calibration.highDenominator)
    return when {
        left is CheckedLong.Invalid || right is CheckedLong.Invalid -> AssessmentReason.ARITHMETIC_OVERFLOW
        (left as CheckedLong.Value).value < (right as CheckedLong.Value).value ->
            AssessmentReason.INVALID_ESTIMATE_RANGE
        else -> null
    }
}

private fun diffusionMultiplyRatio(value: Long, numerator: Long, denominator: Long): CheckedLong {
    if (value < 0L || numerator < 0L || denominator <= 0L) {
        return CheckedLong.Invalid(AssessmentReason.INVALID_METADATA)
    }
    val whole = value / denominator
    val remainder = value % denominator
    val wholeProduct = checkedMultiply(whole, numerator)
    if (wholeProduct is CheckedLong.Invalid) return wholeProduct
    val remainderProduct = checkedMultiply(remainder, numerator)
    if (remainderProduct is CheckedLong.Invalid) return remainderProduct
    val remainderQuotient = (remainderProduct as CheckedLong.Value).value / denominator
    return checkedAdd((wholeProduct as CheckedLong.Value).value, remainderQuotient)
}

private fun diffusionRangeFromChecked(
    low: CheckedLong,
    likely: CheckedLong,
    high: CheckedLong,
): DiffusionRangeResult {
    if (low is CheckedLong.Invalid) return DiffusionRangeResult.Invalid(low.reason)
    if (likely is CheckedLong.Invalid) return DiffusionRangeResult.Invalid(likely.reason)
    if (high is CheckedLong.Invalid) return DiffusionRangeResult.Invalid(high.reason)
    return when (
        val result = EstimateRange.create(
            (low as CheckedLong.Value).value,
            (likely as CheckedLong.Value).value,
            (high as CheckedLong.Value).value,
        )
    ) {
        is CheckedEstimateRange.Invalid -> DiffusionRangeResult.Invalid(result.reason)
        is CheckedEstimateRange.Value -> DiffusionRangeResult.Value(result.range)
    }
}

private fun diffusionExactRange(value: Long): EstimateRange = diffusionValidRange(value, value, value)

private fun diffusionValidRange(low: Long, likely: Long, high: Long): EstimateRange =
    when (val result = EstimateRange.create(low, likely, high)) {
        is CheckedEstimateRange.Invalid -> error("Internal diffusion range invariant violated")
        is CheckedEstimateRange.Value -> result.range
    }

private fun diffusionConfidence(memory: Confidence, storageAvailable: Boolean = true) = AssessmentConfidence(
    compatibility = Confidence.MEDIUM,
    memory = memory,
    storage = if (storageAvailable) Confidence.HIGH else Confidence.LOW,
    performance = Confidence.LOW,
)
