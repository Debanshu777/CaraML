package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.DeviceSnapshot
import com.debanshu777.caraml.core.platform.MemoryTopology
import kotlin.math.exp
import kotlin.math.ln
import kotlin.time.Clock

data class PlanUtilityMetrics(
    val performance: Double? = null,
    val energy: Double? = null,
    val quality: Double? = null,
    val context: Double? = null,
    val storageEfficiency: Double? = null,
)

@ConsistentCopyVisibility
data class SelectedPlan private constructor(
    val planAssessment: PlanAssessment?,
    val fitBand: FitBand?,
    val category: RecommendationCategory,
    val reasons: List<AssessmentReason>,
    val utility: Double,
    val worstNormalizedHeadroom: Double,
    val safetyConfidence: Confidence,
    val performanceConfidence: Confidence,
    val candidateIndex: Int,
) {
    val plan: PlanReference?
        get() = planAssessment?.plan

    constructor(
        planAssessment: PlanAssessment?,
        fitBand: FitBand?,
        category: RecommendationCategory,
        reasons: Collection<AssessmentReason>,
        utility: Double,
        worstNormalizedHeadroom: Double,
        safetyConfidence: Confidence,
        performanceConfidence: Confidence,
        candidateIndex: Int,
    ) : this(
        planAssessment,
        fitBand,
        category,
        reasons.toList(),
        utility.takeIf { it.isFinite() } ?: 0.0,
        worstNormalizedHeadroom.takeIf { it.isFinite() } ?: -1.0,
        safetyConfidence,
        performanceConfidence,
        candidateIndex,
    )
}

