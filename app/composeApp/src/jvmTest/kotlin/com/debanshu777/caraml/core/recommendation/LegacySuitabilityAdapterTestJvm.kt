package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.di.appModule
import com.debanshu777.caraml.core.platform.AppLogger
import com.debanshu777.caraml.core.platform.LogLevel
import com.debanshu777.caraml.core.rating.SuitabilityRating
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.koin.dsl.koinApplication

class LegacySuitabilityAdapterTestJvm {
    @Test
    fun debugKoinDefaultRecorderEmitsSafeShadowRecordDespiteInfoLoggerThreshold() {
        val property = "caraml.recommendation.debugBuild"
        val previousProperty = System.getProperty(property)
        val previousLevel = AppLogger.minLevel
        val previousOut = System.out
        val output = ByteArrayOutputStream()
        var application: org.koin.core.KoinApplication? = null
        try {
            System.setProperty(property, "true")
            AppLogger.minLevel = LogLevel.INFO
            System.setOut(PrintStream(output, true, Charsets.UTF_8.name()))
            application = koinApplication { modules(appModule) }
            val adapter = application.koin.get<LegacySuitabilityAdapter>()

            val result = adapter.rating(
                identity = jvmShadowIdentity(),
                legacy = { SuitabilityRating.GOOD },
                v2 = { jvmShadowRecommendation() },
                estimatorVersion = 1,
            )

            assertEquals(SuitabilityRating.GOOD, result)
            val rendered = output.toString(Charsets.UTF_8.name())
            assertTrue(rendered.contains("shadow id=${"a".repeat(64)} old=GOOD new=RISKY"))
            assertTrue(rendered.contains("reasons=MEMORY_BOUNDS_UNKNOWN estimator=1"))
            assertFalse(rendered.contains("private-owner"))
            assertFalse(rendered.contains("secret-model"))
            assertFalse(rendered.contains("private metadata payload"))
        } finally {
            application?.close()
            System.setOut(previousOut)
            AppLogger.minLevel = previousLevel
            if (previousProperty == null) {
                System.clearProperty(property)
            } else {
                System.setProperty(property, previousProperty)
            }
        }
    }
}

private fun jvmShadowIdentity() = ModelFileIdentity(
    repositoryId = "private-owner/private-model",
    revision = "0123456789abcdef0123456789abcdef01234567",
    path = "/private/user/models/secret-model.gguf",
    sizeBytes = 1_024,
    gitOid = null,
    lfsOid = "sha256:${"a".repeat(64)}",
    xetHash = null,
    evidence = listOf(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "private metadata payload")),
)

private fun jvmShadowRecommendation() = PersonalizedRecommendation(
    assessmentKey = "safe-key",
    category = RecommendationCategory.RISKY,
    selectedPlan = null,
    reasons = listOf(AssessmentReason.MEMORY_BOUNDS_UNKNOWN),
    profile = RecommendationProfile(),
)
