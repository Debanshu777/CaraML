package com.debanshu777.caraml.features.modelhub.domain

import com.debanshu777.caraml.core.recommendation.AssessmentConfidence
import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.Confidence
import com.debanshu777.caraml.core.recommendation.FitBand
import com.debanshu777.caraml.core.recommendation.PersonalizedRecommendation
import com.debanshu777.caraml.core.recommendation.RecommendationCategory
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
}

private fun recommendation(
    category: RecommendationCategory,
    memoryFit: FitBand? = FitBand.COMFORTABLE,
    storageFit: FitBand? = FitBand.COMFORTABLE,
) = PersonalizedRecommendation(
    assessmentKey = "assessment",
    category = category,
    selectedPlan = null,
    reasons = listOf(AssessmentReason.METADATA_VALIDATED),
    profile = RecommendationProfile(),
    confidence = AssessmentConfidence(
        compatibility = Confidence.HIGH,
        memory = Confidence.MEDIUM,
        storage = Confidence.HIGH,
        performance = Confidence.LOW,
    ),
    memoryFit = memoryFit,
    storageFit = storageFit,
)
