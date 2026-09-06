package com.debanshu777.caraml.features.modelhub.domain

import com.debanshu777.caraml.core.recommendation.AssessmentConfidence
import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.Confidence
import com.debanshu777.caraml.core.recommendation.FitBand
import com.debanshu777.caraml.core.recommendation.PersonalizedRecommendation
import com.debanshu777.caraml.core.recommendation.PlanAssessment
import com.debanshu777.caraml.core.recommendation.PlanReference
import com.debanshu777.caraml.core.recommendation.RecommendationCategory
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DownloadAdmissionPolicyTest {
    private val policy = DownloadAdmissionPolicy()

    @Test
    fun storageNoFitAlwaysBlocksBeforeRuntimeDownloadForLater() {
        val result = recommendation(
            category = RecommendationCategory.NOT_SUITABLE,
            memoryFit = FitBand.NO_FIT,
            storageFit = FitBand.NO_FIT,
        )

        val decision = policy.decide(result, allowForLater = true)

        assertEquals(
            AssessmentReason.INSUFFICIENT_STORAGE,
            assertIs<DownloadAdmission.Blocked>(decision).reason,
        )
    }

    @Test
    fun runtimeNoFitRequiresExplicitDownloadForLaterConfirmation() {
        val result = recommendation(
            category = RecommendationCategory.NOT_SUITABLE,
            memoryFit = FitBand.NO_FIT,
            storageFit = FitBand.COMFORTABLE,
        )

        val decision = policy.decide(result, allowForLater = true)

        assertEquals(
            AssessmentReason.DOWNLOAD_FOR_LATER,
            assertIs<DownloadAdmission.ConfirmationRequired>(decision).reason,
        )
    }

    @Test
    fun runtimeNoFitBlocksWithoutDownloadForLaterIntent() {
        val decision = policy.decide(
            recommendation(
                category = RecommendationCategory.NOT_SUITABLE,
                memoryFit = FitBand.NO_FIT,
                storageFit = FitBand.COMFORTABLE,
            ),
            allowForLater = false,
        )

        assertEquals(
            AssessmentReason.NO_RUN_PLAN,
            assertIs<DownloadAdmission.Blocked>(decision).reason,
        )
    }

    @Test
    fun incompatibleAlwaysBlocksAndRecommendedIsAllowed() {
        val incompatible = policy.decide(
            recommendation(RecommendationCategory.INCOMPATIBLE),
            allowForLater = true,
        )
        val recommended = policy.decide(
            recommendation(RecommendationCategory.RECOMMENDED),
            allowForLater = false,
        )

        assertEquals(
            AssessmentReason.INCOMPATIBLE_MODEL,
            assertIs<DownloadAdmission.Blocked>(incompatible).reason,
        )
        assertIs<DownloadAdmission.Allowed>(recommended)
    }

    @Test
    fun runnableRecommendationsFailClosedWhenAnyCoherentProjectionIsMissing() {
        val cases = listOf(
            recommendation(RecommendationCategory.RECOMMENDED, memoryFit = null),
            recommendation(RecommendationCategory.RECOMMENDED, storageFit = null),
            recommendation(RecommendationCategory.RECOMMENDED, selectedPlan = null),
            recommendation(RecommendationCategory.RECOMMENDED, selectedPlanAssessment = null),
            recommendation(RecommendationCategory.RECOMMENDED, confidence = null),
        )

        cases.forEach { result ->
            assertEquals(
                AssessmentReason.RECOMMENDATION_EVIDENCE_INCOMPLETE,
                assertIs<DownloadAdmission.Blocked>(policy.decide(result, allowForLater = false)).reason,
            )
        }
    }

    @Test
    fun runnableRecommendationFailsClosedWhenSelectedPlanAndAssessmentDisagree() {
        val selected = TestPlan("selected")
        val different = TestPlan("different")
        val decision = policy.decide(
            recommendation(
                RecommendationCategory.USABLE,
                selectedPlan = selected,
                selectedPlanAssessment = assessment(different),
            ),
            allowForLater = false,
        )

        assertEquals(
            AssessmentReason.RECOMMENDATION_EVIDENCE_INCOMPLETE,
            assertIs<DownloadAdmission.Blocked>(decision).reason,
        )

        val inconsistentType = policy.decide(
            recommendation(
                RecommendationCategory.RECOMMENDED,
                selectedPlan = selected,
                selectedPlanAssessment = assessment(OtherTestPlan(selected.stableKey)),
            ),
            allowForLater = false,
        )
        assertEquals(
            AssessmentReason.RECOMMENDATION_EVIDENCE_INCOMPLETE,
            assertIs<DownloadAdmission.Blocked>(inconsistentType).reason,
        )
    }

    @Test
    fun componentRecheckPreservesTypedBlockedAndConfirmationOutcomes() {
        val blocked = DownloadAdmission.Blocked(AssessmentReason.INSUFFICIENT_STORAGE)
        val confirmation = DownloadAdmission.ConfirmationRequired(AssessmentReason.DOWNLOAD_FOR_LATER)

        assertSame(
            blocked,
            assertFailsWith<DownloadAdmissionRejected> {
                blocked.requireForComponent(downloadForLaterConfirmed = false)
            }.admission,
        )
        assertSame(
            confirmation,
            assertFailsWith<DownloadAdmissionRejected> {
                confirmation.requireForComponent(downloadForLaterConfirmed = false)
            }.admission,
        )
        confirmation.requireForComponent(downloadForLaterConfirmed = true)
        DownloadAdmission.Allowed.requireForComponent(downloadForLaterConfirmed = false)
        assertEquals(
            "Not enough storage space for this download.",
            downloadAdmissionErrorMessage(blocked),
        )
    }
}

private fun recommendation(
    category: RecommendationCategory,
    memoryFit: FitBand? = FitBand.COMFORTABLE,
    storageFit: FitBand? = FitBand.COMFORTABLE,
    selectedPlan: PlanReference? = if (category in runnableCategories) TestPlan("selected") else null,
    selectedPlanAssessment: PlanAssessment? = selectedPlan?.let(::assessment),
    confidence: AssessmentConfidence? = AssessmentConfidence(
        compatibility = Confidence.HIGH,
        memory = Confidence.MEDIUM,
        storage = Confidence.HIGH,
        performance = Confidence.LOW,
    ),
) = PersonalizedRecommendation(
    assessmentKey = "assessment",
    category = category,
    selectedPlan = selectedPlan,
    reasons = listOf(AssessmentReason.METADATA_VALIDATED),
    profile = RecommendationProfile(),
    confidence = confidence,
    memoryFit = memoryFit,
    storageFit = storageFit,
    selectedPlanAssessment = selectedPlanAssessment,
)

private val runnableCategories = setOf(
    RecommendationCategory.RECOMMENDED,
    RecommendationCategory.USABLE,
    RecommendationCategory.RISKY,
)

private data class TestPlan(override val stableKey: String) : PlanReference
private data class OtherTestPlan(override val stableKey: String) : PlanReference

private fun assessment(plan: PlanReference) = PlanAssessment(
    plan = plan,
    hostMemoryBytes = null,
    gpuMemoryBytes = null,
    sharedMemoryBytes = null,
    storageBytes = null,
    confidence = AssessmentConfidence(Confidence.HIGH, Confidence.HIGH, Confidence.HIGH, Confidence.HIGH),
    evidence = emptyList(),
)
