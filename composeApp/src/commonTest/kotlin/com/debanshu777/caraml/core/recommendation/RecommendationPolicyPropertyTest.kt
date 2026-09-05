package com.debanshu777.caraml.core.recommendation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecommendationPolicyPropertyTest {
    private val policy = RecommendationPolicy()

    @Test
    fun increasingEstimatedMemoryNeverImprovesCategoryAcrossOneThousandSeeds() {
        val random = Random(6_001)
        repeat(1_000) { seed ->
            val required = random.nextLong(1, 1_200)
            val increase = random.nextLong(0, 600)
            val risk = RiskTolerance.entries[random.nextInt(RiskTolerance.entries.size)]
            val before = category(memory = required, budget = 1_000, risk = risk)
            val after = category(memory = required + increase, budget = 1_000, risk = risk)

            assertTrue(severity(after) >= severity(before), "seed=$seed risk=$risk $before->$after")
        }
    }

    @Test
    fun increasingAvailableMemoryNeverWorsensCategoryAcrossOneThousandSeeds() {
        val random = Random(6_002)
        repeat(1_000) { seed ->
            val required = random.nextLong(1, 1_500)
            val budget = random.nextLong(1, 1_500)
            val increase = random.nextLong(0, 800)
            val risk = RiskTolerance.entries[random.nextInt(RiskTolerance.entries.size)]
            val before = category(memory = required, budget = budget, risk = risk)
            val after = category(memory = required, budget = budget + increase, risk = risk)

            assertTrue(severity(after) <= severity(before), "seed=$seed risk=$risk $before->$after")
        }
    }

    @Test
    fun loweringSafetyConfidenceNeverImprovesCategoryAcrossOneThousandSeeds() {
        val random = Random(6_003)
        repeat(1_000) { seed ->
            val risk = RiskTolerance.entries[random.nextInt(RiskTolerance.entries.size)]
            val plan = task6PlanAssessment(host = task6Range(100, 100, 100))
            fun recommendation(confidence: Confidence): RecommendationCategory = policy.recommend(
                task6Assessment(
                    plans = listOf(plan.copyForTask6(confidence = task6Confidence(memory = confidence))),
                ),
                task6Snapshot(),
                RecommendationProfile(riskTolerance = risk),
            ).category

            val high = recommendation(Confidence.HIGH)
            val medium = recommendation(Confidence.MEDIUM)
            val low = recommendation(Confidence.LOW)
            assertTrue(severity(medium) >= severity(high), "seed=$seed high=$high medium=$medium")
            assertTrue(severity(low) >= severity(medium), "seed=$seed medium=$medium low=$low")
        }
    }

    @Test
    fun experimentalIsNeverStricterThanConservativeAcrossOneThousandSeeds() {
        val random = Random(6_004)
        repeat(1_000) { seed ->
            val low = random.nextLong(1, 1_200)
            val likely = low + random.nextLong(0, 300)
            val high = likely + random.nextLong(0, 300)
            val range = task6Range(low, likely, high)
            val assessment = task6Assessment(plans = listOf(task6PlanAssessment(host = range)))
            val snapshot = task6Snapshot(hostBudget = 1_000)
            val conservative = policy.recommend(
                assessment,
                snapshot,
                RecommendationProfile(riskTolerance = RiskTolerance.CONSERVATIVE),
            ).category
            val experimental = policy.recommend(
                assessment,
                snapshot,
                RecommendationProfile(riskTolerance = RiskTolerance.EXPERIMENTAL),
            ).category

            assertTrue(
                severity(experimental) <= severity(conservative),
                "seed=$seed conservative=$conservative experimental=$experimental",
            )
        }
    }

    @Test
    fun incompatibilityAndLowConfidenceHardSlowResultsStayWithinSafetyBounds() {
        val random = Random(6_005)
        repeat(1_000) { seed ->
            val profile = RecommendationProfile(
                riskTolerance = RiskTolerance.entries[random.nextInt(RiskTolerance.entries.size)],
                optimizationPriority = OptimizationPriority.entries[random.nextInt(OptimizationPriority.entries.size)],
            )
            val incompatible = policy.recommend(
                task6Assessment(
                    compatibility = Compatibility.Incompatible(listOf(AssessmentReason.UNSUPPORTED_FORMAT)),
                ),
                task6Snapshot(),
                profile,
            )
            val uncertainSlow = policy.recommend(
                task6Assessment(
                    plans = listOf(
                        task6PlanAssessment(
                            performance = task6LlmPerformance(0.01, Confidence.LOW),
                        ),
                    ),
                ),
                task6Snapshot(),
                profile,
            )

            assertEquals(RecommendationCategory.INCOMPATIBLE, incompatible.category, "seed=$seed")
            assertEquals(RecommendationCategory.RISKY, uncertainSlow.category, "seed=$seed")
            assertTrue(uncertainSlow.reasons.contains(AssessmentReason.PERFORMANCE_UNCERTAIN))
        }
    }

    private fun category(memory: Long, budget: Long, risk: RiskTolerance): RecommendationCategory =
        policy.recommend(
            task6Assessment(plans = listOf(task6PlanAssessment(host = task6Range(memory, memory, memory)))),
            task6Snapshot(hostBudget = budget),
            RecommendationProfile(riskTolerance = risk),
        ).category

    private fun severity(category: RecommendationCategory): Int = when (category) {
        RecommendationCategory.RECOMMENDED -> 0
        RecommendationCategory.USABLE -> 1
        RecommendationCategory.RISKY -> 2
        RecommendationCategory.NEEDS_INFORMATION -> 3
        RecommendationCategory.NOT_SUITABLE -> 4
        RecommendationCategory.INCOMPATIBLE -> 5
    }
}

private fun PlanAssessment.copyForTask6(
    confidence: AssessmentConfidence = this.confidence,
) = PlanAssessment(
    plan = plan,
    hostMemoryBytes = hostMemoryBytes,
    gpuMemoryBytes = gpuMemoryBytes,
    sharedMemoryBytes = sharedMemoryBytes,
    storageBytes = storageBytes,
    confidence = confidence,
    evidence = evidence,
    performance = performance,
    utilityMetrics = utilityMetrics,
)
