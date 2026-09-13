@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.rating.ui.RecommendationStatusChip
import com.debanshu777.caraml.core.recommendation.PersonalizedRecommendation
import com.debanshu777.caraml.core.recommendation.RecommendationCategory
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.MessageRole
import com.debanshu777.caraml.features.chat.presentation.components.MessageBubble
import com.debanshu777.caraml.features.chat.presentation.components.ModelErrorScreen
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.features.modelhub.presentation.details.components.GgufFileListItem
import com.debanshu777.caraml.features.modelhub.presentation.details.components.InstallBundleCard
import com.debanshu777.caraml.features.modelhub.presentation.downloaded.components.LocalModelListItem
import com.debanshu777.caraml.features.modelhub.presentation.search.GgufFileUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.InstallBundleUiState
import kotlin.test.Test
import kotlin.test.assertEquals

class AuroraComponentsUiTest {

    @Test
    fun emptyStateExposesItsActionAndInvokesIt() = runComposeUiTest {
        var clicks = 0
        setContent {
            MaterialTheme {
                CaraMLEmptyState(
                    icon = Icons.Default.AutoAwesome,
                    title = "Think locally. Stay private.",
                    supportingText = "Your prompt and model stay on this device.",
                    actionLabel = "Browse models",
                    onAction = { clicks += 1 },
                )
            }
        }

        onNodeWithText("Think locally. Stay private.").assertIsDisplayed()
        onNodeWithText("Browse models")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun statusPillMeaningDoesNotDependOnColor() = runComposeUiTest {
        setContent {
            MaterialTheme {
                CaraMLStatusPill(
                    label = "Ready",
                    contentDescription = "Model ready for chat",
                    tone = StatusTone.Success,
                    icon = Icons.Default.CheckCircle,
                )
            }
        }

        onNodeWithContentDescription("Model ready for chat")
            .assertIsDisplayed()
            .assertTextEquals("Ready")
    }

    @Test
    fun emptyStateTitleAndActionRemainVisibleAtTwoHundredPercentFontScale() = runComposeUiTest {
        setContent {
            AtTwoHundredPercentFontScale {
                MaterialTheme {
                    Box(Modifier.width(320.dp).height(360.dp)) {
                        CaraMLEmptyState(
                            icon = Icons.Default.AutoAwesome,
                            title = "Think locally. Stay private.",
                            supportingText = "Your prompt and model stay on this device.",
                            actionLabel = "Browse models",
                            onAction = {},
                        )
                    }
                }
            }
        }

        onNodeWithText("Think locally. Stay private.").assertIsDisplayed()
        onNodeWithText("Browse models").assertIsDisplayed()
    }

    @Test
    fun localModelStateMappingsExposeNonColorMeaning() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Column {
                    LocalModelStatusFixture(
                        id = "ready-model",
                        filename = "ready.gguf",
                        componentStatus = LocalModelEntity.STATUS_READY,
                    )
                    LocalModelStatusFixture(
                        id = "partial-model",
                        filename = "partial.gguf",
                        componentStatus = LocalModelEntity.STATUS_PARTIAL,
                    )
                    LocalModelStatusFixture(
                        id = "unsupported-model",
                        filename = "unsupported.bin",
                        componentStatus = null,
                    )
                }
            }
        }

        listOf(
            "Ready" to "Ready for chat.",
            "Needs setup" to "Partial download. Missing components need setup.",
            "Unsupported" to "Unsupported. Chat is not available for this model type.",
        ).forEach { (label, description) ->
            onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithContentDescription(description).assertIsDisplayed()
        }
    }

    @Test
    fun recommendationStateMappingsExposeNonColorMeaning() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Column {
                    RecommendationStatusChip(
                        state = DescriptorState.ASSESSED,
                        recommendation = recommendation(RecommendationCategory.RECOMMENDED),
                    )
                    RecommendationStatusChip(
                        state = DescriptorState.ASSESSED,
                        recommendation = recommendation(RecommendationCategory.RISKY),
                    )
                }
            }
        }

        listOf("Recommended", "Risky").forEach { label ->
            onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithContentDescription(
                "$label. Unavailable confidence. No reason available.",
            ).assertIsDisplayed()
        }
    }

    @Test
    fun downloadingStateMappingExposesNonColorMeaning() = runComposeUiTest {
        setContent {
            MaterialTheme {
                GgufFileListItem(
                    filename = "weights.gguf",
                    sizeBytes = 1_024L,
                    isDownloaded = false,
                    progress = 42f,
                    isDownloading = true,
                    onDownloadClick = {},
                )
            }
        }

        onNodeWithText("42%", useUnmergedTree = true).assertIsDisplayed()
        onNode(hasStateDescription("Downloading") and hasText("42%")).assertIsDisplayed()
    }

    @Test
    fun generatingStateMappingExposesNonColorMeaning() = runComposeUiTest {
        setContent {
            MaterialTheme {
                MessageBubble(
                    message = ChatMessage(
                        id = "generating-message",
                        role = MessageRole.Assistant,
                        text = "",
                    ),
                    showMediaPending = true,
                    imageGenStep = 3,
                    imageGenTotalSteps = 10,
                    imageGenElapsedSeconds = 2,
                )
            }
        }

        onNodeWithText("Step 3 / 10  ·  2s", useUnmergedTree = true).assertIsDisplayed()
        onNode(
            hasStateDescription("Generating") and hasText("Step 3 / 10  ·  2s"),
        ).assertIsDisplayed()
    }

    @Test
    fun successStateMappingExposesNonColorMeaning() = runComposeUiTest {
        setContent {
            MaterialTheme {
                InstallBundleCard(
                    modelId = "org/downloaded-model",
                    state = InstallBundleUiState(
                        variants = listOf(
                            GgufFileUiState(
                                path = "weights.gguf",
                                filename = "weights.gguf",
                                sizeBytes = 1_024L,
                                isDownloaded = true,
                                progress = null,
                            ),
                        ),
                        isReady = true,
                    ),
                    familyLabel = null,
                    modelDescription = null,
                    onVariantSelected = {},
                    onInstall = {},
                )
            }
        }

        onNodeWithText("Model downloaded", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithContentDescription("Model downloaded").assertIsDisplayed()
    }

    @Test
    fun errorStateMappingExposesNonColorMeaning() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ModelErrorScreen(
                    errorMessage = "Runtime rejected this model.",
                    onTryAnotherModelClick = {},
                )
            }
        }

        onNodeWithText("Unable to load model", useUnmergedTree = true).assertIsDisplayed()
        onNode(hasStateDescription("Error") and hasText("Unable to load model")).assertIsDisplayed()
    }
}

@Composable
private fun LocalModelStatusFixture(
    id: String,
    filename: String,
    componentStatus: String?,
) {
    LocalModelListItem(
        model = LocalModelEntity(
            modelId = id,
            filename = filename,
            localPath = "/models/$filename",
            sizeBytes = 1_024L,
            downloadedAt = 0L,
            author = null,
            libraryName = null,
            pipelineTag = if (filename.endsWith(".bin")) "audio-classification" else null,
            componentStatus = componentStatus,
        ),
        selectionMode = false,
        isSelected = false,
        onOpenModel = {},
        onToggleSelect = {},
        onLongPress = {},
    )
}

private fun recommendation(category: RecommendationCategory) = PersonalizedRecommendation(
    assessmentKey = "${category.name.lowercase()}-assessment",
    category = category,
    selectedPlan = null,
    reasons = emptyList(),
    profile = RecommendationProfile(),
)

private fun hasStateDescription(description: String) =
    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description)

@Composable
private fun AtTwoHundredPercentFontScale(content: @Composable () -> Unit) {
    val current = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(current.density, fontScale = 2f),
        content = content,
    )
}
