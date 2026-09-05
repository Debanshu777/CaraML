package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.BackendStatus
import com.debanshu777.caraml.core.platform.DeviceSnapshot
import com.debanshu777.caraml.core.platform.HardwareProfile
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.platform.ResourceSnapshot
import com.debanshu777.caraml.core.platform.computeBaseBudget
import com.debanshu777.caraml.core.platform.computeStorageBudget

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

        val settings = planningSettings(descriptor, hardwareProfile, workload)
            ?: return invalidPlans(key, compatibility, hardwareProfile.memoryTopology)
        val candidates: List<RunPlan> = when {
            descriptor is LlmModelDescriptor && workload is LlmWorkloadConfig ->
                runPlanGenerator.llmCandidates(descriptor, workload, settings)
            descriptor is DiffusionModelDescriptor && workload is DiffusionWorkloadConfig ->
                runPlanGenerator.diffusionCandidates(descriptor, workload, settings)
            else -> emptyList()
        }
        if (candidates.isEmpty()) {
            return invalidPlans(key, compatibility, hardwareProfile.memoryTopology)
        }

        val assessments = candidates.map { plan ->
            val footprint = when {
                descriptor is LlmModelDescriptor && plan is LlmRunPlan ->
                    llmFootprintEstimator.estimate(descriptor, plan, MemoryCalibration.None)
                descriptor is DiffusionModelDescriptor && plan is DiffusionRunPlan ->
                    diffusionFootprintEstimator.estimate(descriptor, plan, MemoryCalibration.None)
                else -> invalidPlanAssessment(plan)
            }
            val performance = performanceEstimator.estimate(descriptor, plan, hardwareProfile, calibrationSource)
            PlanAssessment(
                plan = footprint.plan,
                hostMemoryBytes = footprint.hostMemoryBytes,
                gpuMemoryBytes = footprint.gpuMemoryBytes,
                sharedMemoryBytes = footprint.sharedMemoryBytes,
                storageBytes = footprint.storageBytes,
                confidence = footprint.confidence.copy(performance = performanceConfidence(performance)),
                evidence = footprint.evidence + performance.evidence + listOf(
                    Evidence(AssessmentReason.ENERGY_NOT_VERIFIED, Confidence.LOW, "energy-metric-omitted"),
                    Evidence(AssessmentReason.QUALITY_PROXY_USED, Confidence.MEDIUM, "plan-derived-proxy"),
                ),
                performance = performance,
                utilityMetrics = utilityMetrics(plan, workload, footprint),
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
        plan: RunPlan,
        workload: WorkloadConfig,
        footprint: PlanAssessment,
    ): PlanUtilityMetrics {
        val quality = when (plan) {
            is LlmRunPlan -> when (plan.keyCacheType) {
                KvCacheType.F16 -> 1.0
                KvCacheType.Q8_0 -> 0.85
                KvCacheType.Q4_0 -> 0.65
            }
            is DiffusionRunPlan -> if (workload is DiffusionWorkloadConfig) {
                safeRatio(
                    plan.width.toDouble() * plan.height.toDouble(),
                    workload.width.toDouble() * workload.height.toDouble(),
                )
            } else null
        }
        val context = when {
            plan is LlmRunPlan && workload is LlmWorkloadConfig ->
                safeRatio(plan.contextTokens.toDouble(), workload.contextTokens.toDouble())
            else -> null
        }
        val storageEfficiency = footprint.storageBytes?.highBytes?.takeIf { it > 0L }?.let {
            safeRatio(ONE_GIB.toDouble(), it.toDouble())
        }
        return PlanUtilityMetrics(null, null, quality, context, storageEfficiency)
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
        is LlmModelDescriptor -> "${descriptor.repositoryId}@${descriptor.revision}:${descriptor.file.path}"
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

    private fun safeRatio(numerator: Double, denominator: Double): Double? =
        if (numerator.isFinite() && denominator.isFinite() && numerator >= 0.0 && denominator > 0.0) {
            numerator / denominator
        } else null

    private companion object {
        const val ONE_GIB: Long = 1_073_741_824L
    }
}
