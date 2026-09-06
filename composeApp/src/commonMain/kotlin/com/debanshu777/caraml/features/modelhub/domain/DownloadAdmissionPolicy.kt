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

class DownloadAdmissionPolicy {
    fun decide(
        result: PersonalizedRecommendation,
        allowForLater: Boolean,
    ): DownloadAdmission {
        if (result.storageFit == null) {
            return DownloadAdmission.Blocked(AssessmentReason.STORAGE_BOUNDS_UNKNOWN)
        }
        if (result.storageFit == FitBand.NO_FIT) {
            return DownloadAdmission.Blocked(AssessmentReason.INSUFFICIENT_STORAGE)
        }
        return when (result.category) {
            RecommendationCategory.INCOMPATIBLE ->
                DownloadAdmission.Blocked(AssessmentReason.INCOMPATIBLE_MODEL)
            RecommendationCategory.NOT_SUITABLE -> if (allowForLater) {
                DownloadAdmission.ConfirmationRequired(AssessmentReason.DOWNLOAD_FOR_LATER)
            } else {
                DownloadAdmission.Blocked(AssessmentReason.NO_RUN_PLAN)
            }
            RecommendationCategory.NEEDS_INFORMATION ->
                DownloadAdmission.Blocked(result.reasons.firstOrNull() ?: AssessmentReason.MEMORY_BOUNDS_UNKNOWN)
            else -> DownloadAdmission.Allowed
        }
    }
}
