@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.core.rating.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.runComposeUiTest
import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.CalibrationRunResult
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.recommendation.RiskTolerance
import com.debanshu777.caraml.core.recommendation.llmWorkload
import com.debanshu777.caraml.core.recommendation.recommendation
import com.debanshu777.caraml.core.recommendation.LlmRunPlan
import com.debanshu777.caraml.core.recommendation.KvCacheType
import com.debanshu777.caraml.core.recommendation.RunPlanCompromise
import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.features.modelhub.presentation.search.components.RecommendationProfileDialog
import com.debanshu777.caraml.features.modelhub.presentation.search.components.QuickCalibrationDialog
import com.debanshu777.caraml.features.modelhub.presentation.search.QuickCalibrationUiState
import com.debanshu777.caraml.features.modelhub.presentation.details.DownloadForLaterConfirmationDialog
import kotlin.test.assertTrue
import kotlin.test.Test

class RecommendationComponentsUiTest {
    @Test
    fun onboardingAnnouncesTheSelectedBalancedValues() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RecommendationProfileDialog(
                    initial = RecommendationProfile(),
                    onContinue = {},
                    onDismissWithBalanced = {},
                )
            }
        }

        onAllNodesWithContentDescription(
            "Selected risk: Balanced. Selected priority: Balanced.",
            substring = false,
            useUnmergedTree = false,
        )[0].assertIsDisplayed()
    }

    @Test
    fun recommendationChipProgressesFromCheckingToCategory() = runComposeUiTest {
        var state by mutableStateOf(DescriptorState.CHECKING)
        setContent {
            MaterialTheme {
                RecommendationStatusChip(
                    state = state,
                    recommendation = if (state == DescriptorState.ASSESSED) recommendation() else null,
                )
            }
        }

        onNodeWithText("Checking").assertIsDisplayed()
        runOnIdle { state = DescriptorState.ASSESSED }
        onNodeWithText("Recommended").assertIsDisplayed()
    }

    @Test
    fun chipSemanticsDoNotDependOnColor() = runComposeUiTest {
        val recommendation = recommendation(
            reasons = listOf(AssessmentReason.TIGHT_MEMORY_FIT),
        )
        setContent { MaterialTheme { SuitabilityChip(recommendation = recommendation) } }

        onNodeWithContentDescription(
            "Recommended. Medium confidence. Tight memory fit.",
        ).assertContentDescriptionEquals(
            "Recommended. Medium confidence. Tight memory fit.",
        )
    }

    @Test
    fun experimentalTightFitWarningAndDetailsAreVisible() = runComposeUiTest {
        val fallback = LlmRunPlan(
            contextTokens = 1_024,
            batchSize = 64,
            microBatchSize = 32,
            sequenceCount = 1,
            keyCacheType = KvCacheType.Q8_0,
            valueCacheType = KvCacheType.Q8_0,
            backend = BackendKind.CPU,
            memoryTopology = MemoryTopology.DISCRETE,
            gpuLayerCount = 0,
            compromises = listOf(RunPlanCompromise.CONTEXT_REDUCED),
        )
        val recommendation = recommendation(
            reasons = listOf(AssessmentReason.TIGHT_MEMORY_FIT, AssessmentReason.SPEED_NOT_VERIFIED),
            profile = RecommendationProfile(riskTolerance = RiskTolerance.EXPERIMENTAL),
            fallbackPlan = fallback,
        )
        val presentation = recommendationPresentation(
            recommendation = recommendation,
            selectedVariant = "model-q4.gguf",
            workload = llmWorkload(),
        )
        setContent {
            MaterialTheme {
                RecommendationDetailsContent(
                    modelId = "org/model",
                    recommendation = recommendation,
                    presentation = presentation,
                    modifier = Modifier.height(180.dp),
                )
            }
        }

        onNodeWithText("Experimental tight fit", substring = true).assertIsDisplayed()
        onNodeWithText("Confidence").performScrollTo().assertIsDisplayed()
        onNodeWithText("Medium").performScrollTo().assertIsDisplayed()
        onNodeWithText("Fallback plan").performScrollTo().assertIsDisplayed()
        onNodeWithText("1,024", substring = true).performScrollTo().assertIsDisplayed()
        onNodeWithText("model-q4.gguf", substring = true).performScrollTo().assertIsDisplayed()
        onNodeWithText("4,096", substring = true).performScrollTo().assertIsDisplayed()
        onAllNodesWithText("Speed not verified", substring = true)[0].performScrollTo().assertIsDisplayed()
    }

    @Test
    fun downloadForLaterRequiresAnExplicitConfirmationAction() = runComposeUiTest {
        var confirmed = false
        setContent {
            MaterialTheme {
                DownloadForLaterConfirmationDialog(
                    onConfirm = { confirmed = true },
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("Download for later").assertIsDisplayed()
        onNodeWithText("Download anyway").performClick()
        runOnIdle { assertTrue(confirmed) }
    }

    @Test
    fun quarantinedCalibrationExplainsRestartAndDisablesRetry() = runComposeUiTest {
        setContent {
            MaterialTheme {
                QuickCalibrationDialog(
                    state = QuickCalibrationUiState(result = CalibrationRunResult.Quarantined),
                    onRun = {},
                    onRunWithUnknownPower = {},
                    onCancel = {},
                    onSkip = {},
                )
            }
        }

        onNodeWithText("Native work may still be running", substring = true).assertIsDisplayed()
        onNodeWithText("Restart CaraML", substring = true).assertIsDisplayed()
        onNodeWithText("Run calibration").assertIsNotEnabled()
    }
}
