package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.di.appModule
import com.debanshu777.caraml.core.rating.SuitabilityRating
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.koin.dsl.koinApplication

class LegacySuitabilityAdapterTest {
    @Test
    fun v2CategoriesMapToExistingVisualTiers() {
        val adapter = LegacySuitabilityAdapter(RecommendationRolloutModeSource { RecommendationRolloutMode.V2 })

        val expected = mapOf(
            RecommendationCategory.RECOMMENDED to SuitabilityRating.BEST,
            RecommendationCategory.USABLE to SuitabilityRating.GOOD,
            RecommendationCategory.RISKY to SuitabilityRating.AVERAGE,
            RecommendationCategory.NOT_SUITABLE to SuitabilityRating.POOR,
            RecommendationCategory.INCOMPATIBLE to SuitabilityRating.POOR,
            RecommendationCategory.NEEDS_INFORMATION to SuitabilityRating.UNKNOWN,
        )

        expected.forEach { (category, rating) ->
            assertEquals(rating, adapter.toLegacyRating(task7Recommendation(category)))
        }
    }

    @Test
    fun legacyModeReturnsLegacyWithoutComputingV2() {
        var v2Calls = 0
        val adapter = LegacySuitabilityAdapter(RecommendationRolloutModeSource { RecommendationRolloutMode.LEGACY })

        val result = adapter.rating(
            identity = task7AdapterIdentity(),
            legacy = { SuitabilityRating.AVERAGE },
            v2 = {
                v2Calls += 1
                task7Recommendation(RecommendationCategory.RECOMMENDED)
            },
            estimatorVersion = 1,
        )

        assertEquals(SuitabilityRating.AVERAGE, result)
        assertEquals(0, v2Calls)
    }

    @Test
    fun shadowModeComputesBothReturnsLegacyAndRecordsOnlyBoundedSafeFields() {
        val records = mutableListOf<ShadowComparisonRecord>()
        var legacyCalls = 0
        var v2Calls = 0
        val adapter = LegacySuitabilityAdapter(
            modeSource = RecommendationRolloutModeSource { RecommendationRolloutMode.SHADOW },
            recorder = ShadowComparisonRecorder(records::add),
        )

        val result = adapter.rating(
            identity = task7AdapterIdentity(),
            legacy = {
                legacyCalls += 1
                SuitabilityRating.GOOD
            },
            v2 = {
                v2Calls += 1
                task7Recommendation(
                    RecommendationCategory.RISKY,
                    List(100) { AssessmentReason.MEMORY_BOUNDS_UNKNOWN },
                )
            },
            estimatorVersion = 1,
        )

        assertEquals(SuitabilityRating.GOOD, result)
        assertEquals(1, legacyCalls)
        assertEquals(1, v2Calls)
        val record = records.single()
        assertEquals("a".repeat(64), record.identityDigest)
        assertEquals(SuitabilityRating.GOOD, record.legacy)
        assertEquals(RecommendationCategory.RISKY, record.v2)
        assertEquals(List(RecommendationPolicyV1.MAX_ASSESSMENT_REASONS) { AssessmentReason.MEMORY_BOUNDS_UNKNOWN }, record.reasons)
        assertEquals(1, record.estimatorVersion)
        val rendered = record.toString()
        assertEquals(false, rendered.contains("private-owner"))
        assertEquals(false, rendered.contains("secret-model"))
        assertEquals(false, rendered.contains("private metadata payload"))
    }

    @Test
    fun v2ModeReturnsMappedV2WithoutComputingLegacy() {
        var legacyCalls = 0
        val adapter = LegacySuitabilityAdapter(RecommendationRolloutModeSource { RecommendationRolloutMode.V2 })

        val result = adapter.rating(
            identity = task7AdapterIdentity(),
            legacy = {
                legacyCalls += 1
                SuitabilityRating.POOR
            },
            v2 = { task7Recommendation(RecommendationCategory.RECOMMENDED) },
            estimatorVersion = 1,
        )

        assertEquals(SuitabilityRating.BEST, result)
        assertEquals(0, legacyCalls)
    }

    @Test
    fun buildTypeModeSourceKeepsReleaseLegacyAndDebugShadow() {
        assertEquals(
            RecommendationRolloutMode.LEGACY,
            DefaultRecommendationRolloutModeSource(isDebugBuild = false).current(),
        )
        assertEquals(
            RecommendationRolloutMode.SHADOW,
            DefaultRecommendationRolloutModeSource(isDebugBuild = true).current(),
        )
        assertEquals(
            RecommendationRolloutMode.LEGACY,
            DefaultRecommendationRolloutModeSource().current(),
        )
    }

    @Test
    fun appModuleProvidesReleaseSafeRolloutDependencies() {
        val application = koinApplication { modules(appModule) }
        try {
            assertEquals(
                RecommendationRolloutMode.LEGACY,
                application.koin.get<RecommendationRolloutModeSource>().current(),
            )
            assertNotNull(application.koin.get<LegacySuitabilityAdapter>())
        } finally {
            application.close()
        }
    }