class RunPlanOptimizer(
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    fun select(
        assessment: ModelAssessment,
        snapshot: DeviceSnapshot,
        profile: RecommendationProfile,
    ): SelectedPlan {
        assessmentGraphIssue(assessment, snapshot)?.let { issue ->
            return emptySelection(RecommendationCategory.NEEDS_INFORMATION, listOf(issue))
        }
        when (val compatibility = assessment.compatibility) {
            is Compatibility.Incompatible -> return emptySelection(
                RecommendationCategory.INCOMPATIBLE,
                compatibility.reasons.ifEmpty { listOf(AssessmentReason.UNSUPPORTED_ENGINE_FEATURE) },
            )
            is Compatibility.Unknown -> return emptySelection(
                RecommendationCategory.NEEDS_INFORMATION,
                compatibility.reasons.ifEmpty { listOf(AssessmentReason.ENGINE_SUPPORT_UNKNOWN) },
            )
            Compatibility.Compatible -> Unit
        }
        if (!snapshotIsFresh(snapshot)) {
            return emptySelection(
                RecommendationCategory.NEEDS_INFORMATION,
                listOf(AssessmentReason.RESOURCE_SNAPSHOT_STALE),
            )
        }
        val candidates = assessment.planAssessments.values.mapIndexed { index, plan ->
            classifyCandidate(assessment, plan, snapshot, profile, index)
        }
        if (candidates.isEmpty()) {
            return emptySelection(
                RecommendationCategory.NEEDS_INFORMATION,
                assessment.planAssessments.reasons.ifEmpty { listOf(AssessmentReason.MEMORY_BOUNDS_UNKNOWN) },
            )
        }
        return candidates.minWithOrNull(
            compareBy<SelectedPlan> { RecommendationPolicyV1.categoryRank.getValue(it.category) }
                .thenByDescending { it.safetyConfidence.ordinal }
                .thenByDescending { it.performanceConfidence.ordinal }
                .thenByDescending { it.utility }
                .thenByDescending { it.worstNormalizedHeadroom }
                .thenBy { it.candidateIndex },
        ) ?: emptySelection(
            RecommendationCategory.NEEDS_INFORMATION,
            listOf(AssessmentReason.MEMORY_BOUNDS_UNKNOWN),
        )
    }

    internal fun snapshotIsFresh(snapshot: DeviceSnapshot): Boolean {
        if (!snapshot.isFresh) return false
        val now = try {
            clock()
        } catch (_: Exception) {
            return false
        }
        return snapshot.resources.isFreshAt(now, RecommendationPolicyV1.RESOURCE_SNAPSHOT_MAX_AGE_MS)
    }

    private fun assessmentGraphIssue(
        assessment: ModelAssessment,
        snapshot: DeviceSnapshot,
    ): AssessmentReason? {
        val plans = assessment.planAssessments
        if (assessment.assessmentKey.isBlank() ||
            assessment.assessmentKey != plans.assessmentKey ||
            assessment.compatibility != plans.compatibility
        ) {
            return AssessmentReason.ASSESSMENT_GRAPH_INVALID
        }
        if (plans.memoryTopology != snapshot.hardwareProfile.memoryTopology) {
            return AssessmentReason.DEVICE_CAPABILITIES_CHANGED
        }
        for (candidate in plans.values) {
            val plan = candidate.plan as? RunPlan ?: return AssessmentReason.ASSESSMENT_GRAPH_INVALID
            if (!performanceMatchesPlan(plan, candidate.performance)) {
                return AssessmentReason.INVALID_PERFORMANCE_EVIDENCE
            }
            if (plan.memoryTopology != snapshot.hardwareProfile.memoryTopology) {
                return AssessmentReason.DEVICE_CAPABILITIES_CHANGED
            }
            val matchingBackends = snapshot.hardwareProfile.backends.filter { it.kind == plan.backend }
            if (matchingBackends.size != 1 || matchingBackends.single().status !=
                com.debanshu777.caraml.core.platform.BackendStatus.AVAILABLE
            ) {
                return AssessmentReason.DEVICE_CAPABILITIES_CHANGED
            }
        }
        return null
    }

    private fun classifyCandidate(
        assessment: ModelAssessment,
        planAssessment: PlanAssessment,
        snapshot: DeviceSnapshot,
        profile: RecommendationProfile,
        candidateIndex: Int,
    ): SelectedPlan {
        val plan = planAssessment.plan as? RunPlan
            ?: return needsInformation(planAssessment, candidateIndex, AssessmentReason.MEMORY_BOUNDS_UNKNOWN)
        val reserve = RecommendationPolicyV1.memoryReservePercent[profile.riskTolerance]
            ?: return needsInformation(planAssessment, candidateIndex, AssessmentReason.MEMORY_BOUNDS_UNKNOWN)
        val requiredPools = requiredMemoryPools(plan, planAssessment, snapshot, reserve)
            ?: return needsInformation(planAssessment, candidateIndex, AssessmentReason.MEMORY_BOUNDS_UNKNOWN)
        val storage = planAssessment.storageBytes
            ?: return needsInformation(planAssessment, candidateIndex, AssessmentReason.STORAGE_BOUNDS_UNKNOWN)
        val storageBudget = snapshot.baseStorageBudgetBytes?.takeIf { it >= 0L }
            ?: return needsInformation(planAssessment, candidateIndex, AssessmentReason.STORAGE_BOUNDS_UNKNOWN)
        val storageBudgetConfidence = snapshot.budgetConfidence.storage
            ?: return needsInformation(planAssessment, candidateIndex, AssessmentReason.STORAGE_BOUNDS_UNKNOWN)

        val memoryBand = requiredPools.maxOfOrNull { fitBand(it.range, it.policyBudget) }
            ?: return needsInformation(planAssessment, candidateIndex, AssessmentReason.MEMORY_BOUNDS_UNKNOWN)
        val storageBand = fitBand(storage, storageBudget)
        val reasons = mutableListOf(memoryReason(memoryBand))
        var category = categoryFor(memoryBand, profile.riskTolerance)
        if (memoryBand == FitBand.NO_FIT) {
            category = RecommendationCategory.NOT_SUITABLE
        }
        if (storageBand == FitBand.NO_FIT) {
            category = RecommendationCategory.NOT_SUITABLE
            reasons += AssessmentReason.STORAGE_NO_FIT
        }
        if (profile.riskTolerance == RiskTolerance.EXPERIMENTAL && memoryBand == FitBand.LIKELY) {
            reasons += AssessmentReason.TIGHT_MEMORY_FIT
        }

        val safetyConfidence = minimumConfidence(
            assessment.confidence.compatibility,
            planAssessment.confidence.compatibility,
            planAssessment.confidence.memory,
            planAssessment.confidence.storage,
            storageBudgetConfidence,
            *requiredPools.map { it.budgetConfidence }.toTypedArray(),
        )
        category = applySafetyConfidenceCap(category, safetyConfidence, profile.riskTolerance).also { capped ->
            if (capped != category) reasons += AssessmentReason.SAFETY_EVIDENCE_LIMITED
        }

        if (plan.compromises.isNotEmpty()) {
            val capped = worseOf(category, RecommendationCategory.USABLE)
            if (capped != category) category = capped
            reasons += AssessmentReason.FALLBACK_PLAN_REQUIRED
        }

        val rangeConfidence = comparedPerformanceConfidence(planAssessment.performance)
        val wrapperConfidence = planAssessment.confidence.performance
        val performanceConfidence = minimumConfidence(wrapperConfidence, rangeConfidence)
        if (wrapperConfidence != rangeConfidence) {
            reasons += AssessmentReason.INVALID_PERFORMANCE_EVIDENCE
        }
        category = applyPerformancePolicy(
            category,
            planAssessment.performance,
            performanceConfidence,
            profile.optimizationPriority,
            reasons,
        )
        return SelectedPlan(
            planAssessment = planAssessment,
            fitBand = memoryBand,
            category = category,
            reasons = reasons.distinct(),
            utility = preferenceUtility(
                planAssessment.utilityMetrics.copy(
                    performance = normalizedPerformanceMetric(
                        planAssessment.performance,
                        profile.optimizationPriority,
                    ),
                ),
                profile.optimizationPriority,
            ),
            worstNormalizedHeadroom = requiredPools.minOf { pool ->
                (pool.policyBudget - pool.range.highBytes).toDouble() / pool.policyBudget.toDouble()
            },
            safetyConfidence = safetyConfidence,
            performanceConfidence = performanceConfidence,
            candidateIndex = candidateIndex,
        )
    }

    private fun requiredMemoryPools(
        plan: RunPlan,
        assessment: PlanAssessment,
        snapshot: DeviceSnapshot,
        reservePercent: Int,
    ): List<RequiredPool>? {
        fun required(range: EstimateRange?, baseBudget: Long?, budgetConfidence: Confidence?): RequiredPool? {
            val safeRange = range ?: return null
            val budget = baseBudget?.takeIf { it >= 0L } ?: return null
            val confidence = budgetConfidence ?: return null
            val policyBudget = checkedPercentage(budget, 100 - reservePercent) as? CheckedLong.Value ?: return null
            return RequiredPool(safeRange, policyBudget.value, confidence)
        }
        return when {
            plan.backend == com.debanshu777.caraml.core.platform.BackendKind.CPU ->
                listOfNotNull(
                    required(
                        assessment.hostMemoryBytes,
                        snapshot.baseHostBudgetBytes,
                        snapshot.budgetConfidence.host,
                    ),
                )
                    .takeIf { it.size == 1 }
            plan.memoryTopology == MemoryTopology.UNIFIED ->
                listOfNotNull(
                    required(
                        assessment.sharedMemoryBytes,
                        snapshot.baseSharedBudgetBytes,
                        snapshot.budgetConfidence.shared,
                    ),
                )
                    .takeIf { it.size == 1 }
            plan.memoryTopology == MemoryTopology.DISCRETE -> {
                val host = required(
                    assessment.hostMemoryBytes,
                    snapshot.baseHostBudgetBytes,
                    snapshot.budgetConfidence.host,
                )
                val gpu = required(
                    assessment.gpuMemoryBytes,
                    snapshot.baseGpuBudgetBytes,
                    snapshot.budgetConfidence.gpu,
                )
                if (host == null || gpu == null) null else listOf(host, gpu)
            }
            else -> null
        }
    }

    private fun applyPerformancePolicy(
        current: RecommendationCategory,
        performance: PerformanceEstimate,
        confidence: Confidence,
        priority: OptimizationPriority,
        reasons: MutableList<AssessmentReason>,
    ): RecommendationCategory {
        val status = when (performance) {
            is PerformanceEstimate.Unknown -> {
                reasons += AssessmentReason.SPEED_NOT_VERIFIED
                return current
            }
            is PerformanceEstimate.DiffusionVideo -> {
                reasons += AssessmentReason.SPEED_NOT_VERIFIED
                return current
            }
            is PerformanceEstimate.Llm -> {
                val value = performance.decodeTokensPerSecond.likely
                val target = RecommendationPolicyV1.llmDecodeTarget.getValue(priority)
                val hard = RecommendationPolicyV1.llmDecodeHardMinimum.getValue(priority)
                when {
                    !value.isFinite() || value <= 0.0 -> PerformanceStatus.INVALID
                    value < hard -> PerformanceStatus.HARD_MISS
                    value < target -> PerformanceStatus.TARGET_MISS
                    else -> PerformanceStatus.PASS
                }
            }
            is PerformanceEstimate.DiffusionImage -> {
                val value = performance.referenceTotalTimeSeconds.likely
                val target = RecommendationPolicyV1.diffusionSecondsTarget.getValue(priority)
                val hard = RecommendationPolicyV1.diffusionSecondsHardMaximum.getValue(priority)
                when {
                    !value.isFinite() || value <= 0.0 -> PerformanceStatus.INVALID
                    value > hard -> PerformanceStatus.HARD_MISS
                    value > target -> PerformanceStatus.TARGET_MISS
                    else -> PerformanceStatus.PASS
                }
            }
        }
        if (confidence == Confidence.LOW) {
            reasons += AssessmentReason.SPEED_NOT_VERIFIED
            return if (status == PerformanceStatus.HARD_MISS || status == PerformanceStatus.INVALID) {
                reasons += AssessmentReason.PERFORMANCE_UNCERTAIN
                worseOf(current, RecommendationCategory.RISKY)
            } else current
        }
        return when (status) {
            PerformanceStatus.PASS -> current
            PerformanceStatus.TARGET_MISS -> {
                reasons += AssessmentReason.PERFORMANCE_TARGET_MISSED
                downgrade(current)
            }
            PerformanceStatus.HARD_MISS -> {
                reasons += AssessmentReason.PERFORMANCE_TARGET_MISSED
                RecommendationCategory.NOT_SUITABLE
            }
            PerformanceStatus.INVALID -> {
                reasons += AssessmentReason.PERFORMANCE_UNCERTAIN
                worseOf(current, RecommendationCategory.RISKY)
            }
        }
    }

    private fun performanceMatchesPlan(
        plan: RunPlan,
        performance: PerformanceEstimate,
    ): Boolean = when (performance) {
        is PerformanceEstimate.Unknown -> true
        is PerformanceEstimate.Llm -> plan is LlmRunPlan
        is PerformanceEstimate.DiffusionImage ->
            plan is DiffusionRunPlan && plan.mode == DiffusionMode.IMAGE
        is PerformanceEstimate.DiffusionVideo ->
            plan is DiffusionRunPlan && plan.mode == DiffusionMode.VIDEO
    }

    private fun comparedPerformanceConfidence(performance: PerformanceEstimate): Confidence = when (performance) {
        is PerformanceEstimate.Unknown -> Confidence.LOW
        is PerformanceEstimate.Llm -> performance.decodeTokensPerSecond.confidence
        is PerformanceEstimate.DiffusionImage -> performance.referenceTotalTimeSeconds.confidence
        is PerformanceEstimate.DiffusionVideo -> performance.totalTimeSeconds.confidence
    }

    private fun applySafetyConfidenceCap(
        category: RecommendationCategory,
        confidence: Confidence,
        risk: RiskTolerance,
    ): RecommendationCategory = when {
        confidence == Confidence.LOW -> worseOf(category, RecommendationCategory.RISKY)
        confidence == Confidence.MEDIUM && risk == RiskTolerance.CONSERVATIVE ->
            worseOf(category, RecommendationCategory.USABLE)
        else -> category
    }

    private fun categoryFor(fit: FitBand, risk: RiskTolerance): RecommendationCategory = when (fit) {
        FitBand.COMFORTABLE -> RecommendationCategory.RECOMMENDED
        FitBand.LIKELY -> when (risk) {
            RiskTolerance.CONSERVATIVE -> RecommendationCategory.RISKY
            RiskTolerance.BALANCED -> RecommendationCategory.USABLE
            RiskTolerance.EXPERIMENTAL -> RecommendationCategory.RECOMMENDED
        }
        FitBand.BORDERLINE -> when (risk) {
            RiskTolerance.CONSERVATIVE -> RecommendationCategory.NOT_SUITABLE
            RiskTolerance.BALANCED, RiskTolerance.EXPERIMENTAL -> RecommendationCategory.RISKY
        }
        FitBand.NO_FIT -> RecommendationCategory.NOT_SUITABLE
    }

    private fun memoryReason(fit: FitBand): AssessmentReason = when (fit) {
        FitBand.COMFORTABLE -> AssessmentReason.MEMORY_FIT_COMFORTABLE
        FitBand.LIKELY -> AssessmentReason.MEMORY_FIT_LIKELY
        FitBand.BORDERLINE -> AssessmentReason.MEMORY_FIT_BORDERLINE
        FitBand.NO_FIT -> AssessmentReason.MEMORY_NO_FIT
    }

    private fun downgrade(category: RecommendationCategory): RecommendationCategory = when (category) {
        RecommendationCategory.RECOMMENDED -> RecommendationCategory.USABLE
        RecommendationCategory.USABLE -> RecommendationCategory.RISKY
        RecommendationCategory.RISKY -> RecommendationCategory.NOT_SUITABLE
        else -> category
    }

    private fun worseOf(
        first: RecommendationCategory,
        second: RecommendationCategory,
    ): RecommendationCategory = if (
        RecommendationPolicyV1.categoryRank.getValue(first) >= RecommendationPolicyV1.categoryRank.getValue(second)
    ) first else second

    private fun minimumConfidence(vararg values: Confidence): Confidence =
        values.minByOrNull { it.ordinal } ?: Confidence.LOW

    private fun needsInformation(
        plan: PlanAssessment,
        candidateIndex: Int,
        reason: AssessmentReason,
    ) = SelectedPlan(
        plan,
        null,
        RecommendationCategory.NEEDS_INFORMATION,
        listOf(reason),
        0.0,
        -1.0,
        Confidence.LOW,
        plan.confidence.performance,
        candidateIndex,
    )

    private fun emptySelection(
        category: RecommendationCategory,
        reasons: Collection<AssessmentReason>,
    ) = SelectedPlan(
        null,
        null,
        category,
        reasons,
        0.0,
        -1.0,
        Confidence.LOW,
        Confidence.LOW,
        -1,
    )

    private data class RequiredPool(
        val range: EstimateRange,
        val policyBudget: Long,
        val budgetConfidence: Confidence,
    )
    private enum class PerformanceStatus { PASS, TARGET_MISS, HARD_MISS, INVALID }
}

