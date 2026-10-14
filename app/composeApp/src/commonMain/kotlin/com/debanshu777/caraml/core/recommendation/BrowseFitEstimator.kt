package com.debanshu777.caraml.core.recommendation

import androidx.compose.runtime.Stable
import com.debanshu777.caraml.core.platform.DeviceSnapshot
import com.debanshu777.caraml.core.platform.MemoryTopology
import kotlin.time.Clock

enum class BrowseResourceFit {
    LIKELY_FIT,
    TIGHT_FIT,
    TOO_LARGE,
    UNKNOWN,
}

/** Presentation evidence only. It carries no executable or native-load permit. */
@Stable
data class BrowseFitEstimate(
    val compatibility: Compatibility,
    val memoryFit: BrowseResourceFit,
    val storageFit: BrowseResourceFit,
    val memory: MemoryPhaseEstimates?,
    val storageBytes: EstimateRange?,
    val downloadBytes: Long?,
    val performance: PerformanceEstimate,
    val resourceSnapshotFresh: Boolean,
    val resourceTimestampEpochMs: Long,
    val reasons: List<AssessmentReason>,
    val evidence: List<Evidence>,
)

/** Runs the existing bounded estimators for browse copy, including when compatibility is Unknown. */
class BrowseFitEstimator(
    private val suitabilityEngine: SuitabilityEngine,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    fun estimate(
        descriptor: LlmModelDescriptor,
        snapshot: DeviceSnapshot,
        workload: WorkloadConfig,
    ): BrowseFitEstimate {
        val assessed = suitabilityEngine.assessBrowsePlans(descriptor, snapshot.hardwareProfile, workload)
        val selectedEstimate = assessed.values.firstOrNull()
        val now = runCatching(clock).getOrNull()
        val fresh = snapshot.isFresh && now != null && snapshot.resources.isFreshAt(
            now,
            RecommendationPolicyV1.RESOURCE_SNAPSHOT_MAX_AGE_MS,
        )
        val reasons = buildList {
            addAll(assessed.reasons)
            addAll(assessed.compatibility.reasons())
            if (!fresh) add(AssessmentReason.RESOURCE_SNAPSHOT_STALE)
            if (selectedEstimate?.storageBytes == null) add(AssessmentReason.STORAGE_BOUNDS_UNKNOWN)
        }.distinct()
        val evidence = buildList {
            addAll(assessed.evidence)
            selectedEstimate?.let { addAll(it.evidence) }
            if (!fresh) add(Evidence(AssessmentReason.RESOURCE_SNAPSHOT_STALE, Confidence.LOW, "browse-resource-snapshot"))
        }.distinct()
        return BrowseFitEstimate(
            compatibility = assessed.compatibility,
            memoryFit = selectedEstimate?.let { memoryFit(it, snapshot, fresh) } ?: BrowseResourceFit.UNKNOWN,
            storageFit = selectedEstimate?.let { storageFit(it.storageBytes, snapshot.baseStorageBudgetBytes, fresh) }
                ?: BrowseResourceFit.UNKNOWN,
            memory = selectedEstimate?.rawMemoryByPhase,
            storageBytes = selectedEstimate?.storageBytes,
            downloadBytes = (descriptor.checkedTotalFileBytes() as? CheckedLong.Value)?.value,
            performance = selectedEstimate?.performance ?: PerformanceEstimate.Unknown(AssessmentReason.SPEED_NOT_VERIFIED),
            resourceSnapshotFresh = fresh,
            resourceTimestampEpochMs = snapshot.resources.capturedAtEpochMs,
            reasons = reasons,
            evidence = evidence,
        )
    }

    private fun memoryFit(
        estimate: PlanAssessment,
        snapshot: DeviceSnapshot,
        fresh: Boolean,
    ): BrowseResourceFit {
        if (!fresh) return BrowseResourceFit.UNKNOWN
        val plan = estimate.plan as? LlmRunPlan ?: return BrowseResourceFit.UNKNOWN
        val comparisons = when (snapshot.hardwareProfile.memoryTopology) {
            MemoryTopology.UNIFIED -> listOfNotNull(
                compare(estimate.sharedMemoryBytes, snapshot.baseSharedBudgetBytes),
            )
            MemoryTopology.DISCRETE -> if (plan.backend == com.debanshu777.caraml.core.platform.BackendKind.CPU) {
                listOfNotNull(compare(estimate.hostMemoryBytes, snapshot.baseHostBudgetBytes))
            } else {
                listOfNotNull(
                    compare(estimate.hostMemoryBytes, snapshot.baseHostBudgetBytes),
                    compare(estimate.gpuMemoryBytes, snapshot.baseGpuBudgetBytes),
                )
            }
            MemoryTopology.UNKNOWN -> emptyList()
        }
        val requiredPools = when (snapshot.hardwareProfile.memoryTopology) {
            MemoryTopology.UNIFIED -> 1
            MemoryTopology.DISCRETE -> if (plan.backend == com.debanshu777.caraml.core.platform.BackendKind.CPU) 1 else 2
            MemoryTopology.UNKNOWN -> 0
        }
        if (comparisons.isEmpty() || comparisons.size != requiredPools) {
            return BrowseResourceFit.UNKNOWN
        }
        return combineFits(comparisons)
    }

    private fun storageFit(
        estimate: EstimateRange?,
        budget: Long?,
        fresh: Boolean,
    ): BrowseResourceFit {
        if (!fresh || estimate == null || budget == null || budget < 0L) return BrowseResourceFit.UNKNOWN
        return compare(estimate, budget) ?: BrowseResourceFit.UNKNOWN
    }

    private fun compare(estimate: EstimateRange?, budget: Long?): BrowseResourceFit? {
        if (estimate == null || budget == null || budget < 0L) return null
        return when {
            estimate.lowBytes > budget -> BrowseResourceFit.TOO_LARGE
            estimate.highBytes <= budget -> BrowseResourceFit.LIKELY_FIT
            else -> BrowseResourceFit.TIGHT_FIT
        }
    }

    private fun combineFits(fits: List<BrowseResourceFit>): BrowseResourceFit = when {
        BrowseResourceFit.TOO_LARGE in fits -> BrowseResourceFit.TOO_LARGE
        fits.all { it == BrowseResourceFit.LIKELY_FIT } -> BrowseResourceFit.LIKELY_FIT
        fits.any { it == BrowseResourceFit.TIGHT_FIT } -> BrowseResourceFit.TIGHT_FIT
        else -> BrowseResourceFit.UNKNOWN
    }

}

private fun Compatibility.reasons(): List<AssessmentReason> = when (this) {
    Compatibility.Compatible -> emptyList()
    is Compatibility.Incompatible -> reasons
    is Compatibility.Unknown -> reasons
}
