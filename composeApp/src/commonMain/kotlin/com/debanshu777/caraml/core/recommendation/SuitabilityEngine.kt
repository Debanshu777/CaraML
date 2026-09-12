package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.BackendStatus
import com.debanshu777.caraml.core.platform.DeviceSnapshot
import com.debanshu777.caraml.core.platform.HardwareProfile
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.platform.ResourceSnapshot
import com.debanshu777.caraml.core.platform.computeBaseBudget
import com.debanshu777.caraml.core.platform.computeStorageBudget
import kotlin.math.exp
import kotlin.math.ceil
import kotlin.math.ln

class SuitabilityEngine(
    private val compatibilityChecker: CompatibilityChecker,
    private val calibrationSource: CalibrationSource,
    private val runPlanGenerator: RunPlanGenerator = RunPlanGenerator(),
    private val llmFootprintEstimator: LlmFootprintEstimator = LlmFootprintEstimator(),
    private val diffusionFootprintEstimator: DiffusionFootprintEstimator = DiffusionFootprintEstimator(),
    private val performanceEstimator: PerformanceEstimator = PerformanceEstimator(),
) {
    fun assessPlans(
        descriptor: ModelDescriptor,
        hardwareProfile: HardwareProfile,
        workload: WorkloadConfig,
    ): AssessedPlans {
        val compatibility = compatibilityChecker.check(descriptor, hardwareProfile)
        return assessPlansWithCompatibility(descriptor, hardwareProfile, workload, compatibility)
    }

    fun reassessPlans(
        descriptor: ModelDescriptor,
        hardwareProfile: HardwareProfile,
        workload: WorkloadConfig,
        previous: AssessedPlans,
    ): AssessedPlans {
        val key = assessmentKey(descriptor)
        if (previous.assessmentKey != key || previous.memoryTopology != hardwareProfile.memoryTopology) {
            val evidence = listOf(Evidence(AssessmentReason.INVALID_METADATA, Confidence.LOW))
            return AssessedPlans(
                values = emptyList(),
                assessmentKey = key,
                compatibility = Compatibility.Unknown(listOf(AssessmentReason.INVALID_METADATA), evidence),
                memoryTopology = hardwareProfile.memoryTopology,
                reasons = listOf(AssessmentReason.INVALID_METADATA),
                evidence = evidence,
            )
        }
        val cachedPlans = previous.values.map { it.plan as? RunPlan ?: return invalidPlans(
            key,
            previous.compatibility,
            hardwareProfile.memoryTopology,
        ) }
        return assessPlansWithCompatibility(
            descriptor,
            hardwareProfile,
            workload,
            previous.compatibility,
            cachedPlans,
        )
    }

    private fun assessPlansWithCompatibility(
        descriptor: ModelDescriptor,
        hardwareProfile: HardwareProfile,
        workload: WorkloadConfig,
        compatibility: Compatibility,
        cachedPlans: List<RunPlan>? = null,
    ): AssessedPlans {
        val key = assessmentKey(descriptor)
        if (compatibility != Compatibility.Compatible) {
            return AssessedPlans(
                values = emptyList(),
                assessmentKey = key,
                compatibility = compatibility,
                memoryTopology = hardwareProfile.memoryTopology,
                reasons = compatibilityReasons(compatibility),
                evidence = compatibilityEvidence(compatibility),
            )
        }

        val candidates: List<RunPlan> = cachedPlans ?: run {
            val settings = planningSettings(descriptor, hardwareProfile, workload)
                ?: return invalidPlans(key, compatibility, hardwareProfile.memoryTopology)
            when {
                descriptor is LlmModelDescriptor && workload is LlmWorkloadConfig ->
                    runPlanGenerator.llmCandidates(descriptor, workload, settings)
                descriptor is DiffusionModelDescriptor && workload is DiffusionWorkloadConfig ->
                    runPlanGenerator.diffusionCandidates(descriptor, workload, settings)
                else -> emptyList()
            }
        }
        if (candidates.isEmpty()) {
            return invalidPlans(key, compatibility, hardwareProfile.memoryTopology)
        }

        val assessments = candidates.map { plan ->
            val footprint = when {
                descriptor is LlmModelDescriptor && plan is LlmRunPlan ->
                    llmFootprintEstimator.estimate(descriptor, plan, memoryCalibration(descriptor, plan))
                descriptor is DiffusionModelDescriptor && plan is DiffusionRunPlan ->
                    diffusionFootprintEstimator.estimate(descriptor, plan, memoryCalibration(descriptor, plan))
                else -> invalidPlanAssessment(plan)
            }
            val performance = performanceEstimator.estimate(descriptor, plan, hardwareProfile, calibrationSource)
            val utility = utilityMetrics(descriptor, plan, workload, footprint)
            PlanAssessment(
                plan = footprint.plan,
                hostMemoryBytes = footprint.hostMemoryBytes,
                gpuMemoryBytes = footprint.gpuMemoryBytes,
                sharedMemoryBytes = footprint.sharedMemoryBytes,
                storageBytes = footprint.storageBytes,
                confidence = footprint.confidence.copy(performance = performanceConfidence(performance)),
                evidence = footprint.evidence + performance.evidence + utility.evidence +
                    Evidence(AssessmentReason.ENERGY_NOT_VERIFIED, Confidence.LOW, "energy-metric-omitted"),
                performance = performance,
                utilityMetrics = utility.metrics,
                rawMemoryByPhase = footprint.rawMemoryByPhase,
            )
        }
        return AssessedPlans(
            values = assessments,
            assessmentKey = key,
            compatibility = compatibility,
            memoryTopology = hardwareProfile.memoryTopology,
            reasons = emptyList(),
            evidence = descriptor.evidence + hardwareProfile.evidence,
        )
    }

    fun assemble(assessedPlans: AssessedPlans, snapshot: DeviceSnapshot): ModelAssessment = assemble(
        assessedPlans = assessedPlans,
        hostBudget = snapshot.baseHostBudgetBytes,
        gpuBudget = snapshot.baseGpuBudgetBytes,
        sharedBudget = snapshot.baseSharedBudgetBytes,
        storageBudget = snapshot.baseStorageBudgetBytes,
        snapshotEvidence = snapshot.evidence,
    )

    fun assemble(assessedPlans: AssessedPlans, resourceSnapshot: ResourceSnapshot): ModelAssessment {
        val hostBudget = resourceSnapshot.additionalAllocatableHostBytes?.let { allocatable ->
            resourceSnapshot.platformMinimumReserveHostBytes?.let { minimum ->
                computeBaseBudget(
                    allocatable = allocatable,
                    osThreshold = resourceSnapshot.osPressureReserveHostBytes ?: 0L,
                    noiseP95 = resourceSnapshot.observedAppFootprintNoiseP95Bytes ?: 0L,
                    minimum = minimum,
                )
            }
        }
        val gpuBudget = resourceSnapshot.additionalAllocatableGpuBytes?.takeIf { it >= 0L }
        val (baseHost, baseGpu, baseShared) = when (assessedPlans.memoryTopology) {
            MemoryTopology.UNIFIED -> Triple(null, null, minimumKnown(hostBudget, gpuBudget))
            MemoryTopology.DISCRETE -> Triple(hostBudget, gpuBudget, null)
            MemoryTopology.UNKNOWN -> Triple(hostBudget, null, null)
        }
        val storageBudget = resourceSnapshot.freeStorageBytes
            ?.takeIf { it >= 0L }
            ?.let(::computeStorageBudget)
        return assemble(
            assessedPlans,
            baseHost,
            baseGpu,
            baseShared,
            storageBudget,
            resourceSnapshot.evidence,
        )
    }

    private fun assemble(
        assessedPlans: AssessedPlans,
        hostBudget: Long?,
        gpuBudget: Long?,
        sharedBudget: Long?,
        storageBudget: Long?,
        snapshotEvidence: Collection<Evidence>,
    ): ModelAssessment {
        val aggregateConfidence = aggregateConfidence(assessedPlans)
        return ModelAssessment(
            assessmentKey = assessedPlans.assessmentKey,
            compatibility = assessedPlans.compatibility,
            planAssessments = assessedPlans,
            baseHostBudgetBytes = hostBudget,
            baseGpuBudgetBytes = gpuBudget,
            baseSharedBudgetBytes = sharedBudget,
            baseStorageBudgetBytes = storageBudget,
            confidence = aggregateConfidence,
            evidence = assessedPlans.evidence + snapshotEvidence,
        )
    }

    private fun planningSettings(
        descriptor: ModelDescriptor,
        hardware: HardwareProfile,
        workload: WorkloadConfig,
    ): PlanningSettings? {
        val backend = hardware.backends.firstOrNull {
            it.status == BackendStatus.AVAILABLE && it.kind != BackendKind.CPU
        }?.kind ?: hardware.backends.firstOrNull {
            it.status == BackendStatus.AVAILABLE && it.kind == BackendKind.CPU
        }?.kind ?: return null
        return when {
            descriptor is LlmModelDescriptor && workload is LlmWorkloadConfig -> PlanningSettings(
                engineMaxContextTokens = maxOf(workload.contextTokens, descriptor.contextLimit ?: workload.contextTokens),
                engineMaxBatchSize = workload.batchSize,
                engineMaxMicroBatchSize = workload.microBatchSize,
                engineMaxSequenceCount = workload.sequenceCount,
                allowContextFallback = workload.allowContextFallback,
                allowBatchFallback = workload.allowBatchFallback,
                allowKvCacheFallback = workload.allowKvCacheFallback,
                allowedKvCacheTypes = workload.allowedKvCacheTypes,
                backend = backend,
                memoryTopology = hardware.memoryTopology,
                gpuLayerCount = if (backend == BackendKind.CPU) 0 else null,
            )
            descriptor is DiffusionModelDescriptor && workload is DiffusionWorkloadConfig -> PlanningSettings(
                engineMaxContextTokens = DescriptorLimits.MAX_CONTEXT_TOKENS,
                engineMaxBatchSize = workload.batchSize,
                engineMaxMicroBatchSize = workload.batchSize,
                engineMaxSequenceCount = 1,
                allowContextFallback = false,
                allowBatchFallback = false,
                allowKvCacheFallback = false,
                allowedKvCacheTypes = emptyList(),
                backend = backend,
                memoryTopology = hardware.memoryTopology,
                gpuLayerCount = null,
                engineImageDimensionMultiple = WorkloadLimits.DEFAULT_IMAGE_DIMENSION_MULTIPLE,
                engineMaxImageDimension = maxOf(workload.width, workload.height),
                engineMaxDiffusionFrames = workload.frameCount,
                engineMaxDiffusionSteps = workload.steps,
                supportsVaeTiling = workload.vaeTiling,
                supportsMaxVram = workload.maxVramBytes != null,
                supportsLayerStreaming = workload.layerStreaming,
                maxVramBytes = workload.maxVramBytes,
            )
            else -> null
        }
    }

    private fun utilityMetrics(
        descriptor: ModelDescriptor,
        plan: RunPlan,
        workload: WorkloadConfig,
        footprint: PlanAssessment,
    ): UtilityAssessment {
        val quality = qualityProxy(descriptor)
        val context = when {
            plan is LlmRunPlan && workload is LlmWorkloadConfig ->
                safeRatio(plan.contextTokens.toDouble(), workload.contextTokens.toDouble())
            else -> null
        }
        val storageEfficiency = footprint.storageBytes?.highBytes?.takeIf { it > 0L }?.let {
            safeRatio(RecommendationPolicyV1.STORAGE_EFFICIENCY_TARGET_BYTES.toDouble(), it.toDouble())
        }
        val evidence = if (quality.value == null) {
            listOf(Evidence(AssessmentReason.QUALITY_NOT_VERIFIED, Confidence.LOW, "quality-proxy-unavailable"))
        } else {
            listOf(
                Evidence(
                    AssessmentReason.QUALITY_PROXY_USED,
                    Confidence.MEDIUM,
                    "quality-proxy:${quality.components.joinToString("+")};not-benchmark",
                ),
            )
        }
        return UtilityAssessment(
            PlanUtilityMetrics(null, null, quality.value, context, storageEfficiency),
            evidence,
        )
    }

    private fun qualityProxy(descriptor: ModelDescriptor): QualityProxy = when (descriptor) {
        is LlmModelDescriptor -> {
            val weights = RecommendationPolicyV1.llmQualityProxyWeights
            val values = buildList {
                val quantization = (descriptor.quantization as? QuantizationEvidence.Known)
                    ?.quantization
                    ?.let(RecommendationPolicyV1.llmQuantizationQualityProxy::get)
                if (quantization != null) add(WeightedProxy(quantization, weights.quantization, "llm-quantization"))
                val parameterScale = descriptor.parameterCount
                    ?.takeIf { it in 1..DescriptorLimits.MAX_PARAMETERS }
                    ?.let {
                        safeRatio(
                            it.toDouble(),
                            RecommendationPolicyV1.LLM_PARAMETER_QUALITY_TARGET.toDouble(),
                        )
                    }
                if (parameterScale != null) add(WeightedProxy(parameterScale, weights.scale, "parameter-scale"))
            }
            combinedQualityProxy(values)
        }
        is DiffusionModelDescriptor -> {
            val weights = RecommendationPolicyV1.diffusionQualityProxyWeights
            val values = buildList {
                RecommendationPolicyV1.diffusionArchitectureQualityProxy[descriptor.architecture]?.let {
                    add(WeightedProxy(it, weights.scale, "diffusion-architecture"))
                }
                val quantizations = descriptor.quantizationDistribution.map {
                    RecommendationPolicyV1.diffusionQuantizationQualityProxy[it]
                }
                if (quantizations.isNotEmpty() && quantizations.none { it == null }) {
                    val normalized = quantizations.filterNotNull()
                    val geometric = exp(normalized.sumOf { ln(it) } / normalized.size.toDouble())
                    add(WeightedProxy(geometric, weights.quantization, "diffusion-quantization"))
                }
            }
            combinedQualityProxy(values)
        }
    }

    private fun combinedQualityProxy(values: List<WeightedProxy>): QualityProxy {
        val usable = values.filter {
            it.value.isFinite() && it.value > 0.0 && it.weight.isFinite() && it.weight > 0.0
        }
        val weightSum = usable.sumOf { it.weight }
        if (!weightSum.isFinite() || weightSum <= 0.0) return QualityProxy(null, emptyList())
        val value = exp(
            usable.sumOf {
                it.weight * ln(
                    it.value.coerceIn(
                        RecommendationPolicyV1.UTILITY_METRIC_MIN,
                        RecommendationPolicyV1.UTILITY_METRIC_MAX,
                    ),
                )
            } / weightSum,
        )
        return QualityProxy(value.takeIf { it.isFinite() }, usable.map { it.component })
    }

    private fun invalidPlans(
        key: String,
        compatibility: Compatibility,
        topology: MemoryTopology,
    ) = AssessedPlans(
        values = emptyList(),
        assessmentKey = key,
        compatibility = compatibility,
        memoryTopology = topology,
        reasons = listOf(AssessmentReason.INVALID_WORKLOAD),
        evidence = listOf(Evidence(AssessmentReason.INVALID_WORKLOAD, Confidence.LOW)),
    )

    private fun invalidPlanAssessment(plan: RunPlan) = PlanAssessment(
        plan = plan,
        hostMemoryBytes = null,
        gpuMemoryBytes = null,
        sharedMemoryBytes = null,
        storageBytes = null,
        confidence = AssessmentConfidence(Confidence.LOW, Confidence.LOW, Confidence.LOW, Confidence.LOW),
        evidence = listOf(Evidence(AssessmentReason.INVALID_WORKLOAD, Confidence.LOW)),
    )

    private fun aggregateConfidence(plans: AssessedPlans): AssessmentConfidence {
        if (plans.values.isEmpty()) {
            val compatibility = if (plans.compatibility == Compatibility.Compatible) Confidence.HIGH else Confidence.LOW
            return AssessmentConfidence(compatibility, Confidence.LOW, Confidence.LOW, Confidence.LOW)
        }
        return AssessmentConfidence(
            compatibility = Confidence.HIGH,
            memory = plans.values.minOf { it.confidence.memory.ordinal }.let(Confidence.entries::get),
            storage = plans.values.minOf { it.confidence.storage.ordinal }.let(Confidence.entries::get),
            performance = plans.values.minOf { it.confidence.performance.ordinal }.let(Confidence.entries::get),
        )
    }

    private fun performanceConfidence(value: PerformanceEstimate): Confidence = when (value) {
        is PerformanceEstimate.Unknown -> Confidence.LOW
        is PerformanceEstimate.Llm -> value.decodeTokensPerSecond.confidence
        is PerformanceEstimate.DiffusionImage -> value.referenceTotalTimeSeconds.confidence
        is PerformanceEstimate.DiffusionVideo -> value.totalTimeSeconds.confidence
    }

    private fun assessmentKey(descriptor: ModelDescriptor): String = when (descriptor) {
        is LlmModelDescriptor -> buildString {
            append(descriptor.repositoryId).append('@').append(descriptor.revision).append(':')
            descriptor.files.map { it.path }.sorted().joinTo(this, separator = "+")
        }
        is DiffusionModelDescriptor -> buildString {
            append(descriptor.repositoryId).append('@').append(descriptor.revision).append(':')
            descriptor.components.map { it.file.path }.sorted().joinTo(this, separator = "+")
        }
    }

    private fun compatibilityReasons(value: Compatibility): List<AssessmentReason> = when (value) {
        Compatibility.Compatible -> emptyList()
        is Compatibility.Incompatible -> value.reasons
        is Compatibility.Unknown -> value.reasons
    }

    private fun compatibilityEvidence(value: Compatibility): List<Evidence> = when (value) {
        Compatibility.Compatible -> emptyList()
        is Compatibility.Incompatible -> value.evidence
        is Compatibility.Unknown -> value.evidence
    }

    private fun minimumKnown(first: Long?, second: Long?): Long? = when {
        first != null && second != null -> minOf(first, second)
        first != null -> first
        else -> second
    }

    private fun memoryCalibration(descriptor: ModelDescriptor, plan: RunPlan): MemoryCalibration {
        val engineVersion = calibrationSource.engineVersion() ?: return MemoryCalibration.None
        val identity = ObservationModelIdentity.fromDescriptor(descriptor) ?: return MemoryCalibration.None
        val pools = when {
            plan.backend == BackendKind.CPU -> listOf(MemoryPool.HOST)
            plan.memoryTopology == MemoryTopology.UNIFIED -> listOf(MemoryPool.SHARED)
            plan.memoryTopology == MemoryTopology.DISCRETE ->
                listOf(MemoryPool.HOST, MemoryPool.DISCRETE_GPU)
            else -> emptyList()
        }
        val loadCorrections = pools.mapNotNull { pool ->
            val key = identity.calibrationKey(
                plan,
                engineVersion,
                MetricKind.MEMORY,
                InferenceObservationPhase.LOAD,
            )
            calibrationSource.correctionFor(key.copy(memoryPool = pool.stableName))
                ?.toMemoryCorrection()
                ?.let { pool to it }
        }.toMap()
        val generationCorrections = pools.mapNotNull { pool ->
            val key = identity.calibrationKey(
                plan,
                engineVersion,
                MetricKind.MEMORY,
                InferenceObservationPhase.GENERATION,
            )
            calibrationSource.correctionFor(key.copy(memoryPool = pool.stableName))
                ?.toMemoryCorrection()
                ?.let { pool to it }
        }.toMap()
        return if (loadCorrections.isEmpty() && generationCorrections.isEmpty()) {
            MemoryCalibration.None
        } else {
            MemoryCalibration.ByPool(loadCorrections, generationCorrections)
        }
    }

    private fun CalibrationCorrection.toMemoryCorrection(): MemoryCalibration.Correction? {
        val likelyFactor = maxOf(1.0, likely)
        val highFactor = maxOf(likelyFactor, high, 1.0)
        if (!likelyFactor.isFinite() || !highFactor.isFinite() ||
            likelyFactor > MAX_MEMORY_CORRECTION || highFactor > MAX_MEMORY_CORRECTION
        ) return null
        return MemoryCalibration.Correction(
            likelyNumerator = ceil(likelyFactor * MEMORY_CORRECTION_SCALE).toLong(),
            likelyDenominator = MEMORY_CORRECTION_SCALE.toLong(),
            highNumerator = ceil(highFactor * MEMORY_CORRECTION_SCALE).toLong(),
            highDenominator = MEMORY_CORRECTION_SCALE.toLong(),
        )
    }

    private fun safeRatio(numerator: Double, denominator: Double): Double? =
        if (numerator.isFinite() && denominator.isFinite() && numerator >= 0.0 && denominator > 0.0) {
            numerator / denominator
        } else null

    private data class UtilityAssessment(val metrics: PlanUtilityMetrics, val evidence: List<Evidence>)
    private data class WeightedProxy(val value: Double, val weight: Double, val component: String)
    private data class QualityProxy(val value: Double?, val components: List<String>)

    private companion object {
        const val MEMORY_CORRECTION_SCALE = 1_000_000.0
        const val MAX_MEMORY_CORRECTION = Long.MAX_VALUE.toDouble() / MEMORY_CORRECTION_SCALE
    }
}