internal fun normalizedPerformanceMetric(
    performance: PerformanceEstimate,
    priority: OptimizationPriority,
): Double? = when (performance) {
    is PerformanceEstimate.Llm -> {
        val value = performance.decodeTokensPerSecond.likely
        val target = RecommendationPolicyV1.llmDecodeTarget[priority]
        if (value.isFinite() && value > 0.0 && target != null && target.isFinite() && target > 0.0) {
            value / target
        } else null
    }
    is PerformanceEstimate.DiffusionImage -> {
        val value = performance.referenceTotalTimeSeconds.likely
        val target = RecommendationPolicyV1.diffusionSecondsTarget[priority]
        if (value.isFinite() && value > 0.0 && target != null && target.isFinite() && target > 0.0) {
            target / value
        } else null
    }
    is PerformanceEstimate.DiffusionVideo, is PerformanceEstimate.Unknown -> null
}

internal fun fitBand(range: EstimateRange, policyBudget: Long): FitBand = when {
    policyBudget < 0L -> FitBand.NO_FIT
    range.highBytes <= policyBudget -> FitBand.COMFORTABLE
    range.likelyBytes <= policyBudget -> FitBand.LIKELY
    range.lowBytes <= policyBudget -> FitBand.BORDERLINE
    else -> FitBand.NO_FIT
}

