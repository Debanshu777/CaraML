package com.debanshu777.caraml.features.modelhub.domain

import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.FitBand
import com.debanshu777.caraml.core.recommendation.PersonalizedRecommendation
import com.debanshu777.caraml.core.recommendation.RecommendationCategory

sealed interface DownloadAdmission {
    data object Allowed : DownloadAdmission
    data class ConfirmationRequired(val reason: AssessmentReason) : DownloadAdmission
    data class Blocked(val reason: AssessmentReason) : DownloadAdmission
}

internal class DownloadAdmissionRejected(
    val admission: DownloadAdmission,
) : Exception("Download admission requirements are not met")

internal fun DownloadAdmission.requireForComponent(downloadForLaterConfirmed: Boolean) {
    when (this) {
        DownloadAdmission.Allowed -> Unit
        is DownloadAdmission.ConfirmationRequired -> if (!downloadForLaterConfirmed) {
            throw DownloadAdmissionRejected(this)
        }
        is DownloadAdmission.Blocked -> throw DownloadAdmissionRejected(this)
    }
}

internal fun downloadAdmissionErrorMessage(admission: DownloadAdmission): String = when (admission) {
    is DownloadAdmission.Blocked -> when (admission.reason) {
        AssessmentReason.INSUFFICIENT_STORAGE -> "Not enough storage space for this download."
        else -> "This download is blocked because current device requirements are not met."
    }
    is DownloadAdmission.ConfirmationRequired -> "This download requires confirmation."
    DownloadAdmission.Allowed -> ""
}

class DownloadAdmissionPolicy {
    fun decide(
        result: PersonalizedRecommendation,
        allowForLater: Boolean,
    ): DownloadAdmission {
        if (result.storageFit == FitBand.NO_FIT) {
            return DownloadAdmission.Blocked(AssessmentReason.INSUFFICIENT_STORAGE)
        }
        if (result.category == RecommendationCategory.NEEDS_INFORMATION) {
            return DownloadAdmission.Allowed
        }
        if (result.category in runnableCategories && !result.hasCoherentRunnableProjection()) {
            return DownloadAdmission.Blocked(AssessmentReason.RECOMMENDATION_EVIDENCE_INCOMPLETE)
        }
        if (result.storageFit == null) {
            return DownloadAdmission.Blocked(AssessmentReason.STORAGE_BOUNDS_UNKNOWN)
        }
        return when (result.category) {
            RecommendationCategory.INCOMPATIBLE ->
                DownloadAdmission.Blocked(AssessmentReason.INCOMPATIBLE_MODEL)
            RecommendationCategory.NOT_SUITABLE -> if (allowForLater) {
                DownloadAdmission.ConfirmationRequired(AssessmentReason.DOWNLOAD_FOR_LATER)
            } else {
                DownloadAdmission.Blocked(AssessmentReason.NO_RUN_PLAN)
            }
            RecommendationCategory.NEEDS_INFORMATION -> DownloadAdmission.Allowed
            else -> DownloadAdmission.Allowed
        }
    }

    private fun PersonalizedRecommendation.hasCoherentRunnableProjection(): Boolean {
        val plan = selectedPlan ?: return false
        val assessment = selectedPlanAssessment ?: return false
        return confidence != null && memoryFit != null && storageFit != null &&
            memoryFit != FitBand.NO_FIT && assessment.plan.stableKey == plan.stableKey &&
            assessment.plan::class == plan::class
    }

    private companion object {
        val runnableCategories = setOf(
            RecommendationCategory.RECOMMENDED,
            RecommendationCategory.USABLE,
            RecommendationCategory.RISKY,
        )
    }
}
