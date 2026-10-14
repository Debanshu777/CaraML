package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.LogLevel
import com.debanshu777.caraml.core.platform.platformLog
import com.debanshu777.caraml.core.rating.SuitabilityRating
import kotlinx.coroutines.CancellationException

@ConsistentCopyVisibility
data class ShadowComparisonRecord private constructor(
    val identityDigest: String,
    val legacy: SuitabilityRating,
    val v2: RecommendationCategory,
    val reasons: List<AssessmentReason>,
    val estimatorVersion: Int,
) {
    companion object {
        internal fun create(
            identityDigest: String,
            legacy: SuitabilityRating,
            v2: RecommendationCategory,
            reasons: Collection<AssessmentReason>,
            estimatorVersion: Int,
        ): ShadowComparisonRecord = ShadowComparisonRecord(
            identityDigest = identityDigest.take(MAX_IDENTITY_DIGEST_LENGTH),
            legacy = legacy,
            v2 = v2,
            reasons = reasons.take(RecommendationPolicyV1.MAX_ASSESSMENT_REASONS),
            estimatorVersion = estimatorVersion.coerceIn(0, MAX_LOGGED_ESTIMATOR_VERSION),
        )

        private const val MAX_IDENTITY_DIGEST_LENGTH = 64
        private const val MAX_LOGGED_ESTIMATOR_VERSION = 1_000_000
    }
}

fun interface ShadowComparisonRecorder {
    fun record(value: ShadowComparisonRecord)
}

class LegacySuitabilityAdapter(
    private val modeSource: RecommendationRolloutModeSource = DefaultRecommendationRolloutModeSource(),
    private val recorder: ShadowComparisonRecorder = PlatformShadowComparisonRecorder,
) {
    fun toLegacyRating(recommendation: PersonalizedRecommendation): SuitabilityRating = when (recommendation.category) {
        RecommendationCategory.RECOMMENDED -> SuitabilityRating.BEST
        RecommendationCategory.USABLE -> SuitabilityRating.GOOD
        RecommendationCategory.RISKY -> SuitabilityRating.AVERAGE
        RecommendationCategory.NOT_SUITABLE,
        RecommendationCategory.INCOMPATIBLE,
        -> SuitabilityRating.POOR
        RecommendationCategory.NEEDS_INFORMATION -> SuitabilityRating.UNKNOWN
    }

    fun rating(
        identity: ModelFileIdentity,
        legacy: () -> SuitabilityRating,
        v2: () -> PersonalizedRecommendation,
        estimatorVersion: Int,
    ): SuitabilityRating = when (modeSource.current()) {
        RecommendationRolloutMode.LEGACY -> legacy()
        RecommendationRolloutMode.V2 -> toLegacyRating(v2())
        RecommendationRolloutMode.SHADOW -> {
            val legacyResult = legacy()
            try {
                val v2Result = v2()
                recorder.record(
                    ShadowComparisonRecord.create(
                        identityDigest = identity.stableContentDigest(),
                        legacy = legacyResult,
                        v2 = v2Result.category,
                        reasons = v2Result.reasons,
                        estimatorVersion = estimatorVersion,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Shadow comparison is observational; legacy output remains authoritative.
            }
            legacyResult
        }
    }
}

private data object PlatformShadowComparisonRecorder : ShadowComparisonRecorder {
    override fun record(value: ShadowComparisonRecord) {
        platformLog(
            level = LogLevel.DEBUG,
            tag = TAG,
            message = "shadow id=${value.identityDigest} old=${value.legacy.name} new=${value.v2.name} " +
                "reasons=${value.reasons.joinToString(",") { it.name }} estimator=${value.estimatorVersion}",
            throwable = null,
        )
    }

    private const val TAG = "ModelRecommendation"
}

internal fun ModelFileIdentity.stableContentDigest(): String {
    val candidates = listOfNotNull(lfsOid, xetHash, gitOid)
    for (candidate in candidates) {
        if (candidate.length > MAX_PREFIXED_CONTENT_ID_LENGTH) continue
        val normalized = when {
            ':' !in candidate -> candidate
            candidate.startsWith(SHA256_PREFIX) -> candidate.removePrefix(SHA256_PREFIX)
            else -> continue
        }.lowercase()
        if (normalized.length in 40..64 && normalized.all { it in '0'..'9' || it in 'a'..'f' }) {
            return normalized.padStart(64, '0')
        }
    }
    return UNAVAILABLE_IDENTITY_DIGEST
}

private const val UNAVAILABLE_IDENTITY_DIGEST = "unavailable"
private const val SHA256_PREFIX = "sha256:"
private const val MAX_PREFIXED_CONTENT_ID_LENGTH = 71