internal fun preferenceUtility(
    metrics: PlanUtilityMetrics,
    priority: OptimizationPriority,
): Double {
    val configured = RecommendationPolicyV1.utilityWeights.getValue(priority)
    val weights = doubleArrayOf(
        configured.performance,
        configured.energy,
        configured.quality,
        configured.context,
        configured.storage,
    )
    val values = arrayOf(
        metrics.performance,
        metrics.energy,
        metrics.quality,
        metrics.context,
        metrics.storageEfficiency,
    )
    var weightSum = 0.0
    var weightedLog = 0.0
    values.indices.forEach { index ->
        val value = values[index]
        val weight = weights[index]
        if (value != null && value.isFinite() && weight > 0.0) {
            val clamped = value.coerceIn(
                RecommendationPolicyV1.UTILITY_METRIC_MIN,
                RecommendationPolicyV1.UTILITY_METRIC_MAX,
            )
            weightSum += weight
            weightedLog += weight * ln(clamped)
        }
    }
    return if (weightSum > 0.0) exp(weightedLog / weightSum) else 0.0
}

class RecommendationSortKey private constructor(
    private val categoryRank: Int,
    private val safetyConfidenceRank: Int,
    private val performanceConfidenceRank: Int,
    private val utility: Double,
    private val headroom: Double,
    private val stableId: String,
) : Comparable<RecommendationSortKey> {
    override fun compareTo(other: RecommendationSortKey): Int {
        val category = categoryRank.compareTo(other.categoryRank)
        if (category != 0) return category
        val safety = other.safetyConfidenceRank.compareTo(safetyConfidenceRank)
        if (safety != 0) return safety
        val performance = other.performanceConfidenceRank.compareTo(performanceConfidenceRank)
        if (performance != 0) return performance
        val preference = other.utility.compareTo(utility)
        if (preference != 0) return preference
        val remaining = other.headroom.compareTo(headroom)
        if (remaining != 0) return remaining
        return stableId.compareTo(other.stableId)
    }

    companion object {
        fun create(
            category: RecommendationCategory,
            confidence: AssessmentConfidence,
            utility: Double,
            worstNormalizedHeadroom: Double,
            stableId: String,
        ): RecommendationSortKey {
            val safety = minOf(confidence.compatibility, confidence.memory, confidence.storage)
            return RecommendationSortKey(
                categoryRank = RecommendationPolicyV1.categoryRank.getValue(category),
                safetyConfidenceRank = safety.ordinal,
                performanceConfidenceRank = confidence.performance.ordinal,
                utility = utility.takeIf { it.isFinite() } ?: 0.0,
                headroom = worstNormalizedHeadroom.takeIf { it.isFinite() } ?: -1.0,
                stableId = stableId,
            )
        }
    }
}