    @Test
    fun unverifiedContentIdentifierPrefixIsNotAcceptedAsAStableDigest() {
        val records = mutableListOf<ShadowComparisonRecord>()
        val adapter = LegacySuitabilityAdapter(
            modeSource = RecommendationRolloutModeSource { RecommendationRolloutMode.SHADOW },
            recorder = ShadowComparisonRecorder(records::add),
        )

        adapter.rating(
            identity = task7AdapterIdentity(lfsOid = "private-prefix:${"b".repeat(64)}"),
            legacy = { SuitabilityRating.GOOD },
            v2 = { task7Recommendation(RecommendationCategory.USABLE) },
            estimatorVersion = 1,
        )

        assertEquals("unavailable", records.single().identityDigest)
    }

    @Test
    fun shadowV2OrdinaryFailureCannotChangeLegacyResult() {
        var recorderCalls = 0
        val adapter = LegacySuitabilityAdapter(
            modeSource = RecommendationRolloutModeSource { RecommendationRolloutMode.SHADOW },
            recorder = ShadowComparisonRecorder { recorderCalls += 1 },
        )

        val result = adapter.rating(
            identity = task7AdapterIdentity(),
            legacy = { SuitabilityRating.GOOD },
            v2 = { throw IllegalStateException("private-v2-payload") },
            estimatorVersion = 1,
        )

        assertEquals(SuitabilityRating.GOOD, result)
        assertEquals(0, recorderCalls)
    }

    @Test
    fun shadowRecorderOrdinaryFailureCannotChangeLegacyResult() {
        val adapter = LegacySuitabilityAdapter(
            modeSource = RecommendationRolloutModeSource { RecommendationRolloutMode.SHADOW },
            recorder = ShadowComparisonRecorder { throw IllegalStateException("private-recorder-payload") },
        )

        val result = adapter.rating(
            identity = task7AdapterIdentity(),
            legacy = { SuitabilityRating.AVERAGE },
            v2 = { task7Recommendation(RecommendationCategory.USABLE) },
            estimatorVersion = 1,
        )

        assertEquals(SuitabilityRating.AVERAGE, result)
    }

    @Test
    fun shadowCancellationIsRethrownByIdentityFromCalculationAndRecorder() {
        val calculationCancellation = kotlinx.coroutines.CancellationException("calculation-cancelled")
        val calculationAdapter = LegacySuitabilityAdapter(
            modeSource = RecommendationRolloutModeSource { RecommendationRolloutMode.SHADOW },
        )
        val calculationActual = try {
            calculationAdapter.rating(
                identity = task7AdapterIdentity(),
                legacy = { SuitabilityRating.GOOD },
                v2 = { throw calculationCancellation },
                estimatorVersion = 1,
            )
            error("expected calculation cancellation")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            cancelled
        }
        assertSame(calculationCancellation, calculationActual)

        val recorderCancellation = kotlinx.coroutines.CancellationException("recorder-cancelled")
        val recorderAdapter = LegacySuitabilityAdapter(
            modeSource = RecommendationRolloutModeSource { RecommendationRolloutMode.SHADOW },
            recorder = ShadowComparisonRecorder { throw recorderCancellation },
        )
        val recorderActual = try {
            recorderAdapter.rating(
                identity = task7AdapterIdentity(),
                legacy = { SuitabilityRating.GOOD },
                v2 = { task7Recommendation(RecommendationCategory.USABLE) },
                estimatorVersion = 1,
            )
            error("expected recorder cancellation")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            cancelled
        }
        assertSame(recorderCancellation, recorderActual)
    }

    @Test
    fun shadowFatalErrorIsNotSuppressed() {
        val fatal = AssertionError("fatal")
        val adapter = LegacySuitabilityAdapter(
            modeSource = RecommendationRolloutModeSource { RecommendationRolloutMode.SHADOW },
        )

        val actual = try {
            adapter.rating(
                identity = task7AdapterIdentity(),
                legacy = { SuitabilityRating.GOOD },
                v2 = { throw fatal },
                estimatorVersion = 1,
            )
            error("expected fatal error")
        } catch (error: AssertionError) {
            error
        }

        assertSame(fatal, actual)
        assertTrue(actual.message == "fatal")
    }
}

private fun task7Recommendation(
    category: RecommendationCategory,
    reasons: Collection<AssessmentReason> = listOf(AssessmentReason.MEMORY_BOUNDS_UNKNOWN),
) = PersonalizedRecommendation(
    assessmentKey = "safe-key",
    category = category,
    selectedPlan = null,
    reasons = reasons,
    profile = RecommendationProfile(),
)

private fun task7AdapterIdentity(
    lfsOid: String = "sha256:${"a".repeat(64)}",
) = ModelFileIdentity(
    repositoryId = "private-owner/private-model",
    revision = "0123456789abcdef0123456789abcdef01234567",
    path = "/private/user/models/secret-model.gguf",
    sizeBytes = 1_024,
    gitOid = null,
    lfsOid = lfsOid,
    xetHash = null,
    evidence = listOf(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "private metadata payload")),
)
